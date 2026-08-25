package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * A MySQL table may declare neither a PRIMARY KEY nor a UNIQUE key. Such a
 * table has no row identity at all, and was created in ClickHouse as
 * {@code ReplacingMergeTree(...) ORDER BY tuple()}: with an empty sorting key
 * every row compares equal, so ReplacingMergeTree keeps exactly ONE row for the
 * entire table.
 *
 * <p>Measured on the connector before this fix (MySQL 8.0.36 ROW/FULL/GTID -&gt;
 * ClickHouse 24.8.14), with MySQL row counts on the left:</p>
 *
 * <pre>
 *   scenario                        mysql   clickhouse
 *   5 distinct rows                     5            1   total loss
 *   3 rows, UPDATE one of them          3            1   total loss
 *   3 rows, DELETE one of them          2            0   table emptied
 *   grows from 1 row to 2 rows          2            1   loss on growth
 * </pre>
 *
 * <p>The canonical instance is Alembic's {@code alembic_version}, which holds
 * exactly one row -- so the collapse is invisible there, and only appears the
 * moment any keyless table holds two. The defect is a property of the table
 * SHAPE, not of Alembic.</p>
 *
 * <p>The fix orders by every column, which makes the full row its own identity
 * -- exactly MySQL's semantics for a keyless table, where rows are distinguished
 * by their values. {@code PRIMARY KEY tuple()} is emitted alongside so the
 * in-memory sparse index stays empty despite the wide sorting key, and
 * {@code allow_nullable_key=1} because such a key necessarily spans the table's
 * nullable columns (ClickHouse otherwise rejects it with
 * {@code Code: 44 ILLEGAL_COLUMN}).</p>
 *
 * <p>Ordering by all columns requires the writer-side counterpart in
 * {@code PreparedStatementExecutor}: an UPDATE moves the row to a different
 * sorting key, so the before-image must be tombstoned or the pre-update row
 * survives alongside the updated one. See
 * {@code PreparedStatementExecutorSortingKeyTombstoneTest}.</p>
 *
 * <p>Rejected alternatives, each refuted by measurement against ClickHouse
 * 24.8.14 rather than by argument:</p>
 * <ul>
 *   <li>{@code ORDER BY _version} -- every record gets a distinct version, so
 *       nothing ever deduplicates: an UPDATE leaves both the old and new row
 *       (2 rows where MySQL has 1) and a DELETE tombstone merely adds a third.</li>
 *   <li>{@code Engine=MergeTree} -- {@code SELECT ... FINAL} then fails with
 *       {@code Code: 181 ILLEGAL_FINAL}, breaking every consumer query and
 *       checksum job. Worse, {@code SETTINGS final=1} does NOT fail: it
 *       silently returns undeduplicated rows.</li>
 *   <li>{@code CollapsingMergeTree} / {@code SummingMergeTree} multiplicity
 *       counters -- the arithmetic is right, but at merge the physical rows
 *       collapse to one, so an ordinary consumer query still sees a single row
 *       where MySQL has two.</li>
 * </ul>
 *
 * <p>One case remains unrepresentable: a keyless table holding two
 * byte-identical rows. Identical rows cannot be told apart by any sorting key,
 * so ClickHouse retains one. The connector logs a warning naming the table at
 * create time.</p>
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
     * The exact DDL Alembic emits. This is the shape that produced the
     * whole-table collapse.
     */
    @Test
    public void testAlembicVersionTableGetsAllColumnsSortKey() {
        String createQuery = "CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "alembic_version", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertFalse("ORDER BY tuple() collapses the whole table to one row, was: " + query,
                query.toLowerCase().contains("order by tuple()"));
        Assert.assertTrue("the generated row-key column must be the sorting key, was: " + query,
                query.toLowerCase().contains("order by `_row_key`"));
        // Keying on the data column itself is what froze the table's schema:
        // ClickHouse forbids altering any column in the sorting key.
        Assert.assertFalse("no data column may sit in the sorting key, was: " + query,
                query.toLowerCase().contains("order by (version_num)"));
    }

    /**
     * A multi-column keyless table: every column participates, in DDL order.
     */
    @Test
    public void testMultiColumnKeylessTableOrdersByAllColumnsInDeclarationOrder() {
        String createQuery = "CREATE TABLE nokey_multi (a INT, b VARCHAR(64), c DATETIME) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey_multi", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertFalse("ORDER BY tuple() collapses the whole table to one row, was: " + query,
                query.toLowerCase().contains("order by tuple()"));
        Assert.assertTrue("the generated row-key column must be the sorting key, was: " + query,
                query.toLowerCase().contains("order by `_row_key`"));
        // Declaration order, not hash order: the fingerprint has to be
        // reproducible across connectors replicating the same source.
        String lower = query.toLowerCase();
        Assert.assertFalse("no data column may sit in the sorting key, was: " + query,
                lower.contains("order by (a,b,c)"));
    }

    /**
     * A wide sorting key must not become a wide sparse index.
     */
    @Test
    public void testKeylessTableEmitsEmptyPrimaryKey() {
        String createQuery = "CREATE TABLE nokey_pk (a INT, b VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey_pk", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertTrue("PRIMARY KEY tuple() keeps the sparse index empty, was: " + query,
                query.toLowerCase().contains("primary key tuple()"));
    }

    /**
     * Nullable columns are the common case for a keyless table. The fingerprint
     * is a non-Nullable UInt64 by construction, so {@code allow_nullable_key} is
     * no longer needed -- and must not be emitted, since it would silently
     * permit nullable keys ClickHouse is right to reject elsewhere.
     */
    @Test
    public void testKeylessTableNeedsNoNullableKeySetting() {
        String createQuery = "CREATE TABLE nokey_nullable (a INT, b VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey_nullable", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertTrue("the row-key column must be a non-Nullable UInt64, was: " + query,
                query.toLowerCase().contains("`_row_key` uint64"));
        Assert.assertFalse("a non-Nullable key needs no allow_nullable_key, was: " + query,
                query.toLowerCase().contains("allow_nullable_key"));
    }

    /**
     * A PRIMARY KEY still wins outright -- the fallback must not disturb the
     * overwhelmingly common case.
     */
    @Test
    public void testPrimaryKeyStillWins() {
        String createQuery = "CREATE TABLE haspk (id INT NOT NULL PRIMARY KEY, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "haspk", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertTrue("PRIMARY KEY must remain the sorting key, was: " + query,
                query.toLowerCase().contains("order by id"));
        Assert.assertFalse("a keyed table must not get the keyless PRIMARY KEY tuple(), was: " + query,
                query.toLowerCase().contains("primary key tuple()"));
        Assert.assertFalse("a keyed table must not gain the generated row-key column, was: " + query,
                query.toLowerCase().contains("_row_key"));
    }

    /**
     * A UNIQUE key still wins over the all-columns fallback: it is a narrower
     * and more meaningful identity.
     */
    @Test
    public void testUniqueKeyStillWinsOverAllColumns() {
        String createQuery = "CREATE TABLE hasuk (uk INT NOT NULL UNIQUE, v VARCHAR(64)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "hasuk", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertTrue("UNIQUE key must remain the sorting key, was: " + query,
                query.toLowerCase().contains("order by uk"));
        Assert.assertFalse("the UNIQUE key is the identity; v must not join the sorting key, was: " + query,
                query.toLowerCase().contains("order by uk,v"));
    }

    /**
     * A generated column is excluded from the sorting key.
     *
     * <p>It is a pure function of the columns it derives from, so it adds
     * nothing to row identity: two rows agreeing on every stored column agree
     * on the generated one by construction. It is also absent from the CDC
     * record payload -- ClickHouse computes it -- so the writer could not
     * compare it when deciding whether an UPDATE relocates the row.</p>
     *
     * <p>Note this is a deliberate choice, not a ClickHouse restriction: the
     * connector renders generated columns as {@code MATERIALIZED}, which is
     * accepted in a sorting key (verified on 24.8.14). Only {@code ALIAS}
     * columns are rejected there, with {@code Code: 47 Missing columns}.</p>
     */
    @Test
    public void testGeneratedColumnExcludedFromSortKey() {
        String createQuery = "CREATE TABLE nokey_gen (a INT, b INT AS (a + 1)) ENGINE=InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "nokey_gen", clickHouseQuery);

        String query = clickHouseQuery.toString();
        Assert.assertTrue("the generated row-key column must be the sorting key, was: " + query,
                query.toLowerCase().contains("order by `_row_key`"));
    }
}
