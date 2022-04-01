package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.Test;

public class DbWriterTest {

    @Test
    public void testInsertQuery() {
        DbWriter writer = new DbWriter();
        String query = writer.getInsertQuery("products", 4);

        Assert.assertEquals(query, "insert into products values(?,?,?,?)");


    }

}
