package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 09.05: the schema-history store must keep every part of a Debezium 3.3
 * record (issue #1450). These tests pin the decision the preflight makes from the
 * live table facts; the copy/verify/EXCHANGE execution is exercised end to end
 * against a real ClickHouse server (spec 09.05 section 5).
 */
public class SchemaHistoryStorePreflightTest {

    /** The layout the shipped schema-history DDL created before this change. */
    private static SchemaHistoryStorePreflight.TableState defaultLayout() {
        SchemaHistoryStorePreflight.TableState s = new SchemaHistoryStorePreflight.TableState();
        s.database = "altinity_sink_connector";
        s.table = "replicate_schema_history";
        s.databaseEngine = "Atomic";
        s.engine = "ReplacingMergeTree";
        s.engineFull = "ReplacingMergeTree(record_insert_seq) ORDER BY id SETTINGS index_granularity = 8192";
        s.sortingKey = "id";
        s.primaryKey = "id";
        return s;
    }

    @Test
    @DisplayName("ReplacingMergeTree ORDER BY id is migrated to ORDER BY (id, history_data_seq)")
    public void defaultLayoutIsMigrated() {
        SchemaHistoryStorePreflight.Plan plan = SchemaHistoryStorePreflight.plan(defaultLayout());
        assertEquals(SchemaHistoryStorePreflight.Action.MIGRATE, plan.action);
        assertEquals("ReplacingMergeTree(record_insert_seq) ORDER BY (id, history_data_seq) "
                + "SETTINGS index_granularity = 8192", plan.newEngineFull);
        assertFalse(plan.rekey, "no 3.1.3 oversized record: a plain copy");
    }

    @Test
    @DisplayName("A sorting key that already includes history_data_seq is left alone")
    public void fixedLayoutIsLeftAlone() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.engineFull = "ReplacingMergeTree(record_insert_seq) ORDER BY (id, history_data_seq) "
                + "SETTINGS index_granularity = 8192";
        s.sortingKey = "id, history_data_seq";
        assertEquals(SchemaHistoryStorePreflight.Action.NONE, SchemaHistoryStorePreflight.plan(s).action);
    }

    @Test
    @DisplayName("3.1.3 oversized records on a fixed layout are re-keyed, keeping the engine clause")
    public void legacyRecordsOnFixedLayoutAreRekeyed() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.engineFull = "ReplacingMergeTree(record_insert_seq) ORDER BY (id, history_data_seq) "
                + "SETTINGS index_granularity = 8192";
        s.sortingKey = "id, history_data_seq";
        s.legacyRecordIds = 2;
        SchemaHistoryStorePreflight.Plan plan = SchemaHistoryStorePreflight.plan(s);
        assertEquals(SchemaHistoryStorePreflight.Action.MIGRATE, plan.action);
        assertTrue(plan.rekey);
        assertEquals(s.engineFull, plan.newEngineFull);
    }

    @Test
    @DisplayName("3.1.3 oversized records on the default layout: one migration fixes both")
    public void legacyRecordsOnDefaultLayout() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.legacyRecordIds = 1;
        SchemaHistoryStorePreflight.Plan plan = SchemaHistoryStorePreflight.plan(s);
        assertEquals(SchemaHistoryStorePreflight.Action.MIGRATE, plan.action);
        assertTrue(plan.rekey);
        assertTrue(plan.newEngineFull.contains("ORDER BY (id, history_data_seq)"));
    }

    @Test
    @DisplayName("Plain MergeTree keeps every row, so ORDER BY id is safe")
    public void plainMergeTreeIsSafe() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.engine = "MergeTree";
        s.engineFull = "MergeTree ORDER BY id SETTINGS index_granularity = 8192";
        assertEquals(SchemaHistoryStorePreflight.Action.NONE, SchemaHistoryStorePreflight.plan(s).action);
    }

    @Test
    @DisplayName("A replicated collapsing table is refused, never migrated automatically")
    public void replicatedIsRefused() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.engine = "ReplicatedReplacingMergeTree";
        s.engineFull = "ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/h', '{replica}', "
                + "record_insert_seq) ORDER BY id SETTINGS index_granularity = 8192";
        SchemaHistoryStorePreflight.Plan plan = SchemaHistoryStorePreflight.plan(s);
        assertEquals(SchemaHistoryStorePreflight.Action.REFUSE, plan.action);
        assertTrue(plan.reason.contains("replicated"));
    }

    @Test
    @DisplayName("A non-Atomic database is refused: no atomic EXCHANGE TABLES")
    public void ordinaryDatabaseIsRefused() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.databaseEngine = "Ordinary";
        assertEquals(SchemaHistoryStorePreflight.Action.REFUSE, SchemaHistoryStorePreflight.plan(s).action);
    }

    @Test
    @DisplayName("A key-value table keyed by id is refused: parts would overwrite each other")
    public void keyValueKeyedByIdIsRefused() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.engine = "KeeperMap";
        s.engineFull = "KeeperMap('/h', 10)";
        s.sortingKey = "";
        s.primaryKey = "id";
        assertEquals(SchemaHistoryStorePreflight.Action.REFUSE, SchemaHistoryStorePreflight.plan(s).action);
    }

    @Test
    @DisplayName("An ORDER BY clause that cannot be located is refused, never guessed")
    public void unlocatableOrderByIsRefused() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.engineFull = "ReplacingMergeTree(record_insert_seq) PRIMARY KEY id SETTINGS index_granularity = 8192";
        assertEquals(SchemaHistoryStorePreflight.Action.REFUSE, SchemaHistoryStorePreflight.plan(s).action);
    }

    @Test
    @DisplayName("A multi-column sorting key gets history_data_seq appended")
    public void multiColumnKey() {
        SchemaHistoryStorePreflight.TableState s = defaultLayout();
        s.engineFull = "ReplacingMergeTree(record_insert_seq) ORDER BY (id, record_insert_ts) "
                + "SETTINGS index_granularity = 8192";
        s.sortingKey = "id, record_insert_ts";
        SchemaHistoryStorePreflight.Plan plan = SchemaHistoryStorePreflight.plan(s);
        assertEquals(SchemaHistoryStorePreflight.Action.MIGRATE, plan.action);
        assertEquals("ReplacingMergeTree(record_insert_seq) ORDER BY (id, record_insert_ts, history_data_seq) "
                + "SETTINGS index_granularity = 8192", plan.newEngineFull);
    }

    @Test
    @DisplayName("ORDER BY id is matched as a whole clause, not as a prefix of a longer key")
    public void clauseMatchIsExact() {
        assertNull(SchemaHistoryStorePreflight.withSeqInSortingKey(
                "ReplacingMergeTree(v) ORDER BY identifier", "id",
                SchemaHistoryStorePreflight.keyColumns("id")));
    }

    @Test
    @DisplayName("Replicated/Shared prefixes are stripped to the base engine")
    public void baseEngine() {
        assertEquals("ReplacingMergeTree", SchemaHistoryStorePreflight.baseEngine("ReplicatedReplacingMergeTree"));
        assertEquals("ReplacingMergeTree", SchemaHistoryStorePreflight.baseEngine("SharedReplacingMergeTree"));
        assertEquals("MergeTree", SchemaHistoryStorePreflight.baseEngine("MergeTree"));
    }

    @Test
    @DisplayName("The re-key copy groups parts by Debezium's recovery order, a record starting at each part 0")
    public void rekeyStatementShape() {
        String sql = SchemaHistoryStorePreflight.rekeyInsert("`d`.`n`", "`d`.`o`");
        assertTrue(sql.contains("sum(history_data_seq = 0) OVER (ORDER BY record_insert_ts, record_insert_seq, "
                + "id, history_data_seq ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"), sql);
        assertTrue(sql.contains("first_value(id) OVER w AS new_id"), sql);
        assertTrue(sql.startsWith("INSERT INTO `d`.`n`"), sql);
    }

    @Test
    @DisplayName("Not a ClickHouse-backed JdbcSchemaHistory: nothing is touched")
    public void otherStoresAreIgnored() {
        Properties props = new Properties();
        props.setProperty("schema.history.internal", "io.debezium.storage.file.history.FileSchemaHistory");
        assertDoesNotThrow(() -> SchemaHistoryStorePreflight.apply(props));
        props.setProperty("schema.history.internal", SchemaHistoryStorePreflight.JDBC_HISTORY);
        props.setProperty("schema.history.internal.jdbc.url", "jdbc:mysql://127.0.0.1:1/x");
        assertDoesNotThrow(() -> SchemaHistoryStorePreflight.apply(props));
    }

    @Test
    @DisplayName("A ClickHouse that stays unreachable refuses the start instead of skipping the check")
    public void unreachableStoreRefusesTheStart() {
        Properties props = new Properties();
        props.setProperty("schema.history.internal", SchemaHistoryStorePreflight.JDBC_HISTORY);
        props.setProperty("schema.history.internal.jdbc.url", "jdbc:clickhouse://127.0.0.1:1/altinity_sink_connector");
        props.setProperty("schema.history.internal.jdbc.user", "default");
        props.setProperty("schema.history.internal.jdbc.password", "");
        props.setProperty(SchemaHistoryStorePreflight.WAIT_MS_PROPERTY, "0");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SchemaHistoryStorePreflight.apply(props));
        assertTrue(e.getMessage().contains("unverified"), e.getMessage());
    }

    @Test
    @DisplayName("The URL's database is located so a first start can create it")
    public void urlDatabaseParsing() {
        assertEquals("altinity_sink_connector",
                SchemaHistoryStorePreflight.urlDatabase("jdbc:clickhouse://clickhouse:8123/altinity_sink_connector"));
        assertEquals("db", SchemaHistoryStorePreflight.urlDatabase("jdbc:clickhouse://h:8123/db?ssl=false"));
        assertEquals("db", SchemaHistoryStorePreflight.urlDatabase("jdbc:clickhouse:http://h:8123/db"));
        assertNull(SchemaHistoryStorePreflight.urlDatabase("jdbc:clickhouse://clickhouse:8123"));
        assertNull(SchemaHistoryStorePreflight.urlDatabase("jdbc:clickhouse://clickhouse:8123/"));
        assertNull(SchemaHistoryStorePreflight.urlDatabase("jdbc:clickhouse://clickhouse:8123/?x=1"));
        assertEquals("jdbc:clickhouse://clickhouse:8123",
                SchemaHistoryStorePreflight.serverUrl("jdbc:clickhouse://clickhouse:8123/altinity_sink_connector"));
        assertEquals("jdbc:clickhouse://h:8123?ssl=false",
                SchemaHistoryStorePreflight.serverUrl("jdbc:clickhouse://h:8123/db?ssl=false"));
        assertEquals("jdbc:clickhouse://h:8123", SchemaHistoryStorePreflight.serverUrl("jdbc:clickhouse://h:8123"));
    }

    @Test
    @DisplayName("Only ClickHouse's UNKNOWN_DATABASE triggers creating the database")
    public void unknownDatabaseDetection() {
        assertTrue(SchemaHistoryStorePreflight.isUnknownDatabase(new java.sql.SQLException(
                "Code: 81. DB::Exception: Database altinity_sink_connector does not exist. (UNKNOWN_DATABASE)")));
        assertTrue(SchemaHistoryStorePreflight.isUnknownDatabase(new java.sql.SQLException("x", "HY000", 81)));
        assertTrue(SchemaHistoryStorePreflight.isUnknownDatabase(new java.sql.SQLException("wrapped",
                new RuntimeException("Code: 81. DB::Exception: Database d does not exist. (UNKNOWN_DATABASE)"))));
        assertFalse(SchemaHistoryStorePreflight.isUnknownDatabase(new java.sql.SQLException(
                "Connect to http://127.0.0.1:1 failed: Connection refused")));
        assertFalse(SchemaHistoryStorePreflight.isUnknownDatabase(new java.sql.SQLException(
                "Code: 516. DB::Exception: default: Authentication failed")));
    }

    @Test
    @DisplayName("The wait for an unreachable ClickHouse defaults to 5 minutes and is never negative")
    public void waitBound() {
        Properties props = new Properties();
        assertEquals(300_000L, SchemaHistoryStorePreflight.waitMs(props));
        props.setProperty(SchemaHistoryStorePreflight.WAIT_MS_PROPERTY, "-5");
        assertEquals(0L, SchemaHistoryStorePreflight.waitMs(props));
        props.setProperty(SchemaHistoryStorePreflight.WAIT_MS_PROPERTY, "x");
        assertEquals(300_000L, SchemaHistoryStorePreflight.waitMs(props));
    }
}
