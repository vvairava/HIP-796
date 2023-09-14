/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

package com.swirlds.merkledb.serialize;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.virtualmap.VirtualKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An interface to serialize keys used in virtual maps. Virtual keys are serializable in themselves,
 * but the corresponding interface, {@link SelfSerializable}, lacks some abilities needed by virtual
 * data sources. For example, there is no way to easily get serialized key size in bytes, and there
 * are no methods to serialize / deserialize keys to / from byte or PBJ buffers.
 *
 * <p>Serialization bytes used by key serializers may or may not be identical to bytes used when
 * keys are self-serialized. In many cases key serializers will just delegate serialization to keys,
 * just returning the size of serialized byte array. On deserialization, typical implementation is
 * to create a new key object and call its {@link
 * SelfSerializable#deserialize(SerializableDataInputStream, int)} method.
 *
 * @param <K> Virtual key type
 */
public interface KeySerializer<K extends VirtualKey> extends BaseSerializer<K>, SelfSerializable {

    /**
     * Get the current key serialization version. Key serializers can only use the lower 32 bits of
     * the version long.
     *
     * @return Current key serialization version
     */
    long getCurrentDataVersion();

    /**
     * @return The index to use for indexing the keys that this KeySerializer creates
     */
    default KeyIndexType getIndexType() {
        return getSerializedSize() == Long.BYTES ? KeyIndexType.SEQUENTIAL_INCREMENTING_LONGS : KeyIndexType.GENERIC;
    }

    /**
     * Compare keyToCompare's data to that contained in the given ByteBuffer. The data in the buffer
     * is assumed to be starting at the current buffer position and in the format written by this
     * class's serialize() method. The reason for this rather than just deserializing then doing an
     * object equals is performance. By doing the comparison here you can fail fast on the first
     * byte that does not match. As this is used in a tight loop in searching a hash map bucket for
     * a match performance is critical.
     *
     * <p>Deprecation note: this method is only used by MerkleDb, when it checks data in
     * JDB format. This format will be eventually removed.
     *
     * @param buffer The buffer to read from and compare to
     * @param dataVersion The serialization version of the data in the buffer
     * @param keyToCompare The key to compare with the data in the file.
     * @return true if the content of the buffer matches this class's data
     * @throws IOException If there was a problem reading from the buffer
     */
    @Deprecated
    boolean equals(ByteBuffer buffer, int dataVersion, K keyToCompare) throws IOException;

    /**
     * Compare keyToCompare's data to that contained in the given buffer. The data in the buffer
     * is assumed to be starting at the current buffer position and in the format written by this
     * class's serialize() method. The reason for this rather than just deserializing then doing an
     * object equals is performance. By doing the comparison here you can fail fast on the first
     * byte that does not match. As this is used in a tight loop in searching a hash map bucket for
     * a match performance is critical.
     *
     * @param buffer The buffer to read from and compare to
     * @param keyToCompare The key to compare with the data in the file.
     * @return true if the content of the buffer matches this class's data
     */
    boolean equals(@NonNull BufferedData buffer, @NonNull K keyToCompare);

    @Override
    default void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        // most key serializers are stateless, so there is nothing to serialize
    }

    @Override
    default void deserialize(@NonNull final SerializableDataInputStream in, int version) throws IOException {
        // most key serializers are staless, so there is nothing to deserialize
    }
}
