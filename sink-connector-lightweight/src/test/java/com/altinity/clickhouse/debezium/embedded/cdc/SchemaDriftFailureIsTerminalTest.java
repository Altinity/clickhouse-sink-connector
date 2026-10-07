package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.postgres.schema.PostgresSchemaChangeDetector;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 10.04 section 3.9: a PostgreSQL schema-drift failure on a real row must
 * halt {@code processEveryChangeRecord} the same way an unconvertible row
 * does, not be logged and swallowed.
 *
 * <p>The record used here carries a populated {@code after} struct and a
 * {@code source.db} field, so {@code dmlTable}/{@code dmlDatabase} both
 * resolve and {@code PostgresSchemaChangeDetector.checkAndReconcile} is
 * actually invoked. The detector is wired to a {@link BaseDbWriter} whose
 * JDBC connection rejects every statement, so {@code fetchClickHouseSchema}
 * fails genuinely (not a legitimate table-absent case) and
 * {@code checkAndReconcile} propagates that failure.</p>
 *
 * <p>Against the pre-fix code this fails: the catch at the schema-drift call
 * site logged {@code "Schema drift detection threw unexpectedly; continuing
 * replication"} and returned, so {@code parse()} ran next and the record was
 * written (or, with no live ClickHouse, would have been) without the pipeline
 * ever knowing its schema could not be confirmed.</p>
 */
public class SchemaDriftFailureIsTerminalTest {

    private static void setField(Object target, Class<?> declaring, String name, Object value) throws Exception {
        Field f = declaring.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object invokeProcess(DebeziumChangeEventCapture capture,
                                         ChangeEvent<SourceRecord, SourceRecord> record) throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class,
                ChangeEvent.class,
                com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class,
                DebeziumEngine.RecordCommitter.class,
                boolean.class,
                VersionSequencer.VersionAssignment.class);
        m.setAccessible(true);
        try {
            return m.invoke(capture, new Properties(), record, null, config(), null, true,
                    new VersionSequencer.VersionAssignment(1000000001L, 1000L));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** A JDBC connection that reports itself open and rejects every statement. */
    private static Connection rejectingConnection() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "close":
                    return null;
                case "createStatement":
                case "prepareStatement":
                    throw new SQLException("Code: 210. DB::NetException: Connection refused");
                case "toString":
                    return "rejecting-connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /** A row (non-DDL) change event: op=c, a populated after struct, source.db set. */
    private static ChangeEvent<SourceRecord, SourceRecord> rowEvent() {
        Schema afterSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        Schema sourceSchema = SchemaBuilder.struct()
                .field("db", Schema.STRING_SCHEMA)
                .build();
        Schema valueSchema = SchemaBuilder.struct()
                .field("after", afterSchema)
                .field("source", sourceSchema)
                .field("op", Schema.STRING_SCHEMA)
                .build();

        Struct after = new Struct(afterSchema).put("id", 7).put("name", "a-row");
        Struct source = new Struct(sourceSchema).put("db", "appdb");
        Struct value = new Struct(valueSchema).put("after", after).put("source", source).put("op", "c");

        SourceRecord record = new SourceRecord(null, null, "pg.public.orders", valueSchema, value);
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
                return "pg.public.orders";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    @Test
    @DisplayName("10.04 s3.9: a schema-drift failure on a real row halts via RecordReplicationException")
    public void schemaDriftFailurePropagatesThroughProcessEveryChangeRecord() throws Exception {
        BaseDbWriter writer = new BaseDbWriter("localhost", 8123, "system", "default", "",
                config(), rejectingConnection());
        PostgresSchemaChangeDetector detector = new PostgresSchemaChangeDetector(writer, config());

        PostgresConnectorConfig pgConfig = new PostgresConnectorConfig(new Properties());
        setField(pgConfig, PostgresConnectorConfig.class, "postgresSchemaChangeDetector", detector);

        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        setField(capture, DebeziumChangeEventCapture.class, "pgConfig", pgConfig);

        RecordReplicationException thrown = assertThrows(RecordReplicationException.class,
                () -> invokeProcess(capture, rowEvent()),
                "a genuine schema-drift failure (ClickHouse unreachable while checking a real row) "
                        + "must halt the pipeline instead of being logged and swallowed");
        assertTrue(thrown.getMessage().contains("schema-drift"),
                "the halting exception must name the schema-drift check: " + thrown.getMessage());
        assertInstanceOf(RuntimeException.class, thrown.getCause(),
                "the underlying ClickHouse-unreachable failure must be preserved as the cause");
    }
}
