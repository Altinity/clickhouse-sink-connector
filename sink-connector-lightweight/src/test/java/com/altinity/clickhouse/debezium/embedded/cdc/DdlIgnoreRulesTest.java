package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.PostgreSQLDDLParserService;
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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which DDL is ignored before execution (spec 06.08 §3.3): DDL for tables
 * outside the capture lists, and — when the operator asks for it —
 // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
 * statement-level {@code DROP TABLE} / {@code TRUNCATE TABLE} /
 * {@code DROP DATABASE}.
 *
 * <p><b>The defects.</b> (1) No table filter existed on the sink DDL path, so
 * a {@code CREATE TABLE} outside {@code table.include.list} created a spurious
 * ClickHouse table and an {@code ALTER} on such a table halted the pipeline on
 // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
 * the missing target. (2) {@code disable.drop.truncate} was dead: it was
 * tested BEFORE {@code parseSql} computed the flag it reads, so the flag was
 * always false; and the flag itself was set by ANY {@code DROP} token, so had
 * it worked it would have swallowed {@code ALTER TABLE ... DROP COLUMN} and
 * {@code DROP INDEX} too.</p>
 */
public class DdlIgnoreRulesTest {

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static boolean mysqlFlag(String sql) {
        MySQLDDLParserService service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db1");
        AtomicBoolean flag = new AtomicBoolean(false);
        service.parseSql(sql, "", new StringBuffer(), flag);
        return flag.get();
    }

    private static boolean postgresFlag(String sql) {
        PostgreSQLDDLParserService service = new PostgreSQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db1");
        AtomicBoolean flag = new AtomicBoolean(false);
        service.parseSql(sql, "", new StringBuffer(), flag);
        return flag.get();
    }

    // ------------------------------------------------------------ D12: statement kind

    @Test
    // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
    @DisplayName("MySQL: the drop/truncate flag is set only for DROP TABLE, TRUNCATE TABLE and DROP DATABASE")
    public void mysqlFlagIsStatementKind() {
        assertTrue(mysqlFlag("DROP TABLE t"));
        assertTrue(mysqlFlag("DROP TABLE IF EXISTS db1.t"));
        assertTrue(mysqlFlag("TRUNCATE TABLE t"));
        assertTrue(mysqlFlag("TRUNCATE t"));
        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        assertTrue(mysqlFlag("DROP DATABASE d"));

        assertFalse(mysqlFlag("ALTER TABLE t DROP COLUMN c"),
                "a column drop is schema evolution, not a table drop (pre-fix: any DROP token counted)");
        assertFalse(mysqlFlag("ALTER TABLE t ALTER COLUMN c DROP DEFAULT"));
        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        assertFalse(mysqlFlag("DROP INDEX i ON t"));
        assertFalse(mysqlFlag("ALTER TABLE t ADD COLUMN c INT"));
    }

    @Test
    @DisplayName("PostgreSQL: the drop/truncate flag is set only for DROP TABLE, TRUNCATE and DROP SCHEMA")
    public void postgresFlagIsStatementKind() {
        assertTrue(postgresFlag("DROP TABLE t"));
        assertTrue(postgresFlag("TRUNCATE t"));
        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        assertTrue(postgresFlag("DROP SCHEMA s"));
        assertFalse(postgresFlag("ALTER TABLE t DROP COLUMN c"),
                "pre-fix: any DROP token counted");
        assertFalse(postgresFlag("DROP INDEX i"));
    }

    // ------------------------------------------------------------ helpers for the record path

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

