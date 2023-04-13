package com.hedera.node.app.integration;

import com.hedera.node.app.integration.facilities.ReplayAdvancingConsensusNow;
import com.hedera.node.app.integration.infra.InMemoryWritableStoreFactory;
import com.hedera.node.app.integration.infra.ReplayFacilityTransactionDispatcher;
import com.hedera.node.app.service.mono.utils.replay.ReplayAssetRecording;
import com.hedera.node.app.services.ServiceModule;
import com.hedera.node.app.workflows.handle.HandlersModule;
import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = {
        ServiceModule.class,
        HandlersModule.class,
        ReplayFacilityModule.class,
})
public interface ReplayFacilityComponent {
    @Component.Factory
    interface Factory {
        ReplayFacilityComponent create();
    }

    ReplayAssetRecording assetRecording();
    ReplayAdvancingConsensusNow consensusNow();
    InMemoryWritableStoreFactory writableStoreFactory();
    ReplayFacilityTransactionDispatcher transactionDispatcher();
}
