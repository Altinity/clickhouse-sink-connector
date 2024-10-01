package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@DisplayName("Integration test that tests replication of data types and validates datetime," +
        " date limits with no timezone values and MySQL Point Data typek     set on CH and MySQL")
public class CreateTableDataTypesIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
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
        executorService.execute(() -> {
            try {

                Properties props = getDebeziumProperties();
                props.setProperty("database.include.list", "datatypes");

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()),
                                "employees"), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null,
                chConn);

        Map<String, String> decimalTable = writer.getColumnsDataTypesForTable("numeric_types_DECIMAL_65_30");
        Map<String, String> dateTimeTable6 = writer.getColumnsDataTypesForTable("temporal_types_DATETIME6");
        Map<String, String> dateTimeTable2 = writer.getColumnsDataTypesForTable("temporal_types_DATETIME2");

        Map<String, String> timestampTable = writer.getColumnsDataTypesForTable("temporal_types_TIMESTAMP6");

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

        //String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), "employees");
        ClickHouseConnection connection = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

         writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);
        // Validate temporal_types_DATE data.
        ResultSet dateResult = writer.executeQueryWithResultSet("select * from temporal_types_DATE");

        while(dateResult.next()) {
            Assert.assertTrue(dateResult.getDate("Minimum_Value").toString().equalsIgnoreCase("1900-01-01"));
            Assert.assertTrue(dateResult.getDate("Mid_Value").toString().equalsIgnoreCase("2022-09-29"));
            Assert.assertTrue(dateResult.getDate("Maximum_Value").toString().equalsIgnoreCase("2299-12-31"));
        }
        // Validate temporal_types_DATETIME data.
        ResultSet dateTimeResult = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME");

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
            Assert.assertTrue(dateTimeResult.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:47:46.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MAX));
        }

        // DATETIME1
        ResultSet dateTimeResult1 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME1");
        while(dateTimeResult1.next()) {
            System.out.println("DATE TIME 1");


            System.out.println(dateTimeResult1.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult1.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:48:25.1"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_1_MAX));
        }

        // DATETIME2
        ResultSet dateTimeResult2 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME2");
        while(dateTimeResult2.next()) {
            System.out.println("DATE TIME 2");

            System.out.println(dateTimeResult2.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Minimum_Value").toString());


            Assert.assertTrue(dateTimeResult2.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:49:05.12"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_2_MAX));
        }

        // DATETIME3
        ResultSet dateTimeResult3 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME3");
        while(dateTimeResult3.next()) {
            System.out.println("DATE TIME 3");

            System.out.println(dateTimeResult3.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult3.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:49:22.123"));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_3_MAX));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
        }

        // DATETIME4
        ResultSet dateTimeResult4 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME4");
        while(dateTimeResult4.next()) {
            System.out.println("DATE TIME 4");

            System.out.println(dateTimeResult4.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult4.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:50:12.1234"));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_4_MAX));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));

        }


        // DATETIME5
        ResultSet dateTimeResult5 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME5");
        while(dateTimeResult5.next()) {
            System.out.println("DATE TIME 5");

            System.out.println(dateTimeResult5.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult5.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:50:28.12345"));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_5_MAX));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));

        }

        // DATETIME6
        ResultSet dateTimeResult6 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME6");
        while(dateTimeResult6.next()) {
            System.out.println("DATE TIME 6");

            System.out.println(dateTimeResult6.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult6.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:50:56.123456"));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_6_MAX));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DataTypeRange.DATETIME_MIN));
            break;
        }

        // validate POINT data type
        // Create a new table with POINT data type
        // Crate a new table on MySQL with POINT data type
        String createTableWithPoint = "CREATE TABLE employees.point_table (id int, c1 int, c2 int, c3a point, c3b point, f1 float(10), f2 decimal(8,4) primary key(id))";
        ITCommon.connectToMySQL(mySqlContainer).createStatement().execute(createTableWithPoint);

        // Sleep for 10 seconds to allow the table to be replicated
        Thread.sleep(10000);

        // Insert a new row into the table
        ITCommon.connectToMySQL(mySqlContainer).createStatement().execute("INSERT INTO employees.point_table (id, c1, c2, c3a, c3b, f1, f2) values (1, 123, 456, '(1,2)', '(3,4)', 100.20, 100.20)");

        Thread.sleep(10000);
        ResultSet rs = writer.executeQueryWithResultSet("select * from employees.point_table");
        boolean pointResultValidated = false;
        while(rs.next()) {
            pointResultValidated = true;
            Assert.assertTrue(rs.getString("c3a").equalsIgnoreCase("(1,2)"));
            Assert.assertTrue(rs.getString("c3b").equalsIgnoreCase("(3,4)"));
        }
        Assert.assertTrue(pointResultValidated);





        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();
    }
}
