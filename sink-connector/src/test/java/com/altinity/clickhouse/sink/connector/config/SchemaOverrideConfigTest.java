package com.altinity.clickhouse.sink.connector.config;

import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SchemaOverrideConfigTest is a test class that validates the functionality of
 * SchemaOverrideConfig. It loads the configuration from a YAML file and verifies that
 * the lookup by database and table name returns the correct configuration.
 */
public class SchemaOverrideConfigTest {

    private SchemaOverrideConfig config;

    /**
     * Set up a new instance of SchemaOverrideConfig and load the YAML configuration
     * before each test. The file is loaded from the classpath (resources).
     */
    @BeforeEach
    public void setUp() throws IOException {
        config = new SchemaOverrideConfig();
        // Specify the path to the YAML file relative to the resources folder
        String filePath = "config_schema_override.yml";  // File is inside src/main/resources
        config.loadTableConfigs(filePath);
    }

    /**
     * Test to verify that the configuration for dbo.tr_live is loaded correctly.
     */
    @Test
    public void testDboTrLiveConfig() {
        SchemaOverrideConfig.Table tableConfig = config.getTableConfig("test", "employee3");
        assertNotNull(tableConfig, "dbo.tr_live configuration should not be null");
        assertEquals("tr_date_id", tableConfig.getPartitionBy(), "Partition key should match");
        assertEquals("gmt_time", tableConfig.getPrimaryKey(), "Primary key should match");
        assertEquals("allow_nullable_key=1", tableConfig.getSettings(), "Settings should match");
    }


    /**
     * Test to verify that requesting a non-existent configuration returns null.
     */
    @Test
    public void testNonExistentConfig() {
        SchemaOverrideConfig.Table tableConfig = config.getTableConfig("nonexistent_db", "nonexistent_table");
        assertNull(tableConfig, "Non-existent configuration should return null");
    }
}
