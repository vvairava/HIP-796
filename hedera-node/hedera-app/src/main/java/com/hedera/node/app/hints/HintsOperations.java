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

package com.hedera.node.app.hints;

import com.hedera.cryptography.bls.BlsPrivateKey;
import com.hedera.cryptography.bls.BlsPublicKey;
import com.hedera.cryptography.bls.BlsSignature;
import com.hedera.hapi.node.state.hints.HintsKey;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

public interface HintsOperations {
    /**
     * Signs the given message with the given private key.
     * @param message the message
     * @param key the private key
     * @return the signature
     */
    BlsSignature signPartial(@NonNull Bytes message, @NonNull BlsPrivateKey key);

    /**
     * Verifies the given signature for the given message and public key.
     * @param message the message
     * @param signature the signature
     * @param publicKey the public key
     * @return true if the signature is valid; false otherwise
     */
    boolean verifyPartial(@NonNull Bytes message, @NonNull BlsSignature signature, @NonNull BlsPublicKey publicKey);

    /**
     * Aggregates the signatures for the given party ids with the given aggregation key.
     * @param aggregationKey the aggregation key
     * @param signatures the signatures by party id
     * @return the aggregated signature
     */
    Bytes aggregateSignatures(@NonNull Bytes aggregationKey, @NonNull Map<Long, Bytes> signatures);

    /**
     * Extracts the public key for the given party id from the given aggregation key.
     * @param aggregationKey the aggregation key
     * @param partyId the party id
     * @return the public key
     */
    BlsPublicKey extractPublicKey(@NonNull Bytes aggregationKey, long partyId);

    /**
     * Computes the hints for the given public key and number of parties.
     * @param publicKey the public key
     * @param n the number of parties
     * @return the hints
     */
    Bytes computeHints(@NonNull BlsPublicKey publicKey, int n);

    /**
     * Validates the hints for the given public key and number of parties.
     *
     * @param publicKey the public key
     * @param hints the hints
     * @param n the number of parties
     * @return true if the hints are valid; false otherwise
     */
    boolean validateHints(@NonNull BlsPublicKey publicKey, @NonNull Bytes hints, int n);

    /**
     * Aggregates the given validated hint keys and party weights into a {@link PreprocessedKeys}.
     * @param hintKeys the valid hint keys by party id
     * @param weights the weights by party id
     * @param n the number of parties
     * @return the aggregated keys
     */
    PreprocessedKeys aggregate(@NonNull Map<Long, HintsKey> hintKeys, @NonNull Map<Long, Long> weights, int n);
}
