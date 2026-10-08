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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 01.11: a MySQL TRUNCATE TABLE keeps reaching ClickHouse the way it did
 * under Debezium 3.1.3 after the upgrade to 3.3, which drops the statement when
 * {@code skipped.operations} contains {@code t}.
 */
public class MySqlTruncateRoutingTest {

    private static Properties mysql(String skipped) {
        Properties p = new Properties();
        p.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        if (skipped != null) {
            p.setProperty("skipped.operations", skipped);
        }
        return p;
    }

    @Test
    @DisplayName("Unset (Debezium default 't'): DDL route, Debezium is handed 'none'")
    public void defaultRoutesThroughDdl() {
        Properties p = mysql(null);
        assertTrue(MySqlTruncateRouting.apply(p));
        assertEquals("none", p.getProperty("skipped.operations"));
        assertEquals("ddl", p.getProperty(MySqlTruncateRouting.ROUTE_PROPERTY));
    }

    @Test
    @DisplayName("Idempotent: an engine rebuilt from the rewritten properties keeps the DDL route")
    public void applyIsIdempotent() {
        Properties p = mysql(null);
        assertTrue(MySqlTruncateRouting.apply(p));
        assertTrue(MySqlTruncateRouting.apply(p), "second call must not re-read the rewritten 'none'");
        assertEquals("none", p.getProperty("skipped.operations"));
    }

    @Test
    @DisplayName("'t' among other codes: DDL route, the other codes are kept")
    public void otherCodesAreKept() {
        Properties p = mysql("d, t");
        assertTrue(MySqlTruncateRouting.apply(p));
        assertEquals("d", p.getProperty("skipped.operations"));
    }

    @Test
    @DisplayName("'none': row route (spec 04.05), unchanged")
    public void noneKeepsRowRoute() {
        Properties p = mysql("none");
        assertFalse(MySqlTruncateRouting.apply(p));
        assertEquals("none", p.getProperty("skipped.operations"));
        assertEquals("row", p.getProperty(MySqlTruncateRouting.ROUTE_PROPERTY));
    }

    @Test
    @DisplayName("Not a binlog connector: untouched")
    public void postgresIsUntouched() {
        Properties p = new Properties();
        p.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        assertFalse(MySqlTruncateRouting.apply(p));
        assertNull(p.getProperty("skipped.operations"));
        assertNull(p.getProperty(MySqlTruncateRouting.ROUTE_PROPERTY));
    }

    private static final Schema SOURCE = SchemaBuilder.struct().name("io.debezium.connector.mysql.Source")
            .field("db", Schema.OPTIONAL_STRING_SCHEMA)
            .field("table", Schema.OPTIONAL_STRING_SCHEMA)
            .field("ts_ms", Schema.INT64_SCHEMA)
            .field("file", Schema.OPTIONAL_STRING_SCHEMA)
            .field("pos", Schema.OPTIONAL_INT64_SCHEMA)
            .build();

    /** The envelope Debezium's BinlogChangeRecordEmitter.emitTruncateRecord produces. */
    static SourceRecord truncateEvent(String op) {
        Schema envelope = SchemaBuilder.struct().name("srv.employees.t1.Envelope")
                .field("before", SchemaBuilder.struct().optional().build())
                .field("after", SchemaBuilder.struct().optional().build())
                .field("source", SOURCE)
                .field("op", Schema.STRING_SCHEMA)
                .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
                .build();
        Struct source = new Struct(SOURCE).put("db", "employees").put("table", "t1")
                .put("ts_ms", 1_700_000_000_000L).put("file", "binlog.000007").put("pos", 4242L);
        Struct value = new Struct(envelope).put("source", source).put("op", op).put("ts_ms", 1_700_000_009_999L);
        return new SourceRecord(Map.of("server", "srv"), Map.of("file", "binlog.000007", "pos", 4242L),
                "srv.employees.t1", null, null, null, envelope, value);
    }

    private static ChangeEvent<SourceRecord, SourceRecord> event(SourceRecord record) {
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

    // DESTRUCTIVE: none -- classifies in-memory records; nothing is executed.
    @Test
    @DisplayName("Only an op='t' envelope with a source db and table is a truncate event")
    public void recognisesTruncateEvents() {
        assertTrue(MySqlTruncateRouting.isTruncateEvent(truncateEvent("t")));
        assertFalse(MySqlTruncateRouting.isTruncateEvent(truncateEvent("c")));
        assertFalse(MySqlTruncateRouting.isTruncateEvent(null));
    }

    @Test
    @DisplayName("The 3.1.3 schema-change record: key databaseName, ddl, same source block and offset")
    public void schemaChangeRecordShape() {
        SourceRecord in = truncateEvent("t");
        SourceRecord out = MySqlTruncateRouting.toSchemaChangeRecord(in);
        Struct key = (Struct) out.key();
        Struct value = (Struct) out.value();
        assertEquals("employees", key.get("databaseName"));
        assertEquals("employees", value.get("databaseName"));
        // DESTRUCTIVE: none -- asserts on generated statement text; nothing is executed.
        assertEquals("TRUNCATE TABLE `t1`", value.get("ddl"));
        assertSame(((Struct) in.value()).get("source"), value.get("source"));
        assertEquals(1_700_000_000_000L, value.get("ts_ms"), "binlog event time (source.ts_ms), as 3.1.3");
        assertEquals(in.sourceOffset(), out.sourceOffset());
        assertEquals(in.sourcePartition(), out.sourcePartition());
        assertTrue(new DebeziumChangeEventCapture().isDDLRecord(event(out)));
    }

    @Test
    @DisplayName("A backtick in the table name is escaped in the statement")
    public void backtickIsEscaped() {
        // DESTRUCTIVE: none -- statement text only.
        assertEquals("TRUNCATE TABLE `a``b`", MySqlTruncateRouting.ddlFor("a`b"));
    }

    private static Object process(DebeziumChangeEventCapture capture,
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

    // DESTRUCTIVE: none -- the statement is matched by an ignore rule and never
    // executed; no connection exists in this test.
    @Test
    @DisplayName("DDL route: the truncate event reaches the DDL path (here: its ignore rules)")
    public void ddlRouteReachesTheDdlPath() throws Exception {
        Properties props = new Properties();
        // An ignore rule that matches only the routed statement: the DDL path
        // evaluates it, ignores the statement and returns no row, without a drain.
        // DESTRUCTIVE: none -- ignore-rule text only; no connection exists.
        props.setProperty(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX, "(?i)^TRUNCATE TABLE `t1`$");
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.mysqlTruncateViaDdl = true;
        ChangeEvent<SourceRecord, SourceRecord> ev = event(truncateEvent("t"));
        assertTrue(capture.isTruncateRoutedToDdl(ev));
        assertNull(process(capture, ev, props));
    }

    @Test
    @DisplayName("Row route: the same event goes to the row parser instead (spec 04.05)")
    public void rowRouteReachesTheRowParser() {
        // DESTRUCTIVE: none -- ignore-rule text only; no connection exists.
        Properties props = new Properties();
        props.setProperty(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX, "(?i)^TRUNCATE TABLE `t1`$");
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ChangeEvent<SourceRecord, SourceRecord> ev = event(truncateEvent("t"));
        assertFalse(capture.isTruncateRoutedToDdl(ev));
        // No parser is wired in this unit test: reaching the row path is proven by
        // the parser call failing loudly (spec 01.06 section 3.1).
        assertThrows(RecordReplicationException.class, () -> process(capture, ev, props));
    }
}
