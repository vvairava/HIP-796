/*
 * Copyright (C) 2016-2024 Hedera Hashgraph, LLC
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

package com.swirlds.fchashmap.internal;

import java.util.Objects;

/**
 * A container for holding a key and an index. Used by {@link com.swirlds.fchashmap.FCOneToManyRelation}.
 *
 * @param <K>
 * 		the type of the key
 */
public class KeyIndexPair<K> {

    private final K key;
    private final int index;

    /**
     * Create a new key and index pair.
     *
     * @param key
     * 		the key, must be non-null
     * @param index
     * 		the value
     */
    public KeyIndexPair(final K key, final int index) {
        if (key == null) {
            throw new NullPointerException("null keys are not supported");
        }
        this.key = key;
        this.index = index;
    }

    /**
     * Get the key.
     */
    public K getKey() {
        return key;
    }

    /**
     * Get the index of the key.
     */
    public int getIndex() {
        return index;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final KeyIndexPair<?> that = (KeyIndexPair<?>) o;
        return index == that.index && Objects.equals(key, that.key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        // Chosen to resemble Object.hashCode(Object...) but with a different prime number
        return key.hashCode() * 37 + index;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "(" + key + ", " + index + ")";
    }
}
