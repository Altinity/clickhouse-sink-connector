package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseDataType;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.time.temporal.ChronoUnit;



public class DebeziumConverterTest {

    @Test
    @DisplayName("Test timestamp converter for multiple timezones.")
    public void testTimestampConverter() {

        // 2022-01-01: 00:01:00
        Object timestampEpoch = LocalDateTime.of(2022, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;

        // 2022-09-29 01:48:25.100
        Object timestampEpoch2 = LocalDateTime.of(2022, 9, 29, 01 , 48, 25 ,100).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;

        String formattedTimestamp2 = DebeziumConverter.TimestampConverter.convert(timestampEpoch2, ClickHouseDataType.DateTime, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"));
        Assert.assertTrue(formattedTimestamp2.equalsIgnoreCase("2022-09-29 01:48:25"));
        // 6 hours difference.
        String timestampWithChicagoTZ = DebeziumConverter.TimestampConverter.convert(timestampEpoch, ClickHouseDataType.DateTime64, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"));
        Assert.assertTrue(timestampWithChicagoTZ.equalsIgnoreCase("2022-01-01 00:01:00.000"));

        Object timestampEpochWPacific = LocalDateTime.of(2022, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;

        String timestampWithPacificTZ = DebeziumConverter.TimestampConverter.convert(timestampEpochWPacific, ClickHouseDataType.DateTime64, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"));
        Assert.assertTrue(timestampWithPacificTZ.equalsIgnoreCase("2022-01-01 00:01:00.000"));

        //DST start time.
        Object timestampDSTStart = LocalDateTime.of(2022, 3, 9, 2, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;
        String timestampWithDSTStart = DebeziumConverter.TimestampConverter.convert(timestampDSTStart, ClickHouseDataType.DateTime64, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"));
        Assert.assertTrue(timestampWithDSTStart.equalsIgnoreCase("2022-03-09 02:01:00.000"));

        //DST end time.
        Object timestampDSTEnd = LocalDateTime.of(2022, 11, 6, 2, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;
        String timestampWithDSTEnd = DebeziumConverter.TimestampConverter.convert(timestampDSTEnd, ClickHouseDataType.DateTime64, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"));
        Assert.assertTrue(timestampWithDSTEnd.equalsIgnoreCase("2022-11-06 02:01:00.000"));

    }

    @Test
    @DisplayName("Test timestamp converter(MIN) when clickhouse columns are DateTime and DateTime64, min limit is different for DateTime and DateTime64")
    public void testTimestampConverterMinRange() {

        Object timestampEpochDateTime = LocalDateTime.of(1960, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;
        String result = DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime32, ZoneId.of("UTC"), ZoneId.of("UTC"));
        Assert.assertTrue(result.equalsIgnoreCase("1970-01-01 00:00:00"));

        //Clickhouse column DateTime64
        String dateTime64Result = DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime64, ZoneId.of("UTC"), ZoneId.of("UTC"));
        Assert.assertTrue(dateTime64Result.equalsIgnoreCase("1960-01-01 00:01:00.000"));
    }

    @Test
    @DisplayName("Test timestamp converter(MAX) when clickhouse columns are DateTime and DateTime64, min limit is different for DateTime and DateTime64")
    public void testTimestampConverterMaxRange() {

        //DateTime64
        Object timestampEpochDateTime = LocalDateTime.of(2289, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime64, ZoneId.of("UTC"), ZoneId.of("UTC")));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2289-01-01 00:01:00.000"));

        //DateTime
        String formattedTimestampDate = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime, ZoneId.of("UTC"), ZoneId.of("UTC")));
        Assert.assertTrue(formattedTimestampDate.equalsIgnoreCase("2106-02-07 06:28:15"));
    }


    @Test
    @DisplayName("Test Microtimestamp converter- for DATETIME(4,5,6) and can map to DateTime or DateTime64 in ClickHouse")
    public void testMicroTimestampConverter() {

        long timestampEpoch = LocalDateTime.of(2022, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).plus(222, ChronoUnit.MILLIS).toInstant().toEpochMilli() * 1000;
        timestampEpoch += 222;
        // UTC timezone
        String formattedTimestamp = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2022-01-01 00:01:00.22222200"));

        // America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("2022-01-01 00:01:00.22222200"));

        // America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("2022-01-01 00:01:00.22222200"));

    }

    @Test
    @DisplayName("Test Microtimestamp converter(MIN) for DATETIME(4,5,6) and can map to DateTime or DateTime64 in ClickHouse")
    public void testMicroTimestampConverterMin() {

        Object timestampEpoch = LocalDateTime.of(1000, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;

        // DateTime64 and UTC timezone
        String formattedTimestamp = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("1900-01-01 00:00:00.00000000"));

        // DateTime64 and America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("1900-01-01 00:00:00.00000000"));

        // DateTime64 and America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("1900-01-01 00:00:00.00000000"));

        // DateTime32 and UTC timezone
        String formattedTimestampDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ZoneId.of("UTC"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampDate32.equalsIgnoreCase("1970-01-01 00:00:00"));

        // DateTime32 and America/Chicago timezone.
        String formattedTimestampChicagoTZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampChicagoTZDate32.equalsIgnoreCase("1970-01-01 00:00:00"));

        // DateTime32 and America/Los Angeles timezone.
        String formattedTimestampLATZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampLATZDate32.equalsIgnoreCase("1970-01-01 00:00:00"));
    }

    @Test
    @DisplayName("Test Microtimestamp converter(MAX) for DATETIME(4,5,6) and can map to DateTime or DateTime64 in ClickHouse")
    public void testMicroTimestampConverterMax() {

        Object timestampEpoch = LocalDateTime.of(3000, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;

        // DateTime64 and UTC timezone
        String formattedTimestamp = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2299-12-31 23:59:59.00000000"));

        // DateTime64 and America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("2299-12-31 23:59:59.00000000"));

        // DateTime64 and America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("2299-12-31 23:59:59.00000000"));

        // DateTime32 and UTC timezone
        String formattedTimestampDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ZoneId.of("UTC"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampDate32.equalsIgnoreCase("2106-02-07 06:28:15"));

        // DateTime32 and America/Chicago timezone.
        String formattedTimestampChicagoTZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampChicagoTZDate32.equalsIgnoreCase("2106-02-07 06:28:15"));

        // DateTime32 and America/Los Angeles timezone.
        String formattedTimestampLATZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampLATZDate32.equalsIgnoreCase("2106-02-07 06:28:15"));

        Object timestampEpoch2 = LocalDateTime.of(2026, 3, 8, 3, 0, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;
        // Test 2026-03-08 03:00:00.000000 and  2026-03-08 02:00:00.000000 with America/Chicago timezone.
        String formattedTimestampChicagoTZ2 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch2, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampChicagoTZ2.equalsIgnoreCase("2026-03-08 03:00:00"));

        // DST start time.(2026)
        Object timestampEpoch3 = LocalDateTime.of(2026, 3, 8, 2, 0, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;
        String formattedTimestampChicagoTZ3 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch3, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime);

        Assert.assertTrue(formattedTimestampChicagoTZ3.equalsIgnoreCase("2026-03-08 02:00:00"));

        // DST end time.(2026) - Nov 1st 2026 2 am.
        Object timestampEpoch4 = LocalDateTime.of(2026, 11, 1, 2, 0, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;
        String formattedTimestampChicagoTZ4 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch4, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampChicagoTZ4.equalsIgnoreCase("2026-11-01 02:00:00"));


    }

    @Test
    public void testDateConverter() {

        Integer date = Math.toIntExact(LocalDate.of(1925, 1, 1).toEpochDay());
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date, ClickHouseDataType.Date32);

        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase("1925-01-01"));
    }

