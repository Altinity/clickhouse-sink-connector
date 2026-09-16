package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.ResultSet;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;

/**
 * End-to-end regression test for issue #1379, "Initial Snapshot never
 * finishes".
 *
 * <p><b>What actually went wrong.</b> Debezium marks a snapshot complete only
 * AFTER the last snapshot row is emitted, so every snapshot ROW still carries
 * {@code snapshot=INITIAL, snapshot_completed=false}. The completed state
 * rides exclusively on records emitted after the snapshot. On a source that
 * goes idle once the snapshot ends -- the normal case for the small database
 * someone first tries the connector on -- the only such records are
 * heartbeats.</p>
 *
 * <p>Committing the offset from those control records is what
 * {@code DebeziumChangeEventCapture#commitControlRecordOffset} was added to
 * do. But it can only act on a heartbeat that is actually emitted, and
 * Debezium's {@code heartbeat.interval.ms} defaults to 0, which disables
 * heartbeats entirely. The connector never set it, so on an idle source there
 * was nothing to fire on and the offset stayed at
 * {@code snapshot_completed=false} indefinitely.</p>
 *
 * <p><b>Why that is destructive, not cosmetic.</b> On restart Debezium reads
 * the persisted offset, sees a snapshot that never completed, and re-runs the
 * whole snapshot from scratch ({@code InitialSnapshotter#shouldSnapshotData}
 * keys off exactly this state). The connector can therefore never make
 * forward progress past its first snapshot, and the target is rewritten every
 * time the process restarts.</p>
 *
 * <p><b>What this test proves.</b> It is deliberately end-to-end rather than a
 * unit test, because every unit-level piece of this already passed while the
 * bug was live: the parse returns null for a heartbeat (correct), the commit
 * helper commits when handed a control record (correct), the quiescence check
 * gates it (correct). The defect was that no heartbeat ever arrived, which is
 * only observable by running a real connector against a real source and
 * reading the persisted offset.</p>
 *
 * <p>So: snapshot a small Postgres database, let it go idle, and assert the
 * PERSISTED offset flips to {@code snapshot_completed=true} without any
 * further writes to the source. Then restart the connector against that same
 * offset store and assert the snapshot is NOT re-run -- which is the failure
 * the reporter actually suffers.</p>
 */
public class PostgresSnapshotCompletionIT {

    /**
     * How long to wait for the post-snapshot heartbeat to be emitted and its
     * offset committed. The default interval is 5s; this allows several.
     */
    private static final long HEARTBEAT_WAIT_MS = 60_000L;

    /** Poll interval while waiting for the offset to flip. */
    private static final long POLL_MS = 2_000L;

    @Container
    public static ClickHouseContainer clickHouseContainer =
            new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_it.sql")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

    public static DockerImageName pgImage =
            DockerImageName.parse("debezium/postgres:15-alpine")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    public static PostgreSQLContainer postgreSQLContainer =
            (PostgreSQLContainer) new PostgreSQLContainer(pgImage)
                    .withInitScript("init_postgres.sql")
                    .withDatabaseName("public")
                    .withUsername("root")
                    .withPassword("root")
                    .withExposedPorts(5432)
                    .withCommand("postgres -c wal_level=logical")
                    .withNetworkAliases("postgres")
                    .withAccessToHost(true);

    public Properties getProperties() throws Exception {
        Properties properties =
                getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("table.include.list", "public.tm");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        // heartbeat.interval.ms is deliberately NOT set here. The connector
        // must supply a working default on its own -- that omission is the
        // bug. A test that set it would pass against the broken build.
        return properties;
    }

