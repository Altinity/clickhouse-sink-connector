package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
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
        record.setTs_ms(1709290200000L);  // 2025-03-01 10:30:00 UTC in ms
        record.setTsSec(1709290200L);     // 2025-03-01 10:30:00 UTC in seconds
        record.setGtid(12345L);

        return record;
    }

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

        // Verify query has two UNION ALL clauses (three SELECTs)
        int unionAllCount = query.split("UNION ALL").length - 1;
        Assert.assertEquals("Query should have two UNION ALL clauses", 2, unionAllCount);

        // Verify column index map has both after and before image columns
        Assert.assertTrue("After image column should be in index map", 
                columnIndexMap.containsKey("employeeNumber"));
        Assert.assertTrue("Before image column should be in index map with before_ prefix", 
                columnIndexMap.containsKey("before_employeeNumber"));

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
    public void testGenerateUpdateQueryForDelete() {
        // Test with DELETE operation
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

        // Test generateUpdateQuery with DELETE operation
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(queryFormatter, null);
        MutablePair<String, Map<String, Integer>> result = handler.generateUpdateQuery(
                "test_history.employees",
                employeeFields,
                columnToDataTypeMap,
                params
        );

        String query = result.left;

        // Verify the query structure for DELETE
        Assert.assertNotNull("Query should not be null", query);
        Assert.assertTrue("Query should contain INSERT INTO", query.contains("INSERT INTO"));
        Assert.assertTrue("Query should contain 'D' operation", query.contains("'D' as `_operation`"));

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
}
