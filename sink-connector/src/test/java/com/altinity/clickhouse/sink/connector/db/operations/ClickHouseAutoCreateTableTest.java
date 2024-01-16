package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import io.debezium.data.Json;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Testcontainers

public class ClickHouseAutoCreateTableTest {

    static Map<String, String> columnToDataTypesMap;

    static ClickHouseConnection conn;

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("./init_clickhouse.sql");
    @BeforeAll
    static void initialize() {

        columnToDataTypesMap =  getExpectedColumnToDataTypesMap();

//        this.columnToDataTypesMap.put("customer_id", "Int32");
//        this.columnToDataTypesMap.put("address", "String");
//        this.columnToDataTypesMap.put("first_name", "String");
//        this.columnToDataTypesMap.put("amount", "Int32");

        String hostName = "localhost";
        Integer port = 8123;
        String database = "test";
        String userName = "root";
        String password = "root";
        String tableName = "auto_create_table";

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        DbWriter writer = new DbWriter(hostName, port, database, tableName, userName, password, config, null);

        conn = writer.getConnection();

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

        Schema decimalSchema = SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).parameter("scale", "10")
                        .parameter("connect.decimal.precision", "30")
                                .name(Decimal.LOGICAL_NAME).build();

        fields.add(new Field("blob_storage_scale", 7, decimalSchema));

        fields.add(new Field("json_output", 8, Json.schema()));

        fields.add(new Field("max_amount", 9, Schema.FLOAT64_SCHEMA));

        Field[] result = new Field[fields.size()];
        fields.toArray(result);
        return result;
    }

    protected static Map<String, String> getExpectedColumnToDataTypesMap() {

        Map<String, String> columnToDataTypesMap = new HashMap<>();
        columnToDataTypesMap.put("customerName", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("occupation", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("quantity", ClickHouseDataType.Int32.name());

        columnToDataTypesMap.put("amount_1", ClickHouseDataType.Float32.name());

        columnToDataTypesMap.put("amount", ClickHouseDataType.Float64.name());

        columnToDataTypesMap.put("employed", ClickHouseDataType.Bool.name());

        columnToDataTypesMap.put("blob_storage", ClickHouseDataType.String.name());

        columnToDataTypesMap.put("blob_storage_scale", ClickHouseDataType.Decimal.name());

        columnToDataTypesMap.put("json_output", ClickHouseDataType.JSON.name());

        columnToDataTypesMap.put("max_amount", ClickHouseDataType.Float64.name());


        return columnToDataTypesMap;
    }

    @Test
    public void getColumnNameToCHDataTypeMappingTest() {
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, false);
        Field[] fields = createFields();
        Map<String, String> colNameToDataTypeMap = act.getColumnNameToCHDataTypeMapping(fields);

        Map<String, String> expectedColNameToDataTypeMap = getExpectedColumnToDataTypesMap();

       // Assert.assertTrue(colNameToDataTypeMap.equals(expectedColNameToDataTypeMap));
        Assert.assertFalse(colNameToDataTypeMap.isEmpty());
    }

    @Test
    public void testCreateTableSyntax() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, false);

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", createFields(), this.columnToDataTypesMap);
        //System.out.println("QUERY " + query);
        Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY(customerName)"));
        //Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY (customerName)"));
    }

    @Test
    public void testCreateTableSyntaxReplacingMergeTreeDeleteColumn() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN.toString(), "row_is_deleted");
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, false);

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", createFields(), this.columnToDataTypesMap);
        //System.out.println("QUERY " + query);
        Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`row_is_deleted` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) PRIMARY KEY(customerName) ORDER BY(customerName)"));
    }

    @Test
    public void testCreateTableSyntaxUseIsDeletedColumn() {
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(ClickHouseSinkConnectorConfigVariables.USE_REPLACING_MERGE_TREE_IS_DELETED_COLUMN.toString(), "true");
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(properties);
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, true);

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", createFields(), this.columnToDataTypesMap);
        //System.out.println("QUERY " + query);                                                
        Assert.assertTrue(query.equalsIgnoreCase("CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`is_deleted` UInt8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version,is_deleted) PRIMARY KEY(customerName) ORDER BY(customerName)"));
    }

    @Test
    public void testCreateTableEmptyPrimaryKey() {
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, false);

        String query = act.createTableSyntax(null, "auto_create_table", createFields(), this.columnToDataTypesMap);

        String expectedQuery = "CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) ORDER BY tuple()";
        //System.out.println("QUERY " + query);
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
    }
    @Test
    public void testCreateTableMultiplePrimaryKeys() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customer_id");
        primaryKeys.add("customer_name");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, false);

        String query = act.createTableSyntax(primaryKeys, "auto_create_table", createFields(), this.columnToDataTypesMap);

        String expectedQuery = "CREATE TABLE auto_create_table(`customerName` String NOT NULL,`occupation` String NOT NULL,`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,`amount` Float64 NOT NULL,`employed` Bool NOT NULL,`blob_storage` String NOT NULL,`blob_storage_scale` Decimal NOT NULL,`json_output` JSON,`max_amount` Float64 NOT NULL,`_sign` Int8,`_version` UInt64) ENGINE = ReplacingMergeTree(_version) ORDER BY tuple()";
        //System.out.println("QUERY " + query);
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
    }

    @Test
    @Tag("IntegrationTest")
    public void testCreateNewTable() {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "default";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, false);
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        try {
            act.createNewTable(primaryKeys, "auto_create_table", this.createFields(), writer.getConnection());
        } catch(SQLException se) {
            Assert.assertTrue(false);
        }
    }

    @Test
    public void testIsPrimaryKeyColumnPresent()    {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");
        primaryKeys.add("id");

        ArrayList<String> primaryKeys2 = new ArrayList<>();
        primaryKeys2.add("customerName2");
        primaryKeys2.add("id2");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable(config, false);

        Map<String, String> columnToDataTypesMap = new HashMap<>();
        columnToDataTypesMap.put("customerName", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("occupation", ClickHouseDataType.String.name());
        columnToDataTypesMap.put("quantity", ClickHouseDataType.Int32.name());
        columnToDataTypesMap.put("amount_1", ClickHouseDataType.Float32.name());
        columnToDataTypesMap.put("id", ClickHouseDataType.Int8.name());

        Assert.assertTrue(act.isPrimaryKeyColumnPresent(primaryKeys, columnToDataTypesMap));
        Assert.assertFalse(act.isPrimaryKeyColumnPresent(primaryKeys2, columnToDataTypesMap));
    }

}
