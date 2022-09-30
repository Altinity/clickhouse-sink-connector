package com.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryFormatterTest {

    static Map<String, String> columnNameToDataTypesMap = new HashMap<>();

    static List<Field> fields = new ArrayList<>();

    @BeforeAll
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

        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap, includeKafkaMetaData, includeRawData,
                null, null, null, null, engine);

//        String expectedQuery = "insert into products(customerName,occupation,quantity,_topic) select customerName,occupation,quantity,_topic " +
//                "from input('customerName String,occupation String,quantity UInt32,_topic String')";

        String expectedQuery  = "insert into products(occupation,quantity,_topic,customerName,_topic) select occupation,quantity,_topic,customerName,_topic from input('occupation String,quantity UInt32,_topic String,customerName String,_topic String')";
        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));

    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithKafkaMetaDataDisabled() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = false;

        DBMetadata.TABLE_ENGINE engine = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, null, null, null, null, engine);

        String expectedQuery = "insert into products(occupation,quantity,_topic,customerName) select occupation,quantity,_topic,customerName from input('occupation String,quantity UInt32,_topic String,customerName String')";
//        String expectedQuery = "insert into products(customerName,occupation,quantity) select customerName,occupation,quantity from input('customerName String,occupation " +
//                "String,quantity UInt32')";
//
        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnNotProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;


        DBMetadata.TABLE_ENGINE engine = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, null, null, null, null, engine);

        String expectedQuery = "insert into products(occupation,quantity,_topic,customerName) select occupation,quantity,_topic,customerName from input('occupation String,quantity UInt32,_topic String,customerName String')";

//        String expectedQuery = "insert into products(customerName,occupation,quantity) select customerName,occupation,quantity from input('customerName String,occupation " +
//                "String,quantity UInt32')";
        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }
    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;


        DBMetadata.TABLE_ENGINE engine = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        columnNameToDataTypesMap.put("raw_column", "String");
        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, "raw_column", null, null,
                null,  engine);

        String expectedQuery = "insert into products(raw_column,occupation,quantity,_topic,customerName,raw_column) select raw_column,occupation,quantity,_topic,customerName,raw_column from input('raw_column String,occupation String,quantity UInt32,_topic String,customerName String,raw_column String')";
        //String expectedQuery = "insert into products(customerName,occupation,quantity,raw_column) select customerName,occupation,quantity,raw_column from input('customerName String,occupation String,quantity UInt32,raw_column String')";
        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }
}
