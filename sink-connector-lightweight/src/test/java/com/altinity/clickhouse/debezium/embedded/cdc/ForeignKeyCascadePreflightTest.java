package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * The version boundary, the captured-table filter and the warning text of the
 * cascading foreign-key check (spec 01.10 section 3.4).
 */
public class ForeignKeyCascadePreflightTest {

    private static ForeignKeyCascadePreflight.Cascade cascade(String schema, String table) {
        return new ForeignKeyCascadePreflight.Cascade(schema, table, "fk_" + table + "_parent",
                "parent", "CASCADE", "RESTRICT");
    }

    @Test
    public void testMysqlReleasesBelow96AreCovered() {
        Assert.assertTrue(ForeignKeyCascadePreflight.belowFixedRelease("8.0.46"));
        // Percona and other builds append a suffix.
        Assert.assertTrue(ForeignKeyCascadePreflight.belowFixedRelease("8.0.25-15"));
        Assert.assertTrue(ForeignKeyCascadePreflight.belowFixedRelease("8.0.44-35-log"));
        Assert.assertTrue(ForeignKeyCascadePreflight.belowFixedRelease("8.4.0"));
        Assert.assertTrue(ForeignKeyCascadePreflight.belowFixedRelease("5.7.44-log"));
        Assert.assertTrue(ForeignKeyCascadePreflight.belowFixedRelease("9.5.1"));
    }

    @Test
    public void testMysqlReleasesFrom96AreNotCovered() {
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("9.6.0"));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("9.6.0-log"));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("9.7.0"));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("10.0.0"));
    }

    @Test
    public void testMariaDbUnparseableAndMissingVersionsAreNotCovered() {
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("10.11.6-MariaDB"));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("11.4.2-MariaDB-log"));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("5.5.5-10.3.0-MariaDB"));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease("not a version"));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease(""));
        Assert.assertFalse(ForeignKeyCascadePreflight.belowFixedRelease(null));
    }

    @Test
    public void testCascadeQueryIsASingleSelect() {
        // Every preflight statement is checked by the same guard the keyless-table check uses.
        KeylessTablePreflight.assertReadOnlySql(ForeignKeyCascadePreflight.CASCADE_QUERY);
        Assert.assertTrue(ForeignKeyCascadePreflight.CASCADE_QUERY.contains("information_schema.REFERENTIAL_CONSTRAINTS"));
        Assert.assertTrue(ForeignKeyCascadePreflight.CASCADE_QUERY.contains("DELETE_RULE = 'CASCADE'"));
        Assert.assertTrue(ForeignKeyCascadePreflight.CASCADE_QUERY.contains("UPDATE_RULE = 'CASCADE'"));
    }

    @Test
    public void testOnlyTablesOnTheIncludeListAreKept() {
        // An include list, when set, is the whole rule: only the named table is captured.
        Properties props = new Properties();
        props.setProperty("table.include.list", "app\\.kept");
        List<ForeignKeyCascadePreflight.Cascade> found = new ArrayList<>(Arrays.asList(
                cascade("app", "kept"), cascade("app", "outside")));
        List<ForeignKeyCascadePreflight.Cascade> captured = ForeignKeyCascadePreflight.captured(found, props);
        Assert.assertEquals(1, captured.size());
        Assert.assertEquals("kept", captured.get(0).table);
    }

    @Test
    public void testExcludedTablesAreDroppedWhenNoIncludeListIsSet() {
        Properties props = new Properties();
        props.setProperty("table.exclude.list", "app\\.ignored");
        List<ForeignKeyCascadePreflight.Cascade> found = new ArrayList<>(Arrays.asList(
                cascade("app", "kept"), cascade("app", "ignored")));
        List<ForeignKeyCascadePreflight.Cascade> captured = ForeignKeyCascadePreflight.captured(found, props);
        Assert.assertEquals(1, captured.size());
        Assert.assertEquals("kept", captured.get(0).table);
    }

    @Test
    public void testWarningNamesEachCapturedChildConstraintAndRemedy() {
        List<ForeignKeyCascadePreflight.Cascade> captured = Arrays.asList(
                new ForeignKeyCascadePreflight.Cascade("shop_app", "child", "fk_child_parent", "parent",
                        "CASCADE", "CASCADE"));
        String text = ForeignKeyCascadePreflight.warningText("8.0.46", captured);
        Assert.assertTrue(text.contains("MySQL 8.0.46"));
        Assert.assertTrue(text.contains("shop_app.child"));
        Assert.assertTrue(text.contains("constraint fk_child_parent"));
        Assert.assertTrue(text.contains("parent parent"));
        Assert.assertTrue(text.contains("ON DELETE CASCADE, ON UPDATE CASCADE"));
        Assert.assertTrue(text.contains("WILL DIVERGE"));
        Assert.assertTrue(text.contains("REPLICATING ANYWAY"));
        Assert.assertTrue(text.contains("ch-mysql-resync"));
        Assert.assertTrue(text.contains("MySQL 9.6 or later"));
    }

    @Test
    public void testNonMysqlConnectorIsIgnored() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        ForeignKeyCascadePreflight.check(props);
    }

    @Test
    public void testUnreachableSourceNeverThrows() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("database.hostname", "127.0.0.1");
        props.setProperty("database.port", "1");
        props.setProperty("database.user", "nobody");
        props.setProperty("database.password", "nothing");
        ForeignKeyCascadePreflight.check(props);
    }
}
