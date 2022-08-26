/*
 * Copyright (C) 2022 Hedera Hashgraph, LLC
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
package com.hedera.services.state.expiry.removal;

import static com.hedera.services.state.expiry.removal.ContractGC.*;
import static com.hedera.services.state.virtual.VirtualBlobKey.Type.CONTRACT_BYTECODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.internals.BitPackUtils;
import com.hedera.services.state.virtual.ContractKey;
import com.hedera.services.state.virtual.ContractStorageListMutation;
import com.hedera.services.state.virtual.IterableContractValue;
import com.hedera.services.state.virtual.VirtualBlobKey;
import com.hedera.services.state.virtual.VirtualBlobValue;
import com.hedera.services.throttling.ExpiryThrottle;
import com.hedera.services.utils.EntityNum;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.virtualmap.VirtualMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractGCTest {
    @Mock private ExpiryThrottle expiryThrottle;
    @Mock private GlobalDynamicProperties dynamicProperties;
    @Mock private MerkleMap<EntityNum, MerkleAccount> contracts;
    @Mock private VirtualMap<ContractKey, IterableContractValue> storage;
    @Mock private VirtualMap<VirtualBlobKey, VirtualBlobValue> bytecode;
    @Mock private ContractGC.RemovalFacilitation removalFacilitation;

    private ContractGC subject;

    @BeforeEach
    void setUp() {
        subject =
                new ContractGC(
                        expiryThrottle,
                        dynamicProperties,
                        () -> contracts,
                        () -> storage,
                        () -> bytecode);
        subject.setRemovalFacilitation(removalFacilitation);
    }

    @Test
    void removesBytecodeIfPresent() {
        given(expiryThrottle.allow(BYTECODE_REMOVAL_WORK)).willReturn(true);
        assertTrue(subject.expireBestEffort(contractNum, contractNoKvPairs));
        verify(bytecode).remove(bytecodeKey);
    }

    @Test
    void doesntRemovesBytecodeIfNoCapacity() {
        assertFalse(subject.expireBestEffort(contractNum, contractNoKvPairs));
        verifyNoInteractions(bytecode);
    }

    @Test
    void justRemovesAllKvPairsIfWithinMaxToPurge() {
        given(expiryThrottle.allow(ROOT_KEY_UPDATE_WORK)).willReturn(true);
        given(expiryThrottle.allow(BYTECODE_REMOVAL_WORK)).willReturn(true);
        given(expiryThrottle.allow(NEXT_SLOT_REMOVAL_WORK)).willReturn(true);
        given(expiryThrottle.allow(ONLY_SLOT_REMOVAL_WORK)).willReturn(true);
        given(
                        removalFacilitation.removeNext(
                                eq(rootKey), eq(rootKey), any(ContractStorageListMutation.class)))
                .willReturn(interKey);
        given(
                        removalFacilitation.removeNext(
                                eq(interKey), eq(interKey), any(ContractStorageListMutation.class)))
                .willReturn(tailKey);
        given(
                        removalFacilitation.removeNext(
                                eq(tailKey), eq(tailKey), any(ContractStorageListMutation.class)))
                .willReturn(null);

        final var done = subject.expireBestEffort(contractNum, contractSomeKvPairs);

        assertTrue(done);
    }

    @Test
    void reclaimsThrottleCapacityIfNoSlotsCanBeRemoved() {
        given(expiryThrottle.allow(ROOT_KEY_UPDATE_WORK)).willReturn(true);

        final var done = subject.expireBestEffort(contractNum, contractSomeKvPairs);

        assertFalse(done);
        assertEquals(3, contractSomeKvPairs.getNumContractKvPairs());
        verify(expiryThrottle).reclaimLastAllowedUse();
    }

    private static ContractKey asKey(final int[] uint256Key) {
        return new ContractKey(BitPackUtils.numFromCode(contractNum.intValue()), uint256Key);
    }

    private void givenMutableContract(final MerkleAccount contract) {
        given(contracts.getForModify(contractNum)).willReturn(contract);
    }

    private static final EntityNum contractNum = EntityNum.fromLong(666);
    private static final VirtualBlobKey bytecodeKey =
            new VirtualBlobKey(CONTRACT_BYTECODE, contractNum.intValue());
    private static final int[] rootUint256Key = new int[] {1, 2, 3, 4, 5, 6, 7, 8};
    private static final int[] interUint256Key = new int[] {2, 3, 4, 5, 6, 7, 8, 9};
    private static final int[] tailUint256Key = new int[] {3, 4, 5, 6, 7, 8, 9, 10};
    private static final ContractKey rootKey = asKey(rootUint256Key);
    private static final ContractKey interKey = asKey(interUint256Key);
    private static final ContractKey tailKey = asKey(tailUint256Key);
    private final MerkleAccount contractSomeKvPairs =
            MerkleAccountFactory.newContract()
                    .balance(0)
                    .number(contractNum)
                    .numKvPairs(3)
                    .firstContractKey(rootUint256Key)
                    .get();
    private final MerkleAccount contractNoKvPairs =
            MerkleAccountFactory.newContract().number(contractNum).balance(0).get();
}
