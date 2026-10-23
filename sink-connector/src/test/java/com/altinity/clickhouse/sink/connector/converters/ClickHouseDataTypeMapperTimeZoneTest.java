package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 sections 3.1.2 and 3.1.3: how the source zone and the column
 * zone are resolved before a temporal value is rendered.
 */
public class ClickHouseDataTypeMapperTimeZoneTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static ClickHouseSinkConnectorConfig config(String sourceZone) {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.datetime.timezone", "UTC");
        if (sourceZone != null) {
            props.put("database.connectionTimeZone", sourceZone);
        }
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("An unset database.connectionTimeZone is refused when the config is built; there is no session-zone default")
    public void unsetSourceZoneIsRefused() {
        for (String unset : new String[]{null, "", "   "}) {
            ConfigException refused = assertThrows(ConfigException.class, () -> config(unset));
            assertTrue(refused.getMessage().contains("database.connectionTimeZone"), refused.getMessage());
            assertTrue(refused.getMessage().contains("database.connectionTimeZone=UTC"), refused.getMessage());
        }
    }

    @Test
    @DisplayName("A configured source zone is used as given")
    public void configuredSourceZoneIsUsed() {
        assertEquals(ZoneId.of("UTC"), ClickHouseDataTypeMapper.resolveSourceTimeZone(config("UTC")));
        assertEquals(ZoneId.of("Europe/London"),
                ClickHouseDataTypeMapper.resolveSourceTimeZone(config("Europe/London")));
    }

    @Test
    @DisplayName("An unparseable source zone is refused when the config is built (spec 07.03 §3.1.6), not per batch")
    public void garbageSourceZoneThrows() {
        assertThrows(ConfigException.class, () -> config("Not/AZone"));
        assertThrows(ConfigException.class, () -> config("+5"));
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

    @Test
    @DisplayName("Two zone ids with equal rules are one zone: US/Central renders exactly like America/Chicago (Spec 07.03 section 3.1.13)")
    public void sameRulesDifferentIdIsSameZone() {
        ZoneId us = ZoneId.of("US/Central");
        assertTrue(DebeziumConverter.sameZone(us, CHICAGO), "equal rules, different ids");
        assertTrue(DebeziumConverter.sameZone(CHICAGO, CHICAGO));
        assertFalse(DebeziumConverter.sameZone(CHICAGO, ZoneId.of("Asia/Kolkata")));

        // 2026-03-08 02:30 is a spring-forward gap time in Chicago. Debezium encoded the
        // zone-less DATETIME digits as a UTC epoch; the same-zone decode resolves the gap with the offset
        // before the transition (02:30 CST = 08:30 UTC) and binds that instant as epoch text.
        long encodedDigits = LocalDateTime.of(2026, 3, 8, 2, 30).toInstant(ZoneOffset.UTC).toEpochMilli();
        String canonical = DebeziumConverter.TimestampConverter.convert(encodedDigits,
                ClickHouseDataType.DateTime64, CHICAGO, CHICAGO, null);
        String alias = DebeziumConverter.TimestampConverter.convert(encodedDigits,
                ClickHouseDataType.DateTime64, us, CHICAGO, null);
        assertEquals("1772958600.000000", canonical);
        assertEquals(canonical, alias, "the alias must not take the different-zones branch (shift by the source offset)");

        long encodedMicros = encodedDigits * 1000L;
        assertEquals(DebeziumConverter.MicroTimestampConverter.convert(encodedMicros, CHICAGO, CHICAGO,
                        ClickHouseDataType.DateTime64, null),
                DebeziumConverter.MicroTimestampConverter.convert(encodedMicros, us, CHICAGO,
                        ClickHouseDataType.DateTime64, null));
    }
}
