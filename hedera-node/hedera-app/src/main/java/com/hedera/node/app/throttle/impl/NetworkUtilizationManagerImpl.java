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

package com.hedera.node.app.throttle.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.MonoMultiplierSources;
import com.hedera.node.app.service.mono.fees.congestion.MultiplierSources;
import com.hedera.node.app.state.HederaState;
import com.hedera.node.app.throttle.HandleThrottleAccumulator;
import com.hedera.node.app.throttle.NetworkUtilizationManager;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;

/**
 * Implementation of {@link NetworkUtilizationManager}  that delegates to injected {@link HandleThrottleAccumulator} and {@link
 * MultiplierSources}.
 */
public class NetworkUtilizationManagerImpl implements NetworkUtilizationManager {
    // Used to update network utilization after a user-submitted transaction fails the signature
    // validity
    // screen; the stand-in is a CryptoTransfer because it best reflects the work done charging fees
    static final TransactionInfo STAND_IN_CRYPTO_TRANSFER = new TransactionInfo(
            Transaction.DEFAULT, TransactionBody.DEFAULT, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER);

    private final HandleThrottleAccumulator handleThrottling;

    private final MonoMultiplierSources multiplierSources;

    @Inject
    public NetworkUtilizationManagerImpl(
            @NonNull final HandleThrottleAccumulator handleThrottling,
            @NonNull final MonoMultiplierSources multiplierSources) {
        this.handleThrottling = requireNonNull(handleThrottling, "handleThrottling must not be null");
        this.multiplierSources = requireNonNull(multiplierSources, "multiplierSources must not be null");
    }

    @Override
    public void trackTxn(@NonNull final TransactionInfo txnInfo, Instant consensusTime, HederaState state) {
        track(txnInfo, consensusTime, state);
    }

    @Override
    public void trackFeePayments(Instant consensusNow, HederaState state) {
        track(STAND_IN_CRYPTO_TRANSFER, consensusNow, state);
    }

    private void track(@NonNull TransactionInfo txnInfo, Instant consensusTime, HederaState state) {
        handleThrottling.shouldThrottle(txnInfo, consensusTime, state);
        multiplierSources.updateMultiplier(consensusTime);
    }

    @Override
    public boolean wasLastTxnGasThrottled() {
        return handleThrottling.wasLastTxnGasThrottled();
    }
}