    /** A MySQL schema-change record: key {databaseName}, value {ddl, tableChanges[{id}]}. */
    private static SourceRecord ddlRecord(String database, String ddl, String... tableIds) {
        Schema keySchema = SchemaBuilder.struct().field("databaseName", Schema.STRING_SCHEMA).build();
        Struct key = new Struct(keySchema);
        key.put("databaseName", database);
        Schema changeSchema = SchemaBuilder.struct().field("id", Schema.STRING_SCHEMA).build();
        Schema valueSchema = SchemaBuilder.struct()
                .field("ddl", Schema.STRING_SCHEMA)
                .field("tableChanges", SchemaBuilder.array(changeSchema).build())
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ddl", ddl);
        List<Struct> changes = new ArrayList<>();
        for (String id : tableIds) {
            Struct c = new Struct(changeSchema);
            c.put("id", id);
            changes.add(c);
        }
        value.put("tableChanges", changes);
        return new SourceRecord(null, null, "db1", 0, keySchema, key, valueSchema, value);
    }

    /**
     * Like {@link #ddlRecord} but carrying a source offset {@code snapshot=INITIAL},
     * so {@code DebeziumChangeEventCapture.isSnapshotDDL} reports it as snapshot DDL.
     */
    private static SourceRecord snapshotDdlRecord(String database, String ddl, String... tableIds) {
        SourceRecord streaming = ddlRecord(database, ddl, tableIds);
        Map<String, Object> sourceOffset = new HashMap<>();
        sourceOffset.put("snapshot", "INITIAL");
        return new SourceRecord(null, sourceOffset, "db1", 0,
                streaming.keySchema(), streaming.key(),
                streaming.valueSchema(), streaming.value());
    }

