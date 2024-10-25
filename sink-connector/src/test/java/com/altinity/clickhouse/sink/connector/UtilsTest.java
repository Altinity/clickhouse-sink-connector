package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.common.Utils;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class UtilsTest {

    @Test
    public void testParseTopicToTableMap() throws Exception {
        String topicsToTable = "cluster1.topic1:table1, cluster2.topic2:table2";

        Map<String, String> result = Utils.parseTopicToTableMap(topicsToTable);

        Map<String, String> expectedHashMap = new HashMap<String, String>();
        expectedHashMap.put("cluster1.topic1", "table1");
        expectedHashMap.put("cluster2.topic2", "table2");

        Assert.assertEquals(result, expectedHashMap);

    }

    @Test
    public void testGetTableNameFromTopic() {

        String topicName = "SERVER5432.test.employees";
        String tableName = Utils.getTableNameFromTopic(topicName);

        Assert.assertEquals(tableName, "employees");
    }

    @Test
    public void testIsValidDatabase() {
        String database =  "a".repeat(64);
        boolean result = Utils.isValidDatabaseName(database);
        Assert.assertFalse(result);

        String validDatabase = "test";
        boolean validResult = Utils.isValidDatabaseName(validDatabase);
        Assert.assertTrue(validResult);

        // Database name with numbers.
        String databaseWithNumbers = "test123";
        boolean resultWithNumbers = Utils.isValidDatabaseName(databaseWithNumbers);
        Assert.assertTrue(resultWithNumbers);

        // Database name with special characters.
        String databaseWithSpecialCharacters = "test_123";
        boolean resultWithSpecialCharacters = Utils.isValidDatabaseName(databaseWithSpecialCharacters);
        Assert.assertTrue(resultWithSpecialCharacters);

    }

    @Test
    public void testParseSourceToDestinationDatabaseMap() throws Exception {
        String sourceToDestination = "src_db1:dst_db1, src_db2:dst_db2,src-db2:src_db2";
        Map<String, String> result = Utils.parseSourceToDestinationDatabaseMap(sourceToDestination);

        Map<String, String> expectedHashMap = new HashMap<String, String>();
        expectedHashMap.put("src_db1", "dst_db1");
        expectedHashMap.put("src_db2", "dst_db2");
        expectedHashMap.put("src-db2", "src_db2");

        Assert.assertEquals(result, expectedHashMap);
    }
}

