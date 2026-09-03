package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.KeylessTableWarning;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;

/**
 * A MySQL table may declare neither a PRIMARY KEY nor a non-null UNIQUE key.
 * Such a table has no row identity, and it cannot be replicated correctly:
 * ClickHouse's {@code ORDER BY tuple()} makes every row compare equal, so
 * ReplacingMergeTree keeps exactly ONE row for the whole table.
 *
 * <p>Measured on the connector (MySQL 8.0.36 ROW/FULL/GTID -&gt; ClickHouse
 * 24.8.14), with MySQL row counts on the left:</p>
 *
 * <pre>
 *   scenario                        mysql   clickhouse
 *   5 distinct rows                     5            1   total loss
 *   3 rows, UPDATE one of them          3            1   total loss
 *   3 rows, DELETE one of them          2            0   table emptied
 * </pre>
 *
 * <p>The identity has to come from MySQL, the source of truth, and MySQL has
 * exactly one to offer: the GENERATED INVISIBLE PRIMARY KEY (8.0.30+). With
 * {@code sql_generate_invisible_primary_key=ON} a keyless InnoDB table is
 * created with a real
 * {@code my_row_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT INVISIBLE PRIMARY KEY},
 * which is part of the table definition and therefore carried by the binlogged
 * DDL and by every row image. Such a table reaches this parser as an ORDINARY
 * KEYED table -- the case pinned first below.</p>
 *
 * <p>InnoDB's internal {@code DB_ROW_ID} is NOT an alternative. Verified on
 * MySQL 8.0.36: {@code SELECT DB_ROW_ID} fails with
 * {@code ERROR 1054 Unknown column}, {@code information_schema.innodb_columns}
 * lists only the declared columns while {@code innodb_indexes} shows the
 * {@code GEN_CLUST_INDEX}, and the value is never binlogged.</p>
 *
 * <p>Deriving an identity downstream was tried twice and abandoned. Both
 * attempts are pinned here as things the parser must NOT do, because both
 * cost the table its ability to take any other operation:</p>
 * <ul>
 *   <li>{@code ORDER BY (every column)} -- ClickHouse forbids altering any
 *       column in the sorting key, so the schema FREEZES: MODIFY and RENAME
 *       fail with {@code Code: 524}, DROP with {@code Code: 47}, and the
 *       connector's ten DDL retries (~45s) all fail.</li>
 *   <li>{@code ORDER BY (a hash of the row)} -- a column the hash names cannot
 *       be dropped ({@code Code: 44}), and a column added later is absent from
 *       it, so two rows differing only in the new column collapse: 2 in, 1 out.</li>
 * </ul>
 *
 * <p>So the parser invents nothing. {@link com.altinity.clickhouse.debezium.embedded.cdc.KeylessTablePreflight}
 * reports such a table at startup -- a loud banner naming it and the
 * {@code ALTER TABLE} that fixes it -- and replication continues.</p>
 */
public class CreateTableNoKeySortKeyTest {

    private static MySQLDDLParserService mySQLDDLParserService;

    @BeforeAll
    static public void init() {
        mySQLDDLParserService = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    /**
     * The whole point of the fix: with GIPK enabled, MySQL binlogs the keyless
     * table's DDL WITH {@code my_row_id}, so the connector sees a keyed table
     * and needs no invented identity.
     *
     * <p>This is the exact DDL MySQL 8.0.36 emits for
     * {@code CREATE TABLE on_tbl(a int, b varchar(20))} with
     * {@code sql_generate_invisible_primary_key=ON}.</p>
     */
    @Test
    public void testGipkTableIsOrdinaryKeyedTable() {
        String createQuery = "CREATE TABLE `g`.`on_tbl` (\n"
                + "  `my_row_id` bigint unsigned NOT NULL AUTO_INCREMENT /*!80023 INVISIBLE */,\n"
                + "  `a` int DEFAULT NULL,\n"
                + "  `b` varchar(20) DEFAULT NULL,\n"
                + "  PRIMARY KEY (`my_row_id`))";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "on_tbl", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertTrue("MySQL's generated invisible primary key must become the sorting key, "
                        + "was: " + clickHouseQuery, query.contains("order by (`my_row_id`)"));
        Assert.assertFalse("a GIPK table is an ordinary keyed table -- nothing may be invented "
                        + "for it, was: " + clickHouseQuery, query.contains("order by tuple()"));
        Assert.assertFalse("no data column may join the sorting key, was: " + clickHouseQuery,
                query.contains("order by (`my_row_id`,"));
    }

    /**
     * A genuinely keyless table gets NO invented sorting key.
     *
     * <p>The exact DDL Alembic emits, and the shape that produced the
     * whole-table collapse. The preflight refuses this source; if it was
     * skipped, the parser must still not fabricate an identity, because every
     * fabrication costs the table its schema.</p>
     */
    @Test
    public void testKeylessTableGetsNoInventedSortKey() {
        String createQuery = "CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "alembic_version", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertFalse("keying on the data column freezes the schema: ClickHouse then refuses "
                        + "to MODIFY (524), RENAME (524) or DROP (47) it, was: " + clickHouseQuery,
                query.contains("order by (version_num)"));
        Assert.assertFalse("no row-fingerprint column may be invented either -- a column it names "
                        + "cannot be dropped (44), was: " + clickHouseQuery,
                query.contains("_row_key"));
    }

