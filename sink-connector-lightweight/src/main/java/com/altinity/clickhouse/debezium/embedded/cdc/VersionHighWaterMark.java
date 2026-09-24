package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ddl.DdlCaptureFilter;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.common.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Durable high-water mark of the {@code _version} values this connector has handed
 * to the writers, kept in {@value #TABLE_NAME} next to the offset table (spec 09.03
 * section 3.4), and the source of the version floor a new run is seeded with
 * (spec 02.02 section 3.5).
 *
 * <p><b>Why it exists.</b> The version sequence's floor ({@code sequenceMaxSourceTs})
 * is a process-local static. Without a seed a restart put it back at {@code 0}, so
 * the first rows of the new run were versioned in their own source second -- on a
 * lagging source, BELOW the second the previous run had been clamped to -- and rows
 * the previous run had written for the same keys kept winning under {@code FINAL}.
 * Seeding the floor from a value at or above every version the previous run
 * assigned closes that window regardless of lag, seeds or clock skew
 * ({@code Replication.VersionFloor.restart_boundary}).</p>
 *
 * <p><b>Write-ahead horizon.</b> {@link #cover(long)} is called on the dispatch
 * thread for every version assigned to a row, BEFORE the row is handed off. It
 * keeps a persisted horizon {@code H} with the invariant "every version ever handed
 * off is {@code <= H}": when a version exceeds the horizon, the horizon is moved
 * {@value #HORIZON_HEADROOM_MS} ms of source time ahead of it and written
 * synchronously; otherwise nothing is written. Under load that is one tiny insert
 * per ~5 s of source time; on an idle source, none. The mark is written ahead of
 * handoff rather than on acknowledgement because a unit a worker has already
 * written can still be parked behind an older unacknowledged sequence (spec 09.01):
 * its rows are in ClickHouse before any acknowledgement exists, so an
 * acknowledgement-time mark would not cover them. A horizon that cannot be made
 * durable fails the batch loudly rather than handing off rows the next start could
 * not order.</p>
 *
 * <p><b>Startup fallback.</b> On the first start after an upgrade (or on a
 * ClickHouse replica that never saw this connector) there is no mark row.
 * {@link #scanTargets} then reads {@code max(_version)} over every
 * {@code ReplacingMergeTree} table with a {@code _version} column in the databases
 * the connector writes to, and {@link #decodePlausibleFloor} interprets each value
 * in both version domains -- {@code v / 1_000_000} for the sequence domain,
 * {@code (v >>> 22) + snowflakeEpoch} for the GTID/snowflake domain -- accepting a
 * decoding only when it falls between 2015 and 24 h past the connector clock. That
 * excludes UInt64-max sentinel rows, raw-GTID and LSN versions (not
 * timestamp-anchored, so they need no floor) and a snowflake misread as a sequence
 * value (which would pin the floor a decade ahead).</p>
 */
public final class VersionHighWaterMark {

    private static final Logger log = LogManager.getLogger(VersionHighWaterMark.class);

    /** The table, created in the offset database. */
    static final String TABLE_NAME = "replica_version_high_water";

    /** Multiplier of the shipped version formula ({@code ts_ms * 1_000_000 + counter}). */
    static final long VERSION_MULTIPLIER = 1_000_000L;

    /** How far ahead (source-time ms) the persisted horizon is moved when exceeded. */
    static final long HORIZON_HEADROOM_MS = 5_000L;

    /** A decoded floor before this instant (2015-01-01T00:00:00Z) is not this connector's. */
    static final long PLAUSIBLE_FLOOR_MIN_MS = 1_420_070_400_000L;

    /** A decoded floor more than this far past the connector clock is not a timestamp. */
    static final long PLAUSIBLE_FUTURE_MS = 24L * 60 * 60 * 1000;

    /** Attempts and delay for persisting the horizon before failing loudly. */
    static final int DEFAULT_WRITE_ATTEMPTS = 30;
    static final long DEFAULT_WRITE_RETRY_MS = 2_000L;

    /** Bound on one {@code max(_version)} scan per table at startup. */
    static final int SCAN_MAX_EXECUTION_SECONDS = 60;

    private static final Pattern LITERAL_DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$]+");

    private final Supplier<Connection> connections;
    private final String offsetTable;
    private final String database;
    private final LongSupplier clock;
    private final int writeAttempts;
    private final long writeRetryMs;

    /** The horizon in force: every version handed off so far is {@code <= horizon}. */
    private long horizon = 0L;

    VersionHighWaterMark(Supplier<Connection> connections, String offsetTable, LongSupplier clock,
                         int writeAttempts, long writeRetryMs) {
        if (offsetTable == null || offsetTable.isBlank()) {
            throw new IllegalArgumentException("the offset table name is required to place the "
                    + TABLE_NAME + " table next to it");
        }
        this.connections = connections;
        this.offsetTable = offsetTable.replace("\"", "").replace("`", "");
        int dot = this.offsetTable.indexOf('.');
        this.database = dot > 0 ? this.offsetTable.substring(0, dot) : "system";
        this.clock = clock;
        this.writeAttempts = writeAttempts;
        this.writeRetryMs = writeRetryMs;
    }

    /**
     * The mark for the connector whose offset table is {@code offsetTable}
     * ({@code offset.storage.jdbc.table.name}, e.g. {@code db.replica_source_info}).
     *
     * @param connections supplies the ClickHouse connection to use for each statement.
     * @param offsetTable the fully qualified offset table name.
     * @return the mark, with production retry settings.
     */
    static VersionHighWaterMark forOffsetTable(Supplier<Connection> connections, String offsetTable) {
        return new VersionHighWaterMark(connections, offsetTable, System::currentTimeMillis,
                DEFAULT_WRITE_ATTEMPTS, DEFAULT_WRITE_RETRY_MS);
    }

    String database() {
        return database;
    }

    String qualifiedTableName() {
        return "`" + database + "`.`" + TABLE_NAME + "`";
    }

    /** The persisted horizon in force ({@code 0} before {@link #load} / the first write). */
    synchronized long horizon() {
        return horizon;
    }

    /**
     * Creates the table if it does not exist (spec 09.03 section 3.4).
     *
     * @throws SQLException if ClickHouse rejects the statement.
     */
    void ensureTable() throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS " + qualifiedTableName()
                + " (`offset_table` String, `high_water_version` UInt64,"
                + " `updated_at` DateTime64(3) DEFAULT now64(3))"
                + " ENGINE = ReplacingMergeTree(high_water_version) ORDER BY offset_table";
        try (PreparedStatement ps = connection().prepareStatement(ddl)) {
            ps.execute();
        }
        log.info("Version high-water table {} is in place for offset table {}", qualifiedTableName(),
                offsetTable);
    }

    /**
     * Reads the highest persisted mark for this connector and makes it the horizon in
     * force.
     *
     * @return the persisted high-water version, or {@code 0} when there is none.
     * @throws SQLException if the read fails.
     */
    synchronized long load() throws SQLException {
        String sql = "SELECT max(`high_water_version`) FROM " + qualifiedTableName()
                + " WHERE `offset_table` = ?";
        long persisted = 0L;
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, offsetTable);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    persisted = readUInt64(rs.getString(1));
                }
            }
        }
        if (persisted > horizon) {
            horizon = persisted;
        }
        return persisted;
    }

    /**
     * Makes sure a durable horizon at or above {@code version} exists BEFORE the
     * caller hands the row carrying it to the writers.
     *
     * @param version the sequence-domain version just assigned to a row.
     * @throws IllegalStateException if the horizon had to move and could not be
     *                               persisted after every attempt; the caller must
     *                               not hand the row off.
     */
    synchronized void cover(long version) {
        if (version <= horizon) {
            return;
        }
        long next = version + HORIZON_HEADROOM_MS * VERSION_MULTIPLIER;
        SQLException last = null;
        for (int attempt = 1; attempt <= writeAttempts; attempt++) {
            try {
                persist(next);
                horizon = next;
                log.info("Version high-water horizon moved to {} (floor slot {} ms) for offset table {}",
                        next, Math.floorDiv(next, VERSION_MULTIPLIER) + 1, offsetTable);
                return;
            } catch (SQLException e) {
                last = e;
                log.error("Could not persist the version high-water horizon {} to {} (attempt {}/{}); "
                        + "rows are held back until it is durable", next, qualifiedTableName(), attempt,
                        writeAttempts, e);
                if (attempt < writeAttempts && writeRetryMs > 0) {
                    try {
                        Thread.sleep(writeRetryMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new IllegalStateException("The version high-water horizon " + next + " could not be "
                + "persisted to " + qualifiedTableName() + " after " + writeAttempts + " attempts; "
                + "refusing to hand off rows whose versions the next start could not order above. "
                + "Restore ClickHouse write access to the offset database and restart.", last);
    }

    private void persist(long value) throws SQLException {
        String sql = "INSERT INTO " + qualifiedTableName()
                + " (`offset_table`, `high_water_version`) VALUES (?, ?)";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, offsetTable);
            ps.setLong(2, value);
            ps.execute();
        }
    }

    /**
     * Startup fallback: the highest plausible floor decoded from {@code max(<versionColumn>)}
     * over every {@code ReplacingMergeTree} table carrying that column in the given
     * databases (spec 02.02 section 3.5 (2)).
     *
     * @param databases     ClickHouse databases the connector writes to.
     * @param versionColumn the version column name ({@code _version}).
     * @return the floor in ms, or {@code 0} when nothing usable was found.
     * @throws SQLException if table discovery fails.
     */
    long scanTargets(Collection<String> databases, String versionColumn) throws SQLException {
        if (databases == null || databases.isEmpty()) {
            return 0L;
        }
        List<String> targets = new ArrayList<>();
        for (String db : databases) {
            if (db != null && LITERAL_DATABASE_NAME.matcher(db).matches()) {
                targets.add(db);
            }
        }
        if (targets.isEmpty()) {
            return 0L;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
        }
        String discovery = "SELECT c.database, c.table FROM system.columns AS c"
                + " INNER JOIN system.tables AS t ON t.database = c.database AND t.name = c.table"
                + " WHERE c.name = ? AND t.engine LIKE '%ReplacingMergeTree%'"
                + " AND c.database IN (" + in + ") ORDER BY c.database, c.table";
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(discovery)) {
            ps.setString(1, versionColumn);
            for (int i = 0; i < targets.size(); i++) {
                ps.setString(i + 2, targets.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        long now = clock.getAsLong();
        long best = 0L;
        for (String[] table : tables) {
            String qualified = "`" + table[0] + "`.`" + table[1] + "`";
            String sql = "SELECT max(`" + versionColumn + "`) FROM " + qualified
                    + " SETTINGS max_execution_time = " + SCAN_MAX_EXECUTION_SECONDS;
            try (PreparedStatement ps = connection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    continue;
                }
                String raw = rs.getString(1);
                long version = readUInt64(raw);
                long floor = decodePlausibleFloor(version, now);
                if (floor == 0L) {
                    log.warn("max({}) of {} is {}, which decodes to no plausible timestamp in either "
                            + "version domain; not used to seed the version floor", versionColumn,
                            qualified, raw);
                    continue;
                }
                log.info("max({}) of {} is {} -> candidate version floor {} ms", versionColumn, qualified,
                        raw, floor);
                if (floor > best) {
                    best = floor;
                }
            } catch (SQLException e) {
                log.warn("Could not read max({}) of {} while seeding the version floor; skipping it",
                        versionColumn, qualified, e);
            }
        }
        return best;
    }

    /**
     * The ClickHouse databases the connector writes to, for the startup scan: the
     * literal entries of {@code database.include.list}, mapped through
     * {@code clickhouse.database.override.map} and the common database prefix. A
     * pattern entry cannot be resolved without guessing and yields an empty list.
     *
     * @param props  the Debezium properties.
     * @param config the connector configuration.
     * @return the target database names, possibly empty.
     */
    static List<String> targetDatabases(Properties props, ClickHouseSinkConnectorConfig config) {
        String include = props == null ? null : props.getProperty(DdlCaptureFilter.DATABASE_INCLUDE_LIST);
        if (include == null || include.trim().isEmpty()) {
            log.warn("database.include.list is not set; the startup version-floor scan has no target "
                    + "databases");
            return Collections.emptyList();
        }
        Map<String, String> overrides = null;
        String overrideConfig = config == null ? null : config.getString(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString());
        if (overrideConfig != null && !overrideConfig.trim().isEmpty()) {
            try {
                overrides = Utils.parseSourceToDestinationDatabaseMap(overrideConfig);
            } catch (Exception e) {
                log.error("Invalid {} while resolving the startup version-floor scan targets",
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP, e);
            }
        }
        String prefix = config == null ? null : config.getString(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_COMMON_DATABASE_PREFIX.toString());
        List<String> out = new ArrayList<>();
        for (String entry : include.split(",")) {
            String name = entry.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!LITERAL_DATABASE_NAME.matcher(name).matches()) {
                log.warn("database.include.list entry '{}' is a pattern, not a literal database name; "
                        + "the startup version-floor scan cannot resolve its ClickHouse targets and is "
                        + "skipped", name);
                return Collections.emptyList();
            }
            String target = name;
            if (overrides != null && overrides.containsKey(name)) {
                target = overrides.get(name);
            }
            target = Utils.applyDatabasePrefix(target, prefix);
            if (!out.contains(target)) {
                out.add(target);
            }
        }
        return out;
    }

    /**
     * The floor slot strictly above a sequence-domain version: {@code floorDiv(v, 1e6) + 1}.
     *
     * @param version a version in the {@code ts_ms * 1_000_000 + counter} domain.
     * @return the first whole millisecond whose versions all exceed {@code version}.
     */
    static long sequenceFloor(long version) {
        return Math.floorDiv(version, VERSION_MULTIPLIER) + 1;
    }

    /**
     * Whether a floor (ms) is a credible timestamp for this connector: not before
     * {@link #PLAUSIBLE_FLOOR_MIN_MS} and not more than {@link #PLAUSIBLE_FUTURE_MS}
     * past the connector clock.
     */
    static boolean isPlausibleFloor(long floorMs, long nowMs) {
        return floorMs >= PLAUSIBLE_FLOOR_MIN_MS && floorMs <= nowMs + PLAUSIBLE_FUTURE_MS;
    }

    /**
     * Decodes a stored {@code _version} into the floor (ms) that places every future
     * version above it, trying the sequence domain ({@code v / 1e6 + 1}) and the
     * snowflake domain ({@code (v >>> 22) + epoch + 1}) and keeping the highest
     * plausible result. A too-high floor only delays the source clock catching up;
     * a too-low one is the defect, so the higher decoding is the safe choice.
     *
     * @param version the stored version (non-positive values decode to nothing).
     * @param nowMs   the connector clock.
     * @return the floor in ms, or {@code 0} when no decoding is plausible.
     */
    static long decodePlausibleFloor(long version, long nowMs) {
        if (version <= 0) {
            return 0L;
        }
        long best = 0L;
        long sequence = sequenceFloor(version);
        if (isPlausibleFloor(sequence, nowMs)) {
            best = sequence;
        }
        long snowflake = (version >>> SnowFlakeId.GTID_FIELD_BITS) + SnowFlakeId.SNOWFLAKE_EPOCH + 1;
        if (isPlausibleFloor(snowflake, nowMs) && snowflake > best) {
            best = snowflake;
        }
        return best;
    }

    /** A UInt64 read as text; values above {@code Long.MAX_VALUE} (sentinels) map to it. */
    private static long readUInt64(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        BigInteger value = new BigInteger(raw.trim());
        if (value.signum() <= 0) {
            return 0L;
        }
        return value.bitLength() > 63 ? Long.MAX_VALUE : value.longValue();
    }

    private Connection connection() throws SQLException {
        Connection conn = connections.get();
        if (conn == null) {
            throw new SQLException("no ClickHouse connection is available for " + qualifiedTableName());
        }
        return conn;
    }
}
