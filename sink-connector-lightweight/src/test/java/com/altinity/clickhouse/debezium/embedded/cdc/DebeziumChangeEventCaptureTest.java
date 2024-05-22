package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.json.simple.parser.ParseException;
import static org.junit.Assert.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class DebeziumChangeEventCaptureTest {

    @Test
    @DisplayName("Unit test to check if the LSN record is created properly")
    public void testUpdateBingLogInformation() throws ParseException {
        String record = "{\"transaction_id\":null,\"ts_sec\":1687278006,\"file\":\"mysql-bin.000003\",\"pos\":1156385,\"gtids\":\"30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442\",\"row\":1,\"server_id\":266,\"event\":2}";

        String updatedRecord = new DebeziumOffsetStorage().updateBinLogInformation(record , "mysql-bin.001", "1222", "232232323");

        assertTrue(updatedRecord.equalsIgnoreCase("{\"transaction_id\":null,\"ts_sec\":1687278006,\"file\":\"mysql-bin.001\",\"pos\":\"1222\",\"gtids\":\"232232323\",\"row\":1,\"server_id\":266,\"event\":2}"));
    }

    @Test
    @DisplayName("Unit test to check if the LSN record is updated properly")
    public void testUpdateLsn() throws ParseException {
        String record = "{\"transaction_id\":null,\"lsn_proc\":27485360,\"messageType\":\"UPDATE\",\"lsn\":27485360,\"txId\":743,\"ts_usec\":1687876724804733}";

        String updatedRecord = new DebeziumOffsetStorage().updateLsnInformation(record, 1232323L);

        assertTrue(updatedRecord.equalsIgnoreCase("{\"transaction_id\":null,\"lsn_proc\":1232323,\"messageType\":\"UPDATE\",\"lsn\":1232323,\"txId\":743,\"ts_usec\":1687876724804733}"));
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

    @Test
    @DisplayName("Should assign unique sequence numbers within the same second")
    public void shouldAssignUniqueSequenceNumbersWithinSameSecond() throws InterruptedException {
        long currentTimestamp = System.currentTimeMillis();
        // Define multiple ClickHouseStructs
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch1.setTs_ms(currentTimestamp);

        ClickHouseStruct ch2 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp + 100, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch2.setTs_ms(currentTimestamp);

        ClickHouseStruct ch3 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp + 200, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch3.setTs_ms(currentTimestamp);

        ClickHouseStruct ch4 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp + 300, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch4.setTs_ms(currentTimestamp);

        ClickHouseStruct ch5 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp + 500, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch5.setTs_ms(currentTimestamp);

        Thread.sleep(1000);
        ClickHouseStruct ch6 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp + 1000, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch6.setTs_ms(currentTimestamp);

        // Make a list of ch1, ch2, ch3 and ch4
        List<ClickHouseStruct> clickHouseStructs = Arrays.asList(ch1, ch2, ch3, ch4, ch5);
        DebeziumChangeEventCapture.addVersion(clickHouseStructs);

        Thread.sleep(1000);
        // Add ch5 and ch6
        List<ClickHouseStruct> clickHouseStructs2 = Arrays.asList(ch5, ch6);
        DebeziumChangeEventCapture.addVersion(clickHouseStructs2);

        // Check if the sequence numbers are unique
        assertTrue(clickHouseStructs.get(0).getSequenceNumber() < clickHouseStructs.get(1).getSequenceNumber());
        assertTrue(clickHouseStructs.get(1).getSequenceNumber() < clickHouseStructs.get(2).getSequenceNumber());
        assertTrue(clickHouseStructs.get(2).getSequenceNumber() < clickHouseStructs.get(3).getSequenceNumber());


        // Validate ch5 and ch6
        assertTrue(clickHouseStructs2.get(0).getSequenceNumber() < clickHouseStructs2.get(1).getSequenceNumber());

        assertTrue(clickHouseStructs.get(3).getSequenceNumber() < clickHouseStructs2.get(0).getSequenceNumber());


    }

    @Test
    @DisplayName("Should reset sequence number when a second has passed")
    public void shouldResetSequenceNumberWhenSecondHasPassed() {

    }
}