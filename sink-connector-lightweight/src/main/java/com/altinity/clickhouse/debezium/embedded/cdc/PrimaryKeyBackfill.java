package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.PrimaryKeyRebuildPlan;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.PrimaryKeyRebuildPlan.Provenance;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.altinity.clickhouse.debezium.embedded.cdc.PrimaryKeyRebuild.ColumnInfo;
import static com.altinity.clickhouse.debezium.embedded.cdc.PrimaryKeyRebuild.lit;
import static com.altinity.clickhouse.debezium.embedded.cdc.PrimaryKeyRebuild.likeLit;
import static com.altinity.clickhouse.debezium.embedded.cdc.PrimaryKeyRebuild.oldNameOf;
import static com.altinity.clickhouse.debezium.embedded.cdc.PrimaryKeyRebuild.q;
import static com.altinity.clickhouse.debezium.embedded.cdc.PrimaryKeyRebuild.qList;

/**
 * The backfill phase of a primary-key rebuild (Spec 06.09 §3.3.2): after
 * {@link PrimaryKeyRebuild#swap} exchanged the empty rebuilt table into place
 * inside the DDL barrier, this class copies the pre-DDL live rows from the
 * retired table {@code R} into the rebuilt {@code T} <b>online</b>, on the
 * connector's single {@code pk-rebuild-backfill} daemon thread and its own
 * ClickHouse connection, while replication of this and every other table
 * continues (Invariant I5).
 *
 * <p>Correct under {@code ReplacingMergeTree} because every copied row keeps
 * the {@code _version} it had before the DDL, below every post-DDL event's
 * version: a key the new table already holds keeps its newer row, tombstone
 * or relocation in {@code FINAL}; a key it does not hold yet receives its
 * current state. The copy is therefore idempotent and can be re-run after any
 * failure ({@code PkRebuild.lean}: {@code backfill_never_shadows_newer},
 * {@code backfill_idempotent}).</p>
 *
 * <p>Steps: 1. source key map ({@code SOURCE_VALUED} columns only, §3.4);
 * 2. {@code INSERT INTO T ... SELECT ... FROM R FINAL WHERE <live>} per
 * partition of {@code R}; 3. completeness check (every live key of {@code R}
 * present in {@code T}); 4. retire {@code R} and {@code K} unless
 * {@code disable.drop.truncate=true}; 5. any failure is logged at ERROR with
 * the step and statement, reported through the {@link FailureReporter} and
 * re-scheduled with exponential backoff (10 s doubling to 5 min),
 * indefinitely -- replication is never stopped by a backfill failure; 6. at
 * engine start {@link #resumePending} schedules again every backfill whose
 * retired table still exists; 7. a replicated statement that empties or
 * DESTRUCTIVE: the next lines only name the source statements that supersede a backfill; nothing is executed here.
 * removes the table (TRUNCATE TABLE / DROP TABLE, or DROP DATABASE) while a
 * backfill is pending {@link #cancelFor cancels} it -- a running copy stops
 * at its next statement boundary and is not re-scheduled, and the cancel
 * waits for that boundary -- and drops the retired copy and key map BEFORE
 * the statement runs, so no copy statement can ever resurrect rows the
 * source removed (§3.3.2 step 7); 8. backfills
 * of one table run in submission order (§3.3.2 step 8).</p>
 *
 * <p><b>Restart safety.</b> The swap phase records the task -- old key, new
 * key, renames, source-valued columns, source database -- as a marker line in
 * the retired table's comment ({@link #MARKER_PREFIX}) before the exchange, so
 * a resume never has to guess it from the column sets: the migration shape
 * {@code ADD COLUMN id ... AUTO_INCREMENT, ADD PRIMARY KEY (id)} leaves the
 * added column in {@code R} too (applied to {@code T} before the swap, with
 * its default), and a resume that copied it from {@code R} would silently
 * collapse every row onto the default key. A scratch table without the marker
 * is never resumed and never dropped here.</p>
 */
public final class PrimaryKeyBackfill {

    private static final Logger log = LogManager.getLogger(PrimaryKeyBackfill.class);

    /** First retry delay after a failed attempt (Spec 06.09 §3.3.2 step 5). */
    static final long INITIAL_BACKOFF_MS = 10_000L;

    /** Cap of the doubling retry delay (Spec 06.09 §3.3.2 step 5). */
    static final long MAX_BACKOFF_MS = 300_000L;

    /** Name of the single backfill thread (Spec 06.09 §3.3.1 step 5). */
    static final String THREAD_NAME = "pk-rebuild-backfill";

    /** Longest time {@link #shutdown()} waits for an in-flight attempt. */
    static final long SHUTDOWN_WAIT_MS = 3_000L;

    /**
     * Delay before a task re-checks whether an earlier-submitted task of the
     * same table is still pending (Spec 06.09 §3.3.2 step 8: one table's
     * backfills run in submission order, so a re-scheduled retry is never
     * overtaken by a later task of the same table).
     */
    static final long FIFO_RECHECK_MS = 10_000L;

    /**
     * How often a cancel waiting for the in-flight statement of a running
     * attempt logs that it is still waiting (Spec 06.09 §3.3.2 step 7). The
     * wait itself is not capped: the superseding TRUNCATE/DROP must run
     * after the copy statement, and the statement is bounded by ClickHouse's
     * own limits, not by this runner.
     */
    static final long CANCEL_WAIT_LOG_MS = 30_000L;

    /** Rows bound per {@code executeBatch} when filling the source key map. */
    static final int SOURCE_BATCH_SIZE = 10000;

    /** Prefix of the line in the retired table's comment that carries the pending task. */
    static final String MARKER_PREFIX = "csc-pk-rebuild:";

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Runs an action after a delay; the production scheduler is the backfill thread. */
    public interface Scheduler {
        void schedule(Runnable action, long delayMs);
    }

    /**
     * Receives every failed attempt (Spec 06.09 §3.3.2 step 5) so the owner can
     * record it in the connector's error table without this class depending on
     * its internals. {@code ch} is the attempt's own ClickHouse connection, or
     * {@code null} when none could be opened.
     */
    public interface FailureReporter {
        void failed(Connection ch, Task task, String step, String statement, Exception cause);
    }

    // ------------------------------------------------------------------
    // The task
    // ------------------------------------------------------------------

    /** One pending backfill: the retired table {@code R} to copy into the rebuilt {@code T}. */
    public static final class Task {
        private final String database;
        private final String table;
        private final String retired;
        private final String keyMapTable;
        private final PrimaryKeyRebuildPlan plan;
        private final String sourceDatabase;
        private final String deleteFlag;
        private final List<String> oldKey;
        private final List<String> newKey;
        private final Map<String, String> renames;
        private final List<String> sourceValued;
        private final boolean keylessFallback;
        private final long epochMs;
        private int failures;
        private boolean keyMapLoaded;
        /** Set by {@link PrimaryKeyBackfill#cancelFor}; read by the run loop between statements. */
        private volatile boolean cancelled;
        /**
         * The thread running the current attempt and the latch it releases when
         * that attempt has stopped, or null between attempts; a cancel waits on
         * the latch so the superseding statement runs after the in-flight copy
         * statement (Spec 06.09 §3.3.2 step 7).
         */
        private volatile Thread attemptThread;
        private volatile CountDownLatch attemptDone;
        /** Submission order within one runner (Spec 06.09 §3.3.2 step 8). */
        private long sequence;

