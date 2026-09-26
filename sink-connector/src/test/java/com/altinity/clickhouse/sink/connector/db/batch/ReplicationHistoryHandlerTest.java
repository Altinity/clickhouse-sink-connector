package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for ReplicationHistoryHandler.
 * Tests the SCD Type 2 update query generation and parameter handling.
 */
public class ReplicationHistoryHandlerTest {

    private QueryFormatter queryFormatter;
    private Map<String, String> columnToDataTypeMap;
    private List<Field> employeeFields;
    private Schema employeeSchema;

    @BeforeEach
    public void setUp() {
        queryFormatter = new QueryFormatter();

        // Setup column data types
        columnToDataTypeMap = new HashMap<>();
        columnToDataTypeMap.put("employeeNumber", "Int32");
        columnToDataTypeMap.put("lastName", "String");
        columnToDataTypeMap.put("firstName", "String");
        columnToDataTypeMap.put("email", "String");
        columnToDataTypeMap.put("officeCode", "String");
        columnToDataTypeMap.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        columnToDataTypeMap.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        columnToDataTypeMap.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        columnToDataTypeMap.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        columnToDataTypeMap.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");

        // Setup fields
        employeeFields = new ArrayList<>();
        employeeFields.add(new Field("employeeNumber", 0, Schema.INT32_SCHEMA));
        employeeFields.add(new Field("lastName", 1, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("firstName", 2, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("email", 3, Schema.STRING_SCHEMA));
        employeeFields.add(new Field("officeCode", 4, Schema.STRING_SCHEMA));

        // Setup schema
        employeeSchema = SchemaBuilder.struct()
                .field("employeeNumber", Schema.INT32_SCHEMA)
                .field("lastName", Schema.STRING_SCHEMA)
                .field("firstName", Schema.STRING_SCHEMA)
                .field("email", Schema.STRING_SCHEMA)
                .field("officeCode", Schema.STRING_SCHEMA)
                .build();
    }

    /**
     * Creates a test ClickHouseStruct with the given values.
     */
    private ClickHouseStruct createTestRecord(int employeeNumber, String lastName, 
            String firstName, String email, String officeCode) {
        
        Struct afterStruct = new Struct(employeeSchema)
                .put("employeeNumber", employeeNumber)
                .put("lastName", lastName)
                .put("firstName", firstName)
                .put("email", email)
                .put("officeCode", officeCode);

        // Create primary key schema and struct
        Schema pkSchema = SchemaBuilder.struct()
                .field("employeeNumber", Schema.INT32_SCHEMA)
                .build();
        Struct pkStruct = new Struct(pkSchema)
                .put("employeeNumber", employeeNumber);

        ClickHouseStruct record = new ClickHouseStruct(
                0L,                          // kafkaOffset
                "test-topic",                // topic
                pkStruct,                    // key (Struct for primary key)
                0,                           // kafkaPartition
                System.currentTimeMillis(),  // timestamp
                null,                        // beforeStruct
                afterStruct,                 // afterStruct
                null,                        // metadata
                ClickHouseConverter.CDC_OPERATION.UPDATE  // operation
        );

        // Set additional fields needed for the test
        record.setTs_ms(1709290200000L);  // 2024-03-01 10:50:00 UTC in ms
        record.setTsSec(1709290200L);     // 2024-03-01 10:50:00 UTC in seconds
        record.setGtid(12345L);
        // The standard version the INSERT path would bind (Spec 12.03 section 3.5):
        // the history rows must carry THIS, not a SnowFlakeId of (ts_ms, gtid).
        record.setVersion(STANDARD_VERSION);

        return record;
    }

    /** A lightweight sequence-path version ({@code effectiveTs * 10^6 + seq}), unlike any SnowFlakeId. */
    private static final long STANDARD_VERSION = 1709290200000001L;

    @Test
    public void testBuildUpdateQueryParams() {
        // Create a real ClickHouseStruct
        ClickHouseStruct record = createTestRecord(1001, "Doe", "John", 
                "john.doe@example.com", "NYC01");

        // Test buildUpdateQueryParams
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);
        ReplicationHistoryHandler.UpdateQueryParams params = handler.buildUpdateQueryParams(record);

        // Verify the params
        Assert.assertNotNull("Params should not be null", params);
        Assert.assertEquals("Primary key column should be employeeNumber", 
                "employeeNumber", params.getPrimaryKeyColumnName());
        Assert.assertEquals("Primary key value should be 1001", 
                1001, params.getPrimaryKeyValue());
        Assert.assertNotNull("ValidToMax should not be null", params.getValidToMax());
        Assert.assertNotNull("BinlogRecordTimestamp should not be null", 
                params.getBinlogRecordTimestamp());
        Assert.assertTrue("Version should be positive", params.getVersion() > 0);
        Assert.assertEquals("CDC operation should be UPDATE", 
                ClickHouseConverter.CDC_OPERATION.UPDATE, params.getCdcOperation());

        System.out.println("UpdateQueryParams: " + params);
    }

    @Test
    public void testGenerateUpdateQuery() {
        // Create params manually for testing
        String validToMax = "2100-01-01 00:00:00";
        String binlogRecordTimestamp = "2025-03-01 10:30:00";
        long version = 1234567890L;

        ReplicationHistoryHandler.UpdateQueryParams params = new ReplicationHistoryHandler.UpdateQueryParams(
                validToMax,
                binlogRecordTimestamp,
                version,
                "employeeNumber",
                1001,
                ClickHouseConverter.CDC_OPERATION.UPDATE
        );

        // Test generateUpdateQuery
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);
        MutablePair<String, Map<String, Integer>> result = handler.generateUpdateQuery(
                "test_history.employees",
                employeeFields,
                columnToDataTypeMap,
                params
        );

        String query = result.left;
        Map<String, Integer> columnIndexMap = result.right;

        // Verify the query structure
        Assert.assertNotNull("Query should not be null", query);
        Assert.assertTrue("Query should contain INSERT INTO", query.contains("INSERT INTO"));
        Assert.assertTrue("Query should contain UNION ALL", query.contains("UNION ALL"));
        Assert.assertTrue("Query should contain WHERE clause with primary key", 
                query.contains("`employeeNumber`=1001"));

        // A same-key UPDATE has one UNION ALL: close row + after row (Spec 12.03 section 3.2)
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have one UNION ALL clause", 1, unionAllCount);

        // Verify column index map has after image columns (the close row has no parameter binding)
        Assert.assertTrue("After image column should be in index map",
                columnIndexMap.containsKey("employeeNumber"));

        System.out.println("Generated Query: " + query);
        System.out.println("Column Index Map: " + columnIndexMap);
    }

