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

package com.swirlds.platform.hcm.impl.tss.groth21;

import com.swirlds.platform.hcm.api.pairings.Field;
import com.swirlds.platform.hcm.api.pairings.FieldElement;
import com.swirlds.platform.hcm.api.signaturescheme.SignatureSchema;
import com.swirlds.platform.hcm.api.tss.Tss;
import com.swirlds.platform.hcm.api.tss.TssMessage;
import com.swirlds.platform.hcm.api.tss.TssPrivateShare;
import com.swirlds.platform.hcm.api.tss.TssShareClaim;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A Groth21 implementation of a Threshold Signature Scheme.
 *
 * @param signatureSchema the signature schema to use
 */
public record Groth21Tss(@NonNull SignatureSchema signatureSchema) implements Tss {
    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TssMessage generateTssMessage(
            @NonNull final Random random,
            @NonNull final SignatureSchema signatureSchema,
            @NonNull final List<TssShareClaim> pendingShareClaims,
            @NonNull final TssPrivateShare privateShare,
            final int threshold) {

        final DensePolynomial polynomial =
                DensePolynomial.fromSecret(random, privateShare.privateKey().secretElement(), threshold);
        final FeldmanCommitment polynomialCommitment =
                FeldmanCommitment.create(signatureSchema.getPublicKeyGroup(), polynomial);

        final List<FieldElement> randomness = generateRandomness(random, signatureSchema.getField());

        final List<Groth21UnencryptedShare> unencryptedShares = new ArrayList<>();
        for (final TssShareClaim shareClaim : pendingShareClaims) {
            unencryptedShares.add(new Groth21UnencryptedShare(
                    shareClaim, polynomial.evaluate(shareClaim.shareId().idElement())));
        }

        final Groth21MultishareCiphertext multishareCiphertext =
                Groth21MultishareCiphertext.create(signatureSchema, randomness, unencryptedShares);
        final Groth21Proof proof = Groth21Proof.create(random, randomness, unencryptedShares);

        return new TssMessage(privateShare.shareId(), multishareCiphertext, polynomialCommitment, proof);
    }

    /**
     * Generates randomness for a Groth21 TSS message
     *
     * @param random a source of randomness
     * @param field  the field to use
     * @return a list of random field elements
     */
    private static List<FieldElement> generateRandomness(@NonNull final Random random, @NonNull final Field field) {
        final List<FieldElement> randomness = new ArrayList<>();

        // we need a random element for each byte of a secret
        for (int i = 0; i < field.getElementSize(); i++) {
            randomness.add(field.randomElement(random));
        }

        return randomness;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public SignatureSchema getSignatureSchema() {
        return signatureSchema;
    }
}
