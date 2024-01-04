/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.formatting.UnitFormatter;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.threading.framework.QueueThread;
import com.swirlds.common.threading.interrupt.InterruptableConsumer;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.eventhandling.ConsensusRoundHandler;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class encapsulates the logic for replaying preconsensus events at boot up time.
 */
public final class PreconsensusEventReplayWorkflow {

    private static final Logger logger = LogManager.getLogger(PreconsensusEventReplayWorkflow.class);

    private PreconsensusEventReplayWorkflow() {}

    /**
     * Replays preconsensus events from disk.
     *
     * @param platformContext                    the platform context for this node
     * @param threadManager                      the thread manager for this node
     * @param preconsensusEventFileManager       manages the preconsensus event files on disk
     * @param preconsensusEventWriter            writes preconsensus events to disk
     * @param intakeHandler                      validates events and passes valid events further down the pipeline
     * @param intakeQueue                        the queue thread for the event intake component
     * @param consensusRoundHandler              the object responsible for applying transactions to consensus rounds
     * @param stateHashSignQueue                 the queue thread for hashing and signing states
     * @param initialMinimumGenerationNonAncient the minimum generation of events to replay
     * @param latestImmutableState               provides the latest immutable state if available
     * @param flushIntakePipeline                flushes the intake pipeline. only used if the new intake pipeline is
     *                                           enabled
     */
    public static void replayPreconsensusEvents(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Time time,
            @NonNull final PreconsensusEventFileManager preconsensusEventFileManager,
            @NonNull final PreconsensusEventWriter preconsensusEventWriter,
            @NonNull final InterruptableConsumer<GossipEvent> intakeHandler,
            @NonNull final QueueThread<GossipEvent> intakeQueue,
            @NonNull final ConsensusRoundHandler consensusRoundHandler,
            @NonNull final QueueThread<ReservedSignedState> stateHashSignQueue,
            final long initialMinimumGenerationNonAncient,
            @NonNull final Supplier<ReservedSignedState> latestImmutableState,
            @NonNull Runnable flushIntakePipeline) {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(threadManager);
        Objects.requireNonNull(time);
        Objects.requireNonNull(preconsensusEventFileManager);
        Objects.requireNonNull(preconsensusEventWriter);
        Objects.requireNonNull(intakeHandler);
        Objects.requireNonNull(intakeQueue);
        Objects.requireNonNull(consensusRoundHandler);
        Objects.requireNonNull(stateHashSignQueue);
        Objects.requireNonNull(latestImmutableState);

        logger.info(
                STARTUP.getMarker(),
                "replaying preconsensus event stream starting at generation {}",
                initialMinimumGenerationNonAncient);

        try {
            final Instant start = time.now();
            final Instant firstStateTimestamp;
            final long firstStateRound;
            try (final ReservedSignedState startState = latestImmutableState.get()) {
                if (startState == null || startState.isNull()) {
                    firstStateTimestamp = null;
                    firstStateRound = -1;
                } else {
                    firstStateTimestamp = startState.get().getConsensusTimestamp();
                    firstStateRound = startState.get().getRound();
                }
            }

            final IOIterator<GossipEvent> iterator =
                    preconsensusEventFileManager.getEventIterator(initialMinimumGenerationNonAncient);

            final PreconsensusEventReplayPipeline eventReplayPipeline =
                    new PreconsensusEventReplayPipeline(platformContext, threadManager, iterator, intakeHandler);
            eventReplayPipeline.replayEvents();

            final boolean useLegacyIntake = platformContext
                    .getConfiguration()
                    .getConfigData(EventConfig.class)
                    .useLegacyIntake();

            waitForReplayToComplete(
                    intakeQueue, consensusRoundHandler, stateHashSignQueue, useLegacyIntake, flushIntakePipeline);

            final Instant finish = time.now();
            final Duration elapsed = Duration.between(start, finish);

            logReplayInfo(
                    firstStateTimestamp,
                    firstStateRound,
                    latestImmutableState,
                    eventReplayPipeline.getEventCount(),
                    eventReplayPipeline.getTransactionCount(),
                    elapsed);

            preconsensusEventWriter.beginStreamingNewEvents();

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while replaying preconsensus event stream", e);
        }
    }

    /**
     * Wait for all events to be replayed. Some of this work happens on asynchronous threads, so we need to wait for them
     * to complete even after we exhaust all available events from the stream.
     */
    private static void waitForReplayToComplete(
            @NonNull final QueueThread<GossipEvent> intakeQueue,
            @NonNull final ConsensusRoundHandler consensusRoundHandler,
            @NonNull final QueueThread<ReservedSignedState> stateHashSignQueue,
            final boolean useLegacyIntake,
            @NonNull final Runnable flushIntakePipeline)
            throws InterruptedException {

        // Wait until all events from the preconsensus event stream have been fully ingested.
        intakeQueue.waitUntilNotBusy();

        if (!useLegacyIntake) {
            // The old intake has an empty intake pipeline as soon as the intake queue is empty.
            // The new intake has more steps to the intake pipeline, so we need to flush it before certifying that
            // the replay is complete.
            flushIntakePipeline.run();
        }

        // Wait until all rounds from the preconsensus event stream have been fully processed.
        consensusRoundHandler.waitUntilNotBusy();

        // Wait until we have hashed/signed all rounds
        stateHashSignQueue.waitUntilNotBusy();
    }

    /**
     * Write information about the replay to disk.
     */
    private static void logReplayInfo(
            @Nullable final Instant firstTimestamp,
            final long firstRound,
            @NonNull final Supplier<ReservedSignedState> latestImmutableState,
            final long eventCount,
            final long transactionCount,
            @NonNull final Duration elapsedTime) {

        try (final ReservedSignedState latestConsensusRound = latestImmutableState.get()) {

            if (latestConsensusRound == null || latestConsensusRound.isNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        "Replayed {} preconsensus events. No rounds reached consensus.",
                        commaSeparatedNumber(eventCount));
                return;
            }

            if (firstTimestamp == null) {
                // This should be impossible. If we have a state, we should have a timestamp.
                logger.error(
                        EXCEPTION.getMarker(),
                        "Replayed {} preconsensus events. "
                                + "First state timestamp is null, which should not be possible if a "
                                + "round has reached consensus",
                        commaSeparatedNumber(eventCount));
                return;
            }

            final long latestRound = latestConsensusRound.get().getRound();
            final long elapsedRounds = latestRound - firstRound;

            final Instant latestRoundTimestamp = latestConsensusRound.get().getConsensusTimestamp();
            final Duration elapsedConsensusTime = Duration.between(firstTimestamp, latestRoundTimestamp);

            logger.info(
                    STARTUP.getMarker(),
                    "replayed {} preconsensus events. These events contained {} transactions. "
                            + "{} rounds reached consensus spanning {} of consensus time. The latest "
                            + "round to reach consensus is round {}. Replay took {}.",
                    commaSeparatedNumber(eventCount),
                    commaSeparatedNumber(transactionCount),
                    commaSeparatedNumber(elapsedRounds),
                    new UnitFormatter(elapsedConsensusTime.toMillis(), UNIT_MILLISECONDS)
                            .setAbbreviate(false)
                            .render(),
                    commaSeparatedNumber(latestRound),
                    new UnitFormatter(elapsedTime.toMillis(), UNIT_MILLISECONDS)
                            .setAbbreviate(false)
                            .render());
        }
    }
}