        /**
         * @param database       destination database (clean).
         * @param table          the rebuilt table {@code T} (clean).
         * @param retired        the retired pre-rebuild table {@code R} (clean).
         * @param keyMapTable    the source key map {@code K} (clean); created only when {@code sourceValued} is non-empty.
         * @param plan           the translator's plan (a synthesised one on resume).
         * @param sourceDatabase the RAW source database, for the key-map read.
         * @param deleteFlag     the engine's delete-flag column, or {@code null}.
         * @param oldKey         the old key in the retired table's column spellings.
         * @param newKey         the new key in the rebuilt table's column spellings.
         * @param renames        old spelling -> new name of every renamed key column.
         * @param sourceValued   new-key columns read from the source (rebuilt table's spellings).
         * @param keylessFallback whether the new key is the keyless all-columns identity.
         * @param epochMs        the attempt's epoch, naming the scratch tables.
         */
        Task(String database, String table, String retired, String keyMapTable, PrimaryKeyRebuildPlan plan,
             String sourceDatabase, String deleteFlag, List<String> oldKey, List<String> newKey,
             Map<String, String> renames, List<String> sourceValued, boolean keylessFallback, long epochMs) {
            this.database = database;
            this.table = table;
            this.retired = retired;
            this.keyMapTable = keyMapTable;
            this.plan = plan;
            this.sourceDatabase = sourceDatabase;
            this.deleteFlag = deleteFlag;
            this.oldKey = Collections.unmodifiableList(new ArrayList<>(oldKey));
            this.newKey = Collections.unmodifiableList(new ArrayList<>(newKey));
            this.renames = Collections.unmodifiableMap(new LinkedHashMap<>(renames));
            this.sourceValued = Collections.unmodifiableList(new ArrayList<>(sourceValued));
            this.keylessFallback = keylessFallback;
            this.epochMs = epochMs;
        }

        public String database() {
            return database;
        }

        public String table() {
            return table;
        }

        public String retired() {
            return retired;
        }

        public String keyMapTable() {
            return keyMapTable;
        }

        public PrimaryKeyRebuildPlan plan() {
            return plan;
        }

        public String sourceDatabase() {
            return sourceDatabase;
        }

        public String deleteFlag() {
            return deleteFlag;
        }

        public List<String> oldKey() {
            return oldKey;
        }

        public List<String> newKey() {
            return newKey;
        }

        public Map<String, String> renames() {
            return renames;
        }

        public List<String> sourceValued() {
            return sourceValued;
        }

        public boolean keylessFallback() {
            return keylessFallback;
        }

        public long epochMs() {
            return epochMs;
        }

        /** Whether the copy needs the source key map (Spec 06.09 §3.4). */
        public boolean needKeyMap() {
            return !sourceValued.isEmpty();
        }

        /** Failed attempts so far. */
        public int failures() {
            return failures;
        }

        /**
         * DESTRUCTIVE: only reports a flag; the statements that set it are the source's own, replicated ones.
         * Whether a TRUNCATE TABLE / DROP TABLE of the table superseded this
         * backfill (Spec 06.09 §3.3.2 step 7): the run loop stops at its next
         * statement boundary and the task is not re-scheduled.
         */
        public boolean cancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
        }

        /**
         * Marks an attempt as running on the calling thread. Published BEFORE
         * the attempt reads {@link #cancelled()}, so a cancel that sees no
         * running attempt is guaranteed to be seen by the attempt's own check
         * (both fields are volatile), and a cancel that sees one waits for it.
         */
        void attemptStarted(CountDownLatch done) {
            attemptThread = Thread.currentThread();
            attemptDone = done;
        }

        /** Clears the running attempt and releases every cancel waiting for it. */
        void attemptFinished(CountDownLatch done) {
            attemptThread = null;
            attemptDone = null;
            done.countDown();
        }

        /** Identity of the pending work: one backfill per retired table. */
        String key() {
            return database + "." + retired;
        }

        /** Records a failure and returns the delay before the next attempt: 10 s, 20 s, ... capped at 5 min. */
        long nextBackoffMs() {
            failures++;
            long delay = INITIAL_BACKOFF_MS << Math.min(failures - 1, 16);
            return Math.min(delay, MAX_BACKOFF_MS);
        }

        /** The JSON the swap phase writes into the retired table's comment. */
        String markerJson() {
            Marker m = new Marker();
            m.table = table;
            m.keyMap = keyMapTable;
            m.oldKey = new ArrayList<>(oldKey);
            m.newKey = new ArrayList<>(newKey);
            m.renames = new LinkedHashMap<>(renames);
            m.sourceValued = new ArrayList<>(sourceValued);
            m.keyless = keylessFallback;
            m.sourceDatabase = sourceDatabase;
            m.epochMs = epochMs;
            m.sourceSql = plan == null ? null : plan.sourceSql();
            try {
                return JSON.writeValueAsString(m);
            } catch (Exception e) {
                throw new IllegalStateException("cannot serialise the backfill marker of " + this, e);
            }
        }

