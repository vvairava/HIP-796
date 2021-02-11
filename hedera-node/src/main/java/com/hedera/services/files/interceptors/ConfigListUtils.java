package com.hedera.services.files.interceptors;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
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
 * ‍
 */

import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ConfigListUtils {
	public static final Logger log = LogManager.getLogger(ConfigListUtils.class);

	ConfigListUtils() {
		throw new IllegalStateException();
	}

	public static boolean isConfigList(byte[] data) {
		try {
			ServicesConfigurationList.parseFrom(data);
			return true;
		} catch (InvalidProtocolBufferException ignore) {
			log.warn(ignore.getMessage());
			return false;
		}
	}

	public static ServicesConfigurationList uncheckedParse(byte[] data) {
		try {
			return ServicesConfigurationList.parseFrom(data);
		} catch (InvalidProtocolBufferException impossible) {
			log.warn("Impossible to get this InvalidProtocolBufferException {}", impossible.getMessage());
			return ServicesConfigurationList.getDefaultInstance();
		}
	}
}
