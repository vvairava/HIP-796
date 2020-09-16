package com.hedera.test.factories.sigs;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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

import com.swirlds.common.crypto.Signature;
import com.swirlds.common.crypto.VerificationStatus;

import static com.swirlds.common.crypto.VerificationStatus.INVALID;
import static com.swirlds.common.crypto.VerificationStatus.VALID;
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Map;

public class SigWrappers {
	public static List<Signature> asValid(List<Signature> sigs) {
		return sigs.stream().map(sig -> new SigWithKnownStatus(sig, VALID)).collect(toList());
	}

	public static List<Signature> asInvalid(List<Signature> sigs) {
		return sigs.stream().map(sig -> new SigWithKnownStatus(sig, INVALID)).collect(toList());
	}

	public static List<Signature> asKind(List<Map.Entry<Signature, VerificationStatus>> sigToStatus) {
		return sigToStatus.stream()
				.map(entry -> new SigWithKnownStatus(entry.getKey(), entry.getValue()))
				.collect(toList());
	}

	private static class SigWithKnownStatus extends Signature {
		private final VerificationStatus status;

		public SigWithKnownStatus(Signature wrapped, VerificationStatus status) {
			super(wrapped);
			this.status = status;
		}

		@Override
		public VerificationStatus getSignatureStatus() {
			return status;
		}
	}
}
