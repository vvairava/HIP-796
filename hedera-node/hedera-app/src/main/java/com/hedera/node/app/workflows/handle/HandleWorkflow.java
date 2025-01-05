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

package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCK_INFO_STATE_KEY;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.BLOBS_KEY;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartEvent;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartRound;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartUserTransaction;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartUserTransactionPreHandleResultP2;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartUserTransactionPreHandleResultP3;
import static com.hedera.node.app.state.merkle.VersionUtils.isSoOrdered;
import static com.hedera.node.app.workflows.handle.TransactionType.GENESIS_TRANSACTION;
import static com.hedera.node.app.workflows.handle.TransactionType.ORDINARY_TRANSACTION;
import static com.hedera.node.app.workflows.handle.TransactionType.POST_UPGRADE_TRANSACTION;
import static com.hedera.node.app.workflows.handle.steps.StakePeriodChanges.isNextSecond;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.BOTH;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.platform.system.InitTrigger.EVENT_STREAM_RECOVERY;
import static com.swirlds.state.lifecycle.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.input.EventHeader;
import com.hedera.hapi.block.stream.input.RoundHeader;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.KVStateChangeListener;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.hints.impl.WritableHintsStoreImpl;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.impl.WritableHistoryStoreImpl;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.roster.RosterService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
import com.hedera.node.app.service.addressbook.impl.helpers.AddressBookHelper;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.schedule.ExecutableTxn;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.WritableScheduleStoreImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.HederaRecordCache.DueDiligenceFailure;
import com.hedera.node.app.state.recordcache.BlockRecordSource;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.store.StoreFactoryImpl;
import com.hedera.node.app.store.WritableStoreFactory;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.cache.CacheWarmer;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.record.SystemSetup;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.app.workflows.handle.steps.UserTxn;
import com.hedera.node.app.workflows.handle.steps.UserTxnFactory;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.ConsensusConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.ReadableRosterStoreImpl;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The handle workflow that is responsible for handling the next {@link Round} of transactions.
 */
@Singleton
public class HandleWorkflow {
    private static final Logger logger = LogManager.getLogger(HandleWorkflow.class);

    public static final String ALERT_MESSAGE = "Possibly CATASTROPHIC failure";

    private final StreamMode streamMode;
    private final NetworkInfo networkInfo;
    private final StakePeriodChanges stakePeriodChanges;
    private final DispatchProcessor dispatchProcessor;
    private final StoreMetricsService storeMetricsService;
    private final BlockRecordManager blockRecordManager;
    private final BlockStreamManager blockStreamManager;
    private final CacheWarmer cacheWarmer;
    private final OpWorkflowMetrics opWorkflowMetrics;
    private final ThrottleServiceManager throttleServiceManager;
    private final SemanticVersion version;
    private final InitTrigger initTrigger;
    private final HollowAccountCompletions hollowAccountCompletions;
    private final SystemSetup systemSetup;
    private final StakeInfoHelper stakeInfoHelper;
    private final HederaRecordCache recordCache;
    private final ExchangeRateManager exchangeRateManager;
    private final StakePeriodManager stakePeriodManager;
    private final List<StateChanges.Builder> migrationStateChanges;
    private final UserTxnFactory userTxnFactory;
    private final AddressBookHelper addressBookHelper;
    private final HintsService hintsService;
    private final HistoryService historyService;
    private final ConfigProvider configProvider;
    private final KVStateChangeListener kvStateChangeListener;
    private final BoundaryStateChangeListener boundaryStateChangeListener;
    private final ScheduleService scheduleService;

    // The last second since the epoch at which the metrics were updated; this does not affect transaction handling
    private long lastMetricUpdateSecond;
    // The last second for which this workflow has confirmed all scheduled transactions are executed
    private long lastExecutedSecond;

