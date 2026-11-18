package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.ddl.DdlColumnFilter;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A column kept out by {@code column.include.list} / {@code column.exclude.list}
 * is not created either, on either DDL path.
 *
 * <p>Before the fix the lists were honoured only by the record path: Debezium
 * dropped the value, but the schema-DDL path translated the raw source
 * {@code CREATE TABLE} text, so the column was still created in ClickHouse --
 * empty. With {@code enable.snapshot.ddl=true} that also created the source
 * tables that hold no rows, which is the only way the connector creates them
 * at all, so a filtered empty table arrived with every one of its excluded
 * columns.</p>
 *
 * <p>The tables below are the ones from the bug report: a source database
 * {@code app} with {@code users(id, name, password)} and
 * {@code orders(id, user_id, total)}, and the list
 * {@code app.users.(id|name),app.orders.(id|user_id)}.</p>
 */
public class DdlColumnFilterEmissionTest {

    /** The configuration from the report. */
    private static final String REPORTED_LIST = "app.users.(id|name),app.orders.(id|user_id)";

    /** The same table, with {@code nickname} kept so a kept ADD can be tested. */
    private static final String NICKNAME_LIST = "app.users.(id|name|nickname)";

    /** The SOURCE database the lists are written against. */
    private static final String SOURCE_DATABASE = "app";

