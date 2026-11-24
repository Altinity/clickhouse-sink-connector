package com.altinity.clickhouse.debezium.embedded.cdc;

import io.debezium.embedded.Connect;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.relational.history.AbstractSchemaHistory;
import io.debezium.relational.history.HistoryRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Native-engine reproduction of a current schema over an older offset (spec 01.11). */
@EnabledIfEnvironmentVariable(named = "MYSQL_PREFIX", matches = ".+")
class RecoverySchemaReplayIT {
    @TempDir
    Path work;

    /** Persists actual engine history across sequential runs without a target database. */
    public static class FixtureHistory extends AbstractSchemaHistory {
        private static final List<HistoryRecord> RECORDS = new CopyOnWriteArrayList<>();

        @Override
        protected synchronized void storeRecord(HistoryRecord record) {
            RECORDS.add(record);
        }

        @Override
        protected void recoverRecords(Consumer<HistoryRecord> consumer) {
            List.copyOf(RECORDS).forEach(consumer);
        }

        @Override
        public boolean exists() {
            return !RECORDS.isEmpty();
        }

        @Override
        public boolean storageExists() {
            return true;
        }
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    void historicalResumeAndCurrentSchemaRecoveryAreNotEquivalent() throws Exception {
        Path prefix = Path.of(System.getenv("MYSQL_PREFIX"));
        Path server = prefix.resolve("bin/mysqld");
        assertTrue(Files.isExecutable(server), "sandbox must supply the MySQL module");
        Path data = Files.createDirectory(work.resolve("source"));
        Path initializeLog = work.resolve("initialize.log");
        Process initialize = new ProcessBuilder(server.toString(), "--no-defaults",
                "--initialize-insecure", "--basedir=" + prefix, "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(initializeLog.toFile()).start();
        assertTrue(initialize.waitFor(45, TimeUnit.SECONDS), "MySQL initialization timed out");
        assertEquals(0, initialize.exitValue(), () -> readLog(initializeLog));
        int port;
        try (ServerSocket allocation = new ServerSocket(0)) {
            port = allocation.getLocalPort();
        }
        Path serverLog = work.resolve("mysql.log");
        Process mysql = new ProcessBuilder(server.toString(), "--no-defaults",
                "--basedir=" + prefix, "--datadir=" + data, "--socket=", "--mysqlx=0",
                "--bind-address=127.0.0.1", "--port=" + port, "--server-id=1",
                "--secure-file-priv=NULL", "--pid-file=" + work.resolve("mysql.pid"),
                "--log-bin=" + work.resolve("binary"), "--binlog-format=ROW",
                "--binlog-row-image=FULL", "--innodb-buffer-pool-size=64M")
                .redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        try (AutoCloseable cleanup = () -> stopSource(mysql)) {
            String url = "jdbc:mysql://127.0.0.1:" + port
                    + "/?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectTimeout=500&socketTimeout=2000";
            Class.forName("com.mysql.cj.jdbc.Driver");
            await(Duration.ofSeconds(30), () -> {
                if (!mysql.isAlive()) {
                    fail("MySQL exited: " + readLog(serverLog));
                }
                try (Connection ignored = DriverManager.getConnection(url, "root", "")) {
                    return true;
                } catch (SQLException notReady) {
                    return false;
                }
            }, "MySQL readiness");
            try (Connection source = DriverManager.getConnection(url, "root", "");
                 Statement sql = source.createStatement()) {
                try (var version = sql.executeQuery("SELECT VERSION()")) {
                    assertTrue(version.next());
                    System.out.println("REPLAY PHASE source-ready: MySQL=" + version.getString(1)
                            + "; Debezium=" + io.debezium.connector.mysql.Module.version());
                }
                sql.execute("CREATE DATABASE replay");
                String columns = IntStream.rangeClosed(1, 42)
                        .mapToObj(n -> "c" + n + " VARCHAR(32)").collect(Collectors.joining(","));
                sql.execute("CREATE TABLE replay.t (id INT PRIMARY KEY," + columns + ")");
                sql.execute(insert(1, false));
                Path offset = work.resolve("seed-offset.dat");
                FixtureHistory.RECORDS.clear();
                try (Run seed = new Run(properties(port, offset, "initial"))) {
                    seed.awaitRow(1);
                    sql.execute(insert(2, false));
                    seed.awaitRow(2);
                }
                assertTrue(Files.size(offset) > 0, "streaming checkpoint must be persisted");
                List<HistoryRecord> seedHistory = List.copyOf(FixtureHistory.RECORDS);
                assertFalse(seedHistory.isEmpty(), "initial snapshot must persist schema history");
                Path controlOffset = Files.copy(offset, work.resolve("control-offset.dat"));
                Path recoveryOffset = Files.copy(offset, work.resolve("recovery-offset.dat"));
                System.out.println("REPLAY PHASE checkpoint-ready: 43-column schema, streamed id=2, offset-bytes="
                        + Files.size(offset) + ", history-records=" + seedHistory.size());

                sql.execute(insert(3, false));
                sql.execute("ALTER TABLE replay.t ADD COLUMN contra_quantity DECIMAL(30,10) NULL");
                sql.execute(insert(4, true));
                System.out.println("REPLAY PHASE source-altered: queued id=3 at width 43; id=4 at width 44");

                FixtureHistory.RECORDS.clear();
                FixtureHistory.RECORDS.addAll(seedHistory);
                try (Run control = new Run(properties(port, controlOffset, "no_data"))) {
                    Struct old = control.awaitRow(3);
                    Struct current = control.awaitRow(4);
                    assertNull(old.schema().field("contra_quantity"));
                    assertValues(old, 3);
                    assertValues(current, 4);
                    assertEquals("24.1250000000", current.getString("contra_quantity"));
                    assertNull(control.failure.get(), "normal historical replay must not fail");
                    System.out.println("REPLAY CONTROL PASS: id=3 width=" + old.schema().fields().size()
                            + "; id=4 width=" + current.schema().fields().size()
                            + "; all 42 text columns exact; contra_quantity=24.1250000000");
                }

                FixtureHistory.RECORDS.clear();
                FixtureHistory.RECORDS.addAll(seedHistory);
                Properties forced = properties(port,
                        Files.copy(offset, work.resolve("forced-offset.dat")), "recovery");
                try (Run nativeSnapshot = new Run(forced)) {
                    assertNativeMismatch(nativeSnapshot);
                }
                List<HistoryRecord> incorrectHistory = List.copyOf(FixtureHistory.RECORDS);

                FixtureHistory.RECORDS.clear();
                FixtureHistory.RECORDS.addAll(seedHistory);
                try (Run recovery = new Run(properties(port, recoveryOffset, "custom"))) {
                    Struct old = recovery.awaitRow(3);
                    Struct current = recovery.awaitRow(4);
                    assertNull(old.schema().field("contra_quantity"));
                    assertValues(old, 3);
                    assertValues(current, 4);
                    assertEquals("24.1250000000", current.getString("contra_quantity"));
                    assertNull(recovery.failure.get());
                    System.out.println("REPLAY FIX PASS: custom recovery retained width 43, replayed ALTER, then width 44;"
                            + "all source values exact including contra_quantity=24.1250000000");
                }
                for (String missingOrIncorrect : List.of("missing", "incorrect")) {
                    FixtureHistory.RECORDS.clear();
                    if (missingOrIncorrect.equals("incorrect")) {
                        FixtureHistory.RECORDS.addAll(incorrectHistory);
                    }
                    Path negativeOffset = Files.copy(offset, work.resolve(missingOrIncorrect + "-offset.dat"));
                    try (Run negative = new Run(properties(port, negativeOffset, "custom"))) {
                        assertNativeMismatch(negative);
                        System.out.println("REPLAY NEGATIVE PASS: " + missingOrIncorrect
                                + " history is not fabricated or padded into successful rows");
                    }
                }
            }
        }
    }

    private static void stopSource(Process mysql) throws InterruptedException {
        // DESTRUCTIVE: only the throwaway child started by this test, inside boxcar.
        mysql.destroy();
        try {
            if (!mysql.waitFor(30, TimeUnit.SECONDS)) {
                System.err.println("REPLAY CLEANUP: graceful shutdown exceeded 30s; terminating the sandbox child");
                mysql.destroyForcibly();
                assertTrue(mysql.waitFor(5, TimeUnit.SECONDS), "sandbox MySQL did not terminate");
            }
        } catch (InterruptedException interrupted) {
            mysql.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void assertNativeMismatch(Run replay) throws Exception {
        assertTrue(replay.completed.await(25, TimeUnit.SECONDS), "native width failure must execute");
        Throwable failure = replay.failure.get();
        assertNotNull(failure, "a current-schema snapshot cannot decode the historical row");
        String chain = causes(failure);
        assertTrue(chain.contains("internal schema size 44, but row size 43"), chain);
        assertTrue(replay.rows.isEmpty(), "the undecodable row must not be acknowledged");
        System.out.println("REPLAY NATIVE FAILURE CONFIRMED: " + chain);
    }

    private static Properties properties(int port, Path offset, String mode) {
        Properties props = new Properties();
        props.setProperty("name", "schema-replay");
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("database.hostname", "127.0.0.1");
        props.setProperty("database.port", Integer.toString(port));
        props.setProperty("database.user", "root");
        props.setProperty("database.ssl.mode", "disabled");
        props.setProperty("database.allowPublicKeyRetrieval", "true");
        props.setProperty("database.server.id", "123");
        props.setProperty("topic.prefix", "replay");
        props.setProperty("database.include.list", "replay");
        props.setProperty("table.include.list", "replay\\.t");
        props.setProperty("snapshot.mode", mode);
        if (mode.equals("custom")) {
            props.setProperty("snapshot.mode.custom.name", "history_preserving_recovery");
        }
        props.setProperty("decimal.handling.mode", "string");
        props.setProperty("schema.history.internal", FixtureHistory.class.getName());
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", offset.toString());
        props.setProperty("offset.flush.interval.ms", "0");
        props.setProperty("heartbeat.interval.ms", "100");
        props.setProperty("poll.interval.ms", "50");
        props.setProperty("connect.timeout.ms", "5000");
        return props;
    }

    private static String insert(int id, boolean extra) {
        String values = IntStream.rangeClosed(1, 42)
                .mapToObj(n -> "'row" + id + "-column" + n + "'").collect(Collectors.joining(","));
        return "INSERT INTO replay.t VALUES (" + id + "," + values
                + (extra ? ",24.1250000000" : "") + ")";
    }

    private static void assertValues(Struct row, int id) {
        assertEquals(id, row.getInt32("id"));
        IntStream.rangeClosed(1, 42).forEach(n ->
                assertEquals("row" + id + "-column" + n, row.getString("c" + n)));
    }

    private static String causes(Throwable failure) {
        List<String> chain = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.add(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return String.join(" -> ", chain);
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void await(Duration timeout, BooleanSupplier condition, String phase) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("timed out in " + phase);
    }

    private static final class Run implements AutoCloseable {
        final List<Struct> rows = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine;
        final Future<?> task;

        Run(Properties props) {
            engine = DebeziumEngine.create(Connect.class).using(props)
                    .notifying((List<ChangeEvent<SourceRecord, SourceRecord>> batch,
                                DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer) -> {
                        for (ChangeEvent<SourceRecord, SourceRecord> event : batch) {
                            if (event.value() != null && event.value().value() instanceof Struct value
                                    && value.schema().field("after") != null && value.getStruct("after") != null) {
                                rows.add(value.getStruct("after"));
                            }
                            committer.markProcessed(event);
                        }
                        committer.markBatchFinished();
                    })
                    .using((DebeziumEngine.CompletionCallback) (success, message, thrown) -> {
                        if (!success) {
                            failure.set(thrown == null ? new IllegalStateException(message) : thrown);
                        }
                        completed.countDown();
                    }).build();
            task = executor.submit(engine);
        }

        Struct awaitRow(int id) throws Exception {
            await(Duration.ofSeconds(25), () -> failure.get() != null
                    || rows.stream().anyMatch(row -> row.getInt32("id") == id), "replayed id=" + id);
            assertNull(failure.get(), () -> failure.get() == null ? "" : causes(failure.get()));
            return rows.stream().filter(row -> row.getInt32("id") == id).findFirst().orElseThrow();
        }

        @Override
        public void close() throws Exception {
            try {
                engine.close();
                task.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "engine executor did not terminate");
        }
    }
}
