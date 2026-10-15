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
 * The DeDuplicator identifies a REDELIVERED record -- the same event, i.e. the
 * same (topic, partition, offset), seen twice -- and reports it as not new.
 * It maintains a bounded pool of accepted event identities for each topic and
 * manages de-duplication policies (such as keeping old or new records) within
 * that pool. It never keys on the row (primary) key: distinct events for one
 * row are distinct changes and must all reach ClickHouse (Spec 10.05).
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
     * Pool of records for de-duplication, per topic. Maps an EVENT identity
     * ({@code topic/partition/offset}, see {@link #prepareDeDuplicationKey})
     * to the record accepted under it. When an identity is seen again the
     * policy decides which record object the pool retains (the old one or the
     * new one); either way the second arrival is reported as not new.
     * <p>
     * TODO: Consider how this works when there are multiple tables assigned to one topic.
     * </p>
     */
    private Map<String, Map<Object, Object>> records;

    /**
     * FIFO of accepted identities, per topic, bounding {@link #records}. As
     * soon as a topic's FIFO exceeds {@code maxPoolSize} the oldest identities
     * are evicted from BOTH the FIFO and that topic's pool -- the map
     * {@link #isNew} actually consults. (An earlier implementation pruned only
     * this FIFO, which nothing ever populated, so the pool grew without bound.)
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
     * Records a newly accepted identity in the topic's FIFO and evicts the
     * oldest identities from the topic's pool once the FIFO exceeds
     * {@code maxPoolSize} (Spec 10.05 section 3.2).
     *
     * @param topicName        the topic whose pool the key belongs to
     * @param deDuplicationKey the identity that was just accepted
     */
    public void updateDedupePool(String topicName, Object deDuplicationKey) {

        log.debug("add new key to the pool:" + deDuplicationKey);

        LinkedList<Object> fifo = this.queue.computeIfAbsent(topicName, t -> new LinkedList<>());
        fifo.addLast(deDuplicationKey);

        Map<Object, Object> pool = this.records.get(topicName);
        // If the pool size exceeds maxPoolSize, remove the oldest entries from
        // the pool that isNew() consults, not only from the FIFO.
        while (fifo.size() > this.maxPoolSize) {
            Object oldest = fifo.removeFirst();
            if (pool != null) {
                pool.remove(oldest);
            }
            log.debug("de-duplication pool for topic {} is full ({}); evicted identity {}",
                    topicName, this.maxPoolSize, oldest);
        }
    }

    /**
     * Registers the identity in the topic's pool unless it is already there.
     *
     * @param topicName          the topic name
     * @param deDuplicationKey   the event identity to check
     * @param record             the record to check
     * @return true if the identity is NEW (now registered), false if it was
     *         already seen (a redelivery)
     */
    public boolean checkIfRecordIsDuplicate(String topicName, Object deDuplicationKey, SinkRecord record) {
        Map<Object, Object> matchingRecords =
                this.records.computeIfAbsent(topicName, t -> new HashMap<>());

        if (matchingRecords.containsKey(deDuplicationKey)) {
            log.warn("Duplicate delivery of event {} on topic {}; dropping the redelivered record",
                    deDuplicationKey, topicName);

            // Depending on the policy, replace the retained record or keep the old one
            if (this.policy == DeDuplicationPolicy.NEW) {
                matchingRecords.put(deDuplicationKey, record);
                log.debug("replaced the retained record for identity {}", deDuplicationKey);
            }
            return false;
        }

        matchingRecords.put(deDuplicationKey, record);
        return true;
    }

    /**
     * The de-duplication key is the record's EVENT identity:
     * {@code topic / partition / offset}.
     *
     * <p>Kafka guarantees one event per (topic, partition, offset) and a
     * redelivery reproduces it exactly, which is the only thing de-duplication
     * may drop. The row key ({@code record.key()}, the source primary key) is
     * NOT an event identity: every later UPDATE and DELETE of a row carries
     * the same key as its INSERT, so keying on it dropped every subsequent
     * change to a row and froze its first image in ClickHouse forever
     * (Spec 10.05 section 3.1).</p>
     *
     * @param record the record to prepare the de-duplication key from
     * @return the event identity of the record
     */
    private Object prepareDeDuplicationKey(SinkRecord record) {
        return record.topic() + "/" + record.kafkaPartition() + "/" + record.kafkaOffset();
    }

    /**
     * Number of identities currently held for a topic; the map {@link #isNew}
     * consults. Exposed for tests of the pool bound.
     *
     * @param topicName the topic
     * @return the pool size for that topic, 0 if none
     */
    int poolSize(String topicName) {
        Map<Object, Object> matchingRecords = this.records.get(topicName);
        return matchingRecords == null ? 0 : matchingRecords.size();
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
