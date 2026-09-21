package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.common.Version;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicator;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * <p>Creates sink service instance, takes records loaded from those Kafka
 * partitions and ingests them into ClickHouse via the Sink service.</p>
 * <p>This class extends {@link SinkTask} and is managed by Kafka Connect.</p>
 */
public class ClickHouseSinkTask extends SinkTask {

    /**
     * The logger for this class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseSinkTask.class);

    /**
     * Initial delay (in milliseconds) for scheduling the batch runnable.
     */
    private static final long INITIAL_DELAY_MS = 0L;

    /**
     * The unique task identifier.
     */
    private String id = "-1";

    /**
     * Executor responsible for scheduling and running batches of records
     * to be ingested into ClickHouse.
     */
    private ClickHouseBatchExecutor executor;

    /**
     * Queue used to store batches of records to be processed.
     */
    private LinkedBlockingQueue<List<ClickHouseStruct>> records;

    /**
     * Highest Kafka offset per TopicPartition that has been DURABLY inserted
     * into ClickHouse (fed by the ClickHouseBatchRunnable after each successful
     * flush). preCommit() returns offsets derived from this map instead of the
     * offsets Kafka Connect merely delivered to put(), so a crash/restart never
     * skips records that were consumed but not yet persisted.
     */
    private ConcurrentHashMap<TopicPartition, Long> durablyInsertedOffsets;

    /**
     * A de-duplicator utility to detect and skip duplicate messages.
     */
    private DeDuplicator deduplicator;

    /**
     * The configuration for the sink connector.
     */
    private ClickHouseSinkConnectorConfig config;

    /**
     * The total number of records processed by this task.
     */
    private long totalRecords;

    /**
     * The scheduled future of the batch runnable that drains {@link #records}.
     *
     * <p>{@code ScheduledThreadPoolExecutor} cancels a periodic task whose run
     * throws and calls nobody. The runnable rethrows on a FATAL ClickHouse
     * classification (spec 10.01), so without inspecting this future the task
     * kept accepting records in {@link #put} and answering {@link #preCommit}
     * with a frozen watermark — consuming Kafka forever while replicating
     * nothing. Checked at the top of both (spec 03.01 §3.4).</p>
     */
    private ScheduledFuture<?> runnableFuture;

    /**
     * Default constructor.
     */
    public ClickHouseSinkTask() {
        // No specific initialization here.
    }

    /**
     * Starts the task by initializing the configuration, setting up the
     * executor for batching and scheduling, and creating the de-duplicator.
     *
     * @param config The task configuration map provided by Kafka Connect.
     */
    @Override
    public void start(Map<String, String> config) {
        //ToDo: Check buffer.count.records and how its used.
        //final long count = Long.parseLong(config.get(ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT));
        //log.info("start({}):{}", this.id, count);
        log.info("start({})", this.id);

        this.config = new ClickHouseSinkConnectorConfig(config);

        Map<String, String> topic2TableMap = null;
        try {
            topic2TableMap = Utils.parseTopicToTableMap(
                    this.config.getString(
                            ClickHouseSinkConnectorConfigVariables
                                    .CLICKHOUSE_TOPICS_TABLES_MAP.toString()));
        } catch (Exception e) {
            log.error("Error parsing topic to table map" + e);
        }

        this.id = "task-" + this.config.getLong(
                ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());

        int maxQueueSize = this.config.getInt(
                ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString());

        this.records = new LinkedBlockingQueue<>(maxQueueSize);
        this.durablyInsertedOffsets = new ConcurrentHashMap<>();

        ClickHouseBatchRunnable runnable = new ClickHouseBatchRunnable(
                this.records, this.config, topic2TableMap,
                this.durablyInsertedOffsets);

        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("Sink Connector thread-pool-%d")
                .build();

        this.executor = new ClickHouseBatchExecutor(
                this.config.getInt(
                        ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE
                                .toString()),
                namedThreadFactory);

        this.runnableFuture = this.executor.scheduleAtFixedRate(
                runnable,
                INITIAL_DELAY_MS,
                this.config.getLong(
                        ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME
                                .toString()),
                TimeUnit.MILLISECONDS);

        this.deduplicator = new DeDuplicator(this.config);
    }

    /**
     * Wires the task's collaborators without opening a ClickHouse connection
     * or starting an executor, so {@link #put} and {@link #preCommit} can be
     * exercised in a unit test. {@link #start} builds the same state and
     * additionally schedules the runnable.
     */
    @VisibleForTesting
    void attachForTest(ClickHouseSinkConnectorConfig config,
                       LinkedBlockingQueue<List<ClickHouseStruct>> records,
                       ConcurrentHashMap<TopicPartition, Long> durablyInsertedOffsets,
                       DeDuplicator deduplicator,
                       ScheduledFuture<?> runnableFuture) {
        this.config = config;
        this.records = records;
        this.durablyInsertedOffsets = durablyInsertedOffsets;
        this.deduplicator = deduplicator;
        this.runnableFuture = runnableFuture;
    }

