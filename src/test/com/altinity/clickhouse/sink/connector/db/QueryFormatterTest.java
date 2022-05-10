package com.altinity.clickhouse.sink.connector.db;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryFormatterTest {

    static Map<String, String> columnNameToDataTypesMap = new HashMap<String, String>();

    static List<Field> fields = new ArrayList<Field>();

    @BeforeClass
    public static void initialize() {
        columnNameToDataTypesMap.put("customerName", "String");
        columnNameToDataTypesMap.put("occupation", "String");
        columnNameToDataTypesMap.put("quantity", "UInt32");
        columnNameToDataTypesMap.put("_topic", "String");

        fields.add(new Field("customerName", 0, Schema.STRING_SCHEMA));
        fields.add(new Field("occupation", 1, Schema.STRING_SCHEMA));
        fields.add(new Field("quantity", 2, Schema.INT32_SCHEMA));
        fields.add(new Field("amount", 3, Schema.FLOAT64_SCHEMA));
        fields.add(new Field("employed", 4, Schema.BOOLEAN_SCHEMA));
    }
    @Test
    public void testGetInsertQueryUsingInputFunctionWithKafkaMetaDataEnabled() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = true;

        String insertQuery =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap, includeKafkaMetaData);

        String expectedQuery = "insert into products(customerName,occupation,quantity,_topic) select customerName,occupation,quantity,_topic from input('customerName String,occupation String,quantity UInt32,_topic String')";
        Assert.assertTrue(insertQuery.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithKafkaMetaDataDisabled() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;

        String insertQuery =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap, includeKafkaMetaData);

        String expectedQuery = "insert into products(customerName,occupation,quantity) select customerName,occupation,quantity from input('customerName String,occupation String,quantity UInt32')";
        Assert.assertTrue(insertQuery.equalsIgnoreCase(expectedQuery));
    }
}
