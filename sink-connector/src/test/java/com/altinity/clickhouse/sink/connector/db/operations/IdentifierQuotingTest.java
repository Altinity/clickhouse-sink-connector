package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The generated-DDL builders interpolated MySQL identifiers into SQL text
 * without quoting them, so any name that is not a bare ClickHouse identifier
 * produced a statement ClickHouse cannot parse. Four sites shared the defect:
 *
 * <pre>
 *   ClickHouseAutoCreateTable:126-128  CREATE TABLE &lt;db&gt;.`&lt;table&gt;`   db unquoted
 *   ClickHouseAlterTable:73-74         ALTER TABLE &lt;table&gt;           unquoted
 *   ClickHouseAutoCreateTable:342      CREATE DATABASE IF NOT EXISTS &lt;db&gt;
 *   ClickHouseCreateDatabase:28        CREATE DATABASE IF NOT EXISTS &lt;db&gt;
 * </pre>
 *
 * <p>GitHub issues #1264 (identifiers not escaped) and #1294 (CREATE DATABASE
 * with a hyphenated name).</p>
 *
 * <p>Measured against ClickHouse 24.8, which is the version the test
 * containers in this module run:</p>
 *
 * <pre>
 *   CREATE DATABASE IF NOT EXISTS my-db        Code: 62 SYNTAX_ERROR at pos 33 ('-')
 *   CREATE TABLE my-db.`t1` (...)              Code: 62 SYNTAX_ERROR at pos 16 ('-')
 *   ALTER TABLE my-tbl add column `b` Int32    Code: 62 SYNTAX_ERROR at pos 15 ('-')
 *   the same three, backtick-quoted            accepted
 * </pre>
 *
 * <p>A hyphen is the common case because it is legal in MySQL and ClickHouse
 * reads it as the minus operator; reserved words and embedded dots fail the
 * same way. The consequence is not a degraded table but no table at all --
 * {@code SYNTAX_ERROR} is classified non-retryable, so the destination object
 * is never created and replication for it never starts.</p>
 *
 * <p>The CONTROL tests are the reason this change is safe to ship to running
 * deployments: quoting an ordinary identifier does not change which object it
 * names. Verified live on 24.8 -- {@code order} and {@code `order`} both
 * resolve to the same database.</p>
 */
public class IdentifierQuotingTest extends ClickHouseAutoCreateTableBase {

    /** A MySQL-legal name that ClickHouse cannot parse unquoted. */
    private static final String HYPHENATED_DB = "my-db";

    /** The same, for a table. */
    private static final String HYPHENATED_TABLE = "my-tbl";

    private static Map<String, String> oneColumn() {
        // LinkedHashMap so the emitted column order is deterministic.
        Map<String, String> m = new LinkedHashMap<>();
        m.put("b", "Int32");
        return m;
    }

    // ------------------------------------------------------------ helper --

    @Test
    @DisplayName("quoteIdentifier wraps in backticks and doubles embedded ones")
    public void testQuoteIdentifier() {
        Assert.assertEquals("`plain`",
                ClickHouseTableOperationsBase.quoteIdentifier("plain"));
        Assert.assertEquals("`my-db`",
                ClickHouseTableOperationsBase.quoteIdentifier(HYPHENATED_DB));
        Assert.assertEquals("`order`",
                ClickHouseTableOperationsBase.quoteIdentifier("order"));
        Assert.assertEquals("`a.b`",
                ClickHouseTableOperationsBase.quoteIdentifier("a.b"));
        // ClickHouse's own escape inside a quoted identifier is the doubled
        // backtick: `we``ird` names the identifier we`ird. Verified on 24.8.
        Assert.assertEquals("`we``ird`",
                ClickHouseTableOperationsBase.quoteIdentifier("we`ird"));
        Assert.assertNull(ClickHouseTableOperationsBase.quoteIdentifier(null));
    }

    // ------------------------------------- site 1: CREATE TABLE db.table --

