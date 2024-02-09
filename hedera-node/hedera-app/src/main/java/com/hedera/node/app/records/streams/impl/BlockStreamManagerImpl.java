/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.records.streams.impl;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.streams.v7.*;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.FunctionalBlockRecordManager;
import com.hedera.node.app.records.impl.BlockRecordInfoUtils;
import com.hedera.node.app.records.impl.BlockRecordManagerImpl;
import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.records.streams.ProcessUserTransactionResult;
import com.hedera.node.app.records.streams.impl.producers.BlockStateProofProducer;
import com.hedera.node.app.records.streams.state.BlockObserverSingleton;
import com.hedera.node.app.records.streams.state.StateChangesSink;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.state.WritableSingletonStateBase;
import com.hedera.node.app.state.HederaState;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.events.ConsensusEvent;
import com.swirlds.platform.system.transaction.ConsensusTransaction;
import com.swirlds.platform.system.transaction.StateSignatureTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An implementation of {@link BlockRecordManager} primarily responsible for managing state ({@link RunningHashes} and
 * {@link BlockInfo}), and delegating to a {@link BlockRecordStreamProducer} for writing to the stream file, hashing,
 * and performing other duties, possibly on background threads. All APIs of {@link BlockRecordManager} can only be
 * called from the "handle" thread!
 *
 * <p>BlockRecordManager is only responsible for updating BlockInfo about the status of the blocks, it's what manages
 *    the "state" of our block production.
 *
 * <p>BlockStreamManagerImpl is different from BlockRecordManagerImpl in that it does not have a blockPeriodInSeconds
 *    that triggers the closing of a block. Instead, it relies on the owner to call endRound() when a round is complete.
 */
@Singleton
public final class BlockStreamManagerImpl implements FunctionalBlockRecordManager, StateChangesSink {
    private static final Logger logger = LogManager.getLogger(BlockRecordManagerImpl.class);

    /**
     * The number of blocks to keep multiplied by hash size. This is computed based on the
     * {@link BlockStreamConfig#numOfBlockHashesInState()} setting multiplied by the size of each hash. This
     * setting is computed once at startup and used throughout.
     */
    private final int numBlockHashesToKeepBytes;
    /**
     * The number of rounds in a block, from configuration. This is computed based on the
     * {@link BlockStreamConfig#numRoundsInBlock()} setting. This setting is computed once at startup and used
     * throughout.
     */
    private final int numRoundsInBlock;
    /**
     * The stream file producer we are using. This is set once during startup, and used throughout the execution of the
     * node. It would be nice to allow this to be a dynamic property, but it just isn't convenient to do so at this
     * time.
     */
    private final BlockStreamProducer blockStreamProducer;
    /**
     * A {@link BlockInfo} of the most recently completed block. This is actually available in state, but there
     * is no reason for us to read it from state every time we need it, we can just recompute and cache this every
     * time we finish a provisional block.
     */
    private BlockInfo lastBlockInfo;
    /**
     * Keeps track if this block is currently open.
     */
    // TODO(nickpoorman): This should be stored in BlockInfo so that it's safe for restarts.
    private boolean blockOpen;
    /**
     * The number of rounds in the current provisional block. "provisional" because the block is not yet complete.
     */
    // TODO(nickpoorman): This should be stored in BlockInfo so that it's safe for restarts.
    private int roundsUntilNextBlock;
    /** True when we have completed event recovery. This is not yet implemented properly. */
    private boolean eventRecoveryCompleted = false;

