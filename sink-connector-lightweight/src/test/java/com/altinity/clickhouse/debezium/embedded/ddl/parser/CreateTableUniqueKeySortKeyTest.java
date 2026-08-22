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
 * what the sorting key must be, so it is used when no PRIMARY KEY exists.
 * Note that ordering by all data columns is NOT a valid alternative: an UPDATE
 * to a non-key column then produces a different sorting key, so both versions
 * survive FINAL and the table gains permanent duplicates instead.</p>
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
     * Regression guard: a table with neither a PRIMARY KEY nor a UNIQUE key has
     * no stable row identity to key on, so it must still fall back to
     * ORDER BY tuple(). Inventing a key here (for example ordering by all data
     * columns) would trade collapse for permanent duplication on UPDATE.
     */
    @Test
    public void testNoKeyAtAllStillFallsBackToTuple() {
        String createQuery = "CREATE TABLE nokey (a INT, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey", clickHouseQuery);

        Assert.assertTrue("no key of any kind must still yield ORDER BY tuple(), was: "
                        + clickHouseQuery,
                clickHouseQuery.toString().toLowerCase().contains("order by tuple()"));
    }
}
