package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
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
        fields.add(new Field("transaction", 5, Schema.INT32_SCHEMA));
        fields.add(new Field("Min Value", 6, Schema.INT32_SCHEMA));
        fields.add(new Field("Null Value", 7, Schema.INT32_SCHEMA));
    }
    @Test
    public void testGetInsertQueryUsingInputFunctionWithKafkaMetaDataEnabled() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = true;
        boolean includeRawData = false;

        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap, includeKafkaMetaData, includeRawData,
                null, "employees");

        String expectedQuery  = "insert into `products`(`occupation`,`quantity`,`_topic`,`customerName`) select `occupation`,`quantity`,`_topic`,`customerName` from input('`occupation` String,`quantity` UInt32,`_topic` String,`customerName` String')";
        //System.out.println("Kafka metadata enabled Processed Query:" + expectedQuery);

        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));

    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithKafkaMetaDataDisabled() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = false;

        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, null, "employees");

        String expectedQuery = "insert into `products`(`occupation`,`quantity`,`customerName`) select `occupation`,`quantity`,`customerName` from input('`occupation` String,`quantity` UInt32,`customerName` String')";

        System.out.println("Kafka metadata disabled Processed Query:" + expectedQuery);
        //Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnNotProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;

        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, null, "customer");

        String expectedQuery = "insert into `products`(`occupation`,`quantity`,`customerName`) select `occupation`,`quantity`,`customerName` from input('`occupation` String,`quantity` UInt32,`customerName` String')";

        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }
    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;

        columnNameToDataTypesMap.put("raw_column", "String");
        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, "raw_column", "customer2");

        String expectedQuery = "insert into `products`(`raw_column`,`occupation`,`quantity`,`customerName`) select `raw_column`,`occupation`,`quantity`,`customerName` from input('`raw_column` String,`occupation` String,`quantity` UInt32,`customerName` String')";
        //String expectedQuery = "insert into products(customerName,occupation,quantity,raw_column) select customerName,occupation,quantity,raw_column from input('customerName String,occupation String,quantity UInt32,raw_column String')";
        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testGetInsertQueryForUpdate() {
        QueryFormatter qf = new QueryFormatter();

        // Setup test data for employees table with temporal tracking fields
        Map<String, String> employeeColumns = new HashMap<>();
        employeeColumns.put("employeeNumber", "Int32");
        employeeColumns.put("lastName", "String");
        employeeColumns.put("firstName", "String");
        employeeColumns.put("extension", "String");
        employeeColumns.put("email", "String");
        employeeColumns.put("officeCode", "String");
        employeeColumns.put("reportsTo", "Int32");
        employeeColumns.put("jobTitle", "String");
        employeeColumns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        employeeColumns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        employeeColumns.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        employeeColumns.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        employeeColumns.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");

        List<Field> employeeFields = new ArrayList<>();
        employeeFields.add(new Field("employeeNumber", 0, Schema.INT32_SCHEMA));
        employeeFields.add(new Field("lastName", 1, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("firstName", 2, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("extension", 3, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("email", 4, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("officeCode", 5, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("reportsTo", 6, Schema.INT32_SCHEMA));
        employeeFields.add(new Field("jobTitle", 7, Schema.STRING_SCHEMA));
        employeeFields.add(new Field(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, 8, Schema.INT64_SCHEMA));
        employeeFields.add(new Field(ClickHouseDbConstants.DELETED_TIME_COLUMN, 9, Schema.INT64_SCHEMA));
        employeeFields.add(new Field(ClickHouseDbConstants.OPERATION_COLUMN, 10, Schema.STRING_SCHEMA));
        employeeFields.add(new Field(ClickHouseDbConstants.VERSION_COLUMN, 11, Schema.INT64_SCHEMA));
        employeeFields.add(new Field(ClickHouseDbConstants.IS_DELETED_COLUMN, 12, Schema.INT8_SCHEMA));

        String tableName = "test_history.employees";
        String primaryKeyColumnName = "employeeNumber";
        Object primaryKeyValue = 1001;
        String validToMax = "'2100-01-01 00:00:00'";
        String binlogRecordTimestamp = "'2025-03-01 10:30:00'";
        long version = 1234567890;
        ClickHouseConverter.CDC_OPERATION cdcOperation = ClickHouseConverter.CDC_OPERATION.UPDATE;
        String result = qf.getInsertQueryForUpdate(
                tableName,
                employeeFields,
                employeeColumns,
                primaryKeyColumnName,
                primaryKeyValue,
                validToMax,
                binlogRecordTimestamp,
                version,
                cdcOperation
        );

        // Expected query format based on the provided example
        // Note: The actual order of columns may vary based on the HashMap iteration
        String expectedPattern = "INSERT INTO `test_history.employees`";
        Assert.assertTrue("Query should start with INSERT INTO statement", 
                result.contains(expectedPattern));
        Assert.assertTrue("Query should contain UNION ALL", 
                result.contains("UNION ALL"));
        Assert.assertTrue("Query should contain WHERE clause with primary key", 
                result.contains("WHERE employeeNumber=1001"));
        Assert.assertTrue("Query should contain valid_to condition", 
                result.contains("valid_to = '2100-01-01 00:00:00'"));
        Assert.assertTrue("Query should contain is_deleted condition", 
                result.contains("is_deleted = 0"));
        
        // Verify that the second SELECT uses placeholders for regular columns
        Assert.assertTrue("Second SELECT should contain ? as columnName for regular columns",
                result.contains("? as employeeNumber") || result.contains("? as lastName") || 
                result.contains("? as firstName") || result.contains("? as email"));

        // Print the generated query for debugging
        System.out.println("Generated Insert Query for Update:");
        System.out.println(result);
    }
}
