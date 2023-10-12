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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateTranslator;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateDecoderTest {
    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    private final UpdateDecoder subject = new UpdateDecoder();

    private final String newName = "NEW NAME";
    private final Tuple hederaToken = Tuple.of(
            newName,
            "symbol",
            OWNER_HEADLONG_ADDRESS,
            "memo",
            true,
            1000L,
            false,
            // TokenKey
            new Tuple[] {},
            // Expiry
            Tuple.of(0L, OWNER_HEADLONG_ADDRESS, 0L));

    @BeforeEach
    void setUp() {
        given(attempt.addressIdConverter()).willReturn(addressIdConverter);
        given(addressIdConverter.convert(OWNER_HEADLONG_ADDRESS)).willReturn(OWNER_ID);
    }

    @Test
    void updateV1Works() {
        final var encoded = Bytes.wrapByteBuffer(UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION.encodeCallWithArgs(
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaToken));
        given(attempt.input()).willReturn(encoded);

        final var body = subject.decodeTokenUpdateV1(attempt);
        final var tokenUpdate = body.tokenUpdateOrThrow();
        assertEquals(tokenUpdate.name(), newName);
    }

    @Test
    void updateV2Works() {
        final var encoded = Bytes.wrapByteBuffer(UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V2.encodeCallWithArgs(
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaToken));
        given(attempt.input()).willReturn(encoded);

        final var body = subject.decodeTokenUpdateV2(attempt);
        final var tokenUpdate = body.tokenUpdateOrThrow();
        assertEquals(tokenUpdate.name(), newName);
    }

    @Test
    void updateV3Works() {
        final var encoded = Bytes.wrapByteBuffer(UpdateTranslator.TOKEN_UPDATE_INFO_FUNCTION_V3.encodeCallWithArgs(
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS, hederaToken));
        given(attempt.input()).willReturn(encoded);

        final var body = subject.decodeTokenUpdateV3(attempt);
        final var tokenUpdate = body.tokenUpdateOrThrow();
        assertEquals(tokenUpdate.name(), newName);
    }
}
