/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.event.hashing;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Hashes events.
 */
public class EventHasher {
    private final Cryptography cryptography;

    /**
     * Constructs a new event hasher.
     *
     * @param platformContext the platform context
     */
    public EventHasher(@NonNull final PlatformContext platformContext) {
        this.cryptography = platformContext.getCryptography();
    }

    /**
     * Hashes the event and builds the event descriptor.
     *
     * @param event the event to hash
     * @return the hashed event
     */
    public GossipEvent hashEvent(@NonNull final GossipEvent event) {
        cryptography.digestSync(event.getHashedData());
        event.buildDescriptor();
        return event;
    }
}
