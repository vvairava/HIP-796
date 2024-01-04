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

plugins {
    id("com.hedera.hashgraph.sdk.conventions")
    id("com.hedera.hashgraph.platform-maven-publish")
    id("com.hedera.hashgraph.benchmark-conventions")
    id("java-test-fixtures")
}

mainModuleInfo {
    annotationProcessor("com.swirlds.config.processor")
    runtimeOnly("com.swirlds.config.impl")
}

jmhModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.config.api")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.test")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("jmh.core")
}

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.common.testing")
    requires("com.swirlds.config.api.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.test.framework")
    requires("org.assertj.core")
    requires("awaitility")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requiresStatic("com.github.spotbugs.annotations")
}
