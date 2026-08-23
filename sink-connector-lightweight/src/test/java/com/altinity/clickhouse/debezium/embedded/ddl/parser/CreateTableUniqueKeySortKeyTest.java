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
}
