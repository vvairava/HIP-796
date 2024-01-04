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

package com.hedera.node.app.service.contract.impl.exec.failure;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An exception thrown when a transaction is aborted before entering the EVM.
 *
 * <p>Includes the effective Hedera id of the sender.
 */
public class AbortException extends HandleException {
    private final AccountID senderId;

    public AbortException(@NonNull final ResponseCodeEnum status, @NonNull final AccountID senderId) {
        super(status);
        this.senderId = requireNonNull(senderId);
    }

    /**
     * Returns the effective Hedera id of the sender.
     *
     * @return the effective Hedera id of the sender
     */
    public AccountID senderId() {
        return senderId;
    }

    /**
     * Throws an {@code AbortException} if the given flag is {@code false}.
     *
     * @param flag the flag to check
     * @param status the status to use if the flag is {@code false}
     * @param senderId the effective Hedera id of the sender
     */
    public static void validateTrueOrAbort(
            final boolean flag, @NonNull final ResponseCodeEnum status, @NonNull final AccountID senderId) {
        if (!flag) {
            throw new AbortException(status, senderId);
        }
    }
}
