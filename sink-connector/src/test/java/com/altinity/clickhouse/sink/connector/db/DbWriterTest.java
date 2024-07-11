package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementExecutor;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.protocol.types.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.*;

@Testcontainers
public class DbWriterTest {

    static DbWriter writer;

    // will be started before and stopped after each test method
    @Container
    private static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("./init_clickhouse.sql");

    @BeforeAll
    public static void init() {

        clickHouseContainer.start();
        String hostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "employees";
        String userName = "default";
        String password = "";
        String tableName = "employees";

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, database);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password,
                config);
        writer = new DbWriter(hostName, port, database, tableName, userName, password, config, null, conn);

    }

    public static Struct getKafkaStruct() {
        Schema kafkaConnectSchema = SchemaBuilder
                .struct()
                .field("first_name", Schema.STRING_SCHEMA)
                .field("last_name", Schema.STRING_SCHEMA)
                .field("quantity", Schema.INT32_SCHEMA)
                .field("amount", Schema.FLOAT64_SCHEMA)
                .field("employed", Schema.BOOLEAN_SCHEMA)
                .build();

        Struct kafkaConnectStruct = new Struct(kafkaConnectSchema);
        kafkaConnectStruct.put("first_name", "John");
        kafkaConnectStruct.put("last_name", "Doe");
        kafkaConnectStruct.put("quantity", 100);
        kafkaConnectStruct.put("amount", 23.223);
        kafkaConnectStruct.put("employed", true);


        return kafkaConnectStruct;
    }

    @Test
    public void testGetConnectionUrl() {

        String hostName = "remoteClickHouse";
        Integer port = 8123;
        String database = "employees";
        String connectionUrl = writer.getConnectionString(hostName, port, database);

        Assert.assertEquals(connectionUrl, "jdbc:clickhouse://remoteClickHouse:8123/employees");
    }

    @Test
    public void testIsColumnTypeDate64() {
       boolean result = DbWriter.isColumnDateTime64("Nullable(DateTime64(3))");
    }
    @Test
    @Tag("IntegrationTest")
    public void testGetColumnsDataTypesForTable() {

        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "employees";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, database);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null, conn);
        Map<String, String> columnDataTypesMap = writer.getColumnsDataTypesForTable("employees");

        Assert.assertTrue(columnDataTypesMap.isEmpty() == false);
        Assert.assertTrue(columnDataTypesMap.size() == 44);

        String database2 = "employees2";
        String jdbcUrl2 = BaseDbWriter.getConnectionString(dbHostName, port, database2);
        ClickHouseConnection conn2 = DbWriter.createConnection(jdbcUrl2, "client_1", userName, password, new ClickHouseSinkConnectorConfig(new HashMap<>()));
        DbWriter writer2 = new DbWriter(dbHostName, port, database2, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null, conn2);
        Map<String, String> columnDataTypesMap2 = writer2.getColumnsDataTypesForTable("employees");

        Assert.assertTrue(columnDataTypesMap2.isEmpty() == false);
        Assert.assertTrue(columnDataTypesMap2.size() ==44);

    }

    @Test
    @Tag("IntegrationTest")
    public void testGetEngineType() {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "system";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, database);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));
        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null, conn);
        MutablePair<DBMetadata.TABLE_ENGINE, String> result = new DBMetadata().getTableEngineUsingShowTable(writer.getConnection(), "default", "employees");
        Assert.assertTrue(result.getLeft() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        Assert.assertTrue(result.getRight().equalsIgnoreCase("_version"));

        MutablePair<DBMetadata.TABLE_ENGINE, String> result_test = new DBMetadata().getTableEngineUsingShowTable(writer.getConnection(), "test", "employees");
        Assert.assertTrue(result_test.getLeft() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        Assert.assertTrue(result_test.getRight().equalsIgnoreCase("_version22"));

        MutablePair<DBMetadata.TABLE_ENGINE, String> result_employees = new DBMetadata().getTableEngineUsingShowTable(writer.getConnection(), "employees", "employees");
        Assert.assertTrue(result_employees.getLeft() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        Assert.assertTrue(result_employees.getRight().equalsIgnoreCase("_version_employees"));

        MutablePair<DBMetadata.TABLE_ENGINE, String> resultProducts = new DBMetadata().getTableEngineUsingShowTable(writer.getConnection(), "default", "products");
        Assert.assertTrue(resultProducts.getLeft() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE);
        Assert.assertTrue(resultProducts.getRight().equalsIgnoreCase("sign"));
    }

    @Test
    @Tag("IntegrationTest")
    public void testGetEngineTypeUsingSystemTables() {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "default";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        String jdbcUrl = BaseDbWriter.getConnectionString(dbHostName, port, database);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));
        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null, conn);
        MutablePair< DBMetadata.TABLE_ENGINE, String> result = new DBMetadata().getTableEngineUsingSystemTables(writer.getConnection(),
                database, "employees");
        Assert.assertTrue(result.getLeft() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);

        MutablePair<DBMetadata.TABLE_ENGINE, String> result_products = new DBMetadata().getTableEngineUsingSystemTables(writer.getConnection(),
                database, "products");
        Assert.assertTrue(result_products.getLeft() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE);

        // Table does not exist.
        MutablePair<DBMetadata.TABLE_ENGINE, String> result_registration = new DBMetadata().getTableEngineUsingSystemTables(writer.getConnection(),
                database, "registration");
        Assert.assertNull(result_registration.getLeft());

        MutablePair<DBMetadata.TABLE_ENGINE, String> ma_users_registration = new DBMetadata().getTableEngineUsingSystemTables(writer.getConnection(),
                "employees2", "ma_users");
        Assert.assertTrue(ma_users_registration.getLeft() == DBMetadata.TABLE_ENGINE.MERGE_TREE);


    }

    public static List<ClickHouseStruct> getSampleRecords() {
        List<ClickHouseStruct> records = new ArrayList<>();

        ClickHouseStruct ch1 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch2 = new ClickHouseStruct(8, "topic_1", getKafkaStruct(), 2, System.currentTimeMillis() ,null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch3 = new ClickHouseStruct(1000, "topic_1", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);


        ClickHouseStruct ch4 = new ClickHouseStruct(1020, "topic_1", getKafkaStruct(), 3, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch5 = new ClickHouseStruct(1400, "topic_2", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch6 = new ClickHouseStruct(1010, "topic_2", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);

        ClickHouseStruct ch7 = new ClickHouseStruct(-1, "topic_2", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch8 = new ClickHouseStruct(210, "topic_2", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);


        records.add(ch1);
        records.add(ch2);
        records.add(ch3);
        records.add(ch4);
        records.add(ch5);
        records.add(ch6);

        records.add(ch7);
        records.add(ch8);

        return records;
    }

    @Test
    public void testGroupRecords() {
        String hostName = "remoteClickHouse";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        String connectionUrl = writer.getConnectionString(hostName, port, database);
        Properties properties = new Properties();
        properties.setProperty("client_name", "Test_1");

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());

        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, database);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password, config);
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config, null, conn);

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();

        Map<TopicPartition, Long> result = new HashMap<>();
        GroupInsertQueryWithBatchRecords groupInsertQueryWithBatchRecords = new GroupInsertQueryWithBatchRecords();

        boolean resultStatus =groupInsertQueryWithBatchRecords.groupQueryWithRecords(getSampleRecords()
                , queryToRecordsMap, result, config, tableName, database, dbWriter.getConnection(), dbWriter.getColumnsDataTypesForTable(tableName));

        Assert.assertTrue(result.isEmpty() == false);

        long topic_1_2_offset = result.get(new TopicPartition("topic_1", 2));
        Assert.assertTrue(topic_1_2_offset == 1000);

        long topic_1_3_offset = result.get(new TopicPartition("topic_1", 3));
        Assert.assertTrue(topic_1_3_offset == 1020);

        long topic_2_2_offset = result.get(new TopicPartition("topic_2", 2));
        Assert.assertTrue(topic_2_2_offset == 1400);

    }

    @Test
    public void testGetClickHouseDataType() {
        String hostName = "remoteClickHouse";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        String connectionUrl = writer.getConnectionString(hostName, port, database);
        Properties properties = new Properties();
        properties.setProperty("client_name", "Test_1");

        HashMap<String, String> colNameToDataTypeMap = new HashMap<>();
        colNameToDataTypeMap.put("Min_Date", "Nullable(Date)");
        colNameToDataTypeMap.put("MinDateTime", "DateTime64(3, 'UTC')");
        colNameToDataTypeMap.put("MaxDateTime", "Nullable(DateTime64(3))");

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, database);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password, config);
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config, null, conn);
        PreparedStatementExecutor preparedStatementExecutor = new PreparedStatementExecutor(null,
                false, null, null, database, ZoneId.of("UTC"));

        ClickHouseDataType dt1 = preparedStatementExecutor.getClickHouseDataType("Min_Date", colNameToDataTypeMap);
        Assert.assertTrue(dt1 == ClickHouseDataType.Date);

        ClickHouseDataType dt2 = preparedStatementExecutor.getClickHouseDataType("MinDateTime", colNameToDataTypeMap);
        Assert.assertTrue(dt2 == ClickHouseDataType.DateTime64);

        ClickHouseDataType dt3 = preparedStatementExecutor.getClickHouseDataType("MaxDateTime", colNameToDataTypeMap);
        Assert.assertTrue(dt3 == ClickHouseDataType.DateTime64);
        System.out.println("");
    }
    @Test
    @Tag("IntegrationTest")
    public void testBatchArrays() {
        String hostName = "localhost";
        Integer port = clickHouseContainer.getFirstMappedPort();

        String database = "default";
        String userName = "default";
        String tableName = "employees";

        Properties properties = new Properties();
        properties.setProperty("client_name", "Test_1");
        properties.setProperty("session_id", "123333");

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, database);
        ClickHouseConnection conn2 = DbWriter.createConnection(jdbcUrl, "client_1", userName, "", config);
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, "", config,
                null, conn2);
        String url = dbWriter.getConnectionString(hostName, port, database);

        /**
         * CREATE TABLE products(
         *   `productCode` String,
         *   `productName` String,
         *   `productLine` String,
         *   `productScale` String,
         *   `productVendor` String,
         *   `productDescription` String,
         *   `quantityInStock` Int32,
         *   `buyPrice` Decimal(10,2),
         *   `MSRP` Decimal(10,2),
         *   `raw_data` String,
         *   `sign` Int8
         * )
         */
        String insertQueryTemplate = "insert into products(productCode, productName, productLine, productScale, productVendor, productDescription, buyPrice, MSRP, raw_data, sign) values(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);

            ClickHouseConnection conn = dataSource.getConnection(userName, "");

            PreparedStatement ps = conn.prepareStatement(insertQueryTemplate);

            int index = 1;
            ps.setString(index++, "123333");
            ps.setString(index++, "Great product");
            ps.setString(index++, "Grocery");
            ps.setString(index++, "11");
            ps.setString(index++, "WM");
            ps.setString(index++, "Product description");
            ps.setFloat(index++, 0.02f);
            ps.setFloat(index++, 11.30f);
            ps.setString(index++, "raw data");
            ps.setInt(index++, 1);

            ps.addBatch();
            ps.executeBatch();

        } catch(Exception e) {
            System.out.println("Error connecting" + e);
        }

    }

    @Test
    @Tag("IntegrationTest")
    public void testBatchInsert() {
        String hostName = "localhost";
        Integer port = 8123;

        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        Properties properties = new Properties();
        properties.setProperty("client_name", "Test_1");

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, database);
        ClickHouseConnection conn2 = DbWriter.createConnection(jdbcUrl, "client_1", userName, password, config);
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config,
                null, conn2);
        String url = dbWriter.getConnectionString(hostName, port, database);

        String insertQueryTemplate = "insert into employees values(?,?,?,?,?,?)";
        try {
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);
            ClickHouseConnection conn = dataSource.getConnection(userName, password);

            PreparedStatement ps = conn.prepareStatement(insertQueryTemplate);

            ps.setInt(1, 1);
            ps.setDate(2, new java.sql.Date(10000));
            ps.setString(3, "John1");
            ps.setString(4, "Doe1");
            ps.setString(5, "M");
            ps.setDate(6, new java.sql.Date(10000));
            ps.addBatch();

            ps.setInt(1, 2);
            ps.setDate(2, new java.sql.Date(10000));
            ps.setString(3, "John2");
            ps.setString(4, "Doe2");
            ps.setString(5, "M");
            ps.setDate(6, new java.sql.Date(10000));
            ps.addBatch();

            ps.setInt(1, 3);
            ps.setDate(2, new java.sql.Date(10000));
            ps.setInt(3, 22);
            ps.setString(4, "012-03-01 08:32:53,431 WARN [2-BAM::Default Agent::Agent:pool-8-thread-1] [JDBCExceptionReporter] SQL Error: 0, SQLState: 22001\n" +
                    "2012-03-01 08:32:53,431 ERROR [2-BAM::Default Agent::Agent:pool-8-thread-1] [JDBCExceptionReporter] Batch entry 0 insert into TEST_CASE (TEST_CLASS_ID, TEST_CASE_NAME, SUCCESSFUL_RUNS, FAILED_RUNS, AVG_DURATION, FIRST_BUILD_NUM, LAST_BUILD_NUM, TEST_CASE_ID) values ('11993104', A_lot_of_data_goes_here_as_TEST_CASE_NAME, '0', '0', '0', '-1', '-1', '11960535') was aborted.  Call getNextException to see the cause.\n" +
                    "2012-03-01 08:32:53,556 WARN [2-BAM::Default Agent::Agent:pool-8-thread-1] [JDBCExceptionReporter] SQL Error: 0, SQLState: 22001\n" +
                    "2012-03-01 08:32:53,556 ERROR [2-BAM::Default Agent::Agent:pool-8-thread-1] [JDBCExceptionReporter] ERROR: value too long for type character varying(4000)\n" +
                    "2012-03-01 08:32:53,556 ERROR [2-BAM::Default Agent::Agent:pool-8-thread-1] [SessionImpl] Could not synchronize database state with session\n" +
                    "2012-03-01 08:32:53,556 INFO [2-BAM::Default Agent::Agent:pool-8-thread-1] [DefaultErrorHandler] Recording an error: Could not save the build results. Data could be in an inconsistent state. : TWTHREE-MAIN-JOB1 : Hibernate operation: Could not execute JDBC batch update; SQL []; Batch entry 0 insert into TEST_CASE (TEST_CLASS_ID, TEST_CASE_NAME, SUCCESSFUL_RUNS, FAILED_RUNS, AVG_DURATION, FIRST_BUILD_NUM, LAST_BUILD_NUM, TEST_CASE_ID) values ('11993104', A_lot_of_data_goes_here_as_TEST_CASE_NAME, '0', '0', '0', '-1', '-1', '11960535') was aborted.  Call getNextException to see the cause.; nested exception is java.sql.BatchUpdateException: Batch entry 0 insert into TEST_CASE (TEST_CLASS_ID, TEST_CASE_NAME, SUCCESSFUL_RUNS, FAILED_RUNS, AVG_DURATION, FIRST_BUILD_NUM, LAST_BUILD_NUM, TEST_CASE_ID) values ('11993104', A_lot_of_data_goes_here_as_TEST_CASE_NAME, '0', '0', '0', '-1', '-1', '11960535') was aborted.  Call getNextException to see the cause.\n" +
                    "2012-03-01 08:32:53,666 FATAL [2-BAM::Default Agent::Agent:pool-8-thread-1] [PlanStatePersisterImpl] Could not save the build results BuildResults: TWTHREE-MAIN-JOB1-659. Data could be in an inconsistent state.\n" +
                    "org.springframework.dao.DataIntegrityViolationException: Hibernate operation: Could not execute JDBC batch update; SQL []; Batch entry 0 insert into TEST_CASE (TEST_CLASS_ID, TEST_CASE_NAME, SUCCESSFUL_RUNS, FAILED_RUNS, AVG_DURATION, FIRST_BUILD_NUM, LAST_BUILD_NUM, TEST_CASE_ID) values ('11993104', A_lot_of_data_goes_here_as_TEST_CASE_NAME, '0', '0', '0', '-1', '-1', '11960535') was aborted.  Call getNextException to see the cause.\n" +
                    "Caused by: java.sql.BatchUpdateException: Batch entry 0 insert into TEST_CASE (TEST_CLASS_ID, TEST_CASE_NAME, SUCCESSFUL_RUNS, FAILED_RUNS, AVG_DURATION, FIRST_BUILD_NUM, LAST_BUILD_NUM, TEST_CASE_ID) values ('11993104', A_lot_of_data_goes_here_as_TEST_CASE_NAME, '0', '0', '0', '-1', '-1', '11960535') was aborted.  Call getNextException to see the cause.\n" +
                    "\tat org.postgresql.jdbc2.AbstractJdbc2Statement$BatchResultHandler.handleError(AbstractJdbc2Statement.java:2569)\n" +
                    "\tat org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:1796)\n" +
                    "\tat org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:407)\n" +
                    "\tat org.postgresql.jdbc2.AbstractJdbc2Statement.executeBatch(AbstractJdbc2Statement.java:2708)\n" +
                    "\tat com.mchange.v2.c3p0.impl.NewProxyPreparedStatement.executeBatch(NewProxyPreparedStatement.java:1723)\n" +
                    "\tat net.sf.hibernate.impl.BatchingBatcher.doExecuteBatch(BatchingBatcher.java:54)\n" +
                    "\tat net.sf.hibernate.impl.BatcherImpl.executeBatch(BatcherImpl.java:128)\n" +
                    "\tat net.sf.hibernate.impl.BatcherImpl.prepareStatement(BatcherImpl.java:61)\n" +
                    "\tat net.sf.hibernate.impl.BatcherImpl.prepareStatement(BatcherImpl.java:58)\n" +
                    "\tat net.sf.hibernate.impl.BatcherImpl.prepareBatchStatement(BatcherImpl.java:111)\n" +
                    "\tat net.sf.hibernate.persister.EntityPersister.insert(EntityPersister.java:454)\n" +
                    "\tat net.sf.hibernate.persister.EntityPersister.insert(EntityPersister.java:436)\n" +
                    "\tat net.sf.hibernate.impl.ScheduledInsertion.execute(ScheduledInsertion.java:37)\n" +
                    "\tat net.sf.hibernate.impl.SessionImpl.execute(SessionImpl.java:2447)\n" +
                    "...");
            ps.setString(5, "M");
            ps.setDate(6, new java.sql.Date(10000));
            ps.addBatch();

            ps.setInt(1, 2);
            ps.setDate(2, new java.sql.Date(10000));
            ps.setString(3, "John4");
            ps.setString(4, "Doe4");
            ps.setString(5, "M");
            ps.setDate(6, new java.sql.Date(10000));
            ps.addBatch();

            int[] result = ps.executeBatch();
            for(int i = 0; i < result.length; i++) {
                System.out.println("Index:" + i + " Result:" + result[i]);
            }
        } catch (Exception e) {
            System.out.println("Exception" + e);
        }

    }
}
