package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Turns the binlog client's keep-alive auto-reconnect OFF unless the operator
 * explicitly asked for it (spec 01.07).
 *
 * <p><b>The loss this prevents.</b> Debezium's binlog client runs a keep-alive
 * thread ({@code connect.keep.alive}, Debezium default {@code true}) that
 * reconnects a dropped binlog connection on its own, from the client's own
 * last-read position. That position is a raw byte offset, not a transaction
 * boundary: while the sink is blocked -- a pre-DDL drain, a full handoff
 * queue, a slow ClickHouse -- the reader stops draining the socket, the source
 * aborts the dump connection after {@code net_write_timeout} (60 s by
 * default), and when the sink unblocks the keep-alive thread resumes
 * streaming from the middle of whatever transaction was being read. The
 * resumed stream opens with a fresh ROTATE, which clears the table-number map
 * Debezium keeps from TABLE_MAP events; the ROWS events that follow, still
 * inside the interrupted statement, carry a table number that no longer maps
 * to a table, so Debezium skips them as events for a table it does not
 * capture -- at DEBUG, without an error, with the offset advancing past them.
 * Every other table in the same transaction is fine (each statement opens with
 * its own TABLE_MAP); the rest of the interrupted statement is gone. In one
 * production run a 729-row statement split at the resume point lost the 527
 * rows after it, and the only signal was a checksum job hours later.
 * Reported upstream as debezium/dbz#2359 and fixed in the client library
 * (debezium/mysql-binlog-connector-java#28, September 2026); the Debezium
 * release this connector builds against still carries the old client.</p>
 *
 * <p><b>Why off is the safe default.</b> Every restart path the connector owns
 * resumes from the DURABLE offset, which Debezium records as the position of
 * the transaction's BEGIN plus the number of events and rows already
 * delivered, and the durable offset only advances once rows are in ClickHouse
 * (Invariant I8, spec 09.01). With the keep-alive thread off, a lost
 * connection surfaces as a communication failure, the engine stops, and the
 * completion callback restarts it (spec 10.04) from that boundary: the
 * TABLE_MAP is replayed, the already-written rows are skipped by count, and
 * the rest of the statement is delivered. The cost is a restart and a bounded
 * redelivery; the alternative is silent loss.</p>
 *
 * <p>An operator who sets {@code connect.keep.alive=true} keeps it -- a
 * GTID-positioned source, or a client library carrying the upstream fix,
 * reconnects at a transaction boundary -- but is told at WARN, on every start,
 * what the setting risks on a file/position source.</p>
 */
public final class BinlogKeepAlivePreflight {

    private static final Logger log = LogManager.getLogger(BinlogKeepAlivePreflight.class);

    /** Debezium's own key for the binlog client's keep-alive thread. */
    static final String PROPERTY = "connect.keep.alive";

    /** The value applied when the operator has not chosen one. */
    static final String SAFE_VALUE = "false";

    private static final String BANNER_RULE =
            "========================================================================";

    private BinlogKeepAlivePreflight() {
    }

    /**
     * Applies the default to the properties the engine will be built from.
     *
     * <p>Only binlog-based connectors (MySQL, MariaDB) are touched; other
     * connectors have no binlog client. The properties object is the one the
     * engine and its restarts are built from, so one call at setup covers every
     * engine created in the process.</p>
     *
     * @param props the connector properties, mutated in place.
     * @return {@code true} when this call set the property, {@code false} when
     *         the operator's value (or a non-binlog connector) left it alone.
     */
    public static boolean apply(Properties props) {
        if (!isBinlogConnector(props)) {
            return false;
        }
        String configured = props.getProperty(PROPERTY);
        if (configured == null) {
            props.setProperty(PROPERTY, SAFE_VALUE);
            log.info("{}={} (connector default): a lost binlog connection stops the engine, which "
                    + "restarts from the last committed transaction boundary. The client's own "
                    + "keep-alive reconnect resumes from its last-read byte offset instead, which "
                    + "on a file/position source can be inside a transaction and drops the rest "
                    + "of the interrupted statement without an error (spec 01.07).",
                    PROPERTY, SAFE_VALUE);
            return true;
        }
        if (Boolean.parseBoolean(configured.trim())) {
            // Loud on purpose: the operator has re-enabled a reconnect path
            // that silently drops rows on a file/position-positioned source.
            log.warn("\n{}\n  !!  {}=true -- BINLOG KEEP-ALIVE AUTO-RECONNECT ENABLED  !!\n{}\n"
                    + "  On a source positioned by binlog file and offset (no GTID auto-positioning), "
                    + "the keep-alive thread reconnects from the client's last-read byte offset. When "
                    + "the connection was lost while the sink was blocked, that offset is inside a "
                    + "transaction: the resumed stream's ROTATE clears the TABLE_MAP cache and the "
                    + "remaining ROWS events of the interrupted statement are skipped without an "
                    + "error (debezium/dbz#2359). Remove {} or set it to false to let a lost "
                    + "connection restart the engine from the last committed transaction boundary "
                    + "instead. Safe only with GTID auto-positioning, or with a binlog client that "
                    + "carries debezium/mysql-binlog-connector-java#28.\n{}",
                    BANNER_RULE, PROPERTY, BANNER_RULE, PROPERTY, BANNER_RULE);
            return false;
        }
        log.info("{}={}: a lost binlog connection restarts the engine from the last committed "
                + "transaction boundary (spec 01.07).", PROPERTY, configured.trim());
        return false;
    }

    /** MySQL and MariaDB are the connectors with a binlog client. */
    static boolean isBinlogConnector(Properties props) {
        String connector = props.getProperty("connector.class", "").toLowerCase();
        return connector.contains("mysql") || connector.contains("mariadb");
    }
}
