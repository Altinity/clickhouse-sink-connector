package com.altinity.clickhouse.sink.connector;

import java.util.HashMap;
import java.util.Map;

/**
 * Test fixtures for the two mandatory zone keys (spec 07.03 section 3.1.2). Both keys have no default, so
 * a test that builds a {@link ClickHouseSinkConnectorConfig} from a map must carry them. {@link #withZones} returns a
 * copy of the map with {@code UTC} for each zone key the map does not already set. A test that needs another zone
 * sets it on the map first; a blank value stays blank and is refused.
 */
public final class ZoneFixtures {

    private ZoneFixtures() {
    }

    public static Map<String, String> withZones(Map<String, String> props) {
        Map<String, String> copy = new HashMap<>(props);
        copy.putIfAbsent(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString(), "UTC");
        copy.putIfAbsent(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString(), "UTC");
        return copy;
    }
}
