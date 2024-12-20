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

package com.swirlds.merkledb.collections;

import static com.swirlds.base.units.UnitConstants.MEBIBYTES_TO_BYTES;
import static com.swirlds.merkledb.collections.AbstractLongList.DEFAULT_MAX_LONGS_TO_STORE;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

public class LongListDiskTest extends AbstractLongListTest<LongListDisk> {

    @Override
    protected LongListDisk createLongListWithChunkSizeInMb(int chunkSizeInMb) {
        final int impliedLongsPerChunk = Math.toIntExact((chunkSizeInMb * (long) MEBIBYTES_TO_BYTES) / Long.BYTES);
        return new LongListDisk(impliedLongsPerChunk, DEFAULT_MAX_LONGS_TO_STORE, 0, CONFIGURATION);
    }

    @Override
    protected LongListDisk createFullyParameterizedLongListWith(int numLongsPerChunk, long maxLongs) {
        return new LongListDisk(numLongsPerChunk, maxLongs, 0, CONFIGURATION);
    }

    @Override
    protected LongListDisk createLongListFromFile(Path file) throws IOException {
        return new LongListDisk(file, CONFIGURATION);
    }

    /**
     * Provides a stream of writer-reader pairs specifically for the {@link LongListDisk} implementation.
     * The writer is always {@link LongListDisk}, and it is paired with three reader implementations
     * (heap, off-heap, and disk-based). This allows for testing whether data written by the
     * {@link LongListDisk} can be correctly read back by all supported long list implementations.
     * <p>
     * This method builds on {@link AbstractLongListTest#longListWriterBasedPairsProvider(Supplier)} to generate
     * the specific writer-reader combinations for the {@link LongListDisk} implementation.
     *
     * @return a stream of argument pairs, each containing a {@link LongListDisk} writer
     *         and one of the supported reader implementations
     */
    static Stream<Arguments> longListWriterReaderPairsProvider() {
        return longListWriterBasedPairsProvider(() -> new LongListDisk(CONFIGURATION));
    }

    /**
     * Provides writer-reader pairs combined with range configurations for testing.
     * <p>
     * Used for {@link AbstractLongListTest#writeReadRangeElement}
     *
     * @return a stream of arguments for range-based parameterized tests
     */
    static Stream<Arguments> longListWriterReaderRangePairsProvider() {
        return longListWriterReaderRangePairsProviderBase(longListWriterReaderPairsProvider());
    }

    /**
     * Provides writer-reader pairs combined with chunk offset configurations for testing.
     * <p>
     * Used for {@link AbstractLongListTest#createHalfEmptyLongListInMemoryReadBack}
     *
     * @return a stream of arguments for chunk offset based parameterized tests
     */
    static Stream<Arguments> longListWriterReaderOffsetPairsProvider() {
        return longListWriterReaderOffsetPairsProviderBase(longListWriterReaderPairsProvider());
    }
}
