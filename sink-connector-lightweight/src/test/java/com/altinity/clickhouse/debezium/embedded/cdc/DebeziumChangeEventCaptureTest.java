package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.json.simple.parser.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class DebeziumChangeEventCaptureTest {

    @Test
    @DisplayName("Unit test to check if the LSN record is created properly")
    public void testUpdateBingLogInformation() throws ParseException {
        String record = "{\"transaction_id\":null,\"ts_sec\":1687278006,\"file\":\"mysql-bin.000003\",\"pos\":1156385,\"gtids\":\"30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442\",\"row\":1,\"server_id\":266,\"event\":2}";

        String updatedRecord = new DebeziumOffsetStorage().updateBinLogInformation(record , "mysql-bin.001", "1222", "232232323");

        assertTrue(updatedRecord.equalsIgnoreCase("{\"transaction_id\":null,\"ts_sec\":1687278006,\"file\":\"mysql-bin.001\",\"pos\":\"1222\",\"gtids\":\"232232323\",\"row\":1,\"server_id\":266,\"event\":2}"));
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

        // Both batches go through ONE capture instance. The sequence counter is
        // per-instance (an AtomicLong, not a shared static: a plain static long
        // mutated by several worker threads is a lost-update race that can hand
        // two records the SAME _version and let the ReplacingMergeTree collapse
        // one of them away). Production creates exactly one
        // DebeziumChangeEventCapture, so successive batches must be sequenced
        // through the same instance for the counter to carry across them --
        // using a fresh instance per batch would only be testing the race this
        // fix removed.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        // Make a list of ch1, ch2, ch3 and ch4
        List<ClickHouseStruct> clickHouseStructs = Arrays.asList(ch1, ch2, ch3, ch4, ch5);
        capture.addVersion(clickHouseStructs);

        Thread.sleep(1000);
        // Add ch5 and ch6
        List<ClickHouseStruct> clickHouseStructs2 = Arrays.asList(ch5, ch6);
        capture.addVersion(clickHouseStructs2);

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
    @DisplayName("ANALYZE PARTITION is matched regardless of statement case")
    public void shouldIgnoreAnalyzePartitionCaseInsensitively() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        // MySQL echoes the statement exactly as the client typed it, so the
        // built-in pattern has to be case-insensitive to catch the lowercase
        // spelling clients actually emit. The sibling ADD/DROP PARTITION
        // patterns already carried (?i); this one did not.
        assertTrue(capture.checkDDLAgainstRegexPatterns(
                "ALTER TABLE sales analyze PARTITION p2022"));
        assertTrue(capture.checkDDLAgainstRegexPatterns(
                "alter table sales analyze partition p2022"));
        assertTrue(capture.checkDDLAgainstRegexPatterns(
                "ALTER TABLE sales ANALYZE PARTITION p2022"));
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

    /**
     * Regression: the assigned sequence number must come from the atomic
     * mutation itself, never from a follow-up get(). incrementAndGet() then
     * re-reading with get() is a TOCTOU race -- two threads can interleave
     * between the increment and the read and both observe the same highest
     * value, handing two records an identical _version and letting the
     * ReplacingMergeTree collapse one of them away. That is exactly the
     * lost-update the AtomicLong exists to prevent.
     *
     * <p>Every emitted _version across concurrent batches must be unique.</p>
     */
    @Test
    @DisplayName("Concurrent addVersion calls must never emit a duplicate _version")
    public void testAddVersionIsRaceFreeAcrossThreads() throws Exception {
        final DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        final long ts = System.currentTimeMillis();
        // Single-record batches maximise the interleave window: each
        // addVersion call is one increment immediately followed by the read,
        // which is precisely where a get()-after-increment loses the update.
        final int threads = 16;
        final int perThread = 1;
        final int rounds = 400;

        // All records share one ts_ms so every version differs only by the
        // counter -- any lost update shows up immediately as a duplicate.
        final java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.List<java.util.concurrent.Future<java.util.List<Long>>> futures =
                new java.util.ArrayList<>();
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    List<Long> seqs = new java.util.ArrayList<>();
                    start.await();
                    // Each round submits its own tiny batch, so the increment
                    // and the subsequent read are adjacent under contention.
                    for (int r = 0; r < rounds; r++) {
                        List<ClickHouseStruct> batch = new java.util.ArrayList<>();
                        for (int i = 0; i < perThread; i++) {
                            ClickHouseStruct ch = new ClickHouseStruct(10, "topic_1",
                                    getKafkaStruct(), 2, ts, null, getKafkaStruct(), null,
                                    ClickHouseConverter.CDC_OPERATION.CREATE);
                            ch.setTs_ms(ts);
                            batch.add(ch);
                        }
                        capture.addVersion(batch);
                        for (ClickHouseStruct ch : batch) {
                            seqs.add(ch.getSequenceNumber());
                        }
                    }
                    return seqs;
                }));
            }
            start.countDown();

            List<Long> all = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<java.util.List<Long>> f : futures) {
                all.addAll(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            java.util.Set<Long> unique = new java.util.HashSet<>(all);
            org.junit.Assert.assertEquals(
                    "duplicate _version emitted -- the counter was read with get() "
                            + "instead of using the value returned by incrementAndGet()",
                    all.size(), unique.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Regression: the time-gap reset is a check-and-act over TWO pieces of
     * state -- the source-timestamp anchor is read to decide whether the
     * second boundary was crossed, and only then is the counter reset. An
     * atomic counter alone cannot make that pair consistent: two threads
     * crossing the same boundary both observe the stale anchor, both reset to
     * SEQUENCE_START, and both emit an identical _version for records sharing
     * that ts_ms -- which ReplacingMergeTree silently collapses.
     */
    @Test
    @DisplayName("Concurrent time-gap resets must not collide on SEQUENCE_START")
    public void testAddVersionRaceOnTimeGap() throws Exception {
        final long oldTs = 1000000000000L;
        // A gap > 1s from the anchor forces every thread down the reset branch.
        final long newTs = oldTs + 5000;
        final int threads = 8;

        // Repeat: a check-and-act window is probabilistic, so a single pass can
        // pass by luck. Each round uses a fresh capture anchored in the past.
        for (int round = 0; round < 200; round++) {
            final DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

            final java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                List<java.util.concurrent.Future<Long>> futures =
                        new java.util.ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        // The anchor is taken from record[0] of THIS batch, so the
                        // reset branch is only reachable when a single batch spans
                        // the gap. record[1] is the one that resets the shared
                        // counter and is assigned SEQUENCE_START.
                        ClickHouseStruct first = new ClickHouseStruct(10, "topic_1",
                                getKafkaStruct(), 2, oldTs, null, getKafkaStruct(),
                                null, ClickHouseConverter.CDC_OPERATION.CREATE);
                        first.setTs_ms(oldTs);
                        ClickHouseStruct afterGap = new ClickHouseStruct(10, "topic_1",
                                getKafkaStruct(), 2, newTs, null, getKafkaStruct(),
                                null, ClickHouseConverter.CDC_OPERATION.CREATE);
                        afterGap.setTs_ms(newTs);
                        start.await();
                        capture.addVersion(Arrays.asList(first, afterGap));
                        return afterGap.getSequenceNumber();
                    }));
                }
                start.countDown();

                List<Long> seqs = new java.util.ArrayList<>();
                for (java.util.concurrent.Future<Long> f : futures) {
                    seqs.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
                }
                java.util.Set<Long> unique = new java.util.HashSet<>(seqs);
                org.junit.Assert.assertEquals(
                        "round " + round + ": threads crossing the same second "
                                + "boundary produced a duplicate _version -- the "
                                + "counter and its time anchor must be updated "
                                + "atomically together",
                        seqs.size(), unique.size());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("addVersion should handle empty list without error")
    public void testAddVersionEmptyListNoOp() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<ClickHouseStruct> emptyList = new java.util.ArrayList<>();
        // Should not throw
        capture.addVersion(emptyList);
        assertTrue(emptyList.isEmpty());
    }

    @Test
    @DisplayName("Separate instances should have independent sequence numbers")
    public void testAddVersionInstanceIsolation() {
        long currentTimestamp = System.currentTimeMillis();

        // Create structs for instance 1
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch1.setTs_ms(currentTimestamp);

        ClickHouseStruct ch2 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                currentTimestamp + 100, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch2.setTs_ms(currentTimestamp);

        // Create structs for instance 2
        ClickHouseStruct ch3 = new ClickHouseStruct(10, "topic_2", getKafkaStruct(), 2,
                currentTimestamp, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch3.setTs_ms(currentTimestamp);

        ClickHouseStruct ch4 = new ClickHouseStruct(10, "topic_2", getKafkaStruct(), 2,
                currentTimestamp + 100, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch4.setTs_ms(currentTimestamp);

        // Process with separate instances
        DebeziumChangeEventCapture instance1 = new DebeziumChangeEventCapture();
        DebeziumChangeEventCapture instance2 = new DebeziumChangeEventCapture();

        List<ClickHouseStruct> batch1 = Arrays.asList(ch1, ch2);
        List<ClickHouseStruct> batch2 = Arrays.asList(ch3, ch4);

        instance1.addVersion(batch1);
        instance2.addVersion(batch2);

        // Both instances should produce the same sequence pattern independently
        // (since they start from the same SEQUENCE_START)
        long seq1_first = batch1.get(0).getSequenceNumber();
        long seq2_first = batch2.get(0).getSequenceNumber();
        long seq1_second = batch1.get(1).getSequenceNumber();
        long seq2_second = batch2.get(1).getSequenceNumber();

        // Within each batch, sequences should be strictly increasing
        assertTrue(seq1_first < seq1_second);
        assertTrue(seq2_first < seq2_second);

        // The relative offsets should be the same for both instances
        // (same timestamp pattern -> same sequence increment)
        long delta1 = seq1_second - seq1_first;
        long delta2 = seq2_second - seq2_first;
        assertTrue(delta1 == delta2);
    }

    @Test
    @DisplayName("addVersion should reset sequence on large timestamp gap")
    public void testAddVersionSequenceResetOnLargeTimeGap() {
        long baseTimestamp = 1700000000000L; // fixed timestamp

        ClickHouseStruct ch1 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                baseTimestamp, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch1.setTs_ms(baseTimestamp);

        // Same second — should increment
        ClickHouseStruct ch2 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                baseTimestamp + 500, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch2.setTs_ms(baseTimestamp + 500);

        // 2 seconds later — should reset
        ClickHouseStruct ch3 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                baseTimestamp + 2000, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch3.setTs_ms(baseTimestamp + 2000);

        // Same second as ch3 — should increment from reset
        ClickHouseStruct ch4 = new ClickHouseStruct(10, "topic_1", getKafkaStruct(), 2,
                baseTimestamp + 2100, null,
                getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch4.setTs_ms(baseTimestamp + 2100);

        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<ClickHouseStruct> batch = Arrays.asList(ch1, ch2, ch3, ch4);
        capture.addVersion(batch);

        // ch1 and ch2 should be in the same sequence window (increasing)
        assertTrue(ch1.getSequenceNumber() < ch2.getSequenceNumber());
        // ch3 should have reset — its sequence should be based on the new timestamp
        // After reset, sequenceNumber goes back to SEQUENCE_START, so the
        // ch3 sequence should be baseTimestamp+2000 * 1000000 + SEQUENCE_START
        assertTrue(ch3.getSequenceNumber() > ch2.getSequenceNumber());
        // ch4 should increment from ch3
        assertTrue(ch3.getSequenceNumber() < ch4.getSequenceNumber());
    }

}