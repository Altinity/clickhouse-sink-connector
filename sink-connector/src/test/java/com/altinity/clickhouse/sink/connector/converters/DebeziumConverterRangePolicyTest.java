package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.format.BinaryStreamUtils;
import io.debezium.time.Date;
import io.debezium.time.Timestamp;
import org.apache.kafka.connect.data.Schema;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 section 3.3: by default ({@code clamp.out.of.range=true}) a
 * value outside the ClickHouse type's range is saturated to the bound and
 * logged at DEBUG only -- never at WARN, which flooded the log on bitemporal
 * schemas (one line per row, then one per column per minute, both measured
 * in production); with {@code clamp.out.of.range=false} the batch fails with
 * an error naming the column, the value and the bounds.
 *
 * <p>Before this, {@code DATETIME '9999-12-31 23:59:59'} -- the customary
 * open-ended sentinel -- was written as {@code 2299-12-31 23:59:59} with no
 * log line and a successful batch.</p>
 */
public class DebeziumConverterRangePolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Debezium encodes DATETIME digits as a UTC epoch; this is that encoding. */
    private static long datetimeMillis(LocalDateTime digits) {
        return digits.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** A configuration that sets {@code clamp.out.of.range} explicitly. */
    // database.connectionTimeZone is pinned to UTC so these tests exercise the range rule alone. An unset
    // value now means the default zone America/Chicago (spec 07.03 section 3.1.6), which shifts the
    // 9999-12-31 23:59:59 sentinel to year 10000 UTC and changes the instant the messages name.
    private static ClickHouseSinkConnectorConfig config(boolean clamp) {
        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", Boolean.toString(clamp));
        props.put("database.connectionTimeZone", "UTC");
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    /** A configuration that leaves {@code clamp.out.of.range} to its default. */
    private static ClickHouseSinkConnectorConfig defaultConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("database.connectionTimeZone", "UTC");
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    /** Records whatever the mapper binds (setString / setDate / setObject). */
    private static PreparedStatement recording(AtomicReference<Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setString":
                case "setDate":
                case "setObject":
                case "setBigDecimal":
                    bound.set(args[1]);
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

    private static Object bindDatetime(ClickHouseSinkConnectorConfig config, LocalDateTime digits,
                                       ClickHouseDataType target) throws Exception {
        AtomicReference<Object> bound = new AtomicReference<>();
        ClickHouseDataTypeMapper.convert(Schema.Type.INT64, Timestamp.SCHEMA_NAME,
                datetimeMillis(digits), 1, recording(bound), config, target, UTC);
        return bound.get();
    }

    private static Object bindDate(ClickHouseSinkConnectorConfig config, LocalDate date,
                                   ClickHouseDataType target) throws Exception {
        AtomicReference<Object> bound = new AtomicReference<>();
        ClickHouseDataTypeMapper.convert(Schema.Type.INT32, Date.SCHEMA_NAME,
                (int) date.toEpochDay(), 1, recording(bound), config, target, UTC);
        return bound.get();
    }

    /**
     * The production default through the mapper: the MySQL sentinel
     * 9999-12-31 23:59:59 does not fit DateTime64 and is stored as the bound,
     * 2299-12-31 23:59:59 -- the value every earlier release stored, so an
     * upgrade never stops replication on it -- and it is not a WARN.
     * An absent configuration means the default too.
     */
    @Test
    @DisplayName("Default: an out-of-range DATETIME is saturated at the mapper, with one WARN")
    public void defaultPolicySaturatesOutOfRangeDatetimeAtTheMapper() throws Exception {
        LossyConversionReporter.resetForTests();
        CapturingAppender appender = captureReporter();
        try {
            LocalDateTime sentinel = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
            assertTrue(DebeziumConverter.RangePolicy.of(defaultConfig(), "db.orders.expires_at").clamps(),
                    "an empty configuration saturates");
            assertTrue(DebeziumConverter.RangePolicy.of(null, "db.orders.expires_at").clamps(),
                    "no configuration at all saturates");

            assertEquals("2299-12-31 23:59:59.999",
                    bindDatetime(defaultConfig(), sentinel, ClickHouseDataType.DateTime64),
                    "the default stores the DateTime64 bound");
            List<String> warnings = appender.warnings();
            assertEquals(1, warnings.size(), "a saturation under the default is one WARN per column: " + warnings);
            assertTrue(warnings.get(0).contains("9999-12-31T23:59:59Z"), warnings.get(0));

            // In range: unchanged, and no further WARN.
            assertEquals("2024-05-06 07:08:09.000",
                    bindDatetime(defaultConfig(), LocalDateTime.of(2024, 5, 6, 7, 8, 9), ClickHouseDataType.DateTime64));
            assertEquals(1, appender.warnings().size(), "an in-range value is not reported");
        } finally {
            releaseReporter(appender);
        }
    }

    @Test
    @DisplayName("Default: an out-of-range DATE is saturated at the mapper")
    public void defaultPolicySaturatesOutOfRangeDateAtTheMapper() throws Exception {
        assertEquals("2149-06-06",
                bindDate(defaultConfig(), LocalDate.of(9999, 12, 31), ClickHouseDataType.Date),
                "9999-12-31 exceeds Date (max 2149-06-06) and is stored as the bound");
        assertEquals("1900-01-01",
                bindDate(defaultConfig(), LocalDate.of(1000, 1, 1), ClickHouseDataType.Date32),
                "1000-01-01 precedes Date32 (min 1900-01-01) and is stored as the bound");
        assertEquals("2024-05-06",
                bindDate(defaultConfig(), LocalDate.of(2024, 5, 6), ClickHouseDataType.Date));
    }

    /**
     * Rule 1, opted into: with clamp.out.of.range=false the sentinel must fail
     * the batch, not become 2299-12-31 23:59:59.
     */
    @Test
    @DisplayName("clamp.out.of.range=false: an out-of-range DATETIME fails at the mapper instead of being saturated")
    public void strictSettingRejectsOutOfRangeDatetimeAtTheMapper() throws Exception {
        LocalDateTime sentinel = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        AtomicReference<Object> bound = new AtomicReference<>();

        RuntimeException e = assertThrows(RuntimeException.class, () -> ClickHouseDataTypeMapper.convert(
                Schema.Type.INT64, Timestamp.SCHEMA_NAME, datetimeMillis(sentinel), 1,
                recording(bound), config(false), ClickHouseDataType.DateTime64, UTC),
                "9999-12-31 23:59:59 exceeds DateTime64 (max 2299-12-31 23:59:59); writing the bound "
                        + "stores a value MySQL never held");
        assertTrue(e.getMessage().contains("9999-12-31"), e.getMessage());
        assertTrue(e.getMessage().contains("2299-12-31"), e.getMessage());
        assertTrue(e.getMessage().contains("clamp.out.of.range"), e.getMessage());
        assertNull(bound.get(), "nothing may be bound for a rejected value");

        // In range: unchanged.
        assertEquals("2024-05-06 07:08:09.000",
                bindDatetime(config(false), LocalDateTime.of(2024, 5, 6, 7, 8, 9), ClickHouseDataType.DateTime64));
    }

    @Test
    @DisplayName("clamp.out.of.range=false: an out-of-range DATE fails at the mapper instead of being saturated")
    public void strictSettingRejectsOutOfRangeDateAtTheMapper() throws Exception {
        assertThrows(RuntimeException.class,
                () -> bindDate(config(false), LocalDate.of(9999, 12, 31), ClickHouseDataType.Date),
                "9999-12-31 exceeds Date (max 2149-06-06)");
        assertThrows(RuntimeException.class,
                () -> bindDate(config(false), LocalDate.of(1000, 1, 1), ClickHouseDataType.Date32),
                "1000-01-01 precedes Date32 (min 1900-01-01)");

        assertEquals("2024-05-06",
                bindDate(config(false), LocalDate.of(2024, 5, 6), ClickHouseDataType.Date));
    }

    /** Collects everything DebeziumConverter logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-range-policy", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<String> warnings() {
            return atLevel(Level.WARN);
        }

        List<String> atLevel(Level level) {
            List<String> out = new ArrayList<>();
            for (LogEvent e : events) {
                if (e.getLevel() == level) {
                    out.add(e.getMessage().getFormattedMessage());
                }
            }
            return out;
        }
    }

    private static CapturingAppender capture() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(DebeziumConverter.class)).addAppender(appender);
        return appender;
    }

    private static void release(CapturingAppender appender) {
        ((Logger) LogManager.getLogger(DebeziumConverter.class)).removeAppender(appender);
        appender.stop();
    }

    /** Collects what the lossy-conversion reporter logs during one call. */
    private static CapturingAppender captureReporter() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(LossyConversionReporter.class)).addAppender(appender);
        return appender;
    }

    private static void releaseReporter(CapturingAppender appender) {
        ((Logger) LogManager.getLogger(LossyConversionReporter.class)).removeAppender(appender);
        appender.stop();
    }

    /**
     * Rule 2 (spec 07.03 section 3.1.14): a saturation is stored as the bound and
     * is loud, not silent. A column whose every row carries the open-ended
     * sentinel (1,000 saturations here), plus a second column, write exactly one
     * WARN each, naming the column and both values; every saturation is counted
     * under kind range_clamp. The earlier revision (WARN silent, detail at DEBUG)
     * hid a lossy conversion from production logs.
     */
    @Test
    @DisplayName("clamp.out.of.range=true saturates loudly: one WARN per column, every saturation counted")
    public void clampSettingSaturatesLoudlyOncePerColumn() throws Exception {
        LossyConversionReporter.resetForTests();
        CapturingAppender appender = captureReporter();
        try {
            DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "db.trades.valid_to");
            assertTrue(policy.clamps());
            long sentinel = datetimeMillis(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
            for (int i = 0; i < 1000; i++) {
                assertEquals("2299-12-31 23:59:59.999", DebeziumConverter.TimestampConverter.convert(
                        sentinel, ClickHouseDataType.DateTime64, UTC, UTC, null, policy));
            }
            DebeziumConverter.RangePolicy other = DebeziumConverter.RangePolicy.of(config(true), "db.orders.expires_at");
            LocalDate date = DebeziumConverter.DateConverter.convert(
                    (int) LocalDate.of(9999, 12, 31).toEpochDay(), ClickHouseDataType.Date32, other);
            assertEquals(LocalDate.parse("2299-12-31"), date);

            assertEquals(1001L, LossyConversionReporter.count(LossyConversionReporter.Kind.RANGE_CLAMP),
                    "every saturation is counted");
            List<String> warns = appender.warnings();
            assertEquals(2, warns.size(), "one WARN per column: " + warns);
            assertTrue(warns.get(0).contains("db.trades.valid_to"), warns.get(0));
            assertTrue(warns.get(0).contains("9999-12-31T23:59:59Z"), warns.get(0));
            assertTrue(warns.get(0).contains("2299-12-31 23:59:59"), warns.get(0));
            assertTrue(warns.get(1).contains("db.orders.expires_at"), warns.get(1));

            // The mapper honours the setting too.
            assertEquals("2299-12-31 23:59:59.999",
                    bindDatetime(config(true), LocalDateTime.of(9999, 12, 31, 23, 59, 59), ClickHouseDataType.DateTime64));
        } finally {
            releaseReporter(appender);
        }
    }

    /** Rule 1: the exception names the column, the value, the type and its bounds. */
    @Test
    @DisplayName("Strict policy names column, value and bounds for every bounded type")
    public void strictPolicyNamesColumnValueAndBounds() {
        DebeziumConverter.RangePolicy strict = DebeziumConverter.RangePolicy.of(config(false), "db.t.c");
        assertTrue(!strict.clamps(), "clamp.out.of.range=false is strict");

        long farFuture = datetimeMillis(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
        DebeziumConverter.ValueOutOfRangeException dt64 = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.TimestampConverter.convert(farFuture, ClickHouseDataType.DateTime64, UTC, UTC, null, strict));
        assertTrue(dt64.getMessage().contains("db.t.c"), dt64.getMessage());
        assertTrue(dt64.getMessage().contains("DateTime64"), dt64.getMessage());
        assertTrue(dt64.getMessage().contains("1900-01-01 00:00:00.000000 .. 2299-12-31 23:59:59.999999"), dt64.getMessage());

        long before1970 = datetimeMillis(LocalDateTime.of(1960, 1, 1, 0, 0, 0));
        DebeziumConverter.ValueOutOfRangeException dt32 = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.TimestampConverter.convert(before1970, ClickHouseDataType.DateTime, UTC, UTC, null, strict));
        assertTrue(dt32.getMessage().contains("1970-01-01 00:00:00 .. 2106-02-07 06:28:15"), dt32.getMessage());

        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.MicroTimestampConverter.convert(farFuture * 1000L, UTC, UTC,
                        ClickHouseDataType.DateTime64, null, strict));

        DebeziumConverter.ValueOutOfRangeException date = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.DateConverter.convert((int) LocalDate.of(2200, 1, 1).toEpochDay(),
                        ClickHouseDataType.Date, strict));
        assertTrue(date.getMessage().contains("2200-01-01"), date.getMessage());
        assertTrue(date.getMessage().contains("1970-01-01 .. 2149-06-06"), date.getMessage());

        DebeziumConverter.ValueOutOfRangeException date32 = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.DateConverter.convert((int) LocalDate.of(9999, 12, 31).toEpochDay(),
                        ClickHouseDataType.Date32, strict));
        assertTrue(date32.getMessage().contains("1900-01-01 .. 2299-12-31"), date32.getMessage());

        DebeziumConverter.ValueOutOfRangeException zoned = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.ZonedTimestampConverter.convert("2338-01-19T03:14:07.99Z", UTC, strict));
        assertTrue(zoned.getMessage().contains("2338-01-19T03:14:07.990Z"), zoned.getMessage());
        // The DateTime64 (epoch text) overload bounds the instant the same way.
        DebeziumConverter.ValueOutOfRangeException zonedEpoch = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.ZonedTimestampConverter.convert("2338-01-19T03:14:07.99Z", UTC,
                        ClickHouseDataType.DateTime64, strict));
        assertTrue(zonedEpoch.getMessage().contains("2338-01-19T03:14:07.990Z"), zonedEpoch.getMessage());

        DebeziumConverter.ValueOutOfRangeException decimal = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> new DebeziumConverter.BigDecimalConverter().truncate(
                        BinaryStreamUtils.DECIMAL128_MAX.add(BigDecimal.ONE), strict));
        assertTrue(decimal.getMessage().contains("Decimal128"), decimal.getMessage());

        // In-range values pass through every bounded converter untouched.
        assertEquals("2021-12-31 19:01:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2021-12-31T19:01:00Z", UTC, strict));
        assertEquals(BigDecimal.TEN, new DebeziumConverter.BigDecimalConverter().truncate(BigDecimal.TEN, strict));
    }

    /**
     * Rule 3 (spec 07.03 section 3.1.14): the policy-less overloads still saturate, and they
     * report like every other saturation, against the column "(unknown column)".
     * The four conversions below share that label, so one WARN is written.
     */
    @Test
    @DisplayName("Legacy overloads saturate and report as unknown-column saturations: one WARN, all counted")
    public void legacyOverloadsSaturateLoudly() {
        LossyConversionReporter.resetForTests();
        CapturingAppender appender = captureReporter();
        try {
            long farFuture = datetimeMillis(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
            assertEquals("2299-12-31 23:59:59.999",
                    DebeziumConverter.TimestampConverter.convert(farFuture, ClickHouseDataType.DateTime64, UTC, UTC));
            assertEquals(LocalDate.parse("2149-06-06"),
                    DebeziumConverter.DateConverter.convert((int) LocalDate.of(2200, 1, 1).toEpochDay(), ClickHouseDataType.Date));
            assertEquals("2299-12-31 23:59:59.999999",
                    DebeziumConverter.ZonedTimestampConverter.convert("2338-01-19T03:14:07.99Z", UTC));
            assertEquals(BinaryStreamUtils.DECIMAL128_MAX,
                    new DebeziumConverter.BigDecimalConverter().truncate(BinaryStreamUtils.DECIMAL128_MAX.add(BigDecimal.ONE)));

            List<String> warnings = appender.warnings();
            assertEquals(1, warnings.size(), "one WARN for the unknown-column saturations: " + warnings);
            assertTrue(warnings.get(0).contains("(unknown column)"), warnings.get(0));
            assertEquals(4L, LossyConversionReporter.count(LossyConversionReporter.Kind.RANGE_CLAMP),
                    "each of the four saturations is counted");
        } finally {
            releaseReporter(appender);
        }
    }

    /** Rule 4: PostgreSQL infinity has no finite value to lose; it keeps its saturation under strict too. */
    @Test
    @DisplayName("PostgreSQL infinity literals keep their documented saturation under the strict policy")
    public void infinityKeepsItsSaturation() {
        DebeziumConverter.RangePolicy strict = DebeziumConverter.RangePolicy.STRICT;
        assertEquals("2299-12-31 23:59:59.999999",
                DebeziumConverter.ZonedTimestampConverter.convert("infinity", UTC, strict));
        assertEquals("1900-01-01 00:00:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("-infinity", UTC, strict));
    }

    @Test
    @DisplayName("A DATE survives a JVM zone whose midnight does not exist: Pacific/Apia skipped 2011-12-30 (Spec 07.03 section 3.1.7)")
    public void dateSurvivesAJvmZoneThatSkipsItsDay() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"));
            int day = Math.toIntExact(LocalDate.of(2011, 12, 30).toEpochDay());
            assertEquals(LocalDate.of(2011, 12, 30),
                    DebeziumConverter.DateConverter.convert(day, ClickHouseDataType.Date32));
            assertEquals("2011-12-30",
                    DebeziumConverter.DateConverter.convert(day, ClickHouseDataType.Date).toString());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("The DateTime64 upper bound is the measured bound of each scale (Spec 07.03 section 3.1.8)")
    public void dateTime64MaxIsTheMeasuredBoundOfEachScale() {
        assertEquals(Instant.ofEpochSecond(DataTypeRange.DATETIME64_MAX, 0), DataTypeRange.dateTime64Max(0));
        assertEquals(Instant.ofEpochSecond(DataTypeRange.DATETIME64_MAX, 999_000_000), DataTypeRange.dateTime64Max(3));
        assertEquals(Instant.ofEpochSecond(DataTypeRange.DATETIME64_MAX, 999_999_000), DataTypeRange.dateTime64Max(6));
        assertEquals(Instant.ofEpochSecond(DataTypeRange.DATETIME64_MAX, 999_999_990), DataTypeRange.dateTime64Max(8));
        // Scale 9 is capped by the Int64 nanosecond count: 2262-04-11 23:47:16.854775807
        assertEquals(Instant.ofEpochSecond(9_223_372_036L, 854_775_807L), DataTypeRange.dateTime64Max(9));
        assertEquals(Long.MAX_VALUE,
                Math.addExact(Math.multiplyExact(9_223_372_036L, 1_000_000_000L), 854_775_807L));
        assertEquals(DataTypeRange.dateTime64Max(6), DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64);
        assertThrows(IllegalArgumentException.class, () -> DataTypeRange.dateTime64Max(10));
        assertThrows(IllegalArgumentException.class, () -> DataTypeRange.dateTime64Max(-1));
    }

    @Test
    @DisplayName("The DateTime64 bound strings the integration tests compare against are the bound of their scale")
    public void dateTime64MaxStringConstantsMatchTheBoundOfEachScale() {
        String[] constants = {DataTypeRange.DATETIME_MAX, DataTypeRange.DATETIME_1_MAX, DataTypeRange.DATETIME_2_MAX,
                DataTypeRange.DATETIME_3_MAX, DataTypeRange.DATETIME_4_MAX, DataTypeRange.DATETIME_5_MAX,
                DataTypeRange.DATETIME_6_MAX};
        for (int scale = 0; scale < constants.length; scale++) {
            // Timestamp.valueOf(LocalDateTime) and Timestamp#toString do not read the JVM default zone.
            String rendered = java.sql.Timestamp.valueOf(
                    LocalDateTime.ofInstant(DataTypeRange.dateTime64Max(scale), ZoneOffset.UTC)).toString();
            assertEquals(rendered, constants[scale], "scale " + scale);
        }
        assertEquals(DataTypeRange.DATETIME_6_MAX, DataTypeRange.DATETIME64_6_MAX);
    }

    @Test
    @DisplayName("A DateTime64 value above its scale's bound saturates to that bound, rendered at the bound's precision (Spec 07.03 section 3.1.8)")
    public void dateTime64ClampsToTheBoundOfItsScale() throws Exception {
        long sentinel = datetimeMillis(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
        assertEquals("2299-12-31 23:59:59.000", DebeziumConverter.TimestampConverter.convert(sentinel,
                ClickHouseDataType.DateTime64, UTC, UTC, null,
                DebeziumConverter.RangePolicy.of(config(true), "db.t.c", 0)));
        assertEquals("2299-12-31 23:59:59.999", DebeziumConverter.TimestampConverter.convert(sentinel,
                ClickHouseDataType.DateTime64, UTC, UTC, null,
                DebeziumConverter.RangePolicy.of(config(true), "db.t.c", 3)));

        // Scale 9: the bound is 2262-04-11 23:47:16.854775807. The millisecond rendering
        // truncates it to .854, which is below the bound, so ClickHouse accepts it.
        AtomicReference<Object> bound = new AtomicReference<>();
        ClickHouseDataTypeMapper.convert(Schema.Type.INT64, Timestamp.SCHEMA_NAME, sentinel, 1,
                recording(bound), config(true), ClickHouseDataType.DateTime64, UTC, null,
                DebeziumConverter.RangePolicy.of(config(true), "db.t.c", 9));
        assertEquals("2262-04-11 23:47:16.854", bound.get(),
                "DateTime64(9) must not receive 2299-12-31, which raises DECIMAL_OVERFLOW (code 407)");
    }

    @Test
    @DisplayName("A DateTime64 instant in the last second of 2299 keeps its fraction; the bound used to zero it (Spec 07.03 section 3.1.8)")
    public void dateTime64BoundIsNotZeroedFraction() {
        // In range at the default scale: kept exactly. The millisecond test with a
        // seconds-only bound stored 23:59:59.000 for every fraction above zero.
        assertEquals("2299-12-31 23:59:59.500000",
                DebeziumConverter.ZonedTimestampConverter.convert("2299-12-31T23:59:59.5Z", UTC));
        assertEquals("2299-12-31 23:59:59.999999",
                DebeziumConverter.ZonedTimestampConverter.convert("2299-12-31T23:59:59.999999Z", UTC));
        // Above the bound: saturated to the bound of the scale, .999999 at the default scale.
        assertEquals("2299-12-31 23:59:59.999999",
                DebeziumConverter.ZonedTimestampConverter.convert("2338-01-19T03:14:07.99Z", UTC));
    }

    @Test
    @DisplayName("A column's declared DateTime64 scale is read from its type; the default is 6 (Spec 07.03 section 3.1.8)")
    public void dateTime64ScaleIsReadFromTheDeclaredType() {
        assertEquals(3, ClickHouseDataTypeMapper.dateTime64Scale("DateTime64(3)"));
        assertEquals(9, ClickHouseDataTypeMapper.dateTime64Scale("Nullable(DateTime64(9, 'UTC'))"));
        assertEquals(0, ClickHouseDataTypeMapper.dateTime64Scale("DateTime64(0)"));
        assertEquals(6, ClickHouseDataTypeMapper.dateTime64Scale("DateTime64(6)"));
        assertEquals(6, ClickHouseDataTypeMapper.dateTime64Scale("DateTime"));
        assertEquals(6, ClickHouseDataTypeMapper.dateTime64Scale(null));
        assertThrows(IllegalArgumentException.class, () -> ClickHouseDataTypeMapper.dateTime64Scale("DateTime64(10)"));
        assertThrows(IllegalArgumentException.class,
                () -> DebeziumConverter.RangePolicy.of(config(true), "db.t.c", 10));
        assertEquals(9, DebeziumConverter.RangePolicy.of(config(true), "db.t.c", 9).scale());
    }
}
