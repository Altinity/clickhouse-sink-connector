package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.value.ClickHouseArrayValue;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static java.time.Instant.ofEpochMilli;


public class DebeziumConverterTest {

    @Test
    @DisplayName("Test timestamp converter for multiple timezones.")
    public void testTimestampConverter() {

        Object timestampEpoch = LocalDateTime.of(2022, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;
        Object timestampEpoch2 = LocalDateTime.of(2022, 9, 29, 01 , 48, 25 ,100).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;

        Instant dateTimeMax = Instant.from(ofEpochMilli(DataTypeRange.DATETIME32_MAX));

        String formattedTimestamp = DebeziumConverter.TimestampConverter.convert(timestampEpoch, ClickHouseDataType.DateTime64, ZoneId.of("UTC"));
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2022-01-01 00:01:00.000"));

        String formattedTimestamp2 = DebeziumConverter.TimestampConverter.convert(timestampEpoch2, ClickHouseDataType.DateTime, ZoneId.of("UTC"));
        Assert.assertTrue(formattedTimestamp2.equalsIgnoreCase("2022-09-29 01:48:25"));
        // 6 hours difference.
        String timestampWithChicagoTZ = DebeziumConverter.TimestampConverter.convert(timestampEpoch, ClickHouseDataType.DateTime64, ZoneId.of("America/Chicago"));
        Assert.assertTrue(timestampWithChicagoTZ.equalsIgnoreCase("2021-12-31 18:01:00.000"));

        String timestampWithPacificTZ = DebeziumConverter.TimestampConverter.convert(timestampEpoch, ClickHouseDataType.DateTime64, ZoneId.of("America/Los_Angeles"));
        Assert.assertTrue(timestampWithPacificTZ.equalsIgnoreCase("2021-12-31 16:01:00.000"));
    }

    @Test
    @DisplayName("Test timestamp converter(MIN) when clickhouse columns are DateTime and DateTime64, min limit is different for DateTime and DateTime64")
    public void testTimestampConverterMinRange() {

        Object timestampEpochDateTime = LocalDateTime.of(1960, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000;
        String result = DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime32, ZoneId.of("UTC"));
        Assert.assertTrue(result.equalsIgnoreCase("1970-01-01 00:00:00"));

        //Clickhouse column DateTime64
        String dateTime64Result = DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime64, ZoneId.of("UTC"));
        Assert.assertTrue(dateTime64Result.equalsIgnoreCase("1960-01-01 00:01:00.000"));
    }

    @Test
    @DisplayName("Test timestamp converter(MAX) when clickhouse columns are DateTime and DateTime64, min limit is different for DateTime and DateTime64")
    public void testTimestampConverterMaxRange() {

        //DateTime64
        Object timestampEpochDateTime = LocalDateTime.of(2289, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime64, ZoneId.of("UTC")));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2289-01-01 00:01:00.000"));

