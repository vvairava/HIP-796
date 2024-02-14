/*
 * Copyright (C) 2021-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.crypto;

import com.swirlds.common.crypto.internal.CryptoUtils;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * An instantiation of this class holds all the keys and CSPRNG state for one Platform object. No other
 * class should store any secret or private key/seed information.
 * <p>
 * The algorithms and key sizes used here are chosen in accordance with the IAD-NSA Commercial National
 * Security Algorithm (CNSA) Suite, and TLS 1.2, as implemented by the SUN and SunEC security providers,
 * using the JCE Unlimited Strength Jurisdiction files. The TLS suite used here is called
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384. Java uses the NIST p-384 curve specified by the CNSA for ECDH
 * and ECDSA.
 * <p>
 * To aid in key recovery, this is designed so that all of the keys here are generated by deterministic
 * functions of a triplet (master key, swirld ID, member ID), which are passed in to the constructor. When
 * TLS key agreement needs a source of random numbers, it uses SecureRandom with "SHA1PRNG", which calls the
 * underlying operating system's /dev/random and /dev/urandom (or Windows equivalent) to incorporate entropy
 * gathered by the operating system. So the key pairs are generated deterministically, but key agreement
 * uses true random numbers.
 * <p>
 * At the time this class is first being written, neither TLS 1.3 nor Java 9 are available. As they become
 * available, it may become appropriate to switch from ECDSA/ECDHE to something based on Ed25519, for
 * increased speed, decreased bandwidth usage, and better safety (e.g., not needing strong random numbers
 * for key agreement). But when (or whether) that change happens will depend on whether standards such as
 * the CNSA Suite are updated. In the longer term, this may be upgraded to a post quantum algorithm (or a
 * hybrid using both pre-quantum and post-quantum). But again, this will depend on a number of factors. The
 * plan is to continue to follow the national standard (currently CNSA Suit), changing the algorithms as the
 * standard changes.
 */
