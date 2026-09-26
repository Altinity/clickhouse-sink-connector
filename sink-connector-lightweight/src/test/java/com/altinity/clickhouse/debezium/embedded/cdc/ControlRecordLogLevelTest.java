package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control records (heartbeats, transaction metadata) produce no ClickHouse row
 * by contract. They must be logged at DEBUG, not WARN, and their offset must
 * still be committed.
 *
 * <p><b>The defect.</b> {@code SourceRecordParserService.parse} returns null for
 * any value Struct without an {@code op} field; that is the documented shape of
 * a Debezium heartbeat ({@code __debezium-heartbeat.<server>}, value
 * {@code {ts_ms}}) and of a transaction-boundary record. The null branch of
 * {@code processEveryChangeRecord} logged every one of them at WARN as
 * "Record could not be parsed to a ClickHouseStruct - skipping". With
 * {@code heartbeat.interval.ms} in the seconds, that is a WARN every few
 * seconds for the life of the process — noise that buries the warnings that
 * matter (spec 01.06 §3.1).</p>
 *
 * <p><b>The rule.</b> A null parse result is classified first: a heartbeat
 * topic, or a value Struct with no {@code op} field, is a control record and is
 * logged at DEBUG with a distinct message. A record that HAS an {@code op}
 * field — a real row — and still could not be parsed is neither logged nor
 * skipped: it is terminal ({@link RecordReplicationException}, spec 01.06
 * §3.1), because skipping it let its offset be committed as a control
 * record's. The control-record offset handling is untouched: it is still
 * acknowledged via {@code commitControlRecordOffset} when the pipeline is
 * quiescent (#1379).</p>
 *
 * <p>These tests drive the real {@code handleChangeEventBatch} with the real
 * {@link SourceRecordParserService}, so they exercise the production path
 * rather than a restatement of it. No ClickHouse, MySQL or Debezium engine is
 * required.</p>
 */
public class ControlRecordLogLevelTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-control-record-log-level", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /** Records what the engine's committer was asked to do. */
    private static final class RecordingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        private final List<ChangeEvent<SourceRecord, SourceRecord>> processed = new ArrayList<>();
        private int batchesFinished = 0;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            processed.add(record);
        }

        @Override
        public void markBatchFinished() {
            batchesFinished++;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            processed.add(record);
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

    /** A parser standing in for the path that returns null for a real row. */
    private static final class NullReturningParser implements DebeziumRecordParserService {

        @Override
        public ClickHouseStruct parse(ChangeEvent<SourceRecord, SourceRecord> record,
                                      DebeziumEngine.RecordCommitter<
                                              ChangeEvent<SourceRecord, SourceRecord>> committer,
                                      boolean lastRecordInBatch) {
            return null;
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
        partition.put("server", "sink-connector-manager");
        return partition;
    }

    private static Map<String, Object> offset() {
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("file", "binlog.000042");
        offset.put("pos", 10240L);
        return offset;
    }

    /** A heartbeat exactly as Debezium emits one: {@code ts_ms} only, no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> heartbeat() {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ts_ms", 1788182208680L);
        return changeEvent(new SourceRecord(partition(), offset(),
                "__debezium-heartbeat.sink-connector-manager", 0,
                null, null, valueSchema, value));
    }

    /**
     * A transaction-boundary record: a regular (non-heartbeat) topic, a value
     * Struct with no {@code op}. Like every Debezium envelope it carries a
     * {@code ts_ms}, which the batch handler reads for version anchoring.
     */
    private static ChangeEvent<SourceRecord, SourceRecord> transactionMetadata() {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.TransactionMetadataValue")
                .field("status", Schema.STRING_SCHEMA)
                .field("id", Schema.STRING_SCHEMA)
                .field("event_count", Schema.OPTIONAL_INT64_SCHEMA)
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("status", "END");
        value.put("id", "file=binlog.000042,pos=10240");
        value.put("event_count", 3L);
        value.put("ts_ms", 1788182208680L);
        return changeEvent(new SourceRecord(partition(), offset(),
                "sink-connector-manager.transaction", 0,
                null, null, valueSchema, value));
    }

    /** A row-change event: it HAS an {@code op} field, so it is not a control record. */
    private static ChangeEvent<SourceRecord, SourceRecord> rowEventWithOp() {
        Schema valueSchema = SchemaBuilder.struct()
                .field("op", Schema.STRING_SCHEMA)
                .field("ts_ms", Schema.INT64_SCHEMA)
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("op", "c");
        value.put("ts_ms", 1788182208680L);
        value.put("id", 1);
        return changeEvent(new SourceRecord(partition(), offset(),
                "sink-connector-manager.shop.orders", 0,
                null, null, valueSchema, value));
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    /**
     * Drives the real batch handler with the capture's logger opened up to
     * DEBUG, and returns everything it logged. The level is restored afterwards.
     */
    private static List<LogEvent> runBatchAndCaptureLog(List<ChangeEvent<SourceRecord, SourceRecord>> batch,
                                                        RecordingCommitter committer,
                                                        DebeziumRecordParserService parser)
            throws Exception {
        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumChangeEventCapture.class);
        Level savedLevel = coreLogger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        Configurator.setLevel(coreLogger.getName(), Level.DEBUG);
        try {
            new DebeziumChangeEventCapture().handleChangeEventBatch(
                    batch, committer, new Properties(), parser, config());
        } finally {
            Configurator.setLevel(coreLogger.getName(), savedLevel);
            coreLogger.removeAppender(appender);
            appender.stop();
        }
        return new ArrayList<>(appender.events);
    }

    private static List<String> warningsAndAbove(List<LogEvent> events) {
        return events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(Level.WARN))
                .map(e -> e.getLevel() + ": " + e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
    }

    private static boolean hasControlRecordDebugLine(List<LogEvent> events) {
        return events.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                && e.getMessage().getFormattedMessage().toLowerCase().contains("control record"));
    }

    /**
     * The regression: a heartbeat must not be a WARN, and it must still be
     * acknowledged.
     *
     * <p>Against the pre-fix code this fails on the first assertion: every
     * heartbeat logged "Record could not be parsed to a ClickHouseStruct -
     * skipping" at WARN.</p>
     */
    @Test
    @DisplayName("A heartbeat is logged at DEBUG as a control record, never at WARN, and its offset is still committed")
    public void heartbeatIsLoggedAtDebugAndStillCommitsItsOffset() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();

        List<LogEvent> events = runBatchAndCaptureLog(
                Collections.singletonList(heartbeat()), committer, new SourceRecordParserService());

        List<String> loud = warningsAndAbove(events);
        assertTrue(loud.isEmpty(),
                "a heartbeat produces no row by contract and must not be logged at WARN or above; "
                        + "one WARN per heartbeat interval buries real warnings. Got: " + loud);
        assertTrue(hasControlRecordDebugLine(events),
                "the heartbeat must be logged at DEBUG with a message naming it as a control record");

        // The offset handling of #1379 is untouched by the log-level change.
        assertEquals(1, committer.processed.size(),
                "the heartbeat's offset must still be staged with the engine");
        assertEquals(1, committer.batchesFinished,
                "the batch must still be finished so the engine flushes the offset");
    }

    @Test
    @DisplayName("A transaction-boundary record (no op, regular topic) is logged at DEBUG, never at WARN")
    public void transactionMetadataIsLoggedAtDebug() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();

        List<LogEvent> events = runBatchAndCaptureLog(
                Collections.singletonList(transactionMetadata()), committer, new SourceRecordParserService());

        List<String> loud = warningsAndAbove(events);
        assertTrue(loud.isEmpty(),
                "a transaction-metadata record has no op field and no row to write; it must not be "
                        + "logged at WARN or above. Got: " + loud);
        assertTrue(hasControlRecordDebugLine(events),
                "the transaction-metadata record must be logged at DEBUG as a control record");
        assertEquals(1, committer.processed.size(),
                "the control record's offset must still be staged with the engine");
    }

    /**
     * The guard, INVERTED from its first version. A record that carries an
     * {@code op} field is a real row; if it cannot be parsed it is not "logged
     * at WARN and skipped" -- that skip let the batch loop commit the row's
     * offset as a control record's and lose the row (spec 01.06 §3.1). It is
     * terminal: {@link RecordReplicationException} leaves the batch handler
     * and nothing is acknowledged.
     */
    @Test
    @DisplayName("A row record (op present) that cannot be parsed is terminal, not a WARN skip")
    public void unparseableRowRecordIsTerminal() {
        RecordingCommitter committer = new RecordingCommitter();

        assertThrows(RecordReplicationException.class,
                () -> runBatchAndCaptureLog(
                        Collections.singletonList(rowEventWithOp()), committer, new NullReturningParser()),
                "a row record that parses to null must halt the pipeline; the earlier WARN-and-skip "
                        + "committed the row's offset and lost the row");
        assertTrue(committer.processed.isEmpty(),
                "no offset may be staged for a row that was never written");
        assertEquals(0, committer.batchesFinished);
    }
}