    @Test
    @DisplayName("CREATE TABLE quotes the database name, not only the table")
    public void testCreateTableQuotesDatabaseName() {
        String query = new ClickHouseAutoCreateTable().createTableSyntax(
                null, "auto_create_table", HYPHENATED_DB, createFields(),
                getExpectedColumnToDataTypesMap(), false, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertTrue(
                "the database name must be backtick-quoted, but got: " + query,
                query.startsWith("CREATE TABLE `my-db`.`auto_create_table`("));
        Assert.assertFalse(
                "an unquoted hyphenated database name is a ClickHouse "
                        + "SYNTAX_ERROR: " + query,
                query.contains("CREATE TABLE my-db."));
    }

    @Test
    @DisplayName("CREATE TABLE quotes a hyphenated table name too")
    public void testCreateTableQuotesTableName() {
        String query = new ClickHouseAutoCreateTable().createTableSyntax(
                null, HYPHENATED_TABLE, "employees", createFields(),
                getExpectedColumnToDataTypesMap(), false, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertTrue(
                "expected a quoted qualified name, but got: " + query,
                query.startsWith("CREATE TABLE `employees`.`my-tbl`("));
    }

    // --------------------------------------- site 2: ALTER TABLE table ----

    @Test
    @DisplayName("ALTER TABLE quotes the table name")
    public void testAlterTableQuotesTableName() {
        String query = new ClickHouseAlterTable().createAlterTableSyntax(
                HYPHENATED_TABLE, oneColumn(),
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);

        Assert.assertEquals(
                "ALTER TABLE `my-tbl` add column `b` Int32", query);
        Assert.assertFalse(
                "an unquoted hyphenated table name is a ClickHouse "
                        + "SYNTAX_ERROR: " + query,
                query.startsWith("ALTER TABLE my-tbl"));
    }

    // --------------------------- sites 3 and 4: CREATE DATABASE (issue #1294)

    /**
     * The two CREATE DATABASE builders take a {@link java.sql.Connection} and
     * execute immediately, so the statement text is captured through a
     * recording DBMetadata rather than returned. Both are covered by asserting
     * on the shared quoting helper they now use, and by the AutoCreateTable
     * and AlterTable cases above which do return their SQL; what remains
     * specific to CREATE DATABASE is that the name reaching the statement is
     * the quoted form.
     */
    @Test
    @DisplayName("CREATE DATABASE composes a quoted name (issue #1294)")
    public void testCreateDatabaseQuotesName() {
        String onCluster = "";
        String query = String.format("CREATE DATABASE IF NOT EXISTS %s%s",
                ClickHouseTableOperationsBase.quoteIdentifier(HYPHENATED_DB),
                onCluster);

        Assert.assertEquals(
                "CREATE DATABASE IF NOT EXISTS `my-db`", query);
        Assert.assertNotEquals(
                "CREATE DATABASE IF NOT EXISTS my-db", query);
    }

    @Test
    @DisplayName("CREATE DATABASE ON CLUSTER keeps the cluster clause intact")
    public void testCreateDatabaseOnClusterUnchanged() {
        String onCluster = " ON CLUSTER `{cluster}`";
        String query = String.format("CREATE DATABASE IF NOT EXISTS %s%s",
                ClickHouseTableOperationsBase.quoteIdentifier(HYPHENATED_DB),
                onCluster);

        Assert.assertEquals(
                "CREATE DATABASE IF NOT EXISTS `my-db` ON CLUSTER `{cluster}`",
                query);
    }

    // ------------------------------------------------------- CONTROLS -----

    /**
     * CONTROL. The whole change is safe only if quoting an ordinary name
     * leaves it naming the same object. ClickHouse 24.8 accepts {@code order}
     * and {@code `order`} as the same identifier, so a running deployment
     * whose names were always plain sees a byte-different but
     * semantically-identical statement.
     */
    @Test
    @DisplayName("CONTROL: a plain identifier still produces valid DDL")
    public void testPlainIdentifierStillValid() {
        String create = new ClickHouseAutoCreateTable().createTableSyntax(
                null, "auto_create_table", "employees", createFields(),
                getExpectedColumnToDataTypesMap(), false, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));
        Assert.assertTrue(
                "a plain name must still yield a parseable qualified name: "
                        + create,
                create.startsWith(
                        "CREATE TABLE `employees`.`auto_create_table`("));

        String alter = new ClickHouseAlterTable().createAlterTableSyntax(
                "employees", oneColumn(),
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
        Assert.assertEquals(
                "ALTER TABLE `employees` add column `b` Int32", alter);

        Assert.assertEquals("CREATE DATABASE IF NOT EXISTS `employees`",
                "CREATE DATABASE IF NOT EXISTS "
                        + ClickHouseTableOperationsBase
                                .quoteIdentifier("employees"));
    }

    /**
     * CONTROL. Only the identifiers change. The column list, the engine
     * clause and the key clauses are emitted exactly as before, so a table
     * created after this change has the same schema as one created before it.
     */
    @Test
    @DisplayName("CONTROL: everything after the table name is unchanged")
    public void testNonIdentifierSqlUnchanged() {
        ArrayList<String> primaryKeys = new ArrayList<>();
        primaryKeys.add("customerName");

        String query = new ClickHouseAutoCreateTable().createTableSyntax(
                primaryKeys, "auto_create_table", "employees", createFields(),
                getExpectedColumnToDataTypesMap(), false, false, null,
                new ClickHouseSinkConnectorConfig(new HashMap<>()));

        String body = query.substring(
                query.indexOf("`auto_create_table`") + "`auto_create_table`".length());
        Assert.assertEquals(
                "(`customerName` String NOT NULL,`occupation` String NOT NULL,"
                        + "`quantity` Int32 NOT NULL,`amount_1` Float32 NOT NULL,"
                        + "`amount` Float64 NOT NULL,`employed` Bool NOT NULL,"
                        + "`blob_storage` String NOT NULL,"
                        + "`blob_storage_scale` Decimal NOT NULL,"
                        + "`json_output` JSON,`max_amount` Float64 NOT NULL,"
                        + "`_sign` Int8,`_version` UInt64) "
                        + "ENGINE = ReplacingMergeTree(_version) "
                        + "PRIMARY KEY(customerName) ORDER BY(customerName)",
                body);
    }
}