    @Test
    @DisplayName("#1379: the initial snapshot's completed state is committed, and a restart does not re-snapshot")
    public void testSnapshotCompletionIsPersistedAndSurvivesRestart() throws Exception {
        Network network = Network.newNetwork();
        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        Thread.sleep(10000);
        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            // Let the snapshot run to completion, then leave the source
            // ENTIRELY IDLE. No inserts, no updates: the whole point is that
            // nothing but a heartbeat can carry the completed state.
            Thread.sleep(45000);

            BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
            long rowsAfterFirstRun = countRows(writer, "public.tm");
            Assert.assertTrue("the snapshot should have copied the source rows",
                    rowsAfterFirstRun > 0);

            // THE ASSERTION THIS TEST EXISTS FOR. Against the broken build
            // this never becomes true, however long you wait, because no
            // heartbeat is ever emitted to carry the state.
            String offset = awaitSnapshotCompleted(writer);
            Assert.assertNotNull(
                    "the offset should record the snapshot as completed within "
                            + HEARTBEAT_WAIT_MS + "ms of the snapshot ending, but it "
                            + "still reads: " + readOffset(writer),
                    offset);
            Assert.assertTrue(
                    "snapshot_completed must be true in the persisted offset, got: " + offset,
                    offset.contains("\"snapshot_completed\":true"));

            // Stop the connector the way a restart would.
            if (engine.get() != null) {
                engine.get().stop();
            }
            Thread.sleep(10000);

            // Restart against the SAME offset store. A connector that reads a
            // completed snapshot resumes streaming; one that reads
            // snapshot_completed=false re-runs the snapshot and rewrites the
            // target. Row count is the observable difference.
            AtomicReference<DebeziumChangeEventCapture> restarted = new AtomicReference<>();
            ExecutorService restartExecutor = Executors.newFixedThreadPool(1);
            restartExecutor.execute(() -> {
                try {
                    restarted.set(new DebeziumChangeEventCapture());
                    restarted.get().setup(getProperties(), new SourceRecordParserService(), false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            try {
                Thread.sleep(45000);

                BaseDbWriter writerAfter = ITCommon.getDBWriter(clickHouseContainer);
                String offsetAfterRestart = readOffset(writerAfter);
                Assert.assertTrue(
                        "after a restart the snapshot must still be recorded as completed, "
                                + "otherwise the next restart re-snapshots again; offset: "
                                + offsetAfterRestart,
                        offsetAfterRestart.contains("\"snapshot_completed\":true"));

                // A re-run snapshot re-inserts every source row. The target is
                // a ReplacingMergeTree, so the duplicates may or may not have
                // collapsed yet -- assert on the un-collapsed count, which is
                // what actually grows when the snapshot repeats.
                long rowsAfterRestart = countRows(writerAfter, "public.tm");
                Assert.assertEquals(
                        "a restart must not re-run the initial snapshot; row count grew from "
                                + rowsAfterFirstRun + " to " + rowsAfterRestart
                                + ", which means the snapshot ran a second time",
                        rowsAfterFirstRun, rowsAfterRestart);
            } finally {
                if (restarted.get() != null) {
                    restarted.get().stop();
                }
                restartExecutor.shutdownNow();
            }
        } finally {
            if (engine.get() != null) {
                engine.get().stop();
            }
            executorService.shutdownNow();
        }
    }

    /**
     * Polls the persisted offset until it reports the snapshot as completed.
     *
     * @return the offset value once completed, or null if it never was.
     */
    private String awaitSnapshotCompleted(BaseDbWriter writer) throws Exception {
        long deadline = System.currentTimeMillis() + HEARTBEAT_WAIT_MS;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = readOffset(writer);
            if (last != null && last.contains("\"snapshot_completed\":true")) {
                return last;
            }
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    /** Reads the connector's persisted Debezium offset. */
    private String readOffset(BaseDbWriter writer) throws Exception {
        return new DebeziumOffsetStorage()
                .getDebeziumStorageStatusQuery(getProperties(), writer.getConnection());
    }

    /** Row count of a replicated table, without FINAL so re-snapshot duplicates show. */
    private long countRows(BaseDbWriter writer, String table) throws Exception {
        try (ResultSet rs = writer.getConnection()
                .prepareStatement("select count(*) from " + table).executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return -1L;
    }
}
