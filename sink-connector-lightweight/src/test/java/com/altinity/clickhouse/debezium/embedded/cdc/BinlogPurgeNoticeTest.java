package com.altinity.clickhouse.debezium.embedded.cdc;

import com.github.shyiko.mysql.binlog.network.ServerException;
import io.debezium.DebeziumException;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the engine's completion callback says when the binlog the durable offset points into has been
 * purged from the source (error 1236 "Could not find first log file name in binary log index file",
 * spec 01.07 FM-01.07-5). The ERROR names the file from the stored offset when it can be read, says that
 * the data from the stored position to the source's current position is lost, and says that a resync is
 * required. It never blames server_id.
 */
public class BinlogPurgeNoticeTest {

    private static final String PURGED = "Could not find first log file name in binary log index file";
    private static final String NOTICE = "Binlog purged on the source";

    private int savedMaxRetries;
    private int savedSleep;
    private IntConsumer savedHook;
    private final List<Integer> exitCodes = new ArrayList<>();
    private CapturingAppender appender;

    @BeforeEach
    public void arm() {
        savedMaxRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        savedSleep = DebeziumChangeEventCapture.SLEEP_TIME;
        savedHook = DebeziumChangeEventCapture.terminalFailureHook;
        DebeziumChangeEventCapture.MAX_RETRIES = 3;
        DebeziumChangeEventCapture.SLEEP_TIME = 0;
        DebeziumChangeEventCapture.terminalFailureHook = exitCodes::add;
        Configurator.setLevel(DebeziumChangeEventCapture.class.getName(), Level.INFO);
        appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(DebeziumChangeEventCapture.class)).addAppender(appender);
    }

    @AfterEach
    public void disarm() {
        ((Logger) LogManager.getLogger(DebeziumChangeEventCapture.class)).removeAppender(appender);
        appender.stop();
        DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
        DebeziumChangeEventCapture.SLEEP_TIME = savedSleep;
        DebeziumChangeEventCapture.terminalFailureHook = savedHook;
    }

    /** Engine properties carrying the two mandatory zone keys, as every started connector has them. */
    private static Properties zonedProperties() {
        Properties props = new Properties();
        props.setProperty("database.connectionTimeZone", "UTC");
        props.setProperty("clickhouse.datetime.timezone", "UTC");
        return props;
    }

    /** The producer failure exactly as Debezium hands over a purged-binlog dump failure. */
    private static ConnectException purgedBinlog() {
        return new ConnectException(
                "An exception occurred in the change event producer. This connector will be stopped.",
                new DebeziumException(PURGED + " Error code: 1236; SQLSTATE: HY000.",
                        new ServerException(PURGED, 1236, "HY000")));
    }

    /**
     * A ClickHouse connection whose offset-store read returns {@code offsetJson} as the single
     * {@code offset_val} row, the way {@code DBMetadata.executeSystemQuery} reads it.
     */
    private static Connection offsetStore(String offsetJson) {
        boolean[] consumed = {false};
        ResultSet rs = proxy(ResultSet.class, (p, method, args) -> {
            switch (method.getName()) {
                case "next":
                    boolean more = !consumed[0];
                    consumed[0] = true;
                    return more;
                case "getString":
                    return offsetJson;
                default:
                    return defaultFor(method.getReturnType());
            }
        });
        PreparedStatement ps = proxy(PreparedStatement.class, (p, method, args) -> {
            switch (method.getName()) {
                case "execute":
                    return true;
                case "getResultSet":
                    return rs;
                default:
                    return defaultFor(method.getReturnType());
            }
        });
        return proxy(Connection.class, (p, method, args) ->
                "prepareStatement".equals(method.getName()) ? ps : defaultFor(method.getReturnType()));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(BinlogPurgeNoticeTest.class.getClassLoader(),
                new Class<?>[]{type}, handler));
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /** The formatted text of every ERROR the capture logs that contains {@code text}. */
    private List<String> errorsContaining(String text) {
        List<String> out = new ArrayList<>();
        synchronized (appender.events) {
            for (LogEvent e : appender.events) {
                String msg = e.getMessage().getFormattedMessage();
                if (e.getLevel() == Level.ERROR && msg.contains(text)) {
                    out.add(msg);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("a purged binlog is logged at ERROR naming the stored file and position, the lost range and the resync")
    public void purgedBinlogNamesTheStoredFileAndPosition() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.systemDbConnection = offsetStore("{\"file\":\"mysql-bin.000123\",\"pos\":4567,\"gtids\":null}");

        capture.handleEngineCompletion(false, "engine stopped", purgedBinlog(), zonedProperties(), () -> { });

        List<String> notices = errorsContaining(NOTICE);
        assertEquals(1, notices.size(), "one ERROR notice per failed attempt");
        String notice = notices.get(0);
        assertTrue(notice.contains("mysql-bin.000123"), "the notice names the stored file: " + notice);
        assertTrue(notice.contains("4567"), "the notice names the stored position: " + notice);
        assertTrue(notice.contains("lost"), "the notice says the data is lost: " + notice);
        assertTrue(notice.contains("resync"), "the notice says a resync is required: " + notice);
    }

    @Test
    @DisplayName("a purged binlog whose stored offset cannot be read says the file is unknown, and still says what is lost")
    public void purgedBinlogWithoutAReadableOffsetSaysTheFileIsUnknown() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();   // no ClickHouse connection

        capture.handleEngineCompletion(false, "engine stopped", purgedBinlog(), new Properties(), () -> { });

        List<String> notices = errorsContaining(NOTICE);
        assertEquals(1, notices.size(), "one ERROR notice per failed attempt");
        String notice = notices.get(0);
        assertTrue(notice.contains("unknown"), "an unreadable stored file is said, not guessed: " + notice);
        assertTrue(notice.contains("lost"), notice);
        assertTrue(notice.contains("resync"), notice);
    }

    @Test
    @DisplayName("the purged-binlog notice never blames server_id or server_uuid")
    public void purgedBinlogNoticeNeverBlamesServerId() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.systemDbConnection = offsetStore("{\"file\":\"mysql-bin.000123\",\"pos\":4567}");

        capture.handleEngineCompletion(false, "engine stopped", purgedBinlog(), new Properties(), () -> { });

        assertEquals(1, errorsContaining(NOTICE).size(), "the purge is noticed");
        assertTrue(errorsContaining("server_id").isEmpty(),
                "error 1236 for a purged binlog is not a server_id collision: " + errorsContaining("server_id"));
        assertTrue(errorsContaining("server_uuid").isEmpty(),
                "error 1236 for a purged binlog is not a server_uuid collision: " + errorsContaining("server_uuid"));
    }

    @Test
    @DisplayName("a dropped source connection logs no purged-binlog notice")
    public void connectionLossLogsNoPurgeNotice() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ConnectException connectionLost = new ConnectException(
                "An exception occurred in the change event producer. This connector will be stopped.",
                new DebeziumException("Failed to read next byte from position 1934012",
                        new EOFException("Failed to read next byte from position 1934012")));
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(false, "engine stopped", connectionLost, new Properties(),
                restarts::incrementAndGet);

        assertEquals(1, restarts.get(), "a lost connection is still retried");
        assertTrue(errorsContaining(NOTICE).isEmpty(), "only a purged binlog gets the notice");
        assertEquals(Collections.emptyList(), exitCodes);
    }

    /** Collects what DebeziumChangeEventCapture logs during one test. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-binlog-purge-notice", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
