package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEW-J2: the {@code snapshot} entry of a record's source offset is a
 * {@link Boolean} for some connector versions and a String
 * ({@code TRUE}, {@code INITIAL}, {@code LAST}, {@code BLOCKING},
 * {@code INITIAL_ONLY}) for others. The classifier used to cast it to
 * {@code Boolean}, so a String threw {@link ClassCastException} out of the DDL
 * path.
 */
public class DdlSnapshotOffsetTest {

    private static SourceRecord ddlRecordWithOffset(Map<String, Object> sourceOffset, String ddl) {
        Schema keySchema = SchemaBuilder.struct().field("databaseName", Schema.STRING_SCHEMA).build();
        Struct key = new Struct(keySchema);
        key.put("databaseName", "db1");
        Schema changeSchema = SchemaBuilder.struct().field("id", Schema.STRING_SCHEMA).build();
        Schema valueSchema = SchemaBuilder.struct()
                .field("ddl", Schema.STRING_SCHEMA)
                .field("tableChanges", SchemaBuilder.array(changeSchema).build())
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ddl", ddl);
        List<Struct> changes = new ArrayList<>();
        Struct change = new Struct(changeSchema);
        change.put("id", "db1.t");
        changes.add(change);
        value.put("tableChanges", changes);
        return new SourceRecord(null, sourceOffset, "db1", 0, keySchema, key, valueSchema, value);
    }

    private static SourceRecord withSnapshot(Object snapshot, String ddl) {
        Map<String, Object> offset = new HashMap<>();
        offset.put("snapshot", snapshot);
        return ddlRecordWithOffset(offset, ddl);
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
        Map<String, String> cfg = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(cfg);
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(cfg));
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class, ChangeEvent.class, DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class, DebeziumEngine.RecordCommitter.class,
                boolean.class, VersionSequencer.VersionAssignment.class);
        m.setAccessible(true);
        try {
            return m.invoke(capture, props, changeEvent(record), null, config, null, true,
                    new VersionSequencer.VersionAssignment(1000000001L, 1000L));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    @Test
    @DisplayName("NEW-J2: every documented snapshot offset value classifies without a ClassCastException")
    public void snapshotOffsetValuesAreClassified() {
        assertTrue(DebeziumChangeEventCapture.isSnapshotOffsetValue(Boolean.TRUE));
        assertFalse(DebeziumChangeEventCapture.isSnapshotOffsetValue(Boolean.FALSE));

        for (String snapshotValue : new String[]{"TRUE", "INITIAL", "LAST", "BLOCKING", "INITIAL_ONLY"}) {
            assertTrue(DebeziumChangeEventCapture.isSnapshotOffsetValue(snapshotValue),
                    snapshotValue + " is a snapshot phase");
        }
        // Case-insensitive and whitespace-tolerant.
        assertTrue(DebeziumChangeEventCapture.isSnapshotOffsetValue("initial"));
        assertTrue(DebeziumChangeEventCapture.isSnapshotOffsetValue("Blocking"));
        assertTrue(DebeziumChangeEventCapture.isSnapshotOffsetValue(" last "));

        assertFalse(DebeziumChangeEventCapture.isSnapshotOffsetValue("FALSE"));
        assertFalse(DebeziumChangeEventCapture.isSnapshotOffsetValue("false"));
        assertFalse(DebeziumChangeEventCapture.isSnapshotOffsetValue(""));
        assertFalse(DebeziumChangeEventCapture.isSnapshotOffsetValue("streaming"));
        assertFalse(DebeziumChangeEventCapture.isSnapshotOffsetValue(null));
        assertFalse(DebeziumChangeEventCapture.isSnapshotOffsetValue(Integer.valueOf(1)),
                "an unknown type is not a snapshot and must not throw");
    }

    @Test
    @DisplayName("NEW-J2: a Boolean or String snapshot DDL is ignored unless enable.snapshot.ddl=true")
    public void snapshotDdlIsIgnoredUnlessEnabled() throws Exception {
        DebeziumChangeEventCapture capture = capture();
        String ddl = "ALTER TABLE t ADD COLUMN c INT";

        for (Object snapshot : new Object[]{Boolean.TRUE, "TRUE", "INITIAL", "LAST", "BLOCKING", "INITIAL_ONLY"}) {
            SourceRecord record = withSnapshot(snapshot, ddl);
            assertTrue(invokeIgnored(capture, ddl, mysqlProps(), record),
                    "snapshot=" + snapshot + " must be ignored by default");
            assertFalse(invokeIgnored(capture, ddl,
                            mysqlProps(SinkConnectorLightWeightConfig.ENABLE_SNAPSHOT_DDL, "true"), record),
                    "snapshot=" + snapshot + " must be applied when enable.snapshot.ddl=true");
        }
    }

    @Test
    @DisplayName("NEW-J2: a streaming DDL (Boolean false, String FALSE, no offset) is not treated as snapshot DDL")
    public void streamingDdlIsNotSnapshotDdl() throws Exception {
        DebeziumChangeEventCapture capture = capture();
        String ddl = "ALTER TABLE t ADD COLUMN c INT";

        assertFalse(invokeIgnored(capture, ddl, mysqlProps(), withSnapshot(Boolean.FALSE, ddl)));
        assertFalse(invokeIgnored(capture, ddl, mysqlProps(), withSnapshot("FALSE", ddl)));
        assertFalse(invokeIgnored(capture, ddl, mysqlProps(), ddlRecordWithOffset(new HashMap<>(), ddl)));
        assertFalse(invokeIgnored(capture, ddl, mysqlProps(), ddlRecordWithOffset(null, ddl)));
    }

    @Test
    // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
    @DisplayName("NEW-J2: a streaming DROP TABLE with a Boolean snapshot=false is still suppressed by disable.drop.truncate")
    public void streamingDropIsStillSuppressedByDisableDropTruncate() throws Exception {
        Properties disabled = mysqlProps(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, "true");
        DebeziumChangeEventCapture capture = capture();

        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        assertNull(invokeProcess(capture, withSnapshot(Boolean.FALSE, "DROP TABLE t"), disabled));
        assertEquals("DROP TABLE t", capture.getLastIgnoredDDL());
    }
}
