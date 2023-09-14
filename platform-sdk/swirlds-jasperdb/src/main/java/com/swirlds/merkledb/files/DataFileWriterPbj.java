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

import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.files.DataFileCommon.PAGE_SIZE;
import static com.swirlds.merkledb.files.DataFileCommon.createDataFilePath;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.serialize.DataItemSerializer;
import com.swirlds.merkledb.utilities.ProtoUtils;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Writer for creating a data file. A data file contains a number of data items. Each data item can
 * be variable or fixed size and is considered as a black box. All access to contents of the data
 * item is done via the DataItemSerializer.
 *
 * <p><b>This is designed to be used from a single thread.</b>
 *
 * <p>At the end of the file it is padded till a 4096 byte page boundary then a footer page is
 * written by DataFileMetadata.
 *
 * <p>Protobuf schema: see {@link DataFileReaderPbj} for details.
 *
 * @param <D> Data item type
 */
// Future work: make this class final after DataFileWriterJdb is dropped
// https://github.com/hashgraph/hedera-services/issues/8344
public class DataFileWriterPbj<D> implements DataFileWriter<D> {

    /** Mapped buffer size */
    private static final int MMAP_BUF_SIZE = PAGE_SIZE * 1024 * 4;

    /**
     * The current mapped byte buffer used for writing. When overflowed, it is released, and another
     * buffer is mapped from the file channel.
     */
    // Future work: make it private once DataFileWriterJdb is dropped
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected MappedByteBuffer writingMmap;
    /**
     * Offset, in bytes, of the current mapped byte buffer in the file channel. After the file is
     * completely written and closed, this field value is equal to the file size.
     */
    // Future work: make it private once DataFileWriterJdb is dropped
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected long mmapPositionInFile = 0;
    /* */
    private BufferedData writingPbjData;

    private MappedByteBuffer writingHeaderMmap;
    private BufferedData writingHeaderPbjData;

    /** Serializer for converting raw data to/from data items */
    // Future work: make it private once DataFileWriterJdb is dropped
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected final DataItemSerializer<D> dataItemSerializer;
    /** The path to the data file we are writing */
    // Future work: make it private once DataFileWriterJdb is dropped
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected final Path path;
    /** File metadata */
    private final DataFileMetadata metadata;
    /**
     * Count of the number of data items we have written so far. Ready to be stored in footer
     * metadata
     */
    // Future work: make it private once DataFileWriterJdb is dropped
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected long dataItemCount = 0;

    /**
     * Create a new data file in the given directory, in append mode. Puts the object into "writing"
     * mode (i.e. creates a lock file. So you'd better start writing data and be sure to finish it
     * off).
     *
     * @param filePrefix string prefix for all files, must not contain "_" chars
     * @param dataFileDir the path to directory to create the data file in
     * @param index the index number for this file
     * @param dataItemSerializer Serializer for converting raw data to/from data items
     * @param creationTime the time stamp for the creation time for this file
     */
    public DataFileWriterPbj(
            final String filePrefix,
            final Path dataFileDir,
            final int index,
            final DataItemSerializer<D> dataItemSerializer,
            final Instant creationTime)
            throws IOException {
        this(filePrefix, dataFileDir, index, dataItemSerializer, creationTime, DataFileCommon.FILE_EXTENSION);
    }

    // Future work: remove this extra constructor, once DataFileWriterJdb is dropped
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected DataFileWriterPbj(
            final String filePrefix,
            final Path dataFileDir,
            final int index,
            final DataItemSerializer<D> dataItemSerializer,
            final Instant creationTime,
            final String extension)
            throws IOException {
        this.dataItemSerializer = dataItemSerializer;
        this.path = createDataFilePath(filePrefix, dataFileDir, index, creationTime, extension);
        metadata = new DataFileMetadata(
                0, // data item count will be updated later in finishWriting()
                index,
                creationTime,
                dataItemSerializer.getCurrentDataVersion());
        Files.createFile(path);
        writeHeader();
    }

