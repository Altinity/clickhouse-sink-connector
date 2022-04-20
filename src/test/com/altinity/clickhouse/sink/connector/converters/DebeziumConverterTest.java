package com.altinity.clickhouse.sink.connector.converters;

import org.junit.Assert;
import org.junit.Test;

public class DebeziumConverterTest {

    @Test
    public void testMicroTimeConverter() {

        Object timeInMicroSeconds = 3723000000L;
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(timeInMicroSeconds);

        Assert.assertTrue(formattedTime.equalsIgnoreCase("20:02:03"));
    }

    @Test
    public void testTimestampConverter() {

        Object timestampEpoch = 1640995260000L;
        String formattedTimestamp = DebeziumConverter.TimestampConverter.convert(timestampEpoch);

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31T19:01:00"));
    }

    @Test
    public void testDateConverter() {

        Integer date = 3652;
        java.sql.Date formattedDate = DebeziumConverter.DateConverter.convert(date);

        Assert.assertTrue(formattedDate.toString().equalsIgnoreCase("1979-12-31"));
    }

    @Test
    public void testZonedTimestampConverter() {

        String formattedTimestamp = DebeziumConverter.ZonedTimestampConverter.convert("2021-12-31T19:01:00Z");

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31 19:01:00"));
    }
}