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

package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_NEGATIVE_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction.NOT_APPLICABLE;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asPriorityId;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * A factory that creates a {@link HederaEvmTransaction} for static calls.
 * Used for handling the {@link ContractCallLocalQuery} flow.
 * Hevm in {@link HevmStaticTransactionFactory} is abbreviated for Hedera EVM.
 */
@QueryScope
public class HevmStaticTransactionFactory {
    private final ContractsConfig contractsConfig;
    private final QueryContext context;
    private final AccountID payerId;

    @Inject
    public HevmStaticTransactionFactory(@NonNull final QueryContext context) {
        this.context = requireNonNull(context);
        this.contractsConfig = context.configuration().getConfigData(ContractsConfig.class);
        this.payerId = requireNonNull(context.payer());
    }

    /**
     * Given a {@link Query}, creates the implied {@link HederaEvmTransaction}.
     *
     * @param query the {@link ContractCallLocalQuery} to convert
     * @return the implied {@link HederaEvmTransaction}
     */
    @NonNull
    public HederaEvmTransaction fromHapiQuery(@NonNull final Query query) {
        final var op = query.contractCallLocalOrThrow();
        assertValidCall(op);
        final var senderId = op.hasSenderId() ? op.senderIdOrThrow() : payerId;
        // For mono-service fidelity, allow calls using 0.0.X id even to contracts with a priority EVM address
        final var targetId = asPriorityId(op.contractIDOrThrow(), context.createStore(ReadableAccountStore.class));
        return new HederaEvmTransaction(
                senderId,
                null,
                targetId,
                NOT_APPLICABLE,
                op.functionParameters(),
                null,
                0L,
                op.gas(),
                1L,
                0L,
                null,
                null);
    }

    private void assertValidCall(@NonNull final ContractCallLocalQuery body) {
        validateTrue(body.gas() >= 0, CONTRACT_NEGATIVE_GAS);
        validateTrue(body.gas() <= contractsConfig.maxGasPerSec(), MAX_GAS_LIMIT_EXCEEDED);
    }
}
