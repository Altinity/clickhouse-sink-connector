package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

@Testcontainers
@Tag("datetime")
@DisplayName("Integration test that tests replication of data types and validates datetime," +
        " date limits with no timezone values and MySQL Point Data typek     set on CH and MySQL")
public class CreateTableDataTypesIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("data_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @Test
    public void testCreateTable() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);

        Properties props = getDebeziumProperties();
        executorService.execute(() -> {
            try {

                props.setProperty("database.include.list", "datatypes");

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService() , false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(40000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        DBMetadata metadata = new DBMetadata(props);
        Connection conn = writer.getConnection();
        Map<String, String> decimalTable = metadata.getColumnsDataTypesForTable(conn, "numeric_types_DECIMAL_65_30", "datatypes");
        Map<String, String> dateTimeTable6 = metadata.getColumnsDataTypesForTable(conn, "temporal_types_DATETIME6", "datatypes");
        Map<String, String> dateTimeTable2 = metadata.getColumnsDataTypesForTable(conn, "temporal_types_DATETIME2", "datatypes");

        Map<String, String> timestampTable = metadata.getColumnsDataTypesForTable(conn, "temporal_types_TIMESTAMP6", "datatypes");

        // Validate all decimal records.
        Assert.assertTrue(decimalTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(decimalTable.get("Minimum_Value").equalsIgnoreCase("Decimal(65, 30)"));
        Assert.assertTrue(decimalTable.get("Zero_Value").equalsIgnoreCase("Decimal(65, 30)"));
        Assert.assertTrue(decimalTable.get("Maximum_Value").equalsIgnoreCase("Decimal(65, 30)"));


        // Validate dateTime64 records.
        Assert.assertTrue(dateTimeTable6.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(dateTimeTable6.get("Minimum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(dateTimeTable6.get("Mid_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(dateTimeTable6.get("Maximum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(dateTimeTable6.get("Null_Value").equalsIgnoreCase("Nullable(DateTime64(6))"));

        Assert.assertTrue(dateTimeTable2.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(dateTimeTable2.get("Minimum_Value").equalsIgnoreCase("DateTime64(2)"));
        Assert.assertTrue(dateTimeTable2.get("Mid_Value").equalsIgnoreCase("DateTime64(2)"));
        Assert.assertTrue(dateTimeTable2.get("Maximum_Value").equalsIgnoreCase("DateTime64(2)"));
        Assert.assertTrue(dateTimeTable2.get("Null_Value").equalsIgnoreCase("Nullable(DateTime64(2))"));

        // Validate timestamp records
        Assert.assertTrue(timestampTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(timestampTable.get("Minimum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(timestampTable.get("Mid_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(timestampTable.get("Maximum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(timestampTable.get("Null_Value").equalsIgnoreCase("Nullable(DateTime64(6))"));

        writer.getConnection().close();
        //Thread.sleep(10000);

        writer = ITCommon.getDBWriter(clickHouseContainer);
        // Validate temporal_types_DATE data.
        ResultSet dateResult = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATE", writer.getConnection());

        while(dateResult.next()) {
            Assert.assertTrue(dateResult.getDate("Minimum_Value").toString().equalsIgnoreCase("1900-01-01"));
            Assert.assertTrue(dateResult.getDate("Mid_Value").toString().equalsIgnoreCase("2022-09-29"));
            Assert.assertTrue(dateResult.getDate("Maximum_Value").toString().equalsIgnoreCase("2299-12-31"));
        }
        // Validate temporal_types_DATETIME data.
        ResultSet dateTimeResult = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME", writer.getConnection());

        /**
        DATE TIME
        1900-01-01 18:09:24.0
        2022-09-28 20:47:46.0
        1970-05-01 07:43:11.999
        DATE TIME 1
        1900-01-01 18:09:24.0
        2022-09-28 20:48:25.0
        1970-05-01 07:43:11.999
        DATE TIME 2
        1900-01-01 18:09:24.0
        2022-09-28 20:49:05.0
        1970-05-01 07:43:11.999
        DATE TIME 3
        2022-09-28 20:49:22.0
        1970-05-01 07:43:11.999
        1900-01-01 18:09:24.0
        DATE TIME 4
        2022-09-28 20:50:12.123
        2299-12-31 17:59:59.999
        1900-01-01 18:09:24.0
        DATE TIME 5
        2022-09-28 20:50:28.123
        2299-12-31 17:59:59.999
        1900-01-01 18:09:24.0
        DATE TIME 6
        2022-09-28 20:50:56.123
        2299-12-31 17:59:59.999
        1900-01-01 18:09:24.0
        DATE TIME 6
        2022-09-28 20:50:56.1
        2299-12-31 17:59:59.999
        1900-01-01 18:09:24.0
         **/
        while(dateTimeResult.next()) {
            System.out.println("DATE TIME");

            System.out.println(dateTimeResult.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
            Assert.assertTrue(dateTimeResult.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 06:47:46.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MAX));
        }

        // DATETIME1
        ResultSet dateTimeResult1 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME1", writer.getConnection());
        while(dateTimeResult1.next()) {
            System.out.println("DATE TIME 1");


            System.out.println(dateTimeResult1.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult1.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 06:48:25.1"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_1_MAX));
        }

        // DATETIME2
        ResultSet dateTimeResult2 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME2", writer.getConnection());
        while(dateTimeResult2.next()) {
            System.out.println("DATE TIME 2");

            System.out.println(dateTimeResult2.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Minimum_Value").toString());


            Assert.assertTrue(dateTimeResult2.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 06:49:05.12"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_2_MAX));
        }

        // DATETIME3
        ResultSet dateTimeResult3 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME3", writer.getConnection());
        while(dateTimeResult3.next()) {
            System.out.println("DATE TIME 3");

            System.out.println(dateTimeResult3.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult3.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 06:49:22.123"));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_3_MAX));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
        }

        // DATETIME4
        ResultSet dateTimeResult4 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME4", writer.getConnection());
        while(dateTimeResult4.next()) {
            System.out.println("DATE TIME 4");

            System.out.println(dateTimeResult4.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult4.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 06:50:12.1234"));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_4_MAX));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));

        }


        // DATETIME5
        ResultSet dateTimeResult5 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME5", writer.getConnection());
        while(dateTimeResult5.next()) {
            System.out.println("DATE TIME 5");

            System.out.println(dateTimeResult5.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult5.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 06:50:28.12345"));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_5_MAX));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));

        }

        // DATETIME6
        ResultSet dateTimeResult6 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME6", writer.getConnection());
        while(dateTimeResult6.next()) {
            System.out.println("DATE TIME 6");

            System.out.println(dateTimeResult6.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult6.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 06:50:56.123456"));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_6_MAX));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
            break;
        }

        // validate POINT data type
        // Create a new table with POINT data type
        // Crate a new table on MySQL with POINT data type
        String createTableWithPoint = "CREATE TABLE employees.point_table (id int not null PRIMARY KEY, c1 int, c2 int, c3a POINT, c3b POINT, f1 float(10), f2 decimal(8,4))";
        ITCommon.connectToMySQL(mySqlContainer).createStatement().execute(createTableWithPoint);

        // Sleep for 10 seconds to allow the table to be replicated
        Thread.sleep(10000);

        // Insert a new row into the table
        ITCommon.connectToMySQL(mySqlContainer).createStatement().execute("INSERT INTO employees.point_table (id, c1, c2, c3a, c3b, f1, f2) values (1, 123, 456, POINT(1.0,2.0), POINT(3.0,4.0), 100.20, 100.20)");

        Thread.sleep(10000);
        ResultSet rs = ITCommon.executeQueryWithResultSet("select * from employees.point_table", writer.getConnection());
        boolean pointResultValidated = false;
        while(rs.next()) {
            pointResultValidated = true;
            String c3a = rs.getString("c3a");
            String c3b = rs.getString("c3b");
            Assert.assertTrue(c3a.equalsIgnoreCase("(1.0,2.0)"));
            Assert.assertTrue(c3b.equalsIgnoreCase("(3.0,4.0)"));
        }
        Assert.assertTrue(pointResultValidated);
        String createTableWithGeometry = "CREATE TABLE employees.locations ( id INT not null AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100), location GEOMETRY)";
        ITCommon.connectToMySQL(mySqlContainer).createStatement().execute(createTableWithGeometry);
        Thread.sleep(10000);

        // Insert a new row into the table
        ITCommon.connectToMySQL(mySqlContainer).createStatement().execute("INSERT INTO locations (name, location)\n" +
                "VALUES ('Route', ST_GeomFromText('LINESTRING(0 0, 1 1, 2 2)'));\n");
        // Validate the row inserted to locations table.
        Thread.sleep(10000);


        ResultSet rs2 = ITCommon.connectToMySQL(mySqlContainer).createStatement().executeQuery("SELECT ST_AsText(location) as location FROM employees.locations");
        boolean geometryResultValidated = false;

        while(rs2.next()) {
            geometryResultValidated = true;
            String c3a = rs2.getString("location");
            Assert.assertTrue(c3a.equalsIgnoreCase("LINESTRING(0 0,1 1,2 2)"));
        }
        Assert.assertTrue(geometryResultValidated);


        // Check if show create table result for integer_types_bool 
//            ┌─statement─────────────────────────────────────────┐
// 1. │ CREATE TABLE employees.integer_types_BOOL        ↴│
//    │↳(                                                ↴│
//    │↳    `Type` String,                               ↴│
//    │↳    `Minimum_Value` Int8,                        ↴│
//    │↳    `Maximum_Value` Int8,                        ↴│
//    │↳    `Null_Value` Nullable(Int8),                 ↴│
//    │↳    `_version` UInt64,                           ↴│
//    │↳    `is_deleted` UInt8                           ↴│
//    │↳)                                                ↴│
//    │↳ENGINE = ReplacingMergeTree(_version, is_deleted)↴│
//    │↳ORDER BY Type                                    ↴│
//    │↳SETTINGS index_granularity = 8192                 │
//    └───────────────────────────────────────────────────┘
        ResultSet rs3 = ITCommon.executeQueryWithResultSet("show create table employees.integer_types_BOOL", writer.getConnection());
        boolean integerTypesBoolResultValidated = false;
        while(rs3.next()) {
            integerTypesBoolResultValidated = true;
            String createTableDML = rs3.getString(1);
            Assert.assertTrue(createTableDML.equalsIgnoreCase("CREATE TABLE employees.integer_types_BOOL\n" +
                "(\n" +
                "    `Type` String,\n" +
                "    `Minimum_Value` Int8,\n" +
                "    `Maximum_Value` Int8,\n" +
                "    `Null_Value` Nullable(Int8),\n" +
                "    `_version` UInt64,\n" +
                "    `is_deleted` UInt8\n" +
                ")\n" +
                "ENGINE = ReplacingMergeTree(_version, is_deleted)\n" +
                "ORDER BY Type\n" +
                "SETTINGS index_granularity = 8192"));
        }
        Assert.assertTrue(integerTypesBoolResultValidated);

        // check if show create table result for integer_types_bigint
//         1. │ CREATE TABLE employees.integer_types_BIGINT      ↴│
//    │↳(                                                ↴│
//    │↳    `Type` String,                               ↴│
//    │↳    `Storage_Bytes` Int32,                       ↴│
//    │↳    `Minimum_Value_Signed` Int64,                ↴│
//    │↳    `Minimum_Value_Unsigned` UInt64,             ↴│
//    │↳    `Maximum_Value_Signed` Int64,                ↴│
//    │↳    `Maximum_Value_Unsigned` UInt64,             ↴│
//    │↳    `Null_Value_Signed` Nullable(Int64),         ↴│
//    │↳    `Null_Value_Unsigned` Nullable(UInt64),      ↴│
//    │↳    `_version` UInt64,                           ↴│
//    │↳    `is_deleted` UInt8                           ↴│
//    │↳)                                                ↴│
//    │↳ENGINE = ReplacingMergeTree(_version, is_deleted)↴│
//    │↳ORDER BY Type                                    ↴│
//    │↳SETTINGS index_granularity = 8192   
//        
        ResultSet rs4 = ITCommon.executeQueryWithResultSet("show create table employees.integer_types_BIGINT", writer.getConnection());
        boolean integerTypesBigintResultValidated = false;
        while(rs4.next()) {
            integerTypesBigintResultValidated = true;
            String createTableDML = rs4.getString(1);
            Assert.assertTrue(createTableDML.equalsIgnoreCase("CREATE TABLE employees.integer_types_BIGINT\n" +
                "(\n" +
                "    `Type` String,\n" +
                "    `Storage_Bytes` Int32,\n" +
                "    `Minimum_Value_Signed` Int64,\n" +
                "    `Minimum_Value_Unsigned` UInt64,\n" +
                "    `Maximum_Value_Signed` Int64,\n" +
                "    `Maximum_Value_Unsigned` UInt64,\n" +
                "    `Null_Value_Signed` Nullable(Int64),\n" +
                "    `Null_Value_Unsigned` Nullable(UInt64),\n" +
                "    `_version` UInt64,\n" +
                "    `is_deleted` UInt8\n" +
                ")\n" +
                "ENGINE = ReplacingMergeTree(_version, is_deleted)\n" +
                "ORDER BY Type\n" +
                "SETTINGS index_granularity = 8192"));
        }
        Assert.assertTrue(integerTypesBigintResultValidated);
        
        
        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();

        HikariDbSource.close();
    }
}
