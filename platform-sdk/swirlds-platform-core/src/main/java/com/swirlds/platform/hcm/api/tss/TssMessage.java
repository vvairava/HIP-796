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

package com.swirlds.platform.hcm.api.tss;

import com.swirlds.platform.hcm.api.signaturescheme.PairingPublicKey;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A message sent as part of either genesis keying, or rekeying.
 *
 * @param shareId    the ID of the share used to generate this message
 * @param cipherText contains secrets that are being distributed
 * @param commitment a commitment to the polynomial that was used to generate the secrets
 * @param proof      a proof that the polynomial commitment is valid
 */
public record TssMessage(
        @NonNull TssShareId shareId,
        @NonNull TssMultishareCiphertext cipherText,
        @NonNull TssCommitment commitment,
        @NonNull TssProof proof) {

    /**
     * Verify that the message is valid.
     *
     * @param publicKey the public key which corresponds to the private key used to generate the message
     * @return true if the message is valid, false otherwise
     */
    boolean verify(@NonNull final PairingPublicKey publicKey) {
        return publicKey.keyElement().equals(commitment.getTerm(0)) && proof.verify(cipherText, commitment);
    }

    /**
     * Convert the message to a byte array.
     *
     * @return the byte array representation of the message
     */
    byte[] toBytes() {
        throw new UnsupportedOperationException("Not implemented");
    }
}
