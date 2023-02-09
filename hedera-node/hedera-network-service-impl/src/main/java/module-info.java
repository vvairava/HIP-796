import com.hedera.node.app.service.network.impl.NetworkServiceImpl;

module com.hedera.node.app.service.network.impl {
    requires com.hedera.node.app.service.network;
    requires dagger;
    requires javax.inject;

    provides com.hedera.node.app.service.network.NetworkService with
            NetworkServiceImpl;

    exports com.hedera.node.app.service.network.impl to
            com.hedera.node.app.service.network.impl.test;
    exports com.hedera.node.app.service.network.impl.handlers;
    exports com.hedera.node.app.service.network.impl.components;
}
