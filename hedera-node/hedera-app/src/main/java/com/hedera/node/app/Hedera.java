/*
 * Copyright (C) 2022-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.node.app.service.contract.impl.ContractServiceImpl.CONTRACT_SERVICE;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.ACCOUNTS;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.CONTRACT_STORAGE;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.NETWORK_CTX;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.PAYER_RECORDS_OR_CONSOLIDATED_FCQ;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.RECORD_STREAM_RUNNING_HASH;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.SCHEDULE_TXS;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.STAKING_INFO;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.STORAGE;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.TOKENS;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.TOKEN_ASSOCIATIONS;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.TOPICS;
import static com.hedera.node.app.service.mono.state.migration.StateChildIndices.UNIQUE_TOKENS;
import static com.hedera.node.app.state.merkle.MerkleSchemaRegistry.isSoOrdered;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.FRONTEND_THROTTLE;
import static com.hedera.node.app.util.HederaAsciiArt.HEDERA;
import static com.swirlds.platform.system.InitTrigger.EVENT_STREAM_RECOVERY;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static com.swirlds.platform.system.InitTrigger.RECONNECT;
import static com.swirlds.platform.system.InitTrigger.RESTART;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.fees.congestion.EntityUtilizationMultiplier;
import com.hedera.node.app.fees.congestion.ThrottleMultiplier;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.info.CurrentPlatformStatusImpl;
import com.hedera.node.app.info.NetworkInfoImpl;
import com.hedera.node.app.info.SelfNodeInfoImpl;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.state.InitialModServiceContractSchema;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.mono.context.properties.BootstrapProperties;
import com.hedera.node.app.service.mono.context.properties.SerializableSemVers;
import com.hedera.node.app.service.mono.state.adapters.VirtualMapLike;
import com.hedera.node.app.service.mono.state.merkle.MerkleNetworkContext;
import com.hedera.node.app.service.mono.state.merkle.MerkleScheduledTransactions;
import com.hedera.node.app.service.mono.state.merkle.MerkleStakingInfo;
import com.hedera.node.app.service.mono.state.merkle.MerkleToken;
import com.hedera.node.app.service.mono.state.merkle.MerkleTopic;
import com.hedera.node.app.service.mono.state.migration.ContractStateMigrator;
import com.hedera.node.app.service.mono.state.submerkle.ExpirableTxnRecord;
import com.hedera.node.app.service.mono.state.virtual.ContractKey;
import com.hedera.node.app.service.mono.state.virtual.EntityNumVirtualKey;
import com.hedera.node.app.service.mono.state.virtual.IterableContractValue;
import com.hedera.node.app.service.mono.state.virtual.UniqueTokenKey;
import com.hedera.node.app.service.mono.state.virtual.UniqueTokenValue;
import com.hedera.node.app.service.mono.state.virtual.entities.OnDiskAccount;
import com.hedera.node.app.service.mono.state.virtual.entities.OnDiskTokenRel;
import com.hedera.node.app.service.mono.stream.RecordsRunningHashLeaf;
import com.hedera.node.app.service.mono.utils.EntityNum;
import com.hedera.node.app.service.mono.utils.NamedDigestFactory;
import com.hedera.node.app.service.mono.utils.NonAtomicReference;
import com.hedera.node.app.service.networkadmin.impl.FreezeServiceImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.SyntheticRecordsGenerator;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.services.ServicesRegistryImpl;
import com.hedera.node.app.spi.HapiUtils;
import com.hedera.node.app.spi.state.StateDefinition;
import com.hedera.node.app.spi.state.WritableKVState;
import com.hedera.node.app.spi.state.WritableKVStateBase;
import com.hedera.node.app.spi.state.WritableSingletonStateBase;
import com.hedera.node.app.spi.workflows.record.GenesisRecordsBuilder;
import com.hedera.node.app.state.HederaLifecyclesImpl;
import com.hedera.node.app.state.HederaState;
import com.hedera.node.app.state.merkle.MerkleHederaState;
import com.hedera.node.app.state.merkle.StateMetadata;
import com.hedera.node.app.state.merkle.memory.InMemoryKey;
import com.hedera.node.app.state.merkle.memory.InMemoryValue;
import com.hedera.node.app.state.merkle.memory.InMemoryWritableKVState;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.node.app.throttle.SynchronizedThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleManager;
import com.hedera.node.app.throttle.impl.NetworkUtilizationManagerImpl;
import com.hedera.node.app.version.HederaSoftwareVersion;
import com.hedera.node.app.workflows.dispatcher.ReadableStoreFactory;
import com.hedera.node.app.workflows.handle.SystemFileUpdateFacility;
import com.hedera.node.app.workflows.handle.record.GenesisRecordsConsensusHook;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.Utils;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.VersionConfig;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.CryptographyHolder;
import com.swirlds.common.platform.NodeId;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.state.PlatformState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.system.SwirldState;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.events.Event;
import com.swirlds.platform.system.status.PlatformStatus;
import com.swirlds.platform.system.transaction.Transaction;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/*
 ****************        ****************************************************************************************
 ************                ************                                                                       *
 *********                      *********                                                                       *
 ******                            ******                                                                       *
 ****                                ****      ___           ___           ___           ___           ___      *
 ***        ĦĦĦĦ          ĦĦĦĦ        ***     /\  \         /\  \         /\  \         /\  \         /\  \     *
 **         ĦĦĦĦ          ĦĦĦĦ         **    /::\  \       /::\  \       /::\  \       /::\  \       /::\  \    *
 *          ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ          *   /:/\:\  \     /:/\:\  \     /:/\:\  \     /:/\:\  \     /:/\:\  \   *
            ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ             /::\~\:\  \   /:/  \:\__\   /::\~\:\  \   /::\~\:\  \   /::\~\:\  \  *
            ĦĦĦĦ          ĦĦĦĦ            /:/\:\ \:\__\ /:/__/ \:|__| /:/\:\ \:\__\ /:/\:\ \:\__\ /:/\:\ \:\__\ *
            ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ            \:\~\:\ \/__/ \:\  \ /:/  / \:\~\:\ \/__/ \/_|::\/:/  / \/__\:\/:/  / *
 *          ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ          *  \:\ \:\__\    \:\  /:/  /   \:\ \:\__\      |:|::/  /       \::/  /  *
 **         ĦĦĦĦ          ĦĦĦĦ         **   \:\ \/__/     \:\/:/  /     \:\ \/__/      |:|\/__/        /:/  /   *
 ***        ĦĦĦĦ          ĦĦĦĦ        ***    \:\__\        \::/__/       \:\__\        |:|  |         /:/  /    *
 ****                                ****     \/__/         ~~            \/__/         \|__|         \/__/     *
 ******                            ******                                                                       *
 *********                      *********                                                                       *
 ************                ************                                                                       *
 ****************        ****************************************************************************************
*/

