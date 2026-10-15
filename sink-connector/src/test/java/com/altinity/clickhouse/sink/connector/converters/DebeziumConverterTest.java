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
        // Debezium MicroTime carries the TIME value as (signed) microseconds,
        // i.e. 09:01:01 is 9h 1m 1s worth of microseconds -- not an epoch.
        Object timeInMicroSeconds = LocalTime.of(9, 1, 1).toNanoOfDay() / 1000L;
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(timeInMicroSeconds);
        Assert.assertEquals("09:01:01.000000", formattedTime);

        Object withMicros = LocalTime.of(10, 1, 1, 424_861_000).toNanoOfDay() / 1000L;
        Assert.assertEquals("10:01:01.424861", DebeziumConverter.MicroTimeConverter.convert(withMicros));

        Assert.assertEquals("00:00:00.000000", DebeziumConverter.MicroTimeConverter.convert(0L));
    }

    /**
     * Spec 07.03 section 3.2: MySQL TIME is a signed duration in
     * -838:59:59 .. 838:59:59, and Debezium MicroTime carries the signed
     * microsecond total. Reducing it modulo 24 h through a LocalTime turned
     * -01:00:00 into 23:00:00 and 25:30:00 into 01:30:00 -- silently.
     */
    @Test
    public void testMicroTimeConverterSignedAndBeyond24Hours() {
        Assert.assertEquals("a negative TIME must keep its sign",
                "-01:00:00.000000", DebeziumConverter.MicroTimeConverter.convert(-3_600_000_000L));
        Assert.assertEquals("a TIME beyond 24h must not wrap",
                "25:30:00.000000", DebeziumConverter.MicroTimeConverter.convert(91_800_000_000L));
        // The MySQL range limits, with fractional seconds.
        Assert.assertEquals("838:59:59.999999",
                DebeziumConverter.MicroTimeConverter.convert(
                        (838L * 3600L + 59L * 60L + 59L) * 1_000_000L + 999_999L));
        Assert.assertEquals("-838:59:59.000001",
                DebeziumConverter.MicroTimeConverter.convert(
                        -((838L * 3600L + 59L * 60L + 59L) * 1_000_000L + 1L)));
        Assert.assertEquals("-00:00:00.000001",
                DebeziumConverter.MicroTimeConverter.convert(-1L));
    }

    /** Debezium encodes DATETIME digits as a UTC epoch; this is that encoding. */
    private static long datetimeDigitsAsUtcEpochMillis(LocalDateTime digits) {
        return digits.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * Spec 07.03 section 3.1.1: MySQL DATETIME is zone-less digits. When the
     * source and session zones are the same DST zone, TimestampConverter used
     * to push the digits through TimeZone.getRawOffset/inDaylightTime, which
     * moved every spring-forward gap time back by an hour: 2026-03-08 02:30:00
     * in America/Chicago came out as 01:30:00. MicroTimestampConverter already
     * decoded the digits as UTC (the inverse of Debezium's encoding); this pins
     * the same behaviour for DATETIME(0..3).
     */
    @Test
    public void testTimestampConverterGapTimePreserved() {
        ZoneId chicago = ZoneId.of("America/Chicago");

        long gap = datetimeDigitsAsUtcEpochMillis(LocalDateTime.of(2026, 3, 8, 2, 30, 0));
        Assert.assertEquals("a wall time inside the spring-forward gap must keep its digits",
                "2026-03-08 02:30:00.000",
                DebeziumConverter.TimestampConverter.convert(gap, ClickHouseDataType.DateTime64, chicago, chicago));
        Assert.assertEquals("2026-03-08 02:30:00",
                DebeziumConverter.TimestampConverter.convert(gap, ClickHouseDataType.DateTime, chicago, chicago));

        long overlap = datetimeDigitsAsUtcEpochMillis(LocalDateTime.of(2026, 11, 1, 1, 30, 0));
        Assert.assertEquals("a wall time inside the fall-back overlap must keep its digits",
                "2026-11-01 01:30:00.000",
                DebeziumConverter.TimestampConverter.convert(overlap, ClickHouseDataType.DateTime64, chicago, chicago));

        // Same digits, same-zone decode, both converters agree.
        Assert.assertEquals("2026-03-08 02:30:00.00000000",
                DebeziumConverter.MicroTimestampConverter.convert(gap * 1000L, chicago, chicago, ClickHouseDataType.DateTime64));

        // Explicitly different zones: the digits are a Chicago wall time; a gap
        // time takes the offset before the transition (-06:00), exactly as
        // MicroTimestampConverter does, so 02:30 CST is 08:30 UTC — bound as
        // the epoch text of that instant (section 3.1.4; 1772958600 is
        // 2026-03-08T08:30:00Z).
        ZoneId utc = ZoneId.of("UTC");
        Assert.assertEquals("1772958600.000000",
                DebeziumConverter.TimestampConverter.convert(gap, ClickHouseDataType.DateTime64, chicago, utc));
        Assert.assertEquals("1772958600.000000",
                DebeziumConverter.MicroTimestampConverter.convert(gap * 1000L, chicago, utc, ClickHouseDataType.DateTime64));
        // A DateTime column cannot take epoch text, so it keeps the digits.
        Assert.assertEquals("2026-03-08 08:30:00",
                DebeziumConverter.TimestampConverter.convert(gap, ClickHouseDataType.DateTime, chicago, utc));
        // And an ordinary summer wall time uses the DST offset (-05:00):
        // 2026-07-01T15:00:00Z.
        long summer = datetimeDigitsAsUtcEpochMillis(LocalDateTime.of(2026, 7, 1, 10, 0, 0));
        Assert.assertEquals("1782918000.000000",
                DebeziumConverter.TimestampConverter.convert(summer, ClickHouseDataType.DateTime64, chicago, utc));
    }

    /**
     * Spec 07.03 section 3.1.4: an instant bound into a DateTime64 column is
     * epoch text. Wall-clock digits name two instants in a fall-back overlap
     * hour and ClickHouse stores the first (measured with clickhouse local
     * 24.8.14: '2026-11-01 01:30:00' into DateTime64(6, 'America/Chicago')
     * reads back 06:30 UTC; '1793518200.000000' reads back 07:30 UTC).
     */
    @Test
    @DisplayName("Instants bind as epoch text into DateTime64; digits into DateTime and String")
    public void testInstantsBindAsEpochTextIntoDateTime64() {
        ZoneId chicago = ZoneId.of("America/Chicago");
        ZoneId utc = ZoneId.of("UTC");
        DebeziumConverter.RangePolicy clamp = DebeziumConverter.RangePolicy.CLAMP;

        // The two Chicago overlap instants: the same digits, distinct epochs.
        Assert.assertEquals("2026-11-01 01:30:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T06:30:00Z", chicago));
        Assert.assertEquals("2026-11-01 01:30:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T07:30:00Z", chicago));
        Assert.assertEquals("1793514600.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T06:30:00Z", chicago,
                        ClickHouseDataType.DateTime64, clamp));
        Assert.assertEquals("1793518200.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T07:30:00Z", chicago,
                        ClickHouseDataType.DateTime64, clamp));
        // The column zone is no longer part of the stored value.
        Assert.assertEquals("1793518200.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T07:30:00Z", utc,
                        ClickHouseDataType.DateTime64, clamp));

        // Microseconds are kept; a seventh fraction digit is truncated, as the
        // MICROS_FORMAT digits rendering truncates it. 2147483647 is
        // 2038-01-19T03:14:07Z.
        Assert.assertEquals("2147483647.999999",
                DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.999999Z", utc,
                        ClickHouseDataType.DateTime64, clamp));
        Assert.assertEquals("2147483647.123456",
                DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.1234567Z", utc,
                        ClickHouseDataType.DateTime64, clamp));
        Assert.assertEquals("2147483647.990000",
                DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.99+00:00", utc,
                        ClickHouseDataType.DateTime64, clamp));
        // An offset other than Z is the same instant.
        Assert.assertEquals("1793518200.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T01:30:00-06:00", chicago,
                        ClickHouseDataType.DateTime64, clamp));

        // The DateTime64 bounds as epoch text: clamped, and the PostgreSQL
        // infinity literals (10413791999 is 2299-12-31T23:59:59Z, -2208988800
        // is 1900-01-01T00:00:00Z).
        Assert.assertEquals("10413791999.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2338-01-19T03:14:07.99Z", utc,
                        ClickHouseDataType.DateTime64, clamp));
        Assert.assertEquals("10413791999.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("infinity", chicago,
                        ClickHouseDataType.DateTime64, clamp));
        Assert.assertEquals("-2208988800.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("-infinity", chicago,
                        ClickHouseDataType.DateTime64, clamp));
        // A pre-1970 instant is a negative decimal ...
        Assert.assertEquals("-1.500000",
                DebeziumConverter.ZonedTimestampConverter.convert("1969-12-31T23:59:58.5Z", utc,
                        ClickHouseDataType.DateTime64, clamp));
        // ... except within the second before the epoch, where ClickHouse
        // drops the sign of '-0.500000'; that second keeps the digits.
        Assert.assertEquals("1969-12-31 23:59:59.500000",
                DebeziumConverter.ZonedTimestampConverter.convert("1969-12-31T23:59:59.5Z", utc,
                        ClickHouseDataType.DateTime64, clamp));

        // A DateTime or String target, and the type-less overloads, keep the
        // digits in the column zone.
        Assert.assertEquals("2026-11-01 01:30:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T07:30:00Z", chicago,
                        ClickHouseDataType.DateTime, clamp));
        Assert.assertEquals("2026-11-01 01:30:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T07:30:00Z", chicago,
                        ClickHouseDataType.String, clamp));
        Assert.assertEquals("2026-11-01 01:30:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2026-11-01T07:30:00Z", chicago, clamp));

        // DATETIME digits converted to an instant because the operator declared
        // a different source zone (section 3.1.1): UTC digits 07:30 into a
        // Chicago column used to render the ambiguous '01:30:00'.
        long overlapDigits = datetimeDigitsAsUtcEpochMillis(LocalDateTime.of(2026, 11, 1, 7, 30, 0));
        Assert.assertEquals("1793518200.000000",
                DebeziumConverter.TimestampConverter.convert(overlapDigits, ClickHouseDataType.DateTime64,
                        utc, chicago, chicago));
        Assert.assertEquals("1793518200.000000",
                DebeziumConverter.MicroTimestampConverter.convert(overlapDigits * 1000L, utc, chicago,
                        ClickHouseDataType.DateTime64, chicago));
        Assert.assertEquals("2026-11-01 01:30:00",
                DebeziumConverter.TimestampConverter.convert(overlapDigits, ClickHouseDataType.DateTime,
                        utc, chicago, chicago));
        // A same-zone decode is digits, not an instant: unchanged in any column zone.
        Assert.assertEquals("2026-11-01 07:30:00.000",
                DebeziumConverter.TimestampConverter.convert(overlapDigits, ClickHouseDataType.DateTime64,
                        chicago, chicago, chicago));
        Assert.assertEquals("2026-11-01 07:30:00.00000000",
                DebeziumConverter.MicroTimestampConverter.convert(overlapDigits * 1000L, chicago, chicago,
                        ClickHouseDataType.DateTime64, chicago));
    }

    /**
     * Spec 07.03 section 3.1.3: ClickHouse parses a DateTime literal in the
     * COLUMN's zone, so an instant must be rendered in that zone. With the
     * session zone America/Chicago and the column declared 'UTC' (what
     * auto-create emits), rendering in the session zone stored every instant
     * six hours early.
     */
    @Test
    public void testTimestampIntoUtcColumnWhenServerZoneIsChicago() {
        ZoneId chicago = ZoneId.of("America/Chicago");
        ZoneId utc = ZoneId.of("UTC");

        // TIMESTAMP (an instant): rendered in the column zone.
        Assert.assertEquals("2022-01-01 16:00:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2022-01-01T16:00:00Z", utc));
        Assert.assertEquals("2022-01-01 10:00:00.000000",
                DebeziumConverter.ZonedTimestampConverter.convert("2022-01-01T16:00:00Z", chicago));

        // DATETIME with an explicitly different source zone (UTC) and session
        // zone (Chicago): the digits 10:00 are the instant 10:00Z
        // (1641031200), bound into a DateTime64 column as epoch text whether
        // the column declares a zone or not (section 3.1.4) ...
        long digits = datetimeDigitsAsUtcEpochMillis(LocalDateTime.of(2022, 1, 1, 10, 0, 0));
        Assert.assertEquals("1641031200.000000",
                DebeziumConverter.TimestampConverter.convert(digits, ClickHouseDataType.DateTime64, utc, chicago, utc));
        Assert.assertEquals("1641031200.000000",
                DebeziumConverter.MicroTimestampConverter.convert(digits * 1000L, utc, chicago, ClickHouseDataType.DateTime64, utc));
        Assert.assertEquals("1641031200.000000",
                DebeziumConverter.TimestampConverter.convert(digits, ClickHouseDataType.DateTime64, utc, chicago, null));
        Assert.assertEquals("1641031200.000000",
                DebeziumConverter.TimestampConverter.convert(digits, ClickHouseDataType.DateTime64, utc, chicago));
        Assert.assertEquals("1641031200.000000",
                DebeziumConverter.MicroTimestampConverter.convert(digits * 1000L, utc, chicago, ClickHouseDataType.DateTime64, null));
        // ... while a DateTime column takes digits: in the column zone when
        // the column declares one, in the session zone when it declares none.
        Assert.assertEquals("2022-01-01 10:00:00",
                DebeziumConverter.TimestampConverter.convert(digits, ClickHouseDataType.DateTime, utc, chicago, utc));
        Assert.assertEquals("2022-01-01 04:00:00",
                DebeziumConverter.TimestampConverter.convert(digits, ClickHouseDataType.DateTime, utc, chicago, null));
        Assert.assertEquals("2022-01-01 04:00:00",
                DebeziumConverter.TimestampConverter.convert(digits, ClickHouseDataType.DateTime, utc, chicago));

        // Same-zone digits decode (section 3.1.1) does not depend on the column zone.
        Assert.assertEquals("2022-01-01 10:00:00.000",
                DebeziumConverter.TimestampConverter.convert(digits, ClickHouseDataType.DateTime64, chicago, chicago, utc));
        Assert.assertEquals("2022-01-01 10:00:00.00000000",
                DebeziumConverter.MicroTimestampConverter.convert(digits * 1000L, chicago, chicago, ClickHouseDataType.DateTime64, utc));
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

}
