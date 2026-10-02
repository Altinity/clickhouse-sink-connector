package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A DDL whose ignore-rule evaluation throws must halt the pipeline, not vanish
 * (spec 01.03 section 7, FM-01.03-2).
 *
 * <p><b>The defect.</b> {@code processEveryChangeRecord} wraps only
 * {@code drainBeforeDDL()} and {@code performDDLOperation()} in the
 * {@code DDLReplicationException} guard. The ignore rules are evaluated BEFORE
 * that guard ({@code checkIfDDLNeedsToBeIgnored}: {@code Pattern.compile} of every
 * {@code ignore.ddl.regex} entry, table-name extraction, the capture filter), so an
 * exception there reaches the method's catch-all, is logged as
 * {@code Exception processing record}, and the method returns {@code null}.
 * {@code handleChangeEventBatch} then treats the DDL record as handled (a DDL is
 * never a control record and acknowledges itself), throws nothing, and the next
 * written rows move the durable offset past a schema change that never reached
 * ClickHouse. An {@code ignore.ddl.regex} that does not compile (one stray
 * parenthesis) does this to EVERY DDL of the process: a silent, count-clean
 * schema divergence.</p>
 */
public class DdlIgnoreRuleFailureIsLoudTest {

    /** A DDL change event: a Struct carrying a {@code ddl} field, no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> ddlEvent(String ddl) {
        Schema schema = SchemaBuilder.struct()
                .field("ddl", Schema.STRING_SCHEMA)
                .build();
        Struct value = new Struct(schema);
        value.put("ddl", ddl);
        SourceRecord record = new SourceRecord(null, null, "embeddedconnector", schema, value);
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
                return "embeddedconnector";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    private static Object invokeProcess(DebeziumChangeEventCapture capture,
                                        ChangeEvent<SourceRecord, SourceRecord> record,
                                        Properties props) throws Exception {
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
        try {
            return m.invoke(capture, props, record, null, null, null, true,
                    new DebeziumChangeEventCapture.VersionAssignment(1_000_000_001L, 1_000L));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    @Test
    @Disabled("DEFECT FM-01.03-2: an exception while evaluating the DDL ignore rules (e.g. an "
            + "ignore.ddl.regex that does not compile) is swallowed by processEveryChangeRecord's "
            + "catch-all; the DDL is dropped and later row acknowledgements move the offset past it")
    @DisplayName("A DDL whose ignore rules cannot be evaluated halts the pipeline instead of being skipped")
    public void invalidIgnoreRegexDoesNotSilentlySkipTheDdl() {
        Properties props = new Properties();
        // One unbalanced parenthesis: Pattern.compile throws PatternSyntaxException
        // for every DDL the process receives.
        props.setProperty(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX, "(?i)^CREATE\\s+VIEW(");

        assertThrows(RuntimeException.class,
                () -> invokeProcess(new DebeziumChangeEventCapture(),
                        ddlEvent("ALTER TABLE db.orders ADD COLUMN note VARCHAR(64)"), props),
                "a DDL that could not even be classified must stop the engine (offset stays behind it, "
                        + "a restart redelivers it); 2.11.0 logs 'Exception processing record' and returns "
                        + "null, and the DDL is silently dropped");
    }
}
