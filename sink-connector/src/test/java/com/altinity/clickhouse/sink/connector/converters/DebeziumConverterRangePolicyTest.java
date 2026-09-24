package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 section 3.3: a value outside the ClickHouse type's range is
 * never silently saturated. By default ({@code clamp.out.of.range=true}) the
 * value is saturated and a WARN names the column -- once per column per
 * window, the rest counted at DEBUG, never one line per row; with
 * {@code clamp.out.of.range=false} the batch fails with an error naming the
 * column, the value and the bounds.
 *
 * <p>Before this, {@code DATETIME '9999-12-31 23:59:59'} -- the customary
 * open-ended sentinel -- was written as {@code 2299-12-31 23:59:59} with no
 * log line and a successful batch.</p>
 */
public class DebeziumConverterRangePolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** The rate limit is process-wide; every test starts with fresh windows and the real clock. */
    @BeforeEach
    public void resetRateLimit() {
        DebeziumConverter.RangePolicy.resetSaturationTallies();
        DebeziumConverter.RangePolicy.ticker = System::nanoTime;
    }

    /** Debezium encodes DATETIME digits as a UTC epoch; this is that encoding. */
    private static long datetimeMillis(LocalDateTime digits) {
        return digits.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** A configuration that sets {@code clamp.out.of.range} explicitly. */
    private static ClickHouseSinkConnectorConfig config(boolean clamp) {
        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", Boolean.toString(clamp));
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** A configuration that leaves {@code clamp.out.of.range} to its default. */
    private static ClickHouseSinkConnectorConfig defaultConfig() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
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
     * upgrade never stops replication on it -- with a WARN, never silently.
     * An absent configuration means the default too.
     */
    @Test
    @DisplayName("Default: an out-of-range DATETIME is saturated at the mapper and reported at WARN")
    public void defaultPolicySaturatesOutOfRangeDatetimeAtTheMapper() throws Exception {
        CapturingAppender appender = capture();
        try {
            LocalDateTime sentinel = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
            assertTrue(DebeziumConverter.RangePolicy.of(defaultConfig(), "db.orders.expires_at").clamps(),
                    "an empty configuration saturates");
            assertTrue(DebeziumConverter.RangePolicy.of(null, "db.orders.expires_at").clamps(),
                    "no configuration at all saturates");

            assertEquals("2299-12-31 23:59:59.000",
                    bindDatetime(defaultConfig(), sentinel, ClickHouseDataType.DateTime64),
                    "the default stores the DateTime64 bound");
            List<String> warnings = appender.warnings();
            assertEquals(1, warnings.size(), "the saturation is reported: " + warnings);
            assertTrue(warnings.get(0).contains("9999-12-31T23:59:59Z"), warnings.get(0));
            assertTrue(warnings.get(0).contains("2299-12-31 23:59:59"), warnings.get(0));

            // In range: unchanged.
            assertEquals("2024-05-06 07:08:09.000",
                    bindDatetime(defaultConfig(), LocalDateTime.of(2024, 5, 6, 7, 8, 9), ClickHouseDataType.DateTime64));
        } finally {
            release(appender);
        }
    }

    @Test
    @DisplayName("Default: an out-of-range DATE is saturated at the mapper")
    public void defaultPolicySaturatesOutOfRangeDateAtTheMapper() throws Exception {
        assertEquals(java.sql.Date.valueOf("2149-06-06"),
                bindDate(defaultConfig(), LocalDate.of(9999, 12, 31), ClickHouseDataType.Date),
                "9999-12-31 exceeds Date (max 2149-06-06) and is stored as the bound");
        assertEquals(java.sql.Date.valueOf("1900-01-01"),
                bindDate(defaultConfig(), LocalDate.of(1000, 1, 1), ClickHouseDataType.Date32),
                "1000-01-01 precedes Date32 (min 1900-01-01) and is stored as the bound");
        assertEquals(java.sql.Date.valueOf("2024-05-06"),
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

        assertEquals(java.sql.Date.valueOf("2024-05-06"),
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
            List<String> out = new ArrayList<>();
            for (LogEvent e : events) {
                if (e.getLevel() == Level.WARN) {
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

    /**
     * Rule 2: an operator who opts into saturation gets the bound AND a WARN
     * naming the column, the source value and the stored value. The two
     * saturations below are one column bound as two ClickHouse types, so each
     * opens its own rate-limit window and each is reported.
     */
    @Test
    @DisplayName("clamp.out.of.range=true saturates and logs a WARN naming column and values")
    public void clampSettingSaturatesAndWarns() throws Exception {
        CapturingAppender appender = capture();
        try {
            DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "db.orders.expires_at");
            assertTrue(policy.clamps());

            LocalDateTime sentinel = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
            String bound = DebeziumConverter.TimestampConverter.convert(datetimeMillis(sentinel),
                    ClickHouseDataType.DateTime64, UTC, UTC, null, policy);
            assertEquals("2299-12-31 23:59:59.000", bound);

            java.sql.Date date = DebeziumConverter.DateConverter.convert(
                    (int) LocalDate.of(9999, 12, 31).toEpochDay(), ClickHouseDataType.Date32, policy);
            assertEquals(java.sql.Date.valueOf("2299-12-31"), date);

            List<String> warnings = appender.warnings();
            assertEquals(2, warnings.size(), "one WARN per saturated value: " + warnings);
            assertTrue(warnings.get(0).contains("db.orders.expires_at"), warnings.get(0));
            assertTrue(warnings.get(0).contains("9999-12-31T23:59:59Z"), warnings.get(0));
            assertTrue(warnings.get(0).contains("2299-12-31 23:59:59"), warnings.get(0));
            assertTrue(warnings.get(1).contains("9999-12-31"), warnings.get(1));
            assertTrue(warnings.get(1).contains("2299-12-31"), warnings.get(1));

            // The mapper honours the setting too.
            assertEquals("2299-12-31 23:59:59.000",
                    bindDatetime(config(true), sentinel, ClickHouseDataType.DateTime64));
        } finally {
            release(appender);
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
        assertTrue(dt64.getMessage().contains("1900-01-01 00:00:00 .. 2299-12-31 23:59:59"), dt64.getMessage());

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

        DebeziumConverter.ValueOutOfRangeException decimal = assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> new DebeziumConverter.BigDecimalConverter().truncate(
                        BinaryStreamUtils.DECIMAL128_MAX.add(BigDecimal.ONE), strict));
        assertTrue(decimal.getMessage().contains("Decimal128"), decimal.getMessage());

        // In-range values pass through every bounded converter untouched.
        assertEquals("2021-12-31 19:01:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2021-12-31T19:01:00Z", UTC, strict));
        assertEquals(BigDecimal.TEN, new DebeziumConverter.BigDecimalConverter().truncate(BigDecimal.TEN, strict));
    }

    /** Rule 3: the policy-less overloads still saturate, but never silently. */
    @Test
    @DisplayName("Legacy overloads saturate with a WARN")
    public void legacyOverloadsStillSaturateWithAWarn() {
        CapturingAppender appender = capture();
        try {
            long farFuture = datetimeMillis(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
            assertEquals("2299-12-31 23:59:59.000",
                    DebeziumConverter.TimestampConverter.convert(farFuture, ClickHouseDataType.DateTime64, UTC, UTC));
            assertEquals(java.sql.Date.valueOf("2149-06-06"),
                    DebeziumConverter.DateConverter.convert((int) LocalDate.of(2200, 1, 1).toEpochDay(), ClickHouseDataType.Date));
            assertEquals("2299-12-31 23:59:59.000000",
                    DebeziumConverter.ZonedTimestampConverter.convert("2338-01-19T03:14:07.99Z", UTC));
            assertEquals(BinaryStreamUtils.DECIMAL128_MAX,
                    new DebeziumConverter.BigDecimalConverter().truncate(BinaryStreamUtils.DECIMAL128_MAX.add(BigDecimal.ONE)));

            // Four saturations, three WARNs: the policy-less overloads carry no
            // column, so their rate-limit key is the ClickHouse type alone and the
            // two DateTime64 saturations share one window (rule 2).
            assertEquals(3, appender.warnings().size(), "every saturated type is reported: " + appender.warnings());
        } finally {
            release(appender);
        }
    }

    /**
     * Rule 2, the rate limit: a column whose every row is out of range (the
     * bitemporal open-ended sentinel) must not turn the log into one WARN per
     * row. One WARN per column per window; the rest counted, at DEBUG; the
     * next WARN after the window carries the count.
     */
    @Test
    @DisplayName("clamp.out.of.range=true logs one WARN per column per window and counts the rest")
    public void clampWarnIsRateLimitedPerColumn() {
        CapturingAppender appender = capture();
        long[] now = {0L};
        DebeziumConverter.RangePolicy.ticker = () -> now[0];
        try {
            DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "db.trades.valid_to");
            long sentinel = datetimeMillis(LocalDateTime.of(9999, 12, 31, 23, 59, 59));
            for (int i = 0; i < 1000; i++) {
                assertEquals("2299-12-31 23:59:59.000", DebeziumConverter.TimestampConverter.convert(
                        sentinel, ClickHouseDataType.DateTime64, UTC, UTC, null, policy));
            }
            List<String> warnings = appender.warnings();
            assertEquals(1, warnings.size(), "one WARN per column per window: " + warnings);
            assertTrue(warnings.get(0).contains("db.trades.valid_to"), warnings.get(0));
            assertTrue(warnings.get(0).contains("9999-12-31T23:59:59Z"), warnings.get(0));
            assertTrue(warnings.get(0).contains("2299-12-31 23:59:59"), warnings.get(0));

            // Another column has its own window.
            DebeziumConverter.RangePolicy other = DebeziumConverter.RangePolicy.of(config(true), "db.trades.expires_at");
            DebeziumConverter.TimestampConverter.convert(sentinel, ClickHouseDataType.DateTime64, UTC, UTC, null, other);
            warnings = appender.warnings();
            assertEquals(2, warnings.size(), warnings.toString());
            assertTrue(warnings.get(1).contains("db.trades.expires_at"), warnings.get(1));

            // Once the window has passed, the next saturation reports again and
            // says how many it stood in for (the 999 suppressed ones plus itself).
            now[0] = DebeziumConverter.RangePolicy.WARN_INTERVAL_NANOS;
            DebeziumConverter.TimestampConverter.convert(sentinel, ClickHouseDataType.DateTime64, UTC, UTC, null, policy);
            warnings = appender.warnings();
            assertEquals(3, warnings.size(), warnings.toString());
            assertTrue(warnings.get(2).contains("db.trades.valid_to"), warnings.get(2));
            assertTrue(warnings.get(2).contains("1000 saturation(s)"), warnings.get(2));
        } finally {
            DebeziumConverter.RangePolicy.ticker = System::nanoTime;
            release(appender);
        }
    }

    /** Rule 4: PostgreSQL infinity has no finite value to lose; it keeps its saturation under strict too. */
    @Test
    @DisplayName("PostgreSQL infinity literals keep their documented saturation under the strict policy")
    public void infinityKeepsItsSaturation() {
        DebeziumConverter.RangePolicy strict = DebeziumConverter.RangePolicy.STRICT;
        assertEquals("2299-12-31 23:59:59.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("infinity", UTC, strict));
        assertEquals("1900-01-01 00:00:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("-infinity", UTC, strict));
    }
}
