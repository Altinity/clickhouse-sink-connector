package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ddl.DdlCaptureFilter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warns at start when a MySQL source older than 9.6 has a captured child table
 * whose foreign key cascades (spec 01.10 section 3.4).
 *
 * <p>MySQL before 9.6 does not write to the binary log the child-row changes an
 * {@code ON DELETE CASCADE} or {@code ON UPDATE CASCADE} foreign key makes. The
 * parent change is logged; the child rows it removed or re-keyed are not. The
 * connector replicates only what the source logs, so the child table keeps rows
 * MySQL no longer has, and no error is raised at run time.</p>
 *
 * <p>The check is advisory. It names each affected captured table in one WARN
 * banner and never refuses the start: refusing would stop every correctly
 * replicated table on the same source, and the operator decides whether the
 * named tables are acceptable. It reads only, over a connection set read-only,
 * and never throws.</p>
 */
public final class ForeignKeyCascadePreflight {

    private static final Logger log = LogManager.getLogger(ForeignKeyCascadePreflight.class);

    /** The first MySQL release that binlogs the child-row changes a cascade makes. */
    static final int FIXED_MAJOR = 9;
    static final int FIXED_MINOR = 6;

    private static final Pattern MYSQL_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    /** Every foreign key whose parent delete or key update cascades, outside the system schemas. */
    static final String CASCADE_QUERY =
            "SELECT rc.CONSTRAINT_SCHEMA, rc.TABLE_NAME, rc.CONSTRAINT_NAME, rc.REFERENCED_TABLE_NAME, "
                    + "rc.DELETE_RULE, rc.UPDATE_RULE "
                    + "FROM information_schema.REFERENTIAL_CONSTRAINTS rc "
                    + "WHERE (rc.DELETE_RULE = 'CASCADE' OR rc.UPDATE_RULE = 'CASCADE') "
                    + "AND rc.CONSTRAINT_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')";

    private static final String BANNER_RULE =
            "========================================================================";

    /** One foreign key that cascades: the child table holds it, the parent is referenced. */
    static final class Cascade {
        final String schema;
        final String table;
        final String constraint;
        final String parent;
        final String deleteRule;
        final String updateRule;

        Cascade(String schema, String table, String constraint, String parent,
                String deleteRule, String updateRule) {
            this.schema = schema;
            this.table = table;
            this.constraint = constraint;
            this.parent = parent;
            this.deleteRule = deleteRule;
            this.updateRule = updateRule;
        }
    }

    private ForeignKeyCascadePreflight() {
    }

    /**
     * Runs the check against the configured MySQL source, once, at start.
     *
     * @param props the connector properties.
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
            log.warn("Foreign-key cascade check could not run: no MySQL host or user in the configuration.");
            return;
        }

        String url = KeylessTablePreflight.jdbcUrl(host, port, props);
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            // Before anything is executed: the connector must not change the server it replicates from.
            conn.setReadOnly(true);
            String version = scalar(conn, "SELECT VERSION()");
            if (version == null || !MYSQL_VERSION.matcher(version.trim()).find()
                    || version.toLowerCase().contains("mariadb")) {
                log.info("Foreign-key cascade check skipped: the source reports '{}', which is not a MySQL "
                        + "version this check covers.", version);
                return;
            }
            if (!belowFixedRelease(version)) {
                log.info("Foreign-key cascade check passed: MySQL {} binlogs the rows a foreign-key cascade changes.",
                        version);
                return;
            }
            List<Cascade> captured = captured(cascades(conn), props);
            if (captured.isEmpty()) {
                log.info("Foreign-key cascade check passed: no captured table has an ON DELETE or ON UPDATE "
                        + "CASCADE foreign key on MySQL {}.", version);
                return;
            }
            log.warn(warningText(version, captured));
        } catch (Exception e) {
            log.warn("Foreign-key cascade check could not run ({}). Continuing. A captured table with an ON DELETE "
                    + "or ON UPDATE CASCADE foreign key would diverge on MySQL before 9.6, and this check did not see "
                    + "whether one exists.", e.toString());
        }
    }

    /**
     * Whether the version is a MySQL release before 9.6. A MariaDB version, or one
     * this check cannot parse, is not below the release: the check does not cover it.
     *
     * @param version the value of {@code SELECT VERSION()}.
     * @return true when the source is MySQL and its release is below 9.6.
     */
    static boolean belowFixedRelease(String version) {
        if (version == null) {
            return false;
        }
        String trimmed = version.trim();
        if (trimmed.toLowerCase().contains("mariadb")) {
            return false;
        }
        Matcher m = MYSQL_VERSION.matcher(trimmed);
        if (!m.find()) {
            return false;
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        return major < FIXED_MAJOR || (major == FIXED_MAJOR && minor < FIXED_MINOR);
    }

    /**
     * The cascading foreign keys whose child table the connector captures.
     *
     * @param found every cascading foreign key on the source.
     * @param props the connector properties, read for the include and exclude lists.
     * @return the keys whose child table {@link DdlCaptureFilter#isCaptured} accepts.
     */
    static List<Cascade> captured(List<Cascade> found, Properties props) {
        List<Cascade> out = new ArrayList<>();
        for (Cascade c : found) {
            if (DdlCaptureFilter.isCaptured(c.schema, c.table, props)) {
                out.add(c);
            }
        }
        return out;
    }

    static List<Cascade> cascades(Connection conn) throws SQLException {
        KeylessTablePreflight.assertReadOnlySql(CASCADE_QUERY);
        List<Cascade> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(CASCADE_QUERY)) {
            while (rs.next()) {
                out.add(new Cascade(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
            }
        }
        return out;
    }

    static String warningText(String version, List<Cascade> captured) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(BANNER_RULE).append('\n');
        sb.append("  !!  CAPTURED TABLES WITH FOREIGN-KEY CASCADES ON MySQL ").append(version).append("  !!\n");
        sb.append(BANNER_RULE).append('\n');
        sb.append("  MySQL before ").append(FIXED_MAJOR).append('.').append(FIXED_MINOR)
                .append(" does not write to the binary log the child rows an ON DELETE or ON UPDATE CASCADE changes.\n");
        sb.append("  The connector replicates only what the source logs, so these child tables WILL DIVERGE from MySQL:\n");
        sb.append("  a DELETE of a parent row, or an UPDATE of its key, leaves the cascaded child rows in ClickHouse.\n");
        for (Cascade c : captured) {
            sb.append("    ").append(c.schema).append('.').append(c.table)
                    .append("  constraint ").append(c.constraint)
                    .append(" (parent ").append(c.parent)
                    .append(", ON DELETE ").append(c.deleteRule)
                    .append(", ON UPDATE ").append(c.updateRule).append(")\n");
        }
        sb.append("  REPLICATING ANYWAY. Remedies, any one of: upgrade the source to MySQL ")
                .append(FIXED_MAJOR).append('.').append(FIXED_MINOR)
                .append(" or later; remove the cascading constraint and enforce the rule in the application;")
                .append(" or re-synchronise each table above after each cascading change (ch-mysql-resync, spec 11.04).\n");
        sb.append(BANNER_RULE);
        return sb.toString();
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        KeylessTablePreflight.assertReadOnlySql(sql);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
