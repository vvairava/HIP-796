/*
 * Copyright (C) 2020-2023 Hedera Hashgraph, LLC
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
  id("com.swirlds.platform.conventions")
  id("com.swirlds.platform.library")
  id("com.swirlds.platform.benchmark-conventions")
}

dependencies {
  // JMH Dependencies
  jmhImplementation(project(":swirlds-platform-core"))
  jmhImplementation(project(":swirlds-config-api"))
  jmhImplementation(project(":swirlds-config-impl"))
  jmhImplementation(libs.bundles.pbj)
}

fun getListProperty(arg: String): ListProperty<String> {
  return project.objects.listProperty(String::class).value(listOf(arg))
}

jmh {
  jvmArgs.set(listOf("-Xmx8g"))
  includes.set(listOf("transfer"))
  warmupIterations.set(0)
  iterations.set(1)
  benchmarkParameters.set(
      mapOf(
          "numFiles" to getListProperty("10"),
          "keySize" to getListProperty("16"),
          "recordSize" to getListProperty("128")))
}
