package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Properties;

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
//    @Test
//    public void testInsertQuery() {
//
//        String query = writer.getInsertQuery("products", 4);
//
//        Assert.assertEquals(query, "insert into products values(?,?,?,?)");
//
//    }

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
        DBMetadata.TABLE_ENGINE result = new DBMetadata().getTableEngine(writer.conn, "employees");
        Assert.assertTrue(result == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE);
    }
    @Test
    public void testInsertPreparedStatement() {
        String hostName = "remoteClickHouse";
        Integer port = 8123;
        String database = "employees";
        String connectionUrl = writer.getConnectionString(hostName, port, database);

    }

    //@Test
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