    @Inject
    public HandleWorkflow(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final StakePeriodChanges stakePeriodChanges,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final ConfigProvider configProvider,
            @NonNull final StoreMetricsService storeMetricsService,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final CacheWarmer cacheWarmer,
            @NonNull final OpWorkflowMetrics opWorkflowMetrics,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final SemanticVersion version,
            @NonNull final InitTrigger initTrigger,
            @NonNull final HollowAccountCompletions hollowAccountCompletions,
            @NonNull final SystemSetup systemSetup,
            @NonNull final StakeInfoHelper stakeInfoHelper,
            @NonNull final HederaRecordCache recordCache,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final StakePeriodManager stakePeriodManager,
            @NonNull final List<StateChanges.Builder> migrationStateChanges,
            @NonNull final UserTxnFactory userTxnFactory,
            @NonNull final AddressBookHelper addressBookHelper,
            @NonNull final HintsService hintsService,
            @NonNull final HistoryService historyService,
            @NonNull final KVStateChangeListener kvStateChangeListener,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final ScheduleService scheduleService) {
        this.networkInfo = requireNonNull(networkInfo);
        this.stakePeriodChanges = requireNonNull(stakePeriodChanges);
        this.dispatchProcessor = requireNonNull(dispatchProcessor);
        this.storeMetricsService = requireNonNull(storeMetricsService);
        this.blockRecordManager = requireNonNull(blockRecordManager);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.cacheWarmer = requireNonNull(cacheWarmer);
        this.opWorkflowMetrics = requireNonNull(opWorkflowMetrics);
        this.throttleServiceManager = requireNonNull(throttleServiceManager);
        this.version = requireNonNull(version);
        this.initTrigger = requireNonNull(initTrigger);
        this.hollowAccountCompletions = requireNonNull(hollowAccountCompletions);
        this.systemSetup = requireNonNull(systemSetup);
        this.stakeInfoHelper = requireNonNull(stakeInfoHelper);
        this.recordCache = requireNonNull(recordCache);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.stakePeriodManager = requireNonNull(stakePeriodManager);
        this.migrationStateChanges = new ArrayList<>(migrationStateChanges);
        this.userTxnFactory = requireNonNull(userTxnFactory);
        this.configProvider = requireNonNull(configProvider);
        this.addressBookHelper = requireNonNull(addressBookHelper);
        this.hintsService = requireNonNull(hintsService);
        this.historyService = requireNonNull(historyService);
        this.kvStateChangeListener = requireNonNull(kvStateChangeListener);
        this.boundaryStateChangeListener = requireNonNull(boundaryStateChangeListener);
        this.scheduleService = requireNonNull(scheduleService);
        this.streamMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
    }

    /**
     * Handles the next {@link Round}
     *
     * @param state the writable {@link State} that this round will work on
     * @param round the next {@link Round} that needs to be processed
     */
    public void handleRound(@NonNull final State state, @NonNull final Round round) {
        logStartRound(round);
        cacheWarmer.warm(state, round);
        reconcileTssState(
                configProvider.getConfiguration().getConfigData(TssConfig.class), state, round.getConsensusTimestamp());
        if (streamMode != RECORDS) {
            blockStreamManager.startRound(round, state);
            blockStreamManager.writeItem(BlockItem.newBuilder()
                    .roundHeader(new RoundHeader(round.getRoundNum()))
                    .build());
            if (!migrationStateChanges.isEmpty()) {
                migrationStateChanges.forEach(builder -> blockStreamManager.writeItem(BlockItem.newBuilder()
                        .stateChanges(builder.consensusTimestamp(blockStreamManager.blockTimestamp())
                                .build())
                        .build()));
                migrationStateChanges.clear();
            }
        }
        recordCache.resetRoundReceipts();
        try {
            handleEvents(state, round);
        } finally {
            // Even if there is an exception somewhere, we need to commit the receipts of any handled transactions
            // to the state so these transactions cannot be replayed in future rounds
            recordCache.commitRoundReceipts(state, round.getConsensusTimestamp());
        }
    }

    private void reconcileTssState(
            @NonNull final TssConfig tssConfig, @NonNull final State state, @NonNull final Instant now) {
        if (tssConfig.hintsEnabled() || tssConfig.historyEnabled()) {
            final var rosterStore = new ReadableRosterStoreImpl(state.getReadableStates(RosterService.NAME));
            final var activeRosters = ActiveRosters.from(rosterStore);
            if (tssConfig.hintsEnabled()) {
                final var hintsWritableStates = state.getWritableStates(HintsService.NAME);
                final var hintsStore = new WritableHintsStoreImpl(hintsWritableStates);
                doStreamingKVChanges(
                        hintsWritableStates, now, () -> hintsService.reconcile(activeRosters, hintsStore, now));
            }
            if (tssConfig.historyEnabled()) {
                final Bytes currentMetadata;
                if (tssConfig.hintsEnabled()) {
                    final var hintsStore = new ReadableHintsStoreImpl(state.getReadableStates(HintsService.NAME));
                    currentMetadata = hintsStore.getVerificationKeyFor(activeRosters.currentRosterHash());
                } else {
                    currentMetadata = null;
                }
                doStreamingKVChanges(state.getWritableStates(HistoryService.NAME), now, () -> {
                    final var historyStore = new WritableHistoryStoreImpl();
                    historyService.reconcile(activeRosters, currentMetadata, historyStore, now);
                });
            }
        }
    }