/**
 * Represents the Hedera Consensus Node.
 *
 * <p>This is the main entry point for the Hedera Consensus Node. It contains initialization logic for the
 * node, including its state. It constructs some artifacts for gluing the mono-service with the modular service
 * infrastructure. It constructs the Dagger dependency tree, and manages the gRPC server, and in all other ways,
 * controls execution of the node. If you want to understand our system, this is a great place to start!
 */
public final class Hedera implements SwirldMain {
    private static final Logger logger = LogManager.getLogger(Hedera.class);
    // FUTURE: This should come from configuration, not be hardcoded.
    public static final int MAX_SIGNED_TXN_SIZE = 6144;
    /**
     * The registry of all known services
     */
    private final ServicesRegistryImpl servicesRegistry;
    /**
     * The current version of THIS software
     */
    private final HederaSoftwareVersion version;
    /**
     * The configuration at the time of bootstrapping the node
     */
    private final ConfigProvider bootstrapConfigProvider;
    /**
     * The Hashgraph Platform. This is set during state initialization.
     */
    private Platform platform;
    /**
     * The configuration for this node
     */
    private ConfigProviderImpl configProvider;
    /**
     * The throttle manager for parsing the throttle definition file
     */
    private ThrottleManager throttleManager;
    /**
     * The exchange rate manager
     */
    private ExchangeRateManager exchangeRateManager;
    /**
     * The fee manager
     */
    private FeeManager feeManager;
    /** The class responsible for remembering objects created in genesis cases */
    private final GenesisRecordsBuilder genesisRecordsBuilder;
    /**
     * Dependencies managed by Dagger. Set during state initialization. The mono-service requires this object, but none
     * of the rest of the system (and particularly the modular implementation) uses it directly. Rather, it is created
     * and used to initialize the system, and more concrete dependencies are used from there.
     */
    private HederaInjectionComponent daggerApp;
    /**
     * Indicates whether the platform is active
     */
    private PlatformStatus platformStatus = PlatformStatus.STARTING_UP;

    private ThrottleAccumulator backendThrottle;
    private ThrottleAccumulator frontendThrottle;
    private CongestionMultipliers congestionMultipliers;
    private final SyntheticRecordsGenerator recordsGenerator;

    /**
     * The application name from the platform's perspective. This is currently locked in at the old main class name and
     * requires data migration to change.
     */
    public static final String APP_NAME = "com.hedera.services.ServicesMain";

    /**
     * The swirld name. Currently, there is only one swirld.
     */
    public static final String SWIRLD_NAME = "123";

    private static final IntSupplier SUPPLY_ONE = () -> 1;

    private static EntityIdService ENTITY_SERVICE;
    private static ConsensusServiceImpl CONSENSUS_SERVICE;
    private static FileServiceImpl FILE_SERVICE;
    private static ScheduleServiceImpl SCHEDULE_SERVICE;
    private static TokenServiceImpl TOKEN_SERVICE;
    private static RecordCacheService RECORD_SERVICE;
    private static BlockRecordService BLOCK_SERVICE;
    private static FeeService FEE_SERVICE;

    /*==================================================================================================================
    *
    * Hedera Object Construction.
    *
    =================================================================================================================*/

