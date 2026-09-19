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
 * and each group is a distinct RoutedBatch on the correct queue.</p>
 *
 * <p>The routing method is private; it is driven reflectively. No ClickHouse,
 * MySQL or Debezium engine is required.</p>
 */
public class HashRoutingPerTableOrderingTest {

    private static final int POOL = 4;

    @AfterEach
    public void drainCounter() {
        // appendToRecordsWithHashRouting increments the shared handoff counter
        // per group; release everything so other tests start quiescent.
        for (int i = 0; i < 4096 && DebeziumOffsetManagement.hasUnwrittenBatches(); i++) {
            DebeziumOffsetManagement.batchHandoffFailed();
        }
    }

    private static ClickHouseStruct rec(String topic) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic(topic);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<LinkedBlockingQueue<RoutedBatch>> wireCapture(DebeziumChangeEventCapture capture)
            throws Exception {
        Field poolField = DebeziumChangeEventCapture.class.getDeclaredField("threadPoolSize");
        poolField.setAccessible(true);
        poolField.setInt(capture, POOL);

        List<LinkedBlockingQueue<RoutedBatch>> queues = new ArrayList<>();
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
}
