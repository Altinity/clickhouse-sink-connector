package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.time.Date;
import io.debezium.time.Time;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

@Testcontainers
public class ClickHouseDataTypeMapperTest {

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("./datatypes.sql");

    @Test
    public void getClickHouseDataType() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.INT16, null);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("INT16"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.INT32, null);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("INT32"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.BYTES_SCHEMA.type(), null);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("String"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.INT32_SCHEMA.type(), Time.SCHEMA_NAME);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("String"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.INT32_SCHEMA.type(), Date.SCHEMA_NAME);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("Date32"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.STRUCT, VariableScaleDecimal.LOGICAL_NAME);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("Decimal"));

    }

    @Test
    public void convert() throws SQLException {
        //Integer tests.
       // ClickHouseDataTypeMapper.convert(Schema.INT16_SCHEMA.type(), null, 244223232, 1, ps);

        //double maxDoubleTest = 1000000000000000000000000000000000000000000000000000000000000d;

        double maxDoubleTest = 999.00009d;

        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "datatypes";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();

        BaseDbWriter dbWriter = new BaseDbWriter(dbHostName, port,
                database, userName, password, null);

        PreparedStatement ps = dbWriter.getConnection().prepareStatement("insert into datatypes.numeric_types_DOUBLE (Type, Minimum_Value, " +
                        "Zero_Value, Maximum_Value, _sign, _version) values(?, ?, ?, ?, ?)");

        int index = 1;
        ps.setString(index++, "Test");
        ps.setDouble(index++, 0d);
        ps.setDouble(index++, 0d);
        ClickHouseDataTypeMapper.convert(Schema.FLOAT32_SCHEMA.type(), null, maxDoubleTest, index++, ps, new ClickHouseSinkConnectorConfig(new HashMap<String, String>()), ClickHouseDataType.Float32);
        ps.setDouble(index, 1d);
        ps.setInt(index++,1);
        ps.setInt(index++, 12);

        ps.addBatch();
        ps.executeBatch();

        Statement stmt = dbWriter.getConnection().createStatement();
        ResultSet rs = stmt.executeQuery("select Maximum_Value from datatypes.numeric_types_DOUBLE");
        Assert.assertTrue(rs.next());
       // Assert.assertEquals(rs.getObject(1), maxDoubleTest);
        System.out.println("Query persisted");
//
//             PreparedStatement stmt = conn.prepareStatement("select 1")) {
//            ResultSet rs = stmt.executeQuery();
//
//        ClickHouseDataTypeMapper.convert(Schema.FLOAT32_SCHEMA.type(), null, maxDoubleTest, 1, ps);



    }
}