    @BeforeAll
    public static void setUp() {
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    /** A translator for destination database {@code db} with the given column list. */
    private static MySQLDDLParserService service(String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        MySQLDDLParserService service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>())), "db");
        service.setColumnFilter(DdlColumnFilter.from(props), SOURCE_DATABASE);
        return service;
    }

    private static String translate(MySQLDDLParserService service, String sql) {
        StringBuffer out = new StringBuffer();
        service.parseSql(sql, "t", out);
        return out.toString().trim();
    }

    private static final String USERS =
            "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), password VARCHAR(100))";

    // ------------------------------------------------------------------
    // CREATE TABLE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("With no column list the CREATE keeps every column, as before")
    public void noListCreatesEveryColumn() {
        String emitted = translate(service(), USERS);
        assertTrue(emitted.contains("id "), emitted);
        assertTrue(emitted.contains("name "), emitted);
        assertTrue(emitted.contains("password "), emitted);
    }

    @Test
    @DisplayName("column.include.list: the excluded column is not created (the reported defect)")
    public void includeListKeepsTheExcludedColumnOutOfTheCreate() {
        String emitted = translate(service("column.include.list", REPORTED_LIST), USERS);

        assertTrue(emitted.contains("id "), "id is listed and must be created: " + emitted);
        assertTrue(emitted.contains("name "), "name is listed and must be created: " + emitted);
        assertFalse(emitted.contains("password"),
                "password is not in the list, so the ClickHouse table must not carry it: " + emitted);
        assertTrue(emitted.contains("ORDER BY id"),
                "dropping a column must not disturb the sorting key: " + emitted);
    }

    @Test
    @DisplayName("The second table of the reported list is filtered by its own entry")
    public void includeListFiltersPerTable() {
        String emitted = translate(service("column.include.list", REPORTED_LIST),
                "CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, total DECIMAL(10,2))");

        assertTrue(emitted.contains("id "), emitted);
        assertTrue(emitted.contains("user_id "), emitted);
        assertFalse(emitted.contains("total"),
                "app.orders lists only id and user_id: " + emitted);
    }

    @Test
    @DisplayName("A column-level PRIMARY KEY column the list excludes is still created, with a warning")
    public void keyColumnIsAlwaysCreated() {
        // app.users lists only name; id is the sorting key and cannot go. It is
        // declared `id INT PRIMARY KEY`, a column-level key, so the identity of
        // the table must be read from the column declaration itself.
        String emitted = translate(service("column.include.list", "app.users.name"), USERS);

        assertTrue(emitted.contains("id "),
                "id is in ORDER BY, so filtering it out would reference a missing column: " + emitted);
        assertTrue(emitted.contains("name "), emitted);
        assertFalse(emitted.contains("password"), emitted);
        assertTrue(emitted.contains("ORDER BY id"),
                "the table must keep the key it was created with, not be re-keyed on name: " + emitted);
    }

    @Test
    @DisplayName("A CREATE with no matching column is created unfiltered, never empty")
    public void noMatchingColumnCreatesEveryColumn() {
        // A list that names no column of this table: an empty column set cannot
        // be translated at all (the sorting-key policy refuses it), and the same
        // include list means Debezium replicates no row of the table either.
        String emitted = translate(service("column.include.list", "app.other.(x)"), USERS);

        assertTrue(emitted.contains("id "), emitted);
        assertTrue(emitted.contains("name "), emitted);
        assertTrue(emitted.contains("password "),
                "an unfiltered CREATE keeps the source schema: " + emitted);
    }

    @Test
    @DisplayName("column.exclude.list: only the named column is left out")
    public void excludeListKeepsTheNamedColumnOutOfTheCreate() {
        String emitted = translate(service("column.exclude.list", "app.users.password"), USERS);

        assertTrue(emitted.contains("id "), emitted);
        assertTrue(emitted.contains("name "), emitted);
        assertFalse(emitted.contains("password"), emitted);
    }

    @Test
    @DisplayName("The lists name the SOURCE database, never the destination one")
    public void destinationDatabaseDoesNotMatch() {
        // The destination is db, the source app. An entry written against the
        // destination name matches nothing, so nothing is filtered.
        String emitted = translate(service("column.include.list", "db.users.(id|name)"), USERS);
        assertTrue(emitted.contains("password "),
                "the list names the destination database, not the source table: " + emitted);
    }

    // ------------------------------------------------------------------
    // ALTER TABLE
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN of an excluded column emits nothing")
    public void alterAddOfAnExcludedColumnEmitsNothing() {
        assertEquals("", translate(service("column.include.list", REPORTED_LIST),
                "ALTER TABLE users ADD COLUMN password VARCHAR(100)"));
    }

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN of a kept column is unaffected")
    public void alterAddOfAKeptColumnIsEmitted() {
        String emitted = translate(service("column.include.list", NICKNAME_LIST),
                "ALTER TABLE users ADD COLUMN nickname VARCHAR(30)");
        assertTrue(emitted.contains("ADD COLUMN IF NOT EXISTS nickname"),
                "a column the list keeps must still be added: " + emitted);
    }

    @Test
    @DisplayName("A parenthesised ADD list adds only the kept columns")
    public void alterAddListSkipsOnlyTheExcludedColumn() {
        String emitted = translate(service("column.include.list", NICKNAME_LIST),
                "ALTER TABLE users ADD COLUMN (nickname VARCHAR(30), password VARCHAR(100))");
        assertTrue(emitted.contains("ADD COLUMN IF NOT EXISTS nickname"), emitted);
        assertFalse(emitted.contains("password"), emitted);
    }

    @Test
    @DisplayName("ALTER TABLE DROP COLUMN of an excluded column emits nothing")
    public void alterDropOfAnExcludedColumnEmitsNothing() {
        // DESTRUCTIVE: none -- the MySQL DDL below is text handed to the
        // translator in memory; nothing is executed against a database. The
        // ClickHouse table never had the column, so there is nothing to drop
        // and nothing to record in a rebuild plan.
        assertEquals("", translate(service("column.include.list", REPORTED_LIST),
                "ALTER TABLE users DROP COLUMN password"));
    }

    @Test
    @DisplayName("ALTER TABLE DROP COLUMN of a kept column is unaffected")
    public void alterDropOfAKeptColumnIsEmitted() {
        // DESTRUCTIVE: none -- the MySQL DDL below is text handed to the
        // translator in memory; nothing is executed against a database. The
        // assertions are on the ClickHouse string the translator emits.
        String emitted = translate(service("column.include.list", REPORTED_LIST),
                "ALTER TABLE users DROP COLUMN name");
        assertTrue(emitted.contains("DROP COLUMN IF EXISTS name"), emitted);
    }

    @Test
    @DisplayName("ALTER TABLE RENAME COLUMN of an excluded column emits nothing")
    public void alterRenameOfAnExcludedColumnEmitsNothing() {
        assertEquals("", translate(service("column.include.list", REPORTED_LIST),
                "ALTER TABLE users RENAME COLUMN password TO passwd"));
    }

    @Test
    @DisplayName("ALTER TABLE RENAME COLUMN of a kept column is unaffected")
    public void alterRenameOfAKeptColumnIsEmitted() {
        String emitted = translate(service("column.include.list", REPORTED_LIST),
                "ALTER TABLE users RENAME COLUMN name TO full_name");
        assertTrue(emitted.toLowerCase().contains("rename column if exists name"), emitted);
        assertTrue(emitted.contains("full_name"), emitted);
    }

    @Test
    @DisplayName("ALTER TABLE MODIFY COLUMN of an excluded column emits nothing")
    public void alterModifyOfAnExcludedColumnEmitsNothing() {
        assertEquals("", translate(service("column.include.list", REPORTED_LIST),
                "ALTER TABLE users MODIFY COLUMN password VARCHAR(200)"));
    }

    @Test
    @DisplayName("ALTER TABLE MODIFY COLUMN of a kept column is unaffected")
    public void alterModifyOfAKeptColumnIsEmitted() {
        String emitted = translate(service("column.include.list", REPORTED_LIST),
                "ALTER TABLE users MODIFY COLUMN name VARCHAR(120)");
        // A plain user MODIFY stays unguarded on purpose (Spec 06.04 section 3.1).
        assertTrue(emitted.contains("MODIFY COLUMN name"), emitted);
    }

    @Test
    @DisplayName("A skipped clause never takes its neighbour with it")
    public void skippedClauseLeavesItsNeighbourIntact() {
        String emitted = translate(service("column.include.list", NICKNAME_LIST),
                "ALTER TABLE users ADD COLUMN password VARCHAR(100), ADD COLUMN nickname VARCHAR(30)");
        assertTrue(emitted.contains("ADD COLUMN IF NOT EXISTS nickname"),
                "the kept clause must survive its skipped neighbour: " + emitted);
        assertFalse(emitted.contains("password"), emitted);
        assertFalse(emitted.contains(",,"), "no doubled separator: " + emitted);
    }
}
