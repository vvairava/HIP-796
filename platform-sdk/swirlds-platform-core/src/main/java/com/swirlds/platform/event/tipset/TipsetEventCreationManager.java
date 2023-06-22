/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.event.tipset;

import static com.swirlds.base.state.LifecyclePhase.NOT_STARTED;
import static com.swirlds.base.state.LifecyclePhase.STARTED;
import static com.swirlds.base.state.LifecyclePhase.STOPPED;

import com.swirlds.base.state.Lifecycle;
import com.swirlds.base.state.LifecyclePhase;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.SoftwareVersion;
import com.swirlds.common.system.address.AddressBook;
import com.swirlds.common.threading.framework.BlockingQueueInserter;
import com.swirlds.common.threading.framework.MultiQueueThread;
import com.swirlds.common.threading.framework.config.MultiQueueThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.platform.components.transaction.TransactionSupplier;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Manages the creation of events.
 */
public class TipsetEventCreationManager implements Lifecycle {

    /**
     * Tracks the lifecycle of this object.
     */
    private LifecyclePhase lifecyclePhase = NOT_STARTED;

    /**
     * The core logic for creating events.
     */
    private final TipsetEventCreator eventCreator;

    /**
     * Contains tasks that need to be run on the processing thread for this component.
     */
    private final MultiQueueThread workQueue;

    /**
     * The object used to enqueue new events onto the work queue.
     */
    private final BlockingQueueInserter<EventImpl> eventInserter;

    /**
     * The object used to enqueue updates to the minimum generation non-ancient onto the work queue.
     */
    private final BlockingQueueInserter<Long> minimumGenerationNonAncientInserter;

    /**
     * When the event creator makes a new event, pass it to this lambda.
     */
    private final Consumer<GossipEvent> newEventHandler;

    /**
     * Prevents events from being created too fast. Maximum speed is specified in configuration.
     */
    private final RateLimiter rateLimiter;

    /**
     * Prevents the creation of new events under various miscellaneous conditions.
     */
    private final TipsetEventCreationBlocker eventCreationBlocker;

    /**
     * Constructor.
     *
     * @param platformContext      the platform's context
     * @param threadManager        manages the creation of new threads
     * @param time                 provides the wall clock time
     * @param random               a source of randomness, does not need to be cryptographically secure
     * @param signer               can sign with this node's key
     * @param addressBook          the current address book
     * @param selfId               the ID of this node
     * @param softwareVersion      the current software version
     * @param transactionSupplier  provides transactions to be included in new events
     * @param newEventHandler      called when a new event is created
     * @param eventCreationBlocker prevents events from being created at various times
     */
    public TipsetEventCreationManager(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Time time,
            @NonNull final Random random,
            @NonNull final Signer signer,
            @NonNull final AddressBook addressBook,
            @NonNull final NodeId selfId,
            @NonNull final SoftwareVersion softwareVersion,
            @NonNull final TransactionSupplier transactionSupplier,
            @NonNull final Consumer<GossipEvent> newEventHandler,
            @NonNull final TipsetEventCreationBlocker eventCreationBlocker) {

        this.newEventHandler = Objects.requireNonNull(newEventHandler);
        this.eventCreationBlocker = Objects.requireNonNull(eventCreationBlocker);

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(threadManager);
        Objects.requireNonNull(time);
        Objects.requireNonNull(random);
        Objects.requireNonNull(signer);
        Objects.requireNonNull(addressBook);
        Objects.requireNonNull(selfId);
        Objects.requireNonNull(softwareVersion);
        Objects.requireNonNull(transactionSupplier);

        final EventCreationConfig eventCreationConfig =
                platformContext.getConfiguration().getConfigData(EventCreationConfig.class);

        eventCreator = new TipsetEventCreator(
                platformContext, time, random, signer, addressBook, selfId, softwareVersion, transactionSupplier);

        workQueue = new MultiQueueThreadConfiguration(threadManager)
                .setThreadName("event-creator")
                .setCapacity(eventCreationConfig.creationQueueSize())
                .setMaxBufferSize(eventCreationConfig.creationQueueBufferSize())
                .addHandler(EventImpl.class, this::handleEvent)
                .addHandler(Long.class, this::handleMinimumGenerationNonAncient)
                .setIdleCallback(this::maybeCreateEvent)
                .setBatchHandledCallback(this::maybeCreateEvent)
                .setWaitForWorkDuration(eventCreationConfig.creationQueueWaitForWorkPeriod())
                .build();

        final double maxCreationRate = eventCreationConfig.maxCreationRate();
        if (maxCreationRate > 0) {
            rateLimiter = new RateLimiter(time, maxCreationRate);
        } else {
            // No brakes!
            rateLimiter = null;
        }

        eventInserter = workQueue.getInserter(EventImpl.class);
        minimumGenerationNonAncientInserter = workQueue.getInserter(Long.class);
    }

    /**
     * Add an event from the event intake to the work queue. A background thread will eventually pass this event to the
     * event creator on the processing thread.
     *
     * @param event the event to add
     */
    public void registerEvent(@NonNull final EventImpl event) throws InterruptedException {
        eventInserter.put(event);
    }

    /**
     * Update the minimum generation non-ancient
     *
     * @param minimumGenerationNonAncient the new minimum generation non-ancient
     */
    public void setMinimumGenerationNonAncient(final long minimumGenerationNonAncient) throws InterruptedException {
        minimumGenerationNonAncientInserter.put(minimumGenerationNonAncient);
    }

    /**
     * Take an event from the work queue and pass it into the event creator.
     *
     * @param event the event to pass
     */
    private void handleEvent(@NonNull final EventImpl event) {
        eventCreator.registerEvent(event);
    }

    /**
     * Pass a new minimum generation non-ancient into the event creator.
     *
     * @param minimumGenerationNonAncient the new minimum generation non-ancient
     */
    private void handleMinimumGenerationNonAncient(final long minimumGenerationNonAncient) {
        eventCreator.setMinimumGenerationNonAncient(minimumGenerationNonAncient);
    }

    /**
     * Create a new event if it is legal to do so.
     */
    private void maybeCreateEvent() {
        if (!eventCreationBlocker.isEventCreationPermitted()) {
            // Event creation is currently not permitted.
            return;
        }

        if (rateLimiter != null && !rateLimiter.request()) {
            // We have created a self event too recently
            return;
        }

        final GossipEvent event = eventCreator.maybeCreateEvent();
        if (event != null) {
            newEventHandler.accept(event);

            if (rateLimiter != null) {
                rateLimiter.trigger();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public LifecyclePhase getLifecyclePhase() {
        return lifecyclePhase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        throwIfNotInPhase(NOT_STARTED);
        lifecyclePhase = STARTED;
        workQueue.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        throwIfNotInPhase(STARTED);
        lifecyclePhase = STOPPED;
        workQueue.stop();
    }
}