    /**
     * Maps the writing byte buffer to the given position in the file. Byte buffer size is always
     * {@link #MMAP_BUF_SIZE}. Previous mapped byte buffer, if not null, is released.
     *
     * @param newMmapPos new mapped byte buffer position in the file, in bytes
     * @throws IOException if I/O error(s) occurred
     */
    private void moveWritingBuffer(final long newMmapPos) throws IOException {
        try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final MappedByteBuffer newMmap = channel.map(MapMode.READ_WRITE, newMmapPos, MMAP_BUF_SIZE);
            if (newMmap == null) {
                throw new IOException("Failed to map file channel to memory");
            }
            if (writingMmap != null) {
                DataFileCommon.closeMmapBuffer(writingMmap);
            }
            mmapPositionInFile = newMmapPos;
            writingMmap = newMmap;
            writingPbjData = BufferedData.wrap(writingMmap);
        }
    }

    // Future work: make it private
    // https://github.com/hashgraph/hedera-services/issues/8344
    protected void writeHeader() throws IOException {
        try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            writingHeaderMmap = channel.map(MapMode.READ_WRITE, 0, 1024);
            writingHeaderPbjData = BufferedData.wrap(writingHeaderMmap);
            metadata.writeTo(writingHeaderPbjData);
        }
        // prepare to write data items
        moveWritingBuffer(writingHeaderPbjData.position());
    }

    public DataFileType getFileType() {
        return DataFileType.PBJ;
    }

    /** Get the path for the file being written. Useful when needing to get a reader to the file. */
    public Path getPath() {
        return path;
    }

    /**
     * Get file metadata for the written file.
     *
     * @return data file metadata
     */
    public DataFileMetadata getMetadata() {
        return metadata;
    }

    /**
     * Write a data item copied from another file like during merge. The data item serializer
     * copyItem() method will be called to give it a chance to pass the data for or upgrade the
     * serialization as needed.
     *
     * @param dataItemData a buffer containing the item's data
     * @return New data location in this file where it was written
     * @throws IOException If there was a problem writing the data item
     */
    public synchronized long writeCopiedDataItem(final Object dataItemData) throws IOException {
        if (!(dataItemData instanceof BufferedData protoData)) {
            throw new IllegalArgumentException("Data item data buffer type mismatch");
        }
        // capture the current write position for beginning of data item
        final long currentWritingMmapPos = writingPbjData.position();
        final long byteOffset = mmapPositionInFile + currentWritingMmapPos;
        // capture the current read position in the data item data buffer
        final long currentProtoPos = protoData.position();
        final long currentProtoLimit = protoData.limit();
        final long size = protoData.remaining();
        try {
            ProtoUtils.writeBytes(
                    writingPbjData, FIELD_DATAFILE_ITEMS, Math.toIntExact(size), o -> o.writeBytes(protoData));
        } catch (final BufferOverflowException e) {
            // Buffer overflow indicates the current writing mapped byte buffer needs to be
            // mapped to a new location
            moveWritingBuffer(byteOffset);
            // Reset dataItemData buffer position and retry
            protoData.position(currentProtoPos);
            protoData.limit(currentProtoLimit);
            try {
                ProtoUtils.writeBytes(
                        writingPbjData, FIELD_DATAFILE_ITEMS, Math.toIntExact(size), o -> o.writeBytes(protoData));
            } catch (final BufferOverflowException t) {
                // If still a buffer overflow, it means the mapped buffer is smaller than even a single
                // data item
                throw new IOException(DataFileCommon.ERROR_DATAITEM_TOO_LARGE, e);
            }
        }
        dataItemCount++;
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), byteOffset);
    }

    /**
     * Store data item in file returning location it was stored at.
     *
     * @param dataItem the data item to write
     * @return the data location of written data in bytes
     * @throws IOException if there was a problem appending data to file
     */
    public synchronized long storeDataItem(final D dataItem) throws IOException {
        // find offset for the start of this new data item, we assume we always write data in a
        // whole number of blocks
        final long currentWritingMmapPos = writingPbjData.position();
        final long byteOffset = mmapPositionInFile + currentWritingMmapPos;
        // write serialized data
        final int dataItemSize = dataItemSerializer.getSerializedSize(dataItem);
        try {
            ProtoUtils.writeBytes(
                    writingPbjData,
                    FIELD_DATAFILE_ITEMS,
                    dataItemSize,
                    out -> dataItemSerializer.serialize(dataItem, out));
        } catch (final BufferOverflowException e) {
            // Buffer overflow indicates the current writing mapped byte buffer needs to be
            // mapped to a new location and retry
            moveWritingBuffer(byteOffset);
            try {
                ProtoUtils.writeBytes(
                        writingPbjData,
                        FIELD_DATAFILE_ITEMS,
                        dataItemSize,
                        out -> dataItemSerializer.serialize(dataItem, out));
            } catch (final BufferOverflowException t) {
                // If still a buffer overflow, it means the mapped buffer is smaller than even a single data item
                throw new IOException(DataFileCommon.ERROR_DATAITEM_TOO_LARGE, e);
            }
        }
        // increment data item counter
        dataItemCount++;
        // return the offset where we wrote the data
        return DataFileCommon.dataLocation(metadata.getIndex(), byteOffset);
    }

    /**
     * When you finished append to a new file, call this to seal the file and make it read only for
     * reading.
     *
     * @throws IOException if there was a problem sealing file or opening again as read only
     */
    public synchronized void finishWriting() throws IOException {
        // total file size is where the current writing pos is
        final long totalFileSize = mmapPositionInFile + writingPbjData.position();
        // update data item count in the metadata and in the file
        // not that updateDataItemCount() messes up with writing buffer state (position), but
        // the buffer will be closed below anyway
        metadata.updateDataItemCount(writingHeaderPbjData, dataItemCount);
        // release all the resources
        DataFileCommon.closeMmapBuffer(writingHeaderMmap);
        DataFileCommon.closeMmapBuffer(writingMmap);

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.truncate(totalFileSize);
            // after finishWriting(), mmapPositionInFile should be equal to the file size
            mmapPositionInFile = totalFileSize;
        }
    }
}
