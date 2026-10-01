package com.altinity.clickhouse.debezium.embedded.cdc;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XAPrepareEventData;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XA audit (spec 01.10): an XA transaction rolled back after XA PREPARE is reported.
 *
 * <p><b>The gap.</b> Debezium 3.1.3 dispatches an XA transaction's rows at PREPARE and ignores every
 * {@code XA} statement, so the rows of an XA transaction rolled back after PREPARE stay in ClickHouse.
 * Measured end to end (spec 01.08 section 5, edge case E3): the rolled-back row replicated, no log line.
 * With compression on, the XA body is inside a Transaction_payload; the payload-decoder feed is pinned by
 * {@code StreamingTransactionPayloadTest.compressedXaBodyReachesTheAudit()}.</p>
 */
public class BinlogEventAuditTest {

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("audit-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<String> errors() {
            return events.stream().filter(e -> e.getLevel() == Level.ERROR)
                    .map(e -> e.getMessage().getFormattedMessage()).collect(Collectors.toList());
        }
    }

    private CapturingAppender appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        BinlogEventAudit.reset();
        appender = new CapturingAppender();
        appender.start();
        logger = (Logger) LogManager.getLogger(BinlogEventAudit.class);
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detach() {
        logger.removeAppender(appender);
        BinlogEventAudit.reset();
    }

    private static Event event(EventType type, EventData data) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(type);
        header.setNextPosition(4242L);
        return new Event(header, data);
    }

    private static Event query(String sql) {
        QueryEventData q = new QueryEventData();
        q.setSql(sql);
        q.setDatabase("test");
        return event(EventType.QUERY, q);
    }

    private static Event tableMap(long id, String db, String table) {
        TableMapEventData tm = new TableMapEventData();
        tm.setTableId(id);
        tm.setDatabase(db);
        tm.setTable(table);
        return event(EventType.TABLE_MAP, tm);
    }

    private static Event xaPrepare(boolean onePhase) {
        XAPrepareEventData d = new XAPrepareEventData();
        d.setOnePhase(onePhase);
        return event(EventType.XA_PREPARE, d);
    }

    private static Event writeRows(long tableId) {
        WriteRowsEventData d = new WriteRowsEventData();
        d.setTableId(tableId);
        d.setRows(new ArrayList<>());
        return event(EventType.EXT_WRITE_ROWS, d);
    }

    private static void feed(BinlogEventAudit audit, Event... events) {
        for (Event e : events) {
            audit.onEvent(e);
        }
    }

    @Test
    void xidTextIsTheSameForEveryVerb() {
        assertEquals("X'7831',X'',1", BinlogEventAudit.xid("XA START X'7831',X'',1", "XA START"));
        assertEquals("X'7831',X'',1", BinlogEventAudit.xid("XA ROLLBACK X'7831',X'',1", "XA ROLLBACK"));
        assertEquals("X'7833',X'',1", BinlogEventAudit.xid("XA COMMIT X'7833',X'',1 ONE PHASE", "XA COMMIT"));
    }

    /** The E3 sequence: only the rolled-back XA transaction is reported, with the tables its PREPARE wrote. */
    @Test
    void xaRollbackAfterPrepareIsReportedWithItsTables() {
        BinlogEventAudit audit = new BinlogEventAudit(null);
        feed(audit,
                query("XA START X'7831',X'',1"), tableMap(129, "test", "e_xa"), writeRows(129),
                query("XA END X'7831',X'',1"), xaPrepare(false), query("XA COMMIT X'7831',X'',1"),
                query("XA START X'7832',X'',1"), tableMap(129, "test", "e_xa"), tableMap(130, "test", "e_other"),
                writeRows(129), query("XA END X'7832',X'',1"), xaPrepare(false), query("XA ROLLBACK X'7832',X'',1"),
                query("XA START X'7833',X'',1"), tableMap(129, "test", "e_xa"), writeRows(129),
                query("XA END X'7833',X'',1"), xaPrepare(true));
        List<String> errors = appender.errors();
        assertEquals(1, errors.size(), "exactly the rolled-back XA is reported: " + errors);
        String e = errors.get(0);
        assertTrue(e.contains("X'7832'"), e);
        assertTrue(e.contains("test.e_other") && e.contains("test.e_xa"), e);
        assertTrue(e.contains("ch-mysql-resync"), e);
    }

    /**
     * With compression on, the body of the XA transaction arrives through the payload decoder
     * ({@link BinlogEventAudit#observeInner}); only XA_PREPARE and the ROLLBACK are outer events. Mutation-checked:
     * without the inner feed the tables are reported UNKNOWN (what the first end-to-end run showed).
     */
    @Test
    void compressedXaBodyFedFromThePayloadIsTracked() {
        BinlogEventAudit audit = new BinlogEventAudit(null);
        BinlogEventAudit.observeInner(query("XA START X'77',X'',1"));
        BinlogEventAudit.observeInner(tableMap(5, "test", "e_xa"));
        BinlogEventAudit.observeInner(query("XA END X'77',X'',1"));
        feed(audit, xaPrepare(false), query("XA ROLLBACK X'77',X'',1"));
        assertEquals(1, appender.errors().size(), appender.errors().toString());
        assertTrue(appender.errors().get(0).contains("[test.e_xa]"), appender.errors().get(0));
    }

    @Test
    void xaRollbackWhosePrepareWasNotSeenSaysSo() {
        BinlogEventAudit audit = new BinlogEventAudit(null);
        feed(audit, query("XA ROLLBACK X'99',X'',1"));
        assertEquals(1, appender.errors().size());
        assertTrue(appender.errors().get(0).contains("UNKNOWN"), appender.errors().get(0));
    }

    @Test
    void ordinaryQueriesAndCommittedXaAreSilent() {
        BinlogEventAudit audit = new BinlogEventAudit(null);
        feed(audit, query("BEGIN"), tableMap(1, "test", "t"), writeRows(1), query("COMMIT"),
                query("XA START X'01',X'',1"), writeRows(1), query("XA END X'01',X'',1"), xaPrepare(false),
                query("XA COMMIT X'01',X'',1"), query("alter table t add column c int"));
        assertEquals(0, appender.errors().size(), appender.errors().toString());
    }

    @Test
    void anAuditFailureNeverStopsTheReader() {
        BinlogEventAudit audit = new BinlogEventAudit(null);
        assertDoesNotThrow(() -> audit.onEvent(event(EventType.QUERY, null)));
        assertDoesNotThrow(() -> audit.onEvent(null));
        assertDoesNotThrow(() -> BinlogEventAudit.observeInner(null));
    }
}
