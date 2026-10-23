package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Refuses to start against a MySQL source whose row image or row value options
 * would replicate wrongly (spec 01.01 §3.2, spec 01.10 §3.1, spec 10.04 §3.6).
 *
 * <p><b>Why this is a refusal and not a warning.</b> The connector turns every
 * row event into a full-row insert into a ReplacingMergeTree: the newest row
 * for a key REPLACES the previous one, column for column. That is only correct
 * if every row event carries every column. With {@code binlog_row_image =
 * MINIMAL} an UPDATE's after-image carries only the columns the statement
 * changed (plus the key), so every column the statement did not touch is
 * written as NULL — or, worse, as a ClickHouse column default — on EVERY
 * update of EVERY table, with row counts intact. With {@code NOBLOB} the same
 * happens to every BLOB/TEXT column not named in the statement. With
 * {@code binlog_row_value_options} containing {@code PARTIAL_JSON} an UPDATE of
 * a JSON column is logged as a partial update event that Debezium drops, with
 * the rest of the statement's columns. Nothing downstream can recover a value
 * the source never logged, so this is all-or-nothing: the connector must not
 * start.</p>
 *
 * <p>The check is read-only against the source: two {@code SELECT}s on a
 * connection opened read-only, and never a {@code SET}. It runs at every engine
 * creation: at start, and before each in-process engine restart. A source that
 * cannot be asked (unreachable, login refused, no value) is a refusal too
 * ({@link Unreadable}): the connector cannot show that the source logs full row
 * images. At restart an unreadable source is a failed attempt that spends the
 * retry budget, and a refusal is terminal (spec 10.04 §3.6). The periodic
 * re-check is not implemented: the restart re-check and the partial update
 * event check (spec 01.10) cover the cases a periodic check would.</p>
 *
 * <p>{@code binlog.row.image.check.skip=true} lets a source through for both
 * values, for an operator who has a reason, and is logged at WARN every
 * start, because silencing a correctness check must not itself be quiet. The
 * partial update event check is not governed by this property.</p>
 */
public final class BinlogRowImagePreflight {

    private static final Logger log = LogManager.getLogger(BinlogRowImagePreflight.class);

    /** Escape hatch, for a source the operator knows is safe. */
    static final String SKIP_PROPERTY = "binlog.row.image.check.skip";

    /** The value the connector requires. */
    static final String REQUIRED = "FULL";

    /** The row value option that makes MySQL log partial updates (spec 01.10). */
    static final String FORBIDDEN_VALUE_OPTION = "PARTIAL_JSON";

    /**
     * The statements this class issues. {@code SELECT @@GLOBAL.x} reads the
     * same value as {@code SHOW GLOBAL VARIABLES LIKE 'x'} and passes
     * {@link KeylessTablePreflight#assertReadOnlySql}'s SELECT-only allowlist.
     */
    static final String QUERY = "SELECT @@GLOBAL.binlog_row_image";
    static final String VALUE_OPTIONS_QUERY = "SELECT @@GLOBAL.binlog_row_value_options";

    /**
     * MySQL's "Unknown system variable". A server before 8.0 has no
     * {@code binlog_row_value_options} and cannot log partial updates.
     */
    private static final int UNKNOWN_VARIABLE = 1193;

    private static final String BANNER_RULE =
            "========================================================================";

    /** Opens the connection the check reads through. A test replaces it with a stub source. */
    @FunctionalInterface
    interface ConnectionOpener {
        Connection open(String url, String user, String password) throws SQLException;
    }

    static volatile ConnectionOpener opener = DriverManager::getConnection;

    /**
     * The source could not be asked. Unlike a refusal, a later attempt may find
     * it readable, so an engine restart counts it as a failed attempt.
     */
    static final class Unreadable extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        Unreadable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private BinlogRowImagePreflight() {
    }

    /**
     * Runs the check against the configured MySQL source.
     *
     * <p>Only MySQL is checked; other connectors pass through untouched. A
     * source that cannot be read is refused, as is a readable value that is
     * not {@code FULL} or that includes {@code PARTIAL_JSON} (unless skipped).</p>
     *
     * @param props the connector properties.
     * @throws IllegalStateException when the source is refused, or
     *                               {@link Unreadable} when it cannot be read
     *                               (both unless skipped).
     */
    public static void check(Properties props) {
        String connector = props.getProperty("connector.class", "");
        if (!connector.toLowerCase().contains("mysql")) {
            return;
        }
        String host = props.getProperty("database.hostname");
        String port = props.getProperty("database.port", "3306");
        String user = props.getProperty("database.user");
        String password = props.getProperty("database.password");
        if (host == null || user == null) {
            unreadable(props, "the configuration names no MySQL host or user", null);
            return;
        }
        String url = KeylessTablePreflight.jdbcUrl(host, port, props);
        try (Connection conn = opener.open(url, user, password)) {
            check(props, conn);
        } catch (IllegalStateException refused) {
            throw refused;
        } catch (Exception e) {
            unreadable(props, "the MySQL source could not be reached or logged in to (" + e + ")", e);
        }
    }

