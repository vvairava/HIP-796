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

package com.hedera.node.app.service.contract.impl.test.exec.utils;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.*;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.streams.CallOperationType;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionsHelper;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionsHelperTest {
    @Mock
    private Operation operation;

    @Mock
    private MessageFrame frame;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    private final ActionsHelper subject = new ActionsHelper();

    @Test
    void representsCallToMissingAddressAsExpected() {
        given(frame.getRemainingGas()).willReturn(REMAINING_GAS);
        given(frame.getMessageStackDepth()).willReturn(STACK_DEPTH);
        givenResolvableEvmAddress();
        given(frame.getStackItem(1)).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(operation.getOpcode()).willReturn(0xF1);
        given(frame.getContractAddress()).willReturn(EIP_1014_ADDRESS);
        given(frame.getCurrentOperation()).willReturn(operation);

        final var expectedAction = ContractAction.newBuilder()
                .callType(ContractActionType.CALL)
                .gas(REMAINING_GAS)
                .callDepth(STACK_DEPTH + 1)
                .callingContract(CALLED_CONTRACT_ID)
                .targetedAddress(tuweniToPbjBytes(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .error(Bytes.wrap("INVALID_SOLIDITY_ADDRESS".getBytes()))
                .callOperationType(CallOperationType.OP_CALL)
                .build();
        final var actualAction = subject.createSynthActionForMissingAddressIn(frame);

        assertEquals(expectedAction, actualAction);
    }

    private void givenResolvableEvmAddress() {
        given(frame.getWorldUpdater()).willReturn(worldUpdater);
        given(worldUpdater.getHederaContractId(EIP_1014_ADDRESS)).willReturn(CALLED_CONTRACT_ID);
    }
}
