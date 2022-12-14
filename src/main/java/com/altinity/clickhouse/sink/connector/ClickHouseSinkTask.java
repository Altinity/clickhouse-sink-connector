package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.common.Version;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicator;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
// import java.util.concurrent.TimeUnit;

/**
 * <p>Creates sink service instance, takes records loaded from those
 * Kafka partitions and ingests to
 * ClickHouse via Sink service
 */
public class ClickHouseSinkTask extends SinkTask {

    private String id = "-1";
    private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkTask.class);

    public ClickHouseSinkTask() {

    }

    private ClickHouseBatchExecutor executor;

    // Records grouped by Topic Name
    // private ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records;

    private DeDuplicator deduplicator;

    private ClickHouseSinkConnectorConfig config;

    private long totalRecords;

    private Map<String, String> topic2TableMap = null;

    @Override
    public void start(Map<String, String> config) {

        //ToDo: Check buffer.count.records and how its used.
        //final long count = Long.parseLong(config.get(ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT));
        //log.info("start({}):{}", this.id, count);
        log.info("start({})", this.id);

        this.config = new ClickHouseSinkConnectorConfig(config);

        try {
             this.topic2TableMap = Utils.parseTopicToTableMap(this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TOPICS_TABLES_MAP));
        } catch (Exception e) {
            log.error("Error parsing topic to table map" + e);
        }

        this.id = "task-" + this.config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID);

        this.executor = new ClickHouseBatchExecutor(this.config.getInt(ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE), new LinkedBlockingQueue<>());

        // ClickHouseBatchRunnable runnable = new ClickHouseBatchRunnable(this.records, this.config, topic2TableMap);
        // this.executor.scheduleAtFixedRate(runnable, 0, this.config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME), TimeUnit.MILLISECONDS);

        this.deduplicator = new DeDuplicator(this.config);
    }

    @Override
    public void stop() {
        log.info("stop({})", this.id);
        if (this.executor != null) {
            this.executor.shutdown();
        }
    }

    @Override
    public void open(final Collection<TopicPartition> partitions) {
        log.info("open({}):{}", this.id, partitions.size());
    }

    @Override
    public void close(final Collection<TopicPartition> partitions) {
        log.info("close({}):{}", this.id, partitions.size());
    }


    @Override
    public void put(Collection<SinkRecord> records) {
        log.info("******** recieved recods " + records.size());
        totalRecords += records.size();

        long taskId = this.config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID);
        log.debug("******** CLICKHOUSE received records **** " + totalRecords + " Task Id: " + taskId);
        ClickHouseConverter converter = new ClickHouseConverter();

        ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records_map = new ConcurrentHashMap<>();
        for (SinkRecord record : records) {
            if (this.deduplicator.isNew(record.topic(), record)) {
                ClickHouseStruct c = converter.convert(record);
                if (c != null) {
                    this.appendToRecords(records_map, c.getTopic(), c);
                }
            } else {
                log.info("skip already seen record: " + record);
            }
        }

        ClickHouseBatchRunnable runnable = new ClickHouseBatchRunnable(records_map, this.config, this.topic2TableMap);
        this.executor.execute(runnable);
    }

    private void appendToRecords(ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records_map, String topicName, ClickHouseStruct chs) {
        ConcurrentLinkedQueue<ClickHouseStruct> structs;

        if(records_map.containsKey(topicName)) {
            structs = records_map.get(topicName);
        } else {
            structs = new ConcurrentLinkedQueue<>();
        }
        structs.add(chs);
        synchronized (records_map) {
            records_map.put(topicName, structs);
        }
    }

    /**
     * preCommit() is a something like a replacement for flush - takes the same parameters
     * Returns the offsets that Kafka Connect should commit.
     * Typical behavior is to call flush and return the same offsets that were passed as params,
     * which means Kafka Connect should commit all the offsets it passed to the connector via preCommit.
     * But if your preCommit returns an empty set of offsets, then Kafka Connect will record no offsets at all.
     * <p>
     * If the connector is going to handle all offsets in the external system and doesn't need Kafka Connect to record anything,
     * then you should override the preCommit method instead of flush, and return an empty set of offsets.
     *
     * @param currentOffsets Offset in Kafka.
     * @return Map of
     * @throws RetriableException
     */
    @Override
    public Map<TopicPartition, OffsetAndMetadata> preCommit(Map<TopicPartition, OffsetAndMetadata> currentOffsets)
            throws RetriableException {
        log.info("preCommit({}) {}", this.id, currentOffsets.size());

        Map<TopicPartition, OffsetAndMetadata> committedOffsets = new HashMap<>();
        try {
            this.flush(currentOffsets);
            currentOffsets.forEach(
                    (topicPartition, offsetAndMetadata) -> {
                        committedOffsets.put(topicPartition, new OffsetAndMetadata(offsetAndMetadata.offset()));
                    });
        } catch (Exception e) {
            log.error("preCommit({}):{}", this.id, e.getMessage());
            return new HashMap<>();
        }

        return committedOffsets;
    }
    
    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> currentOffsets) {
        try {
            this.executor.awaitCurrentTasks();
        } 
        catch (InterruptedException err) {
            throw new ConnectException("Interrupted while waiting for write tasks to complete.", err);
        }
        // this.executor.
        // No-op. The connector is managing the offsets.
        // if(!this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.ENABLE_KAFKA_OFFSET)) {
        //     return currentOffsets;
        // }
    }

    @Override
    public String version() {
        return Version.VERSION;
    }
}
