package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
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
    public void initialize() {
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
        DbWriter writer = new DbWriter(hostName, port, database, tableName, userName, password, config);

        this.conn = writer.getConnection();

    }

    private Field[] createFields() {
        ArrayList<Field> fields = new ArrayList<>();
        fields.add(new Field("customerName", 0, Schema.STRING_SCHEMA));
        fields.add(new Field("occupation", 1, Schema.STRING_SCHEMA));
        fields.add(new Field("quantity", 2, Schema.INT32_SCHEMA));

        fields.add(new Field("amount_1", 3, Schema.FLOAT32_SCHEMA));

        fields.add(new Field("amount", 3, Schema.FLOAT64_SCHEMA));
        fields.add(new Field("employed", 4, Schema.BOOLEAN_SCHEMA));

        fields.add(new Field("blob_storage", 5, Schema.BYTES_SCHEMA));




        Field[] result = new Field[fields.size()];
        fields.toArray(result);
        return result;
    }
    @Test
    public void getColumnNameToCHDataTypeMappingTest() {
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
        Field[] fields = createFields();
        Map<String, String> colNameToDataTypeMap = act.getColumnNameToCHDataTypeMapping(fields);

        Assert.assertFalse(colNameToDataTypeMap.isEmpty());
    }
    @Test
    public void testCreateTableSyntax() {
        String primaryKey = "customer_id";

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String query = act.createTableSyntax(primaryKey, "auto_create_table", this.columnToDataTypesMap);

        String expectedQuery = "CREATE TABLE auto_create_table(`amount` INT32,`address` String,`first_name` String) ENGINE = MergeTree PRIMARY KEY customer_id ORDER BY customer_id";
        Assert.assertTrue(query.equalsIgnoreCase(expectedQuery));
    }

    @Tag("IntegrationTest")
    @Test
    public void testRunCreateTableQuery() throws SQLException {
        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();

        String primaryKey = "customer_id";
        String query = act.createTableSyntax(primaryKey, "auto_create_table", this.columnToDataTypesMap);

        act.runCreateTableQuery(query, this.conn);
    }

}
