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

package com.hedera.node.app.service.mono.state.virtual;

import static com.hedera.node.app.service.mono.state.virtual.KeyPackingUtils.deserializeUint256Key;
import static com.hedera.node.app.service.mono.state.virtual.KeyPackingUtils.serializePackedBytes;
import static com.hedera.node.app.service.mono.state.virtual.KeyPackingUtils.serializePackedBytesToBuffer;
import static com.hedera.node.app.service.mono.state.virtual.KeyPackingUtils.serializePackedBytesToPbj;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.virtualmap.VirtualKey;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * The key of a key/value pair used by a Smart Contract for storage purposes.
 *
 * <p>We only store the number part of the contract ID as the ideas ia there will be a virtual
 * merkle tree for each shard and realm.
 */
public final class ContractKey implements VirtualKey {
    /** The shifts required to deserialize a big-endian contractId with leading zeros omitted */
    private static final int[] BIT_SHIFTS = {0, 8, 16, 24, 32, 40, 48, 56};
    /** The estimated average size for a contract key when serialized */
    public static final int ESTIMATED_AVERAGE_SIZE = 20; // assume 50% full typically, max size is (1 + 8 + 32)
    /** this is the number part of the contract address */
    private long contractId;
    /** number of the least significant bytes in contractId that contain ones. Max is 8 */
    private byte contractIdNonZeroBytes;
    /** this is the raw data for the unit256 */
    private int[] uint256Key;
    /** number of the least significant bytes in uint256Key that contain ones. Max is 32 */
    private byte uint256KeyNonZeroBytes;

    static final long RUNTIME_CONSTRUCTABLE_ID = 0xb2c0a1f733950abdL;
    public static final int MERKLE_VERSION = 1;

    public ContractKey() {
        // there has to be a default constructor for deserialize
    }

    public static ContractKey from(final AccountID id, final UInt256 key) {
        return new ContractKey(id.getAccountNum(), key.toArray());
    }

    public static ContractKey from(final long accountNum, final UInt256 key) {
        return new ContractKey(accountNum, key.toArray());
    }

    public ContractKey(final long contractId, final long key) {
        setContractId(contractId);
        setKey(key);
    }

    public ContractKey(final long contractId, final byte[] data) {
        this(contractId, KeyPackingUtils.asPackedInts(data));
    }

    public ContractKey(final long contractId, final int[] key) {
        setContractId(contractId);
        setKey(key);
    }

    public static int[] asPackedInts(final UInt256 evmKey) {
        return KeyPackingUtils.asPackedInts(evmKey.toArrayUnsafe());
    }

    public long getContractId() {
        return contractId;
    }

    public void setContractId(final long contractId) {
        this.contractId = contractId;
        this.contractIdNonZeroBytes = KeyPackingUtils.computeNonZeroBytes(contractId);
    }

    public int[] getKey() {
        return uint256Key;
    }

    public BigInteger getKeyAsBigInteger() {
        final ByteBuffer buf = ByteBuffer.allocate(32);
        buf.asIntBuffer().put(uint256Key);
        return new BigInteger(buf.array());
    }

    public void setKey(final long key) {
        setKey(new int[] {0, 0, 0, 0, 0, 0, (int) (key >> Integer.SIZE), (int) key});
    }

    public void setKey(final int[] uint256Key) {
        if (uint256Key == null || uint256Key.length != 8) {
            throw new IllegalArgumentException("The key cannot be null and the key's packed int array size must be 8");
        }
        this.uint256Key = uint256Key;
        this.uint256KeyNonZeroBytes = KeyPackingUtils.computeNonZeroBytes(uint256Key);
    }

    @Override
    public long getClassId() {
        return RUNTIME_CONSTRUCTABLE_ID;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ContractKey that = (ContractKey) o;
        return contractId == that.contractId && Arrays.equals(uint256Key, that.uint256Key);
    }

    /** Special hash to make sure we get good distribution. */
    @Override
    public int hashCode() {
        return (int) stableHash64(
                contractId,
                uint256Key[7],
                uint256Key[6],
                uint256Key[5],
                uint256Key[4],
                uint256Key[3],
                uint256Key[2],
                uint256Key[1],
                uint256Key[0]);
    }

    @Override
    public String toString() {
        return "ContractKey{id="
                + contractId
                + "("
                + Long.toHexString(contractId).toUpperCase()
                + "), key="
                + getKeyAsBigInteger()
                + "("
                + Arrays.stream(uint256Key)
                        .mapToObj(Integer::toHexString)
                        .collect(Collectors.joining(","))
                        .toUpperCase()
                + ")}";
    }

