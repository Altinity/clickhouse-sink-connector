package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 07.03 sections 3.1.2 and 3.1.3: how the source zone and the column
 * zone are resolved before a temporal value is rendered.
 */
public class ClickHouseDataTypeMapperTimeZoneTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static ClickHouseSinkConnectorConfig config(String sourceZone) {
        Map<String, String> props = new HashMap<>();
        if (sourceZone != null) {
            props.put("database.connectionTimeZone", sourceZone);
        }
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("An empty database.connectionTimeZone resolves to the session zone, not UTC")
    public void emptySourceZoneIsTheSessionZone() {
        assertEquals(CHICAGO, ClickHouseDataTypeMapper.resolveSourceTimeZone(config(null), CHICAGO),
                "with no declared source zone there is no basis for a shift: the digits MySQL holds are stored");
        assertEquals(CHICAGO, ClickHouseDataTypeMapper.resolveSourceTimeZone(config(""), CHICAGO));
    }

    @Test
    @DisplayName("A configured source zone is used as given")
    public void configuredSourceZoneIsUsed() {
        assertEquals(ZoneId.of("UTC"), ClickHouseDataTypeMapper.resolveSourceTimeZone(config("UTC"), CHICAGO));
        assertEquals(ZoneId.of("Europe/London"),
                ClickHouseDataTypeMapper.resolveSourceTimeZone(config("Europe/London"), CHICAGO));
    }

    @Test
    @DisplayName("An unparseable source zone fails loudly rather than being guessed")
    public void garbageSourceZoneThrows() {
        assertThrows(DateTimeException.class,
                () -> ClickHouseDataTypeMapper.resolveSourceTimeZone(config("Not/AZone"), CHICAGO));
    }

    @Test
    @DisplayName("The zone declared by the column type is parsed; none declared yields null")
    public void columnTimeZoneIsParsedFromTheType() {
        assertEquals(ZoneId.of("UTC"), ClickHouseDataTypeMapper.columnTimeZone("Nullable(DateTime64(3, 'UTC'))"));
        assertEquals(ZoneId.of("UTC"), ClickHouseDataTypeMapper.columnTimeZone("DateTime64(6,'UTC')"));
        assertEquals(ZoneId.of("Europe/London"), ClickHouseDataTypeMapper.columnTimeZone("DateTime('Europe/London')"));
        assertNull(ClickHouseDataTypeMapper.columnTimeZone("DateTime64(6)"),
                "a column without a declared zone is parsed by ClickHouse in the session zone");
        assertNull(ClickHouseDataTypeMapper.columnTimeZone("DateTime"));
        assertNull(ClickHouseDataTypeMapper.columnTimeZone("String"));
        assertNull(ClickHouseDataTypeMapper.columnTimeZone(null));
        assertNull(ClickHouseDataTypeMapper.columnTimeZone(""));
    }
}
