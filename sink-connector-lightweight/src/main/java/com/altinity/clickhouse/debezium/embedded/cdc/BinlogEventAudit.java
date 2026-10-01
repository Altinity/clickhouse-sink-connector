package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeader;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.XAPrepareEventData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reports an XA transaction that the source rolled back AFTER its {@code XA PREPARE}, whose rows Debezium
 * 3.1.3 has already dispatched (spec 01.10).
 *
 * <p>MySQL writes an XA transaction's rows at {@code XA PREPARE} and its outcome later, as a separate
 * {@code XA COMMIT} / {@code XA ROLLBACK} statement. Debezium dispatches the rows as they arrive and ignores
 * every {@code XA} statement ({@code BinlogStreamingChangeEventSource.handleQueryEvent}: "This is an XA
 * transaction, and we currently ignore these"; {@code prepareTransaction}: "do nothing"), so the rows of an
 * XA transaction rolled back after PREPARE stay in ClickHouse. Measured (spec 01.08 section 5, edge case
 * E3): the rolled-back row replicated, the committed ones exact, no log line. The rows are already written
 * when the ROLLBACK arrives, so this is reported, not refused: an ERROR naming the XID and the tables its
 * PREPARE wrote, and the {@code clickhouse_sink_binlog_xa_rollback_after_prepare} metric; replication of
 * everything else continues. Recovery: re-synchronise the named tables (spec 11.04).</p>
 *
 * <p>Two feeds, one state. The listener registered on the task's binlog client (by the connector's copy of
 * Debezium's MySQL streaming metrics class, spec 01.09) sees the outer events -- {@code XA_PREPARE} and the
 * later {@code XA COMMIT} / {@code XA ROLLBACK}. With {@code binlog_transaction_compression=ON} the XA
 * transaction's body ({@code XA START}, its TABLE_MAPs and rows, {@code XA END}) is INSIDE a
 * {@code Transaction_payload}, which the client listener sees as one event; the payload decoder therefore
 * hands the inner {@code QUERY} and {@code TABLE_MAP} events of its first pass to {@link #observeInner}. Both
 * run on the binlog reader thread, in stream order (the decoder runs while the payload event is read, before
 * the client notifies its listeners of it); one connector runs one reader, so the state is static and
 * confined to that thread.</p>
 */
public final class BinlogEventAudit implements BinaryLogClient.EventListener {

    private static final Logger log = LogManager.getLogger(BinlogEventAudit.class);

    /** Bound on remembered prepared XA transactions; the oldest is forgotten beyond it. */
    static final int MAX_PREPARED_XA = 10_000;

    private static final Map<String, TreeSet<String>> PREPARED = new LinkedHashMap<String, TreeSet<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TreeSet<String>> eldest) {
            return size() > MAX_PREPARED_XA;
        }
    };
    private static String currentXid = null;
    private static TreeSet<String> currentTables = null;
    private static volatile BinaryLogClient client = null;

    BinlogEventAudit(BinaryLogClient client) {
        BinlogEventAudit.client = client;
    }

    /**
     * Registers the audit on the task's binlog client. Called by the connector's copy of Debezium's MySQL
     * streaming metrics class, before Debezium registers its own listener.
     *
     * @param client the task's binlog client.
     */
    public static void install(BinaryLogClient client) {
        if (client == null) {
            log.warn("XA audit NOT installed: the task context has no binlog client yet; an XA ROLLBACK after "
                    + "XA PREPARE would go unreported (spec 01.10).");
            return;
        }
        reset();
        client.registerEventListener(new BinlogEventAudit(client));
    }

    /** Forgets the XA state (a new task re-reads the stream from its offset). */
    static synchronized void reset() {
        PREPARED.clear();
        currentXid = null;
        currentTables = null;
    }

    @Override
    public void onEvent(Event event) {
        observe(event, false);
    }

    /**
     * An inner event of a compressed transaction, from the payload decoder's first pass.
     *
     * @param event the inner event, with its data parsed (TABLE_MAP, QUERY, XA_PREPARE).
     */
    public static void observeInner(Event event) {
        observe(event, true);
    }

    private static void observe(Event event, boolean inner) {
        try {
            audit(event);
        } catch (RuntimeException e) {
            // Never let the audit itself stop the reader; say so loudly instead.
            log.error("binlog event audit failed on {}{}: {} (spec 01.10)", inner ? "an inner payload event " : "",
                    event == null ? null : event.getHeader(), e.toString(), e);
        }
    }

    static synchronized void audit(Event event) {
        if (event == null || event.getHeader() == null) {
            return;
        }
        EventHeader header = event.getHeader();
        EventType type = header.getEventType();
        if (type == EventType.TABLE_MAP && event.getData() instanceof TableMapEventData) {
            if (currentTables != null) {
                TableMapEventData tm = (TableMapEventData) event.getData();
                currentTables.add(tm.getDatabase() + "." + tm.getTable());
            }
        } else if (type == EventType.QUERY && event.getData() instanceof QueryEventData) {
            onQuery(((QueryEventData) event.getData()).getSql(), header);
        } else if (type == EventType.XA_PREPARE) {
            boolean onePhase = event.getData() instanceof XAPrepareEventData
                    && ((XAPrepareEventData) event.getData()).isOnePhase();
            if (currentXid != null && !onePhase) {
                PREPARED.put(currentXid, currentTables == null ? new TreeSet<>() : currentTables);
            }
            currentXid = null;
            currentTables = null;
        }
    }

    private static void onQuery(String sql, EventHeader header) {
        if (sql == null) {
            return;
        }
        String s = sql.trim();
        String upper = s.length() > 12 ? s.substring(0, 12).toUpperCase() : s.toUpperCase();
        if (!upper.startsWith("XA ")) {
            return;
        }
        if (upper.startsWith("XA START")) {
            currentXid = xid(s, "XA START");
            currentTables = new TreeSet<>();
        } else if (upper.startsWith("XA BEGIN")) {
            currentXid = xid(s, "XA BEGIN");
            currentTables = new TreeSet<>();
        } else if (upper.startsWith("XA COMMIT")) {
            PREPARED.remove(xid(s, "XA COMMIT"));
        } else if (upper.startsWith("XA ROLLBACK")) {
            String xid = xid(s, "XA ROLLBACK");
            TreeSet<String> tables = PREPARED.remove(xid);
            Metrics.incrementBinlogXaRollbackAfterPrepare();
            if (tables != null) {
                log.error("XA ROLLBACK {} at {}: the source rolled back an XA transaction AFTER its XA PREPARE, "
                                + "whose rows were already replicated (Debezium dispatches an XA transaction's rows at "
                                + "PREPARE and ignores its outcome). ClickHouse now holds rows the source does not. "
                                + "Tables written by its PREPARE: {}. Recovery: re-synchronise those tables from MySQL "
                                + "(ch-mysql-resync, spec 11.04). Replication of everything else continues (spec 01.10).",
                        xid, where(header), tables.isEmpty() ? "none (no row events)" : tables);
            } else {
                // MySQL binlogs an XA ROLLBACK only for a prepared XA transaction, so its PREPARE (and rows) are
                // earlier in the binlog -- before this process's start position.
                log.error("XA ROLLBACK {} at {}: the source rolled back an XA transaction whose XA PREPARE was written "
                                + "before this process's start position, so its tables are UNKNOWN here. If its rows "
                                + "were in captured tables they were replicated and ClickHouse holds rows the source does "
                                + "not: find the PREPARE with mysqlbinlog before this position and re-synchronise the "
                                + "tables it wrote (ch-mysql-resync, spec 11.04) (spec 01.10).", xid, where(header));
            }
        }
    }

    /** The XID text after the verb, without a trailing ONE PHASE; identical for START / PREPARE / COMMIT / ROLLBACK. */
    static String xid(String sql, String verb) {
        String rest = sql.trim().substring(verb.length()).trim();
        if (rest.toUpperCase().endsWith(" ONE PHASE")) {
            rest = rest.substring(0, rest.length() - " ONE PHASE".length()).trim();
        }
        return rest;
    }

    private static String where(EventHeader header) {
        BinaryLogClient c = client;
        String file = c == null ? null : c.getBinlogFilename();
        long pos = header instanceof EventHeaderV4 ? ((EventHeaderV4) header).getPosition() : -1;
        return (file == null ? "?" : file) + ":" + pos;
    }
}
