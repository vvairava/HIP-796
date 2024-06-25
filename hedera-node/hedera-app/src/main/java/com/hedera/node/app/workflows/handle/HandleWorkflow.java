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

package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartEvent;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logStartRound;
import static com.swirlds.platform.system.InitTrigger.EVENT_STREAM_RECOVERY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.metric.HandleWorkflowMetrics;
import com.hedera.node.app.workflows.handle.record.GenesisSetup;
import com.hedera.node.app.workflows.handle.record.RecordListBuilder;
import com.hedera.node.app.workflows.handle.record.SingleTransactionRecordBuilderImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.PlatformState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.events.ConsensusEvent;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import com.swirlds.state.HederaState;
import com.swirlds.state.spi.info.NetworkInfo;
import com.swirlds.state.spi.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
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

    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;
    private final StoreMetricsService storeMetricsService;
    private final BlockRecordManager blockRecordManager;
    private final CacheWarmer cacheWarmer;
    private final HandleWorkflowMetrics handleWorkflowMetrics;
    private final ThrottleServiceManager throttleServiceManager;
    private final SoftwareVersion version;
    private final InitTrigger initTrigger;
    private final DefaultHandleWorkflow defaultHandleWorkflow;
    private final GenesisSetup genesisSetup;
    private final HederaRecordCache recordCache;
    private final ExchangeRateManager exchangeRateManager;
    private final PreHandleWorkflow preHandleWorkflow;

    @Inject
    public HandleWorkflow(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final StoreMetricsService storeMetricsService,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final CacheWarmer cacheWarmer,
            @NonNull final HandleWorkflowMetrics handleWorkflowMetrics,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final SoftwareVersion version,
            @NonNull final InitTrigger initTrigger,
            @NonNull final DefaultHandleWorkflow defaultHandleWorkflow,
            @NonNull final GenesisSetup genesisSetup,
            @NonNull final HederaRecordCache recordCache,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final PreHandleWorkflow preHandleWorkflow) {
        this.networkInfo = requireNonNull(networkInfo, "networkInfo must not be null");
        this.configProvider = requireNonNull(configProvider);
        this.storeMetricsService = requireNonNull(storeMetricsService);
        this.blockRecordManager = requireNonNull(blockRecordManager, "recordManager must not be null");
        this.cacheWarmer = requireNonNull(cacheWarmer, "cacheWarmer must not be null");
        this.handleWorkflowMetrics = requireNonNull(handleWorkflowMetrics, "handleWorkflowMetrics must not be null");
        this.throttleServiceManager = requireNonNull(throttleServiceManager, "throttleServiceManager must not be null");
        this.version = requireNonNull(version);
        this.initTrigger = requireNonNull(initTrigger);
        this.defaultHandleWorkflow = requireNonNull(defaultHandleWorkflow);
        this.genesisSetup = requireNonNull(genesisSetup);
        this.recordCache = requireNonNull(recordCache);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.preHandleWorkflow = requireNonNull(preHandleWorkflow);
    }

    /**
     * Handles the next {@link Round}
     *
     * @param state the writable {@link HederaState} that this round will work on
     * @param platformState the {@link PlatformState} that this round will work on
     * @param round the next {@link Round} that needs to be processed
     */
    public void handleRound(
            @NonNull final HederaState state, @NonNull final PlatformState platformState, @NonNull final Round round) {
        // Keep track of whether any user transactions were handled. If so, then we will need to close the round
        // with the block record manager.
        final var userTransactionsHandled = new AtomicBoolean(false);

        // log start of round to transaction state log
        logStartRound(round);

        // warm the cache
        cacheWarmer.warm(state, round);

        // handle each event in the round
        for (final ConsensusEvent event : round) {
            final var creator = networkInfo.nodeInfo(event.getCreatorId().id());
            if (creator == null) {
                // We were given an event for a node that *does not exist in the address book*. This will be logged as
                // a warning, as this should never happen, and we will skip the event. The platform should guarantee
                // that we never receive an event that isn't associated with the address book, and every node in the
                // address book must have an account ID, since you cannot delete an account belonging to a node, and
                // you cannot change the address book non-deterministically.
                logger.warn("Received event from node {} which is not in the address book", event.getCreatorId());
                return;
            }

            // log start of event to transaction state log
            logStartEvent(event, creator);

            // handle each transaction of the event
            for (final var it = event.consensusTransactionIterator(); it.hasNext(); ) {
                final var platformTxn = it.next();
                try {
                    // skip system transactions
                    if (!platformTxn.isSystem()) {
                        userTransactionsHandled.set(true);
                        handlePlatformTransaction(state, platformState, event, creator, platformTxn);
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
        // round is the minimum we can do.
        if (userTransactionsHandled.get()) {
            blockRecordManager.endRound(state);
        }
    }

    /**
     * Handles a platform transaction. This method is responsible for creating a {@link UserTxn} and
     * executing the workflow for the transaction. This produces a stream of records that are then passed to the
     * {@link BlockRecordManager} to be externalized.
     * @param state the writable {@link HederaState} that this transaction will work on
     * @param platformState the {@link PlatformState} that this transaction will work on
     * @param event the {@link ConsensusEvent} that this transaction belongs to
     * @param creator the {@link NodeInfo} of the creator of the transaction
     * @param txn the {@link ConsensusTransaction} to be handled
     */
    public void handlePlatformTransaction(
            @NonNull final HederaState state,
            @NonNull final PlatformState platformState,
            @NonNull final ConsensusEvent event,
            @NonNull final NodeInfo creator,
            @NonNull final ConsensusTransaction txn) {
        final var handleStart = System.nanoTime();

        final var consensusNow = txn.getConsensusTimestamp().minusNanos(1000 - 3L);
        blockRecordManager.startUserTransaction(consensusNow, state, platformState);
        final var userTxn = newUserTxn(state, platformState, event, creator, txn, consensusNow);
        final var recordStream = execute(userTxn);
        blockRecordManager.endUserTransaction(recordStream, state);

        handleWorkflowMetrics.updateTransactionDuration(
                userTxn.functionality(), (int) (System.nanoTime() - handleStart));
    }

    /**
     * This method gets all the verification data for the current transaction. If pre-handle was previously ran
     * successfully, we only add the missing keys. If it did not run or an error occurred, we run it again.
     * If there is a due diligence error, this method will return a CryptoTransfer to charge the node along with
     * its verification data.
     * @param creator the node that created the transaction
     * @param platformTxn the transaction to be verified
     * @param storeFactory the store factory
     * @return the verification data for the transaction
     */
    @NonNull
    public PreHandleResult getCurrentPreHandleResult(
            @NonNull final NodeInfo creator,
            @NonNull final ConsensusTransaction platformTxn,
            final ReadableStoreFactory storeFactory) {
        final var metadata = platformTxn.getMetadata();
        final PreHandleResult previousResult;
        if (metadata instanceof PreHandleResult result) {
            previousResult = result;
        } else {
            // This should be impossible since the Platform contract guarantees that SwirldState.preHandle()
            // is always called before SwirldState.handleTransaction(); and our preHandle() implementation
            // always sets the metadata to a PreHandleResult
            logger.error(
                    "Received transaction without PreHandleResult metadata from node {} (was {})",
                    creator.nodeId(),
                    metadata);
            previousResult = null;
        }
        // We do not know how long transactions are kept in memory. Clearing metadata to avoid keeping it for too long.
        platformTxn.setMetadata(null);
        return preHandleWorkflow.preHandleTransaction(
                creator.accountId(),
                storeFactory,
                storeFactory.getStore(ReadableAccountStore.class),
                platformTxn,
                previousResult);
    }

    /**
     * Initializes the user record with the transaction information. The record builder list is initialized with the
     * transaction, transaction bytes, transaction ID, exchange rate, and memo.
     * @param recordBuilder the record builder
     * @param txnInfo the transaction info
     */
    // TODO: Guarantee that this never throws an exception
    public SingleTransactionRecordBuilderImpl initializeUserRecord(
            @NonNull final SingleTransactionRecordBuilderImpl recordBuilder, @NonNull final TransactionInfo txnInfo) {
        requireNonNull(txnInfo);
        requireNonNull(recordBuilder);
        final var transaction = txnInfo.transaction();
        // If the transaction uses the legacy body bytes field instead of explicitly setting
        // its signed bytes, the record will have the hash of its bytes as serialized by PBJ
        final Bytes transactionBytes;
        if (transaction.signedTransactionBytes().length() > 0) {
            transactionBytes = transaction.signedTransactionBytes();
        } else {
            transactionBytes = Transaction.PROTOBUF.toBytes(transaction);
        }
        return recordBuilder
                .transaction(txnInfo.transaction())
                .transactionBytes(transactionBytes)
                .transactionID(txnInfo.txBody().transactionIDOrThrow())
                .exchangeRate(exchangeRateManager.exchangeRates())
                .memo(txnInfo.txBody().memo());
    }

    /**
     * Constructs a new {@link UserTxn} with the scope defined by the
     * current state, platform context, creator, and consensus time.
     *
     * @param state the current state
     * @param platformState the current platform state
     * @param event the current consensus event
     * @param creator the creator of the transaction
     * @param txn the consensus transaction
     * @param consensusNow the consensus time
     * @return the new user transaction
     */
    private UserTxn newUserTxn(
            @NonNull final HederaState state,
            @NonNull final PlatformState platformState,
            @NonNull final ConsensusEvent event,
            @NonNull final NodeInfo creator,
            @NonNull final ConsensusTransaction txn,
            @NonNull final Instant consensusNow) {
        return UserTxn.from(
                state,
                platformState,
                event,
                creator,
                txn,
                consensusNow,
                blockRecordManager.consTimeOfLastHandledTxn(),
                configProvider,
                storeMetricsService,
                blockRecordManager,
                this);
    }

    /**
     * Executes the user transaction and returns a stream of records that capture all
     * side effects on state that are stipulated by the pre-block-stream contract with
     * mirror nodes.
     *
     * <p>Never throws an exception without a fundamental breakdown in the integrity
     * of the system invariants. If there is an internal error when executing the
     * transaction, returns a stream of a single {@link ResponseCodeEnum#FAIL_INVALID}
     * record with no other side effects.
     *
     * <p><b>IMPORTANT:</b> With block streams, this contract will expand to include
     * all side effects on state, no exceptions.
     *
     * @return the stream of records
     */
    private Stream<SingleTransactionRecord> execute(@NonNull final UserTxn userTxn) {
        try {
            if (isOlderSoftwareEvent(userTxn)) {
                skip(userTxn);
            } else {
                if (userTxn.isGenesisTxn()) {
                    genesisSetup.setupIn(userTxn.tokenContextImpl());
                }
                defaultHandleWorkflow.execute(userTxn);
                updateWorkflowMetrics(userTxn);
            }
            return finalRecordStream(userTxn);
        } catch (final Exception e) {
            logger.error("{} - exception thrown while handling user transaction", ALERT_MESSAGE, e);
            return failInvalidRecordStream(userTxn);
        }
    }

    /**
     * Returns a stream of a single {@link ResponseCodeEnum#FAIL_INVALID} record
     * for the given user transaction.
     *
     * @return the failure record
     */
    private Stream<SingleTransactionRecord> failInvalidRecordStream(@NonNull final UserTxn userTxn) {
        final var failInvalidRecordListBuilder = new RecordListBuilder(userTxn.consensusNow());
        final var recordBuilder = failInvalidRecordListBuilder.userTransactionRecordBuilder();
        defaultHandleWorkflow.initializeUserRecord(recordBuilder, userTxn.txnInfo());
        recordBuilder.status(FAIL_INVALID);
        userTxn.stack().rollbackFullStack();
        return recordStream(userTxn, failInvalidRecordListBuilder);
    }

    /**
     * Does nothing but has the side effect of adding a {@link ResponseCodeEnum#BUSY}
     * record to the transaction's record stream.
     *
     * @param userTxn the user transaction to skip
     */
    private void skip(@NonNull final UserTxn userTxn) {
        final TransactionInfo transactionInfo = userTxn.txnInfo();
        // Initialize record builder list and place a BUSY record in the cache
        userTxn.recordListBuilder()
                .userTransactionRecordBuilder()
                .transaction(transactionInfo.transaction())
                .transactionBytes(transactionInfo.signedBytes())
                .transactionID(transactionInfo.txBody().transactionIDOrElse(TransactionID.DEFAULT))
                .exchangeRate(exchangeRateManager.exchangeRates())
                .memo(transactionInfo.txBody().memo())
                .status(ResponseCodeEnum.BUSY);
    }

    /**
     * Returns true if the software event is older than the current software version.
     *
     * @return true if the software event is older than the current software version
     */
    private boolean isOlderSoftwareEvent(@NonNull final UserTxn userTxn) {
        return this.initTrigger != EVENT_STREAM_RECOVERY
                && version.compareTo(userTxn.event().getSoftwareVersion()) > 0;
    }

    /**
     * Updates the metrics for the handle workflow.
     */
    private void updateWorkflowMetrics(@NonNull final UserTxn userTxn) {
        if (userTxn.isGenesisTxn()
                || userTxn.consensusNow().getEpochSecond()
                        > userTxn.lastHandledConsensusTime().getEpochSecond()) {
            handleWorkflowMetrics.switchConsensusSecond();
        }
    }

    /**
     * Returns a stream of records for the given user transaction with
     * its in-scope records.
     *
     * @param userTxn the user transaction
     * @return the stream of records
     */
    private Stream<SingleTransactionRecord> finalRecordStream(@NonNull final UserTxn userTxn) {
        return recordStream(userTxn, userTxn.recordListBuilder());
    }

    /**
     * Builds and caches the result of the user transaction with
     * the explicitly provided records.
     *
     * @param userTxn the user transaction
     * @param recordListBuilder the explicit records
     * @return the stream of records
     */
    private Stream<SingleTransactionRecord> recordStream(
            @NonNull final UserTxn userTxn, @NonNull final RecordListBuilder recordListBuilder) {
        final var result = recordListBuilder.build();
        recordCache.add(
                userTxn.creatorInfo().nodeId(), requireNonNull(userTxn.txnInfo().payerID()), result.records());
        return result.records().stream();
    }
}
