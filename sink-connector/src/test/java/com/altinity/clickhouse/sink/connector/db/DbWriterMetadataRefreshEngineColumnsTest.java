package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementExecutor;
import com.altinity.clickhouse.sink.connector.db.batch.RecordingJdbc;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link DbWriter} built while its target table is missing (or its catalog
 * read failed) has no engine, so its constructor resolves no version / sign /
 * delete column. The batch runnable later retries through
 * {@link DbWriter#updateColumnNameToDataTypeMap()}; that refresh must resolve
 * the engine-specific columns too.
 *
 * <p>Before the fix the refresh updated the engine but left the columns
 * unset. For a custom {@code ReplacingMergeTree(ver, del)} the version and
 * delete columns were then omitted from the INSERT and stored as 0, so a
 * DELETE landed as a live row; for the default names the field mapper refused
 * the batch.</p>
 *
 * <p>The tests drive a real writer against an in-memory ClickHouse catalog (JDK
 * dynamic proxies; no mocking framework is on this classpath), so no server is
 * required.</p>
 */
public class DbWriterMetadataRefreshEngineColumnsTest {

    private static final String DATABASE = "testdb";
    private static final String TABLE = "orders";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("val", Schema.STRING_SCHEMA)
            .build();

    @BeforeEach
    public void setUp() {
        // One attempt per metadata read reaches the same verdict and keeps the tests quick.
        DBMetadata.setMaxRetries(1);
        HikariDbSource.close();
    }

    @AfterEach
    public void tearDown() {
        DBMetadata.setMaxRetries(10);
        HikariDbSource.close();
    }

    /**
     * An in-memory catalog holding at most one table. {@code engineFull == null}
     * means the table does not exist; {@link #define} makes it appear.
     */
    private static final class Catalog {

        private volatile String engineFull;
        private volatile String[][] columns = new String[0][];

        void define(String engineFull, String[]... columns) {
            this.columns = columns;
            this.engineFull = engineFull;
        }

        ResultSet answer(String sql) throws SQLException {
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (normalized.contains("is_in_sorting_key")) {
                return rows();
            }
            if (normalized.contains("system.databases")) {
                return rows(new String[]{DATABASE});
            }
            if (normalized.contains("engine_full")) {
                return engineFull == null ? rows() : rows(new String[]{engineFull});
            }
            if (normalized.contains("count()")) {
                return rows(new String[]{engineFull == null ? "0" : "1"});
            }
            if (normalized.contains("system.columns")) {
                String[][] current = columns;
                return rows(current);
            }
            throw new SQLException("The catalog fake has no answer for: " + sql);
        }

        /** Every row is {@code {name-or-text, type}}; {@code getString(int)} reads the first cell. */
        private static ResultSet rows(String[]... rows) {
            int[] cursor = {-1};
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "next":
                                return ++cursor[0] < rows.length;
                            case "getString":
                                if (args[0] instanceof Integer) {
                                    return rows[cursor[0]][(Integer) args[0] - 1];
                                }
                                if ("name".equals(args[0])) {
                                    return rows[cursor[0]][0];
                                }
                                if ("type".equals(args[0])) {
                                    return rows[cursor[0]][1];
                                }
                                return "";
                            case "getLong":
                                return Long.parseLong(rows[cursor[0]][0]);
                            case "close":
                                return null;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "createStatement":
                                return statement();
                            case "isClosed":
                                return false;
                            case "isValid":
                                return true;
                            case "getAutoCommit":
                                return true;
                            case "toString":
                                return "catalog-connection";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private Statement statement() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        return answer((String) args[0]);
                    case "toString":
                        return "catalog-statement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class}, handler);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "1");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", DATABASE);
        // A table missing from ClickHouse is not created for the user, so the writer stays unresolved.
        props.put("auto.create.tables", "false");
        props.put("single.threaded", "true");
        props.put("connection.pool.disable", "true");
        props.put("errors.max.retries", "1");
        // Naming the sink connector class makes ConnectorType answer KAFKA, so the
        // offset-storage database is not created on this no-server config.
        props.put("connector.class",
                "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector");
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    /** A writer built while the table is missing: unresolved, with no engine and no engine columns. */
    private static DbWriter unresolvedWriter(Catalog catalog) {
        DbWriter writer = new DbWriter("127.0.0.1", 1, DATABASE, TABLE, "default", "", config(), null,
                catalog.connection());
        assertFalse(writer.wasTableMetaDataRetrieved(), "precondition: the table is missing");
        assertNull(writer.getEngine(), "precondition: no engine resolved");
        assertNull(writer.getVersionColumn(), "precondition: no version column resolved");
        assertNull(writer.getSignColumn(), "precondition: no sign column resolved");
        assertNull(writer.getReplacingMergeTreeDeleteColumn(), "precondition: no delete column resolved");
        assertFalse(writer.isReplacingMergeTreeWithIsDeletedColumn(), "precondition: no is_deleted flag");
        return writer;
    }

    @Test
    @DisplayName("A refresh after the table appears resolves the default ReplacingMergeTree(_version, is_deleted) columns")
    public void refreshResolvesDefaultReplacingMergeTreeColumns() throws SQLException {
        Catalog catalog = new Catalog();
        DbWriter writer = unresolvedWriter(catalog);

        catalog.define("ReplacingMergeTree(_version, is_deleted) ORDER BY id",
                new String[]{"id", "Int32"}, new String[]{"val", "String"},
                new String[]{"_version", "UInt64"}, new String[]{"is_deleted", "UInt8"});
        writer.updateColumnNameToDataTypeMap();

        assertTrue(writer.wasTableMetaDataRetrieved());
        assertEquals("_version", writer.getVersionColumn());
        assertEquals("is_deleted", writer.getReplacingMergeTreeDeleteColumn());
        assertTrue(writer.isReplacingMergeTreeWithIsDeletedColumn());
        assertNull(writer.getSignColumn());
    }

    @Test
    @DisplayName("A refresh after the table appears resolves a custom ReplacingMergeTree(ver, del) columns")
    public void refreshResolvesCustomReplacingMergeTreeColumns() throws SQLException {
        Catalog catalog = new Catalog();
        DbWriter writer = unresolvedWriter(catalog);

        catalog.define("ReplacingMergeTree(ver, del) ORDER BY id",
                new String[]{"id", "Int32"}, new String[]{"val", "String"},
                new String[]{"ver", "UInt64"}, new String[]{"del", "UInt8"});
        writer.updateColumnNameToDataTypeMap();

        assertTrue(writer.wasTableMetaDataRetrieved());
        assertEquals("ver", writer.getVersionColumn(),
                "left null, the version column is omitted from the INSERT and stored as 0");
        assertEquals("del", writer.getReplacingMergeTreeDeleteColumn(),
                "left null, the delete column is omitted from the INSERT and a DELETE lands as a live row");
        assertTrue(writer.isReplacingMergeTreeWithIsDeletedColumn());
        assertNull(writer.getSignColumn());
    }

    @Test
    @DisplayName("A refresh after the table appears resolves the CollapsingMergeTree sign column")
    public void refreshResolvesCollapsingSignColumn() throws SQLException {
        Catalog catalog = new Catalog();
        DbWriter writer = unresolvedWriter(catalog);

        catalog.define("CollapsingMergeTree(sgn) ORDER BY id",
                new String[]{"id", "Int32"}, new String[]{"sgn", "Int8"});
        writer.updateColumnNameToDataTypeMap();

        assertTrue(writer.wasTableMetaDataRetrieved());
        assertEquals("sgn", writer.getSignColumn());
        assertNull(writer.getVersionColumn());
        assertNull(writer.getReplacingMergeTreeDeleteColumn());
    }

    @Test
    @DisplayName("A refresh is idempotent and does not keep the previous engine's columns")
    public void refreshReplacesTheColumnsOfThePreviousEngine() throws SQLException {
        Catalog catalog = new Catalog();
        DbWriter writer = unresolvedWriter(catalog);
        catalog.define("ReplacingMergeTree(ver, del) ORDER BY id",
                new String[]{"id", "Int32"}, new String[]{"ver", "UInt64"}, new String[]{"del", "UInt8"});

        writer.updateColumnNameToDataTypeMap();
        writer.updateColumnNameToDataTypeMap();
        assertEquals("ver", writer.getVersionColumn());
        assertEquals("del", writer.getReplacingMergeTreeDeleteColumn());

        // The table is dropped and recreated as a plain MergeTree.
        catalog.define("MergeTree ORDER BY id", new String[]{"id", "Int32"});
        writer.updateColumnNameToDataTypeMap();

        assertTrue(writer.wasTableMetaDataRetrieved());
        assertNull(writer.getVersionColumn(), "stale version column of the dropped table");
        assertNull(writer.getReplacingMergeTreeDeleteColumn(), "stale delete column of the dropped table");
        assertFalse(writer.isReplacingMergeTreeWithIsDeletedColumn(), "stale is_deleted flag of the dropped table");
    }

    @Test
    @DisplayName("A refresh that finds an unversionable ReplacingMergeTree refuses it, and keeps refusing it")
    public void refreshRefusesAnUnversionableReplacingMergeTree() throws SQLException {
        Catalog catalog = new Catalog();
        DbWriter writer = unresolvedWriter(catalog);

        // A bare ReplacingMergeTree() names no version column: the constructor refuses it
        // (DbWriterEngineColumnsTest); a refresh must refuse it as well.
        catalog.define("ReplacingMergeTree() ORDER BY id",
                new String[]{"id", "Int32"}, new String[]{"val", "String"});

        assertThrows(IllegalStateException.class, writer::updateColumnNameToDataTypeMap);
        assertFalse(writer.wasTableMetaDataRetrieved(),
                "a writer that refused its table must report unresolved metadata, or the next batch "
                        + "skips the refresh and writes with columns it cannot bind");
        // The next batch refreshes again and is refused again, rather than writing.
        assertThrows(IllegalStateException.class, writer::updateColumnNameToDataTypeMap);
        assertFalse(writer.wasTableMetaDataRetrieved());
    }

    /**
     * What the batch runnable does on a metadata retry: refresh the writer, then
     * build the executor from the writer's engine columns. A DELETE against the
     * custom ReplacingMergeTree must bind its delete and version columns.
     */
    @Test
    @DisplayName("An executor built after the refresh binds the version and delete columns of a custom ReplacingMergeTree")
    public void executorBuiltAfterRefreshBindsEngineColumns() throws Exception {
        Catalog catalog = new Catalog();
        DbWriter writer = unresolvedWriter(catalog);
        catalog.define("ReplacingMergeTree(ver, del) ORDER BY id",
                new String[]{"id", "Int32"}, new String[]{"val", "String"},
                new String[]{"ver", "UInt64"}, new String[]{"del", "UInt8"});
        writer.updateColumnNameToDataTypeMap();

        ClickHouseSinkConnectorConfig config = config();
        ClickHouseStruct delete = new ClickHouseStruct(1L, "topic", null, 0, System.currentTimeMillis(),
                new Struct(ROW_SCHEMA).put("id", 1).put("val", "v"), null, null,
                ClickHouseConverter.CDC_OPERATION.DELETE);
        delete.setDatabase(DATABASE);
        delete.setGtid(77L);
        delete.setTs_ms(1767225600000L);
        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(delete);

        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> grouped = new ArrayList<>();
        new GroupInsertQueryWithBatchRecords(writer.getVersionColumn(), writer.getSignColumn(),
                writer.getReplacingMergeTreeDeleteColumn())
                .groupQueryWithRecords(records, grouped, new HashMap<>(), config, TABLE, DATABASE, null,
                        writer.getColumnNameToDataTypeMap());
        Map<String, Integer> indexMap = grouped.get(0).keySet().iterator().next().getRight();

        RecordingJdbc jdbc = new RecordingJdbc();
        PreparedStatementExecutor executor = new PreparedStatementExecutor(
                writer.getReplacingMergeTreeDeleteColumn(),
                writer.isReplacingMergeTreeWithIsDeletedColumn(),
                writer.getSignColumn(),
                writer.getVersionColumn(),
                writer.getDatabaseName(),
                ZoneId.of("UTC"),
                writer::getSortingKeyColumns);
        executor.addToPreparedStatementBatch("topic", grouped, new BlockMetaData(), config,
                jdbc.connection(), TABLE, writer.getColumnNameToDataTypeMap(), writer.getEngine());

        List<RecordingJdbc.Event> staged = jdbc.ofKind(RecordingJdbc.ADD_BATCH);
        assertEquals(1, staged.size(), "the DELETE is staged as one row: " + jdbc.events);
        Map<Integer, Object> bound = staged.get(0).params;
        assertEquals(1, bound.get(indexMap.get("del")),
                "a DELETE must carry the delete marker; unbound it is stored as 0, a live row: " + bound);
        assertNotNull(bound.get(indexMap.get("ver")),
                "the version column must be bound; unbound it is stored as 0: " + bound);
        assertTrue(bound.get(indexMap.get("ver")) instanceof Long, bound.toString());
    }
}
