package com.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.clickhouse.client.data.ClickHouseArrayValue;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.junit.Assert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.altinity.clickhouse.sink.connector.metadata.DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32;
import static com.altinity.clickhouse.sink.connector.metadata.DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32;

public class DebeziumConverterTest {

    @Test
    public void testMicroTimeConverter() {

        Object timeInMicroSeconds = 3723000000L;
        String formattedTime = DebeziumConverter.MicroTimeConverter.convert(timeInMicroSeconds);

        Assert.assertTrue(formattedTime.equalsIgnoreCase("01:02:03"));
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


        Timestamp result = DebeziumConverter.MicroTimestampConverter.convert(1664416228000000L);
        System.out.println("");

        Timestamp result2 = DebeziumConverter.MicroTimestampConverter.convert(253402300799999990L);
        System.out.println("");
    }

    @Test
    public void testTimestampConverter() {

        Object timestampEpoch = 1640995260000L;
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpoch, false));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("1640995260000"));
    }

    @Test
    public void testTimestampConverterMinRange() {

        Object timestampEpoch = -2166681362000L;
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpoch, false));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("-1420070400000"));
    }

    @Test
    public void testTimestampConverterMaxRange() {

        Object timestampEpoch = 4807440238000L;
        String formattedTimestamp = String.valueOf(DebeziumConverter.TimestampConverter.convert(timestampEpoch, false));

        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("4807440238000"));
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
        Assert.assertTrue(formattedTimestamp.equalsIgnoreCase("2021-12-31 19:01:00"));

        String formattedTimestampWMicroSeconds = DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.999999Z");
        Assert.assertTrue(formattedTimestampWMicroSeconds.equalsIgnoreCase("2038-01-19 03:14:07.999999"));

        String formattedTimestamp3 = DebeziumConverter.ZonedTimestampConverter.convert("2038-01-19T03:14:07.99Z");
        Assert.assertTrue(formattedTimestamp3.equalsIgnoreCase("2038-01-19 03:14:07.99"));

    }

    @Test
    public void testCheckIfDateTimeExceedsSupportedRange() {
        DebeziumConverter.TimestampConverter.convert(1665076675000L, false);
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
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config, null);
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