    int getSerializedSizeInBytes() {
        return 1 // total non-zero bytes count
                + contractIdNonZeroBytes // non-zero contractId bytes
                + uint256KeyNonZeroBytes; // non-zero uint256Key bytes
    }

    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.write(getContractIdNonZeroBytesAndUint256KeyNonZeroBytes());
        for (int b = contractIdNonZeroBytes - 1; b >= 0; b--) {
            out.write((byte) (contractId >> (b * 8)));
        }
        serializePackedBytes(uint256Key, uint256KeyNonZeroBytes, out);
    }

    void serialize(final WritableSequentialData out) {
        out.writeByte(getContractIdNonZeroBytesAndUint256KeyNonZeroBytes());
        for (int b = contractIdNonZeroBytes - 1; b >= 0; b--) {
            out.writeByte((byte) (contractId >> (b * 8)));
        }
        serializePackedBytesToPbj(uint256Key, uint256KeyNonZeroBytes, out);
    }

    @Override
    public void serialize(final ByteBuffer buffer) throws IOException {
        buffer.put(getContractIdNonZeroBytesAndUint256KeyNonZeroBytes());
        for (int b = contractIdNonZeroBytes - 1; b >= 0; b--) {
            buffer.put((byte) (contractId >> (b * 8)));
        }
        serializePackedBytesToBuffer(uint256Key, uint256KeyNonZeroBytes, buffer);
    }

    @Override
    public void deserialize(final SerializableDataInputStream in, final int i) throws IOException {
        final byte packedSize = in.readByte();
        this.contractIdNonZeroBytes = getContractIdNonZeroBytesFromPacked(packedSize);
        this.uint256KeyNonZeroBytes = getUint256KeyNonZeroBytesFromPacked(packedSize);
        this.contractId = deserializeContractID(contractIdNonZeroBytes, in, SerializableDataInputStream::readByte);
        this.uint256Key = deserializeUint256Key(uint256KeyNonZeroBytes, in, SerializableDataInputStream::readByte);
    }

    void deserialize(final ReadableSequentialData in) {
        final byte packedSize = in.readByte();
        this.contractIdNonZeroBytes = getContractIdNonZeroBytesFromPacked(packedSize);
        this.uint256KeyNonZeroBytes = getUint256KeyNonZeroBytesFromPacked(packedSize);
        this.contractId = deserializeContractID(contractIdNonZeroBytes, in, ReadableSequentialData::readByte);
        this.uint256Key = deserializeUint256Key(uint256KeyNonZeroBytes, in, ReadableSequentialData::readByte);
    }

    @Override
    public void deserialize(final ByteBuffer buf) {
        final byte packedSize = buf.get();
        this.contractIdNonZeroBytes = getContractIdNonZeroBytesFromPacked(packedSize);
        this.uint256KeyNonZeroBytes = getUint256KeyNonZeroBytesFromPacked(packedSize);
        this.contractId = deserializeContractID(contractIdNonZeroBytes, buf, ByteBuffer::get);
        this.uint256Key = deserializeUint256Key(uint256KeyNonZeroBytes, buf, ByteBuffer::get);
    }

    boolean equalsTo(final BufferedData buf) {
        byte packedSize = buf.readByte();
        final byte contractIdNonZeroBytes = getContractIdNonZeroBytesFromPacked(packedSize);
        if (contractIdNonZeroBytes != this.contractIdNonZeroBytes) {
            return false;
        }
        final byte uint256KeyNonZeroBytes = getUint256KeyNonZeroBytesFromPacked(packedSize);
        if (uint256KeyNonZeroBytes != this.uint256KeyNonZeroBytes) {
            return false;
        }
        final long contractId = deserializeContractID(contractIdNonZeroBytes, buf, BufferedData::readByte);
        if (contractId != this.contractId) {
            return false;
        }
        final int[] uint256Key = deserializeUint256Key(uint256KeyNonZeroBytes, buf, BufferedData::readByte);
        return Arrays.equals(uint256Key, this.uint256Key);

    }

    @Override
    public int getVersion() {
        return MERKLE_VERSION;
    }

    /**
     * Read the key size in bytes from a byte buffer containing a serialized ContractKey
     *
     * @param buf The buffer to read from, its position will be restored after we read
     * @return the size in byte for the key contained in buffer.
     */
    public static int readKeySize(final ByteBuffer buf) {
        final byte packedSize = buf.get();
        buf.position(buf.position() - 1); // move position back, like we never read anything
        return 1 + getContractIdNonZeroBytesFromPacked(packedSize) + getUint256KeyNonZeroBytesFromPacked(packedSize);
    }

    // =================================================================================================================
    // Private methods, left package for UnitTests

    /**
     * Deserialize long contract id from data source
     *
     * @param contractIdNonZeroBytes the number of non-zero bytes stored for the long contract id
     * @param dataSource The data source to read from
     * @param reader function to read a byte from the data source
     * @param <D> type for data source, e.g. ByteBuffer or InputStream
     * @return long contract id read
     * @throws E If there was a problem reading
     */
    static <D, E extends Exception> long deserializeContractID(
            final byte contractIdNonZeroBytes, final D dataSource, final KeyPackingUtils.ByteReaderFunction<D, E> reader)
            throws E {
        long contractId = 0;
        /* Bytes are encountered in order of significance (big-endian) */
        for (int byteI = 0, shiftI = contractIdNonZeroBytes - 1; byteI < contractIdNonZeroBytes; byteI++, shiftI--) {
            contractId |= ((long) reader.read(dataSource) & 255) << BIT_SHIFTS[shiftI];
        }
        return contractId;
    }

    /**
     * Get contractIdNonZeroBytes and uint256KeyNonZeroBytes packed into a single byte.
     *
     * <p>contractIdNonZeroBytes is in the range of 0-8 uint256KeyNonZeroBytes is in the range of
     * 0-32
     *
     * <p>As those can not be packed in their entirety in a single byte we accept a minimum of 1, so
     * we store range 1-9 and 1-32 packed into a single byte.
     *
     * @return packed byte containing contractIdNonZeroBytes and uint256KeyNonZeroBytes
     */
    byte getContractIdNonZeroBytesAndUint256KeyNonZeroBytes() {
        final byte contractIdNonZeroBytesMinusOne =
                contractIdNonZeroBytes == 0 ? (byte) 0 : (byte) (contractIdNonZeroBytes - 1);
        final byte uint256KeyNonZeroBytesMinusOne =
                uint256KeyNonZeroBytes == 0 ? (byte) 0 : (byte) (uint256KeyNonZeroBytes - 1);
        return (byte) ((contractIdNonZeroBytesMinusOne << 5) | uint256KeyNonZeroBytesMinusOne & 0xff);
    }

    /**
     * get contractIdNonZeroBytes from packed byte
     *
     * @param packed byte containing contractIdNonZeroBytes and uint256KeyNonZeroBytes
     * @return contractIdNonZeroBytes
     */
    static byte getContractIdNonZeroBytesFromPacked(final byte packed) {
        return (byte) ((Byte.toUnsignedInt(packed) >> 5) + 1);
    }

    /**
     * Get uint256KeyNonZeroBytes from packed byte
     *
     * @param packed byte containing contractIdNonZeroBytes and uint256KeyNonZeroBytes
     * @return uint256KeyNonZeroBytes
     */
    static byte getUint256KeyNonZeroBytesFromPacked(final byte packed) {
        return (byte) ((packed & 0b00011111) + 1);
    }

    /** get contractIdNonZeroBytes for tests */
    byte getContractIdNonZeroBytes() {
        return contractIdNonZeroBytes;
    }

    /** get uint256KeyNonZeroBytes for tests */
    public byte getUint256KeyNonZeroBytes() {
        return uint256KeyNonZeroBytes;
    }

    /**
     * Get a single byte out of our Unit256 stored as 8 integers in an int array.
     *
     * @param byteIndex The index of the byte we want with 0 being the least significant byte, and
     *     31 being the most significant.
     * @return the byte at given index
     */
    public byte getUint256Byte(final int byteIndex) {
        return KeyPackingUtils.extractByte(uint256Key, byteIndex);
    }

    @Override
    public int getMinimumSupportedVersion() {
        return 1;
    }

    /**
     * An unchanging, UNTOUCHABLE implementation of {@code hashCode()} to reduce hash collisions.
     *
     * @param x0 the first long to hash
     * @param x1 the second long to hash
     * @param x2 the third long to hash
     * @param x3 the fourth long to hash
     * @param x4 the fifth long to hash
     * @param x5 the sixth long to hash
     * @param x6 the seventh long to hash
     * @param x7 the eighth long to hash
     * @param x8 the ninth long to hash
     * @return a near-optimal non-cryptographic hash of the given longs
     */
    private static long stableHash64(long x0, long x1, long x2, long x3, long x4, long x5, long x6, long x7, long x8) {
        return stablePerm64(stablePerm64(stablePerm64(stablePerm64(stablePerm64(stablePerm64(
                                                        stablePerm64(stablePerm64(stablePerm64(x0) ^ x1) ^ x2) ^ x3)
                                                ^ x4)
                                        ^ x5)
                                ^ x6)
                        ^ x7)
                ^ x8);
    }

    private static long stablePerm64(long x) {
        x += x << 30;
        x ^= x >>> 27;
        x += x << 16;
        x ^= x >>> 20;
        x += x << 5;
        x ^= x >>> 18;
        x += x << 10;
        x ^= x >>> 24;
        x += x << 30;
        return x;
    }
}