    @Inject
    public BlockStreamManagerImpl(
            @NonNull final ConfigProvider configProvider,
            @NonNull final HederaState state,
            @NonNull final BlockStreamProducer blockStreamProducer) {
        System.out.println("BlockStreamManagerImpl constructor");

        requireNonNull(state);
        requireNonNull(configProvider);
        this.blockStreamProducer = requireNonNull(blockStreamProducer);

        // FUTURE: check if we were started in event recover mode and if event recovery needs to be completed before we
        // write any new records to stream
        this.eventRecoveryCompleted = false;

        // Get static configuration that is assumed not to change while the node is running
        final var blockStreamConfig = configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
        this.numRoundsInBlock = blockStreamConfig.numRoundsInBlock();
        if (this.numRoundsInBlock <= 0) {
            throw new IllegalArgumentException("numRoundsInBlock must be greater than 0");
        }
        this.numBlockHashesToKeepBytes = blockStreamConfig.numOfBlockHashesInState() * HASH_SIZE;

        // Initialize the last block info and provisional block info.
        // NOTE: State migration happens BEFORE dagger initialization, and this object is managed by dagger. So we are
        // guaranteed that the state exists PRIOR to this call.
        final var states = state.getReadableStates(BlockRecordService.NAME);
        final var blockInfoState = states.<BlockInfo>getSingleton(BlockRecordService.BLOCK_INFO_STATE_KEY);
        this.lastBlockInfo = blockInfoState.get();
        assert this.lastBlockInfo != null : "Cannot be null, because this state is created at genesis";
        this.blockOpen = false;
        this.roundsUntilNextBlock = numRoundsInBlock;

        // Initialize the stream file producer. NOTE, if the producer cannot be initialized, and a random exception is
        // thrown here, then startup of the node will fail. This is the intended behavior. We MUST be able to produce
        // block streams, or there really is no point to running the node!
        final var runningHashState = states.<RunningHashes>getSingleton(BlockRecordService.RUNNING_HASHES_STATE_KEY);
        final var lastRunningHashes = runningHashState.get();
        assert lastRunningHashes != null : "Cannot be null, because this state is created at genesis";
        this.blockStreamProducer.initFromLastBlock(lastRunningHashes, this.lastBlockInfo.lastBlockNumber());
    }

    // =================================================================================================================
    // AutoCloseable implementation

    /** {@inheritDoc} */
    @Override
    public void close() {
        try {
            blockStreamProducer.close();
        } catch (final Exception e) {
            // This is a fairly serious warning. This basically means we cannot guarantee that some records were
            // produced. However, since the {@link BlockRecordManager} is a singleton, this close method is only called
            // when the node is being shutdown anyway.
            logger.warn("Failed to close blockStreamProducer properly", e);
        }
    }

    // =================================================================================================================
    // BlockRecordManager implementation

    /** {@inheritDoc} */
    //    @Override
    public void startRound(@NonNull HederaState state, @NonNull Round round) {
        roundsUntilNextBlock--;

        // We do not have an open block so create a new one.
        if (!isBlockOpen()) beginBlock();
    }

    /** {@inheritDoc} */
    //    @Override
    public void endRound(@NonNull final HederaState state) {
        if (roundsUntilNextBlock == 0) {
            updateBlockInfoForEndRound(state);
            resetRoundsUntilNextBlock();
        }

        updateRunningHashesInState(state);
    }

    /** {@inheritDoc} */
    //    @Override
    public void startConsensusEvent(@NonNull HederaState state, @NonNull ConsensusEvent platformEvent) {
        blockStreamProducer.writeConsensusEvent(platformEvent);
    }

    public void endConsensusEvent(@NonNull HederaState state, @NonNull ConsensusEvent platformEvent) {}

    /** {@inheritDoc} */
    //    @Override
    public void startSystemTransaction(@NonNull HederaState state, @NonNull ConsensusTransaction systemTxn) {}

    /** {@inheritDoc} */
    //    @Override
    public void endSystemTransaction(
            @NonNull final HederaState state,
            @NonNull final NodeInfo creator,
            @NonNull ConsensusTransaction systemTxn) {
        // When we get a system StateSignatureTransaction, we need to collect all the signatures and write them to the
        // block stream. However, as it relates to keeping the running hash, these platform events will need to be
        // included in the following block, because they are the signatures for the end root hash of the current block.

        // If the system transaction is a state signature transaction, we collect the signature.
        if (systemTxn instanceof StateSignatureTransaction txn) {
            blockStreamProducer.writeStateSignatureTransaction(txn);
        }

        blockStreamProducer.writeSystemTransaction(systemTxn);
    }

    /** {@inheritDoc} */
    @Override
    public void endUserTransaction(
            @NonNull final Stream<SingleTransactionRecord> singleTransactionRecordStream,
            @NonNull final HederaState state) {
        // Throw an exception saying that this isn't implemented for this class.
        throw new UnsupportedOperationException("endUserTransaction is not implemented for BlockStreamManagerImpl");
    }