    /**
     * The check against an open connection to the source.
     *
     * @param props the connector properties.
     * @param conn  an open connection to the source; it is set read-only.
     * @throws IllegalStateException when a value is refused, or
     *                               {@link Unreadable} when a value cannot be read
     *                               (both unless skipped).
     */
    static void check(Properties props, Connection conn) {
        String rowImage;
        String valueOptions;
        try {
            rowImage = readGlobal(conn, QUERY, false);
            valueOptions = readGlobal(conn, VALUE_OPTIONS_QUERY, true);
        } catch (Exception e) {
            unreadable(props, "a row format variable could not be read from the MySQL source (" + e + ")", e);
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!REQUIRED.equalsIgnoreCase(rowImage.trim())) {
            problems.add(rowImageMessage(rowImage));
        }
        if (valueOptions != null && valueOptions.toUpperCase().contains(FORBIDDEN_VALUE_OPTION)) {
            problems.add(valueOptionsMessage(valueOptions));
        }
        if (problems.isEmpty()) {
            log.info("binlog_row_image check passed: the source logs full row images (binlog_row_image={}, "
                    + "binlog_row_value_options={}).", rowImage, valueOptions == null ? "not supported" : valueOptions);
            return;
        }
        refuse(props, String.join("\n\n", problems), null, false);
    }

    /**
     * Reads one GLOBAL variable on a read-only connection.
     *
     * @param mayBeAbsent whether MySQL error 1193 (no such variable) is an answer, not a failure.
     * @return the value, or {@code null} when {@code mayBeAbsent} and the variable does not exist.
     * @throws SQLException when the query fails, or returns no value.
     */
    private static String readGlobal(Connection conn, String query, boolean mayBeAbsent) throws SQLException {
        try {
            try {
                conn.setReadOnly(true);
            } catch (SQLException e) {
                log.warn("Could not set the binlog row format check's connection read-only ({}). The "
                        + "check still issues only a SELECT, enforced client-side.", e.toString());
            }
            KeylessTablePreflight.assertReadOnlySql(query);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(query)) {
                String value = rs.next() ? rs.getString(1) : null;
                if (value == null) {
                    throw new SQLException("the server returned no value for " + query);
                }
                return value;
            }
        } catch (SQLException e) {
            if (mayBeAbsent && e.getErrorCode() == UNKNOWN_VARIABLE) {
                return null;
            }
            throw e;
        }
    }

    private static String rowImageMessage(String value) {
        return String.format(
                "binlog_row_image=%s on the MySQL source; the connector requires %s. With %s an UPDATE's "
                        + "row image omits the columns the statement did not change, so every update of "
                        + "every table would be replicated with those columns as NULL -- a value-level "
                        + "divergence on every update, with row counts intact. Nothing downstream can "
                        + "recover a value the source never logged. Fix the source and restart:\n"
                        + "    SET GLOBAL binlog_row_image = FULL;\n"
                        + "    # and in my.cnf so it survives a restart:\n"
                        + "    binlog_row_image = FULL\n"
                        + "(rows logged before the change stay incomplete; resnapshot the affected tables).",
                value, REQUIRED, value);
    }

    private static String valueOptionsMessage(String value) {
        return String.format(
                "binlog_row_value_options=%s on the MySQL source; the connector requires it empty. With %s an "
                        + "UPDATE of a JSON column is logged as a partial update event that the connector "
                        + "cannot replicate, so the rest of that statement's changes would be lost without an "
                        + "error. Fix the source and restart:\n"
                        + "    SET GLOBAL binlog_row_value_options = '';\n"
                        + "    # and in my.cnf so it survives a restart:\n"
                        + "    binlog_row_value_options = ''\n"
                        + "(updates logged before the change stay lost; re-synchronise the affected tables).",
                value, FORBIDDEN_VALUE_OPTION);
    }

    /** A source that cannot be read: refused at start and restart, unless skipped. */
    private static void unreadable(Properties props, String reason, Throwable cause) {
        refuse(props,
                "the source's row format cannot be read: " + reason + ". The connector cannot show that the "
                        + "source logs full row images (binlog_row_image=FULL, binlog_row_value_options "
                        + "without PARTIAL_JSON), so it does not start. Check the source, its reachability "
                        + "and this login, then restart.",
                cause, true);
    }

    /**
     * Refuses the engine, or lets it through with a WARN when the operator skipped the check.
     *
     * @param unreadable whether the refusal is a {@link Unreadable} (a retriable failure) rather than a verdict.
     */
    private static void refuse(Properties props, String message, Throwable cause, boolean unreadable) {
        if (Boolean.parseBoolean(props.getProperty(SKIP_PROPERTY, "false"))) {
            // Loud on purpose: the override silences a correctness refusal.
            log.warn("\n{}\n  !!  {}=true -- BINLOG ROW FORMAT CHECK DISABLED  !!\n{}\n  {}\n{}",
                    BANNER_RULE, SKIP_PROPERTY, BANNER_RULE, message, BANNER_RULE);
            return;
        }
        String text = message + " To start anyway, set " + SKIP_PROPERTY + "=true.";
        log.error("\n{}\n  !!  REFUSING TO START THE ENGINE: {}\n{}", BANNER_RULE, text, BANNER_RULE);
        if (unreadable) {
            throw new Unreadable("Refusing to start: " + text, cause);
        }
        throw new IllegalStateException("Refusing to start: " + text);
    }
}