    /**
     * Nor is a multi-column keyless table given an all-column key.
     */
    @Test
    public void testMultiColumnKeylessTableGetsNoAllColumnSortKey() {
        String createQuery = "CREATE TABLE nokey_multi (a INT, b VARCHAR(64), c DATETIME) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey_multi", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertFalse("all-column sorting key freezes every column of the table, was: "
                        + clickHouseQuery, query.contains("order by (a,b,c)"));
        Assert.assertFalse("allow_nullable_key only existed to support that wide key, was: "
                        + clickHouseQuery, query.replace(" ", "").contains("allow_nullable_key=1"));
    }

    /**
     * A PRIMARY KEY still wins outright -- the overwhelmingly common case must
     * be untouched.
     */
    @Test
    public void testPrimaryKeyStillWins() {
        String createQuery = "CREATE TABLE haspk (id INT NOT NULL PRIMARY KEY, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "haspk", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertTrue("PRIMARY KEY must remain the sorting key, was: " + clickHouseQuery,
                query.contains("order by id"));
    }

    /**
     * A non-null UNIQUE key is a genuine row identity and is still adopted.
     */
    @Test
    public void testUniqueKeyStillWins() {
        String createQuery = "CREATE TABLE hasuk (uk INT NOT NULL UNIQUE, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "hasuk", clickHouseQuery);

        String query = clickHouseQuery.toString().toLowerCase();
        Assert.assertTrue("UNIQUE key must remain the sorting key, was: " + clickHouseQuery,
                query.contains("order by uk"));
        Assert.assertFalse("the UNIQUE key is the identity; v must not join it, was: "
                        + clickHouseQuery, query.contains("order by uk,v"));
    }

    /**
     * The warning must be impossible to miss and must carry the fix.
     *
     * <p>A keyless table is bad practice with a correctness consequence, so a
     * single line at INFO is not enough -- it scrolls past and the table stays
     * broken. The banner names the table, states the consequence, and gives the
     * exact ALTER TABLE.</p>
     */
    @Test
    public void testWarningBannerNamesTheTableAndTheFix() {
        String banner = KeylessTableWarning.banner("app", "events");

        Assert.assertTrue("the banner must be visually unmissable", banner.contains("!!"));
        Assert.assertTrue("it must say this is bad practice needing prompt action",
                banner.toUpperCase().contains("BAD PRACTICE"));
        Assert.assertTrue("it must name the offending table", banner.contains("app.events"));
        Assert.assertTrue("it must carry the exact fix, not just a complaint",
                banner.contains("ADD COLUMN my_row_id BIGINT UNSIGNED NOT NULL"));
        Assert.assertTrue("it must say how to stop this recurring",
                banner.contains("sql_generate_invisible_primary_key"));
        Assert.assertTrue("it must be multi-line so it stands out in a log",
                banner.split("\n").length > 10);
    }

    /**
     * The startup banner lists every offending table, and its advice depends on
     * whether GIPK is now on: when it is, only the pre-existing tables need
     * fixing, and saying so prevents "but I enabled it" confusion.
     */
    @Test
    public void testStartupBannerListsEveryTableAndAdaptsAdvice() {
        String withGipk = KeylessTableWarning.banner(
                Arrays.asList("app.events", "app.audit"), true);
        Assert.assertTrue(withGipk.contains("app.events"));
        Assert.assertTrue(withGipk.contains("app.audit"));
        Assert.assertTrue("it must state that GIPK is not retroactive, or the operator will "
                        + "expect enabling it to have fixed these", withGipk.contains("NOT retroactive"));

        String withoutGipk = KeylessTableWarning.banner(
                Arrays.asList("app.events"), false);
        Assert.assertTrue("without GIPK it must say how to turn it on",
                withoutGipk.contains("SET GLOBAL sql_generate_invisible_primary_key = ON"));
    }
}
