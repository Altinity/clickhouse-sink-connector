package com.altinity.clickhouse.sink.connector;


import org.apache.kafka.common.config.Config;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ClickHouseSinkConnector extends SinkConnector {

    // String configuration as properties
    private Map<String, String> config;
    private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkConnector.class);
    private boolean ready;

    /**
     *
     */
    public ClickHouseSinkConnector() {
        log.info("ClickHouseSinkConnector()");
        // Connector is not yet ready to accept data
        this.ready = false;
    }

    /**
     * @param conf
     */
    @Override
    public void start(final Map<String, String> conf) {
        log.info("start()");
        // Instantiate main connector's config and fill it with default values
        this.config = conf;
        // From now on connector is ready to accept data
        this.ready = true;
    }

    /**
     *
     */
    @Override
    public void stop() {
        log.info("stop()");
        // Connector is no more ready to accept data
        this.ready = false;
    }

    /**
     * @return
     */
    @Override
    public Class<? extends Task> taskClass() {
        return ClickHouseSinkTask.class;
    }

    /**
     * @param maxTasks
     * @return
     */
    @Override
    public List<Map<String, String>> taskConfigs(final int maxTasks) {
        while (!this.ready) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
                // Action may be interrupted
            }
        }

        // Create personal configuration for each task
        List<Map<String, String>> configs = new ArrayList<>(maxTasks);
        for (int i = 0; i < maxTasks; i++) {
            // Instantiate config from the main connector's config and personalize with additional params
            Map<String, String> conf = new HashMap<>(this.config);
            conf.put(ClickHouseSinkConnectorConfigVariables.TASK_ID, "" + i);
            configs.add(conf);
        }
        return configs;
    }

    /**
     * @return
     */
    @Override
    public ConfigDef config() {
        return ClickHouseSinkConnectorConfig.newConfigDef();
    }

    /**
     * @param conf
     * @return
     */
    @Override
    public Config validate(Map<String, String> conf) {
        log.debug("validate()");
        // Insert name of the connector.
        // TODO - should it be a parameter?
        conf.put(Const.NAME, "TEST_CONNECTOR");
        Config result = super.validate(conf);
        log.info("Config validated");
        return result;
    }

    /**
     * @return
     */
    @Override
    public String version() {
        return Version.VERSION;
    }

}