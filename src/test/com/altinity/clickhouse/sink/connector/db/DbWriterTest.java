package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DbWriterTest {

    DbWriter writer;

    @Before
    public void init() {

        String hostName = "remoteClickHouse";
        Integer port = 8000;
        String database = "employees";
        String userName = "test";
        String password = "test";

        this.writer = new DbWriter(hostName, port, database, userName, password);

    }
    @Test
    public void testInsertQuery() {

        String query = writer.getInsertQuery("products", 4);

        Assert.assertEquals(query, "insert into products values(?,?,?,?)");

    }

    @Test
    public void testGetConnectionUrl() {

        String hostName = "remoteClickHouse";
        Integer port = 8123;
        String database = "employees";
        String connectionUrl = writer.getConnectionString(hostName, port, database);

        Assert.assertEquals(connectionUrl, "jdbc:clickhouse://remoteClickHouse:8123/employees");
    }

}
