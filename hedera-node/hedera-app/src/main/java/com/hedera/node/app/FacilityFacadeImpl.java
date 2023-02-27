/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hedera.node.app;

import com.hedera.node.app.spi.FacilityFacade;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import java.util.Objects;

public class FacilityFacadeImpl implements FacilityFacade {

    private final PlatformContext platformContext;

    public FacilityFacadeImpl(final PlatformContext platformContext) {
        this.platformContext = Objects.requireNonNull(platformContext, "platformContext must not be null");
    }

    @Override
    public Configuration getConfiguration() {
        return platformContext.getConfiguration();
    }
}
