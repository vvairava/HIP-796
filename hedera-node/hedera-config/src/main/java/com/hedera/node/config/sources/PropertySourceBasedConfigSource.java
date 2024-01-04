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

package com.hedera.node.config.sources;

import com.hedera.node.app.service.mono.context.properties.PropertySource;
import com.swirlds.config.api.source.ConfigSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link ConfigSource} that wraps a {@link PropertySource} and redirects all calls to the {@link PropertySource}.
 */
public class PropertySourceBasedConfigSource implements ConfigSource {

    private final PropertySource propertySource;

    /**
     * Creates a new instance
     *
     * @param propertySource the property source
     */
    public PropertySourceBasedConfigSource(@NonNull final PropertySource propertySource) {
        this.propertySource = Objects.requireNonNull(propertySource, "propertySource");
    }

    @Override
    @NonNull
    public Set<String> getPropertyNames() {
        return propertySource.allPropertyNames();
    }

    @Override
    @Nullable
    public String getValue(@NonNull final String name) throws NoSuchElementException {
        return propertySource.getRawValue(name);
    }
}
