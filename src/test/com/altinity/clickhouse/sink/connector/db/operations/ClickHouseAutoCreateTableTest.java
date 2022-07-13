package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.clickhouse.client.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ClickHouseAutoCreateTableTest {

    Map<String, String> columnToDataTypesMap;

    ClickHouseConnection conn;
    @Before
    public void initialize() throws SQLException {
        this.columnToDataTypesMap = new HashMap<>();

        this.columnToDataTypesMap.put("customer_id", "Int32");
        this.columnToDataTypesMap.put("address", "String");
        this.columnToDataTypesMap.put("first_name", "String");
        this.columnToDataTypesMap.put("amount", "Int32");

        String hostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "auto_create_table";

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        DbWriter writer = new DbWriter(hostName, port, database, tableName, userName, password, config, null);

        this.conn = writer.getConnection();

    }

    protected Field[] createFields() {
        ArrayList<Field> fields = new ArrayList<>();
        fields.add(new Field("customerName", 0, Schema.STRING_SCHEMA));
        fields.add(new Field("occupation", 1, Schema.STRING_SCHEMA));
        fields.add(new Field("quantity", 2, Schema.INT32_SCHEMA));

        fields.add(new Field("amount_1", 3, Schema.FLOAT32_SCHEMA));

        fields.add(new Field("amount", 4, Schema.FLOAT64_SCHEMA));
        fields.add(new Field("employed", 5, Schema.BOOLEAN_SCHEMA));

        fields.add(new Field("blob_storage", 6, SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).
                name(Decimal.LOGICAL_NAME).build()));


        Field[] result = new Field[fields.size()];
        fields.toArray(result);
        return result;
    }

    protected Map<String, String> getExpectedColumnToDataTypesMap() {

        Map<String, String> columnToDataTypesMap = new HashMap<>();
        columnToDataTypesMap.put("customerName", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("occupation", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("quantity", ClickHouseDataType.Int32.name());

        columnToDataTypesMap.put("amount_1", ClickHouseDataType.Float32.name());

        columnToDataTypesMap.put("amount", ClickHouseDataType.Float64.name());

        columnToDataTypesMap.put("employed", ClickHouseDataType.Bool.name());

        columnToDataTypesMap.put("blob_storage", ClickHouseDataType.String.name());


        return columnToDataTypesMap;
    }

    @Test
    public void getColumnNameToCHDataTypeMappingTest() {
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
        Field[] fields = createFields();
        Map<String, String> colNameToDataTypeMap = act.getColumnNameToCHDataTypeMapping(fields);

        Map<String, String> expectedColNameToDataTypeMap = getExpectedColumnToDataTypesMap();

        //Assert.assertTrue(colNameToDataTypeMap.equals(expectedColNameToDataTypeMap));
        Assert.assertFalse(colNameToDataTypeMap.isEmpty());
    }

    @Test
    public void testCreateTableSyntax() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customer_id");

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", this.columnToDataTypesMap);

        String expectedQuery = "CREATE TABLE auto_create_table(`amount` Int32,`address` String,`customer_id` Int32,`first_name` String,`sign` Int8,`ver` UInt64) ENGINE = ReplacingMergeTree(ver) PRIMARY KEY(customer_id) ORDER BY(customer_id)";
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
    }

    @Test
    public void testCreateTableMultiplePrimaryKeys() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customer_id");
        primaryKeys.add("customer_name");

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", this.columnToDataTypesMap);

        String expectedQuery = "CREATE TABLE auto_create_table(`amount` Int32,`address` String,`customer_id` Int32,`first_name` String,`sign` Int8,`ver` UInt64) ENGINE = ReplacingMergeTree(ver) PRIMARY KEY(customer_id,customer_name) ORDER BY(customer_id,customer_name)";
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
        System.out.println(query);
    }

    @Test
    @Tag("IntegrationTest")
    public void testCreateNewTable() {
        String dbHostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        try {
            act.createNewTable(primaryKeys, "auto_create_table", this.createFields(), writer.getConnection());
        } catch(SQLException se) {
            Assert.assertTrue(false);
        }
    }

}
