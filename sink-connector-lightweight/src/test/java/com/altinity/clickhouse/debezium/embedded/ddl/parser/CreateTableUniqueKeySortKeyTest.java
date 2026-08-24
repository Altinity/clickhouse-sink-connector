package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * A MySQL table may declare a UNIQUE key but no PRIMARY KEY. Such a table was
 * created in ClickHouse as {@code ReplacingMergeTree(...) ORDER BY tuple()}:
 * with an empty sorting key every row compares equal, so ReplacingMergeTree
 * collapses the whole table down to a single row.
 *
 * <p>Measured on the connector before this fix (MySQL 8.0.36 ROW/FULL/GTID ->
 * ClickHouse 24.8.14): a five-row table with a UNIQUE key and no PRIMARY KEY
 * arrived as ONE row. Total, silent data loss.</p>
 *
 * <p>The UNIQUE key is the source's stable row identity, which is precisely
 * what the sorting key must be, so it is used when no PRIMARY KEY exists.</p>
 *
 * <p>This javadoc previously asserted that ordering by all data columns is "NOT
 * a valid alternative", on the grounds that an UPDATE then writes a different
 * sorting key so both versions survive FINAL. The duplication is real, but the
 * conclusion was wrong: it follows from emitting only the after-image, not from
 * the choice of sorting key. Tombstoning the before-image when the key changes
 * eliminates it, and that is how tables with neither a PRIMARY KEY nor a UNIQUE
 * key are now handled -- see {@link CreateTableNoKeySortKeyTest}. A UNIQUE key
 * is still preferred here: it is a narrower and more meaningful identity than
 * the whole row.</p>
 */
public class CreateTableUniqueKeySortKeyTest {

    private static MySQLDDLParserService mySQLDDLParserService;

    @BeforeAll
    static public void init() {
        mySQLDDLParserService = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    /**
     * Column-level UNIQUE (`uk INT NOT NULL UNIQUE`) with no PRIMARY KEY.
     * This is the exact shape that reproduced the 5-rows-to-1 collapse.
     */
    @Test
    public void testColumnLevelUniqueKeyUsedAsSortKeyWhenNoPrimaryKey() {
        String createQuery = "CREATE TABLE nopk_ddl (uk INT NOT NULL UNIQUE, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_ddl", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertFalse("ORDER BY tuple() collapses every row into one",
                query.toLowerCase().contains("order by tuple()"));
        Assert.assertTrue("UNIQUE key must become the sorting key, was: " + query,
                query.toLowerCase().contains("order by uk"));
    }

    /**
     * Table-level UNIQUE KEY (...) with no PRIMARY KEY.
     */
    @Test
    public void testTableLevelUniqueKeyUsedAsSortKeyWhenNoPrimaryKey() {
        String createQuery = "CREATE TABLE nopk_tbl (uk INT NOT NULL, v VARCHAR(64), "
                + "UNIQUE KEY uk_idx (uk)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_tbl", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertFalse("ORDER BY tuple() collapses every row into one",
                query.toLowerCase().contains("order by tuple()"));
        Assert.assertTrue("UNIQUE key must become the sorting key, was: " + query,
                query.toLowerCase().contains("order by") && query.contains("uk"));
    }

    /**
     * A composite table-level UNIQUE KEY must be used in full: keying on only
     * part of it would merge genuinely distinct rows.
     */
    @Test
    public void testCompositeUniqueKeyUsedInFull() {
        String createQuery = "CREATE TABLE nopk_composite (a INT NOT NULL, b INT NOT NULL, "
                + "v VARCHAR(64), UNIQUE KEY ab_idx (a, b)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_composite", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertFalse("ORDER BY tuple() collapses every row into one",
                query.toLowerCase().contains("order by tuple()"));
        Assert.assertTrue("both UNIQUE key columns must be in the sorting key, was: " + query,
                query.contains("a") && query.contains("b"));
    }

    /**
     * Regression guard: when a PRIMARY KEY exists it always wins, and a UNIQUE
     * key present alongside it must not alter the sorting key.
     */
    @Test
    public void testPrimaryKeyWinsOverUniqueKey() {
        String createQuery = "CREATE TABLE pk_and_uk (id INT NOT NULL PRIMARY KEY, "
                + "uk INT NOT NULL UNIQUE, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "pk_and_uk", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertTrue("PRIMARY KEY must remain the sorting key, was: " + query,
                query.toLowerCase().contains("order by id"));
    }