    /**
     * Fails the task loudly if the batch runnable's scheduled task has
     * terminated.
     *
     * <p>Mirrors {@code DebeziumChangeEventCapture.failIfWorkerDied} for the
     * embedded runtime (spec 03.01 §3.3). A done future — completed
     * exceptionally after a FATAL rethrow, cancelled, or completed normally,
     * which a periodic task never legitimately does — means nothing will ever
     * drain the queue again: records accepted by {@link #put} can never be
     * written and {@link #preCommit} can never advance. Throwing
     * {@link ConnectException} makes Kafka Connect fail the task with the
     * runnable's cause in the log; a restart resumes from the last committed
     * offset, which only ever reflects durably inserted rows.</p>
     *
     * @throws ConnectException carrying the runnable's cause.
     */
    @VisibleForTesting
    void failIfRunnableDied() {
        ScheduledFuture<?> future = this.runnableFuture;
        if (future == null || !future.isDone()) {
            return;
        }
        Throwable cause = null;
        try {
            future.get();
        } catch (ExecutionException e) {
            cause = e.getCause() != null ? e.getCause() : e;
        } catch (CancellationException e) {
            cause = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cause = e;
        }
        String message = String.format("Sink task %s: the ClickHouse batch runnable is dead -- its "
                + "scheduled task has terminated%s. Records accepted by put() can never be written "
                + "and preCommit() can never advance, so the task would consume Kafka forever while "
                + "replicating nothing. Failing the task so the failure is visible; a restart resumes "
                + "from the last committed (durably inserted) offset.", this.id,
                cause == null ? " normally (a runnable must run for the life of the task)" : "");
        log.error(message, cause);
        throw new ConnectException(message, cause);
    }

    /**
     * Stops the task by shutting down the executor, preventing further
     * processing of records.
     */
    @Override
    public void stop() {
        log.info("stop({})", this.id);
        if (this.executor != null) {
            this.executor.shutdown();
        }
    }

    /**
     * Called when the task is opened, typically to initialize or resume
     * processing for the given set of partitions.
     *
     * @param partitions The collection of topic partitions that
     *                   will be processed by this task.
     */
    @Override
    public void open(final Collection<TopicPartition> partitions) {
        log.info("open({}):{}", this.id, partitions.size());
    }

    /**
     * Called when the task is closed, typically to clean up resources
     * associated with the given partitions.
     *
     * @param partitions The collection of topic partitions that
     *                   were processed by this task.
     */
    @Override
    public void close(final Collection<TopicPartition> partitions) {
        log.info("close({}):{}", this.id, partitions.size());
    }

    /**
     * Receives a collection of {@link SinkRecord} objects from Kafka Connect,
     * processes them (optionally de-duplicating), and places them into the
     * queue for ingestion into ClickHouse.
     *
     * @param records The collection of records to process.
     */
    @Override
    public void put(Collection<SinkRecord> records) {
        // Before accepting anything: is there still a runnable to write it?
        failIfRunnableDied();

        totalRecords += records.size();

        long taskId = this.config.getLong(
                ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());

        log.debug("******** CLICKHOUSE received records **** {} Task Id: {}",
                totalRecords, taskId);

        ClickHouseConverter converter = new ClickHouseConverter();
        List<ClickHouseStruct> batch = new ArrayList<>();

        for (SinkRecord record : records) {
            // Establish the per-partition durable baseline the first time we see
            // a partition in this run. Kafka delivers records to put() in offset
            // order per partition, so the first record's offset is the resume
            // point: everything strictly before it was already durably committed
            // in a previous run. Seeding the watermark to (firstOffset - 1) means
            // preCommit holds at the resume point until a real insert advances it,
            // instead of blindly trusting the offsets Connect delivered (which
            // would lose records consumed-but-not-yet-inserted, e.g. if CH is down
            // right from task start).
            TopicPartition tp = new TopicPartition(
                    record.topic(), record.kafkaPartition());
            this.durablyInsertedOffsets.putIfAbsent(tp, record.kafkaOffset() - 1L);

            // A Kafka tombstone (null value) is the ONLY record that may be
            // skipped: Debezium emits one after each DELETE for log
            // compaction, and it carries no change event -- the DELETE itself
            // arrived in the preceding record. Everything else is either a
            // change event or a defect (spec 10.04 section 3.5).
            if (record.value() == null) {
                log.debug("Skipping Kafka tombstone at topic {} partition {} offset {}",
                        record.topic(), record.kafkaPartition(), record.kafkaOffset());
                continue;
            }

            if (this.deduplicator.isNew(record.topic(), record)) {
                ClickHouseStruct c = converter.convert(record);
                if (c == null) {
                    // A non-null value that does not convert is not a
                    // tombstone; it is a record this connector cannot
                    // replicate (no Debezium envelope 'op' / row image, or a
                    // non-STRUCT value). Dropping it at DEBUG -- the previous
                    // behaviour -- lost the change while the offset advanced
                    // past it.
                    throw new DataException(String.format(
                            "Record at topic %s partition %d offset %d has a non-null value that "
                                    + "does not convert to a change event (no Debezium envelope "
                                    + "'op' / row image, or a non-STRUCT value schema). Dropping it "
                                    + "would lose a replicated change while the offset advances; "
                                    + "failing the task instead (spec 10.04 section 3.5).",
                            record.topic(), record.kafkaPartition(), record.kafkaOffset()));
                }
                batch.add(c);
            }
        }

        try {
            this.records.put(batch);
        } catch (InterruptedException e) {
            throw new RetriableException(e);
        }
    }

