package com.altinity.clickhouse.sink.connector;

import org.junit.Assert;
import org.junit.Test;

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
}