    /**
     * A table with neither a PRIMARY KEY nor a UNIQUE key.
     *
     * <p>This test previously asserted the OPPOSITE -- that such a table "must
     * still fall back to ORDER BY tuple()", reasoning that inventing a key
     * would trade collapse for permanent duplication on UPDATE. That reasoning
     * was wrong twice over. ORDER BY tuple() is not a safe fallback but a total
     * data loss: ReplacingMergeTree keeps ONE row for the entire table. And the
     * duplication it feared comes from emitting only the after-image, which the
     * writer-side tombstone now handles.</p>
     *
     * <p>Measured on ClickHouse 24.8.14 against MySQL 8.0.36: five distinct
     * rows in such a table arrived as one, and deleting one row of three
     * emptied the table completely. See {@link CreateTableNoKeySortKeyTest}.</p>
     */
    @Test
    public void testNoKeyAtAllUsesAllColumnsSortKey() {
        String createQuery = "CREATE TABLE nokey (a INT, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertFalse("ORDER BY tuple() collapses the whole table to one row, was: "
                        + clickHouseQuery, query.contains("order by tuple()"));
        Assert.assertTrue("all columns must become the sorting key, was: " + clickHouseQuery,
                query.contains("order by (a,v)"));
    }

    /**
     * A UNIQUE key over a NULLABLE column, with no PRIMARY KEY.
     *
     * <p>MySQL permits any number of NULLs in a UNIQUE index, so unlike a
     * PRIMARY KEY -- whose columns MySQL forces to NOT NULL -- a UNIQUE key may
     * name nullable columns. ClickHouse rejects a nullable sorting key outright
     * with {@code Code: 44 ILLEGAL_COLUMN} unless {@code allow_nullable_key} is
     * enabled, so the emitted DDL must carry that setting.</p>
     *
     * <p>Every other test in this class declares its UNIQUE column
     * {@code NOT NULL}, which is exactly why the missing setting went
     * unnoticed: the generated DDL is only rejected once a nullable column
     * reaches the key.</p>
     *
     * <p>Measured on the connector before this fix (MySQL 8.0.36 ROW/FULL/GTID
     * -&gt; ClickHouse 24.8.14): the CREATE TABLE failed with Code: 44, and
     * because DDL is retried indefinitely the failure stalled the ENTIRE
     * replication stream -- every table behind it, not merely the one that
     * failed. The five-row table never appeared and three later tables were
     * never created at all.</p>
     */
    @Test
    public void testNullableUniqueKeyEmitsAllowNullableKey() {
        String createQuery = "CREATE TABLE nopk_nullable_uk (a INT, v VARCHAR(64), "
                + "UNIQUE KEY uk_idx (a)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_nullable_uk", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertTrue("UNIQUE key must become the sorting key, was: " + clickHouseQuery,
                query.contains("order by (a)") || query.contains("order by a"));
        Assert.assertTrue("a nullable UNIQUE-key column needs allow_nullable_key, or ClickHouse "
                        + "rejects the CREATE TABLE with Code: 44 and the indefinite DDL retry "
                        + "stalls replication, was: " + clickHouseQuery,
                query.contains("allow_nullable_key=1"));
    }

    /**
     * A composite UNIQUE key with a nullable member needs the setting too: one
     * nullable column anywhere in the sorting key is enough for Code: 44.
     */
    @Test
    public void testPartiallyNullableCompositeUniqueKeyEmitsAllowNullableKey() {
        String createQuery = "CREATE TABLE nopk_mixed_uk (a INT NOT NULL, b INT, v VARCHAR(64), "
                + "UNIQUE KEY ab_idx (a, b)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_mixed_uk", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertTrue("both UNIQUE key columns must be in the sorting key, was: "
                        + clickHouseQuery, query.contains("a") && query.contains("b"));
        Assert.assertTrue("a nullable member of a composite UNIQUE key still needs "
                        + "allow_nullable_key, was: " + clickHouseQuery,
                query.contains("allow_nullable_key=1"));
    }

    /**
     * Regression guard on the other side: a PRIMARY KEY sorting key must NOT
     * acquire allow_nullable_key. MySQL forces PRIMARY KEY columns to NOT NULL,
     * so the setting is unnecessary there, and emitting it unconditionally
     * would silently permit nullable keys ClickHouse is right to reject.
     */
    @Test
    public void testPrimaryKeyDoesNotEmitAllowNullableKey() {
        String createQuery = "CREATE TABLE pk_only (id INT NOT NULL PRIMARY KEY, "
                + "v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "pk_only", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertTrue("PRIMARY KEY must be the sorting key, was: " + clickHouseQuery,
                query.contains("order by id"));
        Assert.assertFalse("a PRIMARY KEY sorting key cannot be nullable, so the setting must "
                        + "not be emitted, was: " + clickHouseQuery,
                query.contains("allow_nullable_key"));
    }
}
