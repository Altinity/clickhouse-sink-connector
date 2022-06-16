package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.client.data.ClickHouseArrayValue;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DbWriterTest {

    DbWriter writer;

    @Before
    public void init() {

        String hostName = "remoteClickHouse";
        Integer port = 8000;
        String database = "employees";
        String userName = "test";
        String password = "test";
        String tableName = "employees";

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<String, String>());
        this.writer = new DbWriter(hostName, port, tableName, database, userName, password, config);

    }

    public Struct getKafkaStruct() {
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

        String dbHostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<String, String>()));
        writer.getColumnsDataTypesForTable("employees");

    }

    @Test
    @Tag("IntegrationTest")
    public void testGetEngineType() {
        String dbHostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<String, String>()));
        MutablePair<DBMetadata.TABLE_ENGINE, String> result = new DBMetadata().getTableEngineUsingShowTable(writer.conn, "employees");
        Assert.assertTrue(result.getLeft() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        Assert.assertTrue(result.getRight().equalsIgnoreCase("ver"));

        MutablePair<DBMetadata.TABLE_ENGINE, String> resultProducts = new DBMetadata().getTableEngineUsingShowTable(writer.conn, "products");
        Assert.assertTrue(resultProducts.getLeft() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE);
        Assert.assertTrue(resultProducts.getRight().equalsIgnoreCase("sign"));
    }

    @Test
    @Tag("IntegrationTest")
    public void testGetEngineTypeUsingSystemTables() {
        String dbHostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));
        MutablePair< DBMetadata.TABLE_ENGINE, String> result = new DBMetadata().getTableEngineUsingSystemTables(writer.conn,
                "test", "employees");
        Assert.assertTrue(result.getLeft() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);

        MutablePair<DBMetadata.TABLE_ENGINE, String> result_products = new DBMetadata().getTableEngineUsingSystemTables(writer.conn,
                "test", "products");
        Assert.assertTrue(result_products.getLeft() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE);

        // Table does not exist.
        MutablePair<DBMetadata.TABLE_ENGINE, String> result_registration = new DBMetadata().getTableEngineUsingSystemTables(writer.conn,
                "test", "registration");
        Assert.assertNull(result_registration.getLeft());

        MutablePair<DBMetadata.TABLE_ENGINE, String> result_t1 = new DBMetadata().getTableEngineUsingSystemTables(writer.conn,
                "test", "t1");
        Assert.assertTrue(result_t1.getLeft() == DBMetadata.TABLE_ENGINE.MERGE_TREE);

    }

    public ConcurrentLinkedQueue<ClickHouseStruct> getSampleRecords() {
        ConcurrentLinkedQueue<ClickHouseStruct> records = new ConcurrentLinkedQueue<ClickHouseStruct>();

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

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<String, String>());
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config);




        Map<String, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<String, List<ClickHouseStruct>>();

        Map<TopicPartition, Long> result = dbWriter.groupQueryWithRecords(getSampleRecords()
                , queryToRecordsMap);

        Assert.assertTrue(result.isEmpty() == false);

        long topic_1_2_offset = result.get(new TopicPartition("topic_1", 2));
        Assert.assertTrue(topic_1_2_offset == 1000);

        long topic_1_3_offset = result.get(new TopicPartition("topic_1", 3));
        Assert.assertTrue(topic_1_3_offset == 1020);

        long topic_2_2_offset = result.get(new TopicPartition("topic_2", 2));
        Assert.assertTrue(topic_2_2_offset == 1400);

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

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<String, String>());
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config);
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

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<String, String>());
        DbWriter dbWriter = new DbWriter(hostName, port, database, tableName, userName, password, config);
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