    /**
     * Applies all effects of the events in the given round to the given state, writing stream items
     * that capture these effects in the process.
     *
     * @param state the state to apply the effects to
     * @param round the round to apply the effects of
     */
    private void handleEvents(@NonNull final State state, @NonNull final Round round) {
        boolean userTransactionsHandled = false;
        for (final var event : round) {
            if (streamMode != RECORDS) {
                final var headerItem = BlockItem.newBuilder()
                        .eventHeader(new EventHeader(event.getEventCore(), event.getSignature()))
                        .build();
                blockStreamManager.writeItem(headerItem);
            }
            final var creator = networkInfo.nodeInfo(event.getCreatorId().id());
            if (creator == null) {
                if (!isSoOrdered(event.getSoftwareVersion(), version)) {
                    // We were given an event for a node that does not exist in the address book and was not from
                    // a strictly earlier software upgrade. This will be logged as a warning, as this should never
                    // happen, and we will skip the event. The platform should guarantee that we never receive an event
                    // that isn't associated with the address book, and every node in the address book must have an
                    // account ID, since you cannot delete an account belonging to a node, and you cannot change the
                    // address book non-deterministically.
                    logger.warn(
                            "Received event (version {} vs current {}) from node {} which is not in the address book",
                            HapiUtils.toString(event.getSoftwareVersion()),
                            HapiUtils.toString(version),
                            event.getCreatorId());
                }
                continue;
            }
            // log start of event to transaction state log
            logStartEvent(event, creator);
            // handle each transaction of the event
            for (final var it = event.consensusTransactionIterator(); it.hasNext(); ) {
                final var platformTxn = it.next();
                try {
                    // skip system transactions
                    if (!platformTxn.isSystem()) {
                        userTransactionsHandled = true;
                        handlePlatformTransaction(state, creator, platformTxn, event.getSoftwareVersion());
                    }
                } catch (final Exception e) {
                    logger.fatal(
                            "Possibly CATASTROPHIC failure while running the handle workflow. "
                                    + "While this node may not die right away, it is in a bad way, most likely fatally.",
                            e);
                }
            }
        }
        // Update all throttle metrics once per round
        throttleServiceManager.updateAllMetrics();
        // Inform the BlockRecordManager that the round is complete, so it can update running-hashes in state
        // that have been being computed in background threads. The running hash has to be included in
        // state, but we want to synchronize with background threads as infrequently as possible. So once per
        // round is the minimum we can do. Note the BlockStreamManager#endRound() method is called in Hedera's
        // implementation of SwirldState#sealConsensusRound(), since the BlockStreamManager cannot do its
        // end-of-block work until the platform has finished all its state changes.
        if (userTransactionsHandled && streamMode != BLOCKS) {
            blockRecordManager.endRound(state);
        }
    }

    /**
     * Handles a platform transaction. This method is responsible for creating a {@link UserTxn} and
     * executing the workflow for the transaction. This produces a stream of records that are then passed to the
     * {@link BlockRecordManager} to be externalized.
     *
     * @param state      the writable {@link State} that this transaction will work on
     * @param creator    the {@link NodeInfo} of the creator of the transaction
     * @param txn        the {@link ConsensusTransaction} to be handled
     * @param txnVersion the software version for the event containing the transaction
     */
    private void handlePlatformTransaction(
            @NonNull final State state,
            @NonNull final NodeInfo creator,
            @NonNull final ConsensusTransaction txn,
            @NonNull final SemanticVersion txnVersion) {
        final var handleStart = System.nanoTime();

        // Always use platform-assigned time for user transaction, c.f. https://hips.hedera.com/hip/hip-993
        final var consensusNow = txn.getConsensusTimestamp();
        var type = ORDINARY_TRANSACTION;
        stakePeriodManager.setCurrentStakePeriodFor(consensusNow);
        if (streamMode != BLOCKS) {
            final var isBoundary = blockRecordManager.startUserTransaction(consensusNow, state);
            if (streamMode == RECORDS && isBoundary) {
                type = typeOfBoundary(state);
            }
        }
        if (streamMode != RECORDS) {
            type = switch (blockStreamManager.pendingWork()) {
                case GENESIS_WORK -> GENESIS_TRANSACTION;
                case POST_UPGRADE_WORK -> POST_UPGRADE_TRANSACTION;
                default -> ORDINARY_TRANSACTION;};
        }

        final var userTxn = userTxnFactory.createUserTxn(state, creator, txn, consensusNow, type);
        var lastRecordManagerTime = streamMode == RECORDS ? blockRecordManager.consTimeOfLastHandledTxn() : null;
        final var handleOutput = executeTopLevel(userTxn, txnVersion);
        if (streamMode != BLOCKS) {
            final var records = ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
            blockRecordManager.endUserTransaction(records.stream(), state);
        }
        if (streamMode != RECORDS) {
            handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
        }

        opWorkflowMetrics.updateDuration(userTxn.functionality(), (int) (System.nanoTime() - handleStart));

        if (streamMode == RECORDS) {
            // We don't support long-term scheduled transactions if only producing records
            // because that legacy state doesn't have an appropriate way to track the status
            // of triggered execution work; so we just purge all expired schedules without
            // further consideration here
            purgeScheduling(state, lastRecordManagerTime, userTxn.consensusNow());
        } else {
            final var executionStart = blockStreamManager.lastIntervalProcessTime();
            try {
                // We execute as many schedules expiring in [lastIntervalProcessTime, consensusNow]
                // as there are available consensus times and execution slots (ordinarily there will
                // be more than enough of both, but we must be prepared for the edge cases)
                executeAsManyScheduled(
                        state, executionStart, userTxn.consensusNow(), userTxn.creatorInfo(), userTxn.type());
            } catch (Exception e) {
                logger.error(
                        "{} - unhandled exception while executing schedules between [{}, {}]",
                        ALERT_MESSAGE,
                        executionStart,
                        userTxn.consensusNow(),
                        e);
                // This should never happen, but if it does, we skip over everything in the interval to
                // avoid being stuck in a crash loop here
                blockStreamManager.setLastIntervalProcessTime(userTxn.consensusNow());
            }
        }
    }

