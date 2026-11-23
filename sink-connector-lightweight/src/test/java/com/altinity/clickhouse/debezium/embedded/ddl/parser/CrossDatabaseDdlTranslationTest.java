package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEW-J1: a DROP / TRUNCATE / RENAME / ALTER ... RENAME TO that names its table
 * with an explicit database ({@code db2.t}) acts on THAT database. The session
 * database ({@code USE db1}) is only the fallback for an unqualified name.
 * Identifiers are split on the dots outside backticks and quoted when they are
 * not plain identifiers.
 *
 * <p>The tests assert on generated statement text only; nothing is executed.</p>
 */
public class CrossDatabaseDdlTranslationTest {

    private static final String SESSION_DB = "db1";

    private static MySQLDDLParserService parser(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(extra);
        return new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(config)), SESSION_DB);
    }

    private static String translate(MySQLDDLParserService service, String mysqlDdl) {
        StringBuffer out = new StringBuffer();
        service.parseSql(mysqlDdl, SESSION_DB, out, new AtomicBoolean(false));
        return out.toString();
    }

    private static String translate(String mysqlDdl) {
        return translate(parser(new HashMap<>()), mysqlDdl);
    }

    // ------------------------------------------------------------------
    // The helper that splits and quotes table references
    // ------------------------------------------------------------------

    @Test
    @DisplayName("parseTableRef splits on dots outside backticks and strips the outer backticks")
    public void parseTableRefRespectsBackticks() {
        MySqlDDLParserListenerImpl.TableRef plain = MySqlDDLParserListenerImpl.parseTableRef("t");
        assertNull(plain.database);
        assertEquals("t", plain.table);
        assertFalse(plain.tableQuoted);

        MySqlDDLParserListenerImpl.TableRef qualified = MySqlDDLParserListenerImpl.parseTableRef("db2.t");
        assertEquals("db2", qualified.database);
        assertEquals("t", qualified.table);

        MySqlDDLParserListenerImpl.TableRef dotted = MySqlDDLParserListenerImpl.parseTableRef("`my.db`.`t.1`");
        assertEquals("my.db", dotted.database);
        assertEquals("t.1", dotted.table);
        assertTrue(dotted.tableQuoted);

        MySqlDDLParserListenerImpl.TableRef dottedTableOnly = MySqlDDLParserListenerImpl.parseTableRef("`a.b`");
        assertNull(dottedTableOnly.database);
        assertEquals("a.b", dottedTableOnly.table);

        MySqlDDLParserListenerImpl.TableRef escaped = MySqlDDLParserListenerImpl.parseTableRef("`we``ird`");
        assertEquals("we`ird", escaped.table);
    }

    @Test
    @DisplayName("quoteIdentifier quotes only when forced or not a plain identifier, escaping backticks")
    public void quoteIdentifierQuotesWhenNeeded() {
        assertEquals("t", MySqlDDLParserListenerImpl.quoteIdentifier("t", false));
        assertEquals("`t`", MySqlDDLParserListenerImpl.quoteIdentifier("t", true));
        assertEquals("`a.b`", MySqlDDLParserListenerImpl.quoteIdentifier("a.b", false));
        assertEquals("`my-db`", MySqlDDLParserListenerImpl.quoteIdentifier("my-db", false));
        assertEquals("`1st`", MySqlDDLParserListenerImpl.quoteIdentifier("1st", false));
        assertEquals("`we``ird`", MySqlDDLParserListenerImpl.quoteIdentifier("we`ird", false));
    }

    // ------------------------------------------------------------------
    // DROP TABLE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("USE db1; DROP TABLE db2.t drops db2.t, not db1.t")
    public void dropTableKeepsExplicitDatabase() {
        // DESTRUCTIVE: none -- string assertions on the translator output; no database is contacted.
        assertEquals("DROP TABLE if exists db2.t", translate("DROP TABLE db2.t"));
        assertEquals("DROP TABLE if exists db2.t", translate("DROP TABLE `db2`.t"));
    }

    @Test
    @DisplayName("An unqualified DROP TABLE falls back to the session database")
    public void dropTableUnqualifiedUsesSessionDatabase() {
        // DESTRUCTIVE: none -- string assertion on the translator output; no database is contacted.
        assertEquals("DROP TABLE if exists db1.t", translate("DROP TABLE t"));
    }

    @Test
    @DisplayName("DROP TABLE with a dotted backticked table is not cut at the inner dot")
    public void dropTableBacktickedNamesWithDots() {
        // DESTRUCTIVE: none -- string assertions on the translator output; no database is contacted.
        assertEquals("DROP TABLE if exists `my.db`.`t.1`", translate("DROP TABLE `my.db`.`t.1`"));
        assertEquals("DROP TABLE if exists db1.`a.b`", translate("DROP TABLE `a.b`"));
    }

    @Test
    @DisplayName("DROP TABLE of several tables resolves each table's own database")
    public void dropTableMultipleTablesResolveEachDatabase() {
        // DESTRUCTIVE: none -- string assertion on the translator output; no database is contacted.
        assertEquals("DROP TABLE if exists db2.a,db1.b,db3.c", translate("DROP TABLE db2.a, b, db3.c"));
    }

    @Test
    @DisplayName("DROP TABLE db2.t maps db2 through clickhouse.database.override.map")
    public void dropTableAppliesDatabaseOverrideMap() {
        Map<String, String> extra = new HashMap<>();
        extra.put(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(), "db2:ch2");
        // DESTRUCTIVE: none -- string assertion on the translator output; no database is contacted.
        assertEquals("DROP TABLE if exists ch2.t", translate(parser(extra), "DROP TABLE db2.t"));
    }

    // ------------------------------------------------------------------
    // TRUNCATE TABLE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("USE db1; TRUNCATE TABLE db2.t truncates db2.t, not db1.t")
    public void truncateTableKeepsExplicitDatabase() {
        // DESTRUCTIVE: none -- string assertions on the translator output; no database is contacted.
        assertEquals("TRUNCATE TABLE `db2`.t", translate("TRUNCATE TABLE db2.t"));
        assertEquals("TRUNCATE TABLE `db1`.t", translate("TRUNCATE TABLE t"));
    }

    @Test
    @DisplayName("TRUNCATE TABLE with dotted backticked names is not cut at the inner dot")
    public void truncateTableBacktickedNamesWithDots() {
        // DESTRUCTIVE: none -- string assertion on the translator output; no database is contacted.
        assertEquals("TRUNCATE TABLE `my.db`.`t.1`", translate("TRUNCATE TABLE `my.db`.`t.1`"));
    }

    @Test
    @DisplayName("Replication-history mode keeps resolving every table to the history database")
    public void historyModeIgnoresExplicitDatabase() {
        Map<String, String> extra = new HashMap<>();
        extra.put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(), "true");
        MySQLDDLParserService history = parser(extra);
        // DESTRUCTIVE: none -- string/plan assertions on the translator; no database is contacted.
        assertEquals("", translate(history, "TRUNCATE TABLE `db2`.`t2`"));
        List<MySqlDDLParserListenerImpl.HistoryBulkClose> requests = history.historyBulkCloses();
        assertEquals(1, requests.size());
        assertEquals(SESSION_DB, requests.get(0).database());
        assertEquals("t2", requests.get(0).table());
    }

    // ------------------------------------------------------------------
    // RENAME TABLE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RENAME TABLE db2.a TO db3.b keeps each side's database")
    public void renameTableKeepsEachSideDatabase() {
        assertEquals("RENAME TABLE if exists db2.a to db3.b", translate("RENAME TABLE db2.a TO db3.b"));
    }

    @Test
    @DisplayName("RENAME TABLE quotes dotted backticked names instead of cutting them")
    public void renameTableBacktickedNamesWithDots() {
        assertEquals("RENAME TABLE if exists `my.db`.`a.1` to db3.`b.2`",
                translate("RENAME TABLE `my.db`.`a.1` TO db3.`b.2`"));
    }

    @Test
    @DisplayName("RENAME TABLE a TO b falls back to the session database on both sides")
    public void renameTableUnqualifiedUsesSessionDatabase() {
        assertEquals("RENAME TABLE if exists db1.a to db1.b", translate("RENAME TABLE a TO b"));
    }

    // ------------------------------------------------------------------
    // ALTER TABLE ... RENAME TO
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ALTER TABLE t RENAME TO db2.t2 moves the table to db2")
    public void alterRenameToKeepsExplicitDatabase() {
        assertEquals("RENAME TABLE IF EXISTS `db1`.t TO `db2`.t2", translate("ALTER TABLE t RENAME TO db2.t2"));
    }

    @Test
    @DisplayName("ALTER TABLE db2.t RENAME TO t2 renames inside db2")
    public void alterRenameToUnqualifiedStaysInTheTablesDatabase() {
        assertEquals("RENAME TABLE IF EXISTS `db2`.t TO `db2`.t2", translate("ALTER TABLE db2.t RENAME TO t2"));
    }

    @Test
    @DisplayName("ALTER TABLE ... RENAME TO with dotted backticked names is not cut at the inner dot")
    public void alterRenameToBacktickedNamesWithDots() {
        assertEquals("RENAME TABLE IF EXISTS `my.db`.`t.1` TO `my.db`.`t.2`",
                translate("ALTER TABLE `my.db`.`t.1` RENAME TO `t.2`"));
    }

    @Test
    @DisplayName("ALTER TABLE db2.t ADD COLUMN targets db2.t, and a following statement is unaffected")
    public void alterTableHeaderKeepsExplicitDatabaseAndRestoresSession() {
        MySQLDDLParserService service = parser(new HashMap<>());
        assertEquals("ALTER TABLE `db2`.t ADD COLUMN IF NOT EXISTS c Nullable(Int32)",
                translate(service, "ALTER TABLE db2.t ADD COLUMN c INT").trim());
        // DESTRUCTIVE: none -- string assertion on the translator output; no database is contacted.
        assertEquals("TRUNCATE TABLE `db1`.t", translate(service, "TRUNCATE TABLE t"));
    }
}
