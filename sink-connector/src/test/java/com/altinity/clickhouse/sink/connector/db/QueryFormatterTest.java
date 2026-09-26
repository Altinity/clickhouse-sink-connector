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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QueryFormatterTest {

    static Map<String, String> columnNameToDataTypesMap = new LinkedHashMap<>();

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

        String expectedQuery  = "INSERT INTO `products`(`customerName`,`occupation`,`quantity`,`_topic`) VALUES (?,?,?,?)";
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

        String expectedQuery = "INSERT INTO `products`(`customerName`,`occupation`,`quantity`) VALUES (?,?,?)";

        System.out.println("Kafka metadata disabled Processed Query:" + expectedQuery);
        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnNotProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;

        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnNameToDataTypesMap,
                includeKafkaMetaData, includeRawData, null, "customer");

        String expectedQuery = "INSERT INTO `products`(`customerName`,`occupation`,`quantity`) VALUES (?,?,?)";

        Assert.assertTrue(response.left.equalsIgnoreCase(expectedQuery));
    }
    @Test
    public void testGetInsertQueryUsingInputFunctionWithRawDataEnabledButRawColumnProvided() {
        QueryFormatter qf = new QueryFormatter();

        String tableName = "products";
        boolean includeKafkaMetaData = false;
        boolean includeRawData = true;

        Map<String, String> columnsWithRaw = new LinkedHashMap<>(columnNameToDataTypesMap);
        columnsWithRaw.put("raw_column", "String");
        MutablePair<String, Map<String, Integer>> response =  qf.getInsertQueryUsingInputFunction(tableName, fields, columnsWithRaw,
                includeKafkaMetaData, includeRawData, "raw_column", "customer2");

        String expectedQuery = "INSERT INTO `products`(`customerName`,`occupation`,`quantity`,`raw_column`) VALUES (?,?,?,?)";
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
        
        // A same-key UPDATE has ONE UNION ALL (close row + after row); the deleted
        // before copy is gone (Spec 12.03 section 3.2, Gap G-12.03-2)
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have one UNION ALL clause for two SELECTs", 1, unionAllCount);

        Assert.assertTrue("Query should contain WHERE clause with primary key",
                query.contains("WHERE `employeeNumber`=1001"));
        Assert.assertEquals("the before key is selected once: by the close row only", 1,
                occurrences(query, "WHERE `employeeNumber`=1001 AND `_valid_to`"));
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
        
        // A same-key UPDATE has ONE UNION ALL (close row + after row)
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have one UNION ALL clause for two SELECTs", 1, unionAllCount);
        Assert.assertEquals("the before key is selected once: by the close row only", 1,
                occurrences(query, "WHERE `officeCode`='NYC01' AND `_valid_to`"));

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

    /**
     * Spec 02.01 section 3.5 (a): the history UPDATE and DELETE queries take the
     * whole primary key (column -> value, in key order) and close the previous
     * row with a conjunction over every column, each value formatted for its type.
     */
    @Test
    public void updateAndDeleteQueriesUseEveryPrimaryKeyColumn() {
        QueryFormatter qf = new QueryFormatter();
        Map<String, String> columns = new HashMap<>();
        columns.put("tenant", "String");
        columns.put("item_id", "Int32");
        columns.put("qty", "Int32");
        columns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        columns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        columns.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        columns.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        columns.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");
        java.util.LinkedHashMap<String, Object> primaryKey = new java.util.LinkedHashMap<>();
        primaryKey.put("tenant", "acme");
        primaryKey.put("item_id", 99);

        String update = qf.getInsertQueryForUpdate("h.items", columns, primaryKey, "2100-01-01 00:00:00",
                "2025-03-01 10:30:00", 5L, ClickHouseConverter.CDC_OPERATION.UPDATE, "UTC", false).left;
        String delete = qf.getInsertQueryForDelete("h.items", columns, primaryKey, "2100-01-01 00:00:00",
                "2025-03-01 10:30:00", 5L, "UTC").left;

        String predicate = "WHERE `tenant`='acme' AND `item_id`=99 AND `_valid_to`";
        // Same-key UPDATE: only the close row reads the table (Spec 12.03 section 3.2);
        // DELETE: close row and marker both do (section 3.3).
        Assert.assertEquals(1, occurrences(update, predicate));
        Assert.assertEquals(2, occurrences(delete, predicate));
        Assert.assertEquals("the single-column form is the one-entry case of the same predicate",
                qf.getInsertQueryForDelete("h.items", columns, "item_id", 99, "2100-01-01 00:00:00",
                        "2025-03-01 10:30:00", 5L, "UTC").left,
                qf.getInsertQueryForDelete("h.items", columns, new java.util.LinkedHashMap<>(
                        java.util.Collections.singletonMap("item_id", (Object) 99)), "2100-01-01 00:00:00",
                        "2025-03-01 10:30:00", 5L, "UTC").left);
    }

    // ---- Spec 12.03 sections 3.2-3.5: the corrected SCD2 statement shapes ----

    private static final String SENTINEL = "2100-01-01 00:00:00";
    private static final String EVENT_TIME = "2025-03-01 10:30:00";
    private static final String BEFORE_KEY_OPEN_ROW =
            "WHERE `employeeNumber`=1001 AND `_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC') AND `is_deleted` = 0";

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /** An SCD2 table (Spec 12.02): data columns plus the connector's history and engine columns, in table order. */
    private static Map<String, String> historyColumns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("employeeNumber", "Int32");
        columns.put("lastName", "String");
        columns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        columns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        columns.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        columns.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        columns.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");
        return columns;
    }

    private static Map<String, Object> employeeKey(int employeeNumber) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("employeeNumber", employeeNumber);
        return key;
    }

    /**
     * Spec 12.03 section 3.2 / Gap G-12.03-2: a same-key UPDATE writes the close
     * row and the after row, nothing else. The former deleted copy of the before
     * image shared sorting key AND version with the close row, so FINAL could keep
     * either one -- the closed history version was visible only by luck.
     */
    @Test
    public void updateEmitsNoDeletedBeforeCopy() {
        String update = new QueryFormatter().getInsertQueryForUpdate("h.employees", historyColumns(),
                employeeKey(1001), SENTINEL, EVENT_TIME, 77L, ClickHouseConverter.CDC_OPERATION.UPDATE,
                "UTC", false).left;

        Assert.assertEquals("close row + after row: one UNION ALL: " + update, 1, occurrences(update, "UNION ALL"));
        Assert.assertFalse("no deleted copy of the before image: " + update, update.contains("1 as `is_deleted`"));
        Assert.assertEquals("only the close row reads the table: " + update, 1,
                occurrences(update, "FROM `h.employees` FINAL"));
        Assert.assertTrue("the close row ends at the event time",
                update.contains("toDateTime('2025-03-01 10:30:00', 'UTC')"));
    }

    /**
     * Gap G-12.03-3: when the primary key changes, the old key's open row must be
     * retired, or the table shows two current rows for one source row. The third
     * SELECT is a delete marker at the OLD key's open sorting key (before key,
     * sentinel) and exists ONLY for a key change.
     */
    @Test
    public void keyChangingUpdateEmitsDeleteMarkerAtOldKey() {
        QueryFormatter qf = new QueryFormatter();

        String moved = qf.getInsertQueryForUpdate("h.employees", historyColumns(), employeeKey(1001), SENTINEL,
                EVENT_TIME, 77L, ClickHouseConverter.CDC_OPERATION.UPDATE, "UTC", true).left;
        Assert.assertEquals("close row + after row + old-key marker: two UNION ALL: " + moved,
                2, occurrences(moved, "UNION ALL"));
        String marker = moved.substring(moved.lastIndexOf("UNION ALL"));
        Assert.assertTrue("the marker is deleted: " + marker, marker.contains("1 as `is_deleted`"));
        Assert.assertTrue("the marker records the UPDATE: " + marker, marker.contains("'U' as `_operation`"));
        Assert.assertTrue("the marker sits at the open sorting key: " + marker,
                marker.contains("toDateTime('2100-01-01 00:00:00', 'UTC') as `_valid_to`"));
        Assert.assertTrue("the marker starts where the closed version ends: " + marker,
                marker.contains("toDateTime('2025-03-01 10:30:00', 'UTC') as `_valid_from`"));
        Assert.assertTrue("the marker copies the open row at the BEFORE key: " + marker,
                marker.contains(BEFORE_KEY_OPEN_ROW));
        Assert.assertEquals("close row and marker both select the before key's open row: " + moved,
                2, occurrences(moved, BEFORE_KEY_OPEN_ROW));

        String sameKey = qf.getInsertQueryForUpdate("h.employees", historyColumns(), employeeKey(1001), SENTINEL,
                EVENT_TIME, 77L, ClickHouseConverter.CDC_OPERATION.UPDATE, "UTC", false).left;
        Assert.assertEquals("no marker for a same-key UPDATE: " + sameKey, 1, occurrences(sameKey, "UNION ALL"));
    }

    /**
     * Spec 12.03 section 3.5 / Gap G-12.03-4: every row one event emits carries
     * the event's ONE version. {@code version + 1} collided with the next event's
     * version on the lightweight sequence path; the rows never compete with each
     * other (different sorting keys) and beat the earlier event's open row (I2).
     */
    @Test
    public void allRowsOfOneEventShareOneVersion() {
        QueryFormatter qf = new QueryFormatter();
        long version = 4242L;

        String update = qf.getInsertQueryForUpdate("h.employees", historyColumns(), employeeKey(1001), SENTINEL,
                EVENT_TIME, version, ClickHouseConverter.CDC_OPERATION.UPDATE, "UTC", true).left;
        Assert.assertEquals("close, after and old-key marker rows all carry V: " + update,
                3, occurrences(update, "4242 as `_version`"));
        Assert.assertFalse("never V+1: " + update, update.contains("4243"));

        String delete = qf.getInsertQueryForDelete("h.employees", historyColumns(), employeeKey(1001), SENTINEL,
                EVENT_TIME, version, "UTC").left;
        Assert.assertEquals("close row and delete marker both carry V: " + delete, 2, occurrences(delete, "4242"));
        Assert.assertFalse("never V+1: " + delete, delete.contains("4243"));
    }

    /**
     * Spec 12.03 section 3.4 / Gap G-12.03-6: a replicated TRUNCATE closes EVERY
     * open row at the event time and marks each one deleted at its open sorting
     * key, in one column-list-agnostic statement, instead of erasing the history.
     */
    @Test
    public void truncateClosesEveryOpenRowAndMarksIt() {
        QueryFormatter qf = new QueryFormatter();
        String bulk = qf.getInsertQueryForBulkClose("h.employees", true, SENTINEL, EVENT_TIME, 4242L, "T", "UTC");

        Assert.assertTrue("positional INSERT of a SELECT * REPLACE: " + bulk,
                bulk.startsWith("INSERT INTO `h`.`employees` SELECT * REPLACE ("));
        Assert.assertEquals("close rows + markers: one UNION ALL: " + bulk, 1, occurrences(bulk, "UNION ALL"));
        Assert.assertEquals("both SELECTs are column-list agnostic: " + bulk, 2, occurrences(bulk, "SELECT * REPLACE"));
        Assert.assertEquals("both SELECTs read every open row under FINAL: " + bulk, 2, occurrences(bulk,
                "FROM `h`.`employees` FINAL WHERE `_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC') AND `is_deleted` = 0"));
        Assert.assertFalse("no primary-key predicate: every open row is closed: " + bulk,
                bulk.contains("`employeeNumber`"));
        Assert.assertTrue("close rows end at the event time: " + bulk,
                bulk.contains("toDateTime('2025-03-01 10:30:00', 'UTC') AS `_valid_to`"));
        Assert.assertTrue("markers start at the event time: " + bulk,
                bulk.contains("toDateTime('2025-03-01 10:30:00', 'UTC') AS `_valid_from`"));
        Assert.assertTrue("markers are deleted: " + bulk, bulk.contains("1 AS `is_deleted`"));
        Assert.assertTrue("markers record the TRUNCATE: " + bulk, bulk.contains("'T' AS `_operation`"));
        Assert.assertEquals("every row carries the event's version: " + bulk, 2, occurrences(bulk, "4242 AS `_version`"));
        Assert.assertFalse("nothing destructive: " + bulk, bulk.toUpperCase().contains("TRUNCATE"));

        String withoutIsDeleted = qf.getInsertQueryForBulkClose("h.employees", false, SENTINEL, EVENT_TIME, 4242L, "T", "UTC");
        Assert.assertFalse("a table without is_deleted gets neither the replacement nor the conjunct: " + withoutIsDeleted,
                withoutIsDeleted.contains("is_deleted"));
        Assert.assertEquals(1, occurrences(withoutIsDeleted, "UNION ALL"));
    }
}
