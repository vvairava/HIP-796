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

package com.swirlds.platform.tss.bls.impl.bls12381;

import com.swirlds.platform.tss.bls.api.CurveType;
import com.swirlds.platform.tss.bls.api.Group;
import com.swirlds.platform.tss.bls.api.GroupElement;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * G1 group used in BLS12-381
 */
public class Bls12381Group implements Group {

    @NonNull
    @Override
    public GroupElement getGenerator() {
        return null;
    }

    @NonNull
    @Override
    public GroupElement oneElement() {
        return null;
    }

    @NonNull
    @Override
    public GroupElement randomElement(byte[] seed) {
        return null;
    }

    @NonNull
    @Override
    public GroupElement randomElement() {
        return null;
    }

    @NonNull
    @Override
    public GroupElement elementFromHash(byte[] input) {
        return null;
    }

    @NonNull
    @Override
    public GroupElement batchMultiply(@NonNull GroupElement elements) {
        checkSameCurveType(elements);
        return null;
    }

    @NonNull
    @Override
    public GroupElement elementFromBytes(byte[] bytes) {
        checkSameCurveType(bytes);
        // We should remove bytes[0] before sending it to the native call
        return null;
    }

    @Override
    public int getCompressedSize() {
        return 0;
    }

    @Override
    public int getUncompressedSize() {
        return 0;
    }

    @Override
    public int getSeedSize() {
        return 0;
    }

    @Override
    public CurveType curveType() {
        return CurveType.BLS12_381;
    }
}
