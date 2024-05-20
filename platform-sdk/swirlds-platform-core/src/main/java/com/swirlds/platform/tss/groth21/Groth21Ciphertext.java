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

package com.swirlds.platform.tss.groth21;

import com.swirlds.platform.tss.TssCiphertext;
import com.swirlds.platform.tss.TssPrivateKey;
import com.swirlds.platform.tss.TssShareId;
import com.swirlds.platform.tss.ecdh.EcdhPrivateKey;
import com.swirlds.platform.tss.verification.PublicKey;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A TSS ciphertext, as utilized by the Groth21 scheme.
 */
public class Groth21Ciphertext<P extends PublicKey> implements TssCiphertext<P> {

    // TODO: what members belong here?

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TssPrivateKey<P> decryptPrivateKey(
            @NonNull final EcdhPrivateKey ecdhPrivateKey, @NonNull final TssShareId shareId) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBytes() {
        throw new UnsupportedOperationException("Not implemented");
    }
}
