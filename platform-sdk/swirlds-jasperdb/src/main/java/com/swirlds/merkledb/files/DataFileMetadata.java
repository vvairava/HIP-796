/*
 * Copyright (C) 2021-2023 Hedera Hashgraph, LLC
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
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_CREATION_NANOS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_CREATION_SECONDS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_INDEX;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS_COUNT;
import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEM_VERSION;
import static com.swirlds.merkledb.utilities.ProtoUtils.WIRE_TYPE_FIXED_64_BIT;
import static com.swirlds.merkledb.utilities.ProtoUtils.WIRE_TYPE_VARINT;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.merkledb.utilities.ProtoUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * DataFile's metadata that is stored in the data file's footer
 */
@SuppressWarnings("unused")
// Future work: make this class final, once DataFileMetadataJdb is dropped
// See https://github.com/hashgraph/hedera-services/issues/8344 for details
public class DataFileMetadata {

    /** The file index, in a data file collection */
    // Future work: make it private final, once this class is final again
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected int index;

    /** The creation date of this file */
    // Future work: make it private final, once this class is final again
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected Instant creationDate;

    /**
     * The number of data items the file contains. When metadata is loaded from a file, the number
     * of items is read directly from there. When metadata is created by {@link DataFileWriter} for
     * new files during flushes or compactions, this field is set to 0 initially and then updated
     * right before the file is finished writing. For such new files, no code needs their metadata
     * until they are fully written, so wrong (zero) item count shouldn't be an issue.
     */
    // Future work: make it private, once this class is final again
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected volatile long itemsCount;

    /** Serialization version for data stored in the file */
    // Future work: make it private final, once this class is final again
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected long serializationVersion;

    // Set in writeTo()
    private long dataItemCountHeaderOffset = 0;

    /**
     * Create a new DataFileMetadata with complete set of data
     *
     * @param itemsCount The number of data items the file contains
     * @param index The file index, in a data file collection
     * @param creationDate The creation data of this file, this is critical as it is used when
     *     merging two files to know which files data is newer.
     * @param serializationVersion Serialization version for data stored in the file
     */
    public DataFileMetadata(
            final long itemsCount, final int index, final Instant creationDate, final long serializationVersion) {
        this.itemsCount = itemsCount;
        this.index = index;
        this.creationDate = creationDate;
        this.serializationVersion = serializationVersion;
    }

    /**
     * Create a DataFileMetadata loading it from a existing file
     *
     * @param file The file to read metadata from
     * @throws IOException If there was a problem reading metadata footer from the file
     */
    public DataFileMetadata(Path file) throws IOException {
        // Defaults
        int index = 0;
        long creationSeconds = 0;
        int creationNanos = 0;
        long itemsCount = 0;
        long serializationVersion = 0;

        // Track which fields are read, so we don't have to scan through the whole file
        final Set<String> fieldsToRead = new HashSet<>(
                Set.of("index", "creationSeconds", "creationNanos", "itemsCount", "serializationVersion"));

        // Read values from the file, skipping all data items
        try (final InputStream fin = Files.newInputStream(file, StandardOpenOption.READ)) {
            final ReadableSequentialData in = new ReadableStreamingData(fin);
            while (in.hasRemaining() && !fieldsToRead.isEmpty()) {
                final int tag = in.readVarInt(false);
                final int fieldNum = tag >> TAG_FIELD_OFFSET;
                if (fieldNum == FIELD_DATAFILE_INDEX.number()) {
                    index = in.readVarInt(false);
                    fieldsToRead.remove("index");
                } else if (fieldNum == FIELD_DATAFILE_CREATION_SECONDS.number()) {
                    creationSeconds = in.readVarLong(false);
                    fieldsToRead.remove("creationSeconds");
                } else if (fieldNum == FIELD_DATAFILE_CREATION_NANOS.number()) {
                    creationNanos = in.readVarInt(false);
                    fieldsToRead.remove("creationNanos");
                } else if (fieldNum == FIELD_DATAFILE_ITEMS_COUNT.number()) {
                    itemsCount = in.readLong(ByteOrder.LITTLE_ENDIAN);
                    fieldsToRead.remove("itemsCount");
                } else if (fieldNum == FIELD_DATAFILE_ITEM_VERSION.number()) {
                    serializationVersion = in.readVarLong(false);
                    fieldsToRead.remove("serializationVersion");
                } else if (fieldNum == FIELD_DATAFILE_ITEMS.number()) {
                    // Just skip it
                    final int size = in.readVarInt(false);
                    in.skip(size);
                } else {
                    throw new IllegalArgumentException("Unknown data file field: " + fieldNum);
                }
            }
        }

        // Initialize this object
        this.index = index;
        this.creationDate = Instant.ofEpochSecond(creationSeconds, creationNanos);
        this.itemsCount = itemsCount;
        this.serializationVersion = serializationVersion;
    }

