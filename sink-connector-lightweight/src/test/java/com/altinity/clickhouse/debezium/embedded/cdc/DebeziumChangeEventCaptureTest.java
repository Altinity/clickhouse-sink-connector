package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import io.debezium.engine.ChangeEvent;
import org.apache.kafka.connect.source.SourceRecord;
import org.json.simple.parser.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

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


    // ------------------------------------------------------------------
    // Issue #1287: disable.drop.truncate never suppressed anything.
    //
    // performDDLOperation creates a fresh AtomicBoolean (line 761), hands it
    // to checkIfDDLNeedsToBeIgnored (line 763), and only afterwards calls
    // parseSql (line 769) -- and parseSql is the flag's ONLY writer
    // (MySQLDDLParserService line 152). So the guard's
    // "isDropOrTruncate.get() == true" clause read false on every single
    // evaluation and the setting could never suppress a DROP or a TRUNCATE.
    //
    // These tests drive the guard exactly as production does: with a flag
    // that nothing has written yet.
    // ------------------------------------------------------------------

    /**
     * A non-snapshot SourceRecord, so the guard's snapshot branch is not what
     * decides the outcome.
     */
    private static SourceRecord ddlSourceRecord() {
        Map<String, Object> offset = new HashMap<>();
        offset.put("file", "mysql-bin.000003");
        offset.put("pos", 1156385L);
        return new SourceRecord(new HashMap<String, Object>(), offset, "SERVER5432",
                Schema.STRING_SCHEMA, "ddl");
    }

    /** Properties as the connector holds them; null means the user left it unset. */
    private static Properties props(String disableDropTruncate) {
        Properties props = new Properties();
        if (disableDropTruncate != null) {
            props.setProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE,
                    disableDropTruncate);
        }
        return props;
    }

    private static boolean ignored(String ddl, String disableDropTruncate) {
        return new DebeziumChangeEventCapture().checkIfDDLNeedsToBeIgnored(
                ddl, props(disableDropTruncate), ddlSourceRecord(),
                // Exactly what performDDLOperation passes: a flag whose only
                // writer has not run yet.
                new AtomicBoolean(false));
    }

    @Test
    @DisplayName("#1287: disable.drop.truncate=true must suppress DROP TABLE")
    public void disableDropTruncateSuppressesDropTable() {
        assertTrue("disable.drop.truncate is enabled, so this DROP TABLE must not "
                        + "reach ClickHouse; the guard read an AtomicBoolean whose only "
                        + "writer (parseSql) runs after the guard, so it was always false",
                ignored("DROP TABLE employees.contacts", "true"));
    }

    @Test
    @DisplayName("#1287: disable.drop.truncate=true must suppress TRUNCATE TABLE")
    public void disableDropTruncateSuppressesTruncateTable() {
        assertTrue("disable.drop.truncate is enabled, so this TRUNCATE must not "
                        + "reach ClickHouse",
                ignored("TRUNCATE TABLE employees.contacts", "true"));
    }

    @Test
    @DisplayName("#1287: the setting is case-insensitive and covers DROP DATABASE")
    public void disableDropTruncateSuppressesDropDatabase() {
        assertTrue("a DROP DATABASE is the most destructive statement the setting "
                        + "claims to cover",
                ignored("drop database employees", "TRUE"));
    }

    // ------------------------------------------------------------------
    // CONTROLS. The default is unchanged: with the setting off, DROP and
    // TRUNCATE pass through exactly as every existing deployment relies on.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CONTROL: with the setting unset, DROP TABLE still passes through")
    public void defaultLetsDropThrough() {
        assertFalse("the default must not change: an unset disable.drop.truncate "
                        + "leaves DROP TABLE replicating",
                ignored("DROP TABLE employees.contacts", null));
    }

    @Test
    @DisplayName("CONTROL: with the setting unset, TRUNCATE still passes through")
    public void defaultLetsTruncateThrough() {
        assertFalse("the default must not change: an unset disable.drop.truncate "
                        + "leaves TRUNCATE replicating",
                ignored("TRUNCATE TABLE employees.contacts", null));
    }

    @Test
    @DisplayName("CONTROL: disable.drop.truncate=false still lets DROP through")
    public void explicitFalseLetsDropThrough() {
        assertFalse("an explicit false must behave exactly like the default",
                ignored("DROP TABLE employees.contacts", "false"));
    }

    @Test
    @DisplayName("CONTROL: disable.drop.truncate=true does not suppress ordinary DDL")
    public void enabledDoesNotSuppressNonDestructiveDDL() {
        assertFalse("the setting must only stop DROP and TRUNCATE; an ADD COLUMN "
                        + "has to keep replicating",
                ignored("ALTER TABLE employees.contacts ADD COLUMN nickname VARCHAR(50)",
                        "true"));
        assertFalse("a CREATE TABLE has to keep replicating",
                ignored("CREATE TABLE employees.contacts (id INT PRIMARY KEY)", "true"));
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