    //    private void appendToRecords(Map<String, List<ClickHouseStruct>> convertedRecords) {
    //        ConcurrentLinkedQueue<List<ClickHouseStruct>> structs;
    //
    //        synchronized (this.records) {
    //            //Iterate through convertedRecords and add to the records map.
    //            for (Map.Entry<String, List<ClickHouseStruct>> entry : convertedRecords.entrySet()) {
    //                if (this.records.containsKey(entry.getKey())) {
    //                    structs = this.records.get(entry.getKey());
    //                    structs.add(entry.getValue());
    //
    //                } else {
    //                    structs = new ConcurrentLinkedQueue<>();
    //                    structs.add(entry.getValue());
    //                }
    //                this.records.put(entry.getKey(), structs);
    //
    //            }
    //        }
    //    }

    /**
     * <p>preCommit() is something like a replacement for flush - it takes the
     * same parameters. Returns the offsets that Kafka Connect should commit.
     * Typical behavior is to call flush and return the same offsets that
     * were passed, which means Kafka Connect should commit all the offsets
     * it passed to the connector via preCommit.</p>
     * <p>If this method returns an empty set of offsets, then Kafka Connect
     * will record no offsets at all.</p>
     * <p>If the connector is going to handle all offsets in the external
     * system and doesn't need Kafka Connect to record anything, then this
     * method should be overridden (instead of flush) and return an empty
     * set of offsets.</p>
     *
     * @param currentOffsets A map of topic partition to current offset.
     * @return A map of committed offsets that Kafka Connect should record.
     * @throws RetriableException If there's a retriable error.
     */
    @Override
    public Map<TopicPartition, OffsetAndMetadata> preCommit(
            Map<TopicPartition, OffsetAndMetadata> currentOffsets)
            throws RetriableException {

        log.info("preCommit({}) {}", this.id, currentOffsets.size());

        // A dead runnable can never advance the durable watermark; answering
        // from a frozen map would let the task sit forever. Deliberately
        // outside the try below, which turns exceptions into "commit nothing".
        failIfRunnableDied();

        Map<TopicPartition, OffsetAndMetadata> committedOffsets =
                new HashMap<>();

        try {
            currentOffsets.forEach((topicPartition, offsetAndMetadata) -> {
                // Kafka commits the "next offset to consume" = lastOffset + 1.
                Long durable = this.durablyInsertedOffsets.get(topicPartition);
                long committed;
                if (durable == null) {
                    // No durable insert recorded for this partition yet in the
                    // current run (e.g. right after a restart): trust the offset
                    // Connect gives us — it already reflects the last durably
                    // committed position from the previous run, because we only
                    // ever commit durable offsets.
                    committed = offsetAndMetadata.offset();
                } else {
                    // Only advance up to what was actually inserted into CH,
                    // never beyond what Connect delivered.
                    committed = Math.min(offsetAndMetadata.offset(), durable + 1);
                }
                committedOffsets.put(topicPartition,
                        new OffsetAndMetadata(committed));
            });
        } catch (Exception e) {
            log.error("preCommit({}):{}", this.id, e.getMessage());
            return new HashMap<>();
        }

        return committedOffsets;
    }

    //  @Override
    //  public void flush(Map<TopicPartition, OffsetAndMetadata> currentOffsets) {
    //      // No-op. The connector is managing the offsets.
    //      if(!this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.ENABLE_KAFKA_OFFSET)) {
    //          return currentOffsets;
    //      }
    //  }

    /**
     * Returns the version of this task, typically the same as the connector
     * version.
     *
     * @return The version string of this task.
     */
    @Override
    public String version() {
        return Version.VERSION;
    }
}
