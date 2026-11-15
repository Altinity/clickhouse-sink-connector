package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in, intentionally RED reproductions for the ordering audit. Select with
 * -Dtest=SourcePositionTest,OrderingReproductionScenarios. The non-default class
 * name keeps known failures out of normal discovery, not out of this suite:
 * existing disabled methods are invoked directly and their assertions propagate.
 * No expected-failure wrapper converts a defect into a passing test.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(20)
public class OrderingReproductionScenarios {
    private static final long TS = 1_757_900_000_000L;
    private static final Schema KEY = SchemaBuilder.struct().field("id", Schema.INT32_SCHEMA).build();
    private static final Schema ROW = SchemaBuilder.struct().optional()
            .field("id", Schema.INT32_SCHEMA).field("value", Schema.STRING_SCHEMA).build();
    private static final Schema SOURCE = SchemaBuilder.struct()
            .field("ts_ms", Schema.INT64_SCHEMA).field("gtid", Schema.OPTIONAL_STRING_SCHEMA)
            .field("file", Schema.STRING_SCHEMA).field("pos", Schema.INT64_SCHEMA)
            .field("row", Schema.INT32_SCHEMA).field("db", Schema.STRING_SCHEMA).build();
    private static final Schema ENVELOPE = SchemaBuilder.struct()
            .field("source", SOURCE).field("before", ROW).field("after", ROW)
            .field("op", Schema.STRING_SCHEMA).field("ts_ms", Schema.INT64_SCHEMA).build();

    @BeforeEach
    public void resetSequence() {
        new GtidVersionTieBreakTest().resetSequenceState();
    }

    @Test
    public void gtidTieFieldWrap() {
        new GtidVersionTieBreakTest().gtidWrapInsideAClampedMillisecondKeepsCommitOrder();
    }

    @Test
    public void differentUuidTransactionCounters() {
        new GtidVersionTieBreakTest().newServerUuidInsideAClampedMillisecondKeepsCommitOrder();
    }

    @Test
    public void mixedGtidAndAnonymousDomains() {
        new GtidVersionTieBreakTest().laterRowWithoutGtidRanksAboveEarlierGtidRow();
    }

    @Test
    public void inProcessRestartAfterReadingUnwrittenRows() {
        new InRunRedeliveryVersionTest()
                .redeliveredLateCommitPairKeepsCommitOrderAfterAnInProcessEngineRestart();
    }

    @Test
    public void sameBasenameFailoverToLowerFileNumber() {
        new InRunRedeliveryVersionTest().failoverToALowerNumberedLogOfTheSameNameIsAFirstDelivery();
    }

    @Test
    public void durableFloorAfterBackwardClockStep() throws Exception {
        new VersionHighWaterMarkClockStepTest().futureMarkBeyondThePlausibilityWindowDoesNotLowerTheFloor();
    }

    @Test
    public void kafkaConnectLateCommitStatementTime() {
        ClickHouseStruct earlier = kafkaRow(TS + 1_000, "00000000-0000-0000-0000-000000000001:500", 100, "old");
        ClickHouseStruct later = kafkaRow(TS, "00000000-0000-0000-0000-000000000001:501", 200, "new");
        assertTrue(later.getVersion() > earlier.getVersion(),
                "Kafka Connect later commit for key id=1 has version " + later.getVersion()
                        + " below earlier commit " + earlier.getVersion()
                        + "; the converter did not receive the lightweight commit-order floor");
    }

    @Test
    public void kafkaConnectAnonymousOffsetWrap() {
        ClickHouseStruct earlier = kafkaRow(TS, null, (1L << 22) - 1, "old");
        ClickHouseStruct later = kafkaRow(TS, null, 1L << 22, "new");
        assertTrue(later.getVersion() > earlier.getVersion(),
                "Kafka Connect offset 4194304 has version " + later.getVersion()
                        + " below offset 4194303 version " + earlier.getVersion());
    }

    @Test
    public void controlLightweightLateCommitKeepsOrder() {
        new GtidLateCommitVersionTest().lateCommittingTransactionOutranksEarlierCommitUnderGtid();
    }

    @Test
    public void controlDurableFloorWithinFutureWindow() throws Exception {
        new VersionHighWaterMarkClockStepTest().markWithinTheFutureWindowIsStillTheSeed();
    }

    private static ClickHouseStruct kafkaRow(long statementTs, String gtid, long offset, String value) {
        Struct source = new Struct(SOURCE).put("ts_ms", statementTs).put("gtid", gtid)
                .put("file", "mysql-bin.000001").put("pos", offset).put("row", 0).put("db", "example");
        Struct row = new Struct(ROW).put("id", 1).put("value", value);
        Struct envelope = new Struct(ENVELOPE).put("source", source).put("before", null)
                .put("after", row).put("op", "u").put("ts_ms", TS + 10_000);
        SinkRecord input = new SinkRecord("example.rows", 0, KEY, new Struct(KEY).put("id", 1),
                ENVELOPE, envelope, offset, TS + 10_000, TimestampType.CREATE_TIME);
        ClickHouseStruct converted = new ClickHouseConverter().convert(input);
        assertNotNull(converted, "the real Kafka converter must produce a row");
        assertEquals(statementTs, converted.getTs_ms(), "source timestamp must reach the version calculation");
        assertEquals(offset, converted.getKafkaOffset(), "source Kafka offset must survive conversion");
        ClickHouseStruct unassigned = new ClickHouseStruct();
        assertEquals(gtid == null ? unassigned.getGtid() : Long.parseLong(gtid.substring(gtid.lastIndexOf(':') + 1)),
                converted.getGtid(), "GTID presence must survive conversion");
        assertEquals(unassigned.getSequenceNumber(), converted.getSequenceNumber(),
                "exercise Kafka Connect, not a manually assigned lightweight sequence");
        converted.calculateVersion(true);
        return converted;
    }
}