    private static boolean invokeIgnored(DebeziumChangeEventCapture capture, String ddl, Properties props,
                                         SourceRecord sr) throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod("checkIfDDLNeedsToBeIgnored",
                String.class, Properties.class, SourceRecord.class, AtomicBoolean.class);
        m.setAccessible(true);
        try {
            return (Boolean) m.invoke(capture, ddl, props, sr, new AtomicBoolean(false));
        } catch (InvocationTargetException ite) {
            throw (Exception) ite.getCause();
        }
    }

    private static Object invokeProcess(DebeziumChangeEventCapture capture, SourceRecord record,
                                        Properties props) throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class, ChangeEvent.class, DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class, DebeziumEngine.RecordCommitter.class,
                boolean.class, long.class);
        m.setAccessible(true);
        try {
            return m.invoke(capture, props, changeEvent(record), null, config(), null, true, 1000000001L);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    private static DebeziumChangeEventCapture capture() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        Field f = DebeziumChangeEventCapture.class.getDeclaredField("pgConfig");
        f.setAccessible(true);
        f.set(capture, new PostgresConnectorConfig(new Properties()));
        return capture;
    }

    private static Properties mysqlProps(String... keyValues) {
        Properties p = new Properties();
        p.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    // ------------------------------------------------------------ D18: capture lists

    @Test
    @DisplayName("DDL for a table outside table.include.list is ignored; DDL for a captured table is not")
    public void ddlOutsideIncludeListIsIgnored() throws Exception {
        DebeziumChangeEventCapture capture = capture();
        Properties props = mysqlProps("table.include.list", "db1\\.orders");

        String audit = "CREATE TABLE audit (id INT PRIMARY KEY)";
        assertTrue(invokeIgnored(capture, audit, props, ddlRecord("db1", audit, "db1.audit")),
                "a table nobody asked to replicate must not have its DDL applied (spurious table / "
                        + "Code: 60 halt on a later ALTER)");
        assertEquals(audit, capture.getLastIgnoredDDL());

        String orders = "ALTER TABLE orders ADD COLUMN note VARCHAR(20)";
        assertFalse(invokeIgnored(capture, orders, props, ddlRecord("db1", orders, "db1.orders")));
    }

    @Test
    @DisplayName("DDL for a table in table.exclude.list, or a database outside database.include.list, is ignored")
    public void excludeAndDatabaseListsApply() throws Exception {
        DebeziumChangeEventCapture capture = capture();
        String ddl = "ALTER TABLE audit_log ADD COLUMN c INT";
        assertTrue(invokeIgnored(capture, ddl, mysqlProps("table.exclude.list", "db1\\.audit.*"),
                ddlRecord("db1", ddl, "db1.audit_log")));
        assertTrue(invokeIgnored(capture, ddl, mysqlProps("database.include.list", "shop"),
                ddlRecord("db1", ddl, "db1.audit_log")));
        assertFalse(invokeIgnored(capture, ddl, mysqlProps("database.include.list", "db1"),
                ddlRecord("db1", ddl, "db1.audit_log")));
    }

    @Test
    @DisplayName("A multi-table DDL is kept when any of its tables is captured; no lists means nothing is filtered")
    public void multiTableAndNoLists() throws Exception {
        DebeziumChangeEventCapture capture = capture();
        String rename = "RENAME TABLE audit TO orders";
        assertFalse(invokeIgnored(capture, rename, mysqlProps("table.include.list", "db1\\.orders"),
                ddlRecord("db1", rename, "db1.audit", "db1.orders")));
        assertFalse(invokeIgnored(capture, "ALTER TABLE audit ADD COLUMN c INT", mysqlProps(),
                ddlRecord("db1", "ALTER TABLE audit ADD COLUMN c INT", "db1.audit")));
    }

    // ------------------------------------------------------------ D12: through the record path

    @Test
    // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
    @DisplayName("disable.drop.truncate=true skips DROP TABLE / TRUNCATE after the parse, and nothing else")
    public void disableDropTruncateIsScopedAndLive() throws Exception {
        Properties disabled = mysqlProps(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, "true");

        // Skipped: returns without executing (nothing to execute against, and
        // no DDLReplicationException).
        DebeziumChangeEventCapture capture = capture();
        assertNull(invokeProcess(capture, ddlRecord("db1", "DROP TABLE t", "db1.t"), disabled));
        assertEquals("DROP TABLE t", capture.getLastIgnoredDDL(),
                "pre-fix the flag was read before the parse set it, so the property was dead");
        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        assertNull(invokeProcess(capture, ddlRecord("db1", "TRUNCATE TABLE t", "db1.t"), disabled));

        // NOT skipped: a column drop reaches execution (which fails loudly
        // here because there is no ClickHouse), proving it was not swallowed.
        assertThrows(DDLReplicationException.class,
                // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
                () -> invokeProcess(capture(), ddlRecord("db1", "ALTER TABLE t DROP COLUMN c", "db1.t"),
                        disabled),
                "ALTER TABLE ... DROP COLUMN must not be caught by disable.drop.truncate");

        // Without the property a DROP TABLE is executed (and fails loudly here).
        assertThrows(DDLReplicationException.class,
                // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
                () -> invokeProcess(capture(), ddlRecord("db1", "DROP TABLE t", "db1.t"), mysqlProps()));
    }

    @Test
    // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
    @DisplayName("disable.drop.truncate exempts snapshot-phase DDL: the snapshot DROP TABLE still executes")
    public void disableDropTruncateExemptsSnapshotDdl() throws Exception {
        // enable.snapshot.ddl=true so the snapshot DDL is not ignored by the
        // snapshot branch first; disable.drop.truncate=true would, before the
        // fix, still swallow the snapshot DROP and freeze a pre-created schema.
        Properties props = mysqlProps(
                SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, "true",
                SinkConnectorLightWeightConfig.ENABLE_SNAPSHOT_DDL, "true");

        // A snapshot DROP TABLE reaches execution (and fails loudly here because
        // there is no ClickHouse), proving it was NOT swallowed by the flag.
        assertThrows(DDLReplicationException.class,
                // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
                () -> invokeProcess(capture(),
                        snapshotDdlRecord("db1", "DROP TABLE t", "db1.t"), props),
                "a snapshot DROP is schema bootstrap and must execute despite disable.drop.truncate");

        // A STREAMING DROP with the same properties is still skipped.
        DebeziumChangeEventCapture capture = capture();
        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        assertNull(invokeProcess(capture, ddlRecord("db1", "DROP TABLE t", "db1.t"), props));
        assertEquals("DROP TABLE t", capture.getLastIgnoredDDL(),
                "a streaming DROP is still frozen by disable.drop.truncate");
    }
}
