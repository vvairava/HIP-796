/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;

import com.hedera.services.bdd.junit.hedera.HederaTest;
import com.hedera.services.bdd.spec.HapiSpec;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(HelloWorldExtension.class)
public class HelloWorldTest {
    @Test
    public void testHelloWorld() {
        System.out.println("Hello, World!");
    }

    @TestFactory
    public DynamicTest testDynamicHelloWorld() {
        return DynamicTest.dynamicTest("Dynamic Hello, World!", () -> {
            System.out.println("DYNAMIC EXECUTION");
        });
    }

    @HederaTest
    @TestFactory
    public Stream<DynamicTest> hwHapiSpec() {
        return HapiSpec.defaultHapiSpec("hwHapiSpec")
                .given()
                .when()
                .then(getAccountInfo(DEFAULT_PAYER).logged());
    }
}
