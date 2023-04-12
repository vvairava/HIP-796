package com.hedera.node.app.integration;

import com.hedera.node.app.service.admin.FreezeService;
import com.hedera.node.app.service.admin.impl.FreezeServiceImpl;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.network.NetworkService;
import com.hedera.node.app.service.network.impl.NetworkServiceImpl;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.util.UtilService;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.spi.fixtures.state.MapWritableKVState;
import com.hedera.node.app.spi.fixtures.state.MapWritableStates;
import com.hedera.node.app.spi.state.Schema;
import com.hedera.node.app.spi.state.SchemaRegistry;
import com.hedera.node.app.spi.state.WritableSingletonStateBase;
import com.hedera.node.app.workflows.dispatcher.WritableStoreFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Singleton
public class InMemoryWritableStoreFactory implements WritableStoreFactory {
    private final Map<String, MapWritableStates> serviceStates = new HashMap<>();

    @Inject
    public InMemoryWritableStoreFactory() {
        final var services = Map.of(
                ConsensusService.NAME, new ConsensusServiceImpl(),
                ContractService.NAME, new ContractServiceImpl(),
                FileService.NAME, new FileServiceImpl(),
                FreezeService.NAME, new FreezeServiceImpl(),
                NetworkService.NAME, new NetworkServiceImpl(),
                ScheduleService.NAME, new ScheduleServiceImpl(),
                TokenService.NAME, new TokenServiceImpl(),
                UtilService.NAME, new UtilServiceImpl());
        services.forEach((name, service) ->
                serviceStates.put(name, inMemoryStatesFrom(service::registerSchemas)));
    }

    @Override
    public WritableTopicStore createTopicStore() {
        return new WritableTopicStore(serviceStates.get(ConsensusService.NAME));
    }

    private MapWritableStates inMemoryStatesFrom(@NonNull final Consumer<SchemaRegistry> cb) {
        final var factory = new StatesBuildingSchemaRegistry();
        cb.accept(factory);
        return factory.build();
    }

    private static class StatesBuildingSchemaRegistry implements SchemaRegistry {
        private final MapWritableStates.Builder builder = new MapWritableStates.Builder();

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public SchemaRegistry register(@NonNull final Schema schema) {
            schema.statesToCreate().forEach(stateDefinition -> {
                if (stateDefinition.singleton()) {
                    final var accessor = new AtomicReference();
                    builder.state(new WritableSingletonStateBase<>(
                            stateDefinition.stateKey(), accessor::get, accessor::set));
                } else {
                    builder.state(new MapWritableKVState(stateDefinition.stateKey()));
                }
            });
            return this;
        }

        public MapWritableStates build() {
            return builder.build();
        }
    }
}
