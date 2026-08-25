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
    public void testNoKeyAtAllHasNoInventedSortKey() {
        String createQuery = "CREATE TABLE nokey (a INT, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        // No identity is invented from the data. Such a table is refused by
        // KeylessTablePreflight; reaching here at all means the check was
        // skipped, and an invented key would cost the table its schema.
        Assert.assertFalse("no data column may be made the sorting key, was: " + clickHouseQuery,
                query.contains("order by (a,v)"));
        Assert.assertFalse("a data column in the sorting key freezes the schema, was: "
                        + clickHouseQuery, query.contains("allow_nullable_key=1"));
    }

    /**
     * A UNIQUE key over a NULLABLE column, with no PRIMARY KEY, must NOT be
     * adopted as the sorting key.
     *
     * <p>MySQL does not treat NULLs as equal for uniqueness, so a nullable
     * UNIQUE index permits any number of rows whose key is NULL -- it is not a
     * row identity at all. ClickHouse does compare NULLs as equal in a sorting
     * key, so adopting such a key makes ReplacingMergeTree collapse those
     * distinct source rows into one.</p>
     *
     * <p>Measured on MySQL 8.0.36 ROW/FULL/GTID -&gt; ClickHouse 24.8.14.10547
     * with {@code UNIQUE KEY(a)} over a nullable {@code a}: four source rows,
     * three of them {@code a IS NULL}, arrived as TWO. Rows 'first' and
     * 'second' were silently lost.</p>
     *
     * <p>Such a table falls through to the all-columns fallback, which does
     * reproduce MySQL's semantics for a table with no row identity: rows are
     * distinguished by value. That key spans nullable columns, so
     * {@code allow_nullable_key} is required -- without it ClickHouse rejects
     * the CREATE TABLE with {@code Code: 44 ILLEGAL_COLUMN}, and because DDL
     * is retried indefinitely that failure stalls the ENTIRE replication
     * stream, not merely the offending table.</p>
     */
    @Test
    public void testNullableUniqueKeyFallsBackToAllColumns() {
        String createQuery = "CREATE TABLE nopk_nullable_uk (a INT, v VARCHAR(64), "
                + "UNIQUE KEY uk_idx (a)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_nullable_uk", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertFalse("a nullable UNIQUE key is not a row identity -- MySQL allows many "
                        + "NULL-keyed rows -- so it must not become the sorting key, was: "
                        + clickHouseQuery,
                query.contains("order by (a)"));
        Assert.assertFalse("no identity is invented from the data columns either, was: "
                        + clickHouseQuery, query.contains("order by (a,v)"));
    }

    /**
     * A composite UNIQUE key with even one nullable member is equally unusable:
     * MySQL permits many rows sharing a NULL in that member.
     */
    @Test
    public void testPartiallyNullableCompositeUniqueKeyFallsBackToAllColumns() {
        String createQuery = "CREATE TABLE nopk_mixed_uk (a INT NOT NULL, b INT, v VARCHAR(64), "
                + "UNIQUE KEY ab_idx (a, b)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_mixed_uk", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertFalse("one nullable member makes the whole UNIQUE key unusable as an "
                        + "identity, so it must not become the sorting key, was: " + clickHouseQuery,
                query.contains("order by (a,b)"));
        Assert.assertFalse("no identity is invented from the data columns either, was: "
                        + clickHouseQuery, query.contains("order by (a,b,v)"));
    }

    /**
     * The safe case must keep working: a UNIQUE key whose every column is
     * NOT NULL IS a stable row identity, and stays the sorting key. Being
     * NOT NULL, it needs no allow_nullable_key.
     */
    @Test
    public void testNotNullCompositeUniqueKeyRemainsSortKey() {
        String createQuery = "CREATE TABLE nopk_nn_uk (a INT NOT NULL, b INT NOT NULL, "
                + "v VARCHAR(64), UNIQUE KEY ab_idx (a, b)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nopk_nn_uk", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertTrue("a fully NOT NULL UNIQUE key is a valid identity and must remain the "
                        + "sorting key, was: " + clickHouseQuery,
                query.contains("order by (a,b)"));
        Assert.assertFalse("a NOT NULL UNIQUE sorting key cannot be nullable, so the setting "
                        + "must not be emitted, was: " + clickHouseQuery,
                query.contains("allow_nullable_key"));
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
