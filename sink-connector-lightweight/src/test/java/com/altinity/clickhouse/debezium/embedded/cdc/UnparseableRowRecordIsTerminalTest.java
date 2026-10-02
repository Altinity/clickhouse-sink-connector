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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ROW record the parser cannot convert is terminal, never an acknowledged
 * heartbeat (spec 01.06 §3.1, spec 10.04 §3.3).
 *
 * <p><b>The defect.</b> {@code handleChangeEventBatch} remembered EVERY
 * non-DDL record that produced no struct as the batch's
 * {@code lastControlRecord}, and {@code commitControlRecordOffset} then
 * committed its offset once the pipeline was quiescent. That is right for a
 * heartbeat or a transaction marker, which carry no row by contract. It is
 * data loss for a row record (its value has an {@code op} field) whose parse
 * returned {@code null} or threw: the row never reached ClickHouse, yet the
 * durable source position moved past it, so a restart never redelivered it.
 * The only trace was a WARN.</p>
 *
 * <p><b>The rule.</b> A null struct may become {@code lastControlRecord} only
 * if {@code isControlRecord(sourceRecord)}. A row record yielding null or
 * throwing raises {@link RecordReplicationException}, re-thrown ahead of the
 * catch-all in {@code processEveryChangeRecord} exactly like
 * {@link DDLReplicationException}, so it leaves {@code handleBatch} and halts
 * the engine with the offset still behind the record.</p>
 *
 * <p>These tests drive the real {@code handleChangeEventBatch}; no ClickHouse,
 * MySQL or Debezium engine is required.</p>
 */
