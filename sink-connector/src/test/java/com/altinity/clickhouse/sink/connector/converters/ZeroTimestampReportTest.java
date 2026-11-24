package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 section 3.1.15: a MySQL TIMESTAMP that arrives as the instant 1970-01-01 00:00:00 UTC exactly is the
 * zero TIMESTAMP (the smallest real value is 1970-01-01 00:00:01 UTC). It has no ClickHouse value of its own, so it is
 * stored as the epoch and reported (counted and logged by default, refused under strict mode). A PostgreSQL
 * timestamptz at the epoch is a real value and is never reported.
 */
class ZeroTimestampReportTest {

    private static final String COLUMN = "shop.orders.created";
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @BeforeEach
    void resetCounts() {
        LossyConversionReporter.resetForTests();
    }

    private static ClickHouseSinkConnectorConfig config(boolean clamp) {
        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", Boolean.toString(clamp));
        props.put("database.connectionTimeZone", "UTC");
        props.put("clickhouse.datetime.timezone", "UTC");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static DebeziumConverter.RangePolicy policy(boolean clamp) {
        return DebeziumConverter.RangePolicy.of(config(clamp), COLUMN);
    }

    private static long count() {
        return LossyConversionReporter.count(LossyConversionReporter.Kind.ZERO_TIMESTAMP);
    }

    private static String bind(String text, ZoneId zone, ClickHouseDataType target, boolean mysqlTimestamp) {
        return DebeziumConverter.ZonedTimestampConverter.convert(text, zone, target, policy(true), mysqlTimestamp);
    }

    @Test
    void zeroTimestampIsCountedAndTheBoundTextIsUnchanged() {
        String reported = bind("1970-01-01T00:00:00Z", UTC, ClickHouseDataType.DateTime64, true);

        assertEquals(1L, count());
        assertEquals(bind("1970-01-01T00:00:00Z", UTC, ClickHouseDataType.DateTime64, false), reported,
                "the report does not change what is bound; the value is stored as the epoch");
        assertEquals(1L, count(), "the unreported bind used for comparison must not count");
    }

    @Test
    void theInstantDecidesNotTheText() {
        // Four renderings of the instant 1970-01-01 00:00:00 UTC.
        bind("1970-01-01T00:00:00Z", UTC, ClickHouseDataType.DateTime64, true);
        bind("1970-01-01T00:00:00.000000Z", UTC, ClickHouseDataType.DateTime64, true);
        bind("1969-12-31T18:00:00-06:00", UTC, ClickHouseDataType.DateTime64, true);
        bind("1970-01-01T05:30:00+05:30", UTC, ClickHouseDataType.DateTime64, true);

        assertEquals(4L, count());
    }

    @Test
    void theSmallestRealTimestampIsNotReported() {
        // 1970-01-01 00:00:01 UTC is the smallest TIMESTAMP MySQL can hold; one microsecond is not it.
        bind("1970-01-01T00:00:01Z", UTC, ClickHouseDataType.DateTime64, true);
        bind("1970-01-01T00:00:00.000001Z", UTC, ClickHouseDataType.DateTime64, true);
        bind("1969-12-31T23:59:59Z", UTC, ClickHouseDataType.DateTime64, true);

        assertEquals(0L, count());
    }

    @Test
    void aValueFromAnotherSourceTypeIsNeverReported() {
        // PostgreSQL timestamptz at the epoch is a real value: the caller passes false for it.
        bind("1970-01-01T00:00:00Z", UTC, ClickHouseDataType.DateTime64, false);

        assertEquals(0L, count());
    }

    @Test
    void everyTargetTypeIsReported() {
        bind("1970-01-01T00:00:00Z", UTC, ClickHouseDataType.DateTime64, true);
        bind("1970-01-01T00:00:00Z", UTC, ClickHouseDataType.DateTime, true);
        bind("1970-01-01T00:00:00Z", UTC, ClickHouseDataType.String, true);
        bind("1970-01-01T00:00:00Z", CHICAGO, ClickHouseDataType.DateTime64, true);
        bind("1970-01-01T00:00:00Z", CHICAGO, ClickHouseDataType.DateTime, true);

        assertEquals(5L, count());
    }

    @Test
    void theOlderOverloadsNeverReport() {
        // The overloads without the flag keep their contract: no source type is known.
        DebeziumConverter.ZonedTimestampConverter.convert("1970-01-01T00:00:00Z", UTC);
        DebeziumConverter.ZonedTimestampConverter.convert("1970-01-01T00:00:00Z", UTC, policy(true));
        DebeziumConverter.ZonedTimestampConverter.convert("1970-01-01T00:00:00Z", UTC,
                ClickHouseDataType.DateTime64, policy(true));

        assertEquals(0L, count());
    }

    @Test
    void strictModeRefusesTheValueNamingTheColumn() {
        DebeziumConverter.ValueOutOfRangeException refused = assertThrows(
                DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.ZonedTimestampConverter.convert("1970-01-01T00:00:00Z", UTC,
                        ClickHouseDataType.DateTime64, policy(false), true));

        assertTrue(refused.getMessage().contains(COLUMN), refused.getMessage());
        assertTrue(refused.getMessage().contains("0000-00-00 00:00:00"), refused.getMessage());
        assertEquals(0L, count(), "a refused value is not counted as stored");
    }

    @Test
    void strictModeDoesNotRefuseARealValue() {
        DebeziumConverter.ZonedTimestampConverter.convert("1970-01-01T00:00:01Z", UTC,
                ClickHouseDataType.DateTime64, policy(false), true);

        assertEquals(0L, count());
    }

    @Test
    void onlyTheWholeWordTimestampNamesAMysqlTimestamp() {
        assertTrue(ClickHouseDataTypeMapper.isMysqlTimestampSource(source("TIMESTAMP")));
        assertTrue(ClickHouseDataTypeMapper.isMysqlTimestampSource(source("timestamp")));
        assertTrue(ClickHouseDataTypeMapper.isMysqlTimestampSource(source("TIMESTAMP(6)")));
        assertTrue(ClickHouseDataTypeMapper.isMysqlTimestampSource(source(" Timestamp (3) ")));

        // PostgreSQL timestamptz is also delivered as io.debezium.time.ZonedTimestamp; it must not match.
        assertFalse(ClickHouseDataTypeMapper.isMysqlTimestampSource(source("timestamptz")));
        assertFalse(ClickHouseDataTypeMapper.isMysqlTimestampSource(source("TIMESTAMP WITH TIME ZONE")));
        assertFalse(ClickHouseDataTypeMapper.isMysqlTimestampSource(source("DATETIME")));
        assertFalse(ClickHouseDataTypeMapper.isMysqlTimestampSource(source("")));
        assertFalse(ClickHouseDataTypeMapper.isMysqlTimestampSource(new HashMap<>()));
        assertFalse(ClickHouseDataTypeMapper.isMysqlTimestampSource(null));
    }

    private static Map<String, String> source(String type) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, type);
        return parameters;
    }
}
