package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stopped engine's handed-off units are retired before anything else
 * happens -- at {@code connectorStopped} and at the top of the completion
 * callback -- so no worker acknowledges through a committer whose offset
 * store has closed (spec 09.01 §3.8 item 5, spec 10.04 §3.5).
 *
 * <p><b>The defect.</b> The completion-callback retry recreates the engine on
 * this same instance and keeps the worker pool, and the pool keeps the
 * stopped engine's batches. Debezium closes the engine's offset store when
 * the engine completes, but the units stayed outstanding for the pool to
 * "finish": the first one written was acknowledged through the stopped
 * engine's committer, {@code JdbcOffsetBackingStore.set} threw
 * {@code NullPointerException} ("this.executor is null") inside
 * {@code OffsetStorageWriter.doFlush}, the writer stayed "already flushing",
 * the worker died on its next acknowledgement, and every recreated engine
 * failed on "Sink worker 1 of 10 is dead" until the process exited --
 * 234 ms after the completion callback had already run, twice in one day.</p>
 */
public class StoppedEngineRetiresHandoffsTest {

    private int savedMaxRetries;
    private int savedSleep;
    private IntConsumer savedHook;
    private final List<Integer> exitCodes = new ArrayList<>();

    @BeforeEach
    public void arm() {
        savedMaxRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        savedSleep = DebeziumChangeEventCapture.SLEEP_TIME;
        savedHook = DebeziumChangeEventCapture.terminalFailureHook;
        DebeziumChangeEventCapture.MAX_RETRIES = 3;
        DebeziumChangeEventCapture.SLEEP_TIME = 0;
        DebeziumChangeEventCapture.terminalFailureHook = exitCodes::add;
        DebeziumOffsetManagement.reset();
    }

    @AfterEach
    public void disarm() {
        DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
        DebeziumChangeEventCapture.SLEEP_TIME = savedSleep;
        DebeziumChangeEventCapture.terminalFailureHook = savedHook;
        DebeziumOffsetManagement.reset();
    }

    /** A committer whose engine has stopped: its offset store is closed, so every call throws. */
    private static final class ClosedStoreCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {
        int calls = 0;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            calls++;
            throw new NullPointerException("Cannot invoke \"java.util.concurrent.ExecutorService"
                    + ".submit(java.util.concurrent.Callable)\" because \"this.executor\" is null");
        }

        @Override
        public void markBatchFinished() {
            calls++;
            throw new NullPointerException("Cannot invoke \"java.util.concurrent.ExecutorService"
                    + ".submit(java.util.concurrent.Callable)\" because \"this.executor\" is null");
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            markProcessed(record);
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> event(String name) {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return null;
            }

            @Override
            public String destination() {
                return name;
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /** One handed-off unit of the (about to be) stopped engine, as the producer registers it. */
    private static List<ClickHouseStruct> handedOffUnit(ClosedStoreCommitter committer) {
        List<ClickHouseStruct> unit = new ArrayList<>();
        for (String name : new String[] {"orders", "orders"}) {
            ClickHouseStruct s = new ClickHouseStruct();
            s.setTopic("srv.db." + name);
            s.setDebezium_ts_ms(1L);
            s.setCommitter(committer);
            s.setSourceRecord(event(name));
            unit.add(s);
        }
        unit.get(unit.size() - 1).setLastRecordInBatch(true);
        DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));
        return unit;
    }

    /** The source-side failure that preceded the worker's death in production. */
    private static RuntimeException sourceConnectionLost() {
        return new ConnectException("An exception occurred in the change event producer. "
                + "This connector will be stopped.",
                new RuntimeException("Failed to deserialize data of EventHeaderV4",
                        new java.io.EOFException("Failed to read next byte from position 336461968")));
    }

    private static Properties props() {
        Properties p = new Properties();
        p.setProperty(SinkConnectorLightWeightConfig.EXIT_ON_TERMINAL_FAILURE, "true");
        return p;
    }

    @Test
    @DisplayName("The completion callback retires the stopped engine's units before retrying: "
            + "a worker that writes one afterwards never touches the closed-store committer")
    public void completionCallbackRetiresTheStoppedEnginesUnitsBeforeRetrying()
            throws InterruptedException {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClosedStoreCommitter committer = new ClosedStoreCommitter();
        List<ClickHouseStruct> unit = handedOffUnit(committer);
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches());
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(false, "engine stopped", sourceConnectionLost(), props(),
                restarts::incrementAndGet);

        assertEquals(1, restarts.get(), "a transient source failure with a live pool is retried");
        assertEquals(Collections.emptyList(), exitCodes, "and is not terminal");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the stopped engine's unit is retired before the retry, not left for the pool to finish");

        // The pool finishes the old batch after the retry has started.
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unit),
                "the written batch is not acknowledged (redelivered by the new engine)");
        assertEquals(0, committer.calls,
                "the stopped engine's committer is never called (pre-fix: NPE from the closed store, "
                        + "a poisoned OffsetStorageWriter, a dead worker, ten futile engine restarts)");
    }

    @Test
    @DisplayName("connectorStopped() retires the engine's units and marks replication not running")
    public void connectorStoppedRetiresTheEnginesUnits() throws InterruptedException {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClosedStoreCommitter committer = new ClosedStoreCommitter();
        List<ClickHouseStruct> unit = handedOffUnit(committer);

        capture.onConnectorStopped();

        assertFalse(ReplicationStatusSingleton.getInstance().isReplicationRunning());
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unit));
        assertEquals(0, committer.calls);
        assertEquals(0, capture.retireHandoffsOfStoppedEngine("again"),
                "retirement is idempotent: a second call retires nothing");
    }

    @Test
    @DisplayName("A clean completion retires too: the store closes whichever way the engine ends")
    public void cleanCompletionRetiresTheEnginesUnits() throws InterruptedException {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClosedStoreCommitter committer = new ClosedStoreCommitter();
        List<ClickHouseStruct> unit = handedOffUnit(committer);
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(true, "engine closed", null, props(), restarts::incrementAndGet);

        assertEquals(0, restarts.get(), "a clean completion is not retried");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unit));
        assertEquals(0, committer.calls);
    }
}
