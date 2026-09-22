package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SourcePosition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
import org.json.simple.parser.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class DebeziumChangeEventCaptureTest {

    /*
     * Minimal harness to push a control record (heartbeat / transaction
     * metadata) through the REAL handleChangeEventBatch, so the tests below
     * observe what the dispatch loop does to the version-sequence statics -- not
     * a restatement of it. Mirrors ControlRecordLogLevelTest; no ClickHouse,
     * MySQL or Debezium engine is needed.
     */

    /** A committer that only records what it was asked to do. */
    private static final class RecordingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {
        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
        }

        @Override
        public void markBatchFinished() {
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return new DebeziumEngine.Offsets() {
                @Override
                public void set(String key, Object value) {
                }
            };
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> changeEvent(SourceRecord record) {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return record.topic();
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    private static Map<String, Object> partition() {
        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("server", "db1");
        return partition;
    }

    private static Map<String, Object> offset() {
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("file", "mysql-bin.000020");
        offset.put("pos", 450L);
        return offset;
    }

    /** A heartbeat exactly as Debezium emits one: envelope {@code ts_ms} only, no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> heartbeatAt(long connectorClockMs) {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ts_ms", connectorClockMs);
        return changeEvent(new SourceRecord(partition(), offset(), "__debezium-heartbeat.db1", 0,
                null, null, valueSchema, value));
    }

    /** A transaction-boundary record: regular topic, envelope {@code ts_ms}, no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> transactionMetadataAt(long connectorClockMs) {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.TransactionMetadataValue")
                .field("status", Schema.STRING_SCHEMA)
                .field("id", Schema.STRING_SCHEMA)
                .field("event_count", Schema.OPTIONAL_INT64_SCHEMA)
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("status", "END");
        value.put("id", "file=mysql-bin.000020,pos=450");
        value.put("event_count", 3L);
        value.put("ts_ms", connectorClockMs);
        return changeEvent(new SourceRecord(partition(), offset(), "db1.transaction", 0,
                null, null, valueSchema, value));
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static void runControlRecordBatch(ChangeEvent<SourceRecord, SourceRecord> record)
            throws InterruptedException {
        new DebeziumChangeEventCapture().handleChangeEventBatch(
                Collections.singletonList(record), new RecordingCommitter(), new Properties(),
                new SourceRecordParserService(), config());
    }

    /** The four statics of the version sequence, captured for comparison. */
    private static List<Object> sequenceState() {
        return Arrays.asList(
                DebeziumChangeEventCapture.sequenceMaxSourceTs,
                DebeziumChangeEventCapture.sequenceAnchorTs,
                DebeziumChangeEventCapture.sequenceNumber,
                DebeziumChangeEventCapture.sequenceHighWaterPosition);
    }

    @Test
    @DisplayName("Unit test to check if the LSN record is created properly")
    public void testUpdateBingLogInformation() throws ParseException {
        // INVERTED expectations (spec 09.03 section 3.4): this test used to
        // pin `"pos":"1222"` (a string, which Debezium fails on when it is not
        // numeric) and the OLD `"row":1,"event":2` skip counters (which skip
        // rows of the first event at the NEW position). A file/position edit
        // now stores the position as a number and resets both counters; the
        // GTID set given alongside is kept.
        String record = "{\"transaction_id\":null,\"ts_sec\":1687278006,\"file\":\"mysql-bin.000003\",\"pos\":1156385,\"gtids\":\"30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442\",\"row\":1,\"server_id\":266,\"event\":2}";

        String updatedRecord = new DebeziumOffsetStorage().updateBinLogInformation(record , "mysql-bin.001", "1222", "232232323");

        org.json.simple.JSONObject json = (org.json.simple.JSONObject)
                new org.json.simple.parser.JSONParser().parse(updatedRecord);
        assertEquals("mysql-bin.001", json.get("file"));
        assertEquals(1222L, json.get("pos"));
        assertEquals("232232323", json.get("gtids"));
        assertEquals(0L, json.get("row"));
        assertEquals(0L, json.get("event"));
        assertEquals(266L, json.get("server_id"));
        assertEquals(1687278006L, json.get("ts_sec"));
        assertTrue(json.containsKey("transaction_id"));
    }

    @Test
    @DisplayName("Unit test to check if the LSN record is updated properly when provided in string format and long format")
    public void testUpdateLsn() throws ParseException {
        String record = "{\"transaction_id\":null,\"lsn_proc\":27485360,\"messageType\":\"UPDATE\",\"lsn\":27485360,\"txId\":743,\"ts_usec\":1687876724804733}";

        String updatedRecord = new DebeziumOffsetStorage().updateLsnInformation(record, "0/1A38FA0");

        assertTrue(updatedRecord.equalsIgnoreCase("{\"transaction_id\":null,\"lsn_proc\":27496352,\"messageType\":\"UPDATE\",\"lsn\":27496352,\"txId\":743,\"ts_usec\":1687876724804733}"));

        String updatedRecordLong = new DebeziumOffsetStorage().updateLsnInformation(record, "27496352");
        assertTrue(updatedRecordLong.equalsIgnoreCase("{\"transaction_id\":null,\"lsn_proc\":27496352,\"messageType\":\"UPDATE\",\"lsn\":27496352,\"txId\":743,\"ts_usec\":1687876724804733}"));

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

    /**
     * Puts the static version-sequence state back to what a freshly started
     * JVM has, so a test observes the real start/resume seeding rather than
     * whatever an earlier test left behind. The fields are the production
     * statics themselves (spec 02.02 §2), not copies.
     */
    private static void resetSequenceStateAsAfterRestart() {
        DebeziumChangeEventCapture.sequenceNumber = DebeziumChangeEventCapture.SEQUENCE_START;
        DebeziumChangeEventCapture.sequenceAnchorTs = 0L;
        DebeziumChangeEventCapture.sequenceHighWaterPosition = null;
        DebeziumChangeEventCapture.sequenceMaxSourceTs = 0L;
    }

    /** Counter component of a version emitted by nextSequenceNumber (spec 02.01 §3.2). */
    private static long counterOf(long version, long effectiveTs) {
        return version - effectiveTs * 1_000_000L;
    }

    /**
     * The reset boundary is NOT one second. nextSequenceNumber computes
     * {@code diff = (int) ((effectiveTs - sequenceAnchorTs) / 1000)} and resets
     * only when {@code diff > 1}, i.e. when the clock has advanced by at least
     * 2000 ms past the anchor (spec 02.03 §3.2). An advance of 1500 ms keeps
     * incrementing; an advance of 2500 ms resets to SEQUENCE_START and moves
     * the anchor.
     */
    @Test
    @DisplayName("Should reset sequence number only once the source clock is 2000 ms past the anchor")
    public void shouldResetSequenceNumberWhenSecondHasPassed() {
        resetSequenceStateAsAfterRestart();
        final long ts = 1_757_900_000_000L;
        SourcePosition p1 = SourcePosition.ofBinlog("mysql-bin.000010", 100L, 0);
        SourcePosition p2 = SourcePosition.ofBinlog("mysql-bin.000010", 200L, 0);
        SourcePosition p3 = SourcePosition.ofBinlog("mysql-bin.000010", 300L, 0);

        long first = DebeziumChangeEventCapture.nextSequenceNumber(ts, p1);
        assertEquals("first record after start seeds the counter at SEQUENCE_START_INITIAL and increments once",
                DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 1, counterOf(first, ts));
        assertEquals("the anchor is the first record's timestamp",
                ts, DebeziumChangeEventCapture.sequenceAnchorTs);

        long plus1500 = DebeziumChangeEventCapture.nextSequenceNumber(ts + 1500, p2);
        assertEquals("1500 ms past the anchor yields diff == 1, which does NOT reset: the counter keeps incrementing",
                DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 2, counterOf(plus1500, ts + 1500));
        assertEquals("no reset, so the anchor has not moved",
                ts, DebeziumChangeEventCapture.sequenceAnchorTs);

        long plus2500 = DebeziumChangeEventCapture.nextSequenceNumber(ts + 2500, p3);
        assertEquals("2500 ms past the anchor yields diff == 2, which resets the counter to SEQUENCE_START",
                DebeziumChangeEventCapture.SEQUENCE_START, counterOf(plus2500, ts + 2500));
        assertEquals("the reset re-anchors the window on the resetting record's timestamp",
                ts + 2500, DebeziumChangeEventCapture.sequenceAnchorTs);

        assertTrue("versions stay strictly increasing across the boundary within one run",
                first < plus1500 && plus1500 < plus2500);
    }

    /**
     * The restart boundary (spec 02.02 §3.5, 02.04 §3.2), through the real
     * statics. A source lagging 30 s behind the connector clock {@code W}: the
     * first run versions a row at {@code W-40000}, sees a heartbeat carrying
     * {@code W}, then versions the key's write at {@code W-30000}. The process
     * restarts; the floor is seeded from the high-water mark ({@code v1}, the
     * last version handed off); a genuinely newer write of the key at
     * {@code W-25000} (next binlog position) must out-rank {@code v1}.
     *
     * <p>Before the fix this exact sequence gave {@code v2 < v1}: the heartbeat
     * pinned the floor to {@code W}, so {@code v1} was clamped to
     * {@code W*1e6 + 1e9 + 1}, while the restart put the floor back at 0 and
     * {@code v2 = (W-25000)*1e6 + 5e8 + 1}. The window was the lag plus the
     * seed carry, not the ~1 ms of the arithmetic example in spec 02.01 §4 --
     * which is why this test lags by 25-40 s rather than 1 ms.</p>
     */
    @Test
    @DisplayName("A newer event versioned after a seeded restart ranks above the older pre-restart event")
    public void newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent() throws InterruptedException {
        final long connectorClock = 1_787_635_797_000L; // W
        SourcePosition p400 = SourcePosition.ofBinlog("mysql-bin.000020", 400L, 0);
        SourcePosition p500 = SourcePosition.ofBinlog("mysql-bin.000020", 500L, 0);
        SourcePosition p600 = SourcePosition.ofBinlog("mysql-bin.000020", 600L, 0);

        // Run 1.
        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.nextSequenceNumber(connectorClock - 40_000, p400);
        runControlRecordBatch(heartbeatAt(connectorClock));
        long v1 = DebeziumChangeEventCapture.nextSequenceNumber(connectorClock - 30_000, p500);

        // Restart: the statics are fresh; the engine seeds the floor from the
        // durable high-water mark, which is at least v1.
        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.seedVersionFloor(v1);
        long v2 = DebeziumChangeEventCapture.nextSequenceNumber(connectorClock - 25_000, p600);

        assertTrue("the newer post-restart write (" + v2 + ") must out-rank the older pre-restart "
                        + "write (" + v1 + "); ReplacingMergeTree keeps the stale row otherwise",
                v2 > v1);
        assertTrue("the floor was seeded at floorDiv(v1, 1e6) + 1 before the first record",
                DebeziumChangeEventCapture.sequenceMaxSourceTs >= Math.floorDiv(v1, 1_000_000L) + 1);
        assertEquals("the counter still starts in the 500m domain after a start: the seed changes "
                        + "the floor, not the 2.8.0 arithmetic",
                DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 1,
                counterOf(v2, connectorClock - 25_000));

        resetSequenceStateAsAfterRestart();
    }

    /**
     * The seed carry window (spec 02.01 §4), which the heartbeat exclusion alone
     * does not close: an older event versioned with the steady-state 1000m
     * counter, a restart, and a genuinely newer event 1 ms later (next binlog
     * position) versioned with the 500m start seed. Without the seeded floor
     * {@code (T+1)*1e6 + 5e8 + 1 < T*1e6 + 1e9}; with it the newer event is
     * clamped to {@code T + 1001} and out-ranks the older one. This is the
     * former known-defect pin, flipped into the guarantee.
     */
    @Test
    @DisplayName("A newer event 1 ms after the last pre-restart event ranks above it after a seeded restart")
    public void newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent() {
        final long olderTs = 1_787_635_797_000L;
        SourcePosition olderPos = SourcePosition.ofBinlog("mysql-bin.000020", 500L, 0);
        SourcePosition newerPos = SourcePosition.ofBinlog("mysql-bin.000020", 600L, 0);

        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.nextSequenceNumber(olderTs - 10_000, SourcePosition.ofBinlog("mysql-bin.000020", 400L, 0));
        long olderBeforeRestart = DebeziumChangeEventCapture.nextSequenceNumber(olderTs, olderPos);
        assertEquals("precondition: the pre-restart write carries the steady-state seed",
                DebeziumChangeEventCapture.SEQUENCE_START, counterOf(olderBeforeRestart, olderTs));

        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.seedVersionFloor(olderBeforeRestart);
        long newerAfterRestart = DebeziumChangeEventCapture.nextSequenceNumber(olderTs + 1, newerPos);

        assertTrue("newer(" + newerAfterRestart + ") must out-rank older(" + olderBeforeRestart
                        + "); before the seeded floor it did not", newerAfterRestart > olderBeforeRestart);
        assertEquals("the newer event is clamped to the seeded slot floorDiv(older, 1e6) + 1 = T + 1001",
                olderTs + 1001, DebeziumChangeEventCapture.sequenceMaxSourceTs);
        assertEquals("and still carries the 500m start seed: the arithmetic is the 2.8.0 arithmetic",
                DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 1,
                counterOf(newerAfterRestart, olderTs + 1001));

        resetSequenceStateAsAfterRestart();
    }

    /**
     * {@code seedVersionFloor} only ever raises the floor: a lower or non-positive
     * seed is ignored, and the floor it establishes is
     * {@code floorDiv(highWater, 1e6) + 1} -- the first whole-millisecond slot
     * strictly above the high-water version (spec 02.02 §3.5).
     */
    @Test
    @DisplayName("seedVersionFloor raises the floor to floorDiv(v, 1e6) + 1 and never lowers it")
    public void seedVersionFloorRaisesButNeverLowersTheFloor() {
        resetSequenceStateAsAfterRestart();
        final long highWater = 1_787_635_797_000L * 1_000_000L + 1_000_000_000L + 7;

        assertEquals(0L, DebeziumChangeEventCapture.seedVersionFloor(0L));
        assertEquals(0L, DebeziumChangeEventCapture.seedVersionFloor(-5L));
        assertEquals("a non-positive high-water mark leaves the floor untouched",
                0L, DebeziumChangeEventCapture.sequenceMaxSourceTs);

        long floor = DebeziumChangeEventCapture.seedVersionFloor(highWater);
        assertEquals(Math.floorDiv(highWater, 1_000_000L) + 1, floor);
        assertEquals(floor, DebeziumChangeEventCapture.sequenceMaxSourceTs);
        assertTrue("the seeded slot lies strictly above the high-water version",
                floor * 1_000_000L > highWater);

        long lower = DebeziumChangeEventCapture.seedVersionFloor(highWater - 5_000L * 1_000_000L);
        assertEquals("a lower seed (e.g. a re-setup in the same JVM) never lowers the floor",
                floor, lower);
        assertEquals(floor, DebeziumChangeEventCapture.sequenceMaxSourceTs);

        assertEquals("the anchor and counter are left to their start-of-run rules",
                0L, DebeziumChangeEventCapture.sequenceAnchorTs);
        assertEquals(null, DebeziumChangeEventCapture.sequenceHighWaterPosition);

        resetSequenceStateAsAfterRestart();
    }

    /**
     * A heartbeat carries the connector's wall clock, not a source commit time,
     * and produces no row; it must not enter the version sequence (spec 02.02
     * §3.2, 01.06 §3.1). Before the fix every control record went through
     * {@code nextSequenceNumber(envelopeTs, null)} and pinned the floor to the
     * connector clock, which is the lag-sized part of the restart window.
     */
    @Test
    @DisplayName("A heartbeat and a transaction-metadata record leave the version-sequence statics unchanged")
    public void heartbeatAndTransactionMetadataDoNotTouchTheSequenceState() throws InterruptedException {
        final long sourceClock = 1_787_635_797_000L - 40_000;
        final long connectorClock = 1_787_635_797_000L;
        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.nextSequenceNumber(sourceClock,
                SourcePosition.ofBinlog("mysql-bin.000020", 400L, 0));
        List<Object> before = sequenceState();

        runControlRecordBatch(heartbeatAt(connectorClock));
        assertEquals("a heartbeat must not raise the floor, move the anchor, touch the counter or "
                + "the high-water position", before, sequenceState());

        runControlRecordBatch(transactionMetadataAt(connectorClock));
        assertEquals("a transaction-metadata record must not touch the sequence state either",
                before, sequenceState());

        assertEquals("the floor stays at the source clock", sourceClock,
                DebeziumChangeEventCapture.sequenceMaxSourceTs);
        resetSequenceStateAsAfterRestart();
    }

    /**
     * After a restart the high-water position is empty, so the events Debezium
     * re-publishes from the committed offset are first deliveries to the new run:
     * they are clamped to the seeded floor and rank above the old run (spec
     * 02.04 §3.2). This replaces the earlier reading that post-restart
     * redeliveries keep their own (older) timestamps.
     */
    @Test
    @DisplayName("A replayed event after a seeded restart is clamped above the old run's highest version")
    public void replayAfterSeededRestartIsClampedAboveTheOldRun() {
        final long ts = 1_757_900_000_000L;
        SourcePosition p100 = SourcePosition.ofBinlog("mysql-bin.000007", 100L, 0);
        SourcePosition p200 = SourcePosition.ofBinlog("mysql-bin.000007", 200L, 0);
        SourcePosition p300 = SourcePosition.ofBinlog("mysql-bin.000007", 300L, 0);

        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.nextSequenceNumber(ts, p100);
        long oldRunHighest = DebeziumChangeEventCapture.nextSequenceNumber(ts + 3_000, p200);

        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.seedVersionFloor(oldRunHighest);
        long replayedFirst = DebeziumChangeEventCapture.nextSequenceNumber(ts, p100);
        long replayedSecond = DebeziumChangeEventCapture.nextSequenceNumber(ts + 3_000, p200);
        long genuinelyNew = DebeziumChangeEventCapture.nextSequenceNumber(ts + 3_100, p300);

        assertTrue("the replayed copy is a first delivery to the new run and ranks above the old run",
                replayedFirst > oldRunHighest);
        assertTrue("the replay stays in log order", replayedSecond > replayedFirst);
        assertTrue("a genuinely new event ranks above the replay and the old run",
                genuinelyNew > replayedSecond);
        resetSequenceStateAsAfterRestart();
    }

    @Test
    @DisplayName("Should ignore DDL statements matching regex patterns")
    public void shouldIgnoreDDLMatchingRegexPatterns() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail analyze PARTITION p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        String ddlNotToIgnore = "ALTER TABLE trade_prod.bundle_detail ADD COLUMN new_column INT";
        assertFalse(capture.checkDDLAgainstRegexPatterns(ddlNotToIgnore));
    }

    @Test
    @DisplayName("Should ignore ADD PARTITION DDL statements")
    public void shouldIgnoreAddPartitionDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail ADD PARTITION (p20230106)";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail add partition (p20230106)";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }

    @Test
    @DisplayName("Should ignore DROP PARTITION DDL statements")
    public void shouldIgnoreDropPartitionDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail DROP PARTITION p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail drop partition p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }

    /**
     * A rename must invalidate the cached writer for the OLD table name as well
     * as the new one. Debezium's tableChanges entry identifies a renamed table
     * by its NEW name only, so the old name has to be recovered from the DDL
     * text -- otherwise a writer keyed to the old name keeps inserting against
     * a table that no longer exists.
     */
    @Test
    @DisplayName("RENAME TABLE yields both the source and the destination table")
    public void shouldExtractBothSidesOfRenameTable() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<String> names = capture.getRenamedTableNames("RENAME TABLE orders TO orders_archive");

        assertTrue("source table must be invalidated", names.contains("orders"));
        assertTrue("destination table must be invalidated", names.contains("orders_archive"));
    }

    @Test
    @DisplayName("ALTER TABLE ... RENAME TO yields both the source and the destination table")
    public void shouldExtractBothSidesOfAlterTableRename() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<String> names = capture.getRenamedTableNames("ALTER TABLE orders RENAME TO orders_archive");

        assertTrue("source table must be invalidated", names.contains("orders"));
        assertTrue("destination table must be invalidated", names.contains("orders_archive"));
    }

    /**
     * Guards against a keyword suffix being mistaken for a table name: an
     * unanchored exclusion of the RENAME keyword still lets the scan resume one
     * character in and match "ENAME" as the rename source, which would
     * invalidate a table that does not exist and mask the real one.
     */
    @Test
    @DisplayName("ALTER TABLE ... RENAME TO yields exactly two tables, no keyword fragments")
    public void shouldNotExtractKeywordFragmentsFromAlterTableRename() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<String> names = capture.getRenamedTableNames("ALTER TABLE orders RENAME TO orders_archive");

        assertEquals("only the two real tables may be returned", 2, names.size());
        assertFalse("a fragment of the RENAME keyword must not be treated as a table",
                names.contains("ENAME"));
    }

    @Test
    @DisplayName("Database qualifiers and quoting are stripped from renamed table names")
    public void shouldStripQualifiersAndQuotingFromRenamedNames() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<String> names = capture.getRenamedTableNames(
                "RENAME TABLE `shop`.`orders` TO `shop`.`orders_archive`");

        assertTrue("source table must be invalidated", names.contains("orders"));
        assertTrue("destination table must be invalidated", names.contains("orders_archive"));
    }

    @Test
    @DisplayName("A multi-pair RENAME TABLE yields every table involved")
    public void shouldExtractEveryPairOfMultiRename() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<String> names = capture.getRenamedTableNames("RENAME TABLE a TO b, c TO d");

        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
        assertTrue(names.contains("c"));
        assertTrue(names.contains("d"));
    }

    @Test
    @DisplayName("A DDL with no rename yields no table names")
    public void shouldReturnNothingForNonRenameDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        assertTrue(capture.getRenamedTableNames(
                "ALTER TABLE orders ADD COLUMN total INT").isEmpty());
        assertTrue(capture.getRenamedTableNames("").isEmpty());
        assertTrue(capture.getRenamedTableNames(null).isEmpty());
    }

    /** Builds a schema-change event carrying the given DDL statement. */
    private static ChangeEvent<SourceRecord, SourceRecord> ddlEvent(String ddl) {
        return ddlEvent(ddl, "DDL");
    }

    /**
     * Builds a schema-change event whose DDL field carries the given name.
     * Debezium spells it differently across connectors, and Struct.get is
     * case-sensitive, so both spellings must resolve.
     */
    private static ChangeEvent<SourceRecord, SourceRecord> ddlEvent(String ddl, String fieldName) {
        Schema schema = SchemaBuilder.struct()
                .field(fieldName, Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        Struct value = new Struct(schema);
        if (ddl != null) {
            value.put(fieldName, ddl);
        }
        SourceRecord record = new SourceRecord(null, null, "topic", schema, value);
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return "topic";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /** Builds a row-change event with no DDL field at all. */
    private static ChangeEvent<SourceRecord, SourceRecord> rowEvent() {
        Struct value = getKafkaStruct();
        SourceRecord record = new SourceRecord(null, null, "topic", value.schema(), value);
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return "topic";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /**
     * The batch loop uses this predicate to decide when to hand pending rows
     * to the consumers before a DDL is applied. A false negative reintroduces
     * the ordering inversion; a false positive would flush on every row and
     * defeat batching, so both directions are asserted.
     */
    @Test
    @DisplayName("A DDL event is recognised so pending rows can be flushed before it")
    public void shouldRecogniseDDLRecord() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        assertTrue("uppercase DDL field must resolve",
                capture.isDDLRecord(ddlEvent("ALTER TABLE orders ADD COLUMN total INT", "DDL")));
        assertTrue("lowercase ddl field must resolve",
                capture.isDDLRecord(ddlEvent("ALTER TABLE orders ADD COLUMN total INT", "ddl")));
    }

    @Test
    @DisplayName("A row-change event is not treated as DDL")
    public void shouldNotTreatRowChangeAsDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        assertFalse("a row event must not trigger a pre-DDL flush",
                capture.isDDLRecord(rowEvent()));
    }

    @Test
    @DisplayName("An empty, absent or null DDL statement is not treated as DDL")
    public void shouldNotTreatEmptyDDLAsDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        assertFalse(capture.isDDLRecord(ddlEvent("")));
        assertFalse(capture.isDDLRecord(ddlEvent(null)));
        assertFalse(capture.isDDLRecord(null));
    }

    @Test
    @DisplayName("Should ignore REORGANIZE PARTITION DDL statements")
    public void shouldIgnoreReorganizePartitionDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail REORGANIZE PARTITION p20230106 INTO (p20230106_1, p20230106_2)";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail reorganize partition p20230106 into (p20230106_1, p20230106_2)";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }

    @Test
    @DisplayName("Should ignore REMOVE PARTITIONING DDL statements")
    public void shouldIgnoreRemovePartitioningDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail REMOVE PARTITIONING";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail remove partitioning";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }

    @Test
    @DisplayName("Should ignore TRUNCATE PARTITION DDL statements")
    public void shouldIgnoreTruncatePartitionDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail TRUNCATE PARTITION p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail truncate partition p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }

    @Test
    @DisplayName("Should ignore ANALYZE PARTITION DDL statements")
    public void shouldIgnoreAnalyzePartitionDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail ANALYZE PARTITION p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail analyze partition p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }

    @Test
    @DisplayName("Should ignore CHECK PARTITION DDL statements")
    public void shouldIgnoreCheckPartitionDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail CHECK PARTITION p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail check partition p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }

    @Test
    @DisplayName("Should ignore OPTIMIZE PARTITION DDL statements")
    public void shouldIgnoreOptimizePartitionDDL() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        String ddlToIgnore = "ALTER TABLE trade_prod.bundle_detail OPTIMIZE PARTITION p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnore));

        // Test case insensitivity
        String ddlToIgnoreCaseInsensitive = "alter table trade_prod.bundle_detail optimize partition p20230106";
        assertTrue(capture.checkDDLAgainstRegexPatterns(ddlToIgnoreCaseInsensitive));
    }
}