package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DDLReplicationException;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Translator-side failure modes of the DDL path (Spec 06.03 / 06.04 / 06.05 /
 * 06.07 section 6). Pure unit tests: no ClickHouse, the target-schema lookup
 * talks to a JDBC proxy that fails like an unreachable server.
 */
public class DdlTranslationFailureModesTest {

    @BeforeAll
    static void engine() {
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(MySQLDDLParserService service, String sql) {
        StringBuffer out = new StringBuffer();
        service.parseSql(sql, "", out, new AtomicBoolean(false));
        return out.toString();
    }

    private static MySQLDDLParserService plainService() {
        return new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "db");
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
        return null;
    }

    /** A connection on which every query fails, as when ClickHouse is briefly unreachable. */
    private static Connection failingConnection() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "close":
                    return null;
                case "prepareStatement":
                case "createStatement":
                    throw new SQLException("simulated: connection reset while reading system.columns");
                case "toString":
                    return "failing-connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /** A parser whose PRODUCTION target-schema lookup reads through a failing connection. */
    private static MySQLDDLParserService serviceWithFailingLookup() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);
        BaseDbWriter writer = new BaseDbWriter("localhost", 8123, "system", "default", "", config,
                failingConnection());
        return new MySQLDDLParserService(writer, config, "db");
    }

    /**
     * FM-06.03-1: a statement the grammar rejects is refused by
     * {@code ErrorListenerImpl} with a RuntimeException ("Error parsing DDL")
     * -- never translated to a partial or empty statement that would be
     * acknowledged as applied.
     */
    @Test
    @DisplayName("FM-06.03-1: an unparseable statement throws instead of translating to nothing")
    public void unparseableStatementThrows() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> translate(plainService(), "ALTER TABLE t FROBNICATE COLUMN c"));
        assertEquals("Error parsing DDL", e.getMessage());
    }

    /**
     * FM-06.03-3: statement kinds the listener has no callback for (views,
     * stand-alone index statements) parse and translate to the empty string,
     * which {@code executeDDL} skips; they hold no rows, so nothing is lost.
     */
    @Test
    @DisplayName("FM-06.03-3: statement kinds without a listener callback translate to nothing")
    public void nonReplicatedStatementKindsTranslateToNothing() {
        MySQLDDLParserService service = plainService();
        assertEquals("", translate(service, "CREATE VIEW v AS SELECT id FROM t"));
        assertEquals("", translate(service, "CREATE INDEX idx_a ON t (a)"));
        // DESTRUCTIVE: statement text only -- parsed and translated in memory; nothing is executed against any database.
        assertEquals("", translate(service, "DROP INDEX idx_a ON t"));
        assertEquals("", translate(service, "DROP VIEW IF EXISTS v"));
    }

    /**
     * FM-06.03-2 control: partition maintenance that moves no row between
     * the table and anything else (ADD / REORGANIZE / COALESCE / ANALYZE /
     * OPTIMIZE PARTITION) is loss-free to skip, and is skipped.
     */
    @Test
    @DisplayName("FM-06.03-2 control: data-preserving partition maintenance is skipped")
    public void dataPreservingPartitionOperationIsSkipped() {
        MySQLDDLParserService service = plainService();
        assertEquals("", translate(service,
                "ALTER TABLE t ADD PARTITION (PARTITION p9 VALUES LESS THAN (100))"));
        assertEquals("", translate(service,
                "ALTER TABLE t REORGANIZE PARTITION p0 INTO (PARTITION p0a VALUES LESS THAN (5), "
                        + "PARTITION p0b VALUES LESS THAN (10))"));
        assertEquals("", translate(service, "ALTER TABLE t COALESCE PARTITION 2"));
        assertEquals("", translate(service, "ALTER TABLE t ANALYZE PARTITION p0"));
        assertEquals("", translate(service, "ALTER TABLE t OPTIMIZE PARTITION p0"));
    }

    /**
     * FM-06.03-2 (DEFECT). {@code DROP PARTITION}, {@code TRUNCATE PARTITION},
     * {@code EXCHANGE PARTITION ... WITH TABLE} and {@code IMPORT TABLESPACE}
     * remove or replace ROWS on the source, and the binlog carries only the
     * statement, never a row event. Classified "not representable,
     * loss-free" by {@code isNoOpSpecification}, they are skipped at INFO and
     * acknowledged: the replica keeps rows the source no longer has, or
     * misses rows it received, count-clean and permanently. The correct
     * outcome is a loud refusal naming the re-synchronisation of the table
     * (Spec 11.04).
     */
    @Test
    @Disabled("DEFECT FM-06.03-2: data-changing partition/tablespace operations are skipped as if loss-free")
    @DisplayName("FM-06.03-2: data-changing partition operations are refused loudly, not skipped")
    public void dataChangingPartitionOperationIsLoud() {
        MySQLDDLParserService service = plainService();
        for (String ddl : new String[]{
                // DESTRUCTIVE: statement text only -- parsed and translated in memory; nothing is executed against any database.
                "ALTER TABLE t DROP PARTITION p0",
                "ALTER TABLE t TRUNCATE PARTITION p0",
                "ALTER TABLE t EXCHANGE PARTITION p0 WITH TABLE t_archive",
                "ALTER TABLE t IMPORT TABLESPACE"}) {
            assertThrows(DDLReplicationException.class, () -> translate(service, ddl),
                    "[" + ddl + "] changes rows that no row event will ever carry; skipping it diverges silently");
        }
    }

    /**
     * FM-06.07-3 (DEFECT). When the sorting-key lookup FAILS (ClickHouse
     * unreachable, query timeout), {@code DBMetadata.getSortingKeyColumns}
     * and {@code MetadataTargetSchemaLookup} answer "empty", which
     * {@code enforcePrimaryKeyPolicy} cannot tell apart from "no key known"
     * (rule 1): the identity change is skipped at INFO, no rebuild is planned
     * and the statement is acknowledged. The replica keeps the old key and
     * collapses rows the source keeps distinct. A failed lookup must be loud.
     */
    @Test
    @Disabled("DEFECT FM-06.07-3: a failed sorting-key lookup is treated as 'unknown key' and the PK change is skipped")
    @DisplayName("FM-06.07-3: a PRIMARY KEY change whose target-key lookup failed is refused, not skipped")
    public void failedSortingKeyLookupIsLoudNotUnknown() {
        MySQLDDLParserService service = serviceWithFailingLookup();
        assertThrows(DDLReplicationException.class,
                () -> translate(service, "ALTER TABLE t DROP PRIMARY KEY, ADD PRIMARY KEY (b)"),
                "the replica's key could not be read, so whether the identity changes is unknown");
    }

    /**
     * FM-06.07-3, what 2.11.0 does today (pins the silent outcome the
     * disabled test above rejects): nothing emitted, no rebuild planned, no
     * exception.
     */
    @Test
    @DisplayName("FM-06.07-3 current behaviour: a failed key lookup skips the PRIMARY KEY change silently")
    public void failedSortingKeyLookupSkipsPrimaryKeyChangeToday() {
        MySQLDDLParserService service = serviceWithFailingLookup();
        assertEquals("", translate(service, "ALTER TABLE t DROP PRIMARY KEY, ADD PRIMARY KEY (b)"));
        assertNull(service.primaryKeyRebuildPlan(), "no rebuild is planned when the key could not be read");
    }

    /**
     * FM-06.05-2 (DEFECT). A failed nullability lookup makes
     * {@code MODIFY COLUMN c BIGINT NOT NULL} of an existing non-Nullable
     * column come out as {@code Nullable(Int64)} (the unknown-schema rule):
     * a real type change on ClickHouse (a column rewrite), away from what
     * the source declares, decided by a transient network error.
     */
    @Test
    @Disabled("DEFECT FM-06.05-2: a failed nullability lookup silently falls back to Nullable")
    @DisplayName("FM-06.05-2: a MODIFY whose nullability lookup failed is refused, not widened to Nullable")
    public void failedNullabilityLookupIsLoudNotNullable() {
        MySQLDDLParserService service = serviceWithFailingLookup();
        assertThrows(DDLReplicationException.class,
                () -> translate(service, "ALTER TABLE t MODIFY COLUMN c BIGINT NOT NULL"));
    }

    /**
     * FM-06.05-2, what 2.11.0 does today: the failed lookup yields the
     * unknown-schema translation, {@code Nullable(Int64)}.
     */
    @Test
    @DisplayName("FM-06.05-2 current behaviour: a failed nullability lookup yields Nullable")
    public void failedNullabilityLookupYieldsNullableToday() {
        String q = translate(serviceWithFailingLookup(), "ALTER TABLE t MODIFY COLUMN c BIGINT NOT NULL");
        assertTrue(q.contains("MODIFY COLUMN c Nullable(Int64)"), q);
    }

    /**
     * FM-06.06-1 (DEFECT). {@code extractGeneratedExpression} renders the
     * generation expression with ANTLR {@code getText()}, which concatenates
     * tokens without the whitespace between them: {@code CASE WHEN a > 0 THEN
     * 1 ELSE 0 END} becomes {@code CASEWHENa>0THEN1ELSE0END}. ClickHouse
     * refuses the CREATE/ALTER, and the pipeline stops on a statement MySQL
     * accepted. Any expression with a keyword operator ({@code DIV},
     * {@code AND}, {@code IS NULL}, {@code CASE}, {@code INTERVAL}) is hit.
     */
    @Test
    @Disabled("DEFECT FM-06.06-1: getText() drops the whitespace between tokens of a generation expression")
    @DisplayName("FM-06.06-1: a generation expression keeps its token boundaries")
    public void generatedExpressionKeepsTokenBoundaries() {
        String q = translate(plainService(),
                "ALTER TABLE t ADD COLUMN g INT GENERATED ALWAYS AS (CASE WHEN a > 0 THEN 1 ELSE 0 END) STORED");
        assertTrue(q.toUpperCase().contains("CASE WHEN"), q);
    }

    /** FM-06.06-1, what 2.11.0 emits today. */
    @Test
    @DisplayName("FM-06.06-1 current behaviour: keyword tokens of a generation expression are glued together")
    public void generatedExpressionTokensAreGluedToday() {
        String q = translate(plainService(),
                "ALTER TABLE t ADD COLUMN g INT GENERATED ALWAYS AS (CASE WHEN a > 0 THEN 1 ELSE 0 END) STORED");
        assertTrue(q.toUpperCase().contains("CASEWHEN"), q);
    }

    /**
     * FM-06.04-4 (DEFECT). With {@code auto.create.tables.replicated=true}
     * the translator creates tables and databases {@code ON CLUSTER}, but
     * emits {@code DROP TABLE}, {@code RENAME TABLE}, {@code CREATE TABLE ...
     * AS} and {@code DROP DATABASE} without it. On an Atomic database those
     * run on the connector's server only: the other replicas keep the old
     * name / the dropped table, and the next {@code CREATE ... ON CLUSTER IF
     * NOT EXISTS} is a no-op there.
     */
    @Test
    // DESTRUCTIVE: the annotation text only names statement kinds; nothing is executed against any database.
    @Disabled("DEFECT FM-06.04-4: DROP/RENAME/CREATE LIKE/DROP DATABASE lack ON CLUSTER in replicated mode")
    @DisplayName("FM-06.04-4: replicated mode issues every table-level statement ON CLUSTER")
    public void replicatedModeTableStatementsRunOnCluster() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(), "true");
        MySQLDDLParserService service = new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(props), "db");
        for (String ddl : new String[]{
                "RENAME TABLE t TO t_old, t_new TO t",
                // DESTRUCTIVE: statement text only -- parsed and translated in memory; nothing is executed against any database.
                "DROP TABLE t_old",
                "CREATE TABLE t2 LIKE t",
                "DROP DATABASE db"}) {
            String q = translate(service, ddl);
            assertTrue(q.contains("ON CLUSTER"), "[" + ddl + "] -> [" + q + "] runs on one replica only");
        }
    }

    /**
     * FM-06.04-4, what 2.11.0 does today: CREATE TABLE carries ON CLUSTER,
     * RENAME TABLE and DROP TABLE do not.
     */
    @Test
    @DisplayName("FM-06.04-4 current behaviour: only CREATE carries ON CLUSTER in replicated mode")
    public void replicatedModeOnlyCreateCarriesOnClusterToday() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(), "true");
        MySQLDDLParserService service = new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(props), "db");
        assertTrue(translate(service, "CREATE TABLE t (id INT NOT NULL PRIMARY KEY)").contains("ON CLUSTER"));
        assertTrue(!translate(service, "RENAME TABLE t TO t_old").contains("ON CLUSTER"));
        // DESTRUCTIVE: statement text only -- parsed and translated in memory; nothing is executed against any database.
        assertTrue(!translate(service, "DROP TABLE t_old").contains("ON CLUSTER"));
    }
}
