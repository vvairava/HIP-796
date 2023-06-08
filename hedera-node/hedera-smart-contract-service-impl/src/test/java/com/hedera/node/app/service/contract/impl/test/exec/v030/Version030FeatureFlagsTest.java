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

package com.hedera.node.app.service.contract.impl.test.exec.v030;

import static com.hedera.node.app.service.contract.impl.exec.TransactionProcessor.CONFIG_CONTEXT_VARIABLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.v030.Version030FeatureFlags;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Version030FeatureFlagsTest {
    @Mock
    private MessageFrame frame;

    private Version030FeatureFlags subject = new Version030FeatureFlags();

    @Test
    void everythingIsDisabled() {
        assertFalse(subject.isImplicitCreationEnabled(frame));
    }

    @Test
    void create2FeatureFlagWorks() {
        final var config = new HederaTestConfigBuilder()
                .withValue("contracts.allowCreate2", false)
                .getOrCreateConfig();
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        assertFalse(subject.isCreate2Enabled(frame));
    }
}
