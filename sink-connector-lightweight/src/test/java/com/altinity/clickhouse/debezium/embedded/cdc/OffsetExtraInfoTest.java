package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 09.05: the {@code extra_info} column of the offset table is optional. Every outcome other than
 * "the column is visible on the offset store's own endpoint" leaves the connector properties as they were
 * and never throws.
 *
 * <p>The ClickHouse server is a JDK-proxy fake that records every statement with the connection it ran on.
 * The real servers are covered by the end-to-end run in the spec.</p>
 */
public class OffsetExtraInfoTest {

    private static final String TABLE = "altinity_sink_connector.replica_source_info";
    private static final String JSON = "{\"schema\":1,\"connector_version\":\"2.11.1\"}";
    private static final String ALTER = "ALTER TABLE `altinity_sink_connector`.`replica_source_info` "
            + "ADD COLUMN IF NOT EXISTS `extra_info` String DEFAULT ''";

    /** A scriptable ClickHouse server. Statement texts are recorded as {@code label: sql}. */
    private static final class Fake {
        final List<String> log = new ArrayList<>();
        boolean tableExists = true;
        boolean columnExists = false;
        boolean alterFails = false;
        boolean alterVisible = true;
        boolean metadataFails = false;
        boolean storeOpenFails = false;
        String currentDb = "altinity_sink_connector";
        int opened = 0;
        int closed = 0;

        Connection system() {
            return connection("system", false);
        }

        OffsetExtraInfo.StoreEndpoint endpoint() {
            return () -> {
                if (storeOpenFails) {
                    throw new SQLException("connection refused");
                }
                opened++;
                return connection("store", true);
            };
        }

        List<String> on(String label) {
            List<String> sqls = new ArrayList<>();
            for (String entry : log) {
                if (entry.startsWith(label + ": ")) {
                    sqls.add(entry.substring(label.length() + 2));
                }
            }
            return sqls;
        }

        private static Object zero(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }

        private ResultSet result(Object value) {
            final boolean[] read = {false};
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        if (read[0]) {
                            return false;
                        }
                        read[0] = true;
                        return true;
                    case "getLong":
                        return ((Number) value).longValue();
                    case "getString":
                        return String.valueOf(value);
                    default:
                        return zero(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, h);
        }

