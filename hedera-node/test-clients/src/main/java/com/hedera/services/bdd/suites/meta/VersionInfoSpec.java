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

package com.hedera.services.bdd.suites.meta;

import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.suites.BddTestNameDoesNotMatchMethodName;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

@HapiTestSuite
public class VersionInfoSpec extends HapiSuite {
    private static final Logger log = LogManager.getLogger(VersionInfoSpec.class);
    private final Map<String, String> specConfig;

    public VersionInfoSpec(final Map<String, String> specConfig) {
        this.specConfig = specConfig;
    }

    public VersionInfoSpec() {
        specConfig = null;
    }

    public static void main(String... args) {
        new VersionInfoSpec().runSuiteSync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(discoversExpectedVersions());
    }

    @BddTestNameDoesNotMatchMethodName
    @HapiTest
    final Stream<DynamicTest> discoversExpectedVersions() {
        if (specConfig != null) {
            return customHapiSpec("getVersionInfo")
                    .withProperties(specConfig)
                    .given()
                    .when()
                    .then(getVersionInfo().withYahcliLogging().noLogging());
        } else {
            return defaultHapiSpec("discoversExpectedVersions")
                    .given()
                    .when()
                    .then(getVersionInfo().logged().hasNoDegenerateSemvers());
        }
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given()
                .when()
                .then(sendModified(withSuccessivelyVariedQueryIds(), QueryVerbs::getVersionInfo));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
