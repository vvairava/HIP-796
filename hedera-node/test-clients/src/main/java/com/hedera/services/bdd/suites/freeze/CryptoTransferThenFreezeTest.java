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

package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForNodesToFreeze;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import com.hedera.services.bdd.suites.perf.crypto.CryptoTransferLoadTest;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class CryptoTransferThenFreezeTest extends CryptoTransferLoadTest {
    private static final Logger log = LogManager.getLogger(CryptoTransferThenFreezeTest.class);

    public static void main(String... args) {
        parseArgs(args);

        CryptoTransferThenFreezeTest suite = new CryptoTransferThenFreezeTest();
        suite.runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(runCryptoTransfers(), freezeAfterTransfers());
    }

    final Stream<DynamicTest> freezeAfterTransfers() {
        PerfTestLoadSettings settings = new PerfTestLoadSettings();
        return defaultHapiSpec("FreezeAfterTransfers")
                .given(
                        withOpContext(
                                (spec, ignore) -> settings.setFrom(spec.setup().ciPropertiesMap())),
                        logIt(ignore -> settings.toString()))
                .when(freezeOnly().startingIn(30).seconds().payingWith(GENESIS))
                .then(
                        // wait for the nodes to freeze (fails if they don't freeze)
                        waitForNodesToFreeze(75));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
