package com.altinity.clickhouse.sink.connector.history;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BinLogHistoryTest {

    private BinLogHistory binLogHistory;
    private ClickHouseSinkConnectorConfig config;
    private Map<String, String> columnToDataTypesMap;
    private Field[] fields;

    @BeforeEach
    public void setUp() {
        binLogHistory = new BinLogHistory();
        config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        
        columnToDataTypesMap = new HashMap<>();
        columnToDataTypesMap.put("id", "Int32");
        columnToDataTypesMap.put("name", "String");
        columnToDataTypesMap.put("email", "String");
        
        fields = createTestFields();
    }

    @Test
    public void testCreateHistoryTableSyntaxWithPrimaryKey() {
        ArrayList<String> primaryKey = new ArrayList<>();
        primaryKey.add("id");
        
        String result = binLogHistory.createHistoryTableSyntax(
            primaryKey,
            "test_history",
            "test_db",
            fields,
            columnToDataTypesMap,
            config
        );
        
        Assert.assertTrue(result.contains("CREATE TABLE test_db.`test_history`"));
        Assert.assertTrue(result.contains("ORDER BY(id)"));
        Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
        Assert.assertTrue(result.contains("`database` String"));
        Assert.assertTrue(result.contains("`table` String"));
        Assert.assertTrue(result.contains("`before` String"));
        Assert.assertTrue(result.contains("`after` String"));
        Assert.assertTrue(result.contains("`_raw` String"));
        Assert.assertTrue(result.contains("`_time` UInt64"));
        Assert.assertTrue(result.contains("`is_deleted` UInt8"));
        Assert.assertTrue(result.contains("`operation` String"));
        Assert.assertTrue(result.contains("`_version` UInt64"));
        Assert.assertTrue(result.contains("`host` String"));
        Assert.assertTrue(result.contains("`logfile` String"));
        Assert.assertTrue(result.contains("`position` UInt64"));
        Assert.assertTrue(result.contains("`primary_host` String"));
    }

    @Test
    public void testCreateHistoryTableSyntaxWithNullPrimaryKey() {
        String result = binLogHistory.createHistoryTableSyntax(
            null,
            "test_history",
            "test_db",
            fields,
            columnToDataTypesMap,
            config
        );
        
        Assert.assertTrue(result.contains("CREATE TABLE test_db.`test_history`"));
        Assert.assertTrue(result.contains("ORDER BY tuple()"));
        Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
    }

    @Test
    public void testCreateHistoryTableSyntaxWithEmptyPrimaryKey() {
        ArrayList<String> primaryKey = new ArrayList<>();
        
        String result = binLogHistory.createHistoryTableSyntax(
            primaryKey,
            "test_history",
            "test_db",
            fields,
            columnToDataTypesMap,
            config
        );
        
        Assert.assertTrue(result.contains("ORDER BY tuple()"));
    }

    @Test
    public void testCreateHistoryTableSyntaxWithMultiplePrimaryKeys() {
        ArrayList<String> primaryKey = new ArrayList<>();
        primaryKey.add("id");
        primaryKey.add("name");
        
        String result = binLogHistory.createHistoryTableSyntax(
            primaryKey,
            "test_history",
            "test_db",
            fields,
            columnToDataTypesMap,
            config
        );
        
        Assert.assertTrue(result.contains("ORDER BY(id,name)"));
    }

    @Test
    public void testCreateHistoryTableSyntaxWithOptionalFields() {
        Field[] fieldsWithOptional = createFieldsWithOptional();
        
        ArrayList<String> primaryKey = new ArrayList<>();
        primaryKey.add("id");
        
        String result = binLogHistory.createHistoryTableSyntax(
            primaryKey,
            "test_history",
            "test_db",
            fieldsWithOptional,
            columnToDataTypesMap,
            config
        );
        
        Assert.assertTrue(result.contains("`id` Int32 NOT NULL"));
        Assert.assertTrue(result.contains("`email` String NULL"));
    }

    @Test
    public void testHistoryTableConstants() {
        Assert.assertEquals("CREATE TABLE", BinLogHistory.CREATE_TABLE);
        Assert.assertEquals("NULL", BinLogHistory.NULL);
        Assert.assertEquals("NOT NULL", BinLogHistory.NOT_NULL);
        Assert.assertEquals("ORDER BY", BinLogHistory.ORDER_BY);
        Assert.assertEquals("ORDER BY tuple()", BinLogHistory.ORDER_BY_TUPLE);
        Assert.assertEquals("database", BinLogHistory.DATABASE_COLUMN);
        Assert.assertEquals("String", BinLogHistory.DATABASE_COLUMN_DATA_TYPE);
        Assert.assertEquals("table", BinLogHistory.TABLE_COLUMN);
        Assert.assertEquals("String", BinLogHistory.TABLE_COLUMN_DATA_TYPE);
        Assert.assertEquals("before", BinLogHistory.BEFORE_COLUMN);
        Assert.assertEquals("after", BinLogHistory.AFTER_COLUMN);
        Assert.assertEquals("_raw", BinLogHistory.RAW_COLUMN);
        Assert.assertEquals("String", BinLogHistory.RAW_COLUMN_DATA_TYPE);
        Assert.assertEquals("_time", BinLogHistory.TIME_COLUMN);
        Assert.assertEquals("UInt64", BinLogHistory.TIME_COLUMN_DATA_TYPE);
        Assert.assertEquals("is_deleted", BinLogHistory.IS_DELETED_COLUMN);
        Assert.assertEquals("UInt8", BinLogHistory.IS_DELETED_COLUMN_DATA_TYPE);
        Assert.assertEquals("operation", BinLogHistory.OPERATION_COLUMN);
        Assert.assertEquals("String", BinLogHistory.OPERATION_COLUMN_DATA_TYPE);
        Assert.assertEquals("_version", BinLogHistory.VERSION_COLUMN);
        Assert.assertEquals("UInt64", BinLogHistory.VERSION_COLUMN_DATA_TYPE);
        Assert.assertEquals("host", BinLogHistory.HOST_COLUMN);
        Assert.assertEquals("String", BinLogHistory.HOST_COLUMN_DATA_TYPE);
        Assert.assertEquals("logfile", BinLogHistory.LOGFILE_COLUMN);
        Assert.assertEquals("String", BinLogHistory.LOGFILE_COLUMN_DATA_TYPE);
        Assert.assertEquals("position", BinLogHistory.POSITION_COLUMN);
        Assert.assertEquals("UInt64", BinLogHistory.POSITION_COLUMN_DATA_TYPE);
        Assert.assertEquals("primary_host", BinLogHistory.PRIMARY_HOST_COLUMN);
        Assert.assertEquals("String", BinLogHistory.PRIMARY_HOST_COLUMN_DATA_TYPE);
    }

    private Field[] createTestFields() {
        return new Field[] {
            new Field("id", 0, SchemaBuilder.int32().required().build()),
            new Field("name", 1, SchemaBuilder.string().required().build()),
            new Field("email", 2, SchemaBuilder.string().required().build())
        };
    }

    private Field[] createFieldsWithOptional() {
        return new Field[] {
            new Field("id", 0, SchemaBuilder.int32().required().build()),
            new Field("name", 1, SchemaBuilder.string().required().build()),
            new Field("email", 2, SchemaBuilder.string().optional().build())
        };
    }
}