    /**
     * Create a new Hedera instance.
     *
     * @param constructableRegistry The registry to use during the deserialization process
     */
    public Hedera(@NonNull final ConstructableRegistry constructableRegistry) {
        requireNonNull(constructableRegistry);

        // Print welcome message
        logger.info(
                "\n{}\n\nWelcome to Hedera! Developed with ❤\uFE0F by the Open Source Community. "
                        + "https://github.com/hashgraph/hedera-services\n",
                HEDERA);

        // Load the bootstrap configuration. These config values are NOT stored in state, so we don't need to have
        // state up and running for getting their values. We use this bootstrap config only in this constructor.
        this.bootstrapConfigProvider = new BootstrapConfigProviderImpl();
        final var bootstrapConfig = bootstrapConfigProvider.getConfiguration();

        // Let the user know which mode they are starting in (DEV vs. TEST vs. PROD).
        // NOTE: This bootstrapConfig is not entirely satisfactory. We probably need an alternative...
        final var hederaConfig = bootstrapConfig.getConfigData(HederaConfig.class);
        final var activeProfile = hederaConfig.activeProfile();
        logger.info("Starting in {} mode", activeProfile);

        // Read the software version. In addition to logging, we will use this software version to determine whether
        // we need to migrate the state to a newer release, and to determine which schemas to execute.
        logger.debug("Loading Software Version");
        final var versionConfig = bootstrapConfig.getConfigData(VersionConfig.class);
        version = new HederaSoftwareVersion(versionConfig.hapiVersion(), versionConfig.servicesVersion());
        logger.info(
                "Creating Hedera Consensus Node {} with HAPI {}",
                () -> HapiUtils.toString(version.getServicesVersion()),
                () -> HapiUtils.toString(version.getHapiVersion()));

        // Create a records generator for any synthetic records that need to be CREATED
        this.recordsGenerator = new SyntheticRecordsGenerator();
        // Create a records builder for any genesis records that need to be RECORDED
        this.genesisRecordsBuilder = new GenesisRecordsConsensusHook();

        // Create all the service implementations
        logger.info("Registering services");

        ENTITY_SERVICE = new EntityIdService();
        CONSENSUS_SERVICE = new ConsensusServiceImpl();
        FILE_SERVICE = new FileServiceImpl(bootstrapConfigProvider);
        SCHEDULE_SERVICE = new ScheduleServiceImpl();
        TOKEN_SERVICE = new TokenServiceImpl(
                recordsGenerator::sysAcctRecords,
                recordsGenerator::stakingAcctRecords,
                recordsGenerator::treasuryAcctRecords,
                recordsGenerator::multiUseAcctRecords,
                recordsGenerator::blocklistAcctRecords);
        RECORD_SERVICE = new RecordCacheService();
        BLOCK_SERVICE = new BlockRecordService();
        FEE_SERVICE = new FeeService();

        // FUTURE: Use the service loader framework to load these services!
        this.servicesRegistry = new ServicesRegistryImpl(constructableRegistry, genesisRecordsBuilder);
        Set.of(
                        ENTITY_SERVICE,
                        CONSENSUS_SERVICE,
                        CONTRACT_SERVICE,
                        FILE_SERVICE,
                        new FreezeServiceImpl(),
                        SCHEDULE_SERVICE,
                        TOKEN_SERVICE,
                        new UtilServiceImpl(),
                        RECORD_SERVICE,
                        BLOCK_SERVICE,
                        FEE_SERVICE,
                        new CongestionThrottleService())
                .forEach(service -> servicesRegistry.register(service, version));

        // Register MerkleHederaState with the ConstructableRegistry, so we can use a constructor OTHER THAN the default
        // constructor to make sure it has the config and other info it needs to be created correctly.
        try {
            logger.debug("Register MerkleHederaState with ConstructableRegistry");
            constructableRegistry.registerConstructable(
                    new ClassConstructorPair(MerkleHederaState.class, this::newState));
        } catch (final ConstructableRegistryException e) {
            logger.error("Failed to register MerkleHederaState with ConstructableRegistry", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the port the gRPC server is listening on, or {@code -1} if there is no server listening.
     */
    public int getGrpcPort() {
        return daggerApp.grpcServerManager().port();
    }

    /**
     * Indicates whether this node is UP and ready for business.
     *
     * @return True if the platform is active and the gRPC server is running.
     */
    public boolean isActive() {
        return platformStatus == PlatformStatus.ACTIVE
                && daggerApp.grpcServerManager().isRunning();
    }

    /**
     * Get the current platform status
     * @return current platform status
     */
    public PlatformStatus getPlatformStatus() {
        return platformStatus;
    }

    /**
     * Indicates whether this node is FROZEN.
     * @return True if the platform is frozen
     */
    public boolean isFrozen() {
        return platformStatus == PlatformStatus.FREEZE_COMPLETE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called immediately after the constructor to get the version of this software. In an upgrade scenario, this
     * version will be greater than the one in the saved state.
     *
     * @return The software version.
     */
    @Override
    @NonNull
    public SoftwareVersion getSoftwareVersion() {
        return version;
    }

    /*==================================================================================================================
    *
    * Initialization Step 1: Create a new state (either genesis or restart, once per node).
    *
    =================================================================================================================*/

    /**
     * {@inheritDoc}
     *
     * <p>Called by the platform <b>ONLY</b> during genesis (that is, if there is no saved state). However, it is also
     * called indirectly by {@link ConstructableRegistry} due to registration in this class' constructor.
     *
     * @return A new {@link SwirldState} instance.
     */
    @Override
    @NonNull
    public SwirldState newState() {
        return new MerkleHederaState(new HederaLifecyclesImpl(this));
    }

    /*==================================================================================================================
    *
    * Initialization Step 2: Initialize the state. Either genesis or restart or reconnect or some other trigger.
    * Includes migration when needed.
    *
    =================================================================================================================*/

    /**
     * Invoked by the platform when the state should be initialized. This happens <b>BEFORE</b>
     * {@link #init(Platform, NodeId)} and after {@link #newState()}.
     */
    @SuppressWarnings("java:S1181") // catching Throwable instead of Exception when we do a direct System.exit()
    public void onStateInitialized(
            @NonNull final MerkleHederaState state,
            @NonNull final Platform platform,
            @NonNull final PlatformState platformState,
            @NonNull final InitTrigger trigger,
            @Nullable final SoftwareVersion previousVersion) {
        // Initialize the configuration from disk. We must do this BEFORE we run migration, because the various
        // migration methods may depend on configuration to do their work. For example, the token service migration code
        // needs to know the token treasury account, which has an account ID specified in config. The initial config
        // file in state, created by the file service migration, will match what we have here, so we don't have to worry
        // about re-loading config after migration.
        logger.info("Initializing configuration with trigger {}", trigger);
        configProvider = new ConfigProviderImpl(trigger == GENESIS);
        logConfiguration();

        // Determine if we need to create synthetic records for system entities
        final var blockRecordState = state.getReadableStates(BlockRecordService.NAME);
        boolean createSynthRecords = false;
        if (!blockRecordState.isEmpty()) {
            final var blockInfo = blockRecordState
                    .<BlockInfo>getSingleton(BlockRecordService.BLOCK_INFO_STATE_KEY)
                    .get();
            if (blockInfo == null || blockInfo.consTimeOfLastHandledTxn() == null) {
                createSynthRecords = true;
            }
        } else {
            createSynthRecords = true;
        }
        if (createSynthRecords) {
            recordsGenerator.createRecords(configProvider.getConfiguration(), genesisRecordsBuilder);
        }

        System.out.println("BBM: migration beginning 😅...");

        // --------------------- BEGIN MONO -> MODULAR MIGRATION ---------------------

        // --------------------- UNIQUE_TOKENS (0)
        VirtualMap<UniqueTokenKey, UniqueTokenValue> a = state.getChild(UNIQUE_TOKENS);
        if (a != null) {
            TOKEN_SERVICE.setNftsFromState(a);
        }

        // --------------------- TOKEN_ASSOCIATIONS (1)
        VirtualMap<EntityNumVirtualKey, OnDiskTokenRel> b = state.getChild(TOKEN_ASSOCIATIONS);
        if (b != null) {
            TOKEN_SERVICE.setTokenRelsFromState(b);
        }

        // --------------------- TOPICS (2)
        MerkleMap<EntityNum, MerkleTopic> c = state.getChild(TOPICS);
        if (c != null) {
            CONSENSUS_SERVICE.setFromState(c);
        }

        // --------------------- STORAGE (3)     // only "non-special" files
        // There's no 'd' variable here because we don't want this reference to state hanging around
        // because it seems to contribute to the node hanging. I could be wrong though; can revisit
        // if needed
        if (state.getChild(STORAGE) != null) {
            FILE_SERVICE.setFs(() -> VirtualMapLike.from(state.getChild(STORAGE)));
        }
        // Note: some files have no metadata; these are contract bytecode files

        // --------------------- ACCOUNTS (4)
        VirtualMap<EntityNumVirtualKey, OnDiskAccount> e = state.getChild(ACCOUNTS);
        if (e != null) {
            TOKEN_SERVICE.setAcctsFromState(e);
        }

        // --------------------- TOKENS (5)
        MerkleMap<EntityNum, MerkleToken> f = state.getChild(TOKENS);
        if (f != null) {
            TOKEN_SERVICE.setTokensFromState(f);
        }

        // --------------------- NETWORK_CTX (6)
        MerkleNetworkContext fromNetworkContext = state.getChild(NETWORK_CTX);
        // ??? the translator is using firstConsTimeOfLastBlock instead of CURRENTBlock...is that ok???
        // firstConsTimeOfCurrentBlock – needed in blockInfo

        // --------------------- SPECIAL_FILES (7)
        // upgrade_file_key is no longer useful; don't migrate
        // MerkleSpecialFiles h = state.getChild(SPECIAL_FILES);

        // --------------------- SCHEDULE_TXS (8)
        MerkleScheduledTransactions i = state.getChild(SCHEDULE_TXS);
        if (i != null) {
            SCHEDULE_SERVICE.setFs(i);
        }

        // --------------------- RECORD_STREAM_RUNNING_HASH (9)
        // From MerkleNetworkContext: blockNo, blockHashes
        RecordsRunningHashLeaf j = state.getChild(RECORD_STREAM_RUNNING_HASH);
        if (j != null) {
            BLOCK_SERVICE.setFs(j, fromNetworkContext);
        }

        // --------------------- LEGACY_ADDRESS_BOOK (10)
        // Not using anywhere; won't be migrated
        // AddressBook k = state.getChild(LEGACY_ADDRESS_BOOK);

        // --------------------- CONTRACT_STORAGE (11)
        VirtualMap<ContractKey, IterableContractValue> l = state.getChild(CONTRACT_STORAGE);
        if (l != null) {
            final var fromStore = VirtualMapLike.from(l);
            final var expectedNumberOfSlots = Math.toIntExact(fromStore.size());

            // Start the migration with a clean, writable KV store.  Using the in-memory store here.

            final var contractSchema = new InitialModServiceContractSchema(version.getServicesVersion());
            final var contractSchemas = contractSchema.statesToCreate();
            final StateDefinition<SlotKey, SlotValue> contractStoreStateDefinition = contractSchemas.stream()
                    .filter(sd -> sd.stateKey().equals(InitialModServiceContractSchema.STORAGE_KEY))
                    .findFirst()
                    .orElseThrow();
            final var contractStoreSchemaMetadata =
                    new StateMetadata<>(ContractService.NAME, contractSchema, contractStoreStateDefinition);
            final var contractMerkleMap =
                    new NonAtomicReference<MerkleMap<InMemoryKey<SlotKey>, InMemoryValue<SlotKey, SlotValue>>>(
                            new MerkleMap<>(expectedNumberOfSlots));
            final var toStore = new NonAtomicReference<WritableKVState<SlotKey, SlotValue>>(
                    new InMemoryWritableKVState<>(contractStoreSchemaMetadata, contractMerkleMap.get()));
            var flusher = new ContractStateMigrator.StateFlusher() {
                @Override
                public WritableKVState<SlotKey, SlotValue> apply(
                        WritableKVState<SlotKey, SlotValue> slotKeySlotValueWritableKVState) {
                    // Commit all the new leafs to the underlying map
                    ((WritableKVStateBase<SlotKey, SlotValue>) (slotKeySlotValueWritableKVState)).commit();
                    // Copy the underlying map, which does the flush
                    contractMerkleMap.set(contractMerkleMap.get().copy());

                    // Create a new store to go on with
                    InMemoryWritableKVState<SlotKey, SlotValue> finishedStore =
                            new InMemoryWritableKVState<>(contractStoreSchemaMetadata, contractMerkleMap.get());
                    toStore.set(finishedStore);

                    return finishedStore;
                }
            };
            // For contract storage migration:
            CONTRACT_SERVICE.setFromState(fromStore);
            CONTRACT_SERVICE.setToState(toStore.get());
            CONTRACT_SERVICE.setFlusher(flusher);

            // For contract bytecode migration:
            CONTRACT_SERVICE.setFileFs(() -> VirtualMapLike.from(state.getChild(STORAGE)));
        }

        // --------------------- STAKING_INFO (12)
        MerkleMap<EntityNum, MerkleStakingInfo> m = state.getChild(STAKING_INFO);
        if (m != null) {
            TOKEN_SERVICE.setStakingFs(m, fromNetworkContext);
        }

        // --------------------- PAYER_RECORDS_OR_CONSOLIDATED_FCQ (13)
        FCQueue<ExpirableTxnRecord> n = state.getChild(PAYER_RECORDS_OR_CONSOLIDATED_FCQ);
        if (n != null) {
            RECORD_SERVICE.setFromState(new ArrayList<>(n));
        }

        // --------------------- Midnight Rates (separate service in modular code - fee service)
        if (fromNetworkContext != null) {
            FEE_SERVICE.setFs(fromNetworkContext.getMidnightRates());
        }

        // --------------------- Sequence Number (separate service in modular code - entity ID service)
        if (fromNetworkContext != null) {
            ENTITY_SERVICE.setFs(fromNetworkContext.seqNo().current());
        }

        // --------------------- END OF MONO -> MODULAR MIGRATION ---------------------

        // We do nothing for EVENT_STREAM_RECOVERY. This is a special case that is handled by the platform.
        if (trigger == EVENT_STREAM_RECOVERY) {
            logger.debug("Skipping state initialization for trigger {}", trigger);
            return;
        }

        //noinspection ConstantValue
        assert platformState != null : "Platform should never pass a null platform state";
        logger.info(
                "Initializing Hedera state with trigger {} and previous version {}",
                () -> trigger,
                () -> previousVersion == null ? "<NONE>" : previousVersion);

        // We do not support downgrading from one version to an older version.
        final var deserializedVersion = (HederaSoftwareVersion) previousVersion;
        if (isDowngrade(version, deserializedVersion)) {
            logger.fatal(
                    "Fatal error, state source version {} is higher than node software version {}",
                    deserializedVersion,
                    version);
            System.exit(1);
        }

        // This is the *FIRST* time in the initialization sequence that we have access to the platform. Grab it!
        // This instance should never change on us, once it has been set
        assert this.platform == null || this.platform == platform : "Platform should never change once set";
        this.platform = platform;

        // Different paths for different triggers. Every trigger should be handled here. If a new trigger is added,
        // since there is no 'default' case, it will cause a compile error, so you will know you have to deal with it
        // here. This is intentional so as to avoid forgetting to handle a new trigger.
        try {
            switch (trigger) {
                case GENESIS -> genesis(state);
                case RESTART -> restart(state, deserializedVersion);
                case RECONNECT -> reconnect(state, deserializedVersion);
                    // We exited from this method early if we were recovering from an event stream.
                case EVENT_STREAM_RECOVERY -> throw new RuntimeException("Should never be reached");
            }
        } catch (final Throwable th) {
            logger.fatal("Critical failure during initialization", th);
            System.exit(1);
        }

        // This field has to be set by the time we get here. It will be set by both the genesis and restart code
        // branches. One of those two is called before a "reconnect" trigger, so we should be fully guaranteed that this
        // assertion will hold true.
        assert configProvider != null : "Config Provider *must* have been set by now!";

        // Some logging on what we found about freeze in the platform state
        logger.info(
                "Platform state includes freeze time={} and last frozen={}",
                platformState.getFreezeTime(),
                platformState.getLastFrozenTime());
    }
    /**
     * Called by this class when we detect it is time to do migration. The {@code deserializedVersion} must not be newer
     * than the current software version. If it is prior to the current version, then each migration between the
     * {@code deserializedVersion} and the current version, including the current version, will be executed, thus
     * bringing the state up to date.
     *
     * <p>If the {@code deserializedVersion} is {@code null}, then this is the first time the node has been started,
     * and thus all schemas will be executed.
     * @param state current state
     * @param deserializedVersion version deserialized
     * @param trigger trigger that is calling migration
     */
    private void onMigrate(
            @NonNull final MerkleHederaState state,
            @Nullable final HederaSoftwareVersion deserializedVersion,
            @NonNull final InitTrigger trigger) {
        final var currentVersion = version.getServicesVersion();
        final var previousVersion = deserializedVersion == null ? null : deserializedVersion.getServicesVersion();
        logger.info(
                "Migrating from version {} to {} with trigger {}",
                () -> previousVersion == null ? "<NONE>" : HapiUtils.toString(previousVersion),
                () -> HapiUtils.toString(currentVersion),
                () -> trigger);

        final var selfId = platform.getSelfId();
        final var nodeAddress = platform.getAddressBook().getAddress(selfId);
        final var selfNodeInfo = SelfNodeInfoImpl.of(nodeAddress, version);
        final var networkInfo = new NetworkInfoImpl(selfNodeInfo, platform, bootstrapConfigProvider);

        final var migrator = new OrderedServiceMigrator(servicesRegistry, backendThrottle);
        migrator.doMigrations(state, currentVersion, previousVersion, configProvider.getConfiguration(), networkInfo);

        final var isUpgrade = isSoOrdered(previousVersion, currentVersion);
        if (isUpgrade && !trigger.equals(RECONNECT)) {
            // When we upgrade to a higher version, after migrations are complete, we need to update
            // migrationRecordsStreamed flag to false
            // Now that the migrations have happened, we need to give the node a chance to publish any records that need
            // to
            // be created as a result of the migration. We'll do this by unsetting the `migrationRecordsStreamed` flag.
            // Then, when the handle workflow has its first consensus timestamp, it will handle publishing these records
            // (if
            // needed), and re-set this flag to prevent duplicate publishing.
            unmarkMigrationRecordsStreamed(state);
        }

        logger.info("Migration complete");
    }

    /**
     * Unsets the `migrationRecordsStreamed` flag in state, giving the handle workflow an opportunity
     * to publish any necessary records from the node's startup migration.
     */
    private void unmarkMigrationRecordsStreamed(HederaState state) {
        final var blockServiceState = state.getWritableStates(BlockRecordService.NAME);
        final var blockInfoState = blockServiceState.<BlockInfo>getSingleton(BlockRecordService.BLOCK_INFO_STATE_KEY);
        final var currentBlockInfo = requireNonNull(blockInfoState.get());
        final var nextBlockInfo =
                currentBlockInfo.copyBuilder().migrationRecordsStreamed(false).build();
        blockInfoState.put(nextBlockInfo);
        logger.info(
                "Unmarked migration records streamed with block info {} with hash {}",
                nextBlockInfo,
                blockInfoState.hashCode());
        ((WritableSingletonStateBase<BlockInfo>) blockInfoState).commit();
    }

    /*==================================================================================================================
    *
    * Initialization Step 3: Initialize the app. Happens once at startup.
    *
    =================================================================================================================*/

    /**
     * {@inheritDoc}
     *
     * <p>Called <b>AFTER</b> init and migrate have been called on the state (either the new state created from
     * {@link #newState()} or an instance of {@link MerkleHederaState} created by the platform and loaded from the saved
     * state).
     */
    @SuppressWarnings("java:S1181") // catching Throwable instead of Exception when we do a direct System.exit()
    @Override
    public void init(@NonNull final Platform platform, @NonNull final NodeId nodeId) {
        if (this.platform != platform) {
            throw new IllegalArgumentException("Platform must be the same instance");
        }
        logger.info("Initializing Hedera app with HederaNode#{}", nodeId);

        // Check that UTF-8 is in use. Otherwise, the node will be subject to subtle bugs in string handling that will
        // lead to ISS.
        final var defaultCharset = daggerApp.nativeCharset().get();
        if (!isUTF8(defaultCharset)) {
            logger.error(
                    """
                            Fatal precondition violation in HederaNode#{}: default charset is {} and not UTF-8
                            LC_ALL={}
                            LANG={}
                            file.encoding={}
                            """,
                    daggerApp.nodeId(),
                    defaultCharset,
                    System.getenv("LC_ALL"),
                    System.getenv("LANG"),
                    System.getProperty("file.encoding"));
            daggerApp.systemExits().fail(1);
        }

        // Check that the digest factory supports SHA-384.
        final var digestFactory = daggerApp.digestFactory();
        if (!sha384DigestIsAvailable(digestFactory)) {
            logger.error(
                    "Fatal precondition violation in HederaNode#{}: digest factory does not support SHA-384",
                    daggerApp.nodeId());
            daggerApp.systemExits().fail(1);
        }

        // Finish initialization
        try {
            Locale.setDefault(Locale.US);
            logger.info("Locale to set to US en");

            // The Hashgraph platform has a "platform state", and a notification service to indicate when those
            // states change. We will use these state changes for various purposes, such as turning off the gRPC
            // server when we fall behind or ISS.
            final var notifications = platform.getNotificationEngine();
            notifications.register(PlatformStatusChangeListener.class, notification -> {
                final var wasActive = platformStatus == PlatformStatus.ACTIVE;
                platformStatus = notification.getNewStatus();
                switch (platformStatus) {
                    case ACTIVE -> {
                        logger.info("Hederanode#{} is ACTIVE", nodeId);
                        startGrpcServer();
                    }

                    case REPLAYING_EVENTS,
                            STARTING_UP,
                            OBSERVING,
                            RECONNECT_COMPLETE,
                            CHECKING,
                            FREEZING,
                            BEHIND -> logger.info("Hederanode#{} is {}", nodeId, platformStatus.name());

                    case CATASTROPHIC_FAILURE, FREEZE_COMPLETE -> {
                        logger.info("Hederanode#{} is {}", nodeId, platformStatus.name());
                        if (wasActive) shutdownGrpcServer();
                    }
                }
            });

            // TBD: notifications.register(ReconnectCompleteListener.class, daggerApp.reconnectListener());
            // The main job of the reconnect listener (com.hedera.node.app.service.mono.state.logic.ReconnectListener)
            // is to log some output (including hashes from the tree for the main state per service) and then to
            // "catchUpOnMissedSideEffects". This last part worries me, because it looks like it invades into the space
            // filled by the freeze service. How should we coordinate lifecycle like reconnect with the services? I am
            // tempted to say that each service has lifecycle methods we can invoke (optional methods on the Service
            // interface), but I worry about the order of invocation on different services. Which service gets called
            // before which other service? Does it matter?
            // ANSWER: We need to look and see if there is an update to the upgrade file that happened on other nodes
            // that we reconnected with. In that case, we need to save the file to disk. Similar to how we have to hook
            // for all the other special files on restart / genesis / reconnect.

            // TBD: notifications.register(StateWriteToDiskCompleteListener.class,
            // It looks like this notification is handled by
            // com.hedera.node.app.service.mono.state.logic.StateWriteToDiskListener
            // which looks like it is related to freeze / upgrade.
            // daggerApp.stateWriteToDiskListener());
            // see issue #8660

            // TBD: notifications.register(NewSignedStateListener.class, daggerApp.newSignedStateListener());
            // com.hedera.node.app.service.mono.state.exports.NewSignedStateListener
            // Has some relationship to freeze/upgrade, but also with balance exports. This was the trigger that
            // caused us to export balance files on a certain schedule.
        } catch (final Throwable th) {
            logger.error("Fatal precondition violation in HederaNode#{}", daggerApp.nodeId(), th);
            daggerApp.systemExits().fail(1); // TBD: Better exit code?
        }
    }

    /**
     * Gets whether the default charset is UTF-8.
     */
    private boolean isUTF8(@NonNull final Charset defaultCharset) {
        if (!UTF_8.equals(defaultCharset)) {
            logger.error("Default charset is {}, not UTF-8", defaultCharset);
            return false;
        }
        return true;
    }

    /**
     * Gets whether the sha384 digest is available
     */
    private boolean sha384DigestIsAvailable(@NonNull final NamedDigestFactory digestFactory) {
        try {
            digestFactory.forName("SHA-384");
            return true;
        } catch (final NoSuchAlgorithmException e) {
            logger.error(e);
            return false;
        }
    }

    /*==================================================================================================================
    *
    * Other app lifecycle methods
    *
    =================================================================================================================*/

    /**
     * {@inheritDoc}
     *
     * <p>Called by the platform after <b>ALL</b> initialization to start the gRPC servers and begin operation, or by
     * the notification listener when it is time to restart the gRPC server after it had been stopped (such as during
     * reconnect).
     */
    @Override
    public void run() {
        logger.info("Starting the Hedera node");
    }

    /**
     * Called for an orderly shutdown.
     */
    public void shutdown() {
        logger.info("Shutting down Hedera node");
        shutdownGrpcServer();

        if (daggerApp != null) {
            logger.debug("Shutting down the state");
            final var state = daggerApp.workingStateAccessor().getHederaState();
            if (state instanceof MerkleHederaState mhs) {
                mhs.close();
            }

            logger.debug("Shutting down the block manager");
            daggerApp.blockRecordManager().close();
        }

        platform = null;
        daggerApp = null;
    }

    /**
     * Invoked by the platform to handle pre-consensus events. This only happens after {@link #run()} has been called.
     */
    public void onPreHandle(@NonNull final Event event, @NonNull final HederaState state) {
        final var readableStoreFactory = new ReadableStoreFactory(state);
        final var creator =
                daggerApp.networkInfo().nodeInfo(event.getCreatorId().id());
        if (creator == null) {
            // We were given an event for a node that *does not exist in the address book*. This will be logged as
            // a warning, as this should never happen, and we will skip the event, which may well result in an ISS.
            logger.warn("Received event from node {} which is not in the address book", event.getCreatorId());
            return;
        }

        final var transactions = new ArrayList<Transaction>(1000);
        event.forEachTransaction(transactions::add);
        daggerApp.preHandleWorkflow().preHandle(readableStoreFactory, creator.accountId(), transactions.stream());
    }

    /**
     * Invoked by the platform to handle a round of consensus events.  This only happens after {@link #run()} has been
     * called.
     */
    public void onHandleConsensusRound(
            @NonNull final Round round, @NonNull final PlatformState platformState, @NonNull final HederaState state) {
        daggerApp.workingStateAccessor().setHederaState(state);
        daggerApp.handleWorkflow().handleRound(state, platformState, round);
    }

    /**
     * Invoked by the platform to update weights of all nodes, to be used in consensus.
     * This only happens during upgrade.
     * @param state current state
     * @param configAddressBook address book from config.txt
     * @param context platform context
     */
    public void onUpdateWeight(
            @NonNull final MerkleHederaState state,
            @NonNull AddressBook configAddressBook,
            @NonNull final PlatformContext context) {
        final var tokenServiceState = state.getReadableStates(TokenService.NAME);
        if (!tokenServiceState.isEmpty()) {
            final var readableStoreFactory = new ReadableStoreFactory(state);
            // Get all nodeIds added in the config.txt
            Set<NodeId> configNodeIds = configAddressBook.getNodeIdSet();
            final var stakingInfoStore = readableStoreFactory.getStore(ReadableStakingInfoStore.class);
            final var allNodeIds = stakingInfoStore.getAll();
            for (final var nodeId : allNodeIds) {
                final var stakingInfo = requireNonNull(stakingInfoStore.get(nodeId));
                NodeId id = new NodeId(nodeId);
                // ste weight for the nodes that exist in state and remove from
                // nodes given in config.txt. This is needed to recognize newly added nodes
                configAddressBook.updateWeight(id, stakingInfo.weight());
                configNodeIds.remove(id);
            }
            // for any newly added nodes that doesn't exist in state, weight should be set to 0
            // irrespective of the weight provided in config.txt
            configNodeIds.forEach(nodeId -> configAddressBook.updateWeight(nodeId, 0));
        } else {
            logger.warn("Token service state is empty to update weights from StakingInfo Map");
        }
    }

    /*==================================================================================================================
    *
    * gRPC Server Lifecycle
    *
    =================================================================================================================*/

    /**
     * Start the gRPC Server if it is not already running.
     */
    void startGrpcServer() {
        if (!daggerApp.grpcServerManager().isRunning()) {
            daggerApp.grpcServerManager().start();
        }
    }

    /**
     * Called to perform orderly shutdown of the gRPC servers.
     */
    public void shutdownGrpcServer() {
        daggerApp.grpcServerManager().stop();
    }

    /*==================================================================================================================
    *
    * Genesis Initialization
    *
    =================================================================================================================*/

    /**
     * Implements the code flow for initializing the state of a new Hedera node with NO SAVED STATE.
     */
    private void genesis(@NonNull final MerkleHederaState state) {
        logger.debug("Genesis Initialization");

        logger.info("Initializing ThrottleManager");
        this.throttleManager = new ThrottleManager();

        this.backendThrottle = new ThrottleAccumulator(SUPPLY_ONE, configProvider, BACKEND_THROTTLE);
        this.frontendThrottle =
                new ThrottleAccumulator(() -> platform.getAddressBook().getSize(), configProvider, FRONTEND_THROTTLE);
        this.congestionMultipliers = createCongestionMultipliers(state);

        logger.info("Initializing ExchangeRateManager");
        exchangeRateManager = new ExchangeRateManager(configProvider);

        logger.info("Initializing FeeManager");
        feeManager = new FeeManager(exchangeRateManager, congestionMultipliers);

        // Create all the nodes in the merkle tree for all the services
        onMigrate(state, null, GENESIS);

        // Now that we have the state created, we are ready to create the dependency graph with Dagger
        initializeDagger(state, GENESIS);

        // And now that the entire dependency graph has been initialized, and we have config, and all migration has
        // been completed, we are prepared to initialize in-memory data structures. These specifically are loaded
        // from information held in state (especially those in special files).
        initializeExchangeRateManager(state);
        initializeFeeManager(state);
        initializeThrottles(state);
    }

    private CongestionMultipliers createCongestionMultipliers(HederaState state) {
        final var genericFeeMultiplier = new ThrottleMultiplier(
                "logical TPS",
                "TPS",
                "CryptoTransfer throughput",
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .minCongestionPeriod(),
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .percentCongestionMultipliers(),
                () -> backendThrottle.activeThrottlesFor(CRYPTO_TRANSFER));

        final var txnRateMultiplier = new EntityUtilizationMultiplier(genericFeeMultiplier, configProvider);

        final var gasFeeMultiplier = new ThrottleMultiplier(
                "EVM gas/sec",
                "gas/sec",
                "EVM utilization",
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .minCongestionPeriod(),
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .percentCongestionMultipliers(),
                () -> List.of(backendThrottle.gasLimitThrottle()));

        return new CongestionMultipliers(txnRateMultiplier, gasFeeMultiplier);
    }

    /*==================================================================================================================
    *
    * Restart Initialization
    *
    =================================================================================================================*/
    /**
     * Initialize flow for when a node has been restarted. This means it was started from a saved state.
     */
    private void restart(
            @NonNull final MerkleHederaState state, @Nullable final HederaSoftwareVersion deserializedVersion) {
        initializeForTrigger(state, deserializedVersion, RESTART);
    }

    /*==================================================================================================================
    *
    * Reconnect Initialization
    *
    =================================================================================================================*/

    /**
     * The initialization needed for reconnect. It constructs all schemas appropriately.
     * These are exactly the same steps done as restart trigger.
     * @param state The current state
     * @param deserializedVersion version of deserialized state
     */
    private void reconnect(
            @NonNull final MerkleHederaState state, @Nullable final HederaSoftwareVersion deserializedVersion) {
        initializeForTrigger(state, deserializedVersion, RECONNECT);
    }

    private void initializeForTrigger(
            @NonNull final MerkleHederaState state,
            @Nullable final HederaSoftwareVersion deserializedVersion,
            @NonNull final InitTrigger trigger) {
        logger.debug(trigger + " Initialization");

        // The deserialized version can ONLY be null if we are in genesis, otherwise something is wrong with the state
        if (deserializedVersion == null) {
            logger.fatal("Fatal error, previous software version not found in saved state!");
            System.exit(1);
        }

        // Initialize the configuration from disk (restart case). We must do this BEFORE we run migration, because
        // the various migration methods may depend on configuration to do their work
        logger.info("Initializing Reconnect configuration");
        this.configProvider = new ConfigProviderImpl(false);
        logConfiguration();

        logger.info("Initializing ThrottleManager");
        this.throttleManager = new ThrottleManager();

        this.backendThrottle = new ThrottleAccumulator(SUPPLY_ONE, configProvider, BACKEND_THROTTLE);
        this.frontendThrottle =
                new ThrottleAccumulator(() -> platform.getAddressBook().getSize(), configProvider, FRONTEND_THROTTLE);
        this.congestionMultipliers = createCongestionMultipliers(state);

        logger.info("Initializing ExchangeRateManager");
        exchangeRateManager = new ExchangeRateManager(configProvider);

        logger.info("Initializing FeeManager");
        feeManager = new FeeManager(exchangeRateManager, congestionMultipliers);

        // Create all the nodes in the merkle tree for all the services
        // TODO: Actually, we should reinitialize the config on each step along the migration path, so we should pass
        //       the config provider to the migration code and let it get the right version of config as it goes.
        onMigrate(state, deserializedVersion, trigger);

        // Now that we have the state created, we are ready to create the dependency graph with Dagger
        initializeDagger(state, trigger);

        // And now that the entire dependency graph has been initialized, and we have config, and all migration has
        // been completed, we are prepared to initialize in-memory data structures. These specifically are loaded
        // from information held in state (especially those in special files).
        initializeExchangeRateManager(state);
        initializeFeeManager(state);
        initializeThrottles(state);
        // TODO We may need to update the config with the latest version in file 121
    }

    /*==================================================================================================================
    *
    * Random private helper methods
    *
    =================================================================================================================*/

    private void initializeDagger(@NonNull final MerkleHederaState state, @NonNull final InitTrigger trigger) {
        logger.debug("Initializing dagger");
        final var selfId = platform.getSelfId();
        final var nodeAddress = platform.getAddressBook().getAddress(selfId);
        // Fully qualified so as to not confuse javadoc
        // DaggerApp should be constructed every time we reach this point, even if exists. This is needed for reconnect
        daggerApp = com.hedera.node.app.DaggerHederaInjectionComponent.builder()
                .initTrigger(trigger)
                .configuration(configProvider)
                .throttleManager(throttleManager)
                .exchangeRateManager(exchangeRateManager)
                .feeManager(feeManager)
                .systemFileUpdateFacility(new SystemFileUpdateFacility(
                        configProvider,
                        throttleManager,
                        exchangeRateManager,
                        feeManager,
                        congestionMultipliers,
                        backendThrottle,
                        frontendThrottle))
                .networkUtilizationManager(new NetworkUtilizationManagerImpl(backendThrottle, congestionMultipliers))
                .synchronizedThrottleAccumulator(new SynchronizedThrottleAccumulator(frontendThrottle))
                .self(SelfNodeInfoImpl.of(nodeAddress, version))
                .platform(platform)
                .maxSignedTxnSize(MAX_SIGNED_TXN_SIZE)
                .crypto(CryptographyHolder.get())
                .currentPlatformStatus(new CurrentPlatformStatusImpl(platform))
                .servicesRegistry(servicesRegistry)
                .bootstrapProps(new BootstrapProperties(false)) // TBD REMOVE
                .instantSource(InstantSource.system())
                .genesisRecordsConsensusHook((GenesisRecordsConsensusHook) genesisRecordsBuilder)
                .build();

        daggerApp.workingStateAccessor().setHederaState(state);
    }

    private boolean isDowngrade(
            final HederaSoftwareVersion deployedVersion, final SoftwareVersion deserializedVersion) {
        return deployedVersion.isBefore(deserializedVersion);
    }

    private void logConfiguration() {
        if (logger.isInfoEnabled()) {
            final var config = configProvider.getConfiguration();
            final var lines = new ArrayList<String>();
            lines.add("Active Configuration:");
            Utils.allProperties(config).forEach((key, value) -> lines.add(key + " = " + value));
            logger.info(String.join("\n", lines));
        }
    }

    private void initializeFeeManager(@NonNull final HederaState state) {
        logger.info("Initializing fee schedules");
        final var filesConfig = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileNum = filesConfig.feeSchedules();
        final File file = getFileFromStorage(state, fileNum);
        if (file != null) {
            final var fileData = file.contents();
            daggerApp.feeManager().update(fileData);
        }
        logger.info("Fee schedule initialized");
    }

    private void initializeExchangeRateManager(@NonNull final HederaState state) {
        logger.info("Initializing exchange rates");
        final var filesConfig = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileNum = filesConfig.exchangeRates();
        final var file = getFileFromStorage(state, fileNum);
        if (file != null) {
            final var fileData = file.contents();
            daggerApp.exchangeRateManager().init(state, fileData);
        }
        logger.info("Exchange rates initialized");
    }

    private void initializeThrottles(@NonNull final HederaState state) {
        logger.info("Initializing throttles");
        final var filesConfig = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileNum = filesConfig.throttleDefinitions();
        final var file = getFileFromStorage(state, fileNum);
        if (file != null) {
            final var fileData = file.contents();
            daggerApp.throttleManager().update(fileData);

            // Initializing handle throttling
            this.backendThrottle.rebuildFor(daggerApp.throttleManager().throttleDefinitions());
            this.backendThrottle.applyGasConfig();
            this.frontendThrottle.rebuildFor(daggerApp.throttleManager().throttleDefinitions());
            this.frontendThrottle.applyGasConfig();

            // Updating the multiplier source to use the new throttle definitions
            this.congestionMultipliers.resetExpectations();
        }
        logger.info("Throttles initialized");
    }

    private File getFileFromStorage(HederaState state, long fileNum) {
        final var readableFileStore = new ReadableStoreFactory(state).getStore(ReadableFileStore.class);
        final var hederaConfig = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var fileId = FileID.newBuilder()
                .fileNum(fileNum)
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .build();
        return readableFileStore.getFileLeaf(fileId);
    }
}
