package com.altinity.clickhouse.sink.connector.rest;

import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.connect.rest.ConnectRestExtension;
import org.apache.kafka.connect.rest.ConnectRestExtensionContext;

import java.io.IOException;
import java.util.Map;

public class ClickHouseSinkConnectorExtension implements ConnectRestExtension {
    private Map<String, ?> configs;

    @Override
    public void register(ConnectRestExtensionContext restPluginContext) {
        restPluginContext.configurable().register(new SinkConnectorPauseResource(configs, restPluginContext.clusterState()));
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public void configure(Map<String, ?> configs) {
        this.configs = configs;
    }

    @Override
    public String version() {
        return AppInfoParser.getVersion();
    }
}
