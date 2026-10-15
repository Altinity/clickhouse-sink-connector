package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Properties;

/**
 * Refuses to start against a MySQL source whose session time zone the JDBC
 * driver cannot name, when {@code database.connectionTimeZone} is not set
 * (spec 07.03 §3.1.5).
 *
 * <p><b>What the driver does.</b> With {@code database.connectionTimeZone}
 * empty, Connector/J derives the session zone from the source: {@code
 * @@time_zone}, or {@code @@system_time_zone} when {@code time_zone = SYSTEM}.
 * That second value is whatever the source host's C library reports, and on a
 * host in a DST zone it is an <em>abbreviation</em> such as {@code CDT}, which
 * names more than one zone. The driver then refuses every connection with
 * {@code The server time zone value 'CDT' is unrecognized or represents more
 * than one time zone}. Before this check the embedded engine failed at start
 * with exactly that error and was restarted {@code errors.max.retries} times
 * -- twenty, each with a full stack trace -- before {@code Replication is
 * STOPPED}: minutes of noise for a configuration that could never work.</p>
 *
 * <p><b>Agreement by construction.</b> The effective zone is resolved with the
 * driver's own {@code com.mysql.cj.util.TimeUtil#getCanonicalTimeZone}, not a
 * reimplemented table: IANA ids, offsets such as {@code +00:00}, {@code UTC}
 * and the driver's unambiguous abbreviations pass; {@code CDT}/{@code CST}-style
 * ambiguous abbreviations throw, exactly as they would at connect time. The
 * resolver is called reflectively through {@link MySqlJdbcDriver#canonicalTimeZone}:
 * the GPL-licensed driver is supplied at run time and this Apache-licensed
 * source does not link against it.</p>
 *
 * <p>The check is read-only against the source, on the same footing as the
 * keyless-table, row-image and compression checks: one {@code SELECT} on a
 * connection opened read-only, passed through
 * {@link KeylessTablePreflight#assertReadOnlySql} first, never a {@code SET}.
 * A source that cannot be asked -- unreachable, permission denied -- is
 * logged at WARN and allowed through: a check that could not run must not
 * take down a healthy pipeline, and the driver reports the failure itself.
 * With {@code database.connectionTimeZone} set nothing is queried: the driver
 * uses the configured value (an unparseable one still fails per §3.1.2, which
 * this class does not re-validate). Non-MySQL connectors are never touched.</p>
 */
public final class ConnectionTimeZonePreflight {

    private static final Logger log = LogManager.getLogger(ConnectionTimeZonePreflight.class);

    /** The Debezium/Connector/J property whose absence this check protects. */
    static final String PROPERTY = "database.connectionTimeZone";

    /**
     * The one statement this class issues. {@code SELECT @@GLOBAL.x} reads the
     * same values as {@code SHOW GLOBAL VARIABLES} and passes
     * {@link KeylessTablePreflight#assertReadOnlySql}'s SELECT-only allowlist.
     */
    static final String QUERY = "SELECT @@GLOBAL.time_zone, @@GLOBAL.system_time_zone";

    /** The {@code time_zone} value that defers to {@code system_time_zone}. */
    static final String SYSTEM = "SYSTEM";

    /** The example zone named in the refusal; the source of the incident that motivated the check. */
    static final String EXAMPLE_ZONE = "America/Chicago";

    private static final String BANNER_RULE =
            "========================================================================";

    /** How the check ended. */
    public enum State {
        /** {@link #PROPERTY} is set; nothing was queried. */
        CONFIGURED,
        /** The source's session zone was read and the driver resolves it. */
        RESOLVED,
        /** The source could not be asked; the start continues. */
        UNKNOWN,
        /** Not a MySQL connector; nothing was done. */
        SKIPPED
    }

    /** The result of one check, for the caller and for the tests. Immutable. */
    public static final class Outcome {
        public final State state;
        /** The configured {@link #PROPERTY}, or null when not set. */
        public final String configured;
        /** {@code @@GLOBAL.time_zone}, or null when not read. */
        public final String timeZone;
        /** {@code @@GLOBAL.system_time_zone}, or null when not read. */
        public final String systemTimeZone;
        /** The zone the driver would derive: {@code time_zone}, or {@code system_time_zone} under SYSTEM. */
        public final String effective;
        /** What the driver's {@code TimeUtil#getCanonicalTimeZone} made of {@link #effective}, or null when not resolved. */
        public final String canonical;

