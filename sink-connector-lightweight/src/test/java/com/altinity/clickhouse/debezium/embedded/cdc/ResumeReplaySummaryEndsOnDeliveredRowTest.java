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
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resume replay summary is reported when the batch handler receives its
 * first ROW after the skip run -- and not before (spec 01.07 §3.5).
 *
 * <p><b>The defect.</b> The summary's only end-of-replay trigger was "the
 * next line from Debezium's streaming logger that is not a skip". In
 * production that logger is silent at INFO once the skip run ends: one
 * deployment logged {@code Resume replay in progress: 292529 ... in 120 s}
 * and then nothing for hours, so the total was never reported and would have
 * surfaced only at the engine stop, with the process lifetime as its
 * duration.</p>
 *
 * <p><b>The rule.</b> Debezium never delivers the rows it skips, so a row
 * reaching {@code handleChangeEventBatch} is past the resume point by
 * construction: the first one flushes the summary. Control records do NOT:
 * Debezium's {@code handleEvent} dispatches a heartbeat after every binlog
 * event, skipped ones included, so a heartbeat or a transaction marker can
 * arrive while the replay is still running.</p>
 *
 * <p>These tests drive the real {@code handleChangeEventBatch}; no ClickHouse,
 * MySQL or Debezium engine is required.</p>
 */
public class ResumeReplaySummaryEndsOnDeliveredRowTest {

    private static final String SKIP_INSERT = "Skipping previously processed row event: Event{header=EventHeaderV4{"
            + "timestamp=1790369503000, eventType=EXT_WRITE_ROWS, serverId=220, headerLength=19, dataLength=8124, "
            + "nextPosition=%d, flags=0}, data=WriteRowsEventData{tableId=627313, includedColumns={0, 1}, rows=[\n"
            + "    [42, secret-row-payload-%d]\n]}}";

    /** Collects what {@link ResumeReplayLogSummary} logs. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-resume-replay-row-delivered", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<String> messages() {
            List<String> out = new ArrayList<>();
            synchronized (events) {
                for (LogEvent e : events) {
                    out.add(e.getMessage().getFormattedMessage());
                }
            }
            return out;
        }
    }

    /** Records what the engine's committer was asked to do. */
    private static final class RecordingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {
        private final List<ChangeEvent<SourceRecord, SourceRecord>> processed = new ArrayList<>();

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            processed.add(record);
        }

        @Override
        public void markBatchFinished() {
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

    /**
     * A parser that returns null for the row: the handler then refuses the
     * row as terminal (spec 01.06 §3.1), which keeps the test away from the
     * writer queues. The replay-ending signal fires BEFORE parsing -- at the
     * point the record is classified as a row -- so the refusal does not
     * matter here.
     */
    private static final class NullReturningParser implements DebeziumRecordParserService {
        @Override
        public ClickHouseStruct parse(ChangeEvent<SourceRecord, SourceRecord> record,
                                      DebeziumEngine.RecordCommitter<
                                              ChangeEvent<SourceRecord, SourceRecord>> committer,
                                      boolean lastRecordInBatch) {
            return null;
        }
    }

    private CapturingAppender summaryAppender;
    private Level savedLevel;
    private ResumeReplayLogSummary filter;

    @BeforeEach
    public void attach() {
        filter = ResumeReplayLogSummary.install();
        filter.flush("test start");
        Logger logger = (Logger) LogManager.getLogger(ResumeReplayLogSummary.class);
        savedLevel = logger.getLevel();
        Configurator.setLevel(ResumeReplayLogSummary.class.getName(), Level.INFO);
        summaryAppender = new CapturingAppender();
        summaryAppender.start();
        logger.addAppender(summaryAppender);
    }

    @AfterEach
    public void detach() {
        Logger logger = (Logger) LogManager.getLogger(ResumeReplayLogSummary.class);
        logger.removeAppender(summaryAppender);
        summaryAppender.stop();
        Configurator.setLevel(ResumeReplayLogSummary.class.getName(), savedLevel);
        filter.flush("test end");
    }

    private static LogEvent debeziumSkipLine(long nextPosition) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(ResumeReplayLogSummary.DEBEZIUM_LOGGER)
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(String.format(SKIP_INSERT, nextPosition, nextPosition)))
                .build();
    }

    private void replayInProgress(int skippedEvents) {
        for (int i = 0; i < skippedEvents; i++) {
            assertEquals(Filter.Result.DENY, filter.filter(debeziumSkipLine(1000 + i)));
        }
        assertEquals(skippedEvents, filter.skipped(), "the replay is being counted");
        assertTrue(summaryAppender.messages().isEmpty(), "nothing is written while the replay runs");
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

    /** A transaction-boundary record: regular topic, a value Struct with no {@code op}. */
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
    private static ChangeEvent<SourceRecord, SourceRecord> row() {
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

    private static void runBatch(List<ChangeEvent<SourceRecord, SourceRecord>> batch,
                                 DebeziumRecordParserService parser) throws Exception {
        new DebeziumChangeEventCapture().handleChangeEventBatch(
                batch, new RecordingCommitter(), new Properties(), parser, config());
    }

    /**
     * The regression. Against the pre-fix code this fails on the first
     * assertion: the handler had no hand in the summary, so the replay stayed
     * pending after the row.
     */
    @Test
    @DisplayName("The first row through the batch handler ends the replay: one summary, counts reset, no row image")
    public void firstDeliveredRowEndsTheReplay() {
        replayInProgress(3);

        // The row is refused by the null parser AFTER it has been classified as
        // a row; the summary was flushed at the classification.
        assertThrows(RecordReplicationException.class,
                () -> runBatch(Collections.singletonList(row()), new NullReturningParser()));

        List<String> lines = summaryAppender.messages();
        assertEquals(1, lines.size(), "exactly one summary once the first row is delivered: " + lines);
        assertTrue(lines.get(0).contains("Resume replay done (" + ResumeReplayLogSummary.ROW_DELIVERED + ")"),
                lines.get(0));
        assertTrue(lines.get(0).contains("skipped 3 previously processed binlog event(s)"), lines.get(0));
        assertTrue(lines.get(0).contains("INSERT=3"), lines.get(0));
        assertFalse(lines.get(0).contains("secret-row-payload"), "no row image in the summary");
        assertEquals(0, filter.skipped(), "the counters reset with the summary");
    }

    @Test
    @DisplayName("A heartbeat and a transaction marker delivered mid-replay do not end it: Debezium dispatches them while skipping")
    public void controlRecordsDoNotEndTheReplay() throws Exception {
        replayInProgress(2);

        runBatch(Collections.singletonList(heartbeat()), new SourceRecordParserService());
        runBatch(Collections.singletonList(transactionMetadata()), new SourceRecordParserService());

        assertTrue(summaryAppender.messages().isEmpty(),
                "a control record proves nothing about the replay; no summary: " + summaryAppender.messages());
        assertEquals(2, filter.skipped(), "the replay is still being counted");
    }

    @Test
    @DisplayName("A row with no replay pending reports nothing")
    public void rowWithoutReplayIsSilent() {
        assertEquals(0, filter.skipped());
        assertThrows(RecordReplicationException.class,
                () -> runBatch(Collections.singletonList(row()), new NullReturningParser()));
        assertTrue(summaryAppender.messages().isEmpty(), "nothing to report: " + summaryAppender.messages());
    }
}
