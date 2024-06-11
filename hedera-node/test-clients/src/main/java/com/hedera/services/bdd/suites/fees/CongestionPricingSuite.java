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

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.propertyPreservingHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.*;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.ThrottleDefsLoader.protoDefsFromResource;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

public class CongestionPricingSuite {
    private static final Logger log = LogManager.getLogger(CongestionPricingSuite.class);

    private static final String FEES_PERCENT_CONGESTION_MULTIPLIERS = "fees.percentCongestionMultipliers";
    private static final String FEES_MIN_CONGESTION_PERIOD = "fees.minCongestionPeriod";
    private static final String CIVILIAN_ACCOUNT = "civilian";
    private static final String SECOND_ACCOUNT = "second";
    private static final String FEE_MONITOR_ACCOUNT = "feeMonitor";

    @LeakyHapiTest({PROPERTY_OVERRIDES, THROTTLE_OVERRIDES})
    final Stream<DynamicTest> canUpdateMultipliersDynamically() {
        var artificialLimits = protoDefsFromResource("testSystemFiles/artificial-limits-congestion.json");
        var defaultThrottles = protoDefsFromResource("testSystemFiles/throttles-dev.json");
        var contract = "Multipurpose";
        String tmpMinCongestionPeriod = "1";

        AtomicLong normalPrice = new AtomicLong();
        AtomicLong sevenXPrice = new AtomicLong();
        // Send enough gas with each transaction to keep the throttle over the
        // 1% of 15M = 150_000 congestion limit
        final var gasToOffer = 200_000L;

        return propertyPreservingHapiSpec("CanUpdateMultipliersDynamically")
                .preserving(FEES_PERCENT_CONGESTION_MULTIPLIERS, FEES_MIN_CONGESTION_PERIOD)
                .given(
                        cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                        uploadInitCode(contract),
                        contractCreate(contract),
                        contractCall(contract)
                                .payingWith(CIVILIAN_ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .gas(gasToOffer)
                                .sending(ONE_HBAR)
                                .via("cheapCall"),
                        getTxnRecord("cheapCall")
                                .providingFeeTo(normalFee -> {
                                    log.info("Normal fee is {}", normalFee);
                                    normalPrice.set(normalFee);
                                })
                                .logged())
                .when(
                        overridingTwo(
                                FEES_PERCENT_CONGESTION_MULTIPLIERS,
                                "1,7x",
                                FEES_MIN_CONGESTION_PERIOD,
                                tmpMinCongestionPeriod),
                        fileUpdate(THROTTLE_DEFS)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .contents(artificialLimits.toByteArray()),
                        sleepFor(2_000),
                        blockingOrder(IntStream.range(0, 10)
                                .mapToObj(i -> new HapiSpecOperation[] {
                                    usableTxnIdNamed("uncheckedTxn" + i).payerId(CIVILIAN_ACCOUNT),
                                    uncheckedSubmit(contractCall(contract)
                                                    .signedBy(CIVILIAN_ACCOUNT)
                                                    .fee(ONE_HUNDRED_HBARS)
                                                    .gas(gasToOffer)
                                                    .sending(ONE_HBAR)
                                                    .txnId("uncheckedTxn" + i))
                                            .payingWith(GENESIS),
                                    sleepFor(125)
                                })
                                .flatMap(Arrays::stream)
                                .toArray(HapiSpecOperation[]::new)),
                        contractCall(contract)
                                .payingWith(CIVILIAN_ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .gas(gasToOffer)
                                .sending(ONE_HBAR)
                                .via("pricyCall"))
                .then(
                        getReceipt("pricyCall").logged(),
                        getTxnRecord("pricyCall").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                            log.info("Congestion fee is {}", congestionFee);
                            sevenXPrice.set(congestionFee);
                        }),

                        /* Make sure the multiplier is reset before the next spec runs */
                        fileUpdate(THROTTLE_DEFS)
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .contents(defaultThrottles.toByteArray()),

                        /* Check for error after resetting settings. */
                        withOpContext((spec, opLog) -> Assertions.assertEquals(
                                7.0,
                                (1.0 * sevenXPrice.get()) / normalPrice.get(),
                                0.1,
                                "~7x multiplier should be in affect!")));
    }

    @LeakyHapiTest({PROPERTY_OVERRIDES, THROTTLE_OVERRIDES})
    final Stream<DynamicTest> canUpdateMultipliersDynamically2() {
        var artificialLimits = protoDefsFromResource("testSystemFiles/artificial-limits-congestion.json");
        var defaultThrottles = protoDefsFromResource("testSystemFiles/throttles-dev.json");
        String tmpMinCongestionPeriod = "1";

        AtomicLong normalPrice = new AtomicLong();
        AtomicLong sevenXPrice = new AtomicLong();

        return propertyPreservingHapiSpec("CanUpdateMultipliersDynamically2")
                .preserving(FEES_PERCENT_CONGESTION_MULTIPLIERS, FEES_MIN_CONGESTION_PERIOD)
                .given(
                        cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                        cryptoCreate(SECOND_ACCOUNT).payingWith(GENESIS).balance(ONE_HBAR),
                        cryptoCreate(FEE_MONITOR_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                        cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(CIVILIAN_ACCOUNT, SECOND_ACCOUNT, 5L))
                                .payingWith(FEE_MONITOR_ACCOUNT)
                                .via("cheapCall"),
                        getTxnRecord("cheapCall")
                                .providingFeeTo(normalFee -> {
                                    log.info("Normal fee is {}", normalFee);
                                    normalPrice.set(normalFee);
                                })
                                .logged())
                .when(
                        overridingTwo(
                                FEES_PERCENT_CONGESTION_MULTIPLIERS,
                                "1,7x",
                                FEES_MIN_CONGESTION_PERIOD,
                                tmpMinCongestionPeriod),
                        fileUpdate(THROTTLE_DEFS)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .contents(artificialLimits.toByteArray()),
                        sleepFor(2_000),
                        blockingOrder(IntStream.range(0, 20)
                                .mapToObj(i -> new HapiSpecOperation[] {
                                    usableTxnIdNamed("uncheckedTxn" + i).payerId(CIVILIAN_ACCOUNT),
                                    uncheckedSubmit(cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(
                                                            CIVILIAN_ACCOUNT, SECOND_ACCOUNT, 5L))
                                                    .txnId("uncheckedTxn" + i))
                                            .payingWith(GENESIS),
                                    sleepFor(125)
                                })
                                .flatMap(Arrays::stream)
                                .toArray(HapiSpecOperation[]::new)),
                        cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(CIVILIAN_ACCOUNT, SECOND_ACCOUNT, 5L))
                                .payingWith(FEE_MONITOR_ACCOUNT)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("pricyCall"))
                .then(
                        getTxnRecord("pricyCall").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                            log.info("Congestion fee is {}", congestionFee);
                            sevenXPrice.set(congestionFee);
                        }),

                        /* Make sure the multiplier is reset before the next spec runs */
                        fileUpdate(THROTTLE_DEFS)
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .contents(defaultThrottles.toByteArray()),
                        /* Check for error after resetting settings. */
                        withOpContext((spec, opLog) -> Assertions.assertEquals(
                                7.0,
                                (1.0 * sevenXPrice.get()) / normalPrice.get(),
                                0.1,
                                "~7x multiplier should be in affect!")));
    }
}
