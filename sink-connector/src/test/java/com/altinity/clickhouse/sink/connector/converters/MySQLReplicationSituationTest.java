package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-integrity contract for the MySQL value situations a replica must
 * survive without loss or corruption.
 *
 * <p>MySQL accepts values that ClickHouse cannot represent. Every one of those
 * is handled here by <em>clamping</em> — the out-of-range value is replaced by
 * the nearest representable one and the row is written. Clamping is the right
 * call (the alternative is stalling replication on one bad row), but it is
 * lossy by construction and completely silent, so each boundary needs an
 * explicit, pinned expectation. Without that, a change to a bound is invisible
 * until someone compares a column against the source months later.</p>
 *
 * <p>The situations covered are the ones a real MySQL source produces and the
 * existing suite did not pin:</p>
 * <ul>
 *   <li><b>Zero dates.</b> {@code '0000-00-00'} and
 *       {@code '0000-00-00 00:00:00'} are legal MySQL values (they are what a
 *       {@code NOT NULL DATE} column gets on a default insert in a non-strict
 *       sql_mode) and are far below every ClickHouse floor.</li>
 *   <li><b>Range extremes.</b> MySQL DATE reaches 9999-12-31; ClickHouse
 *       {@code Date} stops at 2149 and {@code Date32} at 2299.</li>
 *   <li><b>Negative and out-of-range TIME.</b> MySQL TIME spans
 *       -838:59:59 to 838:59:59, which is not a wall clock and does not fit
 *       {@code LocalTime} at all.</li>
 *   <li><b>Type-dependent floors.</b> The same source value clamps differently
 *       for {@code Date} vs {@code Date32} and {@code DateTime} vs
 *       {@code DateTime64}; picking the wrong target type shifts a timestamp by
 *       decades.</li>
 * </ul>
 *
 * <p>Every expectation below is derived from {@link DataTypeRange}, the same
 * constants the production path uses, so the tests state the contract rather
 * than restating a hardcoded literal that could drift from it.</p>
 */