    @Test
    @DisplayName("Test Date converter(MIN), min limits are different for Date and Date32 types")
    public void testDateConverterMinRange() {

        Integer date = Math.toIntExact(LocalDate.of(1960, 1, 1).toEpochDay());

        //Date32
        java.sql.Date formattedDate32 = DebeziumConverter.DateConverter.convert(date, ClickHouseDataType.Date32);
        Assert.assertTrue(formattedDate32.toString().equalsIgnoreCase("1960-01-01"));

        //Date
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date, ClickHouseDataType.Date);
        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase("1970-01-01"));
    }

    @Test
    @DisplayName("Test Date converter(MAX), min limits are different for Date and Date32 types")
    public void testDateConverterMaxRange() {

        Integer date = Math.toIntExact(LocalDate.of(2299, 1, 1).toEpochDay());

        //Date32
        java.sql.Date formattedDate32 = DebeziumConverter.DateConverter.convert(date, ClickHouseDataType.Date32);
        Assert.assertTrue(formattedDate32.toString().equalsIgnoreCase("2299-01-01"));

        //Date
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date, ClickHouseDataType.Date);
        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase("2149-06-06"));

    }

    @Test
    public void testDateConverterWithinRange() {

        // Epoch (days)
        Integer epochInDays = 8249;
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(epochInDays, ClickHouseDataType.Date32);
        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase("1992-08-02"));
    }

    @Test
    public void testZonedTimestampConverter() {

        String formattedTimestamp = DebeziumConverter.ZonedTimestampConverter.convert("2021-12-31T19:01:00Z", ZoneId.of("UTC"));
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31 19:01:00.000000"));

        String formattedTimestampWMicroSeconds = DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.999999Z", ZoneId.of("UTC"));
        Assert.assertTrue(formattedTimestampWMicroSeconds.equalsIgnoreCase("2038-01-19 03:14:07.999999"));

        String formattedTimestamp3 = DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.99Z", ZoneId.of("UTC"));
        Assert.assertTrue(formattedTimestamp3.equalsIgnoreCase("2038-01-19 03:14:07.990000"));

        // Test max limit
        String formattedTimestamp4 = DebeziumConverter.ZonedTimestampConverter.convert("2338-01-19T03:14:07.99Z", ZoneId.of("UTC"));
        Assert.assertTrue(formattedTimestamp4.equalsIgnoreCase("2299-12-31 23:59:59.000000"));
    }

    @Test
    @DisplayName("Test ZonedTimestamp converter with PostgreSQL infinity values.")
    public void testZonedTimestampConverterInfinity() {

        // PostgreSQL timestamptz accepts the special values infinity and
        // -infinity. Debezium delivers them verbatim as these literal strings
        // (PostgresValueConverter#convertTimestampWithZone), so the converter has
        // to map them onto the representable DateTime64 range instead of silently
        // producing an empty string. See Altinity/clickhouse-sink-connector#1231.
        String positiveInfinity = DebeziumConverter.ZonedTimestampConverter.convert("infinity", ZoneId.of("UTC"));
        Assert.assertEquals("2299-12-31 23:59:59.000000", positiveInfinity);

        String negativeInfinity = DebeziumConverter.ZonedTimestampConverter.convert("-infinity", ZoneId.of("UTC"));
        Assert.assertEquals("1900-01-01 00:00:00.000000", negativeInfinity);
    }

    @Test
    public void testMicroTimeConverter() {

        Object timeInMicroSeconds = LocalTime.of(10, 1, 1, 1).toEpochSecond(LocalDate.now(), ZoneOffset.UTC);
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(timeInMicroSeconds);

       // Assert.assertTrue(formattedTime.equalsIgnoreCase("00:28:21.424861"));

        // 09:01:01 as MicroTime actually encodes it: microseconds PAST
        // MIDNIGHT.
        //
        // This input used to be built as
        //   ZonedDateTime.of(2024,1,1,1,1,1,1, "America/Los_Angeles")
        //       .toEpochSecond() * 1000 * 1000
        // which is 1704099661000000 -- an epoch timestamp, i.e. ~473361 HOURS
        // past midnight, not a time of day. No MySQL TIME can hold it (the
        // type caps at 838:59:59) and Debezium never emits it for a TIME
        // column. It only read as "09:01:01" because the converter silently
        // wrapped every value onto a 24-hour clock, which is precisely the
        // defect the signed/large-value handling removes. Keeping the old
        // literal would have pinned that wrap in place as expected behaviour.
        //
        // The assertion below is unchanged, and so is what it checks: a
        // whole-second TIME renders with no fractional part (issue #1215).
        Object timePacificTZ = LocalTime.of(9, 1, 1).toSecondOfDay() * 1_000_000L;
        String formattedTimePacificTZ = DebeziumConverter.MicroTimeConverter.convert(timePacificTZ);
        Assert.assertEquals("09:01:01", formattedTimePacificTZ);
    }


    @Test
    public void testTrailingZeros() {
        String result1 = DebeziumConverter.removeTrailingZeros("2022-01-01 11:50:00.0000");
        Assert.assertTrue(result1.equalsIgnoreCase("2022-01-01 11:50:00"));

        String result2 = DebeziumConverter.removeTrailingZeros("2022-01-01 11:50:00.0010");
        Assert.assertTrue(result2.equalsIgnoreCase("2022-01-01 11:50:00.001"));

        String result3 = DebeziumConverter.removeTrailingZeros("2022-01-01 11:50:00.0100");
        Assert.assertTrue(result3.equalsIgnoreCase("2022-01-01 11:50:00.01"));

        String result4 = DebeziumConverter.removeTrailingZeros("2022-01-01 11:50:00.100");
        Assert.assertTrue(result4.equalsIgnoreCase("2022-01-01 11:50:00.1"));
    }

    @Test
    public void testTimestampConverterMaxTTL() {
        // Testing DebeziumConverter.TimestampConverter with DATETIME32_MAX_TTL / 1000
        long datetime32MaxTtlDiv1000 = DataTypeRange.DATETIME32_MAX_TTL * 1000;
        String formattedTimestamp = DebeziumConverter.TimestampConverter.convert(datetime32MaxTtlDiv1000,
                ClickHouseDataType.DateTime32, ZoneId.of("UTC"), ZoneId.of("UTC"));

        // Assert the expected result, adjust according to the documented behavior
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2100-01-01 00:00:00"));
    }

    /**
     * Issue #1215: a MySQL TIME(0) value replicated as "16:01:25.000000".
     *
     * <p>Under the default ADAPTIVE temporal precision mode Debezium emits
     * TIME(4)..TIME(6) as io.debezium.time.MicroTime (INT64 microseconds past
     * midnight), which is what MicroTimeConverter receives. The converter has
     * no precision context, and it used the fixed-width pattern
     * "HH:mm:ss.SSSSSS", so every value was padded to six fractional digits
     * whether the source column had them or not.
     *
     * <p>16:01:25 is 57685 seconds past midnight.
     */
    @Test
    @DisplayName("Issue #1215: a whole-second TIME must not gain six fractional digits")
    public void testMicroTimeConverterWholeSecondHasNoFraction() {

        long wholeSecond = 57685L * 1_000_000L;

        Assert.assertEquals("16:01:25",
                DebeziumConverter.MicroTimeConverter.convert(wholeSecond));
    }

    /**
     * Issue #8 (the TIME half): 17:51:04.777 must keep its milliseconds and
     * must not be padded out to "17:51:04.777000".
     *
     * <p>17:51:04 is 64264 seconds past midnight.
     */
    @Test
    @DisplayName("Issue #8: a millisecond TIME keeps its digits without zero padding")
    public void testMicroTimeConverterMillisecondsAreNotPadded() {

        long milliPrecision = 64264L * 1_000_000L + 777_000L;

        Assert.assertEquals("17:51:04.777",
                DebeziumConverter.MicroTimeConverter.convert(milliPrecision));
    }

    /**
     * Control. A genuine sub-second TIME value must still render every digit
     * it carries -- the fix trims padding, never significant digits. This
     * assertion holds both before and after the fix and is here to pin that.
     */
    @Test
    @DisplayName("Control: a genuine microsecond TIME still renders all six digits")
    public void testMicroTimeConverterKeepsSignificantDigits() {

        long microPrecision = 64264L * 1_000_000L + 123_456L;
        Assert.assertEquals("17:51:04.123456",
                DebeziumConverter.MicroTimeConverter.convert(microPrecision));

        long trailingSignificantZero = 64264L * 1_000_000L + 100_000L;
        Assert.assertEquals("17:51:04.1",
                DebeziumConverter.MicroTimeConverter.convert(trailingSignificantZero));

        long midnight = 0L;
        Assert.assertEquals("00:00:00",
                DebeziumConverter.MicroTimeConverter.convert(midnight));
    }

    /**
     * MySQL TIME is a signed duration, not a clock reading: its declared range
     * is -838:59:59 .. 838:59:59, and both ends of that range occur in real
     * schemas (elapsed times, deltas, durations).
     *
     * <p>Debezium delivers such a value verbatim. Under every temporal
     * precision mode the connector runs in, JdbcValueConverters passes
     * acceptLargeValues=true into io.debezium.time.MicroTime#toMicroOfDay
     * (see supportsLargeTimeValues(): true for ADAPTIVE,
     * ADAPTIVE_TIME_MICROSECONDS, MICROSECONDS and NANOSECONDS -- i.e. all of
     * them), which then skips its own range check entirely and returns
     * Duration.toNanos() / 1000. So a negative TIME arrives as a NEGATIVE
     * micro count, and an over-24h TIME arrives as a count larger than a day.
     *
     * <p>The converter wrapped that count onto a 24-hour clock:
     *
     *   Instant.EPOCH.plus(value, MICROS).atZone(UTC).toLocalTime()
     *
     * LocalTime cannot represent either case, so both were silently rewritten
     * into a plausible-looking wrong time -- no exception, no log line. This
     * is the worst shape a data bug can take: the destination looks healthy
     * and a checksum job is the only thing that would ever notice.
     */
    @Test
    @DisplayName("A negative MySQL TIME must keep its sign, not wrap onto a 24h clock")
    public void testMicroTimeConverterNegativeValue() {

        // -01:30:00 == -5400 seconds
        long negative = -5400L * 1_000_000L;

        Assert.assertEquals("-01:30:00",
                DebeziumConverter.MicroTimeConverter.convert(negative));
    }

    @Test
    @DisplayName("A MySQL TIME past 24 hours must keep its hour count, not wrap")
    public void testMicroTimeConverterBeyondTwentyFourHours() {

        // MySQL's documented maximum TIME: 838:59:59 == 3020399 seconds.
        long maximum = 3020399L * 1_000_000L;

        Assert.assertEquals("838:59:59",
                DebeziumConverter.MicroTimeConverter.convert(maximum));

        // ...and its documented minimum.
        Assert.assertEquals("-838:59:59",
                DebeziumConverter.MicroTimeConverter.convert(-maximum));
    }

    @Test
    @DisplayName("A negative TIME keeps its fractional digits, and the sign leads the whole value")
    public void testMicroTimeConverterNegativeWithFraction() {

        // -00:00:00.500000 -- the sign belongs to the value as a whole, so it
        // must not be applied to the hour field alone.
        Assert.assertEquals("-00:00:00.5",
                DebeziumConverter.MicroTimeConverter.convert(-500_000L));

        // -01:30:00.123456
        long v = -(5400L * 1_000_000L + 123_456L);
        Assert.assertEquals("-01:30:00.123456",
                DebeziumConverter.MicroTimeConverter.convert(v));
    }

    /**
     * The INT32 millis-of-day shape has the same defect. Debezium's
     * io.debezium.time.Time#toMilliOfDay with acceptLargeValues=true returns
     * (int) Duration.toMillis(), so a negative TIME reaches the converter as a
     * negative millisecond count.
     *
     * <p>Note the int truncation in Debezium itself: 838:59:59 is 3020399000
     * ms, which overflows a signed 32-bit int. That is out of this connector's
     * hands -- but it only ever happens for TIME(0)..TIME(3) columns, and only
     * beyond ~24.8 days of the value range, so the reachable INT32 defect is
     * the sign.
     */
    @Test
    @DisplayName("A negative MySQL TIME on the INT32 millis path must keep its sign")
    public void testTimeConverterNegativeValue() {

        // -01:30:00 == -5400000 ms
        Assert.assertEquals("-01:30:00",
                DebeziumConverter.TimeConverter.convert(-5400000));

        // -00:00:00.250
        Assert.assertEquals("-00:00:00.25",
                DebeziumConverter.TimeConverter.convert(-250));
    }

    @Test
    @DisplayName("A MySQL TIME past 24 hours on the INT32 millis path must keep its hour count")
    public void testTimeConverterBeyondTwentyFourHours() {

        // 30:00:00 == 108000000 ms, representable in an int.
        Assert.assertEquals("30:00:00",
                DebeziumConverter.TimeConverter.convert(108000000));
    }

    /**
     * Control. Every ordinary in-range value must render exactly as before --
     * the signed/large handling must not disturb the common path, including
     * the fraction trimming that issue #1215 asked for.
     */
    @Test
    @DisplayName("Control: ordinary in-range TIME values are unchanged on both paths")
    public void testTimeConvertersInRangeUnchanged() {

        Assert.assertEquals("16:01:25",
                DebeziumConverter.MicroTimeConverter.convert(57685L * 1_000_000L));
        Assert.assertEquals("17:51:04.777",
                DebeziumConverter.MicroTimeConverter.convert(64264L * 1_000_000L + 777_000L));
        Assert.assertEquals("17:51:04.123456",
                DebeziumConverter.MicroTimeConverter.convert(64264L * 1_000_000L + 123_456L));
        Assert.assertEquals("00:00:00",
                DebeziumConverter.MicroTimeConverter.convert(0L));

        Assert.assertEquals("16:01:25",
                DebeziumConverter.TimeConverter.convert(57685000));
        Assert.assertEquals("23:23:00",
                DebeziumConverter.TimeConverter.convert(84180000));
        Assert.assertEquals("00:00:00",
                DebeziumConverter.TimeConverter.convert(0));

        // 23:59:59.999999 -- the largest value that still fits a 24h clock,
        // so it must be rendered by the ordinary path and be identical either
        // way.
        Assert.assertEquals("23:59:59.999999",
                DebeziumConverter.MicroTimeConverter.convert(86400L * 1_000_000L - 1L));
    }
}