    /**
     * {@inheritDoc}
     *
     * @return false, because we do not start a new block at the beginning of a user transaction.
     */
    @Override
    public boolean startUserTransaction(@NonNull final Instant consensusTime, @NonNull final HederaState state) {
        // Our new block does not begin at a user transaction. It begins when beginBlock is called on
        // a new round.
        return false;
    }

    /** {@inheritDoc} */
    //    @Override
    public void endProcessUserTransaction(
            @NonNull final ProcessUserTransactionResult result, @NonNull final HederaState state) {

        // check if we need to run event recovery before we can write any new records to stream
        if (!this.eventRecoveryCompleted) {
            // FUTURE create event recovery class and call it here. Should this be in startUserTransaction()?
            this.eventRecoveryCompleted = true;
        }

        // Write the user transaction records to the block stream.
        blockStreamProducer.writeUserTransactionItems(result);
    }

    // =================================================================================================================
    // StateChangesSink implementation

    /** {@inheritDoc} */
    @Nullable
    @Override
    public void writeStateChanges(StateChanges stateChanges) {
        blockStreamProducer.writeStateChanges(stateChanges);
    }

    // =================================================================================================================
    // Running Hash Getter Methods

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Bytes getRunningHash() {
        return blockStreamProducer.getRunningHash();
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes getNMinus3RunningHash() {
        return blockStreamProducer.getNMinus3RunningHash();
    }

    // =================================================================================================================
    // BlockRecordInfo Implementation

    /** {@inheritDoc} */
    @Override
    public long lastBlockNo() {
        return lastBlockInfo.lastBlockNumber();
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Instant firstConsTimeOfLastBlock() {
        return BlockRecordInfoUtils.firstConsTimeOfLastBlock(lastBlockInfo);
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Instant firstConsTimeOfCurrentBlock() {
        return BlockRecordInfoUtils.firstConsTimeOfCurrentBlock(lastBlockInfo);
    }

    @Override
    public @NonNull Timestamp currentBlockTimestamp() {
        return lastBlockInfo.firstConsTimeOfCurrentBlockOrThrow();
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes lastBlockHash() {
        return BlockRecordInfoUtils.lastBlockHash(lastBlockInfo);
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes blockHashByBlockNumber(final long blockNo) {
        return BlockRecordInfoUtils.blockHashByBlockNumber(lastBlockInfo, blockNo);
    }

    /** {@inheritDoc} */
    @Override
    public void advanceConsensusClock(@NonNull final Instant consensusTime, @NonNull final HederaState state) {
        final var builder = this.lastBlockInfo
                .copyBuilder()
                .consTimeOfLastHandledTxn(Timestamp.newBuilder()
                        .seconds(consensusTime.getEpochSecond())
                        .nanos(consensusTime.getNano()));
        if (!this.lastBlockInfo.migrationRecordsStreamed()) {
            // Any records created during migration should have been published already. Now we shut off the flag to
            // disallow further publishing
            builder.migrationRecordsStreamed(true);
        }
        final var newBlockInfo = builder.build();

        // Update the latest block info in state
        final var states = state.getWritableStates(BlockRecordService.NAME);
        final var blockInfoState = states.<BlockInfo>getSingleton(BlockRecordService.BLOCK_INFO_STATE_KEY);
        blockInfoState.put(newBlockInfo);
        // Commit the changes. We don't ever want to roll back when advancing the consensus clock
        ((WritableSingletonStateBase<BlockInfo>) blockInfoState).commit();

        // Cache the updated block info
        this.lastBlockInfo = newBlockInfo;
    }

    // =================================================================================================================
    // FunctionalBlockRecordManager Implementation

    /** {@inheritDoc} */
    @Override
    public void processRound(
            @NonNull final HederaState state,
            @NonNull final Round round,
            @NonNull final CompletableFuture<BlockStateProof> blockPersisted,
            @NonNull final Runnable runnable) {

        // At the beginning of a round, we create a new StateProofProducer. The StateProofProducer is responsible for
        // collecting system transaction asynchronously outside the single threaded handle workflow. It is also able to
        // asynchronously produce a state proof, once enough signatures have been collected for the round. The
        // production of the state proof triggers the end of the block by calling blockStreamProducer.endBlock.

        final BlockStateProofProducer stateProofProducer = new BlockStateProofProducer(state, round.getRoundNum());

        try {
            BlockObserverSingleton.getInstanceOrThrow().recordRoundStateChanges(this, round, () -> {
                this.startRound(state, round);
                try {
                    runnable.run();
                } finally {
                    // Ensure any opened rounds are closed.
                    this.endRound(state);
                }
            });
        } finally {
            // Regardless of what happens in the try block, attempt to close the block.
            // The result of `endBlock` is directed to the `blockPersisted`.
            // For the concurrent implementation, this needs to happen on the blockStreamProducer thread and not the
            // handle thread, thus endBlock takes over execution.
            blockStreamProducer.endBlock(stateProofProducer, blockPersisted);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void processUserTransaction(
            @NonNull final Instant consensusTime,
            @NonNull final HederaState state,
            @NonNull final ConsensusTransaction platformTxn,
            @NonNull final Supplier<ProcessUserTransactionResult> callable) {
        BlockObserverSingleton.getInstanceOrThrow().recordUserTransactionStateChanges(this, platformTxn, () -> {
            this.startUserTransaction(consensusTime, state);
            ProcessUserTransactionResult result = null;
            try {
                result = callable.get();
            } finally {
                // In BlockStreams we want to make sure we close any opened transactions.
                this.endProcessUserTransaction(result, state);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void processConsensusEvent(
            @NonNull HederaState state, @NonNull ConsensusEvent platformEvent, @NonNull Runnable runnable) {
        BlockObserverSingleton.getInstanceOrThrow().recordEventStateChanges(this, platformEvent, () -> {
            this.startConsensusEvent(state, platformEvent);
            try {
                runnable.run();
            } finally {
                // In BlockStreams we want to make sure we close any opened rounds.
                this.endConsensusEvent(state, platformEvent);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void processSystemTransaction(
            @NonNull HederaState state,
            @NonNull final NodeInfo creator,
            @NonNull ConsensusTransaction systemTxn,
            @NonNull Runnable runnable) {
        BlockObserverSingleton.getInstanceOrThrow().recordSystemTransactionStateChanges(this, systemTxn, () -> {
            this.startSystemTransaction(state, systemTxn);
            try {
                runnable.run();
            } finally {
                // In BlockStreams we want to make sure we close any opened rounds.
                this.endSystemTransaction(state, creator, systemTxn);
            }
        });
    }

    // =================================================================================================================
    // Private Methods

    /**
     * The provisional current block number. This is the block number of the block we are currently writing to. This is
     * provisional because the block is not yet complete.
     * @return The provisional current block number.
     */
    private long provisionalCurrentBlockNumber() {
        return lastBlockInfo.lastBlockNumber() + 1;
    }

    private Instant consTimeOfLastHandledTxn() {
        final var consensusTimestamp = lastBlockInfo.consTimeOfLastHandledTxn();
        return Instant.ofEpochSecond(consensusTimestamp.seconds(), consensusTimestamp.nanos());
    }

    /**
     * Begin a new block.
     */
    private void beginBlock() {
        blockOpen = true;

        // TODO(nickpoorman): I'm not sure this is correct...
        // When a new block is created, we must let the blockStreamProducer know, so it can create a new block.
        blockStreamProducer.beginBlock();
    }

    /**
     * End the current block.
     */
    private void updateBlockInfoForEndRound(@NonNull final HederaState state) {
        // TODO(nickpoorman): I'm not sure I like this. We can store this, however I don't think it makes sense even in
        //  the case of a restart to load it back from state. This is because we will need to open a connection to the
        //  block node. If the connection to the block node is closed, opening a new one would require us to send the
        //  entire block once again. Unless we want to design the block node API in such as way as that restarting
        //  uploading to the block node allows you to reference a previous upload. This would be a nice feature, but I'm
        //  not sure it's worth the complexity.
        blockOpen = false;

        // Compute the state for the newly completed block. The `lastBlockHashBytes` is the running hash after
        // the last transaction.
        final var justFinishedBlockNumber = provisionalCurrentBlockNumber();
        final var lastBlockHashBytes = blockStreamProducer.getRunningHash();
        lastBlockInfo = infoOfJustFinished(lastBlockInfo, justFinishedBlockNumber, lastBlockHashBytes);

        // Update BlockInfo state.
        final var states = state.getWritableStates(BlockRecordService.NAME);
        final var blockInfoState = states.<BlockInfo>getSingleton(BlockRecordService.BLOCK_INFO_STATE_KEY);
        blockInfoState.put(lastBlockInfo);

        final var consensusTime = consTimeOfLastHandledTxn();

        // Log end of block if needed.
        if (logger.isDebugEnabled()) {
            logger.debug(
                    """
                                    --- BLOCK UPDATE ---
                                      Finished: #{} @ {} with hash {}
                                      Starting: #{} @ {}""",
                    justFinishedBlockNumber,
                    firstConsTimeOfLastBlock(),
                    new Hash(lastBlockHashBytes.toByteArray(), DigestType.SHA_384),
                    justFinishedBlockNumber + 1,
                    consensusTime);
        }
    }

    /**
     * Create a new updated BlockInfo from existing BlockInfo and new block information. BlockInfo stores block hashes as a single
     * byte array, so we need to append or if full shift left and insert new block hash.
     *
     * @param lastBlockInfo The current block info
     * @param justFinishedBlockNumber The new block number
     * @param hashOfJustFinishedBlock The new block hash
     */
    private BlockInfo infoOfJustFinished(
            @NonNull final BlockInfo lastBlockInfo,
            @NonNull final long justFinishedBlockNumber,
            @NonNull final Bytes hashOfJustFinishedBlock) {
        // compute new block hashes bytes
        final byte[] blockHashesBytes = lastBlockInfo.blockHashes().toByteArray();
        byte[] newBlockHashesBytes;
        if (blockHashesBytes.length < numBlockHashesToKeepBytes) {
            // append new hash bytes to end
            newBlockHashesBytes = new byte[blockHashesBytes.length + HASH_SIZE];
            System.arraycopy(blockHashesBytes, 0, newBlockHashesBytes, 0, blockHashesBytes.length);
            hashOfJustFinishedBlock.getBytes(0, newBlockHashesBytes, newBlockHashesBytes.length - HASH_SIZE, HASH_SIZE);
        } else {
            // shift bytes left by HASH_SIZE and then set new hash bytes to at end HASH_SIZE bytes
            newBlockHashesBytes = blockHashesBytes;
            System.arraycopy(
                    newBlockHashesBytes, HASH_SIZE, newBlockHashesBytes, 0, newBlockHashesBytes.length - HASH_SIZE);
            hashOfJustFinishedBlock.getBytes(0, newBlockHashesBytes, newBlockHashesBytes.length - HASH_SIZE, HASH_SIZE);
        }

        return new BlockInfo(
                justFinishedBlockNumber,
                lastBlockInfo.firstConsTimeOfCurrentBlock(),
                Bytes.wrap(newBlockHashesBytes),
                lastBlockInfo.consTimeOfLastHandledTxn(),
                lastBlockInfo.migrationRecordsStreamed(),
                null);
    }

    private void updateRunningHashesInState(@NonNull final HederaState state) {
        // We get the latest running hash from the BlockStreamProducer, blocking if needed for it to be computed.
        final var currentRunningHash = blockStreamProducer.getRunningHash();
        // Update running hashes in state with the latest running hash and the previous 3 running hashes.
        final var states = state.getWritableStates(BlockRecordService.NAME);
        final var runningHashesState = states.<RunningHashes>getSingleton(BlockRecordService.RUNNING_HASHES_STATE_KEY);
        final var existingRunningHashes = runningHashesState.get();
        assert existingRunningHashes != null : "This cannot be null because genesis migration sets it";
        runningHashesState.put(new RunningHashes(
                currentRunningHash,
                existingRunningHashes.runningHash(),
                existingRunningHashes.nMinus1RunningHash(),
                existingRunningHashes.nMinus2RunningHash()));
        // Commit the changes to the merkle tree.
        ((WritableSingletonStateBase<RunningHashes>) runningHashesState).commit();
    }

    private boolean isBlockOpen() {
        return blockOpen;
    }

    private void resetRoundsUntilNextBlock() {
        roundsUntilNextBlock = numRoundsInBlock;
    }
}
