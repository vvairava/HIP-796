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

package com.swirlds.virtualmap.internal.hash;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.HashBuilder;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

public class VirtualHasherTestBase extends VirtualTestBase {

    private static final HashBuilder HASH_BUILDER = new HashBuilder(Cryptography.DEFAULT_DIGEST_TYPE);

    private static final Hash NULL_HASH = CRYPTO.getNullHash();

    /**
     * Helper method for computing a list of {@link Arguments} of length {@code num}, each of which contains
     * a random list of dirty leave paths between {@code firstLeafPath} and {@code lastLeafPath}.
     *
     * @param num
     * 		The number of different random lists to create
     * @param firstLeafPath
     * 		The firstLeafPath
     * @param lastLeafPath
     * 		The lastLeafPath
     * @return
     * 		A non-null list of {@link Arguments} of random lists of paths.
     */
    protected static List<Arguments> randomDirtyLeaves(
            final int num, final long firstLeafPath, final long lastLeafPath) {
        final List<Arguments> args = new ArrayList<>();
        final Random rand = new Random(42);
        for (int i = 0; i < num; i++) {
            final int numDirtyLeaves = rand.nextInt((int) firstLeafPath);
            if (numDirtyLeaves == 0) {
                i--;
                continue;
            }
            final List<Long> paths = new ArrayList<>();
            for (int j = 0; j < numDirtyLeaves; j++) {
                paths.add(firstLeafPath + rand.nextInt((int) firstLeafPath));
            }
            args.add(Arguments.of(
                    firstLeafPath,
                    lastLeafPath,
                    paths.stream().sorted().distinct().collect(Collectors.toList())));
        }
        return args;
    }

    protected static Hash hashTree(final TestDataSource ds) {
        final HashBuilder hashBuilder = new HashBuilder(DigestType.SHA_384);
        final VirtualHashRecord root = ds.getInternal(Path.ROOT_PATH);
        assert root != null;
        return hashSubTree(ds, hashBuilder, root).hash();
    }

    protected static List<VirtualLeafBytes> invalidateNodes(final TestDataSource ds, final Stream<Long> dirtyPaths) {
        final List<VirtualLeafBytes> leaves = new ArrayList<>();
        dirtyPaths.forEach(i -> {
            final VirtualLeafBytes rec = ds.getLeaf(i);
            assert rec != null;
            leaves.add(rec);
            long path = rec.path();
            while (path >= 0) {
                final VirtualHashRecord internal = ds.getInternal(path);
                assert internal != null;
                ds.setInternal(new VirtualHashRecord(path));
                if (path == 0) {
                    break;
                }
                path = Path.getParentPath(path);
            }
        });
        return leaves;
    }

    protected static VirtualHashRecord hashSubTree(
            final TestDataSource ds, final HashBuilder hashBuilder, final VirtualHashRecord internalNode) {
        final long leftChildPath = Path.getLeftChildPath(internalNode.path());
        VirtualHashRecord leftChild = ds.getInternal(leftChildPath);
        assert leftChild != null;
        final Hash leftHash;
        if (leftChildPath < ds.firstLeafPath) {
            leftChild = hashSubTree(ds, hashBuilder, leftChild);
        }
        leftHash = leftChild.hash();

        final long rightChildPath = Path.getRightChildPath(internalNode.path());
        VirtualHashRecord rightChild = ds.getInternal(rightChildPath);
        Hash rightHash = CRYPTO.getNullHash();
        if (rightChild != null) {
            if (rightChildPath < ds.firstLeafPath) {
                rightChild = hashSubTree(ds, hashBuilder, rightChild);
            }
            rightHash = rightChild.hash();
        }

        hashBuilder.reset();
        hashBuilder.update(leftHash);
        hashBuilder.update(rightHash);
        VirtualHashRecord record = new VirtualHashRecord(internalNode.path(), hashBuilder.build());
        ds.setInternal(record);
        return record;
    }

    protected static final class TestDataSource {
        private final long firstLeafPath;
        private final long lastLeafPath;
        private final Map<Long, VirtualHashRecord> internals = new ConcurrentHashMap<>();

        TestDataSource(final long firstLeafPath, final long lastLeafPath) {
            this.firstLeafPath = firstLeafPath;
            this.lastLeafPath = lastLeafPath;
        }

        Hash loadHash(final long path) {
            if (path < Path.ROOT_PATH || path > lastLeafPath) {
                return null;
            }
            return getInternal(path).hash();
        }

        VirtualLeafBytes<TestValue> getLeaf(final long path) {
            if (path < firstLeafPath || path > lastLeafPath) {
                return null;
            }

            final Bytes key = TestKey.longToKey(path);
            final TestValue value = new TestValue("Value: " + path);
            return new VirtualLeafBytes<>(path, key, value, TestValueCodec.INSTANCE);
        }

        VirtualHashRecord getInternal(final long path) {
            if (path < Path.ROOT_PATH || path > lastLeafPath) {
                return null;
            }
            VirtualHashRecord rec = internals.get(path);
            if (rec == null) {
                final Hash hash;
                if (path < firstLeafPath) {
                    hash = CRYPTO.getNullHash();
                } else {
                    final VirtualLeafBytes<TestValue> leaf = getLeaf(path);
                    assert leaf != null;
                    // HASH_BUILDER is not thread safe
                    synchronized (HASH_BUILDER) {
                        hash = leaf.hash(HASH_BUILDER);
                    }
                }
                rec = new VirtualHashRecord(path, hash);
            }
            return rec;
        }

        void setInternal(final VirtualHashRecord internal) {
            internals.put(internal.path(), internal);
        }
    }
}
