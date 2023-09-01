/*
 * Copyright (C) 2016-2023 Hedera Hashgraph, LLC
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

package com.swirlds.virtual.merkle;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.serialize.KeySerializer;
import java.nio.ByteBuffer;

public class TestKeySerializer implements KeySerializer<TestKey> {

    public TestKeySerializer() {
        // required for deserialization
    }

    @Override
    public long getClassId() {
        return 8838921;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public int getSerializedSize() {
        return TestKey.BYTES;
    }

    @Override
    public long getCurrentDataVersion() {
        return 1;
    }

    @Override
    public void serialize(final TestKey data, final WritableSequentialData out) {
        data.serialize(out);
    }

    @Override
    @Deprecated(forRemoval = true)
    public int serialize(TestKey data, ByteBuffer buffer) {
        data.serialize(buffer);
        return TestKey.BYTES;
    }

    @Override
    public TestKey deserialize(final ReadableSequentialData in) {
        final TestKey key = new TestKey();
        key.deserialize(in);
        return key;
    }

    @Override
    @Deprecated(forRemoval = true)
    public TestKey deserialize(final ByteBuffer buffer, final long dataVersion) {
        final TestKey key = new TestKey();
        key.deserialize(buffer);
        return key;
    }

    @Override
    public boolean equals(final BufferedData buffer, final TestKey keyToCompare) {
        return buffer.readLong() == keyToCompare.getKeyAsLong();
    }

    @Override
    @Deprecated(forRemoval = true)
    public boolean equals(final ByteBuffer buffer, final int dataVersion, final TestKey keyToCompare) {
        return buffer.getLong() == keyToCompare.getKeyAsLong();
    }
}
