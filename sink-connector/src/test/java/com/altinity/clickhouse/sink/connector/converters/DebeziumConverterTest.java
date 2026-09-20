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