    void writeTo(final BufferedData out) {
        ProtoUtils.writeTag(out, FIELD_DATAFILE_INDEX);
        out.writeVarInt(getIndex(), false);
        final Instant creationInstant = getCreationDate();
        ProtoUtils.writeTag(out, FIELD_DATAFILE_CREATION_SECONDS);
        out.writeVarLong(creationInstant.getEpochSecond(), false);
        ProtoUtils.writeTag(out, FIELD_DATAFILE_CREATION_NANOS);
        out.writeVarInt(creationInstant.getNano(), false);
        dataItemCountHeaderOffset = out.position();
        ProtoUtils.writeTag(out, FIELD_DATAFILE_ITEMS_COUNT);
        out.writeLong(0, ByteOrder.LITTLE_ENDIAN); // will be updated later
        ProtoUtils.writeTag(out, FIELD_DATAFILE_ITEM_VERSION);
        out.writeVarLong(getSerializationVersion(), false);
    }

    /**
     * Get the number of data items the file contains. If this method is called before the
     * corresponding file is completely written by {@link DataFileWriter}, the return value is 0.
     */
    public long getDataItemCount() {
        return itemsCount;
    }

    /**
     * Updates number of data items in the file. This method must be called after metadata is
     * written to a file using {@link #writeTo(BufferedData)}.
     *
     * This method is called by {@link DataFileWriter} right before the file is finished writing.
     */
    void updateDataItemCount(final BufferedData out, final long count) {
        this.itemsCount = count;
        assert dataItemCountHeaderOffset != 0;
        out.position(dataItemCountHeaderOffset);
        //        ProtoWriterTools.writeLong(out, FIELD_DATAFILE_ITEMS_COUNT, count);
        ProtoUtils.writeTag(out, FIELD_DATAFILE_ITEMS_COUNT);
        out.writeLong(count, ByteOrder.LITTLE_ENDIAN);
    }

    /** Get the files index, out of a set of data files */
    public int getIndex() {
        return index;
    }

    /** Get the date the file was created in UTC */
    public Instant getCreationDate() {
        return creationDate;
    }

    /** Get the serialization version for data stored in this file */
    public long getSerializationVersion() {
        return serializationVersion;
    }

    // For testing purposes. In low-level data file tests, skip this number of bytes from the
    // beginning of the file before reading data items, assuming file metadata is always written
    // first, then data items
    int metadataSizeInBytes() {
        return ProtoUtils.sizeOfTag(FIELD_DATAFILE_INDEX, WIRE_TYPE_VARINT)
                + ProtoUtils.sizeOfVarInt32(index)
                + ProtoUtils.sizeOfTag(FIELD_DATAFILE_CREATION_SECONDS, WIRE_TYPE_VARINT)
                + ProtoUtils.sizeOfVarInt64(creationDate.getEpochSecond())
                + ProtoUtils.sizeOfTag(FIELD_DATAFILE_CREATION_NANOS, WIRE_TYPE_VARINT)
                + ProtoUtils.sizeOfVarInt64(creationDate.getNano())
                + ProtoUtils.sizeOfTag(FIELD_DATAFILE_ITEMS_COUNT, WIRE_TYPE_FIXED_64_BIT)
                + Long.BYTES
                + ProtoUtils.sizeOfTag(FIELD_DATAFILE_ITEM_VERSION, WIRE_TYPE_VARINT)
                + ProtoUtils.sizeOfVarInt64(serializationVersion);
    }

    /** toString for debugging */
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("itemsCount", itemsCount)
                .append("index", index)
                .append("creationDate", creationDate)
                .append("serializationVersion", serializationVersion)
                .toString();
    }

    /**
     * Equals for use when comparing in collections, based on all fields in the toString() output.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DataFileMetadata that = (DataFileMetadata) o;
        return itemsCount == that.itemsCount
                && index == that.index
                && serializationVersion == that.serializationVersion
                && Objects.equals(this.creationDate, that.creationDate);
    }

    /**
     * hashCode for use when comparing in collections, based on all fields in the toString() output.
     */
    @Override
    public int hashCode() {
        return Objects.hash(itemsCount, index, creationDate, serializationVersion);
    }
}
