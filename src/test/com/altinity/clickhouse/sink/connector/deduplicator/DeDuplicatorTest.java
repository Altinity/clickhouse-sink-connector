package com.altinity.clickhouse.sink.connector.deduplicator;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class DeDuplicatorTest {

    @Test
    public void testIsNew() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY, "new");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        DeDuplicator dedupe = new DeDuplicator(config);

        //SinkRecord record = new SinkRecord();


    }
}
