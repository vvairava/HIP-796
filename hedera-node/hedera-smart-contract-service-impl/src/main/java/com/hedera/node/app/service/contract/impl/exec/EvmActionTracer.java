/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.exec;

import static com.hedera.hapi.streams.ContractActionType.PRECOMPILE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasActionSidecarsEnabled;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasActionValidationEnabled;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hasValidatedActionSidecarsEnabled;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.*;

import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTracer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * Tracer implementation that chooses an appropriate {@link ActionStack} method to call based on the
 * {@link MessageFrame} state and system configuration.
 */
public class EvmActionTracer implements HederaEvmTracer {
    private static final Logger log = LogManager.getLogger(EvmActionTracer.class);

    private final ActionStack actionStack;

    public EvmActionTracer(@NonNull final ActionStack actionStack) {
        this.actionStack = requireNonNull(actionStack);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void customInit(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        if (hasActionSidecarsEnabled(frame)) {
            actionStack.pushActionOfTopLevel(frame);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void customFinalize(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        if (hasValidatedActionSidecarsEnabled(frame)) {
            actionStack.sanitizeFinalActionsAndLogAnomalies(frame, log, Level.WARN);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tracePostExecution(
            @NonNull final MessageFrame frame, @NonNull final Operation.OperationResult operationResult) {
        requireNonNull(frame);
        requireNonNull(operationResult);
        if (!hasActionSidecarsEnabled(frame)) {
            return;
        }
        final var state = frame.getState();
        if (state == CODE_SUSPENDED) {
            actionStack.pushActionOfIntermediate(frame);
        } else if (state != CODE_EXECUTING) {
            actionStack.finalizeLastActionIn(frame, hasActionValidationEnabled(frame));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void customTracePrecompileResult(@NonNull MessageFrame frame, @NonNull ContractActionType type) {
        requireNonNull(type);
        requireNonNull(frame);
        if (hasActionSidecarsEnabled(frame) && !isAlreadyFinalized(frame, type)) {
            actionStack.finalizeLastActionAsPrecompileIn(frame, type, hasActionValidationEnabled(frame));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void traceAccountCreationResult(
            @NonNull final MessageFrame frame, @NonNull final Optional<ExceptionalHaltReason> haltReason) {
        requireNonNull(frame);
        requireNonNull(haltReason);
        if (hasActionSidecarsEnabled(frame)) {
            actionStack.finalizeLastActionIn(frame, hasActionValidationEnabled(frame));
        }
    }

    private boolean isAlreadyFinalized(@NonNull MessageFrame frame, @NonNull ContractActionType type) {
        return PRECOMPILE.equals(type) && EXCEPTIONAL_HALT.equals(frame.getState());
    }
}
