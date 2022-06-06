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

    static Map<String, String> columnNameToDataTypesMap = new HashMap<>();

    static List<Field> fields = new ArrayList<>();

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
        boolean includeRawData = false;

        DBMetadata.TABLE_ENGINE engine = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        String insertQuery =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap, includeKafkaMetaData, includeRawData,
                null, null, null, null, engine);

        String expectedQuery = "insert into products(customerName,occupation,quantity,_topic) select customerName,occupation,quantity,_topic " +
                "from input('customerName String,occupation String,quantity UInt32,_topic String')";
        Assert.assertTrue(insertQuery.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithKafkaMetaDataDisabled() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = false;

        DBMetadata.TABLE_ENGINE engine = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        String insertQuery =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, null, null, null, null, engine);

        String expectedQuery = "insert into products(customerName,occupation,quantity) select customerName,occupation,quantity from input('customerName String,occupation " +
                "String,quantity UInt32')";
        Assert.assertTrue(insertQuery.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnNotProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;


        DBMetadata.TABLE_ENGINE engine = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        String insertQuery =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, null, null, null, null, engine);

        String expectedQuery = "insert into products(customerName,occupation,quantity) select customerName,occupation,quantity from input('customerName String,occupation " +
                "String,quantity UInt32')";
        Assert.assertTrue(insertQuery.equalsIgnoreCase(expectedQuery));
    }
    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;


        DBMetadata.TABLE_ENGINE engine = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        columnNameToDataTypesMap.put("raw_column", "String");
        String insertQuery =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, "raw_column", null, null,
                null,  engine);

        String expectedQuery = "insert into products(customerName,occupation,quantity,raw_column) select customerName,occupation,quantity,raw_column from input('customerName String,occupation String,quantity UInt32,raw_column String')";
        Assert.assertTrue(insertQuery.equalsIgnoreCase(expectedQuery));
    }
}
