/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.hcm.api.pairings;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a finite field used in the Boneh-Lynn-Shacham (BLS) cryptographic scheme.
 * <p>A finite field, often denoted as 𝔽q, where (q) is a prime power, is a field with a finite number of elements.</p>
 *
 * <p>This is a factory interface, responsible for creating {@link FieldElement} which are scalars belonging to the
 * field represented by this instance.
 * </p>
 *
 * @see FieldElement
 */
public interface Field {

    /**
     * Creates a new field element from a long
     *
     * @param inputLong the long to use to create the field element
     * @return the new field element
     */
    @NonNull
    FieldElement elementFromLong(long inputLong);

    /**
     * Creates a new field element with value 0
     *
     * @return the new field element
     */
    @NonNull
    FieldElement zeroElement();

    /**
     * Creates a new field element with value 1
     *
     * @return the new field element
     */
    @NonNull
    FieldElement oneElement();

    /**
     * Creates a field element from a seed (32 bytes)
     *
     * @param seed a seed to use to generate randomness
     * @return the new field element
     */
    @NonNull
    FieldElement randomElement(byte[] seed);

    /**
     * Creates a field element from its serialized encoding
     *
     * @param bytes serialized form
     * @return the new field element, or null if construction fails
     */
    @NonNull
    FieldElement elementFromBytes(byte[] bytes);

    /**
     * Gets the size in bytes of an element
     *
     * @return the size of an element
     */
    int getElementSize();

    /**
     * Gets the size in bytes of the seed necessary to generate a new element
     *
     * @return the size of a seed needed to generate a new element
     */
    int getSeedSize();

    @NonNull
    BilinearPairing getPairing();
}