    @Test
    public void testGenerateUpdateQueryWithStringPrimaryKey() {
        // Create params with String primary key
        String validToMax = "2100-01-01 00:00:00";
        String binlogRecordTimestamp = "2025-03-01 10:30:00";
        long version = 1234567890L;

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

        ReplicationHistoryHandler.UpdateQueryParams params = new ReplicationHistoryHandler.UpdateQueryParams(
                validToMax,
                binlogRecordTimestamp,
                version,
                "officeCode",
                "NYC01",  // String primary key
                ClickHouseConverter.CDC_OPERATION.UPDATE
        );

        // Test generateUpdateQuery
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);
        MutablePair<String, Map<String, Integer>> result = handler.generateUpdateQuery(
                "test_history.offices",
                officeFields,
                officeColumns,
                params
        );

        String query = result.left;

        // Verify string primary key is properly quoted
        Assert.assertTrue("Query should contain quoted string primary key value", 
                query.contains("`officeCode`='NYC01'"));

        System.out.println("Generated Query with String PK: " + query);
    }

    @Test
    public void testUpdateQueryParamsToString() {
        ReplicationHistoryHandler.UpdateQueryParams params = new ReplicationHistoryHandler.UpdateQueryParams(
                "2100-01-01 00:00:00",
                "2025-03-01 10:30:00",
                1234567890L,
                "employeeNumber",
                1001,
                ClickHouseConverter.CDC_OPERATION.UPDATE
        );

        String toString = params.toString();
        Assert.assertNotNull("toString should not be null", toString);
        Assert.assertTrue("toString should contain validToMax", toString.contains("validToMax"));
        Assert.assertTrue("toString should contain binlogRecordTimestamp", 
                toString.contains("binlogRecordTimestamp"));
        Assert.assertTrue("toString should contain version", toString.contains("version"));
        Assert.assertTrue("toString should contain primaryKeyColumnName", 
                toString.contains("primaryKeyColumnName"));
        Assert.assertTrue("toString should contain primaryKeyValue", 
                toString.contains("primaryKeyValue"));
        Assert.assertTrue("toString should contain cdcOperation", toString.contains("cdcOperation"));

        System.out.println("UpdateQueryParams.toString(): " + toString);
    }

    @Test
    public void testGenerateDeleteQuery() {
        // Test DELETE uses generateDeleteQuery (2-SELECT pattern, no delete marker from update query)
        String validToMax = "2100-01-01 00:00:00";
        String binlogRecordTimestamp = "2025-03-01 10:30:00";
        long version = 1234567890L;

        ReplicationHistoryHandler.UpdateQueryParams params = new ReplicationHistoryHandler.UpdateQueryParams(
                validToMax,
                binlogRecordTimestamp,
                version,
                "employeeNumber",
                1001,
                ClickHouseConverter.CDC_OPERATION.DELETE
        );

        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);
        MutablePair<String, Map<String, Integer>> result = handler.generateDeleteQuery(
                "test_history.employees",
                columnToDataTypeMap,
                params
        );

        String query = result.left;
        Map<String, Integer> columnIndexMap = result.right;

        Assert.assertNotNull("Query should not be null", query);
        Assert.assertTrue("Query should contain INSERT INTO", query.contains("INSERT INTO"));
        Assert.assertTrue("Query should contain 'D' for _operation", query.contains("'D'"));
        Assert.assertEquals("Delete query should have one UNION ALL (2 SELECTs)", 1, query.split("UNION ALL").length - 1);
        Assert.assertTrue("Column index map should be empty (no parameter binding)", columnIndexMap.isEmpty());

        System.out.println("Generated Query for DELETE: " + query);
    }

    @Test
    public void testBuildUpdateQueryParamsWithRealRecord() {
        // Test with a real ClickHouseStruct with before and after structs
        Struct beforeStruct = new Struct(employeeSchema)
                .put("employeeNumber", 1001)
                .put("lastName", "Smith")  // Old value
                .put("firstName", "John")
                .put("email", "john.smith@example.com")
                .put("officeCode", "NYC01");

        Struct afterStruct = new Struct(employeeSchema)
                .put("employeeNumber", 1001)
                .put("lastName", "Doe")    // New value
                .put("firstName", "John")
                .put("email", "john.doe@example.com")
                .put("officeCode", "NYC01");

        // Create primary key schema and struct
        Schema pkSchema = SchemaBuilder.struct()
                .field("employeeNumber", Schema.INT32_SCHEMA)
                .build();
        Struct pkStruct = new Struct(pkSchema)
                .put("employeeNumber", 1001);

        ClickHouseStruct record = new ClickHouseStruct(
                100L,                        // kafkaOffset
                "employees-topic",           // topic
                pkStruct,                    // key
                1,                           // kafkaPartition
                System.currentTimeMillis(),  // timestamp
                beforeStruct,                // beforeStruct
                afterStruct,                 // afterStruct
                null,                        // metadata
                ClickHouseConverter.CDC_OPERATION.UPDATE
        );

        record.setTs_ms(1709290200000L);
        record.setTsSec(1709290200L);
        record.setGtid(67890L);

        // Test buildUpdateQueryParams
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);
        ReplicationHistoryHandler.UpdateQueryParams params = handler.buildUpdateQueryParams(record);

        // Verify params
        Assert.assertEquals("employeeNumber", params.getPrimaryKeyColumnName());
        Assert.assertEquals(1001, params.getPrimaryKeyValue());
        Assert.assertEquals(ClickHouseConverter.CDC_OPERATION.UPDATE, params.getCdcOperation());

        // Verify the record has both before and after structs
        Assert.assertNotNull("Record should have beforeStruct", record.getBeforeStruct());
        Assert.assertNotNull("Record should have afterStruct", record.getAfterStruct());
        Assert.assertEquals("Before lastName should be Smith", 
                "Smith", record.getBeforeStruct().get("lastName"));
        Assert.assertEquals("After lastName should be Doe", 
                "Doe", record.getAfterStruct().get("lastName"));

        System.out.println("UpdateQueryParams from real record: " + params);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * Spec 02.01 section 3.5 (a): a table whose primary key has several columns
     * must close exactly the history row of that composite key. Closing on the
     * first column alone ({@code getPrimaryKey().get(0)}) closed every row that
     * shared it -- every line of an order when one line changed.
     */
    @Test
    public void compositePrimaryKeyClosesOnlyTheMatchingRow() {
        Schema lineSchema = SchemaBuilder.struct()
                .field("order_id", Schema.INT32_SCHEMA)
                .field("line_no", Schema.INT32_SCHEMA)
                .field("qty", Schema.INT32_SCHEMA)
                .build();
        Struct after = new Struct(lineSchema).put("order_id", 42).put("line_no", 7).put("qty", 3);
        Schema pkSchema = SchemaBuilder.struct()
                .field("order_id", Schema.INT32_SCHEMA)
                .field("line_no", Schema.INT32_SCHEMA)
                .build();
        Struct pk = new Struct(pkSchema).put("order_id", 42).put("line_no", 7);
        ClickHouseStruct record = new ClickHouseStruct(0L, "orders-topic", pk, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setTs_ms(1709290200000L);
        record.setTsSec(1709290200L);
        record.setGtid(12345L);

        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("order_id", "Int32");
        columns.put("line_no", "Int32");
        columns.put("qty", "Int32");
        columns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        columns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        columns.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        columns.put(ClickHouseDbConstants.VERSION_COLUMN, "Int64");
        columns.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "Int8");
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("order_id", 0, Schema.INT32_SCHEMA));
        fields.add(new Field("line_no", 1, Schema.INT32_SCHEMA));
        fields.add(new Field("qty", 2, Schema.INT32_SCHEMA));

        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);
        ReplicationHistoryHandler.UpdateQueryParams params = handler.buildUpdateQueryParams(record);
        Assert.assertTrue("the params carry every primary-key column: " + params,
                params.toString().contains("order_id") && params.toString().contains("line_no"));

        String update = handler.generateUpdateQuery("order_lines_history", fields, columns, params).left;
        String fullPredicate = "WHERE `order_id`=42 AND `line_no`=7 AND `_valid_to`";
        Assert.assertEquals("the close row of a same-key UPDATE selects exactly the composite key: "
                + update, 1, occurrences(update, fullPredicate));
        Assert.assertFalse("the first-column-only predicate would close every line of order 42: " + update,
                update.contains("WHERE `order_id`=42 AND `_valid_to`"));

        String delete = handler.generateDeleteQuery("order_lines_history", columns, params).left;
        Assert.assertEquals("both SELECTs of the DELETE query close exactly the composite key: " + delete,
                2, occurrences(delete, fullPredicate));
    }

    // ---- Spec 12.03 sections 3.2-3.5: before-image key, one version, bulk close ----

    private static final String OPEN_ROW_AT_1001 =
            "WHERE `employeeNumber`=1001 AND `_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC') AND `is_deleted` = 0";

    /** An UPDATE record whose before image has key {@code beforeKey} and whose after image has key {@code afterKey}. */
    private ClickHouseStruct updateRecord(int beforeKey, int afterKey) {
        Struct before = new Struct(employeeSchema).put("employeeNumber", beforeKey).put("lastName", "Smith")
                .put("firstName", "John").put("email", "john.smith@example.com").put("officeCode", "NYC01");
        Struct after = new Struct(employeeSchema).put("employeeNumber", afterKey).put("lastName", "Doe")
                .put("firstName", "John").put("email", "john.doe@example.com").put("officeCode", "NYC01");
        Schema pkSchema = SchemaBuilder.struct().field("employeeNumber", Schema.INT32_SCHEMA).build();
        // Debezium keys an UPDATE by the after image; the before image is where the old key lives.
        Struct pk = new Struct(pkSchema).put("employeeNumber", afterKey);
        ClickHouseStruct record = new ClickHouseStruct(7L, "employees-topic", pk, 0, System.currentTimeMillis(),
                before, after, null, ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setTs_ms(1709290200000L);
        record.setTsSec(1709290200L);
        record.setGtid(12345L);
        record.setVersion(STANDARD_VERSION);
        return record;
    }

    /**
     * Gap G-12.03-3: the row an UPDATE or DELETE closes is the one the BEFORE image
     * describes. Keying the close predicate by the after image closed nothing when
     * a primary-key column changed.
     */
    @Test
    public void closePredicateUsesBeforeImageKey() {
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);

        ReplicationHistoryHandler.UpdateQueryParams moved = handler.buildUpdateQueryParams(updateRecord(1001, 2002));
        Assert.assertEquals("the close key is the before-image key", 1001, moved.getPrimaryKeyValue());
        Assert.assertEquals(Collections.singletonMap("employeeNumber", (Object) 1001), moved.getPrimaryKey());
        Assert.assertTrue("a different after key is a key change", moved.isKeyChanged());

        // A DELETE carries only the before image.
        Struct before = new Struct(employeeSchema).put("employeeNumber", 1001).put("lastName", "Smith")
                .put("firstName", "John").put("email", "john.smith@example.com").put("officeCode", "NYC01");
        Schema pkSchema = SchemaBuilder.struct().field("employeeNumber", Schema.INT32_SCHEMA).build();
        ClickHouseStruct delete = new ClickHouseStruct(8L, "employees-topic",
                new Struct(pkSchema).put("employeeNumber", 1001), 0, System.currentTimeMillis(),
                before, null, null, ClickHouseConverter.CDC_OPERATION.DELETE);
        delete.setTs_ms(1709290200000L);
        delete.setTsSec(1709290200L);
        delete.setGtid(12346L);
        delete.setVersion(STANDARD_VERSION + 1);
        ReplicationHistoryHandler.UpdateQueryParams deleted = handler.buildUpdateQueryParams(delete);
        Assert.assertEquals(1001, deleted.getPrimaryKeyValue());
        Assert.assertFalse("a DELETE moves no key", deleted.isKeyChanged());

        // Same-key UPDATE: no key change.
        Assert.assertFalse(handler.buildUpdateQueryParams(updateRecord(1001, 1001)).isKeyChanged());
    }

    /**
     * Gap G-12.03-3 end to end through the handler: an UPDATE that moves key 1001
     * to 2002 closes 1001, marks 1001 deleted at the sentinel, and binds the after
     * image (2002) as parameters -- so 1001 leaves the current-state view and 2002
     * enters it. A same-key UPDATE emits no marker.
     */
    @Test
    public void keyChangingUpdateClosesAndRetiresTheOldKey() {
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);

        ReplicationHistoryHandler.UpdateQueryParams params = handler.buildUpdateQueryParams(updateRecord(1001, 2002));
        MutablePair<String, Map<String, Integer>> result = handler.generateUpdateQuery(
                "test_history.employees", employeeFields, columnToDataTypeMap, params);
        String query = result.left;

        Assert.assertEquals("close row + after row + old-key marker: " + query, 2, occurrences(query, "UNION ALL"));
        Assert.assertEquals("the close row and the marker both select 1001's open row: " + query,
                2, occurrences(query, OPEN_ROW_AT_1001));
        Assert.assertFalse("nothing is selected by the new key: " + query, query.contains("`employeeNumber`=2002"));
        String marker = query.substring(query.lastIndexOf("UNION ALL"));
        Assert.assertTrue("1001 is marked deleted: " + marker, marker.contains("1 as `is_deleted`"));
        Assert.assertTrue("... by the UPDATE: " + marker, marker.contains("'U' as `_operation`"));
        Assert.assertTrue("... at its open sorting key: " + marker,
                marker.contains("toDateTime('2100-01-01 00:00:00', 'UTC') as `_valid_to`"));
        Assert.assertTrue("the after image (2002) is bound as parameters", result.right.containsKey("employeeNumber"));
        Assert.assertTrue(params.isKeyChanged());
        System.out.println("Generated Query for key-changing UPDATE: " + query);

        ReplicationHistoryHandler.UpdateQueryParams sameKey = handler.buildUpdateQueryParams(updateRecord(1001, 1001));
        String sameKeyQuery = handler.generateUpdateQuery("test_history.employees", employeeFields,
                columnToDataTypeMap, sameKey).left;
        Assert.assertFalse(sameKey.isKeyChanged());
        Assert.assertEquals("no marker for a same-key UPDATE: " + sameKeyQuery, 1, occurrences(sameKeyQuery, "UNION ALL"));
        Assert.assertFalse(sameKeyQuery.contains("1 as `is_deleted`"));
    }

    /**
     * Spec 12.03 section 3.5 / Gap G-12.03-4: the history rows carry the record's
     * standard version -- the value the INSERT path binds -- not a separate
     * {@code SnowFlakeId.generate(ts_ms, gtid, false)} that ignored the sequence
     * number and the commit floor.
     */
    @Test
    public void versionComesFromTheRecordNotFromTimestampAndGtid() {
        ClickHouseStruct record = createTestRecord(1001, "Doe", "John", "john.doe@example.com", "NYC01");
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);

        ReplicationHistoryHandler.UpdateQueryParams params = handler.buildUpdateQueryParams(record);

        Assert.assertEquals("the event's standard version", record.getVersion(), params.getVersion());
        Assert.assertEquals(STANDARD_VERSION, params.getVersion());
        Assert.assertNotEquals("not a SnowFlakeId of (ts_ms, gtid)",
                SnowFlakeId.generate(record.getTs_ms(), record.getGtid(), false), params.getVersion());

        String update = handler.generateUpdateQuery("test_history.employees", employeeFields, columnToDataTypeMap, params).left;
        String delete = handler.generateDeleteQuery("test_history.employees", columnToDataTypeMap, params).left;
        Assert.assertEquals("close row and after row carry V: " + update, 2, occurrences(update, STANDARD_VERSION + " as `_version`"));
        Assert.assertFalse("never V+1: " + update, update.contains(String.valueOf(STANDARD_VERSION + 1)));
        Assert.assertEquals("close row and marker carry V: " + delete, 2, occurrences(delete, String.valueOf(STANDARD_VERSION)));
        Assert.assertFalse("never V+1: " + delete, delete.contains(String.valueOf(STANDARD_VERSION + 1)));
    }

    /**
     * The standard path derives the version lazily at bind time; the history
     * statements are built before any binding (and the DELETE binds nothing), so
     * the handler derives it the same way ({@code calculateVersion}) when the
     * record does not carry one yet -- here the raw GTID, as the test constructor
     * runs with {@code snowflake.id=false}.
     */
    @Test
    public void versionIsDerivedTheStandardWayWhenNotYetCalculated() {
        ClickHouseStruct record = createTestRecord(1001, "Doe", "John", "john.doe@example.com", "NYC01");
        record.setVersion(-1L);
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);

        ReplicationHistoryHandler.UpdateQueryParams params = handler.buildUpdateQueryParams(record);

        Assert.assertEquals("raw-GTID versioning: the version IS the GTID", 12345L, params.getVersion());
        Assert.assertEquals("and it is left on the record for the INSERT path to reuse", 12345L, record.getVersion());
    }

    /**
     * Spec 02.05 section 3.2 applied to history rows: a version that cannot be
     * derived is refused loudly. Embedded as -1 it would become the maximum UInt64
     * and win every merge for the key forever.
     */
    @Test
    public void underivableVersionIsRefused() {
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);

        // No GTID, no sequence number, no LSN, no source timestamp: nothing to derive from.
        ClickHouseStruct underivable = createTestRecord(1001, "Doe", "John", "john.doe@example.com", "NYC01");
        underivable.setVersion(-1L);
        underivable.setGtid(-1L);
        underivable.setTs_ms(0L);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> handler.buildUpdateQueryParams(underivable));
        Assert.assertTrue(refused.getMessage(), refused.getMessage().contains("_version -1"));
        Assert.assertEquals("still not derived", -1L, underivable.getVersion());

        // A zero version is not producible by any source coordinate either.
        ClickHouseStruct zero = createTestRecord(1001, "Doe", "John", "john.doe@example.com", "NYC01");
        zero.setVersion(0L);
        assertThrows(IllegalStateException.class, () -> handler.buildUpdateQueryParams(zero));
    }

    /**
     * Spec 12.03 section 3.4 / Gap G-12.03-6: the bulk close of a replicated
     * TRUNCATE closes every open row at the EVENT time (not now()) with the
     * event's version, in one statement that only inserts.
     */
    @Test
    public void bulkCloseUsesEventTimeAndVersion() throws Exception {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, new DBMetadata(config));
        RecordingJdbc jdbc = new RecordingJdbc();

        handler.executeHistoryBulkClose(jdbc.connection(), "test_history.employees", true,
                1709290200L, 4242L, ClickHouseConverter.CDC_OPERATION.TRUNCATE);

        Assert.assertEquals("one statement prepared and executed: " + jdbc.events,
                Arrays.asList(RecordingJdbc.PREPARE, RecordingJdbc.EXECUTE), jdbc.kinds());
        String sql = jdbc.events.get(0).sql;
        Assert.assertTrue("closes at the event time: " + sql,
                sql.contains("toDateTime('2024-03-01 10:50:00', 'UTC') AS `_valid_to`"));
        Assert.assertTrue("markers start at the event time: " + sql,
                sql.contains("toDateTime('2024-03-01 10:50:00', 'UTC') AS `_valid_from`"));
        Assert.assertTrue("open rows are selected by the sentinel: " + sql,
                sql.contains("WHERE `_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC') AND `is_deleted` = 0"));
        Assert.assertEquals("every row carries the event's version: " + sql, 2, occurrences(sql, "4242 AS `_version`"));
        Assert.assertTrue("markers record the TRUNCATE: " + sql, sql.contains("'T' AS `_operation`"));
        Assert.assertTrue("targets the qualified table: " + sql, sql.startsWith("INSERT INTO `test_history`.`employees` "));
        Assert.assertFalse("nothing destructive: " + sql, sql.toUpperCase().contains("TRUNCATE"));

        RecordingJdbc untouched = new RecordingJdbc();
        assertThrows(IllegalStateException.class, () -> handler.executeHistoryBulkClose(
                untouched.connection(), "test_history.employees", true, 1709290200L, 0L,
                ClickHouseConverter.CDC_OPERATION.TRUNCATE));
        Assert.assertTrue("an underivable version prepares nothing", untouched.events.isEmpty());
    }
}