public record KeysAndCerts(
        KeyPair sigKeyPair,
        KeyPair agrKeyPair,
        X509Certificate sigCert,
        X509Certificate agrCert,
        PublicStores publicStores) {
    private static final int SIG_SEED = 2;
    private static final int AGR_SEED = 0;

    /**
     * Creates an instance holding all the keys and certificates. It just reads its own key pairs
     * from privateKeyStore, and remembers the trust stores.
     *
     * @param name
     * 		The name to associate with the key. For example, if it is "alice", then the three key
     * 		pairs will be named "s-alice", "e-alice", "a-alice" for signing, encrypting, and key
     * 		agreement.
     * @param privateKeyStore
     * 		read the 2 keyPairs (signing,agreement) from this store
     * @param publicStores
     * 		all public certificates
     * @throws KeyStoreException
     * 		if the supplied key store is not initialized
     * @throws UnrecoverableKeyException
     * 		if a required key cannot be recovered (e.g., the given password is wrong).
     * @throws NoSuchAlgorithmException
     * 		if the algorithm for recovering a required key cannot be found
     * @throws KeyLoadingException
     * 		if a required certificate is missing or is not an instance of X509Certificate
     */
    public static KeysAndCerts loadExisting(
            final String name, final char[] password, final KeyStore privateKeyStore, final PublicStores publicStores)
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyLoadingException {
        final String signingName = KeyCertPurpose.SIGNING.storeName(name);
        final String agreementName = KeyCertPurpose.AGREEMENT.storeName(name);
        return new KeysAndCerts(
                getKeyPair(privateKeyStore, password, signingName),
                getKeyPair(privateKeyStore, password, agreementName),
                publicStores.getCertificate(KeyCertPurpose.SIGNING, name),
                publicStores.getCertificate(KeyCertPurpose.AGREEMENT, name),
                publicStores);
    }

    private static KeyPair getKeyPair(final KeyStore privateKeyStore, final char[] password, final String storeName)
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyLoadingException {
        final Certificate certificate = privateKeyStore.getCertificate(storeName);
        if (certificate == null) {
            throw new KeyLoadingException(String.format("Certificate '%s' not found!", storeName));
        }
        Key privateKey = privateKeyStore.getKey(storeName, password);
        if (privateKey instanceof PrivateKey pk) {
            return new KeyPair(certificate.getPublicKey(), pk);
        }
        throw new KeyLoadingException(String.format("Key '%s' is not an instance of PrivateKey!", storeName));
    }

    /**
     * Creates an instance holding all the keys and certificates. This also generates the key pairs and certs and CSPRNG
     * state. The key pairs are generated as a function of the seed. The seed is the combination of the three
     * parameters. The signing key pair is used to sign all 3 certs.
     *
     * @param name
     * 		The name to associate with the key. For example, if it is "alice", then the three key
     * 		pairs will be named "s-alice", "e-alice", "a-alice" for signing, encrypting, and key
     * 		agreement.
     * @param masterKey
     * 		master key used to derive key pairs for many identities in many swirlds
     * @param swirldId
     * 		which swirlds is running
     * @param memberId
     * 		which identity is acting as a member in this swirld (because one human user might have several identities
     * 		running in a given swirld)
     * @param publicStores
     * 		all public certificates
     */
    public static KeysAndCerts generate(
            final String name,
            final byte[] masterKey,
            final byte[] swirldId,
            final byte[] memberId,
            final PublicStores publicStores)
            throws NoSuchAlgorithmException, NoSuchProviderException, KeyStoreException, KeyGeneratingException {
        final KeyPairGenerator sigKeyGen;
        final KeyPairGenerator agrKeyGen;

        final SecureRandom sigDetRandom; // deterministic CSPRNG, used briefly then discarded
        final SecureRandom agrDetRandom; // deterministic CSPRNG, used briefly then discarded

        sigKeyGen = KeyPairGenerator.getInstance(CryptoConstants.SIG_TYPE1, CryptoConstants.SIG_PROVIDER);
        agrKeyGen = KeyPairGenerator.getInstance(CryptoConstants.AGR_TYPE, CryptoConstants.AGR_PROVIDER);

        sigDetRandom = CryptoUtils.getDetRandom(); // deterministic, not shared
        agrDetRandom = CryptoUtils.getDetRandom(); // deterministic, not shared

        sigDetRandom.setSeed(masterKey);
        sigDetRandom.setSeed(swirldId);
        sigDetRandom.setSeed(memberId);
        sigDetRandom.setSeed(SIG_SEED);
        sigKeyGen.initialize(CryptoConstants.SIG_KEY_SIZE_BITS, sigDetRandom);

        agrDetRandom.setSeed(masterKey);
        agrDetRandom.setSeed(swirldId);
        agrDetRandom.setSeed(memberId);
        agrDetRandom.setSeed(AGR_SEED);
        agrKeyGen.initialize(CryptoConstants.AGR_KEY_SIZE_BITS, agrDetRandom);

        final KeyPair sigKeyPair = sigKeyGen.generateKeyPair();
        final KeyPair agrKeyPair = agrKeyGen.generateKeyPair();

        final String dnS = CryptoStatic.distinguishedName("s-" + name);
        final String dnA = CryptoStatic.distinguishedName("a-" + name);

        // create the 2 certs (java.security.cert.Certificate)
        // both are signed by sigKeyPair, so sigCert is self-signed
        final X509Certificate sigCert =
                CryptoStatic.generateCertificate(dnS, sigKeyPair, dnS, sigKeyPair, sigDetRandom);
        final X509Certificate agrCert =
                CryptoStatic.generateCertificate(dnA, agrKeyPair, dnS, sigKeyPair, agrDetRandom);

        // add to the 3 trust stores (which have references stored here and in the caller)
        publicStores.setCertificate(KeyCertPurpose.SIGNING, sigCert, name);
        publicStores.setCertificate(KeyCertPurpose.AGREEMENT, agrCert, name);

        return new KeysAndCerts(sigKeyPair, agrKeyPair, sigCert, agrCert, publicStores);
    }
}
