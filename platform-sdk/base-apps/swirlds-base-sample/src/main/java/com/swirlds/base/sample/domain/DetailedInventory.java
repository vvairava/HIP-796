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

package com.swirlds.base.sample.domain;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public record DetailedInventory(
        @NonNull Item item,
        @NonNull Integer amount,
        @NonNull List<StockDetail> stock,
        @NonNull List<Movement> movements) {

    public record Movement(@NonNull Date date, @NonNull Integer amount, @NonNull String operationUUID) {}

    public record StockDetail(@NonNull BigDecimal unitaryPrice, @NonNull Date date, @NonNull Integer quantity) {}
}
