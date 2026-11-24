package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SC-6: capture-list filtering of DDL.
 * <ul>
 *   <li>(a) a multi-table {@code RENAME TABLE} is narrowed to the pairs inside
 *       the capture lists;</li>
 *   <li>(b) the database qualifier of a table is kept while matching
 *       {@code table.include.list} / {@code table.exclude.list};</li>
 *   <li>(d) ignore-rule regexes are compiled once.</li>
 * </ul>
 * (c), snapshot DDL and {@code disable.drop.truncate}, is unchanged and pinned
 * by {@code DdlIgnoreRulesTest}.
 */
public class DdlRenameAndQualifierFilteringTest {

    private static SourceRecord ddlRecord(String database, String ddl, String... tableIds) {
        Schema keySchema = SchemaBuilder.struct().field("databaseName", Schema.STRING_SCHEMA).build();
        Struct key = new Struct(keySchema);
        key.put("databaseName", database);
        Schema changeSchema = SchemaBuilder.struct().field("id", Schema.STRING_SCHEMA).build();
        Schema valueSchema = SchemaBuilder.struct()
                .field("ddl", Schema.STRING_SCHEMA)
                .field("tableChanges", SchemaBuilder.array(changeSchema).build())
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ddl", ddl);
        List<Struct> changes = new ArrayList<>();
        for (String id : tableIds) {
            Struct c = new Struct(changeSchema);
            c.put("id", id);
            changes.add(c);
        }
        value.put("tableChanges", changes);
        return new SourceRecord(null, null, "db1", 0, keySchema, key, valueSchema, value);
    }

