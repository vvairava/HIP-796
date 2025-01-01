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

package com.hedera.services.bdd.junit.hedera.embedded.fakes.hints;

import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.bls.BlsPrivateKey;
import com.hedera.cryptography.bls.BlsPublicKey;
import com.hedera.cryptography.bls.BlsSignature;
import com.hedera.hapi.node.state.hints.HintsKey;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.node.app.hints.HintsOperations;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * A fake implementation of the {@link HintsOperations} for use with embedded tests.
 */
public class FakeHintsOperations implements HintsOperations {
    @Override
    public BlsSignature signPartial(@NonNull final Bytes message, @NonNull final BlsPrivateKey key) {
        requireNonNull(message);
        requireNonNull(key);
        throw new AssertionError("Not implemented");
    }

    @Override
    public boolean verifyPartial(
            @NonNull final Bytes message,
            @NonNull final BlsSignature signature,
            @NonNull final BlsPublicKey publicKey) {
        requireNonNull(message);
        requireNonNull(signature);
        requireNonNull(publicKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public Bytes aggregateSignatures(
            @NonNull final Bytes aggregationKey, @NonNull final Map<Long, BlsSignature> signatures) {
        requireNonNull(aggregationKey);
        requireNonNull(signatures);
        throw new AssertionError("Not implemented");
    }

    @Override
    public long extractWeight(@NonNull final Bytes aggregationKey, final long partyId) {
        requireNonNull(aggregationKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public long extractTotalWeight(@NonNull final Bytes aggregationKey) {
        requireNonNull(aggregationKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public BlsPublicKey extractPublicKey(@NonNull final Bytes aggregationKey, final long partyId) {
        requireNonNull(aggregationKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public Bytes computeHints(@NonNull final BlsPrivateKey privateKey, final int n) {
        requireNonNull(privateKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public boolean validate(@NonNull final HintsKey hintsKey, final int n) {
        requireNonNull(hintsKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public PreprocessedKeys preprocess(@NonNull Map<Long, HintsKey> hintKeys, @NonNull Map<Long, Long> weights, int n) {
        requireNonNull(hintKeys);
        requireNonNull(weights);
        throw new AssertionError("Not implemented");
    }
}
