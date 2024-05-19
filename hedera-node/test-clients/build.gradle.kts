/*
 * Copyright (C) 2022-2024 Hedera Hashgraph, LLC
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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.hedera.gradle.services")
    id("com.hedera.gradle.shadow-jar")
}

description = "Hedera Services Test Clients for End to End Tests (EET)"

mainModuleInfo {
    runtimeOnly("org.junit.jupiter.engine")
    runtimeOnly("org.junit.platform.launcher")
}

itestModuleInfo {
    requires("com.hedera.node.test.clients")
    requires("com.hedera.node.hapi")
    requires("org.apache.commons.lang3")
    requires("org.junit.jupiter.api")
    requires("org.testcontainers")
    requires("org.testcontainers.junit.jupiter")
    requires("org.apache.commons.lang3")
}

eetModuleInfo {
    requires("com.hedera.node.test.clients")
    requires("org.junit.jupiter.api")
    requires("org.testcontainers")
    requires("org.testcontainers.junit.jupiter")
}

sourceSets {
    // Needed because "resource" directory is misnamed. See
    // https://github.com/hashgraph/hedera-services/issues/3361
    main { resources { srcDir("src/main/resource") } }

    create("rcdiff")
    create("yahcli")
}

/**
 * GitHub HAPI CI checks correspond to the following tag expressions,
 *
 * <p>(Crypto) - ./gradlew :test-clients:test -DtagExpression='CRYPTO|STREAM_VALIDATION'
 *
 * <p>(Token) - ./gradlew :test-clients:test -DtagExpression='TOKEN|STREAM_VALIDATION'
 *
 * <p>(Restart) - ./gradlew :test-clients:test -DtagExpression='RESTART|STREAM_VALIDATION'
 *
 * <p>(Smart contract) - ./gradlew :test-clients:test
 * -DtagExpression='SMART_CONTRACT|STREAM_VALIDATION'
 *
 * <p>(ND reconnect) - ./gradlew :test-clients:test -DtagExpression='ND_RECONNECT|STREAM_VALIDATION'
 *
 * <p>(Time consuming) - ./gradlew :test-clients:test
 * -DtagExpression='TIME_CONSUMING|STREAM_VALIDATION'
 */
tasks.test {
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = sourceSets.main.get().runtimeClasspath

    useJUnitPlatform {
        val expression = System.getProperty("tagExpression") ?: "any() | none()"
        includeTags(expression)
    }

    systemProperty("junit.jupiter.execution.parallel.enabled", true)
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Surprisingly, the Gradle JUnitPlatformTestExecutionListener fails to gather result
    // correctly if test classes run in parallel (concurrent execution WITHIN a test class
    // is fine). So we need to force the test classes to run in the same thread. Luckily this
    // is not a huge limitation, as our test classes generally have enough non-leaky tests to
    // get a material speed up. See https://github.com/gradle/gradle/issues/6453.
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation"
    )
    systemProperty("hapi.spec.quiet.mode", true)

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    maxParallelForks = 1

    // Do not yet run things on the '--module-path'
    modularity.inferModulePath.set(false)
}

tasks.itest {
    systemProperty("itests", System.getProperty("itests"))
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
    systemProperty("TAG", "services-node:" + project.version)
    systemProperty("networkWorkspaceDir", layout.buildDirectory.dir("network/itest").get().asFile)
}

tasks.eet {
    systemProperty("TAG", "services-node:" + project.version)
    systemProperty("networkWorkspaceDir", layout.buildDirectory.dir("network/itest").get().asFile)
}

tasks.shadowJar {
    archiveFileName.set("SuiteRunner.jar")

    manifest {
        attributes(
            "Main-Class" to "com.hedera.services.bdd.suites.SuiteRunner",
            "Multi-Release" to "true"
        )
    }
}

val yahCliJar =
    tasks.register<ShadowJar>("yahCliJar") {
        exclude(listOf("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF", "META-INF/INDEX.LIST"))
        from(sourceSets["yahcli"].output)
        archiveClassifier.set("yahcli")
        configurations = listOf(project.configurations.getByName("yahcliRuntimeClasspath"))

        manifest {
            attributes(
                "Main-Class" to "com.hedera.services.yahcli.Yahcli",
                "Multi-Release" to "true"
            )
        }
    }

val rcdiffJar =
    tasks.register<ShadowJar>("rcdiffJar") {
        exclude(listOf("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF", "META-INF/INDEX.LIST"))
        from(sourceSets["rcdiff"].output)
        destinationDirectory.set(project.file("rcdiff"))
        archiveFileName.set("rcdiff.jar")
        configurations = listOf(project.configurations.getByName("rcdiffRuntimeClasspath"))

        manifest {
            attributes(
                "Main-Class" to "com.hedera.services.rcdiff.RcDiff",
                "Multi-Release" to "true"
            )
        }
    }

val validationJar =
    tasks.register<ShadowJar>("validationJar") {
        exclude(listOf("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF", "META-INF/INDEX.LIST"))

        archiveFileName.set("ValidationScenarios.jar")

        manifest {
            attributes(
                "Main-Class" to
                    "com.hedera.services.bdd.suites.utils.validation.ValidationScenarios",
                "Multi-Release" to "true"
            )
        }
    }

val copyValidation =
    tasks.register<Copy>("copyValidation") {
        group = "copy"
        from(validationJar)
        into(project.file("validation-scenarios"))
    }

val cleanValidation =
    tasks.register<Delete>("cleanValidation") {
        group = "copy"
        delete(File(project.file("validation-scenarios"), "ValidationScenarios.jar"))
    }

val copyYahCli =
    tasks.register<Copy>("copyYahCli") {
        group = "copy"
        from(yahCliJar)
        into(project.file("yahcli"))
        rename { "yahcli.jar" }
    }

val cleanYahCli =
    tasks.register<Delete>("cleanYahCli") {
        group = "copy"
        delete(File(project.file("yahcli"), "yahcli.jar"))
    }

tasks.assemble {
    dependsOn(tasks.shadowJar)
    dependsOn(copyYahCli)
}

tasks.clean {
    dependsOn(cleanYahCli)
    dependsOn(cleanValidation)
}