        Outcome(State state, String configured, String timeZone, String systemTimeZone,
                String effective, String canonical) {
            this.state = state;
            this.configured = configured;
            this.timeZone = timeZone;
            this.systemTimeZone = systemTimeZone;
            this.effective = effective;
            this.canonical = canonical;
        }

        @Override
        public String toString() {
            return "Outcome{state=" + state + ", configured=" + configured + ", timeZone=" + timeZone
                    + ", systemTimeZone=" + systemTimeZone + ", effective=" + effective
                    + ", canonical=" + canonical + "}";
        }
    }

    /** What one probe of the source found, or why it could not be read. */
    private static final class Probe {
        final String timeZone;
        final String systemTimeZone;
        /** Non-null when the probe failed; the values are then null. */
        final String failure;

        Probe(String timeZone, String systemTimeZone, String failure) {
            this.timeZone = timeZone;
            this.systemTimeZone = systemTimeZone;
            this.failure = failure;
        }

        static Probe failed(String reason) {
            return new Probe(null, null, reason);
        }
    }

    private ConnectionTimeZonePreflight() {
    }

    /**
     * Runs the check against the configured MySQL source.
     *
     * <p>Only MySQL is checked; other connectors pass through untouched. With
     * {@link #PROPERTY} set nothing is queried. A connection or permission
     * failure is logged at WARN and allowed through. A readable session zone
     * the driver cannot resolve REFUSES startup.</p>
     *
     * @param props the connector properties.
     * @return what was found.
     * @throws IllegalStateException when the source's session zone is readable
     *                               and the JDBC driver cannot map it to one zone.
     */
    public static Outcome check(Properties props) {
        if (!isMySql(props)) {
            return new Outcome(State.SKIPPED, null, null, null, null, null);
        }
        String configured = configured(props);
        if (configured != null) {
            return configuredOutcome(configured);
        }
        String host = props.getProperty("database.hostname");
        String port = props.getProperty("database.port", "3306");
        String user = props.getProperty("database.user");
        String password = props.getProperty("database.password");
        if (host == null || user == null) {
            return unknown("no MySQL host/user in the configuration");
        }
        String url = KeylessTablePreflight.jdbcUrl(host, port, props);
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            return check(props, conn);
        } catch (IllegalStateException refused) {
            throw refused;
        } catch (Exception e) {
            // Never block a healthy pipeline on this check itself.
            return unknown("the check could not connect to the MySQL source: " + e);
        }
    }

    /**
     * The check against an open connection to the source.
     *
     * @param props the connector properties.
     * @param conn  an open connection to the source; it is set read-only.
     * @return what was found.
     * @throws IllegalStateException when the source's session zone is readable
     *                               and the JDBC driver cannot map it to one zone.
     */
    static Outcome check(Properties props, Connection conn) {
        if (!isMySql(props)) {
            return new Outcome(State.SKIPPED, null, null, null, null, null);
        }
        String configured = configured(props);
        if (configured != null) {
            return configuredOutcome(configured);
        }
        Probe probe = probe(conn);
        if (probe.failure != null) {
            return unknown(probe.failure);
        }
        String effective = probe.timeZone;
        if (effective == null || SYSTEM.equalsIgnoreCase(effective.trim())) {
            effective = probe.systemTimeZone;
        }
        if (effective == null || effective.trim().isEmpty()) {
            return unknown(String.format("the source returned time_zone=%s, system_time_zone=%s",
                    probe.timeZone, probe.systemTimeZone));
        }
        effective = effective.trim();
        String canonical;
        // Look the resolver up first: a driver that is missing or lacks the
        // method is an installation problem, reported as such by
        // MySqlJdbcDriver, never as "the zone cannot be mapped".
        MySqlJdbcDriver.resolver(MySqlJdbcDriver.class.getClassLoader());
        try {
            // The driver's own resolver, so the check and the connect path agree by construction.
            canonical = MySqlJdbcDriver.canonicalTimeZone(effective);
        } catch (RuntimeException driverRefused) {
            refuse(String.format(
                    "%s is not set and the MySQL source reports time_zone=%s, system_time_zone='%s': the JDBC "
                            + "driver derives the session zone '%s' from those, and cannot map it to one time "
                            + "zone (%s). Every connection attempt would fail with \"The server time zone value "
                            + "'%s' is unrecognized or represents more than one time zone\", and the engine would "
                            + "be retried errors.max.retries times%s before replication stops. Fix: set %s to "
                            + "the IANA zone of the source host (for example %s%s) and restart; that zone is "
                            + "also the one Debezium uses to interpret TIMESTAMP columns (spec 07.03). "
                            + "Alternatively, set the source's global time_zone to a named zone "
                            + "(SET PERSIST time_zone = '%s'; the source's time zone tables must be loaded).",
                    PROPERTY, probe.timeZone, probe.systemTimeZone, effective, driverRefused.getMessage(),
                    effective, retriesHint(props), PROPERTY, EXAMPLE_ZONE, jvmZoneHint(), EXAMPLE_ZONE));
            return null; // unreachable: refuse throws
        }
        log.info("{} not set; the source session zone {} (time_zone={}, system_time_zone={}) resolves to {}; "
                        + "the JDBC driver will use it.",
                PROPERTY, effective, probe.timeZone, probe.systemTimeZone, canonical);
        return new Outcome(State.RESOLVED, null, probe.timeZone, probe.systemTimeZone, effective, canonical);
    }

    private static boolean isMySql(Properties props) {
        return props.getProperty("connector.class", "").toLowerCase(Locale.ROOT).contains("mysql");
    }

    /**
     * The configured property, or null when absent, blank, or the driver's
     * {@code SERVER} keyword -- which tells Connector/J to derive the zone from
     * the source exactly as an unset value does, so it needs the same probe.
     */
    private static String configured(Properties props) {
        String value = props.getProperty(PROPERTY);
        if (value == null || value.trim().isEmpty() || SERVER_KEYWORD.equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }

    /** Connector/J's {@code connectionTimeZone=SERVER}: "use the server's session zone". */
    static final String SERVER_KEYWORD = "SERVER";

    private static Outcome configuredOutcome(String configured) {
        log.info("{}={}: the JDBC driver uses it as the session zone; the source's time_zone is not read.",
                PROPERTY, configured);
        return new Outcome(State.CONFIGURED, configured, null, null, null, null);
    }

    /** WARN and continue: the probe itself never takes a healthy pipeline down. */
    private static Outcome unknown(String reason) {
        log.warn("{} is not set and the source's session time zone could not be read ({}). Continuing: the "
                        + "JDBC driver resolves the session zone itself and fails every connection if it "
                        + "cannot (time_zone=SYSTEM with an abbreviation such as CDT); set {} to the IANA zone "
                        + "of the source host to rule that out.",
                PROPERTY, reason, PROPERTY);
        return new Outcome(State.UNKNOWN, null, null, null, null, null);
    }

    /** The configured {@code errors.max.retries}, when the operator set one, for the refusal. */
    private static String retriesHint(Properties props) {
        String retries = props.getProperty("errors.max.retries");
        return retries == null || retries.trim().isEmpty() ? "" : " (" + retries.trim() + ")";
    }

    /**
     * Names this JVM's default zone as a hint -- the connector often runs in
     * the source's zone -- unless it is UTC, which says nothing about the source.
     */
    static String jvmZoneHint() {
        ZoneId zone;
        try {
            zone = ZoneId.systemDefault();
        } catch (RuntimeException e) {
            return "";
        }
        if (zone.normalized().equals(ZoneOffset.UTC)) {
            return "";
        }
        return "; this JVM's default zone is " + zone.getId()
                + ", which is the source's zone if the connector runs on the same host or in the same zone";
    }

    /**
     * Reads the two variables on a read-only connection.
     *
     * @return the values, or a failed probe naming why they could not be read.
     */
    private static Probe probe(Connection conn) {
        try {
            conn.setReadOnly(true);
        } catch (Exception e) {
            log.warn("Could not set the connection time zone check's connection read-only ({}). The check "
                    + "still issues only a SELECT, enforced client-side.", e.toString());
        }
        try {
            KeylessTablePreflight.assertReadOnlySql(QUERY);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(QUERY)) {
                if (!rs.next()) {
                    return Probe.failed("the source returned no row for " + QUERY);
                }
                return new Probe(rs.getString(1), rs.getString(2), null);
            }
        } catch (Exception e) {
            return Probe.failed(e.toString());
        }
    }

    private static void refuse(String message) {
        log.error("\n{}\n  !!  REFUSING TO START: {}\n{}", BANNER_RULE, message, BANNER_RULE);
        throw new IllegalStateException("Refusing to start: " + message);
    }
}
