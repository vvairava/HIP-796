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

package com.swirlds.merkledb.files;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.swirlds.merkledb.utilities.ProtoUtils.WIRE_TYPE_FIXED_64_BIT;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.merkledb.serialize.DataItemHeader;
import com.swirlds.merkledb.serialize.DataItemSerializer;
import com.swirlds.merkledb.utilities.ProtoUtils;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Serializer to store and read virtual hash records in MerkleDb data files.
 *
 * <p>Protobuf schema:
 *
 * <p><pre>
 * message HashRecord {
 *
 *     // Virtual node path
 *     optional uint64 path = 1;
 *
 *     // Hash. Always DigestType.SHA_384 for now
 *     bytes hash = 2;
 * }
 * </pre>>
 */
public final class VirtualHashRecordSerializer implements DataItemSerializer<VirtualHashRecord> {

    private static final FieldDefinition FIELD_HASHRECORD_PATH =
            new FieldDefinition("path", FieldType.FIXED64, false, true, false, 1);
    private static final FieldDefinition FIELD_HASHRECORD_HASH =
            new FieldDefinition("hash", FieldType.BYTES, false, true, false, 2);

    /**
     * The digest type to use for Virtual hashes, if this is changed then serialized version need
     * to change
     */
    public static final DigestType DEFAULT_DIGEST = DigestType.SHA_384;

    /**
     * This will need to change if we ever write different data due to path changing or
     * DEFAULT_DIGEST changing
     */
    private static final long CURRENT_SERIALIZATION_VERSION = 1;

    public VirtualHashRecordSerializer() {
        // for deserialization
    }

    @Override
    public long getCurrentDataVersion() {
        return CURRENT_SERIALIZATION_VERSION;
    }

    @Override
    @Deprecated(forRemoval = true)
    public int getSerializedSize() {
        // Once JDB support is dropped, virtual hash records can be of fixed size again
        // https://github.com/hashgraph/hedera-services/issues/8344
        return VARIABLE_DATA_SIZE;
    }

    @Override
    public int getTypicalSerializedSize() {
        return ProtoUtils.sizeOfTag(FIELD_HASHRECORD_PATH, WIRE_TYPE_FIXED_64_BIT)
                + Long.BYTES
                + ProtoUtils.sizeOfDelimited(FIELD_HASHRECORD_HASH, DigestType.SHA_384.digestLength());
    }

    @Override
    public int getSerializedSize(VirtualHashRecord data) {
        // This method is only used for PBJ serialization, so estimation is for PBJ, not JDB
        int size = 0;
        if (data.path() != 0) {
            size += ProtoUtils.sizeOfTag(FIELD_HASHRECORD_PATH, WIRE_TYPE_FIXED_64_BIT) + Long.BYTES;
        }
        size += ProtoUtils.sizeOfDelimited(FIELD_HASHRECORD_HASH, data.hash().getValue().length);
        return size;
    }

    @Override
    @Deprecated(forRemoval = true)
    public int getHeaderSize() {
        return Long.BYTES; // path
    }

    @Override
    @Deprecated(forRemoval = true)
    public DataItemHeader deserializeHeader(ByteBuffer buffer) {
        final long path = buffer.getLong();
        final int size = Long.BYTES + DEFAULT_DIGEST.digestLength();
        return new DataItemHeader(size, path);
    }

    @Override
    public void serialize(@NonNull final VirtualHashRecord hashRecord, @NonNull final WritableSequentialData out) {
        final DigestType digestType = hashRecord.hash().getDigestType();
        if (DEFAULT_DIGEST != digestType) {
            throw new IllegalArgumentException(
                    "Only " + DEFAULT_DIGEST + " digests allowed, but received hash with digest " + digestType);
        }
        if (hashRecord.path() != 0) {
            ProtoUtils.writeTag(out, FIELD_HASHRECORD_PATH);
            // Use long instead of var long to keep the size fixed
            out.writeLong(hashRecord.path());
        }
        ProtoUtils.writeBytes(
                out,
                FIELD_HASHRECORD_HASH,
                hashRecord.hash().getValue().length,
                o -> o.writeBytes(hashRecord.hash().getValue()));
    }

    @Override
    @Deprecated(forRemoval = true)
    public void serialize(final VirtualHashRecord hashRecord, final ByteBuffer buffer) {
        final DigestType digestType = hashRecord.hash().getDigestType();
        if (DEFAULT_DIGEST != digestType) {
            throw new IllegalArgumentException(
                    "Only " + DEFAULT_DIGEST + " digests allowed, but received hash with digest " + digestType);
        }
        buffer.putLong(hashRecord.path());
        buffer.put(hashRecord.hash().getValue());
    }

    @Override
    public VirtualHashRecord deserialize(@NonNull final ReadableSequentialData in) {
        // default values
        long path = 0;
        Hash hash = null;

        // read fields, they may be in any order or even missing at all
        while (in.hasRemaining()) {
            final int tag = in.readVarInt(false);
            final int number = tag >> TAG_FIELD_OFFSET;
            if (number == FIELD_HASHRECORD_PATH.number()) {
                path = readPath(in);
            } else if (number == FIELD_HASHRECORD_HASH.number()) {
                hash = readHash(in);
            } else {
                throw new IllegalArgumentException("Unknown virtual hash record field: " + number);
            }
        }

        // we actually don't expect null hashes here
        assert hash != null : "Null virtual hash record hash";
        return new VirtualHashRecord(path, hash);
    }

    private long readPath(final ReadableSequentialData in) {
        final long path = in.readLong();
        return path;
    }

    private Hash readHash(final ReadableSequentialData in) {
        final int hashSize = in.readVarInt(false);
        final Hash hash = new Hash(DigestType.SHA_384);
        assert hashSize == hash.getValue().length;
        in.readBytes(hash.getValue());
        return hash;
    }

    @Override
    @Deprecated(forRemoval = true)
    public VirtualHashRecord deserialize(ByteBuffer buffer, long dataVersion) throws IOException {
        if (dataVersion != CURRENT_SERIALIZATION_VERSION) {
            throw new IllegalArgumentException(
                    "Cannot deserialize version " + dataVersion + ", current is " + CURRENT_SERIALIZATION_VERSION);
        }
        final long path = buffer.getLong();
        final Hash newHash = new Hash(DigestType.SHA_384);
        buffer.get(newHash.getValue());
        return new VirtualHashRecord(path, newHash);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        return (o != null) && (getClass() == o.getClass());
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return (int) CURRENT_SERIALIZATION_VERSION;
    }
}
