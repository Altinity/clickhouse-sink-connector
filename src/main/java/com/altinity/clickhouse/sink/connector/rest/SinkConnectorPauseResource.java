package com.altinity.clickhouse.sink.connector.rest;


import jakarta.ws.rs.Path;
import org.apache.kafka.connect.health.ConnectClusterState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Path("/connect")
class SinkConnectorPauseResource {
    private static final Logger log = LoggerFactory.getLogger(SinkConnectorPauseResource.class);

    public SinkConnectorPauseResource(Map<String, ?> configs, ConnectClusterState clusterState) {
        //initialize resource
    }

    @Path("/pause")
    public void pauseConnector() {

        log.info("PAUSE connector message received");
    }

    @Path("/resume")
    public void resumeConnector() {

        log.info("RESUME connector message received");

    }
}