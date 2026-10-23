package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup cross-check of the configured ClickHouse zone (spec 07.03 section 3.1.6, B5) against
 * the server's {@code timezone()} and the zones declared by existing DateTime/DateTime64 columns.
 * <p>
 * A mismatch is one WARN per column and never refuses startup: an existing column declared in another zone keeps
 * the values it already holds. The reference zone is the configured {@code clickhouse.datetime.timezone}, which is
 * mandatory; an unset value is refused, never replaced by the server zone. Views are not reported (a view's
 * declared zone is its output type), and neither is the sink's own metadata database (its
 * {@code show_replica_status} view is UTC by design). A column with no explicit zone follows the server zone and is
 * not reported.
 */
public final class ClickHouseZoneCrossCheck {

    private static final Logger log = LogManager.getLogger(ClickHouseZoneCrossCheck.class);

    /** The zone a DateTime or DateTime64 column declares in its type, for example {@code DateTime64(3, 'UTC')}. */
    private static final Pattern DATETIME_ZONE =
            Pattern.compile("DateTime(?:64)?\\((?:\\d+\\s*,\\s*)?'([^']*)'\\)");

    /** The sink's own metadata database; its tables are not captured source tables. */
    static final String METADATA_DATABASE = "altinity_sink_connector";

    /** One existing column that declares a zone. */
    public record ColumnZone(String database, String table, String column, String zone, String engine) {
    }

    /** Where the zones come from. Production: {@link JdbcZoneMetadataSource}. Tests: a fake. */
    public interface ZoneMetadataSource {
        /** The result of {@code SELECT timezone()} on the server. */
        String serverTimezone() throws SQLException;

        /** Every existing DateTime or DateTime64 column whose type declares an explicit zone. */
        List<ColumnZone> dateTimeColumnZones() throws SQLException;
    }

    private ClickHouseZoneCrossCheck() {
    }

    /** The zone named inside a ClickHouse type string, or null when the type declares none. */
    public static String zoneOfType(String type) {
        if (type == null) {
            return null;
        }
        Matcher matcher = DATETIME_ZONE.matcher(type);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Returns one human-readable finding per mismatch, without logging. Throws {@link SQLException}
     * when the server zone cannot be read; the caller decides what that means (startup: WARN).
     */
    public static List<String> findMismatches(String configuredZone, ZoneMetadataSource source) throws SQLException {
        // The configured zone is mandatory and is the only reference. An unset value is refused, never
        // replaced by the server zone.
        TimeZoneResolver.clickHouseZone(configuredZone);
        String serverZone = source.serverTimezone();
        if (TimeZoneResolver.isUnset(serverZone)) {
            throw new SQLException("SELECT timezone() returned no zone");
        }

        List<String> findings = new ArrayList<>();
        if (!configuredZone.equals(serverZone)) {
            findings.add("clickhouse.datetime.timezone is '" + configuredZone
                    + "' but the ClickHouse server reports timezone() '" + serverZone
                    + "'. Align the server timezone or the configured zone.");
        }
        for (ColumnZone column : source.dateTimeColumnZones()) {
            // A view declares its own zone (for example system.replica_status.utc_time); the sink never writes
            // to a view, so its declared zone is not a mismatch of the configured zone. The sink's metadata
            // database is exempt by schema name for the same reason (show_replica_status is UTC by design).
            if (isView(column.engine()) || METADATA_DATABASE.equals(column.database())) {
                continue;
            }
            if (!sameZoneName(column.zone(), configuredZone)) {
                findings.add("column " + column.database() + "." + column.table() + "." + column.column()
                        + " declares zone '" + column.zone() + "' but clickhouse.datetime.timezone is '"
                        + configuredZone + "'. The sink does not change this existing column: it keeps the values it"
                        + " already holds, which are read in the declared zone '" + column.zone() + "'.");
            }
        }
        return findings;
    }

    /** True for View, MaterializedView, LiveView and WindowView: engines that store no rows of their own. */
    static boolean isView(String engine) {
        return engine != null && engine.endsWith("View");
    }

    /**
     * True when two zone names denote the same zone rules, so {@code US/Central} and {@code America/Chicago} agree.
     * A name that is not a Java zone id is compared as text only.
     */
    static boolean sameZoneName(String declared, String configured) {
        if (declared.equals(configured)) {
            return true;
        }
        try {
            return ZoneId.of(declared).getRules().equals(ZoneId.of(configured).getRules());
        } catch (DateTimeException e) {
            return false;
        }
    }

    /** Runs {@link #findMismatches} and logs each finding at WARN, one line per column. Returns the findings. */
    public static List<String> report(String configuredZone, ZoneMetadataSource source) throws SQLException {
        List<String> findings = findMismatches(configuredZone, source);
        for (String finding : findings) {
            log.warn("ClickHouse timezone cross-check: " + finding);
        }
        if (findings.isEmpty()) {
            log.info("ClickHouse timezone cross-check: configured zone, server timezone() and DateTime column zones agree");
        }
        return findings;
    }

    /**
     * Startup entry point. Opens a connection from {@code connections}, runs {@link #report}, and closes
     * the connection. Never throws: a missing connection or any failure to run the check is a WARN, so
     * startup continues.
     */
    public static void checkAtStartup(String configuredZone, Supplier<Connection> connections) {
        try {
            Connection conn = connections.get();
            if (conn == null) {
                log.warn("ClickHouse timezone cross-check skipped: no connection to ClickHouse");
                return;
            }
            try (conn) {
                report(configuredZone, new JdbcZoneMetadataSource(conn));
            }
        } catch (Exception e) {
            log.warn("ClickHouse timezone cross-check could not run; startup continues", e);
        }
    }

    /** Production source over a ClickHouse JDBC connection. */
    public static final class JdbcZoneMetadataSource implements ZoneMetadataSource {

        static final String SERVER_TIMEZONE_SQL = "SELECT timezone()";
        static final String DATETIME_COLUMNS_SQL =
                "SELECT c.database, c.table, c.name, c.type, t.engine FROM system.columns AS c"
                        + " INNER JOIN system.tables AS t ON c.database = t.database AND c.table = t.name"
                        + " WHERE c.type LIKE '%DateTime%'"
                        + " AND c.database NOT IN ('system', 'INFORMATION_SCHEMA', 'information_schema')";

        private final Connection connection;

        public JdbcZoneMetadataSource(Connection connection) {
            this.connection = connection;
        }

        @Override
        public String serverTimezone() throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(SERVER_TIMEZONE_SQL)) {
                return rs.next() ? rs.getString(1) : null;
            }
        }

        @Override
        public List<ColumnZone> dateTimeColumnZones() throws SQLException {
            List<ColumnZone> zones = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(DATETIME_COLUMNS_SQL)) {
                while (rs.next()) {
                    String zone = zoneOfType(rs.getString("type"));
                    if (zone != null) {
                        zones.add(new ColumnZone(rs.getString("database"), rs.getString("table"),
                                rs.getString("name"), zone, rs.getString("engine")));
                    }
                }
            }
            return zones;
        }
    }
}