    /**
     * Executes as many transactions scheduled to expire in the interval {@code [executionStart, consensusNow]} as
     * possible from the given state, given some context of the triggering user transaction.
     * <p>
     * As a side effect on the workflow internal state, updates the {@link BlockStreamManager}'s last interval process
     * time to the latest time known to have been processed; and the {@link #lastExecutedSecond} value to the last
     * second of the interval for which all scheduled transactions were executed.
     *
     * @param state          the state to execute scheduled transactions from
     * @param executionStart the start of the interval to execute transactions in
     * @param consensusNow   the consensus time at which the user transaction triggering this execution was processed
     * @param creatorInfo    the node info of the user transaction creator
     * @param type           the type of the user transaction triggering this execution
     */
    private void executeAsManyScheduled(
            @NonNull final State state,
            @NonNull final Instant executionStart,
            @NonNull final Instant consensusNow,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final TransactionType type) {
        // Non-final right endpoint of the execution interval, in case we cannot do all the scheduled work
        var executionEnd = consensusNow;
        // We only construct an Iterator<ExecutableTxn> if this is not genesis, and we haven't already
        // created and exhausted iterators through the last second in the interval
        if (type != GENESIS_TRANSACTION && executionEnd.getEpochSecond() > lastExecutedSecond) {
            final var config = configProvider.getConfiguration();
            final var schedulingConfig = config.getConfigData(SchedulingConfig.class);
            final var consensusConfig = config.getConfigData(ConsensusConfig.class);
            // Since the next platform-assigned consensus time may be as early as (now + separationNanos),
            // we must ensure that even if the last scheduled execution time is followed by the maximum
            // number of child transactions, the last child's assigned time will be strictly before the
            // first of the next consensus time's possible preceding children; that is, strictly before
            // (now + separationNanos) - (maxAfter + maxBefore + 1)
            final var lastUsableTime = consensusNow.plusNanos(schedulingConfig.consTimeSeparationNanos()
                    - consensusConfig.handleMaxPrecedingRecords()
                    - (consensusConfig.handleMaxFollowingRecords() + 1));
            // The first possible time for the next execution is strictly after the last execution time
            // consumed for the triggering user transaction; plus the maximum number of preceding children
            var nextTime = boundaryStateChangeListener
                    .lastConsensusTimeOrThrow()
                    .plusNanos(consensusConfig.handleMaxPrecedingRecords() + 1);
            // Now we construct the iterator and start executing transactions in this interval
            final var iter = scheduleService.executableTxns(
                    executionStart,
                    consensusNow,
                    StoreFactoryImpl.from(state, ScheduleService.NAME, config, storeMetricsService));
            final var writableStates = state.getWritableStates(ScheduleService.NAME);
            // Configuration sets a maximum number of execution slots per user transaction
            int n = schedulingConfig.maxExecutionsPerUserTxn();
            while (iter.hasNext() && !nextTime.isAfter(lastUsableTime) && n > 0) {
                final var executableTxn = iter.next();
                if (schedulingConfig.longTermEnabled()) {
                    stakePeriodManager.setCurrentStakePeriodFor(nextTime);
                    if (streamMode == BOTH) {
                        blockRecordManager.startUserTransaction(nextTime, state);
                    }
                    final var handleOutput = executeScheduled(state, nextTime, creatorInfo, executableTxn);
                    handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
                    if (streamMode == BOTH) {
                        final var records =
                                ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
                        blockRecordManager.endUserTransaction(records.stream(), state);
                    }
                }
                executionEnd = executableTxn.nbf();
                doStreamingKVChanges(writableStates, executionEnd, iter::remove);
                nextTime = boundaryStateChangeListener
                        .lastConsensusTimeOrThrow()
                        .plusNanos(consensusConfig.handleMaxPrecedingRecords() + 1);
                n--;
            }
            // The purgeUntilNext() iterator extension purges any schedules with wait_until_expiry=false
            // that expire after the last schedule returned from next(), until either the next executable
            // schedule or the iterator boundary is reached
            doStreamingKVChanges(writableStates, executionEnd, iter::purgeUntilNext);
            // If the iterator is not exhausted, we can only mark the second _before_ the last-executed NBF time
            // as complete; if it is exhausted, we mark the rightmost second of the interval as complete
            if (iter.hasNext()) {
                lastExecutedSecond = executionEnd.getEpochSecond() - 1;
            } else {
                // We exhausted the iterator, so jump back ahead to the interval right endpoint
                executionEnd = consensusNow;
                lastExecutedSecond = consensusNow.getEpochSecond();
            }
        }
        // Update our last-processed time with where we ended
        blockStreamManager.setLastIntervalProcessTime(executionEnd);
    }