        private Connection connection(String label, boolean countsClose) {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement":
                        return prepared(label, (String) args[0]);
                    case "createStatement":
                        return statement(label);
                    case "close":
                        if (countsClose) {
                            closed++;
                        }
                        return null;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return label + "-connection";
                    default:
                        return zero(method.getReturnType());
                }
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, h);
        }

        private PreparedStatement prepared(String label, String sql) {
            final List<String> binds = new ArrayList<>();
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setString":
                        binds.add((String) args[1]);
                        return null;
                    case "executeQuery":
                        log.add(label + ": " + sql + " " + binds);
                        if (metadataFails) {
                            throw new SQLException("Code: 60. system tables are not readable");
                        }
                        if (sql.contains("system.tables")) {
                            return result(tableExists ? 1L : 0L);
                        }
                        if (sql.contains("system.columns")) {
                            return result(columnExists ? 1L : 0L);
                        }
                        throw new SQLException("unexpected query " + sql);
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return zero(method.getReturnType());
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, h);
        }

        private Statement statement(String label) {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        log.add(label + ": " + args[0]);
                        if ("SELECT currentDatabase()".equals(args[0])) {
                            return result(currentDb);
                        }
                        throw new SQLException("unexpected query " + args[0]);
                    case "execute":
                        log.add(label + ": " + args[0]);
                        if (alterFails) {
                            throw new SQLException("Code: 497. ACCESS_DENIED: not enough privileges to ALTER");
                        }
                        if (alterVisible) {
                            columnExists = true;
                        }
                        return false;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return zero(method.getReturnType());
                }
            };
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, h);
        }
    }

    @BeforeEach
    public void forgetEarlierRuns() {
        OffsetExtraInfo.resetForTest();
    }

    private static Properties props() {
        Properties props = new Properties();
        props.setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore");
        props.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name(), TABLE);
        return props;
    }

    private static Properties copy(Properties props) {
        return (Properties) props.clone();
    }

    private static OffsetExtraInfo.Result run(Properties props, Fake fake) {
        return OffsetExtraInfo.prepare(props, fake.system(), fake.endpoint(), () -> JSON);
    }

    private static void assertUntouched(Properties before, Properties after) {
        assertEquals(before, after, "the connector properties are exactly as they were");
        assertFalse(after.containsKey(OffsetExtraInfo.INSERT_KEY));
    }

    @Test
    @DisplayName("the property key is the one Debezium's JDBC offset store reads")
    public void insertKeyIsDebeziumsKey() {
        assertEquals("offset.storage.jdbc.table.insert", OffsetExtraInfo.INSERT_KEY);
    }

    @Test
    @DisplayName("column present: the insert is overridden and nothing is altered")
    public void columnPresent() {
        Fake fake = new Fake();
        fake.columnExists = true;
        Properties props = props();
        Properties before = copy(props);

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.ACTIVE, result.state(), result.detail());
        assertEquals(OffsetExtraInfo.insertStatement(JSON), props.getProperty(OffsetExtraInfo.INSERT_KEY));
        props.remove(OffsetExtraInfo.INSERT_KEY);
        assertEquals(before, props, "only the one insert property is added");
        assertEquals(List.of(), fake.on("system"), "no statement runs on the connector's own connection");
        assertEquals(result, OffsetExtraInfo.lastResult());
    }

    @Test
    @DisplayName("column missing: one ADD COLUMN IF NOT EXISTS runs on the system connection, then the insert is overridden")
    public void columnMissingIsAdded() {
        Fake fake = new Fake();
        Properties props = props();

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.ACTIVE, result.state(), result.detail());
        assertEquals(List.of(ALTER), fake.on("system"), "exactly one DDL, and it is the metadata-only ADD COLUMN");
        assertTrue(props.containsKey(OffsetExtraInfo.INSERT_KEY));
        for (String sql : fake.on("store")) {
            assertTrue(sql.startsWith("SELECT"), "the offset store endpoint only reads: " + sql);
        }
    }

    @Test
    @DisplayName("the ALTER fails: no property is set, nothing is thrown, the state says why")
    public void alterFails() {
        Fake fake = new Fake();
        fake.alterFails = true;
        Properties props = props();
        Properties before = copy(props);

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.COLUMN_ABSENT, result.state());
        assertTrue(result.detail().contains("ADD COLUMN extra_info failed"), result.detail());
        assertTrue(result.detail().contains("Code: 497"), result.detail());
        assertUntouched(before, props);
        assertEquals(1, fake.on("system").size(), "the ALTER is tried once and never retried");
    }

    @Test
    @DisplayName("the ALTER succeeds but the offset store's endpoint cannot see the column: not active")
    public void alterInvisibleOnTheStoreEndpoint() {
        Fake fake = new Fake();
        fake.alterVisible = false;
        Properties props = props();
        Properties before = copy(props);

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.COLUMN_ABSENT, result.state());
        assertTrue(result.detail().contains("not visible"), result.detail());
        assertUntouched(before, props);
    }

    @Test
    @DisplayName("an offset insert the operator wrote is never replaced, and no connection is opened")
    public void operatorInsertWins() {
        Fake fake = new Fake();
        Properties props = props();
        String theirs = "INSERT INTO %s(id, offset_key, offset_val, record_insert_ts, record_insert_seq) "
                + "VALUES ( ?, ?, ?, ?, ? )";
        props.setProperty(OffsetExtraInfo.INSERT_KEY, theirs);
        Properties before = copy(props);

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.USER_INSERT, result.state());
        assertEquals(before, props);
        assertEquals(theirs, props.getProperty(OffsetExtraInfo.INSERT_KEY));
        assertEquals(0, fake.opened);
        assertEquals(List.of(), fake.log);
    }

    @Test
    @DisplayName("an insert under the pre-2.7.1 spelling is the operator's statement too, and it removes ours")
    public void operatorInsertUnderTheOldSpelling() {
        Fake fake = new Fake();
        fake.columnExists = true;
        Properties props = props();
        String theirs = "INSERT INTO %s(id, offset_key, offset_val, record_insert_ts, record_insert_seq) "
                + "VALUES ( ?, ?, ?, ?, ? )";
        props.setProperty(OffsetExtraInfo.INSERT_ALIAS_KEY, theirs);
        Properties before = copy(props);

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.USER_INSERT, result.state());
        assertEquals(before, props, "neither spelling is touched and the new one is not set");
        assertEquals(List.of(), fake.log);

        Properties later = props();
        assertEquals(OffsetExtraInfo.State.ACTIVE, run(later, fake).state());
        later.setProperty(OffsetExtraInfo.INSERT_ALIAS_KEY, theirs);
        assertEquals(OffsetExtraInfo.State.USER_INSERT, run(later, fake).state());
        assertFalse(later.containsKey(OffsetExtraInfo.INSERT_KEY), "our statement must not win over theirs");
        assertEquals(theirs, later.getProperty(OffsetExtraInfo.INSERT_ALIAS_KEY));
    }

    @Test
    @DisplayName("a rebuilt engine re-runs prepare: its own statement is replaced, an operator statement set later is kept")
    public void restartsTellOurStatementFromTheOperators() {
        Fake fake = new Fake();
        fake.columnExists = true;
        Properties props = props();

        assertEquals(OffsetExtraInfo.State.ACTIVE, run(props, fake).state());
        String first = props.getProperty(OffsetExtraInfo.INSERT_KEY);

        OffsetExtraInfo.Result again = OffsetExtraInfo.prepare(props, fake.system(), fake.endpoint(),
                () -> "{\"schema\":1,\"connector_version\":\"2.11.2\"}");
        assertEquals(OffsetExtraInfo.State.ACTIVE, again.state(), again.detail());
        assertFalse(first.equals(props.getProperty(OffsetExtraInfo.INSERT_KEY)), "the statement is refreshed");
        assertTrue(props.getProperty(OffsetExtraInfo.INSERT_KEY).contains("2.11.2"));

        String theirs = "INSERT INTO %s(id, offset_key, offset_val, record_insert_ts, record_insert_seq) "
                + "VALUES ( ?, ?, ?, ?, ? )";
        props.setProperty(OffsetExtraInfo.INSERT_KEY, theirs);
        assertEquals(OffsetExtraInfo.State.USER_INSERT, run(props, fake).state());
        assertEquals(theirs, props.getProperty(OffsetExtraInfo.INSERT_KEY));

        props.setProperty(OffsetExtraInfo.ENABLE_PROPERTY, "false");
        assertEquals(OffsetExtraInfo.State.DISABLED, run(props, fake).state());
        assertEquals(theirs, props.getProperty(OffsetExtraInfo.INSERT_KEY),
                "switching the feature off never removes a statement the operator wrote");
    }

    @Test
    @DisplayName("our statement is removed again when a rebuilt engine finds the column gone or the store unreadable")
    public void ownStatementIsRemovedWhenTheColumnIsGone() {
        Fake fake = new Fake();
        fake.columnExists = true;
        Properties props = props();
        assertEquals(OffsetExtraInfo.State.ACTIVE, run(props, fake).state());
        assertTrue(props.containsKey(OffsetExtraInfo.INSERT_KEY));

        fake.columnExists = false;
        fake.alterFails = true;
        assertEquals(OffsetExtraInfo.State.COLUMN_ABSENT, run(props, fake).state());
        assertFalse(props.containsKey(OffsetExtraInfo.INSERT_KEY), "the default five-column insert is back");

        fake.columnExists = true;
        assertEquals(OffsetExtraInfo.State.ACTIVE, run(props, fake).state());
        fake.storeOpenFails = true;
        assertEquals(OffsetExtraInfo.State.STORE_UNAVAILABLE, run(props, fake).state());
        assertFalse(props.containsKey(OffsetExtraInfo.INSERT_KEY));

        fake.storeOpenFails = false;
        assertEquals(OffsetExtraInfo.State.ACTIVE, run(props, fake).state());
        props.setProperty(OffsetExtraInfo.ENABLE_PROPERTY, "false");
        assertEquals(OffsetExtraInfo.State.DISABLED, run(props, fake).state());
        assertFalse(props.containsKey(OffsetExtraInfo.INSERT_KEY), "the kill switch takes our statement away");
    }

    @Test
    @DisplayName("the kill switch: only an explicit true, or no value, keeps the feature on")
    public void killSwitch() {
        for (String on : new String[]{null, "", "  ", "true", "TRUE", " True "}) {
            Properties props = new Properties();
            if (on != null) {
                props.setProperty(OffsetExtraInfo.ENABLE_PROPERTY, on);
            }
            assertTrue(OffsetExtraInfo.enabled(props), "value " + on);
        }
        for (String off : new String[]{"false", "FALSE", "no", "0", "yes", "1", "tru", "off"}) {
            Properties props = new Properties();
            props.setProperty(OffsetExtraInfo.ENABLE_PROPERTY, off);
            assertFalse(OffsetExtraInfo.enabled(props), "value " + off);
        }
        Fake fake = new Fake();
        Properties props = props();
        props.setProperty(OffsetExtraInfo.ENABLE_PROPERTY, "false");
        Properties before = copy(props);
        OffsetExtraInfo.Result result = run(props, fake);
        assertEquals(OffsetExtraInfo.State.DISABLED, result.state());
        assertEquals(before, props);
        assertEquals(List.of(), fake.log, "a disabled feature does not even look at the server");
    }

    @Test
    @DisplayName("offsets kept by another store, or a KeeperMap offset table, are left alone")
    public void otherStoresAreLeftAlone() {
        Fake fake = new Fake();

        Properties file = props();
        file.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        Properties fileBefore = copy(file);
        assertEquals(OffsetExtraInfo.State.NOT_JDBC_STORE, run(file, fake).state());
        assertEquals(fileBefore, file);

        Properties unset = props();
        unset.remove("offset.storage");
        assertEquals(OffsetExtraInfo.State.NOT_JDBC_STORE, run(unset, fake).state());
        assertFalse(unset.containsKey(OffsetExtraInfo.INSERT_KEY));

        String ddlKey = JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_DDL.name();
        for (String key : new String[]{ddlKey, "offset.storage.jdbc.offset.table.ddl"}) {
            Properties keeper = props();
            keeper.setProperty(key, "CREATE TABLE %s (id String) ENGINE = KeeperMap('/offsets') PRIMARY KEY id");
            Properties keeperBefore = copy(keeper);
            assertEquals(OffsetExtraInfo.State.KEEPER_MAP, run(keeper, fake).state(), key);
            assertEquals(keeperBefore, keeper);
        }
        assertEquals(0, fake.opened);
        assertEquals(List.of(), fake.log);
    }

    @Test
    @DisplayName("the offset table does not exist yet (first start of a new connector): nothing is created or altered")
    public void tableAbsent() {
        Fake fake = new Fake();
        fake.tableExists = false;
        Properties props = props();
        Properties before = copy(props);

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.TABLE_ABSENT, result.state());
        assertUntouched(before, props);
        assertEquals(List.of(), fake.on("system"));
    }

    @Test
    @DisplayName("an unreachable store, a missing table name or URL and a metadata error are STORE_UNAVAILABLE")
    public void storeUnavailable() {
        Fake refused = new Fake();
        refused.storeOpenFails = true;
        Properties a = props();
        Properties aBefore = copy(a);
        assertEquals(OffsetExtraInfo.State.STORE_UNAVAILABLE, run(a, refused).state());
        assertUntouched(aBefore, a);
        assertEquals(List.of(), refused.log);

        Fake fake = new Fake();
        Properties noName = props();
        noName.remove(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());
        assertEquals(OffsetExtraInfo.State.STORE_UNAVAILABLE, run(noName, fake).state());
        assertEquals(0, fake.opened);

        Fake denied = new Fake();
        denied.metadataFails = true;
        Properties b = props();
        Properties bBefore = copy(b);
        OffsetExtraInfo.Result result = run(b, denied);
        assertEquals(OffsetExtraInfo.State.STORE_UNAVAILABLE, result.state());
        assertTrue(result.detail().contains("Code: 60"), result.detail());
        assertUntouched(bBefore, b);
        assertEquals(List.of(), denied.on("system"), "no ALTER is tried when the table cannot be read");
        assertEquals(denied.opened, denied.closed, "the store connection is closed");

        Properties noUrl = props();
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(
                ZoneFixtures.withZones(new HashMap<>()));
        OffsetExtraInfo.Result production = OffsetExtraInfo.prepare(noUrl, null, config);
        assertEquals(OffsetExtraInfo.State.STORE_UNAVAILABLE, production.state());
        assertTrue(production.detail().contains("offset.storage.jdbc.url"), production.detail());
    }

    @Test
    @DisplayName("any other failure is absorbed as ERROR, with the properties as they were")
    public void anythingElseIsAbsorbed() {
        Fake fake = new Fake();
        fake.columnExists = true;
        Properties props = props();
        Properties before = copy(props);

        Supplier<String> broken = () -> {
            throw new IllegalStateException("could not write the connector static info");
        };
        OffsetExtraInfo.Result jsonFailure = OffsetExtraInfo.prepare(props, fake.system(), fake.endpoint(), broken);
        assertEquals(OffsetExtraInfo.State.ERROR, jsonFailure.state());
        assertUntouched(before, props);

        OffsetExtraInfo.Result endpointFailure = OffsetExtraInfo.prepare(props, fake.system(),
                () -> {
                    throw new IllegalArgumentException("bad url");
                }, () -> JSON);
        assertEquals(OffsetExtraInfo.State.ERROR, endpointFailure.state());
        assertTrue(endpointFailure.detail().contains("IllegalArgumentException"), endpointFailure.detail());
        assertUntouched(before, props);

        Fake missing = new Fake();
        OffsetExtraInfo.Result noConnection = OffsetExtraInfo.prepare(props, null, missing.endpoint(), () -> JSON);
        assertEquals(OffsetExtraInfo.State.ERROR, noConnection.state());
        assertUntouched(before, props);
        assertEquals(missing.opened, missing.closed, "the store connection is closed on every path");
    }

    @Test
    @DisplayName("every connection opened for the offset table is closed on every outcome")
    public void connectionsAreClosed() {
        for (int mode = 0; mode < 4; mode++) {
            Fake fake = new Fake();
            fake.columnExists = mode == 0;
            fake.alterFails = mode == 1;
            fake.tableExists = mode != 2;
            fake.metadataFails = mode == 3;
            run(props(), fake);
            assertEquals(fake.opened, fake.closed, "mode " + mode);
            OffsetExtraInfo.resetForTest();
        }
    }

    @Test
    @DisplayName("a table name without a database resolves against the database of the offset store connection")
    public void unqualifiedTableName() {
        Fake fake = new Fake();
        fake.currentDb = "other_db";
        Properties props = props();
        props.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name(), "replica_source_info");

        OffsetExtraInfo.Result result = run(props, fake);

        assertEquals(OffsetExtraInfo.State.ACTIVE, result.state(), result.detail());
        assertTrue(fake.on("store").contains("SELECT currentDatabase()"));
        assertTrue(fake.on("store").stream().anyMatch(s -> s.contains("[other_db, replica_source_info]")),
                fake.log.toString());
        assertEquals(List.of("ALTER TABLE `other_db`.`replica_source_info` ADD COLUMN IF NOT EXISTS `extra_info` "
                + "String DEFAULT ''"), fake.on("system"));
    }

    @Test
    @DisplayName("quotes around the configured name are removed, and a name that cannot be quoted safely is refused")
    public void tableNameQuoting() {
        Fake quoted = new Fake();
        quoted.columnExists = true;
        Properties props = props();
        props.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name(), "\"altinity_sink_connector\".`replica_source_info`");
        assertEquals(OffsetExtraInfo.State.ACTIVE, run(props, quoted).state());
        assertTrue(quoted.on("store").stream()
                .anyMatch(s -> s.contains("[altinity_sink_connector, replica_source_info]")), quoted.log.toString());

        for (String bad : new String[]{"db.ta\\ble", "db.ta\nble", "d\\b.table"}) {
            Fake fake = new Fake();
            Properties p = props();
            p.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                    + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name(), bad);
            Properties before = copy(p);
            OffsetExtraInfo.Result result = run(p, fake);
            assertNotNull(result);
            assertFalse(result.state() == OffsetExtraInfo.State.ACTIVE, bad);
            assertEquals(before, p, bad);
            assertEquals(List.of(), fake.on("system"), "no ALTER is built from the name " + bad);
        }
    }

    @Test
    @DisplayName("the statement keeps Debezium's five binds, survives String.format and holds the JSON as one literal")
    public void statementShape() {
        String statement = OffsetExtraInfo.insertStatement(JSON);
        assertEquals(5, statement.chars().filter(c -> c == '?').count(), "five binds, as Debezium sets them");
        assertTrue(statement.startsWith("INSERT INTO %s(id, offset_key, offset_val, record_insert_ts, "
                + "record_insert_seq, extra_info) VALUES ( ?, ?, ?, ?, ?, '"), statement);
        assertEquals("INSERT INTO db.t(id, offset_key, offset_val, record_insert_ts, record_insert_seq, extra_info) "
                + "VALUES ( ?, ?, ?, ?, ?, '" + JSON + "' )", String.format(statement, "db.t"));
    }

    @Test
    @DisplayName("percent signs, backslashes and apostrophes in the text come out of String.format unchanged")
    public void statementEscaping() {
        String text = "{\"a\":\"it's 100% \\\\ x %s %d\"}";
        String formatted = String.format(OffsetExtraInfo.insertStatement(text), "db.t");
        assertEquals("INSERT INTO db.t(id, offset_key, offset_val, record_insert_ts, record_insert_seq, extra_info) "
                + "VALUES ( ?, ?, ?, ?, ?, '{\"a\":\"it\\'s 100% \\\\\\\\ x %s %d\"}' )", formatted);
    }

    @Test
    @DisplayName("awkward connector text goes through the real JSON writer into a statement with five binds and two quotes")
    public void awkwardTextEndToEnd() {
        ConnectorStaticInfo.Inputs in = new ConnectorStaticInfo.Inputs(null, "inv", "/opt/x/app.jar",
                "name it's ?x? %s 100% \\ \"q\" ; -- /* */ é😀", "UTC", "UTC", false, "21", "v", "Linux",
                "amd64", "UTC", "3", 1L, "h", 1000L);
        String statement = OffsetExtraInfo.insertStatement(ConnectorStaticInfo.toJson(ConnectorStaticInfo.collect(in)));
        String formatted = String.format(statement, "db.t");
        assertEquals(5, formatted.chars().filter(c -> c == '?').count(), formatted);
        assertEquals(2, formatted.chars().filter(c -> c == '\'').count(), formatted);
        assertFalse(statement.contains("\n"), "one line");
    }

    @Test
    @DisplayName("a long run of prepare calls on one properties object never grows or changes anything else")
    public void repeatedRunsAreStable() {
        Fake fake = new Fake();
        fake.columnExists = true;
        Properties props = props();
        Properties before = copy(props);
        String last = null;
        for (int i = 0; i < 50; i++) {
            assertEquals(OffsetExtraInfo.State.ACTIVE, run(props, fake).state());
            String now = props.getProperty(OffsetExtraInfo.INSERT_KEY);
            if (last != null) {
                assertEquals(last, now);
            }
            last = now;
        }
        props.remove(OffsetExtraInfo.INSERT_KEY);
        assertEquals(before, props);
        assertEquals(50, fake.opened);
        assertEquals(50, fake.closed);
    }
}
