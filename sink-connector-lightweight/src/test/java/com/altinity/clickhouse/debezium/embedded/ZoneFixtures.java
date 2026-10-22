package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Test fixtures for the two mandatory zone keys (spec 07.03 section 3.1.2). The sink-connector test
 * fixture is not on this module's classpath, so this is the same helper for the lightweight tests. {@link #withZones}
 * returns a copy of the map with {@code UTC} for each zone key the map does not already set. A blank value stays
 * blank and is refused.
 */
public final class ZoneFixtures {

    private ZoneFixtures() {
    }

    public static Map<String, String> withZones(Map<String, String> props) {
        Map<String, String> copy = new HashMap<>(props);
        copy.putIfAbsent(TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE, TimeZoneResolver.SUGGESTED_VALUE);
        copy.putIfAbsent(TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE, TimeZoneResolver.SUGGESTED_VALUE);
        return copy;
    }

    /** The {@link Properties} form, for the connector properties that reach {@code DBMetadata(Properties)}. */
    public static Properties withZones(Properties props) {
        Properties copy = new Properties();
        copy.putAll(props);
        copy.putIfAbsent(TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE, TimeZoneResolver.SUGGESTED_VALUE);
        copy.putIfAbsent(TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE, TimeZoneResolver.SUGGESTED_VALUE);
        return copy;
    }
}
