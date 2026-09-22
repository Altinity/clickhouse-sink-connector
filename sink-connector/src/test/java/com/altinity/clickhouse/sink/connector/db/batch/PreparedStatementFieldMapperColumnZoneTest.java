package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import io.debezium.time.ZonedTimestamp;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec 07.03 sections 3.1.2 and 3.1.3, end to end through
 * {@link PreparedStatementFieldMapper#insertPreparedStatement}: temporal values
 * are formatted in the zone the target COLUMN declares, and an empty
 * {@code database.connectionTimeZone} means "the session zone", not UTC.
 *
 * <p>ClickHouse parses a DateTime literal in the column's zone. Measured with
 * {@code clickhouse local} 24.8.14: {@code '2022-01-01 10:00:00'} inserted into
 * {@code DateTime64(3,'UTC')} is 10:00 UTC, i.e. {@code 04:00:00} when read in
 * America/Chicago. The converters used to format every instant in the
 * session zone regardless of the column's zone, and to treat an empty source
 * zone as UTC, so on an America/Chicago session every auto-created ('UTC')
 * temporal column held a value off by the server offset.</p>
 *
 * <p>The recording {@link PreparedStatement} is a JDK {@link Proxy}; this
 * module has no mocking framework on its test classpath.</p>
 */
public class PreparedStatementFieldMapperColumnZoneTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("ts", SchemaBuilder.string().name(ZonedTimestamp.SCHEMA_NAME).optional().build())
            .field("dt3", SchemaBuilder.int64().name(Timestamp.SCHEMA_NAME).optional().build())
            .field("dt6", SchemaBuilder.int64().name(MicroTimestamp.SCHEMA_NAME).optional().build())
            .build();

    /** Debezium encodes DATETIME digits as a UTC epoch; this is that encoding. */
    private static long digitsAsUtcEpochMillis(LocalDateTime digits) {
        return digits.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static Struct row() {
        LocalDateTime tenOclock = LocalDateTime.of(2022, 1, 1, 10, 0, 0);
        return new Struct(ROW)
                .put("id", 1)
                .put("ts", "2022-01-01T16:00:00Z")
                .put("dt3", digitsAsUtcEpochMillis(tenOclock))
                .put("dt6", digitsAsUtcEpochMillis(tenOclock) * 1000L);
    }

    private static Map<String, Integer> indexMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("id", 1);
        m.put("ts", 2);
        m.put("dt3", 3);
        m.put("dt6", 4);
        return m;
    }

    private static Map<String, String> columns(String tsType, String dt3Type, String dt6Type) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("ts", tsType);
        m.put("dt3", dt3Type);
        m.put("dt6", dt6Type);
        return m;
    }

    /** Records every setString(index, value). */
    private static PreparedStatement recording(Map<Integer, String> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setString":
                    bound.put((Integer) args[0], (String) args[1]);
                    return null;
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    private static Map<Integer, String> bind(ClickHouseSinkConnectorConfig config,
                                             Map<String, String> columns) throws Exception {
        Struct after = row();
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");

        Map<Integer, String> bound = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", CHICAGO)
                .insertPreparedStatement(indexMap(), recording(bound), after.schema().fields(),
                        record, after, false, config, columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        return bound;
    }

    /**
     * Spec 07.03 section 3.1.3: a TIMESTAMP is an instant. Written into a
     * 'UTC' column through an America/Chicago session it must be rendered in
     * UTC, or ClickHouse stores an instant six hours early.
     */
    @Test
    @DisplayName("TIMESTAMP into a DateTime64(6,'UTC') column is formatted in UTC, not in the session zone")
    public void testTimestampIntoUtcColumnWhenSessionZoneIsChicago() throws Exception {
        Map<Integer, String> bound = bind(new ClickHouseSinkConnectorConfig(new HashMap<>()),
                columns("Nullable(DateTime64(6, 'UTC'))", "DateTime64(3, 'UTC')", "DateTime64(6, 'UTC')"));

        assertEquals("2022-01-01 16:00:00.000000", bound.get(2),
                "16:00Z rendered in the column's zone (UTC); the session zone (America/Chicago) "
                        + "would store 10:00 UTC, an instant six hours early");
    }

    /**
     * Spec 07.03 section 3.1.2: with no declared source zone there is no basis
     * for a shift; DATETIME digits are stored as MySQL holds them.
     */
    @Test
    @DisplayName("Empty database.connectionTimeZone keeps DATETIME digits (was: treated as UTC and shifted)")
    public void testEmptySourceZoneKeepsDatetimeDigits() throws Exception {
        Map<Integer, String> bound = bind(new ClickHouseSinkConnectorConfig(new HashMap<>()),
                columns("Nullable(DateTime64(6, 'UTC'))", "DateTime64(3, 'UTC')", "DateTime64(6, 'UTC')"));

        assertEquals("2022-01-01 10:00:00.000", bound.get(3),
                "DATETIME(3) digits must be stored unchanged when no source zone is configured");
        assertEquals("2022-01-01 10:00:00.00000000", bound.get(4),
                "DATETIME(6) digits must be stored unchanged when no source zone is configured");
    }

    /**
     * Spec 07.03 sections 3.1.2 / 3.1.3: an operator who declares a source
     * zone different from the session zone asks for a wall-clock shift. The
     * shifted instant is rendered in the column's zone when it declares one,
     * and in the session zone otherwise.
     */
    @Test
    @DisplayName("Explicit different source zone shifts DATETIME; the instant is rendered in the column zone")
    public void testExplicitSourceZoneShiftsIntoTheColumnZone() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put("database.connectionTimeZone", "UTC");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);

        Map<Integer, String> utcColumns = bind(config,
                columns("DateTime64(6, 'UTC')", "DateTime64(3, 'UTC')", "DateTime64(6, 'UTC')"));
        assertEquals("2022-01-01 10:00:00.000", utcColumns.get(3),
                "10:00 UTC wall time is the instant 10:00Z; rendered in the UTC column it is 10:00");
        assertEquals("2022-01-01 10:00:00.00000000", utcColumns.get(4));

        Map<Integer, String> sessionColumns = bind(config,
                columns("DateTime64(6)", "DateTime64(3)", "DateTime64(6)"));
        assertEquals("2022-01-01 04:00:00.000", sessionColumns.get(3),
                "a column without a declared zone is parsed by ClickHouse in the session zone, "
                        + "so the same instant is rendered as 04:00 America/Chicago");
        assertEquals("2022-01-01 04:00:00.00000000", sessionColumns.get(4));
        assertEquals("2022-01-01 10:00:00.000000", sessionColumns.get(2),
                "TIMESTAMP 16:00Z rendered in the session zone (America/Chicago) is 10:00");
    }
}
