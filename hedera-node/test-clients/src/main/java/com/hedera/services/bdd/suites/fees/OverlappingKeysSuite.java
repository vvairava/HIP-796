/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyLabel.complex;
import static com.hedera.services.bdd.spec.keys.SigControl.ANY;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.SigControl.threshSigs;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyLabel;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class OverlappingKeysSuite {
    @HapiTest
    final Stream<DynamicTest> feeCalcUsesNumPayerKeys() {
        SigControl SHAPE = threshSigs(2, threshSigs(2, ANY, ANY, ANY), threshSigs(2, ANY, ANY, ANY));
        KeyLabel ONE_UNIQUE_KEY = complex(complex("X", "X", "X"), complex("X", "X", "X"));
        SigControl SIGN_ONCE = threshSigs(2, threshSigs(3, ON, OFF, OFF), threshSigs(3, OFF, OFF, OFF));

        return defaultHapiSpec("PayerSigRedundancyRecognized")
                .given(
                        newKeyNamed("repeatingKey").shape(SHAPE).labels(ONE_UNIQUE_KEY),
                        cryptoCreate("testAccount").key("repeatingKey").balance(1_000_000_000L))
                .when()
                .then(
                        QueryVerbs.getAccountInfo("testAccount")
                                .sigControl(forKey("repeatingKey", SIGN_ONCE))
                                .payingWith("testAccount")
                                .numPayerSigs(5)
                                .hasAnswerOnlyPrecheck(INSUFFICIENT_TX_FEE),
                        QueryVerbs.getAccountInfo("testAccount")
                                .sigControl(forKey("repeatingKey", SIGN_ONCE))
                                .payingWith("testAccount")
                                .numPayerSigs(6));
    }
}
