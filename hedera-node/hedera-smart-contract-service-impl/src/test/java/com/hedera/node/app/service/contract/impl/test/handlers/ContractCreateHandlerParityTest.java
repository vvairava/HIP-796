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

package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.test.factories.scenarios.ContractCreateScenarios.*;
import static com.hedera.test.factories.scenarios.CryptoTransferScenarios.MISC_ACCOUNT_KT;
import static com.hedera.test.factories.txns.ContractCreateFactory.DEFAULT_ADMIN_KT;
import static com.hedera.test.factories.txns.SignedTxnFactory.DEFAULT_PAYER_KT;
import static com.hedera.test.utils.KeyUtils.sanityRestored;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.handlers.ContractCreateHandler;
import com.hedera.node.app.spi.AccountKeyLookup;
import com.hedera.node.app.spi.meta.PreHandleContext;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractCreateHandlerParityTest {
    private AccountKeyLookup keyLookup;
    private final ContractCreateHandler subject = new ContractCreateHandler();

    @BeforeEach
    void setUp() {
        final var now = Instant.now();
        keyLookup = AdapterUtils.wellKnownKeyLookupAt();
    }

    @Test
    void getsContractCreateWithAutoRenew() {
        final var theTxn = txnFrom(CONTRACT_CREATE_WITH_AUTO_RENEW_ACCOUNT);
        final var context = new PreHandleContext(keyLookup, theTxn);
        subject.preHandle(context);

        assertThat(sanityRestored(context.getPayerKey())).isEqualTo(DEFAULT_PAYER_KT.asKey());
        assertThat(sanityRestored(context.getRequiredNonPayerKeys())).containsExactly(MISC_ACCOUNT_KT.asKey());
    }

    @Test
    void getsContractCreateNoAdminKey() {
        final var theTxn = txnFrom(CONTRACT_CREATE_NO_ADMIN_KEY);
        final var context = new PreHandleContext(keyLookup, theTxn);
        subject.preHandle(context);

        assertThat(sanityRestored(context.getPayerKey())).isEqualTo(DEFAULT_PAYER_KT.asKey());
        assertThat(sanityRestored(context.getRequiredNonPayerKeys())).isEmpty();
    }

    @Test
    void getsContractCreateDeprecatedAdminKey() {
        final var theTxn = txnFrom(CONTRACT_CREATE_DEPRECATED_CID_ADMIN_KEY);
        final var context = new PreHandleContext(keyLookup, theTxn);
        subject.preHandle(context);

        assertThat(sanityRestored(context.getPayerKey())).isEqualTo(DEFAULT_PAYER_KT.asKey());
        assertThat(sanityRestored(context.getRequiredNonPayerKeys())).isEmpty();
    }

    @Test
    void getsContractCreateWithAdminKey() {
        final var theTxn = txnFrom(CONTRACT_CREATE_WITH_ADMIN_KEY);
        final var context = new PreHandleContext(keyLookup, theTxn);
        subject.preHandle(context);

        assertThat(sanityRestored(context.getPayerKey())).isEqualTo(DEFAULT_PAYER_KT.asKey());
        assertThat(sanityRestored(context.getRequiredNonPayerKeys())).containsExactly(DEFAULT_ADMIN_KT.asKey());
    }

    private TransactionBody txnFrom(final TxnHandlingScenario scenario) {
        try {
            return scenario.platformTxn().getTxn();
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
