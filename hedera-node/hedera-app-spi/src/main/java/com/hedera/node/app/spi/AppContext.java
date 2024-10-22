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

package com.hedera.node.app.spi;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;

/**
 * Gives context to {@link com.swirlds.state.spi.Service} implementations on how the application workflows will do
 * shared functions like verifying signatures or computing the current instant.
 */
public interface AppContext {
    /**
     * The {@link Gossip} interface is used to submit transactions to the network.
     */
    interface Gossip {
        /**
         * A {@link Gossip} that throws an exception indicating it should never have been used; for example,
         * if the client code was running in a standalone mode.
         */
        Gossip UNAVAILABLE_GOSSIP = body -> {
            throw new IllegalArgumentException("" + FAIL_INVALID);
        };

        /**
         * Attempts to submit the given transaction to the network.
         * @param body the transaction to submit
         * @throws IllegalStateException if the network is not active; the client should retry later
         * @throws IllegalArgumentException if body is invalid; so the client can retry immediately with a
         * different transaction id if the exception's message is {@link ResponseCodeEnum#DUPLICATE_TRANSACTION}
         */
        void submit(@NonNull TransactionBody body);
    }

    /**
     * The source of the current instant.
     * @return the instant source
     */
    InstantSource instantSource();

    /**
     * The signature verifier the application workflows will use.
     * @return the signature verifier
     */
    SignatureVerifier signatureVerifier();

    /**
     * The {@link Gossip} can be used to submit transactions to the network when it is active.
     * @return the gossip interface
     */
    Gossip gossip();
}
