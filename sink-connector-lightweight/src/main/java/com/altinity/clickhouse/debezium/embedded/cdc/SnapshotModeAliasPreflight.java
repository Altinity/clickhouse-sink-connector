package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Keeps the {@code snapshot.mode} values Debezium 3.3 removed working, with the
 * behaviour they had under 3.1.3 (spec 02.06 section 3.4).
 *
 * <p>Debezium 3.3 removed {@code schema_only} and {@code schema_only_recovery}
 * from the binlog connectors' {@code SnapshotMode} enum (DBZ-8171), and
 * {@code never} from PostgreSQL's (see {@link #REMOVED_POSTGRES_ALIASES}); a
 * configuration that still names one fails validation and the engine never
 * starts. Under 3.1.3 both were implemented by snapshotters that EXTEND
 * {@code NoDataSnapshotter} and override nothing but their name
 * ({@code SchemaOnlySnapshotter}, {@code SchemaOnlyRecoverySnapshotter},
 * debezium-core 3.1.3 {@code io.debezium.snapshot.mode}); 3.3's
 * {@code NoDataSnapshotter} is byte-identical to 3.1.3's. Both values are
 * therefore rewritten to {@code no_data}, which is exactly what they did. The
 * shipped ansible-systemd and helm templates default {@code snapshot.mode} to {@code schema_only}, so
 * without this every deployment that does not set it would stop starting after
 * the upgrade (Invariant I11: no config value changes meaning).</p>
 */
public final class SnapshotModeAliasPreflight {

    private static final Logger log = LogManager.getLogger(SnapshotModeAliasPreflight.class);

    static final String PROPERTY = "snapshot.mode";

    /**
     * Binlog connectors: removed value -> the 3.7 value with the 3.1.3 behaviour.
     * {@code schema_only} / {@code schema_only_recovery}: exact (their 3.1.3
     * snapshotters extended {@code NoDataSnapshotter} unchanged). {@code never}:
     * removed from the binlog enum and its {@code NeverSnapshotter} deleted by
     * 3.7; with an existing offset it and {@code no_data} both read no data and no
     * schema; on a first start without offsets {@code never} streamed from the
     * oldest binlog with no schema snapshot, which 3.7 no longer offers --
     * {@code no_data} snapshots the schema and streams from the current position.
     */
    static final Map<String, String> REMOVED_BINLOG_ALIASES = Map.of(
            "schema_only", "no_data",
            "schema_only_recovery", "no_data",
            "never", "no_data");

    /**
     * PostgreSQL: 3.3 removed {@code never} from {@code PostgresConnectorConfig.SnapshotMode};
     * 3.1.3 marked it "@deprecated ... replaced by NO_DATA". With an existing
     * offset both read no data and no schema (NeverSnapshotter /
     * NoDataSnapshotter.shouldSnapshotSchema = !offsetExists || inProgress); on a
     * first start without offsets no_data additionally reads the table structures
     * (PostgreSQL emits no schema events, so nothing reaches ClickHouse).
     */
    static final Map<String, String> REMOVED_POSTGRES_ALIASES = Map.of("never", "no_data");

    private SnapshotModeAliasPreflight() {
    }

    /**
     * Rewrites a {@code snapshot.mode} value Debezium 3.3 removed.
     *
     * @param props connector properties (mutated).
     * @return the value written, or {@code null} when nothing was changed.
     */
    public static String apply(Properties props) {
        Map<String, String> aliases;
        if (BinlogKeepAlivePreflight.isBinlogConnector(props)) {
            aliases = REMOVED_BINLOG_ALIASES;
        } else if (props.getProperty("connector.class", "").toLowerCase(Locale.ROOT).contains("postgres")) {
            aliases = REMOVED_POSTGRES_ALIASES;
        } else {
            return null;
        }
        String configured = props.getProperty(PROPERTY);
        if (configured == null) {
            return null;
        }
        String replacement = aliases.get(configured.trim().toLowerCase(Locale.ROOT));
        if (replacement == null) {
            return null;
        }
        props.setProperty(PROPERTY, replacement);
        log.warn("snapshot.mode={} was removed in Debezium 3.3; using snapshot.mode={}, its documented "
                + "replacement and what '{}' did under Debezium 3.1.3 once offsets exist. Set "
                + "snapshot.mode={} in the configuration to silence this warning (spec 02.06 "
                + "section 3.4).", configured, replacement, configured, replacement);
        return replacement;
    }
}
