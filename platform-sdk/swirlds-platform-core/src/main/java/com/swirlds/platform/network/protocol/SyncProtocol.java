/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.network.protocol;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.modular.SyncGossipSharedProtocolState;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.protocol.SyncPeerProtocol;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.system.status.PlatformStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.hiero.consensus.gossip.FallenBehindManager;

/**
 * Implementation of a factory for sync protocol
 */
public class SyncProtocol implements Protocol {

    private final PlatformContext platformContext;
    private final ShadowgraphSynchronizer synchronizer;
    private final FallenBehindManager fallenBehindManager;
    private final SyncPermitProvider permitProvider;
    private final IntakeEventCounter intakeEventCounter;
    private final BooleanSupplier gossipHalted;
    private final Duration sleepAfterSync;
    private final SyncMetrics syncMetrics;
    private final Supplier<PlatformStatus> platformStatusSupplier;

    /**
     * Constructs a new sync protocol
     *
     * @param platformContext        the platform context
     * @param synchronizer           the shadow graph synchronizer, responsible for actually doing the sync
     * @param fallenBehindManager    manager to determine whether this node has fallen behind
     * @param permitProvider         provides permits to sync
     * @param intakeEventCounter     keeps track of how many events have been received from each peer
     * @param gossipHalted           returns true if gossip is halted, false otherwise
     * @param sleepAfterSync         the amount of time to sleep after a sync
     * @param syncMetrics            metrics tracking syncing
     * @param platformStatusSupplier provides the current platform status
     */
    public SyncProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ShadowgraphSynchronizer synchronizer,
            @NonNull final FallenBehindManager fallenBehindManager,
            @NonNull final SyncPermitProvider permitProvider,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final BooleanSupplier gossipHalted,
            @NonNull final Duration sleepAfterSync,
            @NonNull final SyncMetrics syncMetrics,
            @NonNull final Supplier<PlatformStatus> platformStatusSupplier) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.synchronizer = Objects.requireNonNull(synchronizer);
        this.fallenBehindManager = Objects.requireNonNull(fallenBehindManager);
        this.permitProvider = Objects.requireNonNull(permitProvider);
        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.gossipHalted = Objects.requireNonNull(gossipHalted);
        this.sleepAfterSync = Objects.requireNonNull(sleepAfterSync);
        this.syncMetrics = Objects.requireNonNull(syncMetrics);
        this.platformStatusSupplier = Objects.requireNonNull(platformStatusSupplier);
    }

    /**
     * Utility method for creating SyncProtocol from shared state, while staying compatible with pre-refactor code
     * @param platformContext       the platform context
     * @param sharedState           temporary class to share state between various protocols in modularized gossip, to be removed
     * @param intakeEventCounter    keeps track of how many events have been received from each peer
     * @param roster                the current roster
     * @return constructed SyncProtocol
     */
    public static SyncProtocol create(
            @NonNull final PlatformContext platformContext,
            @NonNull final SyncGossipSharedProtocolState sharedState,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Roster roster) {

        final SyncMetrics syncMetrics = new SyncMetrics(platformContext.getMetrics());

        var syncShadowgraphSynchronizer = new ShadowgraphSynchronizer(
                platformContext,
                sharedState.shadowgraph(),
                roster.rosterEntries().size(),
                syncMetrics,
                event -> sharedState.receivedEventHandler().accept(event),
                sharedState.syncManager(),
                intakeEventCounter,
                sharedState.shadowgraphExecutor());

        return new SyncProtocol(
                platformContext,
                syncShadowgraphSynchronizer,
                sharedState.syncManager(),
                sharedState.syncPermitProvider(),
                intakeEventCounter,
                sharedState.gossipHalted()::get,
                Duration.ZERO,
                syncMetrics,
                sharedState.currentPlatformStatus()::get);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SyncPeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        return new SyncPeerProtocol(
                platformContext,
                Objects.requireNonNull(peerId),
                synchronizer,
                fallenBehindManager,
                permitProvider,
                intakeEventCounter,
                gossipHalted,
                sleepAfterSync,
                syncMetrics,
                platformStatusSupplier);
    }
}