    /**
     * Type inference helper to compute the base builder for a {@link UserTxn} derived from a
     * {@link ExecutableTxn}.
     *
     * @param <T>           the type of the stream builder
     * @param executableTxn the executable transaction to compute the base builder for
     * @param userTxn       the user transaction derived from the executable transaction
     * @return the base builder for the user transaction
     */
    private <T extends StreamBuilder> T baseBuilderFor(
            @NonNull final ExecutableTxn<T> executableTxn, @NonNull final UserTxn userTxn) {
        return userTxn.initBaseBuilder(
                exchangeRateManager.exchangeRates(), executableTxn.builderType(), executableTxn.builderSpec());
    }

    /**
     * Purges all service state used for scheduling work that was expired by the last time the purge
     * was triggered; but is not expired at the current time. Returns true if the last purge time
     * should be set to the current time.
     *
     * @param state the state to purge
     * @param then  the last time the purge was triggered
     * @param now   the current time
     */
    private void purgeScheduling(@NonNull final State state, final Instant then, final Instant now) {
        if (!Instant.EPOCH.equals(then) && then.getEpochSecond() < now.getEpochSecond()) {
            final var writableStates = state.getWritableStates(ScheduleService.NAME);
            doStreamingKVChanges(writableStates, now, () -> {
                final var scheduleStore = new WritableScheduleStoreImpl(
                        writableStates, configProvider.getConfiguration(), storeMetricsService);
                scheduleStore.purgeExpiredRangeClosed(then.getEpochSecond(), now.getEpochSecond() - 1);
            });
        }
    }

