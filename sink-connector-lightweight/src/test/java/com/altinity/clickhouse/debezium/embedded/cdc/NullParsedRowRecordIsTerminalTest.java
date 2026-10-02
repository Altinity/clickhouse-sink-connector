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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A ROW record the parser cannot convert is terminal (spec 01.06 §3.1).
 *
 * <p>This class was {@code NullParsedRecordSkipTest}, the regression test for
 * issue #1379: {@code processEveryChangeRecord} called
 * {@code setSequenceNumber} on a null parse result, raised a
 * NullPointerException per record and swallowed it. That test asserted the
 * fix of the day -- "skipped deliberately, at WARN, without an NPE". The skip
 * itself was the next defect: the batch loop then remembered the unconverted
 * row as the batch's {@code lastControlRecord} and committed its offset like a
 * heartbeat's, so the row was lost and a restart never redelivered it.</p>
 *
 * <p>The test is therefore INVERTED, not extended: a row record (its value has
 * an {@code op} field) for which the parser returns null must raise
 * {@link RecordReplicationException} out of {@code processEveryChangeRecord}.
 * Two properties of the original are kept because they still hold: no
 * NullPointerException is raised (the failure is a deliberate, typed
 * exception), and nothing is returned downstream.</p>
 */
public class NullParsedRowRecordIsTerminalTest {

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

    /**
     * A row-change event with no DDL field, so the row branch is taken. It
     * carries an {@code op} field, as every real row-change event does: a
     * value Struct WITHOUT {@code op} is a control record (heartbeat /
     * transaction metadata) and is logged at DEBUG, not raised -- see
     * ControlRecordLogLevelTest and spec 01.06.
     */
    private static ChangeEvent<SourceRecord, SourceRecord> rowEvent() {
        Schema schema = SchemaBuilder.struct()
                .field("op", Schema.STRING_SCHEMA)
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct value = new Struct(schema);
        value.put("op", "c");
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
     * reflective; the argument list mirrors the call in handleBatch. The
     * method's own exception is unwrapped so the test sees what the batch
     * handler would see.
     */
    private static ClickHouseStruct invokeProcess(DebeziumChangeEventCapture capture,
                                                  ChangeEvent<SourceRecord, SourceRecord> record,
                                                  DebeziumRecordParserService parser)
            throws Throwable {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class,
                ChangeEvent.class,
                DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class,
                DebeziumEngine.RecordCommitter.class,
                boolean.class,
                DebeziumChangeEventCapture.VersionAssignment.class);
        m.setAccessible(true);
        // Mirrors handleBatch: the version 1000000001 = effectiveTs 1000 * 1e6 + 1.
        try {
            return (ClickHouseStruct) m.invoke(capture, new Properties(), record, parser,
                    null, null, true,
                    new DebeziumChangeEventCapture.VersionAssignment(1000000001L, 1000L));
        } catch (InvocationTargetException ite) {
            throw ite.getCause();
        }
    }

    @Test
    @DisplayName("A row record the parser cannot convert raises RecordReplicationException, never an NPE, never a skip")
    public void unconvertibleRowRecordIsTerminal() throws Exception {
        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumChangeEventCapture.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);

        try {
            // Pre-#1416 this raised an NPE that was swallowed; post-#1416 and
            // before this change it returned null with a WARN "skipping" and the
            // row's offset was then committed as a control record's. Both are
            // silent loss. The only acceptable outcome is the typed, terminal
            // exception.
            RecordReplicationException ex = assertThrows(RecordReplicationException.class,
                    () -> invokeProcess(new DebeziumChangeEventCapture(), rowEvent(),
                            new NullReturningParser()),
                    "a row record (op present) that parses to null must halt the pipeline");
            assertTrue("the message must say the row was NOT dropped silently: " + ex.getMessage(),
                    ex.getMessage().contains("stopping the pipeline"));
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        // The #1379 property is preserved: the failure is deliberate, not an
        // NPE on the null return value swallowed one line below.
        String npe = appender.events.stream()
                .filter(e -> e.getThrown() instanceof NullPointerException)
                .map(e -> String.valueOf(e.getThrown()))
                .findFirst()
                .orElse(null);
        assertNull("an unconvertible record must not raise a NullPointerException; "
                        + "issue #1379 logged one per record for the whole snapshot, got: " + npe,
                npe);

        // And the old skip is gone: nothing is logged as "skipping" because
        // nothing is skipped.
        boolean skipLogged = appender.events.stream()
                .anyMatch(e -> e.getMessage().getFormattedMessage().toLowerCase()
                        .contains("skipping"));
        assertTrue("a row record must not be logged as skipped -- it is refused, not skipped",
                !skipLogged);
    }
}
