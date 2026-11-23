package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ZoneFixtures;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.MetricsConstants;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DDLSchemaChangeWaiter;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.micrometer.core.instrument.Counter;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the DDL retry loop, the DDL metrics and the DDL error
 * table:
 * <ul>
 *   <li>NEW-J4 (a): a DDL that already succeeded is not executed again when a
 *       later step of the same attempt fails;</li>
 *   <li>NEW-J4 (b): statements are split outside quotes, not on every newline;</li>
 *   <li>NEW-J4 (c): {@code errors.max.retries=0} still attempts the DDL once;</li>
 *   <li>NEW-J3 (a), end to end: a column that never becomes visible fails the
 *       DDL closed;</li>
 *   <li>SC-1: the DDL path resolves the error table through the config default;</li>
 *   <li>SC-3b: the DDL counter is tagged {@code fail=false} for a success and
 *       measures from before the execution.</li>
 * </ul>
 */
public class DdlRetryAndMetricsTest {

    private static final String ADD_COLUMN = "ALTER TABLE t ADD COLUMN c INT NULL";

    // ------------------------------------------------------------ fakes

    /**
     * A ClickHouse stand-in. Statements run through {@code prepareStatement().execute()} (the DDL
     * path) are recorded; the schema-visibility poll
     * ({@code SELECT name FROM system.columns ...}) reports column {@code c}
     * as present only when {@code columnVisible}; every other query fails
     * the way a missing metadata table would.
     */
    private static final class FakeClickHouse {
        final List<String> executed = Collections.synchronizedList(new ArrayList<>());
        volatile boolean columnVisible = true;
        volatile String failOncePrefix = null;
        private boolean failedOnce = false;

        Connection connection() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isClosed":
                        return false;
                    case "close":
                        return null;
                    case "prepareStatement":
                        return preparedStatement(String.valueOf(args[0]));
                    case "createStatement":
                        return statement();
                    case "toString":
                        return "fake-clickhouse";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement preparedStatement(String sql) {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "execute":
                        executed.add(sql);
                        if (failOncePrefix != null && !failedOnce && sql.startsWith(failOncePrefix)) {
                            failedOnce = true;
                            throw new SQLException("simulated transient failure on " + failOncePrefix);
                        }
                        return false;
                    case "toString":
                        return "fake-prepared-statement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private Statement statement() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        String sql = String.valueOf(args[0]);
                        if (sql.startsWith("SELECT name FROM system.columns WHERE database = '")) {
                            return columns(columnVisible ? Arrays.asList("c") : Collections.<String>emptyList());
                        }
                        throw new SQLException("simulated: metadata query not available in this test");
                    case "toString":
                        return "fake-statement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class}, handler);
        }

        private static ResultSet columns(List<String> names) {
            int[] cursor = {-1};
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        cursor[0]++;
                        return cursor[0] < names.size();
                    case "getString":
                        return names.get(cursor[0]);
                    case "toString":
                        return "fake-result-set";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }

        long executionsStartingWith(String prefix) {
            synchronized (executed) {
                return executed.stream().filter(s -> s.startsWith(prefix)).count();
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
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

    /** A committer whose first {@code markProcessed} throws, to fail a step AFTER the DDL succeeded. */
    private static final class FlakyCommitter implements InvocationHandler {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final int failFirst;
        private int markProcessedCalls = 0;

        FlakyCommitter(int failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "toString":
                    return "flaky-committer";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    calls.add(method.getName());
                    if ("markProcessed".equals(method.getName())) {
                        markProcessedCalls++;
                        if (markProcessedCalls <= failFirst) {
                            throw new IllegalStateException("simulated acknowledgement failure");
                        }
                    }
                    return null;
            }
        }

        long markProcessedCount() {
            synchronized (calls) {
                return calls.stream().filter("markProcessed"::equals).count();
            }
        }

        @SuppressWarnings("unchecked")
        DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> proxy() {
            return (DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>) Proxy.newProxyInstance(
                    DebeziumEngine.RecordCommitter.class.getClassLoader(),
                    new Class<?>[]{DebeziumEngine.RecordCommitter.class}, this);
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> ddlEvent(String ddl) {
        Schema schema = SchemaBuilder.struct().field("ddl", Schema.STRING_SCHEMA).build();
        Struct value = new Struct(schema);
        value.put("ddl", ddl);
        SourceRecord record = new SourceRecord(null, null, "topic", schema, value);
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return "topic";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        props.put(ClickHouseSinkConnectorConfigVariables.DDL_SCHEMA_CHANGE_TIMEOUT_MS.toString(), "200");
        props.put(ClickHouseSinkConnectorConfigVariables.DDL_SCHEMA_CHANGE_POLL_INTERVAL_MS.toString(), "20");
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    private static DebeziumChangeEventCapture singleThreadedCapture(Connection connection) throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        setField(capture, "executor", null);
        setField(capture, "pgConfig", new PostgresConnectorConfig(new Properties()));
        capture.setWriter(new BaseDbWriter("localhost", 8123, "system", "default", "",
                config(), connection));
        return capture;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invokeProcess(DebeziumChangeEventCapture capture,
                                      ChangeEvent<SourceRecord, SourceRecord> record,
                                      DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer,
                                      Properties props) throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class, ChangeEvent.class, DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class, DebeziumEngine.RecordCommitter.class,
                boolean.class, VersionSequencer.VersionAssignment.class);
        m.setAccessible(true);
        try {
            m.invoke(capture, props, record, null, config(), committer, true,
                    new VersionSequencer.VersionAssignment(1000000001L, 1000L));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    private static Properties retryProps() {
        Properties p = new Properties();
        p.setProperty(SinkConnectorLightWeightConfig.DDL_RETRY, "true");
        return p;
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ NEW-J4 (b): statement splitting

    @Test
    @DisplayName("NEW-J4(b): a newline inside a quoted literal does not split the statement")
    public void splitStatementsKeepsMultiLineLiteralsWhole() {
        List<String> statements = DebeziumChangeEventCapture.splitStatements(
                "ALTER TABLE t MODIFY COLUMN c String COMMENT 'line one\nline two'");
        assertEquals(1, statements.size());
        assertEquals("ALTER TABLE t MODIFY COLUMN c String COMMENT 'line one\nline two'", statements.get(0));
    }

    @Test
    @DisplayName("NEW-J4(b): newlines outside quotes separate statements and blank statements are skipped")
    public void splitStatementsSeparatesOnTopLevelNewlines() {
        List<String> statements = DebeziumChangeEventCapture.splitStatements(
                "ALTER TABLE t ADD COLUMN c Int32\n\n  \nRENAME TABLE IF EXISTS t TO t2\n");
        assertEquals(Arrays.asList("ALTER TABLE t ADD COLUMN c Int32", "RENAME TABLE IF EXISTS t TO t2"),
                statements.stream().map(String::trim).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("NEW-J4(b): double quotes, backticks and backslash-escaped quotes are honoured")
    public void splitStatementsHonoursEveryQuoteKind() {
        assertEquals(1, DebeziumChangeEventCapture.splitStatements(
                "ALTER TABLE t COMMENT COLUMN c \"a\nb\"").size());
        assertEquals(1, DebeziumChangeEventCapture.splitStatements(
                "ALTER TABLE `we\nird` ADD COLUMN c Int32").size());
        // An escaped quote does not end the literal, so the newline after it is still inside it.
        assertEquals(1, DebeziumChangeEventCapture.splitStatements(
                "ALTER TABLE t COMMENT COLUMN c 'it\\'s\nstill one'").size());
        assertEquals(2, DebeziumChangeEventCapture.splitStatements(
                "ALTER TABLE t COMMENT COLUMN c 'closed'\nRENAME TABLE a TO b").size());
    }

    @Test
    @DisplayName("NEW-J4(b): a multi-line comment ALTER is executed as one statement")
    public void multiLineCommentIsExecutedOnce() throws Exception {
        FakeClickHouse clickHouse = new FakeClickHouse();
        FlakyCommitter committer = new FlakyCommitter(0);
        String ddl = "ALTER TABLE t ADD COLUMN c INT NULL COMMENT 'first\nsecond'";

        invokeProcess(singleThreadedCapture(clickHouse.connection()), ddlEvent(ddl), committer.proxy(),
                new Properties());

        assertEquals(1, clickHouse.executionsStartingWith("ALTER TABLE"),
                "the comment's newline must not break the statement in two; executed=" + clickHouse.executed);
        assertEquals(1, clickHouse.executed.size());
    }

    @Test
    @DisplayName("NEW-J4(b): ALTER ... ADD COLUMN, RENAME TO runs as an ALTER and a RENAME")
    public void alterWithRenameRunsTwoStatements() throws Exception {
        FakeClickHouse clickHouse = new FakeClickHouse();
        FlakyCommitter committer = new FlakyCommitter(0);

        invokeProcess(singleThreadedCapture(clickHouse.connection()),
                ddlEvent("ALTER TABLE t ADD COLUMN c INT NULL, RENAME TO t2"), committer.proxy(),
                new Properties());

        assertEquals(2, clickHouse.executed.size(), "executed=" + clickHouse.executed);
        assertTrue(clickHouse.executed.get(0).startsWith("ALTER TABLE"), clickHouse.executed.toString());
        assertTrue(clickHouse.executed.get(1).startsWith("RENAME TABLE"), clickHouse.executed.toString());
    }

    // ------------------------------------------------------------ NEW-J4 (a), (c): the retry loop

    @Test
    @DisplayName("NEW-J4(a): a failure after the DDL succeeded does not execute the DDL a second time")
    public void postDdlFailureDoesNotReExecuteTheDdl() throws Exception {
        int savedMaxRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        long savedBackoff = DebeziumChangeEventCapture.ddlRetryBackoffMs;
        try {
            DebeziumChangeEventCapture.MAX_RETRIES = 3;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = 0;
            FakeClickHouse clickHouse = new FakeClickHouse();
            FlakyCommitter committer = new FlakyCommitter(1);

            invokeProcess(singleThreadedCapture(clickHouse.connection()), ddlEvent(ADD_COLUMN),
                    committer.proxy(), retryProps());

            assertEquals(1, clickHouse.executionsStartingWith("ALTER TABLE"),
                    "the ALTER already succeeded; the retry must only repeat the failed acknowledgement. executed="
                            + clickHouse.executed);
            assertEquals(2, committer.markProcessedCount(),
                    "the acknowledgement is retried after its first failure");
        } finally {
            DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = savedBackoff;
        }
    }

    @Test
    @DisplayName("NEW-J4(c): errors.max.retries=0 still executes the DDL once and acknowledges it")
    public void zeroMaxRetriesStillAttemptsOnce() throws Exception {
        int savedMaxRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        long savedBackoff = DebeziumChangeEventCapture.ddlRetryBackoffMs;
        try {
            DebeziumChangeEventCapture.MAX_RETRIES = 0;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = 0;
            FakeClickHouse clickHouse = new FakeClickHouse();
            FlakyCommitter committer = new FlakyCommitter(0);

            invokeProcess(singleThreadedCapture(clickHouse.connection()), ddlEvent(ADD_COLUMN),
                    committer.proxy(), new Properties());

            assertEquals(1, clickHouse.executionsStartingWith("ALTER TABLE"),
                    "an attempt budget of 0 used to skip the loop: the DDL was never executed yet was "
                            + "acknowledged. executed=" + clickHouse.executed);
            assertEquals(1, committer.markProcessedCount());
        } finally {
            DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = savedBackoff;
        }
    }

    // ------------------------------------------------------------ NEW-J3 (a), end to end

    @Test
    @DisplayName("NEW-J3(a): an added column that never becomes visible fails the DDL and is not acknowledged")
    public void invisibleColumnFailsTheDdlClosed() throws Exception {
        FakeClickHouse clickHouse = new FakeClickHouse();
        clickHouse.columnVisible = false;
        FlakyCommitter committer = new FlakyCommitter(0);

        Exception failure = assertThrows(Exception.class,
                () -> invokeProcess(singleThreadedCapture(clickHouse.connection()), ddlEvent(ADD_COLUMN),
                        committer.proxy(), new Properties()));

        assertTrue(causedBy(failure, DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException.class),
                "the failure must carry the visibility timeout: " + failure);
        assertEquals(0, committer.markProcessedCount(),
                "a DDL whose effect was never observed must not be acknowledged");
    }

    @Test
    @DisplayName("A schema visibility timeout with ddl.retry=true retries visibility only, never re-executing the ALTER")
    public void visibilityTimeoutRetryDoesNotReExecuteAlter() throws Exception {
        int savedMaxRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        long savedBackoff = DebeziumChangeEventCapture.ddlRetryBackoffMs;
        try {
            DebeziumChangeEventCapture.MAX_RETRIES = 3;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = 0;
            FakeClickHouse clickHouse = new FakeClickHouse();
            clickHouse.columnVisible = false;
            FlakyCommitter committer = new FlakyCommitter(0);

            Exception failure = assertThrows(Exception.class,
                    () -> invokeProcess(singleThreadedCapture(clickHouse.connection()), ddlEvent(ADD_COLUMN),
                            committer.proxy(), retryProps()));

            assertTrue(causedBy(failure, DDLSchemaChangeWaiter.SchemaVisibilityTimeoutException.class),
                    "the failure must carry the visibility timeout: " + failure);
            assertEquals(1, clickHouse.executionsStartingWith("ALTER TABLE"),
                    "visibility retry must not re-run an already executed ALTER; executed=" + clickHouse.executed);
            assertEquals(0, committer.markProcessedCount());
        } finally {
            DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = savedBackoff;
        }
    }

    @Test
    @DisplayName("A retry after a second statement fails in a multi-statement DDL does not re-execute the first")
    public void multiStatementRetryDoesNotReExecuteEarlierStatement() throws Exception {
        int savedMaxRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        long savedBackoff = DebeziumChangeEventCapture.ddlRetryBackoffMs;
        try {
            DebeziumChangeEventCapture.MAX_RETRIES = 3;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = 0;
            FakeClickHouse clickHouse = new FakeClickHouse();
            clickHouse.failOncePrefix = "RENAME TABLE";
            FlakyCommitter committer = new FlakyCommitter(0);

            invokeProcess(singleThreadedCapture(clickHouse.connection()),
                    ddlEvent("ALTER TABLE t ADD COLUMN c INT NULL, RENAME TO t2"),
                    committer.proxy(), retryProps());

            assertEquals(1, clickHouse.executionsStartingWith("ALTER TABLE"),
                    "already-executed first statement must not re-run; executed=" + clickHouse.executed);
            assertEquals(2, clickHouse.executionsStartingWith("RENAME TABLE"),
                    "only the failed second statement should retry; executed=" + clickHouse.executed);
            assertEquals(1, committer.markProcessedCount());
        } finally {
            DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
            DebeziumChangeEventCapture.ddlRetryBackoffMs = savedBackoff;
        }
    }

    // ------------------------------------------------------------ SC-1

    @Test
    @DisplayName("SC-1: the DDL error table resolves to replica_source_error when unset")
    public void errorTableDefaultsToReplicaSourceError() {
        assertEquals("replica_source_error", DebeziumChangeEventCapture.resolveErrorTableName(config()));
    }

    @Test
    @DisplayName("SC-1: a configured default.error.table is used by the DDL path")
    public void configuredErrorTableIsUsed() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        props.put(ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString(), "my_errors");
        ClickHouseSinkConnectorConfig configured = new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));

        assertEquals("my_errors", DebeziumChangeEventCapture.resolveErrorTableName(configured));
    }

    // ------------------------------------------------------------ SC-3b

    private static String freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return String.valueOf(socket.getLocalPort());
        }
    }

    private static Counter ddlCounter(String failTag) {
        return Metrics.meterRegistry().find(MetricsConstants.CLICKHOUSE_SINK_DDL).tag("fail", failTag).counter();
    }

    @Test
    @DisplayName("SC-3b: a successful DDL is counted fail=false and measured from before the execution")
    public void successfulDdlIsCountedAsNotFailed() throws Exception {
        Metrics.initialize("true", freePort());
        try {
            long before = ddlCounter("false") == null ? 0L : (long) ddlCounter("false").count();

            DebeziumChangeEventCapture.recordDdlMetrics(ADD_COLUMN, System.currentTimeMillis() - 250, true);

            Counter ok = ddlCounter("false");
            assertNotNull(ok, "a success is tagged fail=false (pre-fix it was tagged fail=true)");
            assertTrue(ok.count() - before >= 250,
                    "the counter is incremented by the elapsed time since the start; got " + (ok.count() - before));
            assertNull(ddlCounter("true"), "a success must not create the fail=true series");
        } finally {
            Metrics.stop();
        }
    }

    @Test
    @DisplayName("SC-3b: a failed DDL is counted fail=true")
    public void failedDdlIsCountedAsFailed() throws Exception {
        Metrics.initialize("true", freePort());
        try {
            DebeziumChangeEventCapture.recordDdlMetrics(ADD_COLUMN, System.currentTimeMillis() - 10, false);

            assertNotNull(ddlCounter("true"));
            assertNull(ddlCounter("false"));
        } finally {
            Metrics.stop();
        }
    }

    @Test
    @DisplayName("SC-3b: a DDL applied through the record path is recorded as a success")
    public void appliedDdlIsRecordedThroughTheRecordPath() throws Exception {
        Metrics.initialize("true", freePort());
        try {
            FakeClickHouse clickHouse = new FakeClickHouse();
            invokeProcess(singleThreadedCapture(clickHouse.connection()), ddlEvent(ADD_COLUMN),
                    new FlakyCommitter(0).proxy(), new Properties());

            assertNotNull(ddlCounter("false"), "the applied DDL must be recorded as fail=false");
            assertNull(ddlCounter("true"));
        } finally {
            Metrics.stop();
        }
    }

    @Test
    @DisplayName("SC-3b: a DDL that fails through the record path is recorded as a failure")
    public void failedDdlIsRecordedThroughTheRecordPath() throws Exception {
        Metrics.initialize("true", freePort());
        try {
            FakeClickHouse clickHouse = new FakeClickHouse();
            clickHouse.columnVisible = false;
            assertThrows(Exception.class,
                    () -> invokeProcess(singleThreadedCapture(clickHouse.connection()), ddlEvent(ADD_COLUMN),
                            new FlakyCommitter(0).proxy(), new Properties()));

            assertNotNull(ddlCounter("true"), "the failed DDL must be recorded as fail=true");
        } finally {
            Metrics.stop();
        }
    }
}
