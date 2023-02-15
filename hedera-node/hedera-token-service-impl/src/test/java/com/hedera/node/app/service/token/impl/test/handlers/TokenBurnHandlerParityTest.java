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
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.node.app.service.token.impl.test.util.SigReqAdapterUtils.txnFrom;
import static com.hedera.test.factories.scenarios.TokenBurnScenarios.*;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_SUPPLY_KT;
import static com.hedera.test.factories.txns.SignedTxnFactory.DEFAULT_PAYER_KT;
import static com.hedera.test.utils.KeyUtils.sanityRestored;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.service.token.impl.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.TokenBurnHandler;
import com.hedera.node.app.service.token.impl.test.util.SigReqAdapterUtils;
import com.hedera.node.app.spi.AccountKeyLookup;
import com.hedera.test.utils.AdapterUtils;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TokenBurnHandlerParityTest {

    private AccountKeyLookup keyLookup;
    private ReadableTokenStore readableTokenStore;

    private final TokenBurnHandler subject = new TokenBurnHandler();

    @BeforeEach
    void setUp() {
        final var now = Instant.now();
        keyLookup = AdapterUtils.wellKnownKeyLookupAt(now);
        readableTokenStore = SigReqAdapterUtils.wellKnownTokenStoreAt(now);
    }

    @Test
    void getsTokenBurnWithValidId() {
        final var theTxn = txnFrom(BURN_WITH_SUPPLY_KEYED_TOKEN);
        final var meta =
                subject.preHandle(
                        theTxn,
                        theTxn.getTransactionID().getAccountID(),
                        keyLookup,
                        readableTokenStore);

        assertFalse(meta.failed());
        assertEquals(OK, meta.status());
        assertEquals(sanityRestored(meta.payerKey()), DEFAULT_PAYER_KT.asKey());
        assertEquals(1, meta.requiredNonPayerKeys().size());
        assertThat(sanityRestored(meta.requiredNonPayerKeys()), contains(TOKEN_SUPPLY_KT.asKey()));
    }

    @Test
    void getsTokenBurnWithMissingToken() {
        final var theTxn = txnFrom(BURN_WITH_MISSING_TOKEN);
        final var meta =
                subject.preHandle(
                        theTxn,
                        theTxn.getTransactionID().getAccountID(),
                        keyLookup,
                        readableTokenStore);

        assertTrue(meta.failed());
        assertEquals(INVALID_TOKEN_ID, meta.status());
        assertEquals(sanityRestored(meta.payerKey()), DEFAULT_PAYER_KT.asKey());
        assertEquals(0, meta.requiredNonPayerKeys().size());
    }

    @Test
    void getsTokenBurnWithoutSupplyKey() {
        final var theTxn = txnFrom(BURN_FOR_TOKEN_WITHOUT_SUPPLY);
        final var meta =
                subject.preHandle(
                        theTxn,
                        theTxn.getTransactionID().getAccountID(),
                        keyLookup,
                        readableTokenStore);

        assertFalse(meta.failed());
        assertEquals(OK, meta.status());
        assertEquals(sanityRestored(meta.payerKey()), DEFAULT_PAYER_KT.asKey());
        assertEquals(0, meta.requiredNonPayerKeys().size());
    }
}
