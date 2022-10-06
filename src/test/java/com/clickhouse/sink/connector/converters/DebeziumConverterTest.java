package com.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static com.altinity.clickhouse.sink.connector.metadata.DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32;
import static com.altinity.clickhouse.sink.connector.metadata.DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32;

public class DebeziumConverterTest {

    @Test
    public void testMicroTimeConverter() {

        Object timeInMicroSeconds = 3723000000L;
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(timeInMicroSeconds);

        //Assert.assertTrue(formattedTime.equalsIgnoreCase("20:02:03"));
    }

    @Test
    public void testMicroTimestampConverter() {


        // With microseconds
//        String resultWMicroSeconds = DebeziumConverter.MicroTimestampConverter.convert(1665076675000000L);
//       // Assert.assertTrue(resultWMicroSeconds == 1665076675000L);
//
//        // With milliseconds
//        String resultWMilliSeconds = DebeziumConverter.MicroTimestampConverter.convert(1665076675000L);
        //Assert.assertTrue(resultWMilliSeconds == 1665076675L);


    }

    @Test
    public void testTimestampConverter() {

        Object timestampEpoch = 1640995260000L;
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpoch, false));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31 19:01:00"));
    }

    @Test
    public void testTimestampConverterMinRange() {

        Object timestampEpoch = -2166681362000L;
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpoch, false));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("1925-01-01 00:00:00"));
    }

    @Test
    public void testTimestampConverterMaxRange() {

        Object timestampEpoch = 4807440238000L;
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpoch, false));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2122-05-05 16:03:58"));
    }

    @Test
    public void testDateConverter() {

        Integer date = 3652;
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date);

        //Assert.assertTrue(formattedDate.toString().equalsIgnoreCase("1979-12-31"));
    }

    @Test
    public void testDateConverterMinRange() {

        Integer date = -144450000;
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date);
        SimpleDateFormat dt1 = new SimpleDateFormat("yyyy-MM-dd");
        String minSupportedDate = dt1.format(new Date(TimeUnit.DAYS.toMillis(CLICKHOUSE_MIN_SUPPORTED_DATE32))).toString();

        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase(minSupportedDate));
    }
    @Test
    public void testDateConverterMaxRange() {

        Integer date = 450000;
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date);

        SimpleDateFormat dt1 = new SimpleDateFormat("yyyy-MM-dd");
        String maxSupportedDate = dt1.format(new Date(TimeUnit.DAYS.toMillis(CLICKHOUSE_MAX_SUPPORTED_DATE32))).toString();
        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase(maxSupportedDate));
    }

    @Test
    public void testDateConverterWithinRange() {

        // Epoch (days)
        Integer epochInDays = 8249;
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(epochInDays);
        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase("1992-08-01"));
    }

    @Test
    public void testZonedTimestampConverter() {

        String formattedTimestamp = DebeziumConverter.ZonedTimestampConverter.convert("2021-12-31T19:01:00Z");
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31 19:01:00.000000"));

        String formattedTimestampWMicroSeconds = DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.999999Z");
        Assert.assertTrue(formattedTimestampWMicroSeconds.equalsIgnoreCase("2038-01-19 03:14:07.999999"));

        String formattedTimestamp3 = DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.99Z");
        Assert.assertTrue(formattedTimestamp3.equalsIgnoreCase("2038-01-19 03:14:07.990000"));

    }

    @Test
    public void testCheckIfDateTimeExceedsSupportedRange() {
        DebeziumConverter.TimestampConverter.convert(1665076675000L, false);
    }

}