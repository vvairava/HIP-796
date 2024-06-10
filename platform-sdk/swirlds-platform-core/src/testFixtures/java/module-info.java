open module com.swirlds.platform.core.test.fixtures {
    requires transitive com.hedera.node.hapi;
    requires transitive com.swirlds.common.test.fixtures;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.merkle;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.virtualmap;
    requires transitive com.hedera.pbj.runtime;
    requires transitive org.junit.jupiter.params;
    requires com.swirlds.logging;
    requires com.swirlds.merkledb;
    requires com.swirlds.wiring;
    requires org.apache.logging.log4j;
    requires org.junit.jupiter.api;
    requires org.mockito;
    requires static transitive com.github.spotbugs.annotations;

    exports com.swirlds.platform.test.fixtures;
    exports com.swirlds.platform.test.fixtures.stream;
    exports com.swirlds.platform.test.fixtures.event;
    exports com.swirlds.platform.test.fixtures.event.source;
    exports com.swirlds.platform.test.fixtures.event.generator;
    exports com.swirlds.platform.test.fixtures.state;
    exports com.swirlds.platform.test.fixtures.addressbook;
    exports com.swirlds.platform.test.fixtures.state.merkle;
}
