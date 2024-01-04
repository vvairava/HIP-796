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

package com.swirlds.base.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToStringBuilderTest {

    @Test
    void appendingWithNameAndValue() {
        final ToStringBuilder tsb =
                new ToStringBuilder(this).append("field1", "value1").append("field2", "value2");

        assertEquals("ToStringBuilderTest[field1=value1,field2=value2]", tsb.toString());
    }

    @Test
    void appendingValueOnly() {
        final ToStringBuilder tsb =
                new ToStringBuilder(this).append("testValue1").append("testValue2");

        assertEquals("ToStringBuilderTest[testValue1,testValue2]", tsb.toString());
    }

    @Test
    void emptyToString() {
        final ToStringBuilder tsb = new ToStringBuilder(this);
        assertEquals("ToStringBuilderTest[]", tsb.toString());
    }

    @Test
    void testMixedAppending() {
        final ToStringBuilder tsb = new ToStringBuilder(this)
                .append("field1", "value1")
                .append("valueOnly1")
                .append("field2", "value2")
                .append("valueOnly2");

        assertEquals("ToStringBuilderTest[field1=value1,valueOnly1,field2=value2,valueOnly2]", tsb.toString());
    }

    @Test
    void handlingNull() {
        final String s = null;
        final ToStringBuilder tsb =
                new ToStringBuilder(this).append("field1", s).append(s);

        assertEquals("ToStringBuilderTest[field1=<null>,<null>]", tsb.toString());
    }

    static class Parent {
        private static final String PARENT_VALUE = "parentValue";

        @Override
        public String toString() {
            return new ToStringBuilder(this).append("parentField", PARENT_VALUE).toString();
        }
    }

    static class Child extends Parent {
        private static final String CHILD_VALUE = "childValue";

        @Override
        public String toString() {
            return new ToStringBuilder(this)
                    .appendSuper(super.toString())
                    .append("childField", CHILD_VALUE)
                    .toString();
        }
    }

    @Test
    void appendSuper() {
        final Child child = new Child();
        assertEquals("ToStringBuilderTest.Child[parentField=parentValue,childField=childValue]", child.toString());
    }
}
