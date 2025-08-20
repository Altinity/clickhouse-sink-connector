package com.altinity.clickhouse.sink.connector.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SchemaOverrideConfigTest {

    private SchemaOverrideConfig config;

    Map<String, String> configMap;

    /**
     * Set up a new instance of SchemaOverrideConfig and load the YAML configuration
     * before each test. The file is loaded from the classpath (resources).
     */
    @BeforeEach
    public void setUp() throws IOException {
        config = new SchemaOverrideConfig();

        configMap = Map.of(
                "databases.test.tables.tr_live.partition_by", "tr_date_id",
                "databases.test.tables.tr_live.primary_key", "time2",
                "databases.test.tables.tr_live.settings", "allow_nullable_key=1",
                "databases.test.tables.employee3.partition_by", "tr_date_id",
                "databases.test.tables.employee3.primary_key", "id",
                "databases.test.tables.employee3.settings", "allow_nullable_key=1",
                "databases.dbo.tables.tr_live2.partition_by", "tr_date_id",
                "databases.dbo.tables.tr_live2.primary_key", "order_id",
                "databases.dbo.tables.tr_live2.settings", "allow_nullable_key=1",
                "replication.history.enable", "true"
        );
    }

    /**
     * Test to verify that the configuration for dbo.tr_live is loaded correctly.
     */
    @Test
    public void testDboTrLiveConfig() {
        SchemaOverrideConfig.Table tableConfig = config.getTableConfig("test", "test.employee3",configMap);
        assertNotNull(tableConfig, "dbo.tr_live configuration should not be null");
        assertEquals("tr_date_id", tableConfig.getPartitionBy(), "Partition key should match");
        assertEquals("id", tableConfig.getPrimaryKey(), "Primary key should match");
        assertEquals("allow_nullable_key=1", tableConfig.getSettings(), "Settings should match");
    }

    /**
     * Test to verify that the configuration for dbo.tr_live2 is loaded correctly.
     */
    @Test
    public void testDboTrLive2Config() {
        SchemaOverrideConfig.Table tableConfig = config.getTableConfig("dbo", "tr_live2",configMap);
        assertNotNull(tableConfig, "dbo.tr_live2 configuration should not be null");
        // For dbo.tr_live2, partition_by and settings are not defined.
        assertEquals("tr_date_id",tableConfig.getPartitionBy(), "Partition key should be null");
        assertEquals("order_id", tableConfig.getPrimaryKey(), "Primary key should match");
        assertEquals("allow_nullable_key=1",tableConfig.getSettings(), "Settings should be null");
    }
}
