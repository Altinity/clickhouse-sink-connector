package com.altinity.clickhouse.debezium.embedded.config;

import io.debezium.config.Configuration;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.TableId;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TableFilterConfigTest {
    private static final String INCLUDE = RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST.name();
    private static final String EXCLUDE = RelationalDatabaseConnectorConfig.TABLE_EXCLUDE_LIST.name();

    private static Properties filters(String include, String exclude) {
        Properties props = new Properties();
        if (include != null) {
            props.setProperty(INCLUDE, include);
        }
        if (exclude != null) {
            props.setProperty(EXCLUDE, exclude);
        }
        return props;
    }

    private static List<String> exclusionErrors(Properties props) {
        List<String> errors = new ArrayList<>();
        RelationalDatabaseConnectorConfig.TABLE_EXCLUDE_LIST.validate(
                Configuration.from(props), (field, value, problem) -> errors.add(problem));
        return errors;
    }

    @Test
    void redundantIncludeNoLongerPreventsTheRequestedExclusion() {
        Properties props = filters(".*", "app\\.blocked");
        assertFalse(exclusionErrors(props).isEmpty(), "pre-fix Debezium configuration must fail");
        assertTrue(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
        assertFalse(props.containsKey(INCLUDE));
        assertEquals("app\\.blocked", props.getProperty(EXCLUDE));
        assertTrue(exclusionErrors(props).isEmpty());
        props.setProperty("topic.prefix", "test");
        props.setProperty("database.hostname", "localhost");
        MySqlConnectorConfig config = new MySqlConnectorConfig(Configuration.from(props));
        assertFalse(config.getTableFilters().dataCollectionFilter().isIncluded(new TableId("app", null, "blocked")));
        assertTrue(config.getTableFilters().dataCollectionFilter().isIncluded(new TableId("app", null, "kept")));
    }

    @Test
    void restrictiveIncludeIsNeverSilentlyBroadened() {
        Properties props = filters("app\\.allowed", "app\\.blocked");
        Properties before = (Properties) props.clone();
        assertFalse(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
        assertEquals(before, props);
        assertFalse(exclusionErrors(props).isEmpty());
    }

    @Test
    void absentAndBlankExclusionsPreserveTheInclude() {
        for (String exclude : new String[] {null, "", " ", "\t\n"}) {
            Properties props = filters(".*", exclude);
            Properties before = (Properties) props.clone();
            assertFalse(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
            assertEquals(before, props);
        }
    }

    @Test
    void absentBlankAndOtherIncludesAreUnchanged() {
        for (String include : new String[] {null, "", " ", "app\\..*", "(?s).*", ".*,app\\.kept"}) {
            Properties props = filters(include, "app\\.blocked");
            Properties before = (Properties) props.clone();
            assertFalse(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
            assertEquals(before, props);
        }
    }

    @Test
    void whitespaceAroundTheUniversalIncludeIsAccepted() {
        Properties props = filters(" \t.*\n", "app\\.blocked");
        assertTrue(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
        assertNull(props.getProperty(INCLUDE));
    }

    @Test
    void malformedExclusionStillFailsDebeziumValidation() {
        Properties props = filters(".*", "[");
        assertTrue(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
        assertFalse(exclusionErrors(props).isEmpty());
    }

    @Test
    void unrelatedPropertiesArePreservedAndNormalizationIsIdempotent() {
        Properties props = filters(".*", "app\\.blocked");
        props.setProperty("database.include.list", "app");
        props.setProperty("offset.storage", "unchanged");
        assertTrue(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
        Properties normalized = (Properties) props.clone();
        assertFalse(TableFilterConfig.removeRedundantInclude(props, INCLUDE, EXCLUDE));
        assertEquals(normalized, props);
        assertEquals("app", props.getProperty("database.include.list"));
        assertEquals("unchanged", props.getProperty("offset.storage"));
    }
}
