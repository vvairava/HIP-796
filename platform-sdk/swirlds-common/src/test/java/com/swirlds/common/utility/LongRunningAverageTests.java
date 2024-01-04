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

package com.swirlds.common.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LongRunningAverage Tests")
class LongRunningAverageTests {

    @Test
    @DisplayName("Illegal Capacity Test")
    void illegalCapacityTest() {
        assertThrows(IllegalArgumentException.class, () -> new LongRunningAverage(Integer.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new LongRunningAverage(-1));
        assertThrows(IllegalArgumentException.class, () -> new LongRunningAverage(0));
    }

    @Test
    @DisplayName("Capacity One Test")
    void capacityOneTest() {
        final LongRunningAverage average = new LongRunningAverage(1);

        assertEquals(0, average.getAverage());
        assertTrue(average.isEmpty());
        assertEquals(0, average.size());

        average.add(1234);
        assertEquals(1234, average.getAverage());
        assertFalse(average.isEmpty());
        assertEquals(1, average.size());

        average.add(4321);
        assertEquals(4321, average.getAverage());
        assertFalse(average.isEmpty());
        assertEquals(1, average.size());
    }

    @Test
    @DisplayName("High Capacity Test")
    void highCapacityTest() {
        final LongRunningAverage average = new LongRunningAverage(100);

        assertEquals(0, average.getAverage());
        assertTrue(average.isEmpty());
        assertEquals(0, average.size());

        for (int i = 0; i < 100; i++) {
            average.add(i);
        }

        assertEquals(49, average.getAverage());
        assertFalse(average.isEmpty());
        assertEquals(100, average.size());

        for (int i = 0; i < 100; i++) {
            average.add(100 + i);
        }

        assertEquals(149, average.getAverage());
        assertFalse(average.isEmpty());
        assertEquals(100, average.size());
    }
}
