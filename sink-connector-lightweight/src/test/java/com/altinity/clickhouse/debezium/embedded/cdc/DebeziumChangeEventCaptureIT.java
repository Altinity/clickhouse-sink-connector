package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLBaseIT;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.google.common.collect.Maps;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import static org.junit.Assert.assertTrue;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Properties;

@Testcontainers
public class DebeziumChangeEventCaptureIT extends DDLBaseIT {

    private static final Logger log = LoggerFactory.getLogger(DebeziumChangeEventCaptureIT.class);
    @Test
    public void testDeleteOffsetStorageRow2()  {
        //System.out.println("Delete offset");
        DebeziumChangeEventCapture dec = new DebeziumChangeEventCapture();
        try {
            Properties props = getDebeziumProperties();
            props.setProperty("name", "altinity_sink_connector");
            Map<String, String> propertiesMap = Maps.newHashMap(Maps.fromProperties(props));
            ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(propertiesMap);
            String tableName = props.getProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                    JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());
            DBCredentials dbCredentials = dec.parseDBConfiguration(config);

            BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                    dbCredentials.getDatabase(), dbCredentials.getUserName(),
                    dbCredentials.getPassword(), config);
            String offsetValue = new DebeziumOffsetStorage().getDebeziumStorageStatusQuery(props, writer);
            //String offsetKey = new DebeziumOffsetStorage().getOffsetKey(props);

            String updateOffsetValue = new DebeziumOffsetStorage().updateBinLogInformation(offsetValue, "mysql-bin.001", "2333", null);

            //new DebeziumOffsetStorage().deleteOffsetStorageRow(offsetKey, props, writer);
            //new DebeziumOffsetStorage().updateDebeziumStorageRow(writer, tableName, offsetKey, updateOffsetValue, System.currentTimeMillis());

            System.out.print("Test");
        } catch(Exception e) {
            log.error("Exception in testDeleteOffsetStorageRow2", e);
        }
    }

}
