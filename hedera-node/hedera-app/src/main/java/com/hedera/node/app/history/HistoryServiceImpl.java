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

package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.MetadataProof;
import com.hedera.node.app.history.schemas.V059HistorySchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.ReadableRosterStore;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Default implementation of the {@link HistoryService}.
 */
public class HistoryServiceImpl implements HistoryService, Consumer<MetadataProof> {
    /**
     * If not null, the proof of the metadata scoped to the current roster.
     */
    @Nullable
    private MetadataProof proof;

    @Override
    public void reconcile(
            @NonNull final Instant now,
            @NonNull final ReadableRosterStore rosterStore,
            @NonNull final MetadataSource metadataSource,
            @NonNull final WritableHistoryStore historyStore) {
        requireNonNull(now);
        requireNonNull(rosterStore);
        requireNonNull(metadataSource);
        requireNonNull(historyStore);
        // No-op
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public @NonNull Bytes getCurrentProof(@NonNull final Bytes metadata) {
        requireNonNull(metadata);
        return Bytes.EMPTY;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V059HistorySchema(this));
    }

    @Override
    public void accept(@NonNull final MetadataProof construction) {}
}
