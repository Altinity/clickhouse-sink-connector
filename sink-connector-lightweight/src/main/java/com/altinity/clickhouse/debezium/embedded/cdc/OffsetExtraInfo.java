package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Writes the static facts about the running connector (version and the like) into the
 * {@code extra_info} column of its offset table, so a dashboard can read them back (spec 09.05).
 *
 * <p><b>Optional by construction.</b> The feature never decides whether the connector runs and never
 * touches replication. {@link #prepare} is a best-effort step before the engine is built. It reads the
 * offset table through the same endpoint the offset store will use, tries one {@code ADD COLUMN IF NOT
 * EXISTS} when the column is missing, and only when the column is then visible on that endpoint does it
 * set one Debezium property. Every other outcome (feature off, other offset store, an offset statement
 * the operator wrote, table not created yet, column missing, any exception) leaves the properties
 * exactly as they were and the start goes on. It never throws.</p>
 *
 * <p><b>The one property.</b> Debezium's JDBC offset store runs the statement in
 * {@code offset.storage.jdbc.table.insert} for every checkpoint and binds five parameters. The
 * override adds a sixth column whose value is a SQL literal, so the five binds are untouched and the
 * stored offset value is byte-for-byte the one Debezium produced. The column has a default of the empty
 * string, so a writer that does not know the column (an older connector sharing the table, the REST
 * position edit) still writes valid rows and the column stays empty for them.</p>
 *
 * <p><b>Restarts.</b> The engine can be rebuilt in-process, which runs {@link #prepare} again on the
 * same properties. The statement this class set is remembered, so a later run tells its own statement
 * from one the operator wrote, replaces it, or removes it when the column is gone.</p>
 */
final class OffsetExtraInfo {

    private static final Logger log = LogManager.getLogger(OffsetExtraInfo.class);

    /** The user-file key that turns the feature off. Default true. */
    static final String ENABLE_PROPERTY = "offset.extra.info.enable";

    /** The column the JSON text is written to. */
    static final String COLUMN = "extra_info";

    /** Debezium's property for the offset insert statement. */
    static final String INSERT_KEY = JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
            + JdbcOffsetBackingStoreConfig.PROP_TABLE_INSERT.name();

    /**
     * The pre-2.7.1 spelling of {@link #INSERT_KEY}. Debezium still reads it, so a statement under it is the
     * operator's statement too.
     */
    static final String INSERT_ALIAS_KEY = "offset.storage.jdbc.offset.table.insert";

    private static final String TABLE_NAME_KEY =JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
            + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();
    private static final String URL_KEY = "offset.storage.jdbc.url";
    private static final String USER_KEY = "offset.storage.jdbc.user";
    private static final String PASSWORD_KEY = "offset.storage.jdbc.password";
    private static final String STORE_CLASS_KEY = "offset.storage";
    private static final String JDBC_STORE_CLASS = "JdbcOffsetBackingStore";

    /** Seconds a statement of this class may run before the driver gives up on it. */
    static final int STATEMENT_TIMEOUT_SECONDS = 30;

    /** Why the column is, or is not, being written. */
    enum State {
        /** The insert statement carries the column. */
        ACTIVE,
        /** {@value #ENABLE_PROPERTY} is false (or not a boolean). */
        DISABLED,
        /** The offsets are not kept by the JDBC offset store. */
        NOT_JDBC_STORE,
        /** The offset table is a KeeperMap table. */
        KEEPER_MAP,
        /** The operator set the offset insert statement. */
        USER_INSERT,
        /** The offset table cannot be reached or named. */
        STORE_UNAVAILABLE,
        /** The offset table does not exist yet (the first start of a new connector). */
        TABLE_ABSENT,
        /** The column is missing and could not be added. */
        COLUMN_ABSENT,
        /** Anything else went wrong. */
        ERROR
    }

    /**
     * The outcome of a {@link #prepare} call.
     *
     * @param state  the state
     * @param detail one line that says why
     */
    record Result(State state, String detail) {
    }

    /** Opens a connection to the endpoint the offset store uses. A test replaces it with a stub. */
    @FunctionalInterface
    interface StoreEndpoint {
        Connection open() throws SQLException;
    }

    /** The statement this class last set, or null. See "Restarts" above. */
    private static volatile String appliedInsert;

    /** The last outcome, for tests and diagnostics. */
    private static volatile Result lastResult = new Result(State.DISABLED, "not run");

    private OffsetExtraInfo() {
    }

    /**
     * The last outcome of {@link #prepare}.
     *
     * @return the outcome, never null
     */
    static Result lastResult() {
        return lastResult;
    }

    /** Forgets the remembered statement and outcome. Tests only. */
    static void resetForTest() {
        appliedInsert = null;
        lastResult = new Result(State.DISABLED, "not run");
    }

    /**
     * Production entry. Never throws.
     *
     * @param props            the connector properties; the offset insert statement may be set in place
     * @param systemConnection the connector's own ClickHouse connection, used for the one ALTER
     * @param config           the connector configuration
     * @return the outcome
     */
    static Result prepare(Properties props, Connection systemConnection, ClickHouseSinkConnectorConfig config) {
        StoreEndpoint endpoint = () -> {
            String url = props.getProperty(URL_KEY);
            if (url == null || url.isBlank()) {
                throw new SQLException("no " + URL_KEY + " is set");
            }
            Properties credentials = new Properties();
            String user = props.getProperty(USER_KEY);
            String password = props.getProperty(PASSWORD_KEY);
            if (user != null) {
                credentials.setProperty("user", user);
            }
            if (password != null) {
                credentials.setProperty("password", password);
            }
            return DriverManager.getConnection(ValidatingClickHouseDriver.toDelegateUrl(url.trim()), credentials);
        };
        return prepare(props, systemConnection, endpoint,
                () -> ConnectorStaticInfo.toJson(ConnectorStaticInfo.collect(
                        ConnectorStaticInfo.Inputs.live(props, config))));
    }

    /**
     * Decides whether the offset insert carries {@value #COLUMN}, and sets the property when it does.
     * Never throws: any exception ends in {@link State#ERROR} with the properties as they were.
     *
     * @param props            the connector properties
     * @param systemConnection the connector's own ClickHouse connection
     * @param endpoint         opens the connection the offset store will use
     * @param json             supplies the JSON text of the static facts
     * @return the outcome
     */
    static Result prepare(Properties props, Connection systemConnection, StoreEndpoint endpoint,
                          Supplier<String> json) {
        Result result;
        try {
            result = decide(props, systemConnection, endpoint, json);
        } catch (Exception e) {
            clearOwnOverride(props);
            result = new Result(State.ERROR, e.getClass().getSimpleName() + ": " + shorten(e.getMessage()));
        }
        lastResult = result;
        report(result);
        return result;
    }

    private static Result decide(Properties props, Connection systemConnection, StoreEndpoint endpoint,
                                 Supplier<String> json) throws Exception {
        if (!enabled(props)) {
            clearOwnOverride(props);
            return new Result(State.DISABLED, ENABLE_PROPERTY + " is not true");
        }
        String store = props.getProperty(STORE_CLASS_KEY);
        if (store == null || !store.contains(JDBC_STORE_CLASS)) {
            clearOwnOverride(props);
            return new Result(State.NOT_JDBC_STORE, "offsets are not kept by the JDBC offset store");
        }
        if (DebeziumOffsetStorage.isKeeperMapOffsetTable(props)) {
            clearOwnOverride(props);
            return new Result(State.KEEPER_MAP, "the offset table is a KeeperMap table");
        }
        String alias = props.getProperty(INSERT_ALIAS_KEY);
        if (alias != null && !alias.isBlank()) {
            clearOwnOverride(props);
            return new Result(State.USER_INSERT, "the operator set " + INSERT_ALIAS_KEY + "; it is left as written");
        }
        String configured = props.getProperty(INSERT_KEY);
        if (configured != null && !configured.isBlank() && !configured.equals(appliedInsert)) {
            return new Result(State.USER_INSERT, "the operator set " + INSERT_KEY + "; it is left as written");
        }
        String tableName = props.getProperty(TABLE_NAME_KEY);
        if (tableName == null || tableName.isBlank()) {
            clearOwnOverride(props);
            return new Result(State.STORE_UNAVAILABLE, "no " + TABLE_NAME_KEY + " is set");
        }
        try (Connection store1 = endpoint.open()) {
            return decideOnStore(props, systemConnection, store1, tableName, json);
        } catch (SQLException e) {
            clearOwnOverride(props);
            return new Result(State.STORE_UNAVAILABLE,
                    "the offset store endpoint could not be read: " + shorten(e.getMessage()));
        }
    }

    private static Result decideOnStore(Properties props, Connection systemConnection, Connection store,
                                        String tableName, Supplier<String> json) throws Exception {
        String[] parts = splitTableName(tableName, store);
        String database = parts[0];
        String table = parts[1];
        if (!tableExists(store, database, table)) {
            clearOwnOverride(props);
            return new Result(State.TABLE_ABSENT, "the offset table " + database + "." + table
                    + " does not exist yet; the column is added and filled from the next start");
        }
        if (!columnExists(store, database, table)) {
            try {
                addColumn(systemConnection, database, table);
            } catch (SQLException e) {
                clearOwnOverride(props);
                return new Result(State.COLUMN_ABSENT, "ALTER TABLE " + database + "." + table + " ADD COLUMN "
                        + COLUMN + " failed: " + shorten(e.getMessage()));
            }
            if (!columnExists(store, database, table)) {
                clearOwnOverride(props);
                return new Result(State.COLUMN_ABSENT, COLUMN + " is not visible on " + database + "." + table
                        + " through the offset store endpoint after the ALTER");
            }
        }
        String statement = insertStatement(json.get());
        props.setProperty(INSERT_KEY, statement);
        appliedInsert = statement;
        return new Result(State.ACTIVE, COLUMN + " of " + database + "." + table + " is written with each offset");
    }

    /**
     * Whether the operator allows the feature. An absent or blank key is true. An explicit true or false
     * is honoured in any case. Any other value switches the feature off: it is optional, so the cautious
     * reading of a typo is "off".
     *
     * @param props the connector properties
     * @return true when the feature may run
     */
    static boolean enabled(Properties props) {
        String raw = props.getProperty(ENABLE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return raw.trim().equalsIgnoreCase("true");
    }

    /**
     * Removes the statement this class set earlier, and only that one.
     *
     * @param props the connector properties
     */
    static void clearOwnOverride(Properties props) {
        String ours = appliedInsert;
        if (ours != null && ours.equals(props.getProperty(INSERT_KEY))) {
            props.remove(INSERT_KEY);
        }
        appliedInsert = null;
    }

    /**
     * The offset insert statement with the {@value #COLUMN} column. {@code %s} is the table name, filled by
     * Debezium with {@link String#format}, so every {@code %} of the literal is doubled. The literal escapes
     * the backslash and the apostrophe. {@link ConnectorStaticInfo#toJson} already wrote the apostrophe and
     * the question mark as JSON escapes, so the literal holds neither.
     *
     * @param json the JSON text
     * @return the statement text, with five {@code ?} placeholders
     */
    static String insertStatement(String json) {
        String literal = json.replace("\\", "\\\\").replace("'", "\\'").replace("%", "%%");
        return "INSERT INTO %s(id, offset_key, offset_val, record_insert_ts, record_insert_seq, " + COLUMN
                + ") VALUES ( ?, ?, ?, ?, ?, '" + literal + "' )";
    }

    /**
     * Splits the configured offset table name. A name without a database resolves against the database of
     * the offset store connection, which is where Debezium creates it.
     */
    private static String[] splitTableName(String configured, Connection store) throws SQLException {
        String name = configured.replace("\"", "").replace("`", "").trim();
        int dot = name.indexOf('.');
        String database;
        String table;
        if (dot > 0 && dot < name.length() - 1) {
            database = name.substring(0, dot);
            table = name.substring(dot + 1);
        } else {
            database = currentDatabase(store);
            table = name;
        }
        for (String part : new String[]{database, table}) {
            if (part.isEmpty() || part.indexOf('`') >= 0 || part.indexOf('\\') >= 0 || part.indexOf('\n') >= 0) {
                throw new SQLException("the offset table name " + configured + " cannot be quoted safely");
            }
        }
        return new String[]{database, table};
    }

    private static String currentDatabase(Connection store) throws SQLException {
        try (Statement st = store.createStatement()) {
            st.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery("SELECT currentDatabase()")) {
                if (rs.next() && rs.getString(1) != null) {
                    return rs.getString(1);
                }
            }
        }
        throw new SQLException("SELECT currentDatabase() returned no row");
    }

    private static boolean tableExists(Connection store, String database, String table) throws SQLException {
        return count(store, "SELECT count() FROM system.tables WHERE database = ? AND name = ?",
                database, table) > 0;
    }

    private static boolean columnExists(Connection store, String database, String table) throws SQLException {
        return count(store, "SELECT count() FROM system.columns WHERE database = ? AND table = ? AND name = ?",
                database, table, COLUMN) > 0;
    }

    private static long count(Connection connection, String sql, String... binds) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            for (int i = 0; i < binds.length; i++) {
                ps.setString(i + 1, binds[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** The one DDL of this class: a metadata-only ADD COLUMN, a no-op when the column exists. */
    private static void addColumn(Connection connection, String database, String table) throws SQLException {
        String ddl = "ALTER TABLE `" + database + "`.`" + table + "` ADD COLUMN IF NOT EXISTS `" + COLUMN
                + "` String DEFAULT ''";
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            st.execute(ddl);
        }
    }

    private static void report(Result result) {
        if (result.state() == State.ACTIVE) {
            log.info("OFFSET-EXTRA-INFO state={} {}", result.state(), result.detail());
        } else if (result.state() == State.DISABLED || result.state() == State.NOT_JDBC_STORE
                || result.state() == State.KEEPER_MAP || result.state() == State.USER_INSERT) {
            log.info("OFFSET-EXTRA-INFO state={} {}; {} is not populated", result.state(), result.detail(), COLUMN);
        } else {
            log.warn("OFFSET-EXTRA-INFO state={} {}; {} is not populated. Replication is not affected.",
                    result.state(), result.detail(), COLUMN);
        }
    }

    private static String shorten(String message) {
        if (message == null) {
            return "no message";
        }
        String oneLine = message.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300);
    }
}
