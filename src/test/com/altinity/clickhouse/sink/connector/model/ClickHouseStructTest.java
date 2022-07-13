package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ClickHouseStructTest {

    ClickHouseStruct st;
    @Before
    public void initialize() {
    }

    @Test
    public void testGtid() {

        Map<String, Object> metaData = new HashMap<String, Object>();
        metaData.put("gtid", "0010-122323-0232323:512345");


        String keyField = "customer";
        Schema basicKeySchema = SchemaBuilder
                .struct()
                .field(keyField, Schema.STRING_SCHEMA)
                .build();

        st = new ClickHouseStruct(10, "topic_1", new Struct(basicKeySchema), 100,
                12322323L, new Struct(basicKeySchema), new Struct(basicKeySchema),
                metaData, ClickHouseConverter.CDC_OPERATION.CREATE);

        Assert.assertTrue(st.getGtid() == 512345);

    }
}