        @Override
        public String toString() {
            return "PrimaryKeyBackfill.Task{" + database + "." + table + " <- " + database + "." + retired
                    + (needKeyMap() ? " JOIN " + database + "." + keyMapTable + " (source-valued " + sourceValued
                    + " from `" + sourceDatabase + "`.`" + table + "`)" : "")
                    + ", oldKey=" + oldKey + ", newKey=" + newKey
                    + (renames.isEmpty() ? "" : ", renames=" + renames)
                    + (keylessFallback ? ", keyless all-columns fallback" : "")
                    + ", failures=" + failures + "}";
        }
    }

    /** The persisted form of a task (public fields for Jackson). */
    static final class Marker {
        public int v = 1;
        public String table;
        public String keyMap;
        public List<String> oldKey;
        public List<String> newKey;
        public Map<String, String> renames;
        public List<String> sourceValued;
        public boolean keyless;
        public String sourceDatabase;
        public long epochMs;
        public String sourceSql;

        /** The marker carried by a table comment, or {@code null} when the comment has none or it is unreadable. */
        static Marker parse(String comment) {
            String json = markerLine(comment);
            if (json == null) {
                return null;
            }
            try {
                Marker m = JSON.readValue(json, Marker.class);
                if (m.table == null || m.oldKey == null || m.newKey == null) {
                    log.error("Primary-key backfill marker [{}] lacks table/oldKey/newKey; ignored", json);
                    return null;
                }
                if (m.renames == null) {
                    m.renames = new LinkedHashMap<>();
                }
                if (m.sourceValued == null) {
                    m.sourceValued = new ArrayList<>();
                }
                return m;
            } catch (Exception e) {
                log.error("Primary-key backfill marker [{}] is not readable ({}); ignored", json, e.toString());
                return null;
            }
        }

        private static String markerLine(String comment) {
            if (comment == null) {
                return null;
            }
            for (String line : comment.split("\n")) {
                if (line.startsWith(MARKER_PREFIX)) {
                    return line.substring(MARKER_PREFIX.length());
                }
            }
            return null;
        }

        Task toTask(String database, String retired, String deleteFlag) {
            Map<String, Provenance> provenance = new LinkedHashMap<>();
            for (String column : newKey) {
                provenance.put(column, sourceValued.contains(column) ? Provenance.SOURCE_VALUED : Provenance.EXISTING);
            }
            PrimaryKeyRebuildPlan plan = new PrimaryKeyRebuildPlan(database, table, oldKey, newKey, keyless,
                    provenance, sourceSql == null ? "<resumed from " + database + "." + retired + ">" : sourceSql,
                    Collections.emptyList(), renames, Collections.emptyMap());
            String keyMapName = keyMap != null ? keyMap : table + "__pk_rebuild_keys_" + epochMs;
            return new Task(database, table, retired, keyMapName, plan, sourceDatabase, deleteFlag, oldKey, newKey,
                    renames, sourceValued, keyless, epochMs);
        }
    }

    /** {@code comment} with the marker line of {@code json} appended (an earlier marker replaced). */
    static String withMarker(String comment, String json) {
        String base = withoutMarker(comment);
        return (base.isEmpty() ? "" : base + "\n") + MARKER_PREFIX + json;
    }

    /** {@code comment} without its marker line. */
    static String withoutMarker(String comment) {
        if (comment == null || comment.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : comment.split("\n", -1)) {
            if (line.startsWith(MARKER_PREFIX)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    /** A failed step; {@code terminal} when retrying cannot help (the tables are gone). */
    static final class BackfillFailure extends RuntimeException {
        final String step;
        final String statement;
        final boolean terminal;

        BackfillFailure(String step, String statement, String detail, Throwable cause, boolean terminal) {
            super(detail, cause);
            this.step = step;
            this.statement = statement;
            this.terminal = terminal;
        }

        BackfillFailure(String step, String statement, String detail, Throwable cause) {
            this(step, statement, detail, cause, false);
        }
    }

    /**
     * Raised by the run loop at a statement boundary when the attempt must
     * stop without failing: the task was {@link Task#cancelled() cancelled}
     * (Spec 06.09 §3.3.2 step 7) or the runner is shutting down. Never
     * reported as a failure and never re-scheduled here.
     */
    static final class BackfillStopped extends RuntimeException {
        final String step;
        final boolean cancelled;

        BackfillStopped(String step, boolean cancelled, String detail) {
            super(detail);
            this.step = step;
            this.cancelled = cancelled;
        }
    }

    // ------------------------------------------------------------------
    // The runner
    // ------------------------------------------------------------------

    private final Supplier<Connection> clickHouse;
    private final Supplier<Connection> source;
    private final Properties props;
    /**
     * {@link PrimaryKeyRebuild#ON_CLUSTER} in replicated mode
     * ({@code auto.create.tables.replicated=true}), else empty: the retired
     * table exists on every host of the cluster, so its DDL runs there too
     * (Spec 06.09 §3.3.2 steps 4 and 7). The key map is a local helper of the
     * copying host and never carries it.
     */
    private final String onCluster;
    private final FailureReporter reporter;
    private final Scheduler scheduler;
    private final ScheduledExecutorService executor;
    /** Pending tasks by {@link Task#key()}: submitted and not yet complete, cancelled or terminally failed. */
    private final Map<String, Task> inFlight = new ConcurrentHashMap<>();
    /** The queued attempt of each pending task on the production executor, so a cancel can remove it. */
    private final Map<String, Future<?>> queued = new ConcurrentHashMap<>();
    private final AtomicLong submissions = new AtomicLong();
    private volatile boolean shutdown;

    /**
     * The production runner: its own single daemon thread {@link #THREAD_NAME}
     * and the read-only source connection of Spec 06.09 §3.4.
     *
     * @param clickHouse opens a NEW ClickHouse connection per attempt (never the writer's).
     * @param props      the connector properties ({@code disable.drop.truncate}, source credentials).
     * @param reporter   receives every failed attempt; may be {@code null}.
     */
    public PrimaryKeyBackfill(Supplier<Connection> clickHouse, Properties props, FailureReporter reporter) {
        this(clickHouse, PrimaryKeyRebuild.sourceConnectionSupplier(props), props, reporter, null);
    }

    /**
     * @param scheduler {@code null} for the runner's own thread; tests inject a
     *                  recording scheduler so no attempt sleeps.
     */
    PrimaryKeyBackfill(Supplier<Connection> clickHouse, Supplier<Connection> source, Properties props,
                       FailureReporter reporter, Scheduler scheduler) {
        this.clickHouse = clickHouse;
        this.source = source;
        this.props = props == null ? new Properties() : props;
        this.onCluster = PrimaryKeyRebuild.onCluster(this.props);
        this.reporter = reporter;
        if (scheduler != null) {
            this.executor = null;
            this.scheduler = scheduler;
        } else {
            ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, THREAD_NAME);
                t.setDaemon(true);
                return t;
            });
            pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            pool.setRemoveOnCancelPolicy(true);
            this.executor = pool;
            // Scheduled through the executor directly (schedule below) so the
            // queued attempt's Future is kept and a cancel can remove it.
            this.scheduler = null;
        }
    }

    /**
     * Schedules the backfill of {@code task} now. A task for a retired table
     * that is already pending in this runner is not scheduled twice.
     *
     * @return whether the task was accepted.
     */
    public boolean submit(Task task) {
        if (shutdown) {
            log.warn("Primary-key backfill of {}: the runner is shut down; the backfill resumes at the next "
                    + "engine start from the retired table (Spec 06.09 §3.3.2 step 6)", task);
            return false;
        }
        if (inFlight.putIfAbsent(task.key(), task) != null) {
            log.info("Primary-key backfill of {}.{} from {}.{} is already pending in this process; not scheduled "
                    + "twice", task.database(), task.table(), task.database(), task.retired());
            return false;
        }
        task.sequence = submissions.incrementAndGet();
        log.info("Primary-key backfill scheduled on the {} thread: {} (Spec 06.09 §3.3.1 step 5)", THREAD_NAME, task);
        return schedule(task, 0L);
    }

    /** {@link #submit}s every task; returns how many were accepted. */
    public int submitAll(Collection<Task> tasks) {
        int accepted = 0;
        for (Task task : tasks) {
            if (submit(task)) {
                accepted++;
            }
        }
        return accepted;
    }

    /** Retired tables whose backfill is pending in this runner. */
    public int pending() {
        return inFlight.size();
    }

    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Stops the runner: an in-flight attempt is interrupted and waited for at
     * most {@link #SHUTDOWN_WAIT_MS}; whatever is still pending resumes at the
     * next engine start from the retired tables themselves.
     */
    public void shutdown() {
        shutdown = true;
        if (executor == null) {
            return;
        }
        List<Runnable> dropped = executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("Primary-key backfill thread did not stop within {} ms; it is a daemon thread and the "
                        + "interrupted copy re-runs at the next engine start (idempotent)", SHUTDOWN_WAIT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!dropped.isEmpty() || !inFlight.isEmpty()) {
            log.warn("Primary-key backfill runner shut down with {} backfill(s) pending; they resume at the next "
                    + "engine start (Spec 06.09 §3.3.2 step 6)", inFlight.size());
        }
    }

    private boolean schedule(Task task, long delayMs) {
        try {
            if (executor != null) {
                queued.put(task.key(), executor.schedule(() -> attempt(task), delayMs, TimeUnit.MILLISECONDS));
            } else {
                scheduler.schedule(() -> attempt(task), delayMs);
            }
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.remove(task.key());
            log.warn("Primary-key backfill of {} could not be scheduled ({}); it resumes at the next engine start",
                    task, e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Superseding DDL (Spec 06.09 §3.3.2 step 7)
    // ------------------------------------------------------------------

    /**
     * DESTRUCTIVE: drops the connector-owned retired copy (<table>__pk_rebuild_<ts> / __pk_retired_<ts>) and key map
     * DESTRUCTIVE: of a pending rebuild of ONE table, because the source's own replicated TRUNCATE TABLE / DROP TABLE
     * DESTRUCTIVE: of that table just made every pre-DDL row obsolete; never a mirrored table, never source data.
     * Cancels the pending or running backfill(s) of {@code database.table}
     * because a replicated TRUNCATE TABLE / DROP TABLE of that table is about
     * to run (or is suppressed by {@code disable.drop.truncate}, in which case
     * the operator keeps the rows already in the table, not the retired ones):
     * every queued task of the table is removed, a running one stops at its
     * next statement boundary and is not re-scheduled -- this method waits
     * for that boundary, so the statement about to run never overtakes the
     * in-flight copy statement -- and on {@code ch} --
     * the caller's connection, so the drops precede the statement -- the
     * retired table(s) and key map(s) are dropped, including any marker-bearing
     * scratch table of that table found in {@code system.tables} (a pending
     * copy of a previous run that was not resumed in this process). A failed
     * drop escapes to the caller: the statement must not run before the
     * retired rows are gone (Spec 06.09 §3.3.2 step 7).
     *
     * @return the number of tasks cancelled in this runner.
     */
    public int cancelFor(Connection ch, String database, String table) {
        return cancel(ch, database, table);
    }

    /**
     * DESTRUCTIVE: as {@link #cancelFor}, for every table of one destination database, because the source's own
     * DESTRUCTIVE: replicated DROP DATABASE is about to remove them all; only rebuild scratch tables are dropped here.
     * {@link #cancelFor} for every table of {@code database} (a replicated
     * DROP DATABASE).
     */
    public int cancelAllFor(Connection ch, String database) {
        return cancel(ch, database, null);
    }

    private int cancel(Connection ch, String database, String table) {
        List<Task> victims = new ArrayList<>();
        for (Task task : inFlight.values()) {
            if (task.database().equals(database) && (table == null || task.table().equalsIgnoreCase(table))) {
                victims.add(task);
            }
        }
        for (Task task : victims) {
            task.cancel();
            inFlight.remove(task.key());
            Future<?> attempt = queued.remove(task.key());
            if (attempt != null) {
                attempt.cancel(false);
            }
            // DESTRUCTIVE: a log line naming the source statement that superseded the backfill; nothing runs here.
            log.warn("Primary-key backfill of {}.{} from {}.{} cancelled: superseded by TRUNCATE/DROP TABLE of the table "
                    + "(Spec 06.09 §3.3.2 step 7); a running copy stops at its next statement and is not re-scheduled, "
                    + "and the superseding statement waits for it", task.database(), task.table(), task.database(),
                    task.retired());
        }
        for (Task task : victims) {
            awaitRunningAttempt(task);
        }
        Set<String> dropped = new LinkedHashSet<>();
        for (Task task : victims) {
            dropScratch(ch, database, task.retired(), dropped, "the retired pre-rebuild copy", onCluster);
            if (task.needKeyMap()) {
                dropScratch(ch, database, task.keyMapTable(), dropped, "the source key map", "");
            }
        }
        dropMarkedScratchTables(ch, database, table, dropped, onCluster);
        // DESTRUCTIVE: nothing more is dropped below; the setting is only named in the log line.
        if (PrimaryKeyRebuild.dropTruncateDisabled(props) && !dropped.isEmpty()) {
            log.warn("Primary-key backfill: {} dropped although {}=true: the superseding statement leaves the operator "
                    + "the rows already in the table, not the retired pre-DDL ones (Spec 06.09 §3.3.2 step 7)",
                    dropped, SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE);
        }
        return victims.size();
    }

    /**
     * Blocks until the running attempt of a cancelled {@code task}, if any,
     * has stopped -- i.e. its in-flight statement has returned and its next
     * checkpoint threw -- so the caller's TRUNCATE/DROP runs AFTER the last
     * copy statement (Spec 06.09 §3.3.2 step 7). ClickHouse does not order
     * them itself: {@code TRUNCATE TABLE} of a MergeTree table takes no
     * exclusive table lock, so a truncate issued while the copy's
     * {@code INSERT ... SELECT} is executing returns at once and the copied
     * rows land after it. Uncapped by design (the statement is bounded by
     * ClickHouse's own limits); logs every {@link #CANCEL_WAIT_LOG_MS}. A
     * cancel issued from the attempt's own thread cannot wait for itself and
     * does not. An interrupt while waiting fails loudly: the superseding
     * statement must not run ahead of the copy.
     */
    private static void awaitRunningAttempt(Task task) {
        CountDownLatch done = task.attemptDone;
        Thread runner = task.attemptThread;
        if (done == null || runner == Thread.currentThread()) {
            return;
        }
        long waitedMs = 0;
        try {
            while (!done.await(CANCEL_WAIT_LOG_MS, TimeUnit.MILLISECONDS)) {
                waitedMs += CANCEL_WAIT_LOG_MS;
                log.warn("Primary-key backfill of {}.{} from {}.{}: the superseding TRUNCATE/DROP has waited {} s for "
                                + "the in-flight copy statement of the cancelled backfill to return; it runs after it "
                                + "(Spec 06.09 §3.3.2 step 7)", task.database(), task.table(), task.database(),
                        task.retired(), waitedMs / 1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackfillFailure("cancel (wait for the in-flight copy statement)", null, "interrupted while "
                    + "waiting for the in-flight copy statement of " + task.database() + "." + task.table() + " from "
                    + task.database() + "." + task.retired() + "; the superseding statement must not run before it "
                    + "(Spec 06.09 §3.3.2 step 7)", e);
        }
    }

    /**
     * Drops one rebuild scratch table of the cancelled backfill(s), once;
     * {@code onCluster} is the retired copy's clause in replicated mode and
     * empty for the local key map.
     */
    private static void dropScratch(Connection ch, String database, String name, Set<String> dropped, String what,
                                    String onCluster) {
        if (!dropped.add(name)) {
            return;
        }
        // DESTRUCTIVE: drops a connector-owned rebuild scratch table (the
        // retired copy or key map of ONE cancelled backfill, named by the
        // task or by its marker) because the source's replicated
        // TRUNCATE/DROP of the table made its rows obsolete; never a
        // mirrored table. IF EXISTS makes a repeat a no-op.
        exec(ch, "DROP TABLE IF EXISTS " + q(database) + "." + q(name) + onCluster, "cancel (drop " + what + ")", null);
    }

    /**
     * Drops every marker-bearing {@code <table>__pk_rebuild_%} /
     * {@code <table>__pk_retired_%} table of {@code database} (every table
     * when {@code table} is null) and its key map: a pending copy of a
     * previous run that this process did not resume is superseded too.
     */
    private static void dropMarkedScratchTables(Connection ch, String database, String table, Set<String> dropped,
                                                String onCluster) {
        String prefix = table == null ? "%" : likeLit(table);
        String scan = "SELECT name, comment FROM system.tables WHERE database = '" + lit(database) + "' AND (name LIKE '"
                + prefix + likeLit("__pk_rebuild_") + "%' OR name LIKE '" + prefix + likeLit("__pk_retired_")
                + "%') AND name NOT LIKE '%" + likeLit("__pk_rebuild_keys_") + "%' ORDER BY name";
        List<String[]> scratch;
        try {
            scratch = rows(ch, scan, 2);
        } catch (Exception e) {
            throw new BackfillFailure("cancel (find pending retired copies)", scan, e.getMessage(), e);
        }
        for (String[] row : scratch) {
            Marker marker = Marker.parse(row[1]);
            if (marker == null || (table != null && !marker.table.equalsIgnoreCase(table))) {
                continue;
            }
            dropScratch(ch, database, row[0], dropped, "the retired pre-rebuild copy of a previous run", onCluster);
            if (!marker.sourceValued.isEmpty()) {
                // Only a source-valued rebuild ever created its key map.
                String keyMap = marker.keyMap != null ? marker.keyMap
                        : marker.table + "__pk_rebuild_keys_" + marker.epochMs;
                dropScratch(ch, database, keyMap, dropped, "the source key map of a previous run", "");
            }
        }
    }

    /** The earliest-submitted other pending task of the same table, or null (Spec 06.09 §3.3.2 step 8). */
    private Task earlierPendingOfSameTable(Task task) {
        Task earliest = null;
        for (Task other : inFlight.values()) {
            if (other != task && other.database().equals(task.database()) && other.table().equalsIgnoreCase(task.table())
                    && other.sequence < task.sequence && (earliest == null || other.sequence < earliest.sequence)) {
                earliest = other;
            }
        }
        return earliest;
    }

    /**
     * One attempt: open a connection, {@link #run}, and on failure report and
     * re-schedule with backoff (Spec 06.09 §3.3.2 step 5). Package-private so
     * tests can drive attempts directly.
     */
    void attempt(Task task) {
        // Published before the cancelled check below: a cancel that does not
        // see this attempt is then seen by the check, and one that does waits
        // for attemptFinished (Spec 06.09 §3.3.2 step 7).
        CountDownLatch done = new CountDownLatch(1);
        task.attemptStarted(done);
        try {
            attemptOnce(task);
        } finally {
            task.attemptFinished(done);
        }
    }

    private void attemptOnce(Task task) {
        queued.remove(task.key());
        if (task.cancelled()) {
            // DESTRUCTIVE: a log line naming the source statement that superseded the backfill; nothing runs here.
            log.info("Primary-key backfill of {}.{} from {}.{} not started: cancelled: superseded by TRUNCATE/DROP TABLE "
                    + "(Spec 06.09 §3.3.2 step 7)", task.database(), task.table(), task.database(), task.retired());
            return;
        }
        Task earlier = earlierPendingOfSameTable(task);
        if (earlier != null) {
            log.info("Primary-key backfill of {}.{} from {}.{} waits for the earlier backfill from {}.{} of the same "
                            + "table (submission order, Spec 06.09 §3.3.2 step 8); re-checked in {} s",
                    task.database(), task.table(), task.database(), task.retired(), earlier.database(),
                    earlier.retired(), FIFO_RECHECK_MS / 1000);
            schedule(task, FIFO_RECHECK_MS);
            return;
        }
        Connection ch = null;
        try {
            try {
                ch = clickHouse.get();
            } catch (RuntimeException e) {
                throw new BackfillFailure("connect", null, "could not open the backfill's ClickHouse connection: "
                        + e.getMessage(), e);
            }
            if (ch == null) {
                throw new BackfillFailure("connect", null, "could not open the backfill's ClickHouse connection",
                        null);
            }
            run(task, ch);
            inFlight.remove(task.key());
        } catch (BackfillStopped stopped) {
            inFlight.remove(task.key());
            if (stopped.cancelled) {
                // DESTRUCTIVE: a log line naming the source statement that superseded the backfill; nothing runs here.
                log.warn("Primary-key backfill of {}.{} from {}.{} stopped at {}: cancelled: superseded by TRUNCATE/DROP "
                                + "TABLE (Spec 06.09 §3.3.2 step 7); not re-scheduled. {}", task.database(), task.table(),
                        task.database(), task.retired(), stopped.step, stopped.getMessage());
            } else {
                log.warn("Primary-key backfill of {}.{} from {}.{} stopped at {}: {}", task.database(), task.table(),
                        task.database(), task.retired(), stopped.step, stopped.getMessage());
            }
        } catch (Exception e) {
            if (interrupted(e)) {
                inFlight.remove(task.key());
                log.warn("Primary-key backfill of {} interrupted by shutdown; it resumes at the next engine start "
                        + "from the retired table (the copy is idempotent)", task);
                Thread.currentThread().interrupt();
                return;
            }
            String step = e instanceof BackfillFailure ? ((BackfillFailure) e).step : "unexpected";
            String statement = e instanceof BackfillFailure ? ((BackfillFailure) e).statement : null;
            boolean terminal = e instanceof BackfillFailure && ((BackfillFailure) e).terminal;
            log.error("Primary-key backfill of {}.{} from {}.{} FAILED at {}{}: {}{}", task.database(), task.table(),
                    task.database(), task.retired(), step, statement == null ? "" : " executing [" + statement + "]",
                    e.getMessage(), terminal ? " -- NOT retried; manual action required" : "", e);
            report(ch, task, step, statement, e);
            if (terminal) {
                inFlight.remove(task.key());
                return;
            }
            if (shutdown) {
                inFlight.remove(task.key());
                log.warn("Primary-key backfill of {} not re-scheduled: the runner is shut down; it resumes at the "
                        + "next engine start", task);
                return;
            }
            long delay = task.nextBackoffMs();
            log.error("Primary-key backfill of {}.{} from {}.{}: attempt {} failed; retrying in {} s (backoff 10 s "
                            + "doubling to {} s, indefinitely). Replication continues; the retired table {}.{} holds "
                            + "the pre-DDL rows until the copy completes (Spec 06.09 §3.3.2 step 5)",
                    task.database(), task.table(), task.database(), task.retired(), task.failures(), delay / 1000,
                    MAX_BACKOFF_MS / 1000, task.database(), task.retired());
            schedule(task, delay);
        } finally {
            if (ch != null) {
                try {
                    ch.close();
                } catch (Exception e) {
                    log.warn("Primary-key backfill: could not close the ClickHouse connection ({})", e.toString());
                }
            }
        }
    }

    private static boolean interrupted(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private void report(Connection ch, Task task, String step, String statement, Exception cause) {
        if (reporter == null) {
            return;
        }
        try {
            reporter.failed(ch, task, step, statement, cause);
        } catch (Exception e) {
            log.error("Primary-key backfill of {}: the failure could not be recorded ({})", task, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // The protocol (Spec 06.09 §3.3.2 steps 1-4)
    // ------------------------------------------------------------------

    /**
     * Runs steps 1-4 once on {@code ch}. Throws {@link BackfillFailure} (or any
     * runtime exception) on failure; the caller retries.
     */
    void run(Task task, Connection ch) {
        final String db = task.database();
        final String table = task.table();
        final String retired = task.retired();
        final String keyMap = task.keyMapTable();
        log.info("Primary-key backfill of {}.{} from {}.{}: attempt {} (Spec 06.09 §3.3.2): {}", db, table, db,
                retired, task.failures() + 1, task);

        checkpoint(task, "step 0 (columns of the retired table)");
        List<ColumnInfo> retiredColumns = columns(ch, db, retired, "step 0 (columns of the retired table)", task);
        if (retiredColumns.isEmpty()) {
            checkpoint(task, "step 0 (columns of the retired table)");
            // DESTRUCTIVE: the message only names the source statements that may have removed the retired copy.
            throw new BackfillFailure("step 0 (columns of the retired table)", null, "the retired table " + db + "."
                    + retired + " no longer exists, so there is nothing to backfill into " + db + "." + table
                    + "; a replicated TRUNCATE/DROP TABLE of the table supersedes the backfill and removes the copy "
                    + "(Spec 06.09 §3.3.2 step 7); if it was dropped by hand before the copy completed, the pre-DDL rows "
                    + "are lost and the table must be re-snapshotted", null, true);
        }
        List<ColumnInfo> targetColumns = columns(ch, db, table, "step 0 (columns of the rebuilt table)", task);
        if (targetColumns.isEmpty()) {
            throw new BackfillFailure("step 0 (columns of the rebuilt table)", null, "the rebuilt table " + db + "."
                    + table + " no longer exists; the retired table " + db + "." + retired + " is left in place",
                    null, true);
        }
        Map<String, String> retiredTypes = new LinkedHashMap<>();
        for (ColumnInfo c : retiredColumns) {
            retiredTypes.put(c.name, c.type);
        }
        Map<String, String> targetTypes = new LinkedHashMap<>();
        for (ColumnInfo c : targetColumns) {
            targetTypes.put(c.name, c.type);
        }

        // ---- Step 1: source key map (SOURCE_VALUED columns only, §3.4). ----
        if (task.needKeyMap()) {
            checkpoint(task, "step 1 (source key map)");
            if (task.keyMapLoaded) {
                log.info("Primary-key backfill of {}.{}: source key map {}.{} was filled by an earlier attempt of this "
                        + "process and is reused", db, table, db, keyMap);
            } else {
                String existing = scalar(ch, "SELECT name FROM system.tables WHERE database = '" + lit(db)
                        + "' AND name = '" + lit(keyMap) + "'", "step 1 (find the source key map)", task);
                if (existing != null) {
                    log.info("Primary-key backfill of {}.{}: source key map {}.{} exists from an interrupted attempt "
                            + "and may be incomplete; it is re-created and re-read from the source", db, table, db,
                            keyMap);
                    // DESTRUCTIVE: drops the connector-owned scratch key map of
                    // an interrupted attempt for this one table (name
                    // <table>__pk_rebuild_keys_<ts>); a partially filled map
                    // would silently drop rows from the JOIN. Re-created below.
                    exec(ch, "DROP TABLE IF EXISTS " + q(db) + "." + q(keyMap), "step 1 (re-create the source key map)",
                            task);
                }
                StringBuilder createKeys = new StringBuilder("CREATE TABLE ").append(q(db)).append('.')
                        .append(q(keyMap)).append(" (");
                boolean first = true;
                for (String column : task.oldKey()) {
                    String type = retiredTypes.get(column);
                    if (type == null) {
                        throw new BackfillFailure("step 1 (create the source key map)", null, "old-key column "
                                + column + " is not a column of the retired table " + db + "." + retired, null);
                    }
                    createKeys.append(first ? "" : ", ").append(q(column)).append(' ').append(type);
                    first = false;
                }
                for (String column : task.sourceValued()) {
                    String type = targetTypes.get(column);
                    if (type == null) {
                        throw new BackfillFailure("step 1 (create the source key map)", null, "source-valued column "
                                + column + " is not a column of the rebuilt table " + db + "." + table, null);
                    }
                    // Typed as in the rebuilt table: the key column is non-Nullable there.
                    createKeys.append(", ").append(q(column)).append(' ')
                            .append(PrimaryKeyRebuild.withoutNullable(type));
                }
                createKeys.append(") ENGINE = MergeTree ORDER BY (").append(qList(task.oldKey())).append(')');
                exec(ch, createKeys.toString(), "step 1 (create the source key map)", task);

                Connection sourceConn;
                try {
                    sourceConn = source.get();
                } catch (RuntimeException e) {
                    throw new BackfillFailure("step 1 (connect to the source)", null, e.getMessage(), e);
                }
                try {
                    long rows = loadSourceKeyMap(sourceConn, task.sourceDatabase(), table, task.oldKey(),
                            task.sourceValued(), ch, db, keyMap, task);
                    log.info("Primary-key backfill of {}.{}: source key map {}.{} holds {} rows read from `{}`.`{}`",
                            db, table, db, keyMap, rows, task.sourceDatabase(), table);
                } finally {
                    try {
                        sourceConn.close();
                    } catch (Exception e) {
                        log.warn("Primary-key backfill of {}.{}: could not close the source connection ({})", db,
                                table, e.toString());
                    }
                }
                task.keyMapLoaded = true;
            }
        }

        // ---- Step 2: copy the live rows, versions preserved, per partition of R. ----
        List<String> copyColumns = new ArrayList<>();
        for (ColumnInfo c : targetColumns) {
            if (c.defaultKind.equalsIgnoreCase("ALIAS") || c.defaultKind.equalsIgnoreCase("MATERIALIZED")) {
                continue;
            }
            if (task.sourceValued().contains(c.name) || retiredTypes.containsKey(oldNameOf(c.name, task.renames()))) {
                copyColumns.add(c.name);
            } else {
                log.info("Primary-key backfill of {}.{}: column {} exists on the rebuilt table but not on {}.{} (added "
                        + "after the swap); the copied rows take its default", db, table, c.name, db, retired);
            }
        }
        if (copyColumns.isEmpty()) {
            throw new BackfillFailure("step 2 (columns to copy)", null, "no column of " + db + "." + table
                    + " can be read from " + db + "." + retired, null);
        }
        List<String> partitions = column(ch, "SELECT DISTINCT partition_id FROM system.parts WHERE database = '"
                + lit(db) + "' AND table = '" + lit(retired) + "' AND active ORDER BY partition_id",
                "step 2 (partitions of the retired table)", task);
        if (partitions.isEmpty() || (partitions.size() == 1 && "all".equals(partitions.get(0)))) {
            partitions = Collections.singletonList(null);
        }
        long total = 0;
        for (String partition : partitions) {
            String select = copySelect(db, retired, keyMap, copyColumns, task.oldKey(), task.sourceValued(),
                    task.deleteFlag(), task.needKeyMap(), task.renames(), partition);
            String insert = "INSERT INTO " + q(db) + "." + q(table) + " (" + qList(copyColumns) + ") " + select;
            String step = partition == null ? "step 2 (copy the live rows)"
                    : "step 2 (copy the live rows of partition " + partition + ")";
            checkpoint(task, step);
            exec(ch, insert, step, task);
            checkpoint(task, step + " row count");
            // I14-scan-allowed: Spec 06.09 section 3.3.2 count reconciliation of a primary-key rebuild,
            // run on the backfill thread (never the event thread) and cancellable.
            long rows = count(ch, "SELECT count() FROM (" + select + ")", step + " row count", task);
            total += rows;
            log.info("Primary-key backfill of {}.{}: copied {} live rows{} from {}.{}", db, table, rows,
                    partition == null ? "" : " of partition " + partition, db, retired);
        }
        log.info("Primary-key backfill of {}.{}: {} live rows copied from {}.{} in {} statement(s)", db, table, total,
                db, retired, partitions.size());

        // ---- Step 3: completeness check -- every live key of R is present in T. ----
        checkpoint(task, "step 3 (completeness check)");
        String check = completenessCheck(task, retiredTypes, targetTypes);
        long missing = count(ch, check, "step 3 (completeness check)", task);
        if (missing != 0) {
            throw new BackfillFailure("step 3 (completeness check)", check, missing + " live key(s) of " + db + "."
                    + retired + " are missing from " + db + "." + table + " after the copy; " + db + "." + retired
                    + (task.needKeyMap() ? " and " + db + "." + keyMap + " are" : " is") + " kept and the backfill is "
                    + "retried", null);
        }
        log.info("Primary-key backfill of {}.{}: completeness check passed (0 live keys of {}.{} missing)", db, table,
                db, retired);

        // ---- Step 4: retire. ----
        checkpoint(task, "step 4 (retire)");
        if (PrimaryKeyRebuild.dropTruncateDisabled(props)) {
            // DESTRUCTIVE: nothing is dropped on this branch; the setting name is only logged.
            log.warn("Primary-key backfill of {}.{} complete: {}=true, so the retired pre-rebuild copy {}.{}{} kept; "
                            + "drop them once the rebuilt table is verified",
                    db, table, SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, db, retired,
                    task.needKeyMap() ? " and the source key map " + db + "." + keyMap + " are" : " is");
            // The marker is what makes a retired table resumable; without it a
            // kept copy is never copied again at the next start.
            String comment = scalar(ch, "SELECT comment FROM system.tables WHERE database = '" + lit(db)
                    + "' AND name = '" + lit(retired) + "'", "step 4 (comment of the retired table)", task);
            exec(ch, "ALTER TABLE " + q(db) + "." + q(retired) + onCluster + " MODIFY COMMENT '"
                    + lit(withoutMarker(comment)) + "'", "step 4 (mark the kept retired table complete)", task);
        } else {
            // DESTRUCTIVE: drops the pre-rebuild copy of this one table, whose
            // live rows were copied into the rebuilt table at step 2 and proven
            // present by key at step 3 -- as MySQL drops the original after its
            // rebuild. Bounded to this attempt's retired name; disable.drop.truncate=true keeps it.
            exec(ch, "DROP TABLE IF EXISTS " + q(db) + "." + q(retired) + onCluster, "step 4 (drop the retired copy)",
                    task);
            if (task.needKeyMap()) {
                // DESTRUCTIVE: drops the connector-owned source key map of this
                // attempt (scratch, never source data).
                exec(ch, "DROP TABLE IF EXISTS " + q(db) + "." + q(keyMap), "step 4 (drop the source key map)", task);
            }
        }
        log.info("Primary-key backfill of {}.{} from {}.{} COMPLETE: sorting key ({}) (Spec 06.09 §3.3.2)", db, table,
                db, retired, String.join(", ", task.newKey()));
    }

    /**
     * A statement boundary of the run loop: stops the attempt when the task
     * DESTRUCTIVE: the next line only names the source statements that cancel a backfill; nothing is executed here.
     * was cancelled by a superseding TRUNCATE/DROP TABLE (Spec 06.09 §3.3.2
     * step 7) or the runner is shutting down. A statement already sent to
     * ClickHouse completes; the next one is never issued.
     */
    private void checkpoint(Task task, String step) {
        if (task.cancelled()) {
            throw new BackfillStopped(step, true, "the retired copy " + task.database() + "." + task.retired()
                    + " is dropped by the cancel and the rows the source removed are never copied again");
        }
        if (shutdown) {
            throw new BackfillStopped(step, false, "interrupted by shutdown; it resumes at the next engine start "
                    + "from the retired table (the copy is idempotent, Spec 06.09 §3.3.2 step 6)");
        }
    }

    /**
     * The SELECT feeding the copy (Spec 06.09 §3.3.2 step 2). {@code columns}
     * are the rebuilt table's columns; a renamed old-key column is read from
     * the retired table as {@code o.<old> AS <new>}, a source-valued one from
     * the key map; {@code partitionId} restricts it to one partition of
     * {@code R} when non-null.
     */
    static String copySelect(String db, String retired, String keyMapTable, List<String> columns,
                             List<String> oldKey, List<String> sourceValued, String deleteFlag,
                             boolean needKeyMap, Map<String, String> renames, String partitionId) {
        StringBuilder sb = new StringBuilder("SELECT ");
        List<String> where = new ArrayList<>();
        if (!needKeyMap && renames.isEmpty()) {
            sb.append(qList(columns)).append(" FROM ").append(q(db)).append('.').append(q(retired)).append(" FINAL");
            if (deleteFlag != null) {
                where.add(q(deleteFlag) + " = 0");
            }
            if (partitionId != null) {
                where.add("_partition_id = '" + lit(partitionId) + "'");
            }
            if (!where.isEmpty()) {
                sb.append(" WHERE ").append(String.join(" AND ", where));
            }
            return sb.toString();
        }
        boolean first = true;
        for (String column : columns) {
            sb.append(first ? "" : ", ");
            String oldName = oldNameOf(column, renames);
            if (sourceValued.contains(column)) {
                sb.append("k.").append(q(column));
            } else if (!oldName.equals(column)) {
                sb.append("o.").append(q(oldName)).append(" AS ").append(q(column));
            } else {
                sb.append("o.").append(q(column));
            }
            first = false;
        }
        sb.append(" FROM ").append(q(db)).append('.').append(q(retired)).append(" AS o FINAL");
        if (needKeyMap) {
            sb.append(" INNER JOIN ").append(q(db)).append('.').append(q(keyMapTable)).append(" AS k ON ");
            first = true;
            for (String column : oldKey) {
                sb.append(first ? "" : " AND ").append("o.").append(q(column)).append(" = k.").append(q(column));
                first = false;
            }
        }
        if (deleteFlag != null) {
            where.add("o." + q(deleteFlag) + " = 0");
        }
        if (partitionId != null) {
            where.add("o._partition_id = '" + lit(partitionId) + "'");
        }
        if (!where.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", where));
        }
        return sb.toString();
    }

    /**
     * The completeness check (Spec 06.09 §3.3.2 step 3): the number of live
     * keys of {@code R} with no row of that key in {@code T} -- presence by
     * key, not liveness -- which must be 0. A Nullable key column (the keyless
     * all-columns fallback) is compared null-safely, since {@code NULL = NULL}
     * would report every such row as missing.
     *
     * <p>Both sides are explicit subqueries so that {@code FINAL} is confined
     * to the retired table. Measured on ClickHouse 24.8.14: with
     * {@code FROM R AS r FINAL LEFT JOIN T AS t ON t.b = r.b ... WHERE t.b IS
     * NULL}, the right-hand ReplacingMergeTree {@code T} was read FINAL-like
     * as well, so a key whose only rows in {@code T} were tombstones (deleted
     * or relocated during the backfill, {@code is_deleted = 1}) did not match
     * and was counted as missing -- 3 for exactly the 2 deleted and the 1
     * relocated row -- and the backfill retried forever. The same probe with
     * {@code T} read as {@code (SELECT DISTINCT <key> FROM T)} matched all
     * three and returned 0, which is what presence by key means.</p>
     *
     * <p>A new-key column whose type on {@code R} differs from its type on
     * {@code T} (a re-typed key column, Spec 06.09 §3.1.1) is read from
     * {@code R} through the conversion the copy applied -- {@code CAST(r.<old>,
     * '<type on T>')}, and {@code ifNull(CAST(r.<old>, 'Nullable(<type on T>)'),
     * defaultValueOfTypeName('<type on T>'))} when {@code R} has it Nullable
     * and {@code T} does not, the value {@code insert_null_as_default} stored.
     * Without it ClickHouse rejects the join of a String column against a
     * numeric one with {@code Code: 386 NO_COMMON_TYPE} (measured on 24.8.14
     * after {@code MODIFY class_name INT} on a VARCHAR key column: "Left key
     * class_name type Nullable(String). Right key class_name type Int32") and
     * the backfill retried forever.</p>
     */
    static String completenessCheck(Task task, Map<String, String> retiredTypes, Map<String, String> targetTypes) {
        String db = task.database();
        // The retired side: the new-key values of every live row of R, under
        // the new-key names (a renamed column read as o.<old> AS <new>, a
        // source-valued one from the key map), converted to T's type when the
        // column was re-typed by the rebuild.
        StringBuilder retired = new StringBuilder("SELECT ");
        boolean first = true;
        for (String column : task.newKey()) {
            String expr;
            if (task.sourceValued().contains(column)) {
                expr = "k." + q(column);
            } else {
                String old = oldNameOf(column, task.renames());
                expr = asRebuiltType("r." + q(old), retiredTypes.get(old), targetTypes.get(column));
            }
            retired.append(first ? "" : ", ").append(expr).append(" AS ").append(q(column));
            first = false;
        }
        retired.append(" FROM ").append(q(db)).append('.').append(q(task.retired())).append(" AS r FINAL");
        if (task.needKeyMap()) {
            retired.append(" INNER JOIN ").append(q(db)).append('.').append(q(task.keyMapTable())).append(" AS k ON ");
            first = true;
            for (String column : task.oldKey()) {
                retired.append(first ? "" : " AND ").append("r.").append(q(column)).append(" = k.").append(q(column));
                first = false;
            }
        }
        if (task.deleteFlag() != null) {
            retired.append(" WHERE r.").append(q(task.deleteFlag())).append(" = 0");
        }

        // The rebuilt side: the distinct keys T holds, live or tombstoned.
        StringBuilder join = new StringBuilder();
        String nullProbe = null;
        first = true;
        for (String column : task.newKey()) {
            String type = targetTypes.get(column);
            boolean nullable = type != null && type.contains("Nullable(");
            join.append(first ? "" : " AND ");
            if (nullable) {
                join.append("isNotDistinctFrom(t.").append(q(column)).append(", r.").append(q(column)).append(')');
            } else {
                join.append("t.").append(q(column)).append(" = r.").append(q(column));
                if (nullProbe == null) {
                    nullProbe = column;
                }
            }
            first = false;
        }
        List<String> rebuiltColumns = new ArrayList<>(task.newKey());
        if (nullProbe == null) {
            // Every key column is Nullable: probe a column T always has non-Nullable.
            nullProbe = PrimaryKeyRebuild.versionColumn(targetTypes);
            if (nullProbe == null) {
                nullProbe = task.newKey().get(0);
            } else {
                rebuiltColumns.add(nullProbe);
            }
        }
        // I14-scan-allowed: Spec 06.09 section 3.3.2 leftover check of a primary-key rebuild, run on the
        // backfill thread (never the event thread) and cancellable.
        return "SELECT count() FROM (" + retired + ") AS r LEFT JOIN (SELECT DISTINCT " + qList(rebuiltColumns)
                + " FROM " + q(db) + "." + q(task.table()) + ") AS t ON " + join + " WHERE t." + q(nullProbe)
                + " IS NULL SETTINGS join_use_nulls = 1";
    }

    /**
     * {@code expr} (a column of the retired table, of type {@code from}) read
     * as the rebuilt table's type {@code to}: the conversion the copy's
     * {@code INSERT ... SELECT} applied, so the completeness check compares
     * like with like (Spec 06.09 §3.3.2 step 3). Unchanged when either type is
     * unknown or both agree.
     */
    static String asRebuiltType(String expr, String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return expr;
        }
        if (from.contains("Nullable(") && !to.contains("Nullable(")) {
            // The copy stored the type's default for a NULL (insert_null_as_default).
            String nullableTo = to.startsWith("LowCardinality(") && to.endsWith(")")
                    ? "LowCardinality(Nullable(" + to.substring("LowCardinality(".length(), to.length() - 1) + "))"
                    : "Nullable(" + to + ")";
            return "ifNull(CAST(" + expr + ", '" + lit(nullableTo) + "'), defaultValueOfTypeName('" + lit(to) + "'))";
        }
        return "CAST(" + expr + ", '" + lit(to) + "')";
    }

    /**
     * Fills the source key map (Spec 06.09 §3.4): one streaming read-only
     * {@code SELECT <old key>, <source-valued> FROM `<source db>`.`<table>`},
     * bound into {@code K} with {@code setObject} in batches.
     *
     * @return the number of rows loaded.
     */
    static long loadSourceKeyMap(Connection source, String sourceDatabase, String table, List<String> oldKey,
                                 List<String> sourceValued, Connection ch, String db, String keyMapTable,
                                 Task task) {
        List<String> readColumns = new ArrayList<>(oldKey);
        readColumns.addAll(sourceValued);
        String select = "SELECT " + qList(readColumns) + " FROM " + q(sourceDatabase) + "." + q(table);
        KeylessTablePreflight.assertReadOnlySql(select);
        String insert = "INSERT INTO " + q(db) + "." + q(keyMapTable) + " (" + qList(readColumns) + ") VALUES ("
                + PrimaryKeyRebuild.placeholders(readColumns.size()) + ")";
        log.info("Primary-key backfill of {}.{}: reading the source key map with [{}] (read-only)", db, table, select);
        long rows = 0;
        try (Statement st = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            st.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rs = st.executeQuery(select)) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= readColumns.size(); i++) {
                    int jdbcType = md.getColumnType(i);
                    if (!PrimaryKeyRebuild.isKeyMapJdbcType(jdbcType)) {
                        throw new BackfillFailure("step 1 (source column types)", select, "source column "
                                + readColumns.get(i - 1) + " has MySQL type " + md.getColumnTypeName(i) + " (JDBC type "
                                + jdbcType + "), which the source key map cannot bind (integers and strings only, "
                                + "Spec 06.09 §3.2 item 4); manual rebuild required, the retired table is kept", null);
                    }
                }
                log.info("Primary-key backfill of {}.{}: filling {}.{} with [{}] in batches of {}", db, table, db,
                        keyMapTable, insert, SOURCE_BATCH_SIZE);
                try (PreparedStatement ps = ch.prepareStatement(insert)) {
                    int pending = 0;
                    while (rs.next()) {
                        for (int i = 1; i <= readColumns.size(); i++) {
                            ps.setObject(i, rs.getObject(i));
                        }
                        ps.addBatch();
                        rows++;
                        if (++pending == SOURCE_BATCH_SIZE) {
                            ps.executeBatch();
                            pending = 0;
                        }
                    }
                    if (pending > 0) {
                        ps.executeBatch();
                    }
                }
            }
        } catch (BackfillFailure e) {
            throw e;
        } catch (Exception e) {
            throw new BackfillFailure("step 1 (fill the source key map)", "[" + select + "] -> [" + insert + "]",
                    e.getMessage(), e);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Restart (Spec 06.09 §3.3.2 step 6)
    // ------------------------------------------------------------------

    /**
     * The backfills still pending in {@code database}: every
     * {@code T__pk_rebuild_%} / {@code T__pk_retired_%} table whose comment
     * carries the swap's marker and whose companion {@code T} exists keyed by
     * the marker's new key. A scratch table without the marker is the rebuilt
     * definition of a swap that never completed (removed by the next attempt's
     * step 1) or a kept copy whose backfill already completed; neither is
     * resumed. Never throws.
     *
     * @param props  the connector properties (kept for parity with {@link PrimaryKeyRebuild#swap}).
     * @param config the connector configuration (kept for parity with {@link PrimaryKeyRebuild#swap}).
     */
    public static List<Task> resumePending(Connection ch, Properties props, ClickHouseSinkConnectorConfig config,
                                           String database) {
        List<Task> tasks = new ArrayList<>();
        String scan = "SELECT name, comment FROM system.tables WHERE database = '" + lit(database)
                + "' AND (name LIKE '%" + likeLit("__pk_rebuild_") + "%' OR name LIKE '%" + likeLit("__pk_retired_")
                + "%') AND name NOT LIKE '%" + likeLit("__pk_rebuild_keys_") + "%' ORDER BY name";
        List<String[]> scratch;
        try {
            scratch = rows(ch, scan, 2);
        } catch (Exception e) {
            log.error("Primary-key backfill: could not scan {} for pending backfills ({}); a pending backfill in it "
                    + "is not resumed until the next start", database, e.toString());
            return tasks;
        }
        for (String[] row : scratch) {
            String retired = row[0];
            Marker marker = Marker.parse(row[1]);
            if (marker == null) {
                log.warn("Primary-key backfill: {}.{} looks like a rebuild scratch table but carries no pending-backfill "
                        + "marker (a swap that never completed, or a kept copy whose backfill is done); not resumed, "
                        + "not dropped", database, retired);
                continue;
            }
            try {
                String engineFull = scalar(ch, "SELECT engine_full FROM system.tables WHERE database = '" + lit(database)
                        + "' AND name = '" + lit(marker.table) + "'", "resume (engine of the rebuilt table)", null);
                if (engineFull == null) {
                    log.error("Primary-key backfill: {}.{} holds the pre-DDL rows of {}.{}, which no longer exists; the "
                            + "backfill cannot resume and the retired table is left in place", database, retired,
                            database, marker.table);
                    continue;
                }
                List<String> currentKey = sortingKey(ch, database, marker.table, null);
                if (!sameNames(currentKey, marker.newKey)) {
                    log.error("Primary-key backfill: {}.{} is keyed by {} but the pending backfill from {}.{} expects "
                            + "the new key {}; not resumed -- inspect both tables before dropping either", database,
                            marker.table, currentKey, database, retired, marker.newKey);
                    continue;
                }
                Task task = marker.toTask(database, retired, PrimaryKeyRebuild.deleteFlagColumn(engineFull));
                log.warn("Primary-key backfill of {}.{} from {}.{} is pending from a previous run and is resumed "
                        + "(Spec 06.09 §3.3.2 step 6): {}", database, marker.table, database, retired, task);
                tasks.add(task);
            } catch (Exception e) {
                log.error("Primary-key backfill: could not evaluate the pending backfill {}.{} ({}); not resumed until "
                        + "the next start", database, retired, e.toString());
            }
        }
        return tasks;
    }

    /** Every database of {@code ch} except the system ones; used when the configured target set is unknown. */
    public static List<String> nonSystemDatabases(Connection ch) {
        return column(ch, "SELECT name FROM system.databases WHERE name NOT IN ('system', 'information_schema', "
                + "'INFORMATION_SCHEMA') ORDER BY name", "resume (databases)", null);
    }

    /** The sorting-key columns of a table in position order, as {@code system.columns} spells them. */
    static List<String> sortingKey(Connection ch, String db, String table, Task task) {
        return column(ch, "SELECT name FROM system.columns WHERE database = '" + lit(db) + "' AND table = '"
                + lit(table) + "' AND is_in_sorting_key = 1 ORDER BY position", "sorting key of " + db + "." + table,
                task);
    }

    /** Position-wise, case-insensitive equality of two column lists. */
    static boolean sameNames(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // JDBC helpers -- plain Statement, no retry; the caller retries the whole attempt.
    // ------------------------------------------------------------------

    private static String who(Task task) {
        return task == null ? "Primary-key backfill" : "Primary-key backfill of " + task.database() + "." + task.table();
    }

    static void exec(Connection ch, String sql, String step, Task task) {
        log.info("{} {}: {}", who(task), step, sql);
        try (Statement st = ch.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new BackfillFailure(step, sql, e.getMessage(), e);
        }
    }

    static String scalar(Connection ch, String sql, String step, Task task) {
        log.info("{} {}: {}", who(task), step, sql);
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new BackfillFailure(step, sql, e.getMessage(), e);
        }
    }

    static long count(Connection ch, String sql, String step, Task task) {
        log.info("{} {}: {}", who(task), step, sql);
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("count() returned no row");
            }
            return rs.getLong(1);
        } catch (Exception e) {
            throw new BackfillFailure(step, sql, e.getMessage(), e);
        }
    }

    static List<String> column(Connection ch, String sql, String step, Task task) {
        log.info("{} {}: {}", who(task), step, sql);
        List<String> values = new ArrayList<>();
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new BackfillFailure(step, sql, e.getMessage(), e);
        }
        return values;
    }

    private static List<String[]> rows(Connection ch, String sql, int width) throws Exception {
        log.info("Primary-key backfill resume: {}", sql);
        List<String[]> out = new ArrayList<>();
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String[] row = new String[width];
                for (int i = 0; i < width; i++) {
                    row[i] = rs.getString(i + 1);
                }
                out.add(row);
            }
        }
        return out;
    }

    static List<ColumnInfo> columns(Connection ch, String db, String table, String step, Task task) {
        String sql = "SELECT name, type, default_kind FROM system.columns WHERE database = '" + lit(db)
                + "' AND table = '" + lit(table) + "' ORDER BY position";
        log.info("{} {}: {}", who(task), step, sql);
        List<ColumnInfo> columns = new ArrayList<>();
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(new ColumnInfo(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (Exception e) {
            throw new BackfillFailure(step, sql, e.getMessage(), e);
        }
        return columns;
    }
}
