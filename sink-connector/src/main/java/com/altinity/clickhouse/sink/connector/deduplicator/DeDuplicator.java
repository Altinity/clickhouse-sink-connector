package com.altinity.clickhouse.sink.connector.deduplicator;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * DeDuplicator performs SinkRecord items de-duplication.
 * <p>
 * The DeDuplicator ensures that duplicate records are identified based on a de-duplication key.
 * It maintains a pool of records for each topic and manages de-duplication policies (such as keeping
 * old or new records) within that pool.
 * </p>
 */
public class DeDuplicator {
    /**
     * Local instance of a logger.
     */
    private static final Logger log = LogManager.getLogger(DeDuplicator.class);

    /**
     * Prepared, ready-to-use configuration. De-duplication needs some configuration parameters to fetch.
     */
    private ClickHouseSinkConnectorConfig config;

    /**
     * Pool of record for de-duplication. Maps a deduplication key to a record.
     * In case such a deduplication key already exists, deduplication policy comes into play -
     * what record to keep (an old one (already registered) or a newly coming one).
     * Key is topic name.
     * <p>
     * TODO: Consider how this works when there are multiple tables assigned to one topic.
     * </p>
     */
    private Map<String, Map<Object, Object>> records;

    /**
     * FIFO of de-duplication keys. Is limited by maxPoolSize. As soon as the limit is exceeded,
     * all older entries are removed from both FIFO and the pool.
     */
    private final Map<String, LinkedList<Object>> queue;

    /**
     * Max number of records in the de-duplication pool.
     */
    private long maxPoolSize;

    /**
     * DeDuplication policy describes how duplicate records are managed within the pool.
     */
    private DeDuplicationPolicy policy;

    /**
     * Constructor for the DeDuplicator.
     * Initializes the configuration, pool, and policy for de-duplication.
     *
     * @param config configuration to extract parameters from.
     */
    public DeDuplicator(ClickHouseSinkConnectorConfig config) {
        this.config = config;
        this.records = new HashMap<>();
        this.queue = new HashMap<>();

        // Prepare configuration values
        this.maxPoolSize = this.config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString());
        this.policy = DeDuplicationPolicy.of(this.config.getString(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString()));

        log.info("de-duplicator for task: {}, pool size: {}", this.config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString()), this.maxPoolSize);
    }

    /**
     * Checks whether the provided record is a new one or already seen before.
     * If the de-duplication is turned off, all records are considered new.
     *
     * @param topicName the topic name
     * @param record    the record to check
     * @return whether the record is new or already seen
     */
    public boolean isNew(String topicName, SinkRecord record) {
        // In case de-duplicator is turned off, no de-duplication is performed
        if (this.isTurnedOff()) {
            return true;
        }

        // Fetch de-duplication key
        Object deDuplicationKey = this.prepareDeDuplicationKey(record);

        if (false == checkIfRecordIsDuplicate(topicName, deDuplicationKey, record)) {
            return false;
        }

        // Update the deduplication pool with the new key
        updateDedupePool(topicName, deDuplicationKey);

        return true;
    }

    /**
     * Updates the de-duplication pool by adding a new key and removing old records
     * if the pool size exceeds the maximum allowed size.
     *
     * @param deDuplicationKey the key to add to the pool
     */
    public void updateDedupePool(String topicName, Object deDuplicationKey) {

        log.debug("add new key to the pool:" + deDuplicationKey);

        // Add the key to the FIFO queue for this topic
        LinkedList<Object> topicQueue = this.queue.computeIfAbsent(
                topicName, k -> new LinkedList<>());
        topicQueue.addLast(deDuplicationKey);

        // If the pool size exceeds maxPoolSize, evict the oldest entries
        Map<Object, Object> topicRecords = this.records.get(topicName);
        while (topicQueue.size() > this.maxPoolSize) {
            log.info("records pool is too big (" + topicQueue.size()
                    + "), evicting oldest entry");
            Object oldKey = topicQueue.removeFirst();
            if (oldKey != null && topicRecords != null) {
                topicRecords.remove(oldKey);
                log.info("evicted key: " + oldKey);
            }
        }
    }

    /**
     * Checks whether the new record is a duplicate.
     *
     * @param topicName          the topic name
     * @param deDuplicationKey   the key to check for duplication
     * @param record             the record to check
     * @return true if the record is a duplicate, false otherwise
     */
    public boolean checkIfRecordIsDuplicate(String topicName, Object deDuplicationKey, SinkRecord record) {

        // Get matching records for the topic
        Map<Object, Object> matchingRecords = this.records.get(topicName);

        if (matchingRecords == null) {
            // New topic: create records map and add the record
            matchingRecords = new HashMap<>();
            matchingRecords.put(deDuplicationKey, record);
            this.records.put(topicName, matchingRecords);
            return true;
        }

        if (matchingRecords.containsKey(deDuplicationKey)) {
            log.warn("already seen this key:" + deDuplicationKey);
            // Depending on the policy, replace the record or keep the old one
            if (this.policy == DeDuplicationPolicy.NEW) {
                matchingRecords.put(deDuplicationKey, record);
                log.info("replace the key:" + deDuplicationKey);
            }
            return false;
        }

        // New key for existing topic: add the record
        matchingRecords.put(deDuplicationKey, record);
        return true;
    }

    /**
     * Prepares the de-duplication key out of a record.
     * If the key is null, the value will be used as the de-duplication key.
     *
     * @param record the record to prepare the de-duplication key from
     * @return the de-duplication key constructed out of the record
     */
    private Object prepareDeDuplicationKey(SinkRecord record) {
        Object key = record.key();
        if (key == null) {
            key = record.value();
        }
        return key;
    }

    /**
     * Checks whether de-duplication is turned off or is turned on.
     *
     * @return true if de-duplicator is turned off, false otherwise
     */
    private boolean isTurnedOff() {
        return this.policy == DeDuplicationPolicy.OFF;
    }
}
