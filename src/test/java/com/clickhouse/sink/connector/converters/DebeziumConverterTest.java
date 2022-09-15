package com.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class DebeziumConverterTest {

    @Test
    public void testMicroTimeConverter() {

        Object timeInMicroSeconds = 3723000000L;
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(timeInMicroSeconds);

        //Assert.assertTrue(formattedTime.equalsIgnoreCase("20:02:03"));
    }

    @Test
    public void testTimestampConverter() {

        Object timestampEpoch = 1640995260000L;
        String formattedTimestamp = DebeziumConverter.TimestampConverter.convert(timestampEpoch, false);

        //Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31 19:01:00"));
    }

    @Test
    public void testTimestampConverterMinRange() {

        Object timestampEpoch = -2166681362000L;
        String formattedTimestamp = DebeziumConverter.TimestampConverter.convert(timestampEpoch, false);

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("1970-01-01 00:00:00"));
    }

    @Test
    public void testTimestampConverterMaxRange() {

        Object timestampEpoch = 4807440238000L;
        String formattedTimestamp = DebeziumConverter.TimestampConverter.convert(timestampEpoch, false);

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2106-02-07 06:28:15"));
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

        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE));
    }
    @Test
    public void testDateConverterMaxRange() {

        Integer date = 450000;
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date);

        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE));
    }

    @Test
    public void testZonedTimestampConverter() {

        String formattedTimestamp = DebeziumConverter.ZonedTimestampConverter.convert("2021-12-31T19:01:00Z");

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31 19:01:00"));
    }

    @Test
    public void testMicroTimestampConverter() {

        String convertedString = DebeziumConverter.MicroTimestampConverter.convert(-248313600000000L);
        Assert.assertTrue(convertedString.equalsIgnoreCase("1962-02-18 00:00:00"));
    }
}