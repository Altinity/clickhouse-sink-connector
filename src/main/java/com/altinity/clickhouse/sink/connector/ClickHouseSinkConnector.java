/*
 * Copyright (c)  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.altinity.clickhouse.sink.connector;


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

    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseSinkConnector.class);

    private Map<String, String> config;
    @Override
    public String version() {
        return Utils.VERSION;
    }

    @Override
    public void start(Map<String, String> props) {
        // The following activities need to be done here
        // 1. Load configuration (Kafka and Clickhouse specific)
        // 2. Create a connection to Clickhouse.

        LOGGER.debug("STARTING CLICKHOUSE SINK CONNECTOR");
        config = new HashMap<>(props);
    }

    /** @return Sink task class */
    @Override
    public Class<? extends Task> taskClass() {
        return ClickHouseSinkTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {

        List<Map<String, String>> taskConfigs = new ArrayList<>(maxTasks);
        for (int i = 0; i < maxTasks; i++) {
            Map<String, String> conf = new HashMap<>(config);
            conf.put(Utils.TASK_ID, i + "");
            taskConfigs.add(conf);
        }
        return taskConfigs;
    }

    @Override
    public void stop() {
        // TODO Auto-generated method stub
        
    }

    @Override
    public ConfigDef config() {
        return ClickHouseSinkConnectorConfig.newConfigDef();
    }
}