public class UnparseableRowRecordIsTerminalTest {

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
            return (key, value) -> { };
        }
    }

    /** The parser path that returns null for a row it cannot convert. */
    private static final class NullReturningParser implements DebeziumRecordParserService {
        @Override
        public ClickHouseStruct parse(ChangeEvent<SourceRecord, SourceRecord> record,
                                      DebeziumEngine.RecordCommitter<
                                              ChangeEvent<SourceRecord, SourceRecord>> committer,
                                      boolean lastRecordInBatch) {
            return null;
        }
    }

    /** The parser path that throws (e.g. a JSON payload it cannot parse). */
    private static final class ThrowingParser implements DebeziumRecordParserService {
        final RuntimeException failure = new IllegalArgumentException("cannot convert column x");

        @Override
        public ClickHouseStruct parse(ChangeEvent<SourceRecord, SourceRecord> record,
                                      DebeziumEngine.RecordCommitter<
                                              ChangeEvent<SourceRecord, SourceRecord>> committer,
                                      boolean lastRecordInBatch) {
            throw failure;
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
        offset.put("file", "binlog.000042");
        offset.put("pos", 10240L);
        return offset;
    }

    /** A row-change event: the value HAS an {@code op} field. */
    private static ChangeEvent<SourceRecord, SourceRecord> rowEvent(String op) {
        Schema valueSchema = SchemaBuilder.struct()
                .field("op", Schema.STRING_SCHEMA)
                .field("ts_ms", Schema.INT64_SCHEMA)
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("op", op);
        value.put("ts_ms", 1788182208680L);
        value.put("id", 1);
        return changeEvent(new SourceRecord(partition(), offset(),
                "db1.shop.orders", 0, null, null, valueSchema, value));
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
                "__debezium-heartbeat.db1", 0, null, null, valueSchema, value));
    }

    /**
     * A Debezium tombstone: the record that follows a DELETE when
     * {@code tombstones.on.delete=true} (the MySQL connector default). It has
     * a key and a NULL value, and carries no row by contract.
     */
    private static ChangeEvent<SourceRecord, SourceRecord> tombstone() {
        Schema keySchema = SchemaBuilder.struct().field("id", Schema.INT32_SCHEMA).build();
        Struct key = new Struct(keySchema);
        key.put("id", 1);
        return changeEvent(new SourceRecord(partition(), offset(),
                "db1.shop.orders", 0, keySchema, key, null, null));
    }

    /** A record whose value is not a Struct at all: not a row, not a control record. */
    private static ChangeEvent<SourceRecord, SourceRecord> nonStructValue() {
        return changeEvent(new SourceRecord(partition(), offset(),
                "db1.shop.orders", 0, null, null, Schema.STRING_SCHEMA, "garbage"));
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static RecordingCommitter run(List<ChangeEvent<SourceRecord, SourceRecord>> batch,
                                          DebeziumRecordParserService parser) throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        new DebeziumChangeEventCapture().handleChangeEventBatch(
                batch, committer, new Properties(), parser, config());
        return committer;
    }

    @Test
    @DisplayName("op='c' whose parser throws: RecordReplicationException propagates, nothing is acknowledged")
    public void insertWhoseParserThrowsIsTerminal() {
        RecordingCommitter committer = new RecordingCommitter();
        ThrowingParser parser = new ThrowingParser();

        RecordReplicationException ex = assertThrows(RecordReplicationException.class,
                () -> new DebeziumChangeEventCapture().handleChangeEventBatch(
                        Collections.singletonList(rowEvent("c")), committer, new Properties(),
                        parser, config()),
                "a row record whose parse throws must halt the engine, not be acknowledged as a "
                        + "heartbeat (pre-fix: swallowed by the catch-all, offset committed)");

        assertSame(parser.failure, ex.getCause(),
                "the parser's exception must be carried as the cause so the log names the defect");
        assertTrue(committer.processed.isEmpty(),
                "no offset may be staged for a row that never reached ClickHouse");
        assertEquals(0, committer.batchesFinished, "no batch may be finished");
    }

    @Test
    @DisplayName("op='u' whose parser returns null: RecordReplicationException propagates, nothing is acknowledged")
    public void updateWhoseParserReturnsNullIsTerminal() {
        RecordingCommitter committer = new RecordingCommitter();

        RecordReplicationException ex = assertThrows(RecordReplicationException.class,
                () -> new DebeziumChangeEventCapture().handleChangeEventBatch(
                        Collections.singletonList(rowEvent("u")), committer, new Properties(),
                        new NullReturningParser(), config()),
                "a row record (op present) that parses to null must halt the engine "
                        + "(pre-fix: WARN 'skipping', then committed as lastControlRecord)");

        assertTrue(ex.getMessage().contains("db1.shop.orders"),
                "the failure must name the topic of the row it refused to drop: " + ex.getMessage());
        assertTrue(committer.processed.isEmpty(),
                "no offset may be staged for a row that never reached ClickHouse");
        assertEquals(0, committer.batchesFinished, "no batch may be finished");
    }

    @Test
    @DisplayName("A record whose value is not a Struct is terminal too (it is neither a row nor a control record)")
    public void nonStructValueIsTerminal() {
        RecordingCommitter committer = new RecordingCommitter();

        assertThrows(RecordReplicationException.class,
                () -> new DebeziumChangeEventCapture().handleChangeEventBatch(
                        Collections.singletonList(nonStructValue()), committer, new Properties(),
                        new SourceRecordParserService(), config()));

        assertTrue(committer.processed.isEmpty());
        assertEquals(0, committer.batchesFinished);
    }

    @Test
    @DisplayName("A batch with a good heartbeat and then an unparseable row halts before committing the heartbeat")
    public void heartbeatBeforeBadRowIsNotCommittedEither() {
        RecordingCommitter committer = new RecordingCommitter();
        List<ChangeEvent<SourceRecord, SourceRecord>> batch = new ArrayList<>();
        batch.add(heartbeat());
        batch.add(rowEvent("c"));

        assertThrows(RecordReplicationException.class,
                () -> new DebeziumChangeEventCapture().handleChangeEventBatch(
                        batch, committer, new Properties(), new NullReturningParser(), config()));

        // The heartbeat's offset is at or after the row's; committing it would
        // move the durable position past the row that was just refused.
        assertTrue(committer.processed.isEmpty(),
                "the heartbeat that precedes the refused row must not be committed either");
        assertEquals(0, committer.batchesFinished);
    }

    @Test
    @DisplayName("Control records are unaffected: a heartbeat-only batch still commits its offset")
    public void heartbeatStillCommits() throws Exception {
        RecordingCommitter committer = run(Collections.singletonList(heartbeat()),
                new SourceRecordParserService());

        assertEquals(1, committer.processed.size(), "the heartbeat's offset must still be staged");
        assertEquals(1, committer.batchesFinished);
    }

    @Test
    @DisplayName("A Debezium tombstone (null value) is a control record: not a drop, offset committed when quiescent")
    public void tombstoneIsAControlRecord() throws Exception {
        assertTrue(DebeziumChangeEventCapture.isControlRecord(tombstone().value()),
                "a null-valued record is a Debezium tombstone and carries no row by contract");

        RecordingCommitter committer = run(Collections.singletonList(tombstone()),
                new SourceRecordParserService());

        assertEquals(1, committer.processed.size(),
                "the tombstone's offset must be staged like any other control record");
        assertEquals(1, committer.batchesFinished);
        assertNotNull(committer.processed.get(0));
    }

    @Test
    @DisplayName("The classifier: op present -> row; no op -> control; null value -> control; non-Struct -> row")
    public void classifier() {
        assertFalse(DebeziumChangeEventCapture.isControlRecord(rowEvent("c").value()));
        assertFalse(DebeziumChangeEventCapture.isControlRecord(nonStructValue().value()));
        assertTrue(DebeziumChangeEventCapture.isControlRecord(heartbeat().value()));
        assertTrue(DebeziumChangeEventCapture.isControlRecord(tombstone().value()));
        assertFalse(DebeziumChangeEventCapture.isControlRecord(null));
    }
}
