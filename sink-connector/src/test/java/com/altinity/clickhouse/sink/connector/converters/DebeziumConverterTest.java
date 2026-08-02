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
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2022-01-01 00:01:00.222222"));

        // America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("2022-01-01 00:01:00.222222"));

        // America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("2022-01-01 00:01:00.222222"));

    }

    @Test
    @DisplayName("Test Microtimestamp converter(MIN) for DATETIME(4,5,6) and can map to DateTime or DateTime64 in ClickHouse")
    public void testMicroTimestampConverterMin() {

        Object timestampEpoch = LocalDateTime.of(1000, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;

        // DateTime64 and UTC timezone
        String formattedTimestamp = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("1900-01-01 00:00:00.000000"));

        // DateTime64 and America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("1900-01-01 00:00:00.000000"));

        // DateTime64 and America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("1900-01-01 00:00:00.000000"));

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
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2299-12-31 23:59:59.000000"));

        // DateTime64 and America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("2299-12-31 23:59:59.000000"));

        // DateTime64 and America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("2299-12-31 23:59:59.000000"));

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
    public void testMicroTimeConverter() {
        // io.debezium.time.MicroTime carries microseconds past midnight for a MySQL
        // TIME column — a duration, not an epoch instant, and it has no timezone.
        // The previous input was an epoch-second value (2024-01-01 in Los Angeles),
        // which only produced "09:01:01" because the old implementation wrapped it
        // through LocalTime (an implicit mod-24h truncation). MySQL TIME legally
        // ranges to 838:59:59, so wrapping was itself a data-corruption bug; the
        // converter now computes hours/minutes/seconds directly from the duration.
        Object nineOhOneOhOne = (9L * 3600L + 1L * 60L + 1L) * 1_000_000L;
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(nineOhOneOhOne);
        Assert.assertEquals("09:01:01.000000", formattedTime);
    }

    @Test
    @DisplayName("MicroTimeConverter handles zero microseconds")
    public void testMicroTimeConverterZero() {
        String result = DebeziumConverter.MicroTimeConverter.convert(0L);
        Assert.assertEquals("00:00:00.000000", result);
    }

    @Test
    @DisplayName("MicroTimeConverter handles exact 24-hour boundary (86400 seconds)")
    public void testMicroTimeConverter24Hours() {
        // 24 hours = 86400 seconds = 86400000000 microseconds
        long micros24h = 86400L * 1_000_000L;
        String result = DebeziumConverter.MicroTimeConverter.convert(micros24h);
        Assert.assertEquals("24:00:00.000000", result);
    }

    @Test
    @DisplayName("MicroTimeConverter handles MySQL TIME max: 838:59:59.000000")
    public void testMicroTimeConverterMySQLMax() {
        // 838 hours, 59 minutes, 59 seconds = (838*3600 + 59*60 + 59) * 1000000
        long maxMicros = (838L * 3600L + 59L * 60L + 59L) * 1_000_000L;
        String result = DebeziumConverter.MicroTimeConverter.convert(maxMicros);
        Assert.assertEquals("838:59:59.000000", result);
    }

    @Test
    @DisplayName("MicroTimeConverter handles negative MySQL TIME: -01:30:00.000000")
    public void testMicroTimeConverterNegative() {
        // -1 hour 30 minutes = -(1*3600 + 30*60) * 1000000
        long negativeMicros = -((1L * 3600L + 30L * 60L) * 1_000_000L);
        String result = DebeziumConverter.MicroTimeConverter.convert(negativeMicros);
        Assert.assertEquals("-01:30:00.000000", result);
    }

    @Test
    @DisplayName("MicroTimeConverter handles MySQL TIME min: -838:59:59.000000")
    public void testMicroTimeConverterMySQLMin() {
        // -838 hours, 59 minutes, 59 seconds
        long minMicros = -((838L * 3600L + 59L * 60L + 59L) * 1_000_000L);
        String result = DebeziumConverter.MicroTimeConverter.convert(minMicros);
        Assert.assertEquals("-838:59:59.000000", result);
    }

    @Test
    @DisplayName("MicroTimeConverter preserves microsecond precision")
    public void testMicroTimeConverterWithMicroseconds() {
        // 10:30:45.123456
        long micros = (10L * 3600L + 30L * 60L + 45L) * 1_000_000L + 123456L;
        String result = DebeziumConverter.MicroTimeConverter.convert(micros);
        Assert.assertEquals("10:30:45.123456", result);
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


    @Test
    @DisplayName("MicroTimeConverter accepts Integer value (Number cast safety)")
    public void testMicroTimeConverterWithIntegerValue() {
        // Before the fix, passing an Integer would throw ClassCastException
        // because the code did (Long) value instead of ((Number) value).longValue().
        // NOTE: an earlier revision declared an unused `Integer.valueOf(36061000000)`
        // here, which does not compile — 36061000000 exceeds int range. The test only
        // needs a genuine Integer, so the in-range value below is the actual subject.
        Object smallIntValue = Integer.valueOf(3600000);  // 3.6s expressed in microseconds
        String result = DebeziumConverter.MicroTimeConverter.convert(smallIntValue);
        Assert.assertNotNull("Should handle Integer input without ClassCastException", result);
    }


    @Test
    @DisplayName("MicroTimestampConverter accepts Integer value (Number cast safety)")
    public void testMicroTimestampConverterWithIntegerValue() {
        // Small epoch value as Integer — before fix, ClassCastException
        Object intValue = Integer.valueOf(1000000);  // 1 second in microseconds
        String result = DebeziumConverter.MicroTimestampConverter.convert(
            intValue, ZoneId.of("UTC"), ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertNotNull("Should handle Integer input without ClassCastException", result);
        Assert.assertTrue("Result should contain a date", result.contains("1970-01-01"));
    }


    @Test
    @DisplayName("TimestampConverter accepts Integer value (Number cast safety)")
    public void testTimestampConverterWithIntegerValue() {
        // 1000 ms = 1 second from epoch, as Integer
        Object intValue = Integer.valueOf(1000);
        String result = DebeziumConverter.TimestampConverter.convert(
            intValue, ClickHouseDataType.DateTime64, ZoneId.of("UTC"), ZoneId.of("UTC"));
        Assert.assertNotNull("Should handle Integer input without ClassCastException", result);
        Assert.assertTrue("Result should contain epoch date", result.contains("1970-01-01"));
    }


    @Test
    @DisplayName("TimestampConverter accepts Double value (Number cast safety)")
    public void testTimestampConverterWithDoubleValue() {
        // Debezium may sometimes produce Double for numeric fields
        Object doubleValue = Double.valueOf(1640995260000.0);  // 2022-01-01 00:01:00 UTC
        String result = DebeziumConverter.TimestampConverter.convert(
            doubleValue, ClickHouseDataType.DateTime64, ZoneId.of("UTC"), ZoneId.of("UTC"));
        Assert.assertNotNull("Should handle Double input without ClassCastException", result);
        Assert.assertTrue("Result should contain 2022", result.contains("2022"));
    }

    /**
     * The clamped value must still be serializable by the JDBC driver.
     *
     * <p>{@code BinaryStreamUtils.writeDecimal256} does not bound-check the
     * value it is handed; it first multiplies by {@code 10^scale} and only
     * then requires the <em>product</em> to satisfy
     * {@code -10^76 < v < 10^76}, exclusive at both ends. A clamp target of
     * {@code DECIMAL256_MIN}/{@code MAX} is itself exactly {@code 10^76}, so
     * it fails that check at <em>every</em> scale — including scale 0 — and
     * the batch the clamp was supposed to rescue is rejected with
     * {@code IllegalArgumentException} instead. That is the regression this
     * test pins: a {@code Decimal(64,18)} column produced a 95-digit product
     * and killed the insert.</p>
     *
     * <p>The Decimal128 bounds ({@code 10^38}) leave {@code 76 - 38 = 38}
     * digits of headroom, so they survive the check for every scale a real
     * column can pair with a magnitude that large. Note the ceiling is a
     * property of the driver's design, not of the clamp: because the bound is
     * applied post-scaling, <em>no</em> non-zero clamp target survives scale
     * 76 — a {@code Decimal256(76)} column is all-fractional and can only
     * hold values below 1. Asserting "every scale" would therefore assert an
     * impossible property, so the bound checked here is the measured one.</p>
     */
    @Test
    @DisplayName("Clamped decimal survives driver serialization")
    public void testDecimalClampSurvivesDriverScaling() {
        DebeziumConverter.BigDecimalConverter converter =
                new DebeziumConverter.BigDecimalConverter();
        java.math.BigDecimal clampedMin =
                converter.truncate(new java.math.BigDecimal("-1E+90"));
        java.math.BigDecimal clampedMax =
                converter.truncate(new java.math.BigDecimal("1E+90"));

        Assert.assertEquals("Under-range value must clamp to DECIMAL128_MIN",
                com.clickhouse.data.format.BinaryStreamUtils.DECIMAL128_MIN, clampedMin);
        Assert.assertEquals("Over-range value must clamp to DECIMAL128_MAX",
                com.clickhouse.data.format.BinaryStreamUtils.DECIMAL128_MAX, clampedMax);

        // The scale of the Decimal(64,18) column from the failing insert.
        final int REPRO_SCALE = 18;
        Assert.assertTrue(
                "Clamp must survive the driver bound at the scale that failed",
                driverAcceptsAfterScaling(clampedMin, REPRO_SCALE)
                        && driverAcceptsAfterScaling(clampedMax, REPRO_SCALE));

        // And the DECIMAL256 bounds must NOT — this is the regression itself,
        // asserted directly so a future re-widening of the clamp fails here.
        Assert.assertFalse(
                "DECIMAL256_MIN is exactly the driver's own exclusive bound "
                        + "and can never be a valid clamp target",
                driverAcceptsAfterScaling(
                        com.clickhouse.data.format.BinaryStreamUtils.DECIMAL256_MIN,
                        REPRO_SCALE));

        // Headroom check across the range of scales the Decimal128 clamp can
        // actually be paired with (38 digits of magnitude + scale < 76).
        for (int scale = 0; scale < 38; scale++) {
            Assert.assertTrue(
                    "Clamp overflows the driver bound at scale " + scale,
                    driverAcceptsAfterScaling(clampedMin, scale)
                            && driverAcceptsAfterScaling(clampedMax, scale));
        }
    }

    /**
     * Mirrors the bound check inside
     * {@code BinaryStreamUtils.writeDecimal256}: scale the value first, then
     * require the product to lie strictly inside +/-10^76.
     */
    private static boolean driverAcceptsAfterScaling(
            java.math.BigDecimal value, int scale) {
        java.math.BigDecimal scaled =
                value.multiply(java.math.BigDecimal.TEN.pow(scale));
        return scaled.abs().compareTo(java.math.BigDecimal.TEN.pow(76)) < 0;
    }

    /**
     * Values inside the clamp range must be returned untouched — the clamp is
     * a last-resort guard, not a general rescale.
     */
    @Test
    @DisplayName("In-range decimal is returned unchanged by the clamp")
    public void testDecimalWithinRangeIsUnchanged() {
        java.math.BigDecimal inRange = new java.math.BigDecimal("12345.678901");
        Assert.assertEquals(inRange,
                new DebeziumConverter.BigDecimalConverter().truncate(inRange));
    }

}
