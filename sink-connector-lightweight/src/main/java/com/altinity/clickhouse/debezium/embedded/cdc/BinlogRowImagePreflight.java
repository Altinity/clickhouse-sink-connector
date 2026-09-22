package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Refuses to start against a MySQL source whose {@code binlog_row_image} is
 * not {@code FULL} (spec 01.01 §3.2, spec 10.04 §3.6).
 *
 * <p><b>Why this is a refusal and not a warning.</b> The connector turns every
 * row event into a full-row insert into a ReplacingMergeTree: the newest row
 * for a key REPLACES the previous one, column for column. That is only correct
 * if every row event carries every column. With {@code binlog_row_image =
 * MINIMAL} an UPDATE's after-image carries only the columns the statement
 * changed (plus the key), so every column the statement did not touch is
 * written as NULL — or, worse, as a ClickHouse column default — on EVERY
 * update of EVERY table, with row counts intact. With {@code NOBLOB} the same
 * happens to every BLOB/TEXT column not named in the statement. Nothing
 * downstream can recover a value the source never logged, so unlike the
 * keyless-table report ({@link KeylessTablePreflight}, which names specific
 * tables the operator may knowingly accept) this is all-or-nothing: the
 * connector must not start.</p>
 *
 * <p>The check is read-only against the source, on the same footing as the
 * keyless-table check: one {@code SELECT} (a {@code SHOW GLOBAL VARIABLES LIKE
 * 'binlog_row_image'} equivalent that passes the SELECT-only allowlist) on a
 * connection opened read-only, and never a {@code SET}. A source that cannot
 * be asked — unreachable, permission denied — is logged at WARN and allowed
 * through, exactly as the keyless check does: a check that could not run must
 * not take down a healthy pipeline, and Debezium itself will fail loudly on an
 * unreachable source. The value the server reports is trusted as-is.</p>
 *
 * <p>{@code binlog.row.image.check.skip=true} lets a non-FULL source through
 * for an operator who has a reason — and is logged at WARN every start,
 * because silencing a correctness check must not itself be quiet.</p>
 */
public final class BinlogRowImagePreflight {

    private static final Logger log = LogManager.getLogger(BinlogRowImagePreflight.class);

    /** Escape hatch, for a source the operator knows is safe. */
    static final String SKIP_PROPERTY = "binlog.row.image.check.skip";

    /** The value the connector requires. */
    static final String REQUIRED = "FULL";

    /**
     * The one statement this class issues. {@code SELECT @@GLOBAL.x} reads the
     * same value as {@code SHOW GLOBAL VARIABLES LIKE 'x'} and passes
     * {@link KeylessTablePreflight#assertReadOnlySql}'s SELECT-only allowlist.
     */
    static final String QUERY = "SELECT @@GLOBAL.binlog_row_image";

    private static final String BANNER_RULE =
            "========================================================================";

    private BinlogRowImagePreflight() {
    }

    /**
     * Runs the check against the configured MySQL source.
     *
     * <p>Only MySQL is checked; other connectors pass through untouched. A
     * connection or permission failure is logged and allowed through. A
     * readable value other than {@code FULL} REFUSES startup.</p>
     *
     * @param props the connector properties.
     * @throws IllegalStateException when {@code binlog_row_image} is readable
     *                               and not {@code FULL} (unless skipped).
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
            log.warn("binlog_row_image check skipped: no MySQL host/user in the configuration.");
            return;
        }
        String url = KeylessTablePreflight.jdbcUrl(host, port, props);
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            check(props, conn);
        } catch (IllegalStateException refused) {
            throw refused;
        } catch (Exception e) {
            // Never block a healthy pipeline on this check itself.
            log.warn("binlog_row_image check could not run ({}). Continuing. If the source runs with "
                    + "binlog_row_image=MINIMAL or NOBLOB, every UPDATE will replicate its untouched "
                    + "columns as NULL.", e.toString());
        }
    }

    /**
     * The check against an open connection to the source.
     *
     * @param props the connector properties.
     * @param conn  an open connection to the source; it is set read-only.
     * @throws IllegalStateException when {@code binlog_row_image} is readable
     *                               and not {@code FULL} (unless skipped).
     */
    static void check(Properties props, Connection conn) {
        String value = rowImage(conn);
        if (value == null) {
            log.warn("binlog_row_image could not be read from the source. Continuing. If it is not "
                    + "FULL, every UPDATE will replicate its untouched columns as NULL.");
            return;
        }
        if (REQUIRED.equalsIgnoreCase(value.trim())) {
            log.info("binlog_row_image check passed: the source logs full row images (binlog_row_image={}).",
                    value);
            return;
        }
        String message = String.format(
                "binlog_row_image=%s on the MySQL source; the connector requires %s. With %s an UPDATE's "
                        + "row image omits the columns the statement did not change, so every update of "
                        + "every table would be replicated with those columns as NULL -- a value-level "
                        + "divergence on every update, with row counts intact. Nothing downstream can "
                        + "recover a value the source never logged. Fix the source and restart:\n"
                        + "    SET GLOBAL binlog_row_image = FULL;\n"
                        + "    # and in my.cnf so it survives a restart:\n"
                        + "    binlog_row_image = FULL\n"
                        + "(rows logged before the change stay incomplete; resnapshot the affected "
                        + "tables). To start anyway, set %s=true.",
                value, REQUIRED, value, SKIP_PROPERTY);
        if (Boolean.parseBoolean(props.getProperty(SKIP_PROPERTY, "false"))) {
            // Loud on purpose: the override silences a correctness refusal.
            log.warn("\n{}\n  !!  {}=true -- BINLOG ROW IMAGE CHECK DISABLED  !!\n{}\n  {}\n{}",
                    BANNER_RULE, SKIP_PROPERTY, BANNER_RULE, message, BANNER_RULE);
            return;
        }
        log.error("\n{}\n  !!  REFUSING TO START: {}\n{}", BANNER_RULE, message, BANNER_RULE);
        throw new IllegalStateException("Refusing to start: " + message);
    }

    /**
     * Reads {@code @@GLOBAL.binlog_row_image} on a read-only connection.
     *
     * @return the value, or {@code null} when it could not be read.
     */
    private static String rowImage(Connection conn) {
        try {
            try {
                conn.setReadOnly(true);
            } catch (Exception e) {
                log.warn("Could not set the binlog_row_image check's connection read-only ({}). The "
                        + "check still issues only a SELECT, enforced client-side.", e.toString());
            }
            KeylessTablePreflight.assertReadOnlySql(QUERY);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(QUERY)) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            log.warn("binlog_row_image could not be read ({}).", e.toString());
            return null;
        }
    }
}
