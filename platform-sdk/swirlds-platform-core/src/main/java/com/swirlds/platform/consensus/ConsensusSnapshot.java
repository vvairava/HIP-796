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

package com.swirlds.platform.consensus;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.platform.state.MinGenInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A snapshot of consensus at a particular round. This is all the information (except events)
 * consensus needs to continue from a particular point. Apart from this record, consensus needs all
 * non-ancient events to continue.
 */
public class ConsensusSnapshot implements SelfSerializable {
    private static final long CLASS_ID = 0xe9563ac8048b7abcL;
    private static final int MAX_JUDGES = 1000;

    private long round;
    private List<Hash> judgeHashes;
    private List<MinGenInfo> minGens;
    private long nextConsensusNumber;
    private Instant consensusTimestamp;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    public ConsensusSnapshot() {}

    /**
     * @param round
     * 		the latest round for which fame has been decided
     * @param judgeHashes
     * 		the hashes of all the judges for this round, ordered by their creator ID
     * @param minGens
     * 		the round generation numbers for all non-ancient rounds
     * @param nextConsensusNumber
     * 		the consensus order of the next event that will reach consensus
     * @param consensusTimestamp
     * 		the consensus time of this snapshot
     */
    public ConsensusSnapshot(
            long round,
            @NonNull List<Hash> judgeHashes,
            @NonNull List<MinGenInfo> minGens,
            long nextConsensusNumber,
            @NonNull Instant consensusTimestamp) {
        this.round = round;
        this.judgeHashes = Objects.requireNonNull(judgeHashes);
        this.minGens = Objects.requireNonNull(minGens);
        this.nextConsensusNumber = nextConsensusNumber;
        this.consensusTimestamp = Objects.requireNonNull(consensusTimestamp);
    }

    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        out.writeLong(round);
        out.writeSerializableList(judgeHashes, false, true);
        MinGenInfo.serializeList(minGens, out);
        out.writeLong(nextConsensusNumber);
        out.writeInstant(consensusTimestamp);
    }

    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int version) throws IOException {
        round = in.readLong();
        judgeHashes = in.readSerializableList(MAX_JUDGES, false, Hash::new);
        minGens = MinGenInfo.deserializeList(in);
        nextConsensusNumber = in.readLong();
        consensusTimestamp = in.readInstant();
    }

    /**
     * @return the round number of this snapshot
     */
    public long round() {
        return round;
    }

    /**
     * @return the hashes of all the judges for this round, ordered by their creator ID
     */
    public @NonNull List<Hash> judgeHashes() {
        return judgeHashes;
    }

    /**
     * @return the round generation numbers for all non-ancient rounds
     */
    public @NonNull List<MinGenInfo> minGens() {
        return minGens;
    }

    /**
     * @return the consensus order of the next event that will reach consensus
     */
    public long nextConsensusNumber() {
        return nextConsensusNumber;
    }

    /**
     * @return the consensus time of this snapshot
     */
    public @NonNull Instant consensusTimestamp() {
        return consensusTimestamp;
    }

    /**
     * Returns the minimum generation below which all events are ancient
     *
     * @param roundsNonAncient
     * 		the number of non-ancient rounds
     * @return minimum non-ancient generation
     */
    public long getMinimumGenerationNonAncient(final int roundsNonAncient) {
        return RoundCalculationUtils.getMinGenNonAncient(roundsNonAncient, round, this::getMinGen);
    }

    /**
     * The minimum generation of famous witnesses for the round specified. This method only looks at non-ancient rounds
     * contained within this state.
     *
     * @param round the round whose minimum generation will be returned
     * @return the minimum generation for the round specified
     * @throws NoSuchElementException if the generation information for this round is not contained withing this state
     */
    public long getMinGen(final long round) {
        for (final MinGenInfo info : minGens()) {
            if (info.round() == round) {
                return info.minimumGeneration();
            }
        }
        throw new NoSuchElementException("No minimum generation found for round: " + round);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("round", round)
                .append("judgeHashes", judgeHashes)
                .append("minGens", minGens)
                .append("nextConsensusNumber", nextConsensusNumber)
                .append("consensusTimestamp", consensusTimestamp)
                .toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ConsensusSnapshot snapshot = (ConsensusSnapshot) o;
        return round == snapshot.round
                && nextConsensusNumber == snapshot.nextConsensusNumber
                && judgeHashes.equals(snapshot.judgeHashes)
                && minGens.equals(snapshot.minGens)
                && Objects.equals(consensusTimestamp, snapshot.consensusTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(round, judgeHashes, minGens, nextConsensusNumber, consensusTimestamp);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }
}
