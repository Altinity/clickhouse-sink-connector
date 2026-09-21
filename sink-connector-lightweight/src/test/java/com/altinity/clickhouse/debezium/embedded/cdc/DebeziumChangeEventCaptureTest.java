package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SourcePosition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import io.debezium.engine.ChangeEvent;
import org.apache.kafka.connect.source.SourceRecord;
import org.json.simple.parser.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

public class DebeziumChangeEventCaptureTest {

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
     * KNOWN DEFECT (spec 02.01 §4, 02.04 §4; DebeziumOffsetManagement Javadoc),
     * reproduced through the real {@code nextSequenceNumber} rather than over
     * copied constants: the ten-digit seeds carry into the six digits the
     * multiplier leaves them, so after a restart a genuinely NEWER event (1 ms
     * later, next binlog position) is versioned BELOW an older pre-restart
     * event and ReplacingMergeTree would keep the stale row.
     *
     * <p>This test asserts the inversion EXISTS today. It is named as a defect
     * so that fixing the encoding makes it fail; at that point flip the final
     * assertion to {@code newerAfterRestart > olderBeforeRestart} and rename
     * it to the guarantee. Do not "fix" the test by widening the gap between
     * the two events: the whole point is the 1 ms window the {@code diff > 1}
     * reset does not cover.</p>
     */
    @Test
    @DisplayName("KNOWN DEFECT: a newer event versioned right after a restart ranks below an older pre-restart event")
    public void knownDefectNewerEventAfterRestartRanksBelowOlderPreRestartEvent() {
        final long olderTs = 1_787_635_797_000L;
        SourcePosition olderPos = SourcePosition.ofBinlog("mysql-bin.000020", 500L, 0);
        SourcePosition newerPos = SourcePosition.ofBinlog("mysql-bin.000020", 600L, 0);

        // Run 1 (before the restart): leave the 500m start domain with a >= 2000 ms
        // advance, then version the older event in the steady-state 1000m domain.
        resetSequenceStateAsAfterRestart();
        DebeziumChangeEventCapture.nextSequenceNumber(olderTs - 10_000, SourcePosition.ofBinlog("mysql-bin.000020", 400L, 0));
        long olderBeforeRestart = DebeziumChangeEventCapture.nextSequenceNumber(olderTs, olderPos);
        assertEquals("precondition: the pre-restart write carries the steady-state seed",
                DebeziumChangeEventCapture.SEQUENCE_START, counterOf(olderBeforeRestart, olderTs));

        // Restart: every static is back to its initial value; the first record is
        // seeded at SEQUENCE_START_INITIAL. The newer event is 1 ms later and at a
        // higher binlog position, so it is unambiguously newer than the older one.
        resetSequenceStateAsAfterRestart();
        long newerAfterRestart = DebeziumChangeEventCapture.nextSequenceNumber(olderTs + 1, newerPos);
        assertEquals("precondition: the post-restart write carries the resume seed",
                DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 1, counterOf(newerAfterRestart, olderTs + 1));

        assertTrue("KNOWN DEFECT: newer(" + newerAfterRestart + ") should out-rank older("
                        + olderBeforeRestart + ") but does not. When this assertion fails the "
                        + "encoding has been fixed -- invert it into newer > older.",
                newerAfterRestart < olderBeforeRestart);

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