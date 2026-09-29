package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the dead hash-routing path / per-table ordering defect.
 *
 * <p><b>The defect.</b> {@code routedQueues} (formerly a single {@code
 * routedRecords} queue) was never assigned, so the hash-routing branch was dead:
 * every worker polled one shared {@code records} queue and same-table batches
 * were processed concurrently, out of source order.</p>
 *
 * <p><b>The fix.</b> One queue per worker thread; every batch for a given table
 * is routed to a single thread's queue by a stable hash, and that thread drains
 * only its own queue in FIFO order. These tests verify the routing invariant:
 * a table always lands on the same thread's queue (so its batches stay ordered),
 * each group is a distinct RoutedBatch on the correct queue, and every group of
 * one handed-off list carries the list's handoff sequence (spec 01.05 §3.3).</p>
 *
 * <p>The routing method is private; it is driven reflectively. No ClickHouse,
 * MySQL or Debezium engine is required.</p>
 */
public class HashRoutingPerTableOrderingTest {

    private static final int POOL = 4;

    private List<LinkedBlockingQueue<RoutedBatch>> queues;

    @AfterEach
    public void drainHandoffs() throws Exception {
        // Every routed group was registered under a handoff sequence; report
        // each as written through the production API (the records carry no
        // committer, so acknowledging them stages nothing) so other tests start
        // quiescent.
        if (queues == null) {
            return;
        }
        for (LinkedBlockingQueue<RoutedBatch> queue : queues) {
            RoutedBatch rb;
            while ((rb = queue.poll()) != null) {
                DebeziumOffsetManagement.checkIfBatchCanBeCommitted(rb.getBatch());
            }
        }
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "every routed group was written, so nothing may remain outstanding");
    }

    private static ClickHouseStruct rec(String topic) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic(topic);
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<LinkedBlockingQueue<RoutedBatch>> wireCapture(DebeziumChangeEventCapture capture)
            throws Exception {
        Field poolField = DebeziumChangeEventCapture.class.getDeclaredField("threadPoolSize");
        poolField.setAccessible(true);
        poolField.setInt(capture, POOL);

        queues = new ArrayList<>();
        for (int i = 0; i < POOL; i++) {
            queues.add(new LinkedBlockingQueue<>());
        }
        Field queuesField = DebeziumChangeEventCapture.class.getDeclaredField("routedQueues");
        queuesField.setAccessible(true);
        queuesField.set(capture, queues);
        return queues;
    }

    private static void route(DebeziumChangeEventCapture capture, List<ClickHouseStruct> records)
            throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "appendToRecordsWithHashRouting", List.class);
        m.setAccessible(true);
        m.invoke(capture, records);
    }

    @Test
    @DisplayName("All records for a table route to that table's single owning thread queue")
    public void sameTableRoutesToOneQueue() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<LinkedBlockingQueue<RoutedBatch>> queues = wireCapture(capture);

        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(rec("srv.db.orders"));
        records.add(rec("srv.db.orders"));
        records.add(rec("srv.db.orders"));

        route(capture, records);

        int owner = RoutedBatch.calculateThreadId("db.orders", POOL);
        // Exactly one batch, on the owning queue, holding all three records.
        assertEquals(1, queues.get(owner).size(),
                "the table's owning queue must hold exactly one routed batch");
        RoutedBatch rb = queues.get(owner).peek();
        assertEquals("orders", rb.getTableName());
        assertEquals(3, rb.getBatch().size(), "all records for the table must be in one batch");
        // No other queue received an orders batch.
        for (int i = 0; i < POOL; i++) {
            if (i != owner) {
                assertTrue(queues.get(i).isEmpty(),
                        "no other thread's queue may receive this table's batch (queue " + i + ")");
            }
        }
    }

    @Test
    @DisplayName("Successive batches for the same table land on the SAME queue (FIFO ordering)")
    public void successiveBatchesForSameTableStayOnSameQueue() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<LinkedBlockingQueue<RoutedBatch>> queues = wireCapture(capture);

        List<ClickHouseStruct> first = new ArrayList<>();
        first.add(rec("srv.db.orders"));
        route(capture, first);

        List<ClickHouseStruct> second = new ArrayList<>();
        second.add(rec("srv.db.orders"));
        route(capture, second);

        int owner = RoutedBatch.calculateThreadId("db.orders", POOL);
        assertEquals(2, queues.get(owner).size(),
                "both batches for the table must queue on the same owning thread, preserving order");
    }

    @Test
    @DisplayName("Each table's group is routed to its own hash-assigned queue")
    public void differentTablesRouteByHash() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<LinkedBlockingQueue<RoutedBatch>> queues = wireCapture(capture);

        // Two tables in one batch; each group must land on its computed queue.
        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(rec("srv.db.orders"));
        records.add(rec("srv.db.customers"));
        route(capture, records);

        int ordersOwner = RoutedBatch.calculateThreadId("db.orders", POOL);
        int customersOwner = RoutedBatch.calculateThreadId("db.customers", POOL);

        boolean ordersFound = queues.get(ordersOwner).stream()
                .anyMatch(rb -> "orders".equals(rb.getTableName()));
        boolean customersFound = queues.get(customersOwner).stream()
                .anyMatch(rb -> "customers".equals(rb.getTableName()));
        assertTrue(ordersFound, "orders group must be on its computed owning queue");
        assertTrue(customersFound, "customers group must be on its computed owning queue");
    }

    @Test
    @DisplayName("Every group of one handed-off list carries ONE handoff sequence; later handoffs get a greater one")
    public void routedGroupsCarryTheUnitSequenceInHandoffOrder() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<LinkedBlockingQueue<RoutedBatch>> queues = wireCapture(capture);

        List<ClickHouseStruct> first = new ArrayList<>();
        first.add(rec("srv.db.orders"));
        first.add(rec("srv.db.customers"));
        route(capture, first);
        // The unit is outstanding from the instant of handoff (spec 09.01 §3.1).
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches());

        List<ClickHouseStruct> second = new ArrayList<>();
        second.add(rec("srv.db.orders"));
        route(capture, second);

        List<RoutedBatch> all = new ArrayList<>();
        for (LinkedBlockingQueue<RoutedBatch> q : queues) {
            all.addAll(q);
        }
        assertEquals(3, all.size(), "two groups from the first list, one from the second");

        long firstSeq = -1;
        long secondSeq = -1;
        for (RoutedBatch rb : all) {
            if (rb.getBatch().get(0) == first.get(0) || rb.getBatch().get(0) == first.get(1)) {
                if (firstSeq < 0) {
                    firstSeq = rb.getHandoffSequence();
                } else {
                    assertEquals(firstSeq, rb.getHandoffSequence(),
                            "both groups of one Debezium batch must share its handoff sequence");
                }
            } else {
                secondSeq = rb.getHandoffSequence();
            }
        }
        assertTrue(firstSeq >= 0 && secondSeq >= 0);
        assertTrue(firstSeq < secondSeq,
                "a later handoff must receive a strictly greater sequence (binlog order)");
    }

    private static ClickHouseStruct recKeyed(String topic, String key) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic(topic);
        s.setKey(key);
        java.util.ArrayList<String> pk = new java.util.ArrayList<>();
        pk.add("id");
        s.setPrimaryKey(pk);
        s.setCdcOperation(com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE);
        return s;
    }

    @Test
    @DisplayName("Under key routing, distinct rows of one table spread across more than one queue")
    public void differentRowsOfOneTableCanRouteToDifferentQueues() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<LinkedBlockingQueue<RoutedBatch>> queues = wireCapture(capture);

        List<ClickHouseStruct> records = new ArrayList<>();
        for (int id = 1; id <= 24; id++) {
            records.add(recKeyed("srv.db.orders", "Struct{id=" + id + "}"));
        }
        route(capture, records);

        int nonEmpty = 0;
        for (LinkedBlockingQueue<RoutedBatch> q : queues) {
            if (!q.isEmpty()) {
                nonEmpty++;
            }
        }
        assertTrue(nonEmpty > 1,
                "distinct rows of one hot table must spread across more than one worker queue "
                        + "(was " + nonEmpty + ")");
    }

    @Test
    @DisplayName("Under key routing, every change to one row stays on a single queue in order")
    public void sameRowKeyStaysOnOneQueue() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<LinkedBlockingQueue<RoutedBatch>> queues = wireCapture(capture);

        // Three changes to the same row in one poll batch: one group, one queue.
        List<ClickHouseStruct> first = new ArrayList<>();
        first.add(recKeyed("srv.db.orders", "Struct{id=7}"));
        first.add(recKeyed("srv.db.orders", "Struct{id=7}"));
        first.add(recKeyed("srv.db.orders", "Struct{id=7}"));
        route(capture, first);

        int owner = RoutedBatch.calculateThreadId(
                RoutedBatch.createShardKey(first.get(0), true), POOL);
        assertEquals(1, queues.get(owner).size(),
                "all changes to one row in a batch must be one routed group on one queue");
        assertEquals(3, queues.get(owner).peek().getBatch().size(),
                "the group must hold all three changes to the row, in order");
        for (int i = 0; i < POOL; i++) {
            if (i != owner) {
                assertTrue(queues.get(i).isEmpty(),
                        "no other queue may receive this row (queue " + i + ")");
            }
        }

        // A later change to the same row lands on the SAME queue.
        List<ClickHouseStruct> second = new ArrayList<>();
        second.add(recKeyed("srv.db.orders", "Struct{id=7}"));
        route(capture, second);
        assertEquals(2, queues.get(owner).size(),
                "a later change to the same row must queue on the same worker, preserving order");
    }

    @Test
    @DisplayName("A poll batch with a truncate-table event splits into ordered segments")
    public void truncateInMixedBatchSplitsIntoOrderedSegments() throws Exception {
        ClickHouseStruct ins1 = recKeyed("srv.db.orders", "Struct{id=1}");
        ClickHouseStruct trunc = new ClickHouseStruct();
        trunc.setTopic("srv.db.orders");
        trunc.setCdcOperation(com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.TRUNCATE);
        ClickHouseStruct ins2 = recKeyed("srv.db.orders", "Struct{id=2}");

        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(ins1);
        batch.add(trunc);
        batch.add(ins2);

        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod("splitAtTruncate", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<List<ClickHouseStruct>> segs = (List<List<ClickHouseStruct>>) m.invoke(null, batch);

        assertEquals(3, segs.size(), "pre-DML | truncate-table | post-DML");
        assertTrue(segs.get(0).size() == 1 && segs.get(0).get(0) == ins1,
                "the DML before the truncate-table event is its own segment, in source order");
        assertTrue(segs.get(1).size() == 1 && segs.get(1).get(0) == trunc,
                "the truncate-table event is isolated in its own singleton segment");
        assertTrue(segs.get(2).size() == 1 && segs.get(2).get(0) == ins2,
                "the DML after the truncate-table event follows it, in source order");
    }
}