    /**
     * Executes the user transaction and returns the output that should be externalized in the
     * block stream. (And if still producing records, the precomputed records.)
     * <p>
     * Never throws an exception without a fundamental breakdown of the system invariants. If
     * there is an internal error when executing the transaction, returns stream output of
     * just the transaction with a {@link ResponseCodeEnum#FAIL_INVALID} transaction result,
     * and no other side effects.
     *
     * @param userTxn    the user transaction to execute
     * @param txnVersion the software version for the event containing the transaction
     * @return the stream output from executing the transaction
     */
    private HandleOutput executeTopLevel(@NonNull final UserTxn userTxn, @NonNull final SemanticVersion txnVersion) {
        try {
            if (isOlderSoftwareEvent(txnVersion)) {
                if (streamMode != BLOCKS) {
                    // This updates consTimeOfLastHandledTxn as a side effect
                    blockRecordManager.advanceConsensusClock(userTxn.consensusNow(), userTxn.state());
                }
                blockStreamManager.setLastHandleTime(userTxn.consensusNow());
                initializeBuilderInfo(userTxn.baseBuilder(), userTxn.txnInfo(), exchangeRateManager.exchangeRates())
                        .status(BUSY);
                // Flushes the BUSY builder to the stream, no other side effects
                userTxn.stack().commitTransaction(userTxn.baseBuilder());
            } else {
                if (userTxn.type() == GENESIS_TRANSACTION) {
                    // (FUTURE) Once all genesis setup is done via dispatch, remove this method
                    systemSetup.externalizeInitSideEffects(
                            userTxn.tokenContextImpl(), exchangeRateManager.exchangeRates());
                } else if (userTxn.type() == POST_UPGRADE_TRANSACTION) {
                    // Since we track node stake metadata separately from the future address book (FAB),
                    // we need to update that stake metadata from any node additions or deletions that
                    // just took effect; it would be nice to unify the FAB and stake metadata in the future
                    final var writableTokenStates = userTxn.stack().getWritableStates(TokenService.NAME);
                    final var streamBuilder = stakeInfoHelper.adjustPostUpgradeStakes(
                            userTxn.tokenContextImpl(),
                            networkInfo,
                            userTxn.config(),
                            new WritableStakingInfoStore(writableTokenStates),
                            new WritableNetworkStakingRewardsStore(writableTokenStates));

                    // (FUTURE) Verify we can remove this deprecated node metadata sync now that DAB is active;
                    // it should never happen case that nodes are added or removed from the address book without
                    // those changes already being visible in the FAB
                    final var addressBookWritableStoreFactory = new WritableStoreFactory(
                            userTxn.stack(), AddressBookService.NAME, userTxn.config(), storeMetricsService);
                    addressBookHelper.adjustPostUpgradeNodeMetadata(
                            networkInfo,
                            userTxn.config(),
                            addressBookWritableStoreFactory.getStore(WritableNodeStore.class));

                    if (streamMode != RECORDS) {
                        // Only externalize this if we are streaming blocks
                        streamBuilder.exchangeRate(exchangeRateManager.exchangeRates());
                        userTxn.stack().commitTransaction(streamBuilder);
                    } else {
                        // Only update this if we are relying on RecordManager state for post-upgrade processing
                        blockRecordManager.markMigrationRecordsStreamed();
                        userTxn.stack().commitSystemStateChanges();
                    }
                }

                final var dispatch = userTxnFactory.createDispatch(userTxn, exchangeRateManager.exchangeRates());
                advanceTimeFor(userTxn, dispatch);
                logPreDispatch(userTxn);
                if (userTxn.type() != ORDINARY_TRANSACTION) {
                    if (userTxn.type() == GENESIS_TRANSACTION) {
                        logger.info("Doing genesis setup @ {}", userTxn.consensusNow());
                        systemSetup.doGenesisSetup(dispatch);
                    } else if (userTxn.type() == POST_UPGRADE_TRANSACTION) {
                        logger.info("Doing post-upgrade setup @ {}", userTxn.consensusNow());
                        systemSetup.doPostUpgradeSetup(dispatch);
                    }
                    if (streamMode != RECORDS) {
                        blockStreamManager.confirmPendingWorkFinished();
                    }
                }
                hollowAccountCompletions.completeHollowAccounts(userTxn, dispatch);
                dispatchProcessor.processDispatch(dispatch);
                updateWorkflowMetrics(userTxn);
            }
            final var handleOutput =
                    userTxn.stack().buildHandleOutput(userTxn.consensusNow(), exchangeRateManager.exchangeRates());
            recordCache.addRecordSource(
                    userTxn.creatorInfo().nodeId(),
                    userTxn.txnInfo().transactionID(),
                    userTxn.preHandleResult().dueDiligenceFailure(),
                    handleOutput.preferringBlockRecordSource());
            return handleOutput;
        } catch (final Exception e) {
            logger.error("{} - exception thrown while handling user transaction", ALERT_MESSAGE, e);
            return failInvalidStreamItems(userTxn);
        }
    }

    /**
     * Executes the scheduled transaction against the given state at the given time and returns
     * the output that should be externalized in the block stream. (And if still producing records,
     * the precomputed records.)
     * <p>
     * Never throws an exception without a fundamental breakdown of the system invariants. If
     * there is an internal error when executing the transaction, returns stream output of just the
     * scheduled transaction with a {@link ResponseCodeEnum#FAIL_INVALID} transaction result, and
     * no other side effects.
     *
     * @param state        the state to execute the transaction against
     * @param consensusNow the time to execute the transaction at
     * @return the stream output from executing the transaction
     */
    private HandleOutput executeScheduled(
            @NonNull final State state,
            @NonNull final Instant consensusNow,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final ExecutableTxn<? extends StreamBuilder> executableTxn) {
        final var scheduledTxn = userTxnFactory.createUserTxn(
                state, creatorInfo, consensusNow, ORDINARY_TRANSACTION, executableTxn.payerId(), executableTxn.body());
        final var baseBuilder = baseBuilderFor(executableTxn, scheduledTxn);
        final var dispatch =
                userTxnFactory.createDispatch(scheduledTxn, baseBuilder, executableTxn.keyVerifier(), SCHEDULED);
        advanceTimeFor(scheduledTxn, dispatch);
        try {
            dispatchProcessor.processDispatch(dispatch);
            final var handleOutput = scheduledTxn
                    .stack()
                    .buildHandleOutput(scheduledTxn.consensusNow(), exchangeRateManager.exchangeRates());
            recordCache.addRecordSource(
                    scheduledTxn.creatorInfo().nodeId(),
                    scheduledTxn.txnInfo().transactionID(),
                    DueDiligenceFailure.NO,
                    handleOutput.preferringBlockRecordSource());
            return handleOutput;
        } catch (final Exception e) {
            logger.error("{} - exception thrown while handling scheduled transaction", ALERT_MESSAGE, e);
            return failInvalidStreamItems(scheduledTxn);
        }
    }