public class MySQLReplicationSituationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Days since epoch for MySQL's zero-date '0000-00-00'. Negative. */
    private static final int ZERO_DATE_EPOCH_DAYS =
            (int) LocalDate.of(1, 1, 1).toEpochDay();

    @Nested
    @DisplayName("Zero dates — legal in MySQL, unrepresentable in ClickHouse")
    class ZeroDates {

        @Test
        @DisplayName("'0000-00-00' into Date clamps to the epoch, never a negative day")
        public void zeroDateIntoDateClampsToEpoch() {
            // ClickHouse Date is an unsigned day offset from 1970-01-01. A
            // negative day count reinterpreted unsigned becomes a date far in
            // the future, so the floor must be applied before the value is
            // ever bound.
            Integer clamped = DebeziumConverter.DateConverter
                    .checkIfDateExceedsSupportedRange(
                            ZERO_DATE_EPOCH_DAYS, ClickHouseDataType.Date);

            assertEquals(0, clamped.intValue(),
                    "MySQL's zero-date must clamp to epoch day 0 for a ClickHouse "
                            + "Date column. Date is unsigned; letting a negative day through "
                            + "wraps to a far-future date, which is corruption rather than a "
                            + "visible error.");
        }

        @Test
        @DisplayName("'0000-00-00' into Date32 clamps to the Date32 floor")
        public void zeroDateIntoDate32ClampsToFloor() {
            Integer clamped = DebeziumConverter.DateConverter
                    .checkIfDateExceedsSupportedRange(
                            ZERO_DATE_EPOCH_DAYS, ClickHouseDataType.Date32);

            assertEquals(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32, clamped,
                    "Date32 has its own, lower floor than Date. Clamping to the "
                            + "wrong one silently shifts the value.");
        }

        @Test
        @DisplayName("A representable date is passed through unchanged")
        public void inRangeDateIsUntouched() {
            // The clamp must be a boundary guard, not a transform: an ordinary
            // date has to survive byte-for-byte. Without this, a floor bug that
            // rewrote every value would still pass the boundary tests above.
            int epochDays = (int) LocalDate.of(2024, 6, 15).toEpochDay();

            assertEquals(epochDays,
                    DebeziumConverter.DateConverter
                            .checkIfDateExceedsSupportedRange(
                                    epochDays, ClickHouseDataType.Date).intValue(),
                    "An in-range DATE must pass through the clamp unchanged.");
            assertEquals(epochDays,
                    DebeziumConverter.DateConverter
                            .checkIfDateExceedsSupportedRange(
                                    epochDays, ClickHouseDataType.Date32).intValue(),
                    "An in-range DATE must pass through the Date32 clamp unchanged.");
        }

        @Test
        @DisplayName("'0000-00-00 00:00:00' into DateTime clamps to the DateTime32 floor")
        public void zeroDateTimeIntoDateTimeClampsToFloor() {
            Instant zeroDateTime = LocalDateTime.of(1, 1, 1, 0, 0, 0)
                    .toInstant(ZoneOffset.UTC);
            boolean[] rangeExceeded = new boolean[1];

            Instant clamped = DebeziumConverter.checkIfDateTimeExceedsSupportedRange(
                    zeroDateTime, ClickHouseDataType.DateTime, rangeExceeded);

            assertTrue(rangeExceeded[0],
                    "The zero-datetime is out of range for DateTime and must be "
                            + "flagged as clamped — the flag drives UTC formatting downstream.");
            assertEquals(Instant.ofEpochSecond(DataTypeRange.DATETIME32_MIN), clamped,
                    "Zero-datetime must clamp to the DateTime32 floor (epoch).");
        }

        @Test
        @DisplayName("'0000-00-00 00:00:00' into DateTime64 clamps to the DateTime64 floor")
        public void zeroDateTimeIntoDateTime64ClampsToFloor() {
            Instant zeroDateTime = LocalDateTime.of(1, 1, 1, 0, 0, 0)
                    .toInstant(ZoneOffset.UTC);
            boolean[] rangeExceeded = new boolean[1];

            Instant clamped = DebeziumConverter.checkIfDateTimeExceedsSupportedRange(
                    zeroDateTime, ClickHouseDataType.DateTime64, rangeExceeded);

            assertTrue(rangeExceeded[0], "Zero-datetime is out of DateTime64 range.");
            assertEquals(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64, clamped,
                    "DateTime64 reaches back to 1900 — clamping it to the "
                            + "DateTime32 floor of 1970 would move the value by 70 years.");
        }
    }

    @Nested
    @DisplayName("Range extremes — MySQL's ceiling exceeds ClickHouse's")
    class RangeExtremes {

        @Test
        @DisplayName("MySQL's max DATE 9999-12-31 clamps to each type's ceiling")
        public void maxMySqlDateClampsToCeiling() {
            int epochDays = (int) LocalDate.of(9999, 12, 31).toEpochDay();

            Integer date32 = DebeziumConverter.DateConverter
                    .checkIfDateExceedsSupportedRange(
                            epochDays, ClickHouseDataType.Date32);
            assertEquals(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32, date32,
                    "MySQL DATE reaches 9999-12-31, beyond Date32's 2299 ceiling; "
                            + "it must clamp to the ceiling, not overflow past it.");

            Integer date = DebeziumConverter.DateConverter
                    .checkIfDateExceedsSupportedRange(
                            epochDays, ClickHouseDataType.Date);
            assertTrue(date < epochDays,
                    "MySQL's max DATE must be clamped down for a Date column, "
                            + "which only reaches 2149.");
            assertTrue(date > 0,
                    "The Date ceiling clamp must stay positive; a wrapped value "
                            + "would read as a date near the epoch.");
        }

        @Test
        @DisplayName("A datetime past 2299 clamps to the DateTime64 ceiling")
        public void beyondMaxDateTime64ClampsToCeiling() {
            Instant beyond = LocalDateTime.of(9999, 12, 31, 23, 59, 59)
                    .toInstant(ZoneOffset.UTC);
            boolean[] rangeExceeded = new boolean[1];

            Instant clamped = DebeziumConverter.checkIfDateTimeExceedsSupportedRange(
                    beyond, ClickHouseDataType.DateTime64, rangeExceeded);

            assertTrue(rangeExceeded[0], "9999 is beyond DateTime64's 2299 ceiling.");
            assertEquals(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64, clamped,
                    "Must clamp to the DateTime64 ceiling.");
        }

        @Test
        @DisplayName("An in-range datetime is not flagged as clamped")
        public void inRangeDateTimeIsNotFlagged() {
            Instant ordinary = LocalDateTime.of(2024, 6, 15, 12, 30, 45)
                    .toInstant(ZoneOffset.UTC);
            boolean[] rangeExceeded = new boolean[1];

            Instant result = DebeziumConverter.checkIfDateTimeExceedsSupportedRange(
                    ordinary, ClickHouseDataType.DateTime64, rangeExceeded);

            assertTrue(!rangeExceeded[0],
                    "An ordinary timestamp must not be flagged as range-exceeded; "
                            + "the flag switches formatting to UTC and would shift the value "
                            + "by the server-timezone offset.");
            assertEquals(ordinary, result,
                    "An in-range timestamp must pass through byte-for-byte.");
        }
    }

    @Nested
    @DisplayName("TIME — MySQL's range is an interval, not a wall clock")
    class TimeValues {

        @Test
        @DisplayName("MySQL's maximum TIME 838:59:59 is preserved, not wrapped")
        public void maxMySqlTimeIsPreserved() {
            // 838:59:59 exceeds LocalTime's 24-hour domain entirely. Routing it
            // through LocalTime would wrap it modulo 24h and silently rewrite
            // the value; the converter computes the fields directly instead.
            long micros = (838L * 3600 + 59 * 60 + 59) * 1_000_000L;

            assertEquals("838:59:59.000000",
                    DebeziumConverter.MicroTimeConverter.convert(micros),
                    "MySQL TIME reaches 838:59:59. Wrapping it into a 24-hour "
                            + "clock would rewrite the value with no error raised.");
        }

        @Test
        @DisplayName("Negative TIME keeps its sign and magnitude")
        public void negativeTimeIsPreserved() {
            long micros = -((838L * 3600 + 59 * 60 + 59) * 1_000_000L);

            assertEquals("-838:59:59.000000",
                    DebeziumConverter.MicroTimeConverter.convert(micros),
                    "MySQL TIME is signed. Dropping the sign turns a negative "
                            + "interval into a positive one — a sign flip in the data.");
        }

        @Test
        @DisplayName("Sub-second precision survives to microseconds")
        public void microsecondPrecisionIsPreserved() {
            long micros = (10L * 3600 + 30 * 60 + 15) * 1_000_000L + 123_456L;

            assertEquals("10:30:15.123456",
                    DebeziumConverter.MicroTimeConverter.convert(micros),
                    "TIME(6) carries microsecond precision; truncating it loses "
                            + "data that MySQL stored.");
        }

        @Test
        @DisplayName("An Integer-typed TIME is accepted, not rejected on cast")
        public void integerValuedTimeIsAccepted() {
            // Debezium delivers a boxed Integer for small TIME values. A direct
            // (Long) cast throws ClassCastException, which fails the batch and
            // stalls replication on an ordinary value.
            Object smallTime = 1_000_000;

            assertEquals("00:00:01.000000",
                    DebeziumConverter.MicroTimeConverter.convert(smallTime),
                    "Debezium may deliver TIME as Integer; the converter must "
                            + "widen rather than cast, or replication stalls on a normal row.");
        }

        @Test
        @DisplayName("Zero TIME renders as a zero clock, not an empty string")
        public void zeroTimeRenders() {
            assertEquals("00:00:00.000000",
                    DebeziumConverter.MicroTimeConverter.convert(0L),
                    "A zero TIME is a legitimate value and must render fully.");
        }
    }

    @Nested
    @DisplayName("Clamp behaviour is type-directed, never one-size-fits-all")
    class TypeDirectedClamping {

        @Test
        @DisplayName("The same source value clamps differently per target type")
        public void sameValueClampsPerTargetType() {
            // The single most dangerous silent failure in this area: applying
            // one type's bound to another type shifts values by decades while
            // every row still lands successfully.
            Instant year1950 = LocalDateTime.of(1950, 1, 1, 0, 0, 0)
                    .toInstant(ZoneOffset.UTC);

            boolean[] dt32Flag = new boolean[1];
            Instant dt32 = DebeziumConverter.checkIfDateTimeExceedsSupportedRange(
                    year1950, ClickHouseDataType.DateTime, dt32Flag);

            boolean[] dt64Flag = new boolean[1];
            Instant dt64 = DebeziumConverter.checkIfDateTimeExceedsSupportedRange(
                    year1950, ClickHouseDataType.DateTime64, dt64Flag);

            assertTrue(dt32Flag[0],
                    "1950 precedes DateTime32's epoch floor and must be clamped.");
            assertEquals(Instant.ofEpochSecond(DataTypeRange.DATETIME32_MIN), dt32,
                    "DateTime32 must clamp 1950 up to the epoch.");

            assertTrue(!dt64Flag[0],
                    "1950 is inside DateTime64's range (floor 1900) and must NOT "
                            + "be clamped. Clamping it here would move the value 20 years.");
            assertEquals(year1950, dt64,
                    "DateTime64 must preserve 1950 exactly.");
        }

        @Test
        @DisplayName("An unknown target type leaves the value untouched")
        public void unknownTypeIsPassThrough() {
            // Defensive: an unmapped type must not silently coerce the value to
            // a bound. Passing it through lets the driver raise a real error
            // instead of writing a wrong number.
            int epochDays = (int) LocalDate.of(2024, 1, 1).toEpochDay();

            assertEquals(epochDays,
                    DebeziumConverter.DateConverter
                            .checkIfDateExceedsSupportedRange(
                                    epochDays, ClickHouseDataType.String).intValue(),
                    "An unrecognised target type must pass the value through "
                            + "rather than clamp it to an unrelated type's bound.");
        }

        @Test
        @DisplayName("Clamped values are themselves representable")
        public void clampTargetsAreRepresentable() {
            // A clamp that produces an out-of-range value is worse than no
            // clamp: the write then fails at the driver with a confusing error,
            // or wraps. Every floor/ceiling must round-trip.
            assertNotNull(LocalDate.ofEpochDay(
                            DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32),
                    "The Date32 floor must be a constructible date.");
            assertNotNull(LocalDate.ofEpochDay(
                            DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32),
                    "The Date32 ceiling must be a constructible date.");
            assertTrue(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64
                            .isBefore(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64),
                    "The DateTime64 floor must precede its ceiling; an inverted "
                            + "pair would clamp every value to one end.");
            assertTrue(DataTypeRange.DATETIME32_MIN < DataTypeRange.DATETIME32_MAX,
                    "The DateTime32 floor must precede its ceiling.");
            assertTrue(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32
                            < DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32,
                    "The Date32 floor must precede its ceiling.");
        }
    }
}
