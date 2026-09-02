package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
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
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for issue #1379 ("Initial Snapshot never finishes").
 * <p>
 * {@link com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService#parse}
 * returns null for a record it cannot convert: a null source value, a value
 * that is not a Struct, or a value the converter cannot map. Those null
 * returns are contract, not failure.
 * </p>
 * <p>
 * processEveryChangeRecord called setSequenceNumber on that return value
 * before testing it for null, so every such record raised a
 * NullPointerException. The NPE was then swallowed by the catch-all in the
 * same method, so the record was dropped silently while the snapshot loop
 * logged the same stack trace over and over -- the symptom reported in #1379.
 * </p>
 * <p>
 * An unconvertible record must be skipped deliberately and visibly: no
 * NullPointerException, and a warning that says the record was skipped.
 * </p>
 */
public class NullParsedRecordSkipTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-1379", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * Stands in for the real parser on the path that returns null, so the
     * test exercises the caller rather than the converter.
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

    /** A row-change event with no DDL field, so the row branch is taken. */
    private static ChangeEvent<SourceRecord, SourceRecord> rowEvent() {
        Schema schema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct value = new Struct(schema);
        value.put("id", 1);
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

    /**
     * processEveryChangeRecord is private and its only caller is the Debezium
     * batch handler, which needs a live engine. The seam is therefore
     * reflective; the argument list mirrors the call in handleBatch.
     */
    private static ClickHouseStruct invokeProcess(DebeziumChangeEventCapture capture,
                                                  ChangeEvent<SourceRecord, SourceRecord> record,
                                                  DebeziumRecordParserService parser)
            throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class,
                ChangeEvent.class,
                DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class,
                DebeziumEngine.RecordCommitter.class,
                boolean.class,
                long.class);
        m.setAccessible(true);
        return (ClickHouseStruct) m.invoke(capture, new Properties(), record, parser,
                null, null, true, 1000000001L);
    }

    @Test
    @DisplayName("A record the parser cannot convert is skipped without a NullPointerException")
    public void shouldSkipUnparseableRecordWithoutNPE() throws Exception {
        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumChangeEventCapture.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);

        ClickHouseStruct result;
        try {
            result = invokeProcess(new DebeziumChangeEventCapture(), rowEvent(),
                    new NullReturningParser());
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        // Nothing was converted, so there is nothing to hand downstream.
        assertNull("an unconvertible record must not produce a struct", result);

        // Before the fix, setSequenceNumber ran on the null return value and
        // the resulting NPE was swallowed by the catch-all one line below.
        // The throwable is quoted back so a failure names the defect instead
        // of merely asserting that one exists.
        String npe = appender.events.stream()
                .filter(e -> e.getThrown() instanceof NullPointerException)
                .map(e -> String.valueOf(e.getThrown()))
                .findFirst()
                .orElse(null);
        assertNull("an unconvertible record must not raise a NullPointerException; "
                        + "issue #1379 logged one per record for the whole snapshot, got: " + npe,
                npe);

        // Dropping a record must be visible, not silent.
        boolean skipWarned = appender.events.stream()
                .anyMatch(e -> e.getLevel().isMoreSpecificThan(Level.WARN)
                        && e.getMessage().getFormattedMessage().toLowerCase()
                        .contains("skipping"));
        assertTrue("skipping a record must be logged at WARN or above", skipWarned);
    }
}