    /**
     * Manages time-based side effects for the given user transaction and dispatch.
     *
     * @param userTxn  the user transaction to manage time for
     * @param dispatch the dispatch to manage time for
     */
    private void advanceTimeFor(@NonNull final UserTxn userTxn, @NonNull final Dispatch dispatch) {
        // WARNING: The check below relies on the BlockStreamManager's last-handled time not being updated yet,
        // so we must not call setLastHandleTime() until after them
        processStakePeriodChanges(userTxn, dispatch);
        if (isNextSecond(userTxn.consensusNow(), blockStreamManager.lastHandleTime())) {
            processStakePeriodChanges(userTxn, dispatch);
        }
        blockStreamManager.setLastHandleTime(userTxn.consensusNow());
        if (streamMode != BLOCKS) {
            // This updates consTimeOfLastHandledTxn as a side effect
            blockRecordManager.advanceConsensusClock(userTxn.consensusNow(), userTxn.state());
        }
    }

    /**
     * Commits an action with side effects while capturing its key/value state changes and writing them to the
     * block stream.
     *
     * @param writableStates the writable states to commit the action to
     * @param now            the consensus timestamp of the action
     * @param action         the action to commit
     */
    private void doStreamingKVChanges(
            @NonNull final WritableStates writableStates, @NonNull final Instant now, @NonNull final Runnable action) {
        if (streamMode != RECORDS) {
            kvStateChangeListener.reset();
        }
        action.run();
        ((CommittableWritableStates) writableStates).commit();
        if (streamMode != RECORDS) {
            final var changes = kvStateChangeListener.getStateChanges();
            if (!changes.isEmpty()) {
                final var stateChangesItem = BlockItem.newBuilder()
                        .stateChanges(new StateChanges(asTimestamp(now), new ArrayList<>(changes)))
                        .build();
                blockStreamManager.writeItem(stateChangesItem);
            }
        }
    }

