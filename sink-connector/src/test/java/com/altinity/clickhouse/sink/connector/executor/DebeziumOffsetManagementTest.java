package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DebeziumOffsetManagementTest {

    // Test function to validate the isWithinRange function
    @Test
    public void testIsWithinRange() {
        // Create batch timestamps map.
        Map<Pair<Long, Long>, List<ClickHouseStruct>> batchTimestamps = new HashMap();
        List<ClickHouseStruct> clickHouseStructs = new ArrayList<>();
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch2 = new ClickHouseStruct(8, "SERVER5432.test.customers", getKafkaStruct(), 2, System.currentTimeMillis() ,null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct ch3 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, System.currentTimeMillis(), null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        clickHouseStructs.add(ch1);

        batchTimestamps.put(Pair.of(1L, 2L), clickHouseStructs);



        // Test case 1
        DebeziumOffsetManagement dom = new DebeziumOffsetManagement(batchTimestamps);
        assert(dom.isWithinRange(Pair.of(10L, 12L)) == false);

        // Test case 2
        dom = new DebeziumOffsetManagement(batchTimestamps);

        dom.isWithinRange(Pair.of(1L, 2L));
        assert(dom.isWithinRange(Pair.of(1L, 2L)) == true);

    }
    public static Struct getKafkaStruct() {
        Schema kafkaConnectSchema = SchemaBuilder
                .struct()
                .field("first_name", Schema.STRING_SCHEMA)
                .field("last_name", Schema.STRING_SCHEMA)
                .field("quantity", Schema.INT32_SCHEMA)
                .field("amount", Schema.FLOAT64_SCHEMA)
                .field("employed", Schema.BOOLEAN_SCHEMA)
                .build();

        Struct kafkaConnectStruct = new Struct(kafkaConnectSchema);
        kafkaConnectStruct.put("first_name", "John");
        kafkaConnectStruct.put("last_name", "Doe");
        kafkaConnectStruct.put("quantity", 100);
        kafkaConnectStruct.put("amount", 23.223);
        kafkaConnectStruct.put("employed", true);


        return kafkaConnectStruct;
    }
}
