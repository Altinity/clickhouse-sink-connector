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
 * DeDuplicator performs SinkRecord items de-duplication
 */
public class DeDuplicator {
    /**
     * Local instance of a logger
     */
    private static final Logger log = LogManager.getLogger(DeDuplicator.class);

    /**
     * Prepared, ready-to-use configuration. De-duplication needs some configuration parameters to fetch.
     */
    private ClickHouseSinkConnectorConfig config;
    /**
     * Pool of record for de-duplication. Maps a deduplication key to a record.
     * In case such a deduplication key already exists deduplication policy comes into play - what record to keep
     * an old one (already registered) or a newly coming one.
     * Key is topic name
     */
    // ToDo: How would this work when there are multiple tables assigned to one topic.
    private Map<String, Map<Object, Object>> records;
    /**
     * FIFO of de-duplication keys. Is limited by maxPoolSize. As soon as limit is exceeded, all older entries
     * are removed from both FIFO and the pool.
     */
    private final Map<String, LinkedList<Object>> queue;
    /**
     * Max number of records in de-duplication pool.
     */
    private long maxPoolSize;
    /**
     * DeDuplication policy describes how duplicate records are managed within the pool.
     */
    private DeDuplicationPolicy policy;

    /**
     * Constructor.
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
     * Checks whether provided record is a new one or already seen before.
     *
     * @param record record to check
     * @return whether this record is a new one or been already seen
     */
    public boolean isNew(String topicName, SinkRecord record) {
        // In case de-duplicator is turned off no de-duplication is performed and all entries are considered to be new.
        if (this.isTurnedOff()) {
            return true;
        }

        // Fetch de-duplication key
        Object deDuplicationKey = this.prepareDeDuplicationKey(record);

        
        if (false == checkIfRecordIsDuplicate(topicName, deDuplicationKey, record)) {
            return false;
        }

        updateDedupePool(deDuplicationKey);

        return true;
    }

    public void updateDedupePool(Object deDuplicationKey) {

        log.debug("add new key to the pool:" + deDuplicationKey);

        // Iterate through all topics and corresponding pools.

        for(Map.Entry<String, LinkedList<Object>> entry: this.queue.entrySet()) {

            LinkedList<Object> matchingQueue = entry.getValue();

            while (matchingQueue.size() > this.maxPoolSize) {
                log.info("records pool is too big, need to flush:" + this.queue.size());
                Object key = matchingQueue.removeFirst();
                if (key == null) {
                    log.warn("unable to removeFirst() in the queue");
                } else {
                    matchingQueue.remove(key);
                    log.info("removed key: " + key);
                }
            }
        }

    }
    /**
     * Function to check if the new record is a duplicate
     * @param topicName
     * @param record
     * @return true if duplicate, false otherwise
     */
    public boolean checkIfRecordIsDuplicate(String topicName, Object deDuplicationKey, SinkRecord record) {
        boolean result = false;

        Map<Object, Object> matchingRecords = this.records.get(topicName);
        
        if(matchingRecords == null) {
            // New record for topic.
            matchingRecords = new HashMap<Object, Object>();
            matchingRecords.put(deDuplicationKey, record);

            this.records.put(topicName, matchingRecords);
            result = true;
            
        } else {
            if(matchingRecords.containsKey(deDuplicationKey)) {
                log.warn("already seen this key:" + deDuplicationKey);

                if (this.policy == DeDuplicationPolicy.NEW) {
                    matchingRecords.put(deDuplicationKey, record);
                    this.records.put(topicName, matchingRecords);
                    log.info("replace the key:" + deDuplicationKey);
                }
                result = false;
            }
        }

        return result;
    }

    /**
     * Prepares de-duplication key out of a record
     *
     * @param record record to prepare de-duplication key from
     * @return de-duplication key constructed out of the record
     */
    private Object prepareDeDuplicationKey(SinkRecord record) {
        Object key = record.key();
        if (key == null) {
            key = record.value();
        }
        return key;
    }

    /**
     * Checks whether de-duplicator is turned off or is turned on
     *
     * @return true in case de-duplicator is off
     * false in case de-duplicator is on
     */
    private boolean isTurnedOff() {
        if (this.policy == DeDuplicationPolicy.OLD) {
            return false;
        }
        if (this.policy == DeDuplicationPolicy.NEW) {
            return false;
        }
        return true;
    }
}
