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

package com.hedera.services.bdd.suites.utils.sysfiles.serdes;

import java.util.Map;

public class StandardSerdes {

    /**
     * Private constructor to prevent instantiation.
     *
     * @throws UnsupportedOperationException if this class is instantiated via reflection.
     */
    private StandardSerdes() {
        throw new UnsupportedOperationException();
    }

    public static final Map<Long, SysFileSerde<String>> SYS_FILE_SERDES = Map.of(
            101L,
            new AddrBkJsonToGrpcBytes(),
            102L,
            new NodesJsonToGrpcBytes(),
            111L,
            new FeesJsonToGrpcBytes(),
            112L,
            new XRatesJsonToGrpcBytes(),
            121L,
            new JutilPropsToSvcCfgBytes("application.properties"),
            122L,
            new JutilPropsToSvcCfgBytes("api-permission.properties"),
            123L,
            new ThrottlesJsonToGrpcBytes());
}