    private static DebeziumChangeEventCapture capture() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        Field f = DebeziumChangeEventCapture.class.getDeclaredField("pgConfig");
        f.setAccessible(true);
        f.set(capture, new PostgresConnectorConfig(new Properties()));
        return capture;
    }

    private static Properties props(String connectorClass, String... keyValues) {
        Properties p = new Properties();
        if (connectorClass != null) {
            p.setProperty("connector.class", connectorClass);
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    private static Properties mysqlProps(String... keyValues) {
        return props("io.debezium.connector.mysql.MySqlConnector", keyValues);
    }

    private static boolean invokeIgnored(DebeziumChangeEventCapture capture, String ddl, Properties props,
                                         SourceRecord sr) throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod("checkIfDDLNeedsToBeIgnored",
                String.class, Properties.class, SourceRecord.class, AtomicBoolean.class);
        m.setAccessible(true);
        try {
            return (Boolean) m.invoke(capture, ddl, props, sr, new AtomicBoolean(false));
        } catch (InvocationTargetException ite) {
            throw (Exception) ite.getCause();
        }
    }

    // ------------------------------------------------------------ SC-6(a): multi-table RENAME

    @Test
    @DisplayName("SC-6(a): only the captured pair of a multi-table RENAME is kept")
    public void onlyTheCapturedRenamePairIsKept() throws Exception {
        String ddl = "RENAME TABLE orders TO orders_old, audit TO audit_old";
        SourceRecord sr = ddlRecord("db1", ddl, "db1.orders", "db1.audit");

        assertEquals("RENAME TABLE orders TO orders_old",
                capture().retainCapturedRenamePairs(ddl, sr, mysqlProps("table.include.list", "db1\\.orders")));
        assertEquals("RENAME TABLE audit TO audit_old",
                capture().retainCapturedRenamePairs(ddl, sr, mysqlProps("table.exclude.list", "db1\\.orders")));
    }

    @Test
    @DisplayName("SC-6(a): a pair renamed INTO the captured set is kept")
    public void pairRenamedIntoTheCapturedSetIsKept() throws Exception {
        String ddl = "RENAME TABLE tmp TO orders, audit TO audit_old";
        SourceRecord sr = ddlRecord("db1", ddl, "db1.tmp", "db1.orders", "db1.audit");

        assertEquals("RENAME TABLE tmp TO orders",
                capture().retainCapturedRenamePairs(ddl, sr, mysqlProps("table.include.list", "db1\\.orders")));
    }

    @Test
    @DisplayName("SC-6(a): a RENAME with no captured pair is dropped, one with every pair captured is unchanged")
    public void noneOrAllCapturedPairs() throws Exception {
        String ddl = "RENAME TABLE a TO a2, b TO b2";
        SourceRecord sr = ddlRecord("db1", ddl, "db1.a", "db1.b");

        assertNull(capture().retainCapturedRenamePairs(ddl, sr, mysqlProps("table.include.list", "db1\\.zzz")));
        assertSame(ddl, capture().retainCapturedRenamePairs(ddl, sr, mysqlProps()));
        assertSame(ddl, capture().retainCapturedRenamePairs(ddl, sr,
                mysqlProps("table.include.list", "db1\\.a,db1\\.b")));
    }

    @Test
    @DisplayName("SC-6(a): a single-pair RENAME and other statements are returned unchanged")
    public void singlePairAndOtherStatementsAreUnchanged() throws Exception {
        Properties restrictive = mysqlProps("table.include.list", "db1\\.zzz");
        SourceRecord sr = ddlRecord("db1", "RENAME TABLE a TO b", "db1.a");

        assertEquals("RENAME TABLE a TO b", capture().retainCapturedRenamePairs("RENAME TABLE a TO b", sr, restrictive));
        assertEquals("ALTER TABLE a ADD COLUMN c INT",
                capture().retainCapturedRenamePairs("ALTER TABLE a ADD COLUMN c INT", sr, restrictive));
        assertNull(DdlTableNames.renamePairs("ALTER TABLE a ADD COLUMN c INT"));
    }

    // ------------------------------------------------------------ SC-6(b): the qualifier survives

    @Test
    @DisplayName("SC-6(b): a RENAME pair is matched in its own database, not in the record's database")
    public void renamePairsAreMatchedInTheirOwnDatabase() throws Exception {
        String ddl = "RENAME TABLE db1.orders TO db1.o2, db2.orders TO db2.o2";
        SourceRecord sr = ddlRecord("db1", ddl, "db1.orders", "db2.orders");

        assertEquals("RENAME TABLE db1.orders TO db1.o2",
                capture().retainCapturedRenamePairs(ddl, sr, mysqlProps("table.include.list", "db1\\.orders")),
                "db2.orders is not in table.include.list; stripping its qualifier matched it as db1.orders");
        assertEquals("RENAME TABLE db2.orders TO db2.o2",
                capture().retainCapturedRenamePairs(ddl, sr, mysqlProps("table.include.list", "db2\\.orders")));
    }

    @Test
    @DisplayName("SC-6(b): a source whose two-part names are not database.table ignores the qualifier")
    public void nonMySqlSourceIgnoresTheQualifier() throws Exception {
        String ddl = "RENAME TABLE s2.orders TO s2.o2, s2.audit TO s2.a2";
        SourceRecord sr = ddlRecord("db1", ddl, "db1.orders");

        assertEquals("RENAME TABLE s2.orders TO s2.o2",
                capture().retainCapturedRenamePairs(ddl, sr,
                        props("io.debezium.connector.postgresql.PostgresConnector",
                                "table.include.list", "db1\\.orders")));
    }

    @Test
    @DisplayName("SC-6(b): a tableChanges id in another database is not captured by this database's include list")
    public void tableChangesIdQualifierIsRespected() throws Exception {
        DebeziumChangeEventCapture capture = capture();
        String ddl = "ALTER TABLE t ADD COLUMN c INT";

        // The id names db2.t; the include list captures db1.t only.
        assertTrue(invokeIgnored(capture, ddl, mysqlProps("table.include.list", "db1\\.t"),
                ddlRecord("db1", ddl, "db2.t")));
        // The include list captures db2.t: the table is captured although the session database is db1.
        assertFalse(invokeIgnored(capture, ddl, mysqlProps("table.include.list", "db2\\.t"),
                ddlRecord("db1", ddl, "db2.t")),
                "the id's own database must be matched (pre-fix it was stripped and judged as db1.t)");
        assertFalse(invokeIgnored(capture, ddl, mysqlProps("table.include.list", "db1\\.t"),
                ddlRecord("db1", ddl, "db1.t")));
    }

    @Test
    @DisplayName("SC-6(b): a multi-table RENAME whose pairs are all outside the lists is ignored by the record path")
    public void renameOutsideTheListsIsIgnored() throws Exception {
        String ddl = "RENAME TABLE db2.orders TO db2.o2";
        assertTrue(invokeIgnored(capture(), ddl, mysqlProps("table.include.list", "db1\\.orders"),
                ddlRecord("db1", ddl, "db2.orders")));
    }

    // ------------------------------------------------------------ DdlTableNames

    @Test
    @DisplayName("SC-6(b): qualifiedName splits on dots outside quotes")
    public void qualifiedNameRespectsQuotes() {
        DdlTableNames.QualifiedName plain = DdlTableNames.qualifiedName("t");
        assertNull(plain.database);
        assertEquals("t", plain.table);

        DdlTableNames.QualifiedName qualified = DdlTableNames.qualifiedName("db2.t");
        assertEquals("db2", qualified.database);
        assertEquals("t", qualified.table);

        DdlTableNames.QualifiedName quoted = DdlTableNames.qualifiedName("`my.db`.`t.1`");
        assertEquals("my.db", quoted.database);
        assertEquals("t.1", quoted.table);

        assertNull(DdlTableNames.qualifiedName(""));
        assertNull(DdlTableNames.qualifiedName(null));
        assertEquals("fallback", DdlTableNames.qualifiedName("t").databaseOr("fallback"));
        assertEquals("db2", DdlTableNames.qualifiedName("db2.t").databaseOr("fallback"));
    }

    @Test
    @DisplayName("SC-6(a): renamePairs parses every pair, honouring quotes and dots")
    public void renamePairsParsesQuotedPairs() {
        List<DdlTableNames.RenamePair> pairs =
                DdlTableNames.renamePairs("RENAME TABLE `a.b` TO c, d TO `e`.`f.g`;");
        assertNotNull(pairs);
        assertEquals(2, pairs.size());
        assertEquals("`a.b` TO c", pairs.get(0).text);
        assertNull(pairs.get(0).source.database);
        assertEquals("a.b", pairs.get(0).source.table);
        assertEquals("c", pairs.get(0).target.table);
        assertEquals("d TO `e`.`f.g`", pairs.get(1).text);
        assertEquals("e", pairs.get(1).target.database);
        assertEquals("f.g", pairs.get(1).target.table);

        assertNull(DdlTableNames.renamePairs("RENAME TABLE a b"), "an unparseable list is left to the caller");
        assertNull(DdlTableNames.renamePairs(null));
    }

    @Test
    @DisplayName("SC-6(b): affectedQualified keeps the database only when the source's names are database.table")
    public void affectedQualifiedHonoursKeepQualifiers() {
        SourceRecord sr = ddlRecord("db1", "ALTER TABLE t ADD COLUMN c INT", "db2.t");

        List<DdlTableNames.QualifiedName> kept = DdlTableNames.affectedQualified(sr, "ALTER TABLE t ADD COLUMN c INT", true);
        assertEquals(1, kept.size());
        assertEquals("db2", kept.get(0).database);
        assertEquals("t", kept.get(0).table);

        List<DdlTableNames.QualifiedName> stripped =
                DdlTableNames.affectedQualified(sr, "ALTER TABLE t ADD COLUMN c INT", false);
        assertEquals(1, stripped.size());
        assertNull(stripped.get(0).database);
        assertEquals("t", stripped.get(0).table);
    }

    @Test
    @DisplayName("SC-6(b): isMySqlSource recognises the MySQL connector class only")
    public void mySqlSourceDetection() {
        assertTrue(DebeziumChangeEventCapture.isMySqlSource(mysqlProps()));
        assertFalse(DebeziumChangeEventCapture.isMySqlSource(
                props("io.debezium.connector.postgresql.PostgresConnector")));
        assertFalse(DebeziumChangeEventCapture.isMySqlSource(new Properties()));
        assertFalse(DebeziumChangeEventCapture.isMySqlSource(null));
    }

    // ------------------------------------------------------------ SC-6(d): compiled-regex cache

    @Test
    @DisplayName("SC-6(d): an ignore rule is compiled once and the same Pattern is reused")
    public void ignoreRulesAreCompiledOnce() {
        String regex = "^ALTER TABLE .* ADD COLUMN .*";
        assertSame(DebeziumChangeEventCapture.compiledIgnoreRule(regex),
                DebeziumChangeEventCapture.compiledIgnoreRule(regex));
    }

    @Test
    @DisplayName("SC-6(d): repeated checks against a cached ignore rule stay correct")
    public void cachedIgnoreRuleStaysCorrect() throws Exception {
        DebeziumChangeEventCapture capture = capture();
        // DESTRUCTIVE: regex-matching unit-test string only; no DDL is executed.
        Properties props = mysqlProps(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX,
                "^ALTER TABLE .* DROP COLUMN .*||^ALTER TABLE .* MODIFY COLUMN .*");
        SourceRecord sr = ddlRecord("db1", "ALTER TABLE t MODIFY COLUMN c INT", "db1.t");

        for (int i = 0; i < 3; i++) {
            assertTrue(invokeIgnored(capture, "ALTER TABLE t MODIFY COLUMN c INT", props, sr));
            // DESTRUCTIVE: regex-matching unit-test string only; no DDL is executed.
            assertTrue(invokeIgnored(capture, "ALTER TABLE t DROP COLUMN c", props, sr));
            assertFalse(invokeIgnored(capture, "ALTER TABLE t ADD COLUMN c INT", props, sr));
        }
    }
}
