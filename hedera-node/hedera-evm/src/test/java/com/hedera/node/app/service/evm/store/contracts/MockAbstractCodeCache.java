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

package com.hedera.node.app.service.evm.store.contracts;

import com.hedera.node.app.service.evm.store.contracts.utils.BytesKey;
import org.hyperledger.besu.evm.Code;

public class MockAbstractCodeCache extends AbstractCodeCache {
    public MockAbstractCodeCache(int expirationCacheTime, HederaEvmEntityAccess entityAccess) {
        super(expirationCacheTime, entityAccess);
    }

    /* --- Only used by unit tests --- */
    void cacheValue(BytesKey key, Code value) {
        cache.put(key, value);
    }
}
