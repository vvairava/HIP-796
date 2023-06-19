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

package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.STAKING_NOT_ENABLED;
import static com.hedera.node.app.service.mono.context.properties.PropertyNames.ENTITIES_MAX_LIFETIME;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.test.utils.KeyUtils.B_COMPLEX_KEY;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.config.VersionedConfigImpl;
import com.hedera.node.app.service.mono.config.HederaNumbers;
import com.hedera.node.app.service.mono.context.properties.GlobalDynamicProperties;
import com.hedera.node.app.service.mono.context.properties.PropertySource;
import com.hedera.node.app.service.token.impl.CryptoSignatureWaiversImpl;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoUpdateHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.StakingValidator;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.workflows.handle.validation.StandardizedAttributeValidator;
import com.hedera.node.app.workflows.handle.validation.StandardizedExpiryValidator;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoUpdateHandlerTest extends CryptoHandlerTestBase {
    @Mock(strictness = Strictness.LENIENT)
    private HandleContext handleContext;

    @Mock
    private NodeInfo nodeInfo;

    @Mock(strictness = Strictness.LENIENT)
    private LongSupplier consensusSecondNow;

    @Mock
    private GlobalDynamicProperties dynamicProperties;

    @Mock
    private PropertySource compositeProps;

    @Mock
    private HederaNumbers hederaNumbers;

    @Mock(strictness = Strictness.LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = Strictness.LENIENT)
    private CryptoSignatureWaiversImpl waivers;

    private AttributeValidator attributeValidator;
    private ExpiryValidator expiryValidator;
    private StakingValidator stakingValidator;
    private final long updateAccountNum = 32132L;
    private final AccountID updateAccountId =
            AccountID.newBuilder().accountNum(updateAccountNum).build();

    private Account updateAccount;
    private Configuration configuration;
    private CryptoUpdateHandler subject;

    @BeforeEach
    public void setUp() {
        super.setUp();
        updateAccount =
                givenValidAccount(updateAccountNum).copyBuilder().key(otherKey).build();
        updateWritableAccountStore(Map.of(updateAccountId.accountNum(), updateAccount, accountNum, account));
        updateReadableAccountStore(Map.of(updateAccountId.accountNum(), updateAccount, accountNum, account));

        configuration = new HederaTestConfigBuilder().getOrCreateConfig();
        given(compositeProps.getLongProperty(ENTITIES_MAX_LIFETIME)).willReturn(72000L);
        attributeValidator = new StandardizedAttributeValidator(consensusSecondNow, compositeProps, dynamicProperties);
        expiryValidator = new StandardizedExpiryValidator(
                System.out::println, attributeValidator, consensusSecondNow, hederaNumbers, configProvider);
        stakingValidator = new StakingValidator();
        subject = new CryptoUpdateHandler(waivers, stakingValidator, nodeInfo);
    }

    @Test
    void cryptoUpdateVanilla() throws PreCheckException {
        final var txn = new CryptoUpdateBuilder().build();

        given(waivers.isNewKeySignatureWaived(txn, id)).willReturn(false);
        given(waivers.isTargetAccountSignatureWaived(txn, id)).willReturn(false);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertTrue(context.requiredNonPayerKeys().contains(otherKey));
    }

    @Test
    void cryptoUpdateNewSignatureKeyWaivedVanilla() throws PreCheckException {
        final var txn = new CryptoUpdateBuilder().withKey(key).build();

        given(waivers.isNewKeySignatureWaived(txn, id)).willReturn(true);
        given(waivers.isTargetAccountSignatureWaived(txn, id)).willReturn(false);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertIterableEquals(List.of(otherKey), context.requiredNonPayerKeys());
    }

    @Test
    void cryptoUpdateTargetSignatureKeyWaivedVanilla() throws PreCheckException {
        final var newKey = B_COMPLEX_KEY;
        final var txn = new CryptoUpdateBuilder().withKey(newKey).build();
        given(waivers.isNewKeySignatureWaived(any(), any())).willReturn(false);
        given(waivers.isTargetAccountSignatureWaived(any(), any())).willReturn(true);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertFalse(context.requiredNonPayerKeys().contains(otherKey));
        assertTrue(context.requiredNonPayerKeys().contains(newKey));
    }

    @Test
    void cryptoUpdateUpdateAccountMissingFails() throws PreCheckException {
        final var txn = new CryptoUpdateBuilder().build();
        readableAccounts = emptyReadableAccountStateBuilder().value(id, account).build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates);

        given(waivers.isNewKeySignatureWaived(any(), any())).willReturn(true);
        given(waivers.isTargetAccountSignatureWaived(any(), any())).willReturn(false);

        final var context = new FakePreHandleContext(readableStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_ACCOUNT_ID);
    }

    @Test
    void proxySetFailsInPureChecks() throws PreCheckException {
        final var txn =
                new CryptoUpdateBuilder().withProxyAccountNum(id.accountNum()).build();
        givenTxnWith(txn);
        final var context = new FakePreHandleContext(readableStore, txn);

        assertThatThrownBy(() -> subject.preHandle(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED));
    }

    @Test
    void updatesStakedAccountNumberIfPresentAndEnabled() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var txn =
                new CryptoUpdateBuilder().withStakedAccountId(id.accountNum()).build();
        givenTxnWith(txn);

        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());

        subject.handle(handleContext);

        assertEquals(id.accountNum(), writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void updatesStakedNodeNumberIfPresentAndEnabled() {
        given(nodeInfo.isValidId(anyLong())).willReturn(true);
        final var txn = new CryptoUpdateBuilder().withStakedNodeId(0).build();
        givenTxnWith(txn);

        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());

        subject.handle(handleContext);

        assertEquals(-1, writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void validatesStakedAccountIdProvided() {
        final var txn = new CryptoUpdateBuilder().withStakedAccountId(10).build();
        givenTxnWith(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_STAKING_ID));
    }

    @Test
    void validatesStakedNodeIdProvided() {
        final var txn = new CryptoUpdateBuilder().withStakedNodeId(10).build();
        givenTxnWith(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_STAKING_ID));
    }

    @Test
    void doesntThrowStakedAccountIdProvidedIfValid() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var txn = new CryptoUpdateBuilder().withStakedAccountId(3).build();
        givenTxnWith(txn);
        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());
        subject.handle(handleContext);
        assertEquals(3, writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void doesntThrowStakedNodeIdProvidedIfValid() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var txn = new CryptoUpdateBuilder().withStakedNodeId(3).build();
        givenTxnWith(txn);
        given(nodeInfo.isValidId(3)).willReturn(true);

        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());
        subject.handle(handleContext);
        assertEquals(-4, writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void sentinelValuesForStakedAccountNumberWorks() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var txn = new CryptoUpdateBuilder().withStakedAccountId(3).build();
        givenTxnWith(txn);
        subject.handle(handleContext);
        assertEquals(3, writableStore.get(updateAccountId).stakedNumber());

        final var txn1 = new CryptoUpdateBuilder().withStakedAccountId(0).build();
        givenTxnWith(txn1);
        subject.handle(handleContext);
        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());

        final var txn2 = new CryptoUpdateBuilder().withStakedAccountId(-2).build();
        givenTxnWith(txn2);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_STAKING_ID));
        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void sentinelValuesForStakedNodeNumberWorks() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var txn = new CryptoUpdateBuilder().withStakedAccountId(3).build();
        givenTxnWith(txn);
        subject.handle(handleContext);
        assertEquals(3, writableStore.get(updateAccountId).stakedNumber());

        final var txn1 = new CryptoUpdateBuilder().withStakedNodeId(-1).build();
        givenTxnWith(txn1);
        subject.handle(handleContext);
        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());

        final var txn2 = new CryptoUpdateBuilder().withStakedNodeId(-2).build();
        givenTxnWith(txn2);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_STAKING_ID));
        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void rejectsStakedIdIfStakingDisabled() {
        final var txn = new CryptoUpdateBuilder().withStakedAccountId(3).build();
        givenTxnWith(txn);

        final var config = new HederaTestConfigBuilder()
                .withValue("staking.isEnabled", false)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(STAKING_NOT_ENABLED));
        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void rejectsDeclineRewardUpdateIfStakingDisabled() {
        final var txn = new CryptoUpdateBuilder().withDeclineReward(false).build();
        givenTxnWith(txn);

        final var config = new HederaTestConfigBuilder()
                .withValue("staking.isEnabled", false)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(STAKING_NOT_ENABLED));
        assertEquals(0, writableStore.get(updateAccountId).stakedNumber());
    }

    @Test
    void updatesDeclineRewardIfPresent() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        // decline reward set to false will change values to false
        final var falseReward =
                new CryptoUpdateBuilder().withDeclineReward(false).build();
        givenTxnWith(falseReward);
        assertEquals(true, writableStore.get(updateAccountId).declineReward());
        subject.handle(handleContext);
        assertEquals(false, writableStore.get(updateAccountId).declineReward());
        // decline reward set to true
        final var trueReward = new CryptoUpdateBuilder().withDeclineReward(true).build();
        givenTxnWith(trueReward);
        subject.handle(handleContext);
        assertEquals(true, writableStore.get(updateAccountId).declineReward());
        // decline reward not set will not change values to false
        final var noReward = new CryptoUpdateBuilder().build();
        givenTxnWith(noReward);
        subject.handle(handleContext);
        assertEquals(true, writableStore.get(updateAccountId).declineReward());
    }

    @Test
    void updatesReceiverSigReqIfPresent() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));
        // receiverSigReq set to false will change values to false
        final var falseFlag =
                new CryptoUpdateBuilder().withReceiverSigReqWrapper(false).build();
        givenTxnWith(falseFlag);
        assertEquals(true, writableStore.get(updateAccountId).receiverSigRequired());

        subject.handle(handleContext);
        assertEquals(false, writableStore.get(updateAccountId).receiverSigRequired());

        // receiverSigReq set to true
        final var trueFlag =
                new CryptoUpdateBuilder().withReceiverSigReqWrapper(true).build();
        givenTxnWith(trueFlag);
        subject.handle(handleContext);
        assertEquals(true, writableStore.get(updateAccountId).receiverSigRequired());

        // receiverSigReq not set will not change values to false
        final var noFlag = new CryptoUpdateBuilder().build();
        givenTxnWith(noFlag);
        subject.handle(handleContext);
        assertEquals(true, writableStore.get(updateAccountId).receiverSigRequired());
    }

    @Test
    void updatesReceiverSigReqIfTrueInLegacy() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var flag =
                new CryptoUpdateBuilder().withReceiverSigReqWrapper(false).build();
        givenTxnWith(flag);
        // initially account has receiverSigRequired = true
        assertEquals(true, writableStore.get(updateAccountId).receiverSigRequired());

        // change it to false using wrapper
        subject.handle(handleContext);
        assertEquals(false, writableStore.get(updateAccountId).receiverSigRequired());

        final var flag1 = new CryptoUpdateBuilder().withReceiverSigReq(true).build();
        givenTxnWith(flag1);
        // change it to true using legacy field
        subject.handle(handleContext);
        assertEquals(true, writableStore.get(updateAccountId).receiverSigRequired());
    }

    @Test
    void updatesExpiry() {
        final var txn = new CryptoUpdateBuilder().withExpiration(1234600L).build();
        givenTxnWith(txn);
        // initially account has 1234567L expiration
        assertEquals(1234567L, writableStore.get(updateAccountId).expiry());

        // change it to given number
        subject.handle(handleContext);
        assertEquals(1234600L, writableStore.get(updateAccountId).expiry());
    }

    @Test
    void updatesMaxAutomaticAssociations() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var txn = new CryptoUpdateBuilder().withMaxAutoAssociations(100).build();
        givenTxnWith(txn);
        // initially account has 10 auto association slots
        assertEquals(10, writableStore.get(updateAccountId).maxAutoAssociations());

        // change it to given number
        subject.handle(handleContext);
        assertEquals(100, writableStore.get(updateAccountId).maxAutoAssociations());
    }

    @Test
    void maxAutoAssociationsLessThanExistingFails() {
        final var txn = new CryptoUpdateBuilder().withMaxAutoAssociations(1).build();
        givenTxnWith(txn);
        // initially account has 10 auto association slots and 2 are used
        assertEquals(2, writableStore.get(updateAccountId).usedAutoAssociations());
        assertEquals(10, writableStore.get(updateAccountId).maxAutoAssociations());

        // changing to less than 2 slots will fail
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT));
        assertEquals(10, writableStore.get(updateAccountId).maxAutoAssociations());
    }

    @Test
    void maxAutoAssociationUpdateToMoreThanTokenAssociationLimitFails() {
        final var txn = new CryptoUpdateBuilder().withMaxAutoAssociations(12).build();
        givenTxnWith(txn);

        final var config = new HederaTestConfigBuilder()
                .withValue("entities.limitTokenAssociations", true)
                .withValue("tokens.maxPerAccount", 11)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        // changing to less than 2 slots will fail
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT));
        assertEquals(10, writableStore.get(updateAccountId).maxAutoAssociations());
    }

    @Test
    void updateMaxAutomaticAssociationsFailAsExpectedWithMoreThanMaxAutoAssociations() {
        final var txn = new CryptoUpdateBuilder().withMaxAutoAssociations(12).build();
        givenTxnWith(txn);

        final var config = new HederaTestConfigBuilder()
                .withValue("ledger.maxAutoAssociations", 11)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        // changing to less than 2 slots will fail
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT));
        assertEquals(10, writableStore.get(updateAccountId).maxAutoAssociations());
    }

    @Test
    void updatesMemo() {
        given(dynamicProperties.maxMemoUtf8Bytes()).willReturn(1000);
        final var txn = new CryptoUpdateBuilder().withMemo("test memo").build();
        givenTxnWith(txn);
        assertEquals("testAccount", writableStore.get(updateAccountId).memo());

        subject.handle(handleContext);
        assertEquals("test memo", writableStore.get(updateAccountId).memo());
    }

    @Test
    void updatesAutoRenewPeriod() {
        given(dynamicProperties.maxAutoRenewDuration()).willReturn(100000000L);

        final var txn = new CryptoUpdateBuilder().withAutoRenewPeriod(2000000L).build();
        givenTxnWith(txn);
        assertEquals(72000L, writableStore.get(updateAccountId).autoRenewSecs());

        subject.handle(handleContext);
        assertEquals(2000000L, writableStore.get(updateAccountId).autoRenewSecs());
    }

    @Test
    void updatesKey() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));

        final var txn = new CryptoUpdateBuilder().withKey(key).build();
        givenTxnWith(txn);
        assertEquals(otherKey, writableStore.get(updateAccountId).key());

        subject.handle(handleContext);
        assertEquals(key, writableStore.get(updateAccountId).key());
    }

    @Test
    void rejectsKeyWithBadEncoding() {
        final var txn = new CryptoUpdateBuilder().withKey(defaultkey()).build();
        givenTxnWith(txn);
        assertEquals(otherKey, writableStore.get(updateAccountId).key());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BAD_ENCODING));
        assertEquals(otherKey, writableStore.get(updateAccountId).key());
    }

    @Test
    void rejectsInvalidKey() {
        final var txn = new CryptoUpdateBuilder().withKey(emptyKey()).build();
        givenTxnWith(txn);
        assertEquals(otherKey, writableStore.get(updateAccountId).key());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BAD_ENCODING));
        assertEquals(otherKey, writableStore.get(updateAccountId).key());
    }

    @Test
    void rejectsInvalidMemo() {
        final var txn = new CryptoUpdateBuilder()
                .withMemo("some long memo that is too long")
                .build();
        givenTxnWith(txn);

        final var config = new HederaTestConfigBuilder()
                .withValue("ledger.maxMemoUtf8Bytes", 2)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        assertEquals("testAccount", writableStore.get(updateAccountId).memo());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MEMO_TOO_LONG));
        assertEquals("testAccount", writableStore.get(updateAccountId).memo());
    }

    @Test
    void rejectsInvalidAutoRenewPeriod() {
        final var txn = new CryptoUpdateBuilder().withAutoRenewPeriod(10L).build();
        givenTxnWith(txn);

        assertEquals(72000L, writableStore.get(updateAccountId).autoRenewSecs());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @Test
    void rejectsDetachedAccount() {
        updateWritableAccountStore(Map.of(
                updateAccountId.accountNum(),
                updateAccount
                        .copyBuilder()
                        .expiredAndPendingRemoval(true)
                        .tinybarBalance(0)
                        .expiry(0)
                        .build(),
                accountNum,
                account));

        final var txn = new CryptoUpdateBuilder().build();
        givenTxnWith(txn);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void rejectsInvalidExpiryIfExpiryValidatorFails() {
        final var txn = new CryptoUpdateBuilder().withExpiration(1234567).build();
        givenTxnWith(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void permitsDetachedIfExtendingExpiry() {
        updateWritableAccountStore(Map.of(
                updateAccountId.accountNum(),
                updateAccount
                        .copyBuilder()
                        .expiredAndPendingRemoval(true)
                        .tinybarBalance(0)
                        .build(),
                accountNum,
                account));

        final var txn = new CryptoUpdateBuilder().withExpiration(1234600L).build();
        givenTxnWith(txn);
        assertEquals(1234567L, writableStore.get(updateAccountId).expiry());

        subject.handle(handleContext);
        assertEquals(1234600L, writableStore.get(updateAccountId).expiry());
    }

    @Test
    void rejectsExpiryReduction() {
        final var txn = new CryptoUpdateBuilder().withExpiration(10L).build();
        givenTxnWith(txn);
        assertEquals(1234567L, writableStore.get(updateAccountId).expiry());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(EXPIRATION_REDUCTION_NOT_ALLOWED));
    }

    @Test
    void rejectsUpdatingSmartContract() {
        updateWritableAccountStore(Map.of(
                updateAccountId.accountNum(),
                updateAccount.copyBuilder().smartContract(true).build(),
                accountNum,
                account));

        final var txn = new CryptoUpdateBuilder().build();
        givenTxnWith(txn);

        assertTrue(writableStore.get(updateAccountId).smartContract());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
    }

    @Test
    void accountMissingFails() {
        final var txn = new CryptoUpdateBuilder()
                .withTarget(AccountID.newBuilder().accountNum(10).build())
                .build();
        givenTxnWith(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
    }

    @Test
    void failsIfAccountDeleted() {
        updateWritableAccountStore(Map.of(
                updateAccountId.accountNum(),
                updateAccount.copyBuilder().deleted(true).build(),
                accountNum,
                account));

        final var txn = new CryptoUpdateBuilder().build();
        givenTxnWith(txn);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_DELETED));
    }

    /**
     * A builder for {@link TransactionBody} instances.
     */
    private class CryptoUpdateBuilder {
        private AccountID payer = id;
        private Long autoRenewPeriod;
        private Boolean receiverSigReqWrapper;
        private boolean receiverSigReq;
        private Boolean declineReward;
        private AccountID proxyAccountId;
        private Long stakeNodeId;
        private Long stakedAccountId;
        private Long expiry;
        private Integer maxAutoAssociations;
        private String memo;
        private Key opKey;
        private AccountID target = updateAccountId;

        public CryptoUpdateBuilder() {}

        public TransactionBody build() {
            final var transactionID =
                    TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
            final var builder = CryptoUpdateTransactionBody.newBuilder().accountIDToUpdate(target);
            if (opKey != null) {
                /* Note that {@code this.validateSemantics} will have rejected any txn with an invalid key. */
                builder.key(opKey);
            }
            if (expiry != null) {
                builder.expirationTime(Timestamp.newBuilder().seconds(expiry));
            }
            if (receiverSigReqWrapper != null) {
                builder.receiverSigRequiredWrapper(receiverSigReqWrapper.booleanValue());
            } else if (receiverSigReq) {
                builder.receiverSigRequired(true);
            }

            if (autoRenewPeriod != null) {
                builder.autoRenewPeriod(
                        Duration.newBuilder().seconds(autoRenewPeriod).build());
            }
            if (proxyAccountId != null) {
                builder.proxyAccountID(proxyAccountId);
            }
            if (memo != null) {
                builder.memo(memo);
            }
            if (maxAutoAssociations != null) {
                builder.maxAutomaticTokenAssociations(maxAutoAssociations);
            }
            if (declineReward != null) {
                builder.declineReward(declineReward.booleanValue());
            }
            if (stakedAccountId != null) {
                builder.stakedAccountId(AccountID.newBuilder()
                        .accountNum(stakedAccountId.longValue())
                        .build());
            } else if (stakeNodeId != null) {
                builder.stakedNodeId(stakeNodeId.longValue());
            }

            if (opKey != null) {
                builder.key(opKey);
            }

            return TransactionBody.newBuilder()
                    .transactionID(transactionID)
                    .cryptoUpdateAccount(builder.build())
                    .build();
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withTarget(final AccountID target) {
            this.target = target;
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withAutoRenewPeriod(final long autoRenewPeriod) {
            this.autoRenewPeriod = autoRenewPeriod;
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withProxyAccountNum(final long proxyAccountNum) {
            this.proxyAccountId =
                    AccountID.newBuilder().accountNum(proxyAccountNum).build();
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withNoAutoRenewPeriod() {
            this.autoRenewPeriod = -1L;
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withStakedAccountId(final long id) {
            this.stakedAccountId = id;
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withStakedNodeId(final long id) {
            this.stakeNodeId = id;
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withReceiverSigReqWrapper(final boolean receiverSigReq) {
            this.receiverSigReqWrapper = Boolean.valueOf(receiverSigReq);
            ;
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withReceiverSigReq(final boolean receiverSigReq) {
            this.receiverSigReq = receiverSigReq;
            return this;
        }

        public CryptoUpdateHandlerTest.CryptoUpdateBuilder withDeclineReward(final boolean declineReward) {
            this.declineReward = Boolean.valueOf(declineReward);
            return this;
        }

        public CryptoUpdateBuilder withExpiration(long expiration) {
            this.expiry = expiration;
            return this;
        }

        public CryptoUpdateBuilder withMaxAutoAssociations(int maxAutoAssociations) {
            this.maxAutoAssociations = maxAutoAssociations;
            return this;
        }

        public CryptoUpdateBuilder withMemo(String testMemo) {
            this.memo = testMemo;
            return this;
        }

        public CryptoUpdateBuilder withKey(Key key) {
            this.opKey = key;
            return this;
        }
    }

    private void updateReadableAccountStore(Map<Long, Account> accountsToAdd) {
        final var emptyStateBuilder = emptyReadableAccountStateBuilder();
        for (final var entry : accountsToAdd.entrySet()) {
            emptyStateBuilder.value(accountID(entry.getKey()), entry.getValue());
        }
        readableAccounts = emptyStateBuilder.build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates);
    }

    private void updateWritableAccountStore(Map<Long, Account> accountsToAdd) {
        final var emptyStateBuilder = emptyWritableAccountStateBuilder();
        for (final var entry : accountsToAdd.entrySet()) {
            emptyStateBuilder.value(accountID(entry.getKey()), entry.getValue());
        }
        writableAccounts = emptyStateBuilder.build();
        given(writableStates.<AccountID, Account>get(ACCOUNTS)).willReturn(writableAccounts);
        writableStore = new WritableAccountStore(writableStates);
    }

    private void givenTxnWith(TransactionBody txn) {
        given(handleContext.body()).willReturn(txn);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.writableStore(WritableAccountStore.class)).willReturn(writableStore);
        given(handleContext.configuration()).willReturn(configuration);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(configuration, 1));
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(consensusSecondNow.getAsLong()).willReturn(1_234_567L);
    }

    private Key defaultkey() {
        return Key.DEFAULT;
    }

    private Key emptyKey() {
        return Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder().keys(KeyList.DEFAULT).threshold(0))
                .build();
    }
}
