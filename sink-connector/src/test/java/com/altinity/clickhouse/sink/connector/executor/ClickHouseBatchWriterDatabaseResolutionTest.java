package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ClickHouseBatchWriterDatabaseResolutionTest {

    private static ClickHouseSinkConnectorConfig createConfig(Map<String, String> customProps) {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "8123");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "default");
        if (customProps != null) {
            props.putAll(customProps);
        }
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct createStructWithDatabase(String db) {
        ClickHouseStruct struct = new ClickHouseStruct();
        struct.setDatabase(db);
        return struct;
    }

    @Test
    @DisplayName("resolveDatabaseName returns plain database when no overrides are configured")
    public void testResolveDatabaseNamePlain() {
        ClickHouseBatchWriter writer = new ClickHouseBatchWriter(createConfig(null), new HashMap<>());
        ClickHouseStruct record = createStructWithDatabase("source_db");

        String resolved = writer.resolveDatabaseName("server.source_db.users", record);
        assertEquals("source_db", resolved);
    }

    @Test
    @DisplayName("resolveDatabaseName applies database prefix")
    public void testResolveDatabaseNamePrefix() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.common.database.prefix", "prod_");
        ClickHouseBatchWriter writer = new ClickHouseBatchWriter(createConfig(props), new HashMap<>());
        ClickHouseStruct record = createStructWithDatabase("source_db");

        String resolved = writer.resolveDatabaseName("server.source_db.users", record);
        assertEquals("prod_source_db", resolved);
    }

    @Test
    @DisplayName("resolveDatabaseName applies database schema suffix")
    public void testResolveDatabaseNameSchemaSuffix() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.database.schema.suffix", "true");
        props.put("clickhouse.common.schema.template", "_{{ schema }}");
        ClickHouseBatchWriter writer = new ClickHouseBatchWriter(createConfig(props), new HashMap<>());
        ClickHouseStruct record = createStructWithDatabase("source_db");

        String resolved = writer.resolveDatabaseName("server.public.users", record);
        assertEquals("source_db_public", resolved);
    }

    @Test
    @DisplayName("resolveDatabaseName applies database override map")
    public void testResolveDatabaseNameOverrideMap() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.database.override.map", "source_db:overridden_db");
        ClickHouseBatchWriter writer = new ClickHouseBatchWriter(createConfig(props), new HashMap<>());
        ClickHouseStruct record = createStructWithDatabase("source_db");

        String resolved = writer.resolveDatabaseName("server.source_db.users", record);
        assertEquals("overridden_db", resolved);
    }

    @Test
    @DisplayName("resolveDatabaseName preserves replication history database name without being overwritten by override map")
    public void testResolveDatabaseNameReplicationHistoryProtected() {
        Map<String, String> props = new HashMap<>();
        props.put("replication.history.enable", "true");
        props.put("replication.history.database.name", "binlog_history");
        props.put("clickhouse.database.override.map", "source_db:custom_dest");
        ClickHouseBatchWriter writer = new ClickHouseBatchWriter(createConfig(props), new HashMap<>());
        ClickHouseStruct record = createStructWithDatabase("source_db");

        String resolved = writer.resolveDatabaseName("server.source_db.users", record);
        assertEquals("binlog_history", resolved);
    }

    @Test
    @DisplayName("getTableFromTopic formats table name with schema prefix when configured")
    public void testGetTableFromTopicWithSchemaPrefix() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.table.schema.prefix", "true");
        props.put("clickhouse.common.schema.template", "{{ schema }}_");
        ClickHouseBatchWriter writer = new ClickHouseBatchWriter(createConfig(props), new HashMap<>());

        String tableName = writer.getTableFromTopic("server.public.users");
        assertEquals("public_users", tableName);
    }
}