        //DateTime
        String formattedTimestampDate = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpochDateTime, ClickHouseDataType.DateTime, ZoneId.of("UTC")));
        Assert.assertTrue(formattedTimestampDate.equalsIgnoreCase("2106-02-07 06:28:15"));
    }


    @Test
    @DisplayName("Test Microtimestamp converter- for DATETIME(4,5,6) and can map to DateTime or DateTime64 in ClickHouse")
    public void testMicroTimestampConverter() {

        long timestampEpoch = LocalDateTime.of(2022, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).plus(222, ChronoUnit.MILLIS).toInstant().toEpochMilli() * 1000;
        timestampEpoch += 222;
        // UTC timezone
        String formattedTimestamp = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2022-01-01 00:01:00.22222200"));

        // America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("2021-12-31 18:01:00.22222200"));

        // America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("2021-12-31 16:01:00.22222200"));

    }

    @Test
    @DisplayName("Test Microtimestamp converter(MIN) for DATETIME(4,5,6) and can map to DateTime or DateTime64 in ClickHouse")
    public void testMicroTimestampConverterMin() {

        Object timestampEpoch = LocalDateTime.of(1000, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;

        // DateTime64 and UTC timezone
        String formattedTimestamp = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("1900-01-01 00:00:00.00000000"));

        // DateTime64 and America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("1899-12-31 18:00:00.00000000"));

        // DateTime64 and America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("1899-12-31 16:00:00.00000000"));

        // DateTime32 and UTC timezone
        String formattedTimestampDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampDate32.equalsIgnoreCase("1970-01-01 00:00:00"));

        // DateTime32 and America/Chicago timezone.
        String formattedTimestampChicagoTZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampChicagoTZDate32.equalsIgnoreCase("1969-12-31 18:00:00"));

        // DateTime32 and America/Los Angeles timezone.
        String formattedTimestampLATZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampLATZDate32.equalsIgnoreCase("1969-12-31 16:00:00"));
    }

    @Test
    @DisplayName("Test Microtimestamp converter(MAX) for DATETIME(4,5,6) and can map to DateTime or DateTime64 in ClickHouse")
    public void testMicroTimestampConverterMax() {

        Object timestampEpoch = LocalDateTime.of(3000, 1, 1, 0, 1, 0).atZone(ZoneId.of("UTC")).toEpochSecond() * 1000 * 1000;

        // DateTime64 and UTC timezone
        String formattedTimestamp = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase(DataTypeRange.DATETIME64_6_MAX));

        // DateTime64 and America/Chicago timezone.
        String formattedTimestampChicagoTZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampChicagoTZ.equalsIgnoreCase("2299-12-31 17:59:59.99999999"));

        // DateTime64 and America/Los Angeles timezone.
        String formattedTimestampLATZ = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime64);
        Assert.assertTrue(formattedTimestampLATZ.equalsIgnoreCase("2299-12-31 15:59:59.99999999"));

        // DateTime32 and UTC timezone
        String formattedTimestampDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("UTC"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampDate32.equalsIgnoreCase("2106-02-07 06:28:15"));

        // DateTime32 and America/Chicago timezone.
        String formattedTimestampChicagoTZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Chicago"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampChicagoTZDate32.equalsIgnoreCase("2106-02-07 00:28:15"));

        // DateTime32 and America/Los Angeles timezone.
        String formattedTimestampLATZDate32 = DebeziumConverter.MicroTimestampConverter.convert(timestampEpoch, ZoneId.of("America/Los_Angeles"), ClickHouseDataType.DateTime);
        Assert.assertTrue(formattedTimestampLATZDate32.equalsIgnoreCase("2106-02-06 22:28:15"));
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

        Object timeInMicroSeconds = LocalTime.of(10, 1, 1, 1).toEpochSecond(LocalDate.now(), ZoneOffset.UTC);
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(timeInMicroSeconds);

       // Assert.assertTrue(formattedTime.equalsIgnoreCase("00:28:21.424861"));

        Object timePacificTZ = ZonedDateTime.of(2024, 1, 1, 1, 1, 1, 1, ZoneId.of("America/Los_Angeles")).toEpochSecond() * 1000 * 1000;
        String formattedTimePacificTZ = DebeziumConverter.MicroTimeConverter.convert(timePacificTZ);
        Assert.assertTrue(formattedTimePacificTZ.equalsIgnoreCase("09:01:01.000000"));
    }


    @Test
    @Tag("IntegrationTest")
    public void testBatchArrays() {
        String hostName = "localhost";
        Integer port = 8123;

        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "test_ch_jdbc_complex_2";

        Properties properties = new Properties();
        properties.setProperty("client_name", "Test_1");

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        String jdbcUrl = DbWriter.getConnectionString(hostName, port, database);
        ClickHouseConnection conn1 = DbWriter.createConnection(jdbcUrl, "client_1", userName, password, config);
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config, null, conn1);
        String url = dbWriter.getConnectionString(hostName, port, database);

        String insertQueryTemplate = "insert into test_ch_jdbc_complex_2(col1, col2, col3, col4, col5, col6) values(?, ?, ?, ?, ?, ?)";
        try {
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);
            ClickHouseConnection conn = dataSource.getConnection(userName, password);

            PreparedStatement ps = conn.prepareStatement(insertQueryTemplate);

            boolean[] boolArray = {true, false, true};
            float[] floatArray = {0.012f, 0.1255f, 1.22323f};
            ps.setObject(1, "test_string");
            ps.setBoolean(2, true);
            ps.setObject(3, ClickHouseArrayValue.of(new Object[] {Arrays.asList("one", "two", "three")}));
            ps.setObject(4, ClickHouseArrayValue.ofEmpty().update(boolArray));
            ps.setObject(5, ClickHouseArrayValue.ofEmpty().update(floatArray));

            Map<String, Float> test_map = new HashMap<String, Float>();
            test_map.put("2", 0.02f);
            test_map.put("3", 0.02f);

            ps.setObject(6, Collections.unmodifiableMap(test_map));

//            ps.setObject(5, ClickHouseArrayValue.of(new Object[]
//                    {
//                            Arrays.asList(new Float(0.2), new Float(0.3))
//                    }));
            ps.addBatch();
            ps.executeBatch();

        } catch(Exception e) {
            System.out.println("Error connecting" + e);
        }

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

}