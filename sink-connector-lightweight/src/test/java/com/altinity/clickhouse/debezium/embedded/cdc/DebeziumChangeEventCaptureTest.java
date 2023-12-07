package com.altinity.clickhouse.debezium.embedded.cdc;

import org.json.simple.parser.ParseException;
import static org.junit.Assert.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

}