package com.altinity.clickhouse.debezium.embedded.cdc;

import com.github.shyiko.mysql.binlog.network.ServerException;
import io.debezium.DebeziumException;
import org.apache.kafka.connect.errors.ConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the engine's completion callback classifies the source-side and
 * record-side failures of spec 01.06 section 6 and spec 01.07 section 6.
 *
 * <p>Every MySQL producer failure reaches this callback: Debezium 3.1.3's
 * {@code MySqlErrorHandler.isRetriable} walks the cause chain to its end and then
 * returns {@code super.isRetriable(null)}, which is {@code false}, so no producer
 * failure is ever retried inside the embedded engine (verified in the bytecode of
 * {@code debezium-connector-mysql-3.1.3.Final.jar}); the task fails with
 * {@code ConnectException("An exception occurred in the change event producer. This
 * connector will be stopped.")} and {@code handleEngineCompletion} decides.</p>
 *
 * <p>A transient failure (a dropped connection) must draw on the retry budget; a
 * deterministic one (a purged binlog, a row the converter cannot represent) ends
 * the same way on every attempt, so spending {@code errors.max.retries} full
 * engine restarts on it only delays the terminal exit that the supervisor needs.</p>
 */
public class EngineFailureClassificationTest {

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
    }

    @AfterEach
    public void disarm() {
        DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
        DebeziumChangeEventCapture.SLEEP_TIME = savedSleep;
        DebeziumChangeEventCapture.terminalFailureHook = savedHook;
    }

    /** The producer failure exactly as Debezium hands it over for a binlog-client exception. */
    private static ConnectException producerFailure(Exception clientException, String wrappedMessage) {
        return new ConnectException(
                "An exception occurred in the change event producer. This connector will be stopped.",
                new DebeziumException(wrappedMessage, clientException));
    }

    @Test
    @DisplayName("A dropped source connection is transient: it draws on the retry budget and recreates the engine")
    public void sourceConnectionLossStillRetries() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        ConnectException connectionLost = producerFailure(
                new EOFException("Failed to read next byte from position 1934012"),
                "Failed to read next byte from position 1934012");

        capture.handleEngineCompletion(false, "engine stopped", connectionLost, new Properties(),
                restarts::incrementAndGet);

        assertEquals(1, restarts.get(), "a lost connection is retried from the durable offset");
        assertTrue(exitCodes.isEmpty());
    }

    @Test
    @Disabled("DEFECT FM-01.07-5: a purged binlog (server error 1236) is retried errors.max.retries times "
            + "(10 x (10 s + a full engine start)) before the terminal exit, and then again after every "
            + "supervisor restart; no retry can succeed")
    @DisplayName("A purged binlog is terminal at once")
    public void purgedBinlogIsTerminalAtOnce() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        String serverMessage = "Could not find first log file name in binary log index file";
        ConnectException purged = producerFailure(
                new ServerException(serverMessage, 1236, "HY000"),
                serverMessage + " Error code: 1236; SQLSTATE: HY000.");

        capture.handleEngineCompletion(false, "engine stopped", purged, new Properties(),
                restarts::incrementAndGet);

        assertEquals(0, restarts.get(), "the binlog the offset points into no longer exists; every retry "
                + "re-reads the schema history and fails on the same position");
        assertEquals(Collections.singletonList(DebeziumChangeEventCapture.TERMINAL_FAILURE_EXIT_CODE), exitCodes);
    }

    @Test
    @Disabled("DEFECT FM-01.06-1: RecordReplicationException (a row the converter cannot represent) is "
            + "classified UNKNOWN, not FATAL, so the same record is redelivered errors.max.retries times "
            + "before the terminal exit")
    @DisplayName("A row the converter cannot represent is terminal at once")
    public void unconvertibleRowIsTerminalAtOnce() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        RecordReplicationException poison = new RecordReplicationException(
                "Row record (op present) could not be converted to a ClickHouse row; stopping the pipeline "
                        + "rather than acknowledging its offset and silently dropping it. "
                        + "Topic(embeddedconnector.db.orders)");

        capture.handleEngineCompletion(false,
                "Stopping connector after error in the application's handler method: " + poison.getMessage(),
                poison, new Properties(), restarts::incrementAndGet);

        assertEquals(0, restarts.get(), "the same record is redelivered to the same outcome on every attempt");
        assertEquals(Collections.singletonList(DebeziumChangeEventCapture.TERMINAL_FAILURE_EXIT_CODE), exitCodes);
    }
}