    /**
     * Returns a stream of a single {@link ResponseCodeEnum#FAIL_INVALID} record
     * for the given user transaction.
     *
     * @return the failure record
     */
    private HandleOutput failInvalidStreamItems(@NonNull final UserTxn userTxn) {
        // The stack for the user txn should never be committed
        userTxn.stack().rollbackFullStack();

        RecordSource cacheableRecordSource = null;
        final RecordSource recordSource;
        if (streamMode != BLOCKS) {
            final var failInvalidBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER);
            initializeBuilderInfo(failInvalidBuilder, userTxn.txnInfo(), exchangeRateManager.exchangeRates())
                    .status(FAIL_INVALID)
                    .consensusTimestamp(userTxn.consensusNow());
            final var failInvalidRecord = failInvalidBuilder.build();
            cacheableRecordSource = recordSource = new LegacyListRecordSource(
                    List.of(failInvalidRecord),
                    List.of(new RecordSource.IdentifiedReceipt(
                            failInvalidRecord.transactionRecord().transactionIDOrThrow(),
                            failInvalidRecord.transactionRecord().receiptOrThrow())));
        } else {
            recordSource = null;
        }
        final BlockRecordSource blockRecordSource;
        if (streamMode != RECORDS) {
            final List<BlockStreamBuilder.Output> outputs = new LinkedList<>();
            final var failInvalidBuilder = new BlockStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER);
            initializeBuilderInfo(failInvalidBuilder, userTxn.txnInfo(), exchangeRateManager.exchangeRates())
                    .status(FAIL_INVALID)
                    .consensusTimestamp(userTxn.consensusNow());
            outputs.add(failInvalidBuilder.build());
            cacheableRecordSource = blockRecordSource = new BlockRecordSource(outputs);
        } else {
            blockRecordSource = null;
        }

        recordCache.addRecordSource(
                userTxn.creatorInfo().nodeId(),
                requireNonNull(userTxn.txnInfo().transactionID()),
                DueDiligenceFailure.NO,
                requireNonNull(cacheableRecordSource));
        return new HandleOutput(blockRecordSource, recordSource);
    }

    /**
     * Returns true if the software event is older than the current software version.
     *
     * @return true if the software event is older than the current software version
     */
    private boolean isOlderSoftwareEvent(@NonNull final SemanticVersion txnVersion) {
        return this.initTrigger != EVENT_STREAM_RECOVERY
                && SEMANTIC_VERSION_COMPARATOR.compare(version, txnVersion) > 0;
    }

    /**
     * Updates the metrics for the handle workflow.
     */
    private void updateWorkflowMetrics(@NonNull final UserTxn userTxn) {
        if (userTxn.type() == GENESIS_TRANSACTION || userTxn.consensusNow().getEpochSecond() > lastMetricUpdateSecond) {
            opWorkflowMetrics.switchConsensusSecond();
            lastMetricUpdateSecond = userTxn.consensusNow().getEpochSecond();
        }
    }

    /**
     * Initializes the base builder of the given user transaction initialized with its transaction
     * information. The record builder is initialized with the transaction, transaction bytes, transaction ID,
     * exchange rate, and memo.
     *
     * @param builder         the base builder
     * @param txnInfo         the transaction information
     * @param exchangeRateSet the active exchange rate set
     * @return the initialized base builder
     */
    public static StreamBuilder initializeBuilderInfo(
            @NonNull final StreamBuilder builder,
            @NonNull final TransactionInfo txnInfo,
            @NonNull final ExchangeRateSet exchangeRateSet) {
        final var transaction = txnInfo.transaction();
        // If the transaction uses the legacy body bytes field instead of explicitly
        // setting its signed bytes, the record will have the hash of its bytes as
        // serialized by PBJ
        final Bytes transactionBytes;
        if (transaction.signedTransactionBytes().length() > 0) {
            transactionBytes = transaction.signedTransactionBytes();
        } else {
            transactionBytes = Transaction.PROTOBUF.toBytes(transaction);
        }
        return builder.transaction(txnInfo.transaction())
                .functionality(txnInfo.functionality())
                .serializedTransaction(txnInfo.serializedTransaction())
                .transactionBytes(transactionBytes)
                .transactionID(txnInfo.txBody().transactionIDOrThrow())
                .exchangeRate(exchangeRateSet)
                .memo(txnInfo.txBody().memo());
    }

    /**
     * Processes any side effects of crossing a stake period boundary.
     *
     * @param userTxn the user transaction that crossed the boundary
     * @param dispatch the dispatch for the user transaction that crossed the boundary
     */
    private void processStakePeriodChanges(@NonNull final UserTxn userTxn, @NonNull final Dispatch dispatch) {
        try {
            stakePeriodChanges.process(
                    dispatch,
                    userTxn.stack(),
                    userTxn.tokenContextImpl(),
                    streamMode,
                    userTxn.type() == GENESIS_TRANSACTION,
                    blockStreamManager.lastHandleTime());
        } catch (final Exception e) {
            // We don't propagate a failure here to avoid a catastrophic scenario
            // where we are "stuck" trying to process node stake updates and never
            // get back to user transactions
            logger.error("Failed to process stake period changes", e);
        }
    }

    private static void logPreDispatch(@NonNull final UserTxn userTxn) {
        if (logger.isDebugEnabled()) {
            logStartUserTransaction(
                    userTxn.consensusNow(),
                    userTxn.txnInfo().txBody(),
                    requireNonNull(userTxn.txnInfo().payerID()));
            logStartUserTransactionPreHandleResultP2(userTxn.preHandleResult());
            logStartUserTransactionPreHandleResultP3(userTxn.preHandleResult());
        }
    }

    /**
     * Returns the type of transaction encountering the given state at a block boundary.
     *
     * @param state the boundary state
     * @return the type of the boundary transaction
     */
    private TransactionType typeOfBoundary(@NonNull final State state) {
        final var files = state.getReadableStates(FileService.NAME).get(BLOBS_KEY);
        // The files map is empty only at genesis
        if (files.size() == 0) {
            return GENESIS_TRANSACTION;
        }
        final var blockInfo = state.getReadableStates(BlockRecordService.NAME)
                .<BlockInfo>getSingleton(BLOCK_INFO_STATE_KEY)
                .get();
        return !requireNonNull(blockInfo).migrationRecordsStreamed() ? POST_UPGRADE_TRANSACTION : ORDINARY_TRANSACTION;
    }
}
