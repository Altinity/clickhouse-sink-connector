package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
    public void testDestinationOnlyDataColumnIsOmittedFromInsert() {
        // ADD COLUMN scenario: destination has price_usd but the (pre-ALTER)
        // source event does not. The column must be OMITTED from the insert so
        // ClickHouse fills its DEFAULT -- binding NULL fails outright on
        // non-nullable columns and the whole batch then errors and replays
        // forever (the price_usd data-loss/stall incident).
        QueryFormatter qf = new QueryFormatter();

        Map<String, String> colMap = new HashMap<>();
        colMap.put("customerName", "String");
        colMap.put("price_usd", "Decimal(18, 6)");   // destination-only
        colMap.put("_version", "UInt64");            // connector-managed: kept

        MutablePair<String, Map<String, Integer>> response =
                qf.getInsertQueryUsingInputFunction("products", fields, colMap,
                        false, false, null, "employees");

        Assert.assertFalse("Destination-only data column must be omitted",
                response.left.contains("price_usd"));
        Assert.assertTrue("Connector-managed _version must be kept",
                response.left.contains("`_version`"));
        Assert.assertTrue("Source-present column must be kept",
                response.left.contains("`customerName`"));
        Assert.assertFalse(response.right.containsKey("price_usd"));
    }

    @Test
    public void testCustomManagedColumnsAreKept() {
        // Custom version/sign column names (e.g. ReplacingMergeTree(ver)) are
        // passed through the connectorManagedColumns parameter and must be
        // kept even though the source event never carries them.
        QueryFormatter qf = new QueryFormatter();

        Map<String, String> colMap = new HashMap<>();
        colMap.put("customerName", "String");
        colMap.put("ver", "UInt64");   // custom version column
        colMap.put("signv", "Int8");   // custom delete column

        MutablePair<String, Map<String, Integer>> response =
                qf.getInsertQueryUsingInputFunction("products", fields, colMap,
                        false, false, null, "employees",
                        java.util.List.of("ver", "signv"));

        Assert.assertTrue(response.left.contains("`ver`"));
        Assert.assertTrue(response.left.contains("`signv`"));
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
        String validToMax = "2100-01-01 00:00:00";
        String binlogRecordTimestamp = "2025-03-01 10:30:00";
        long version = 1234567890;
        ClickHouseConverter.CDC_OPERATION cdcOperation = ClickHouseConverter.CDC_OPERATION.UPDATE;
        MutablePair<String, Map<String, Integer>> result = qf.getInsertQueryForUpdate(
                tableName,
                employeeColumns,
                primaryKeyColumnName,
                primaryKeyValue,
                validToMax,
                binlogRecordTimestamp,
                version,
                cdcOperation,
                "UTC"
        );

        String query = result.left;
        Map<String, Integer> columnIndexMap = result.right;
        
        // Verify basic query structure
        String expectedPattern = "INSERT INTO `test_history.employees`";
        Assert.assertTrue("Query should start with INSERT INTO statement", 
                query.contains(expectedPattern));
        
        // Query should have TWO UNION ALL clauses (three SELECTs total)
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have two UNION ALL clauses for three SELECTs", 2, unionAllCount);
        
        Assert.assertTrue("Query should contain WHERE clause with primary key", 
                query.contains("WHERE `employeeNumber`=1001"));
        Assert.assertTrue("Query should contain valid_to condition with toDateTime", 
                query.contains("`_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC')"));
        Assert.assertTrue("Query should contain is_deleted condition", 
                query.contains("`is_deleted` = 0"));
        
        // First SELECT should CLOSE the record using binlog timestamp for _valid_to
        Assert.assertTrue("First SELECT should close record with binlog timestamp",
                query.contains("toDateTime('2025-03-01 10:30:00', 'UTC')"));
        
        // Verify that second SELECT uses placeholders for columns
        Assert.assertTrue("Second SELECT should contain ? as columnName for regular columns",
                query.contains("? as `employeeNumber`") || query.contains("? as `lastName`") || 
                query.contains("? as `firstName`") || query.contains("? as `email`"));
        
        // Second SELECT: after image columns should be in index map (third SELECT has no parameter binding)
        Assert.assertTrue("After image column should need parameter binding",
                columnIndexMap.containsKey("employeeNumber"));
        Assert.assertTrue("After image column should need parameter binding",
                columnIndexMap.containsKey("lastName"));
        Assert.assertTrue("After image temporal column should need parameter binding",
                columnIndexMap.containsKey(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN));
        Assert.assertTrue("After image temporal column should need parameter binding",
                columnIndexMap.containsKey(ClickHouseDbConstants.DELETED_TIME_COLUMN));

        // Print the generated query for debugging
        System.out.println("Generated Insert Query for Update:");
        System.out.println(query);
        System.out.println("Column Index Map: " + columnIndexMap);
    }
    
    @Test
    public void testGetInsertQueryForUpdateWithStringPrimaryKey() {
        QueryFormatter qf = new QueryFormatter();

        // Setup test data for offices table with temporal tracking fields and String primary key
        Map<String, String> officeColumns = new HashMap<>();
        officeColumns.put("officeCode", "String");
        officeColumns.put("city", "String");
        officeColumns.put("phone", "String");
        officeColumns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        officeColumns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        officeColumns.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        officeColumns.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        officeColumns.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");

        List<Field> officeFields = new ArrayList<>();
        officeFields.add(new Field("officeCode", 0, Schema.STRING_SCHEMA));
        officeFields.add(new Field("city", 1, Schema.STRING_SCHEMA));
        officeFields.add(new Field("phone", 2, Schema.STRING_SCHEMA));

        String tableName = "test_history.offices";
        String primaryKeyColumnName = "officeCode";
        Object primaryKeyValue = "NYC01";  // String primary key
        String validToMax = "2100-01-01 00:00:00";
        String binlogRecordTimestamp = "2025-03-01 10:30:00";
        long version = 1234567890;
        ClickHouseConverter.CDC_OPERATION cdcOperation = ClickHouseConverter.CDC_OPERATION.UPDATE;
        
        MutablePair<String, Map<String, Integer>> result = qf.getInsertQueryForUpdate(
                tableName,
                officeColumns,
                primaryKeyColumnName,
                primaryKeyValue,
                validToMax,
                binlogRecordTimestamp,
                version,
                cdcOperation,
                "UTC"
        );

        String query = result.left;
        Map<String, Integer> columnIndexMap = result.right;
        
        // Verify that the String primary key value is properly quoted
        Assert.assertTrue("Query should contain quoted string primary key value",
                query.contains("`officeCode`='NYC01'"));
        
        // Query should have TWO UNION ALL clauses (three SELECTs total)
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have two UNION ALL clauses for three SELECTs", 2, unionAllCount);
        
        // Verify basic query structure
        Assert.assertTrue("Query should contain valid_to condition with toDateTime", 
                query.contains("`_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC')"));
        
        // First SELECT should close the record using binlog timestamp
        Assert.assertTrue("First SELECT should close record with binlog timestamp",
                query.contains("toDateTime('2025-03-01 10:30:00', 'UTC')"));
        
        // Verify column index map has after image columns (third SELECT has no parameter binding)
        Assert.assertTrue("After image column should be in index map",
                columnIndexMap.containsKey("officeCode"));
        Assert.assertTrue("After image column should be in index map",
                columnIndexMap.containsKey("city"));
        
        // Print the generated query for debugging
        System.out.println("Generated Insert Query for Update (String PK):");
        System.out.println(query);
        System.out.println("Column Index Map: " + columnIndexMap);
    }

    @Test
    public void testGetInsertQueryForDelete() {
        QueryFormatter qf = new QueryFormatter();

        Map<String, String> employeeColumns = new HashMap<>();
        employeeColumns.put("employeeNumber", "Int32");
        employeeColumns.put("lastName", "String");
        employeeColumns.put("firstName", "String");
        employeeColumns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        employeeColumns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        employeeColumns.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        employeeColumns.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        employeeColumns.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");

        String tableName = "test_history.employees";
        String primaryKeyColumnName = "employeeNumber";
        Object primaryKeyValue = 1001;
        String validToMax = "2100-01-01 00:00:00";
        String binlogRecordTimestamp = "2025-03-01 10:30:00";
        long version = 1234567890;

        MutablePair<String, Map<String, Integer>> result = qf.getInsertQueryForDelete(
                tableName,
                employeeColumns,
                primaryKeyColumnName,
                primaryKeyValue,
                validToMax,
                binlogRecordTimestamp,
                version,
                "UTC"
        );

        String query = result.left;
        Map<String, Integer> columnIndexMap = result.right;

        Assert.assertTrue("Query should start with INSERT INTO statement",
                query.contains("INSERT INTO `test_history.employees`"));

        // Exactly one UNION ALL (2 SELECTs)
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have one UNION ALL for two SELECTs", 1, unionAllCount);

        Assert.assertTrue("Query should contain WHERE with primary key",
                query.contains("WHERE `employeeNumber`=1001"));
        Assert.assertTrue("Query should contain valid_to condition",
                query.contains("`_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC')"));
        Assert.assertTrue("Query should contain is_deleted condition",
                query.contains("`is_deleted` = 0"));

        // First SELECT: close row - unaliased 0 for is_deleted, _valid_to = binlog timestamp (expression only)
        Assert.assertTrue("First SELECT should have unaliased 0 for is_deleted",
                query.contains(", 0") || query.contains(",0"));
        Assert.assertTrue("First SELECT should set _valid_to to binlog timestamp (expression only, no AS)",
                query.contains("toDateTime('2025-03-01 10:30:00', 'UTC')"));

        // Second SELECT: delete marker - unaliased 1, 'D', _valid_from/_valid_to as expressions only
        Assert.assertTrue("Second SELECT should have unaliased 1 for is_deleted",
                query.contains(", 1") || query.contains(",1"));
        Assert.assertTrue("Second SELECT should have 'D' for _operation (no AS)",
                query.contains("'D'"));
        Assert.assertTrue("Second SELECT should set _valid_to to open_end (expression only)",
                query.contains("toDateTime('2100-01-01 00:00:00', 'UTC')"));

        Assert.assertTrue("Column index map should be empty (no parameter binding)",
                columnIndexMap.isEmpty());
    }

    @Test
    public void testGetInsertQueryForDeleteWithStringPrimaryKey() {
        QueryFormatter qf = new QueryFormatter();

        Map<String, String> officeColumns = new HashMap<>();
        officeColumns.put("officeCode", "String");
        officeColumns.put("city", "String");
        officeColumns.put("phone", "String");
        officeColumns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        officeColumns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        officeColumns.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        officeColumns.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        officeColumns.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");

        String tableName = "test_history.offices";
        String primaryKeyColumnName = "officeCode";
        Object primaryKeyValue = "NYC01";
        String validToMax = "2100-01-01 00:00:00";
        String binlogRecordTimestamp = "2025-03-01 10:30:00";
        long version = 1234567890;

        MutablePair<String, Map<String, Integer>> result = qf.getInsertQueryForDelete(
                tableName,
                officeColumns,
                primaryKeyColumnName,
                primaryKeyValue,
                validToMax,
                binlogRecordTimestamp,
                version,
                "UTC"
        );

        String query = result.left;
        Map<String, Integer> columnIndexMap = result.right;

        Assert.assertTrue("Query should contain quoted string primary key value",
                query.contains("`officeCode`='NYC01'"));
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have one UNION ALL for two SELECTs", 1, unionAllCount);
        Assert.assertTrue("Column index map should be empty",
                columnIndexMap.isEmpty());
    }

    @Test
    @DisplayName("extractDateTime64Precision handles Nullable(DateTime64(6)) wrapper")
    public void testExtractDateTime64PrecisionWithNullable() {
        // Test via reflection since extractDateTime64Precision is private
        QueryFormatter qf = new QueryFormatter();
        try {
            java.lang.reflect.Method method = QueryFormatter.class.getDeclaredMethod(
                "extractDateTime64Precision", String.class);
            method.setAccessible(true);

            // Plain DateTime64(3)
            String result1 = (String) method.invoke(qf, "DateTime64(3)");
            Assert.assertEquals("Should extract precision 3", "3", result1);

            // Nullable(DateTime64(6))
            String result2 = (String) method.invoke(qf, "Nullable(DateTime64(6))");
            Assert.assertEquals("Should extract precision 6 from Nullable wrapper", "6", result2);

            // Nullable(DateTime64(9, 'UTC'))
            String result3 = (String) method.invoke(qf, "Nullable(DateTime64(9, 'UTC'))");
            Assert.assertEquals("Should extract precision 9 from Nullable with timezone", "9", result3);

            // DateTime64 without precision
            String result4 = (String) method.invoke(qf, "DateTime64");
            Assert.assertEquals("Should default to 3 when no precision specified", "3", result4);

            // Nested Nullable edge case
            String result5 = (String) method.invoke(qf, "NULLABLE(DateTime64(4))");
            Assert.assertEquals("Should handle uppercase NULLABLE", "4", result5);

        } catch (Exception e) {
            Assert.fail("Reflection failed: " + e.getMessage());
        }
    }


    @Test
    @DisplayName("formatLiteralForSql uses toDateTime64 for DateTime64 types with correct precision")
    public void testFormatLiteralForSqlDateTime64VsDateTime() {
        QueryFormatter qf = new QueryFormatter();
        try {
            // Actual signature is formatLiteralForSql(Object value, String dataType).
            // The column name and timezone are not parameters of this helper — the
            // timezone is applied by the callers that build toDateTime(...) expressions
            // (see getInsertQueryForDelete / getInsertQueryForUpdate).
            java.lang.reflect.Method method = QueryFormatter.class.getDeclaredMethod(
                "formatLiteralForSql", Object.class, String.class);
            method.setAccessible(true);

            // DateTime should use toDateTime
            String dtResult = (String) method.invoke(qf, "2025-01-01 00:00:00", "DateTime");
            Assert.assertTrue("DateTime should use toDateTime function",
                dtResult.contains("toDateTime("));
            Assert.assertFalse("DateTime should NOT use toDateTime64",
                dtResult.contains("toDateTime64("));

            // DateTime64(3) should use toDateTime64 with precision 3
            String dt64Result = (String) method.invoke(qf, "2025-01-01 00:00:00.000", "DateTime64(3)");
            Assert.assertTrue("DateTime64 should use toDateTime64 function",
                dt64Result.contains("toDateTime64("));
            Assert.assertTrue("DateTime64(3) should include precision 3",
                dt64Result.contains(", 3)"));

            // DateTime64(6) should use toDateTime64 with precision 6
            String dt64_6Result = (String) method.invoke(qf, "2025-01-01 00:00:00.000000", "DateTime64(6)");
            Assert.assertTrue("DateTime64(6) should use toDateTime64 function",
                dt64_6Result.contains("toDateTime64("));
            Assert.assertTrue("DateTime64(6) should include precision 6",
                dt64_6Result.contains(", 6)"));

            // Nullable(DateTime64(3)) should also use toDateTime64, with the
            // Nullable wrapper stripped before precision extraction.
            String nullableDt64 = (String) method.invoke(qf, "2025-01-01 00:00:00.000",
                "Nullable(DateTime64(3))");
            Assert.assertTrue("Nullable(DateTime64) should use toDateTime64",
                nullableDt64.contains("toDateTime64("));
            Assert.assertTrue("Nullable(DateTime64(3)) should include precision 3",
                nullableDt64.contains(", 3)"));

        } catch (Exception e) {
            Assert.fail("Reflection failed: " + e.getMessage());
        }
    }

}
