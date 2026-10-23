package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The row format is checked again before every in-process engine restart (spec 01.10 section 3.1, spec 10.04
 * section 3.6): a source changed since start is not replicated from.
 *
 * <p><b>The gap.</b> The check ran once, at start. A source switched to {@code binlog_row_image=MINIMAL} while
 * the connector ran was restarted into unchanged, and every UPDATE from then on diverged silently.</p>
 *
 * <p>The source is the stub from {@link BinlogRowImagePreflightTest}, handed to the check through its
 * connection opener, so no MySQL is needed.</p>
 */
public class BinlogRowImageRestartTest {

    private BinlogRowImagePreflight.ConnectionOpener savedOpener;
    private IntConsumer savedHook;
    private int savedSleep;
    private final List<Integer> exitCodes = new ArrayList<>();

    @BeforeEach
    void saveState() {
        savedOpener = BinlogRowImagePreflight.opener;
        savedHook = DebeziumChangeEventCapture.terminalFailureHook;
        savedSleep = DebeziumChangeEventCapture.SLEEP_TIME;
        DebeziumChangeEventCapture.terminalFailureHook = exitCodes::add;
        DebeziumChangeEventCapture.SLEEP_TIME = 0;
    }

    @AfterEach
    void restoreState() {
        BinlogRowImagePreflight.opener = savedOpener;
        DebeziumChangeEventCapture.terminalFailureHook = savedHook;
        DebeziumChangeEventCapture.SLEEP_TIME = savedSleep;
    }

    private static Properties mysqlProps() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("database.hostname", "mysql-host");
        props.setProperty("database.user", "u");
        props.setProperty("database.password", "p");
        props.setProperty("exit.on.terminal.failure", "true");
        return props;
    }

    @Test
    @DisplayName("A restart against a source switched to MINIMAL since start is refused: no engine is recreated")
    public void restartRechecksRowFormatAndRefusesAnUpdatedSource() {
        BinlogRowImagePreflight.opener = (url, user, password) ->
                BinlogRowImagePreflightTest.source("MINIMAL", "");
        AtomicInteger restarts = new AtomicInteger();

        new DebeziumChangeEventCapture().handleEngineCompletion(false, "engine stopped",
                new IOException("connection reset"), mysqlProps(), () -> restarts.incrementAndGet());

        assertEquals(0, restarts.get(),
                "a recreated engine would replicate every UPDATE with the untouched columns NULL");
        assertEquals(Collections.singletonList(DebeziumChangeEventCapture.TERMINAL_FAILURE_EXIT_CODE), exitCodes,
                "the refusal is terminal, as a FATAL failure is");
    }

    @Test
    @DisplayName("A restart against a source that is FULL again recreates the engine")
    public void restartProceedsWhenTheSourceIsFull() {
        BinlogRowImagePreflight.opener = (url, user, password) ->
                BinlogRowImagePreflightTest.source("FULL", "");
        AtomicInteger restarts = new AtomicInteger();

        new DebeziumChangeEventCapture().handleEngineCompletion(false, "engine stopped",
                new IOException("connection reset"), mysqlProps(), () -> restarts.incrementAndGet());

        assertEquals(1, restarts.get(), "a full-row source may be restarted into");
        assertEquals(0, exitCodes.size(), "no exit while the source is accepted");
    }
}
