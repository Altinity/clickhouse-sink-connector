package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ddl.DdlCaptureFilter;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
 * <p><b>Startup without a mark.</b> On the first start after an upgrade (or on a
 * ClickHouse replica that never saw this connector) there is no mark row.
 * {@link #seedFloor()} then seeds the floor from the <b>connector clock</b> plus
 * {@value #CLOCK_SEED_HEADROOM_MS} ms (spec 02.02 section 3.5 (2)). Every version
 * a previous run assigned decodes to an instant of the past -- a source statement
 * time, an envelope time or a heartbeat-pinned connector clock, each at most one
 * second of counter carry above its wall-clock instant -- so a floor a few seconds
 * past the present is at or above all of them; a too-high floor only delays the
 * source clock catching up, a too-low one is the defect, so the higher choice is
 * the safe one. The seed is a function of the mark table and the clock ONLY: it
 * never reads a target table. The previous design read {@code max(_version)} over
 * every replicated table instead; on a production replica that was thousands of
 * full-column scans of 30-billion-row tables per hour, re-run by every engine
 * retry on the event thread, and it stalled replication (Invariant I14, spec
 * 10.06).</p>
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

    /**
     * Head-room (ms) added to the connector clock when a start has no mark row to
     * seed from: covers the up-to-one-second counter carry of the shipped formula
     * (spec 02.01 section 3.3) and ordinary clock skew between the source host and
     * the connector host. Same magnitude as {@link #HORIZON_HEADROOM_MS}.
     */
    static final long CLOCK_SEED_HEADROOM_MS = HORIZON_HEADROOM_MS;

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
        // I14-scan-allowed: the connector-owned mark table (spec 09.03 section 3.4),
        // one row per ~5 s of source time, filtered on its ORDER BY key.
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
     * The floor a start is seeded with, and where it came from (spec 02.02 section 3.5 (2)).
     */
    static final class Seed {
        /** The floor in ms: every first delivery of the run is versioned at least {@code floorMs * 1e6 + 1}. */
        final long floorMs;
        /** {@code true} when the floor came from a persisted mark, {@code false} when from the clock. */
        final boolean fromMark;
        /** Human-readable origin for the startup log line. */
        final String source;

        Seed(long floorMs, boolean fromMark, String source) {
            this.floorMs = floorMs;
            this.fromMark = fromMark;
            this.source = source;
        }
    }

    /**
     * The seed for this start: {@code floorDiv(V_max, 1e6) + 1} when a plausible mark
     * row exists, otherwise the connector clock plus {@value #CLOCK_SEED_HEADROOM_MS} ms.
     *
     * <p>Exactly two statements are issued, both against {@value #TABLE_NAME}:
     * {@link #ensureTable()} and {@link #load()}. No target table is read -- a
     * connector's bookkeeping must never depend on a scan of the data it replicates
     * (Invariant I14, spec 10.06): such a scan is unbounded in the size of the
     * targets, it ran on the event thread ahead of the first delivery, and an engine
     * retry re-ran it, so one poison event turned it into a permanent load on the
     * ClickHouse side while replication stood still.</p>
     *
     * @return the seed; never {@code null}.
     * @throws SQLException if the mark table cannot be created or read.
     */
    synchronized Seed seedFloor() throws SQLException {
        ensureTable();
        long persisted = load();
        long now = clock.getAsLong();
        if (persisted > 0) {
            long candidate = sequenceFloor(persisted);
            if (isPlausibleFloor(candidate, now)) {
                return new Seed(candidate, true, "the persisted high-water version " + persisted);
            }
            log.error("The persisted high-water version {} in {} decodes to floor {} ms, which is "
                    + "not a plausible timestamp; ignoring it and seeding from the connector clock",
                    persisted, qualifiedTableName(), candidate);
        }
        return new Seed(now + CLOCK_SEED_HEADROOM_MS, false, "the connector clock + "
                + CLOCK_SEED_HEADROOM_MS + " ms (no usable row in " + qualifiedTableName() + ")");
    }

    /**
     * The ClickHouse databases the connector writes to, as the primary-key backfill
     * resume needs them (spec 06.09 section 3.3.2): the literal entries of
     * {@code database.include.list}, mapped through
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
            log.warn("database.include.list is not set; the ClickHouse target databases cannot be "
                    + "resolved from it");
            return Collections.emptyList();
        }
        Map<String, String> overrides = null;
        String overrideConfig = config == null ? null : config.getString(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString());
        if (overrideConfig != null && !overrideConfig.trim().isEmpty()) {
            try {
                overrides = Utils.parseSourceToDestinationDatabaseMap(overrideConfig);
            } catch (Exception e) {
                log.error("Invalid {} while resolving the ClickHouse target databases",
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
                        + "its ClickHouse target databases cannot be resolved without guessing", name);
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
