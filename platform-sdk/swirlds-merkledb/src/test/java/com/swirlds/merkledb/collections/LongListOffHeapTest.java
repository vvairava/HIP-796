/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
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
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

class LongListOffHeapTest extends AbstractLongListTest<LongListOffHeap> {

    @Override
    protected LongListOffHeap createLongList() {
        return new LongListOffHeap();
    }

    @Override
    protected LongListOffHeap createLongListWithChunkSizeInMb(final int chunkSizeInMb) {
        final int impliedLongsPerChunk = Math.toIntExact((((long) chunkSizeInMb * MEBIBYTES_TO_BYTES) / Long.BYTES));
        return new LongListOffHeap(impliedLongsPerChunk, DEFAULT_MAX_LONGS_TO_STORE, 0);
    }

    @Override
    protected LongListOffHeap createFullyParameterizedLongListWith(final int numLongsPerChunk, final long maxLongs) {
        return new LongListOffHeap(numLongsPerChunk, maxLongs, 0);
    }

    @Override
    protected LongListOffHeap createLongListFromFile(final Path file) throws IOException {
        return new LongListOffHeap(file, CONFIGURATION);
    }

    /**
     * Provides a stream of writer-reader pairs specifically for the {@link LongListOffHeap} implementation.
     * The writer is always {@link LongListOffHeap}, and it is paired with three reader implementations
     * (heap, off-heap, and disk-based). This allows for testing whether data written by the
     * {@link LongListOffHeap} can be correctly read back by all supported long list implementations.
     * <p>
     * This method builds on {@link AbstractLongListTest#longListWriterBasedPairsProvider} to generate
     * the specific writer-reader combinations for the {@link LongListOffHeap} implementation.
     *
     * @return a stream of argument pairs, each containing a {@link LongListOffHeap} writer
     *         and one of the supported reader implementations
     */
    static Stream<Arguments> longListWriterReaderPairsProvider() {
        return longListWriterBasedPairsProvider(offHeapWriterFactory);
    }

    /**
     * Provides a stream of writer paired with two reader implementations for testing
     * cross-compatibility.
     * <p>
     * Used for {@link AbstractLongListTest#testUpdateMinToTheLowerEnd}
     *
     * @return a stream of arguments containing a writer and two readers.
     */
    static Stream<Arguments> longListWriterSecondReaderPairsProvider() {
        return longListWriterSecondReaderPairsProviderBase(longListWriterReaderPairsProvider());
    }

    /**
     * Provides writer-reader pairs combined with range configurations for testing.
     * <p>
     * Used for {@link AbstractLongListTest#testWriteReadRangeElement}
     *
     * @return a stream of arguments for range-based parameterized tests
     */
    static Stream<Arguments> longListWriterReaderRangePairsProvider() {
        return longListWriterReaderRangePairsProviderBase(longListWriterReaderPairsProvider());
    }

    /**
     * Provides writer-reader pairs combined with chunk offset configurations (second set) for testing.
     * <p>
     * Used for {@link AbstractLongListTest#testPersistListWithNonZeroMinValidIndex}
     * and {@link AbstractLongListTest#testPersistShrunkList}
     *
     * @return a stream of arguments for chunk offset based parameterized tests
     */
    static Stream<Arguments> longListWriterReaderOffsetPairsProvider() {
        return longListWriterReaderOffsetPairsProviderBase(longListWriterReaderPairsProvider());
    }
}
