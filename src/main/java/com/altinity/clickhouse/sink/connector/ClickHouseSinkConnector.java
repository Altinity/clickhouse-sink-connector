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
    private Map<String, String> config;
    //private ClickHouse ch;
    private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkConnector.class);
    private boolean ready;

    /**
     *
     */
    public ClickHouseSinkConnector() {
        this.ready = false;
        log.info("ClickHouseSinkConnector()");
    }

    /**
     *
     * @param cnf
     */
    @Override
    public void start(final Map<String, String> cnf) {
        log.info("start()");
        // Prepare config
        this.config = new HashMap<>(cnf);
        ClickHouseSinkConnectorConfig.setDefaultValues(this.config);
        // Prepare ClickHouse connection
        //ch = ch.builder().setProperties(this.config).build();
        this.ready = true;
    }

    /**
     *
     */
    @Override
    public void stop() {
        log.info("stop()");
        this.ready = false;
    }

    /**
     *
     * @return
     */
    @Override
    public Class<? extends Task> taskClass() {
        return ClickHouseSinkTask.class;
    }

    /**
     *
     * @param maxTasks
     * @return
     */
    @Override
    public List<Map<String, String>> taskConfigs(final int maxTasks) {
        while (!this.ready) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
            }
        }

        List<Map<String, String>> taskConfigs = new ArrayList<>(maxTasks);
        for (int i = 0; i < maxTasks; i++) {
            Map<String, String> conf = new HashMap<>(this.config);
            conf.put(Const.TASK_ID, "" + i);
            taskConfigs.add(conf);
        }
        return taskConfigs;
    }

    /**
     *
     * @return
     */
    @Override
    public ConfigDef config() {
        return ClickHouseSinkConnectorConfig.newConfigDef();
    }

    /**
     *
     * @param connectorConfigs
     * @return
     */
    @Override
    public Config validate(Map<String, String> connectorConfigs) {
        log.debug("validate()");
        connectorConfigs.put(Const.NAME, "TEST_CONNECTOR");
        Config result = super.validate(connectorConfigs);
        log.info("Config validated");
        return result;
    }

    /**
     *
     * @return
     */
    @Override
    public String version() {
        return Version.VERSION;
    }

}