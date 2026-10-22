package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 §3.1.6: a bad zone property stops the config from being built, at construction, not per batch.
 */
public class ClickHouseSinkConnectorConfigTimeZoneTest {

    private static final String CH = ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString();
    private static final String SRC = ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString();

    /** Both mandatory keys set to UTC, then {@code key} replaced by {@code value}. */
    private static Map<String, String> with(String key, String value) {
        Map<String, String> props = new HashMap<>();
        props.put(CH, "UTC");
        props.put(SRC, "UTC");
        props.put(key, value);
        return props;
    }

    @Test
    public void unsetZonesRefuseConstructionNamingTheKey() {
        // An unset zone key is refused at construction (spec 07.03 section 3.1.2), with the key and a suggested value.
        ConfigException none = assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(new HashMap<>()));
        assertTrue(none.getMessage().contains(CH + " is not set"), none.getMessage());
        assertTrue(none.getMessage().contains(CH + "=UTC"), none.getMessage());
        ConfigException blankClickHouse = assertThrows(ConfigException.class,
                () -> new ClickHouseSinkConnectorConfig(with(CH, "")));
        assertTrue(blankClickHouse.getMessage().contains(CH + " is not set"), blankClickHouse.getMessage());
        ConfigException blankSource = assertThrows(ConfigException.class,
                () -> new ClickHouseSinkConnectorConfig(with(SRC, "   ")));
        assertTrue(blankSource.getMessage().contains(SRC + " is not set"), blankSource.getMessage());
        Map<String, String> onlyClickHouse = new HashMap<>();
        onlyClickHouse.put(CH, "America/Chicago");
        ConfigException sourceMissing = assertThrows(ConfigException.class,
                () -> new ClickHouseSinkConnectorConfig(onlyClickHouse));
        assertTrue(sourceMissing.getMessage().contains(SRC + " is not set"), sourceMissing.getMessage());
    }

    @Test
    public void validZonesConstruct() {
        assertDoesNotThrow(() -> new ClickHouseSinkConnectorConfig(with(CH, "America/Chicago")));
        assertDoesNotThrow(() -> new ClickHouseSinkConnectorConfig(with(SRC, "US/Central")));
        assertDoesNotThrow(() -> new ClickHouseSinkConnectorConfig(with(SRC, "+05:30")));
    }

    @Test
    public void badClickHouseZoneIsAConfigException() {
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(with(CH, "EST")));
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(with(CH, "+05:30")));
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(with(CH, "UTC+5")));
    }

    @Test
    public void badSourceZoneIsAConfigException() {
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(with(SRC, "CDT")));
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(with(SRC, "america/chicago")));
    }

    @Test
    public void untrimmedZoneIsAConfigException() {
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(with(CH, " America/Chicago")));
    }

    @Test
    public void misspelledSourceKeyIsAConfigExceptionNamingTheRightKey() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> new ClickHouseSinkConnectorConfig(with("database.connectionTimezone", "US/Central")));
        assertTrue(e.getMessage().contains(SRC), e.getMessage());
    }
}
