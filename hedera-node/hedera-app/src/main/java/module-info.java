import com.hedera.node.app.config.ServicesConfigExtension;
import com.swirlds.config.api.ConfigurationExtension;

module com.hedera.node.app {
    requires transitive com.hedera.cryptography.bls;
    requires transitive com.hedera.cryptography.pairings.api;
    requires transitive com.hedera.cryptography.tss;
    requires transitive com.hedera.node.app.hapi.utils;
    requires transitive com.hedera.node.app.service.addressbook.impl;
    requires transitive com.hedera.node.app.service.consensus.impl;
    requires transitive com.hedera.node.app.service.contract.impl;
    requires transitive com.hedera.node.app.service.file.impl;
    requires transitive com.hedera.node.app.service.network.admin.impl;
    requires transitive com.hedera.node.app.service.schedule.impl;
    requires transitive com.hedera.node.app.service.schedule;
    requires transitive com.hedera.node.app.service.token.impl;
    requires transitive com.hedera.node.app.service.token;
    requires transitive com.hedera.node.app.service.util.impl;
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.config;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires transitive com.swirlds.common;
    requires transitive com.swirlds.config.api;
    requires transitive com.swirlds.metrics.api;
    requires transitive com.swirlds.platform.core;
    requires transitive com.swirlds.state.api;
    requires transitive com.swirlds.state.impl;
    requires transitive dagger;
    requires transitive io.grpc.stub;
    requires transitive io.minio;
    requires transitive javax.inject;
    requires transitive org.apache.logging.log4j;
    requires transitive org.hyperledger.besu.datatypes;
    requires transitive org.hyperledger.besu.evm;
    requires com.hedera.node.app.hapi.fees;
    requires com.hedera.node.app.service.addressbook;
    requires com.hedera.node.app.service.consensus;
    requires com.hedera.node.app.service.contract;
    requires com.hedera.node.app.service.file;
    requires com.hedera.node.app.service.network.admin;
    requires com.hedera.node.app.service.util;
    requires com.swirlds.base;
    requires com.swirlds.config.extensions;
    requires com.swirlds.logging;
    requires com.swirlds.merkle;
    requires com.swirlds.merkledb;
    requires com.swirlds.virtualmap;
    requires com.fasterxml.jackson.databind;
    requires com.google.common;
    requires com.google.protobuf;
    requires io.grpc.netty;
    requires io.grpc;
    requires io.helidon.common.tls;
    requires io.helidon.webclient.api;
    requires io.helidon.webclient.grpc;
    requires io.netty.handler;
    requires io.netty.transport.classes.epoll;
    requires io.netty.transport;
    requires java.annotation;
    requires org.apache.commons.lang3;
    requires static com.github.spotbugs.annotations;
    requires static com.google.auto.service;
    requires static java.compiler; // javax.annotation.processing.Generated

    exports com.hedera.node.app;
    exports com.hedera.node.app.state;
    exports com.hedera.node.app.workflows.ingest;
    exports com.hedera.node.app.workflows.query;
    exports com.hedera.node.app.workflows;
    exports com.hedera.node.app.state.merkle to
            com.hedera.node.app.test.fixtures,
            com.hedera.node.test.clients;
    exports com.hedera.node.app.workflows.dispatcher;
    exports com.hedera.node.app.workflows.standalone;
    exports com.hedera.node.app.config;
    exports com.hedera.node.app.workflows.handle.validation;
    exports com.hedera.node.app.signature;
    exports com.hedera.node.app.info;
    exports com.hedera.node.app.grpc;
    exports com.hedera.node.app.metrics;
    exports com.hedera.node.app.authorization;
    exports com.hedera.node.app.platform;
    exports com.hedera.node.app.components;
    exports com.hedera.node.app.workflows.handle;
    exports com.hedera.node.app.workflows.prehandle;
    exports com.hedera.node.app.version;
    exports com.hedera.node.app.validation;
    exports com.hedera.node.app.state.listeners;
    exports com.hedera.node.app.services;
    exports com.hedera.node.app.store;
    exports com.hedera.node.app.workflows.handle.steps;
    exports com.hedera.node.app.workflows.handle.record;
    exports com.hedera.node.app.workflows.handle.throttle;
    exports com.hedera.node.app.workflows.handle.dispatch;
    exports com.hedera.node.app.workflows.handle.cache;
    exports com.hedera.node.app.ids;
    exports com.hedera.node.app.state.recordcache;
    exports com.hedera.node.app.records;
    exports com.hedera.node.app.blocks;
    exports com.hedera.node.app.fees;
    exports com.hedera.node.app.throttle;
    exports com.hedera.node.app.blocks.impl;
    exports com.hedera.node.app.workflows.handle.metric;
    exports com.hedera.node.app.roster;
    exports com.hedera.node.app.tss;
    exports com.hedera.node.app.uploader;
    exports com.hedera.node.app.uploader.configs;
    exports com.hedera.node.app.statedumpers;
    exports com.hedera.node.app.workflows.handle.stack;
    exports com.hedera.node.app.fees.congestion;
    exports com.hedera.node.app.throttle.annotations;
    exports com.hedera.node.app.workflows.query.annotations;
    exports com.hedera.node.app.signature.impl;
    exports com.hedera.node.app.workflows.standalone.impl;
    exports com.hedera.node.app.records.impl;
    exports com.hedera.node.app.records.impl.producers;
    exports com.hedera.node.app.records.impl.producers.formats;
    exports com.hedera.node.app.grpc.impl.netty;
    exports com.hedera.node.app.tss.schemas;
    exports com.hedera.node.app.blocks.schemas;
    exports com.hedera.node.app.roster.schemas;

    provides ConfigurationExtension with
            ServicesConfigExtension;
}
