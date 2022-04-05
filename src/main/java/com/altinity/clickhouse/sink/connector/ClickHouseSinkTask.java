package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicator;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * <p>Creates sink service instance, takes records loaded from those
 * Kafka partitions and ingests to
 * ClickHouse via Sink service
 */
public class ClickHouseSinkTask extends SinkTask {
    private static final long WAIT_TIME = 5 * 1000; // 5 sec
    private static final int REPEAT_TIME = 12; // 60 sec
    private String id = "-1";
    private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkTask.class);

    public ClickHouseSinkTask() {
        return;
    }

    private void getConnection() {
        return;
    }

    private ClickHouseBatchExecutor executor;
    private ClickHouseBatchRunnable runnable;
    private ConcurrentLinkedQueue<Struct> records;

    private DeDuplicator deduplicator;

    @Override
    public void start(Map<String, String> config) {
        this.id = config.getOrDefault(Const.TASK_ID, "-1");
        final long count = Long.parseLong(config.get(ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT));
        log.info("start({}):{}", this.id, count);

        this.records = new ConcurrentLinkedQueue();
        this.runnable = new ClickHouseBatchRunnable(this.records);
        this.executor = new ClickHouseBatchExecutor(2);
        this.executor.scheduleAtFixedRate(this.runnable, 0, 30, TimeUnit.SECONDS);

        this.deduplicator = new DeDuplicator();

        /*


        ClickHouseProtocol protocol = ClickHouseProtocol.HTTP;
        ClickHouseFormat format = ClickHouseFormat.RowBinaryWithNamesAndTypes;
        ClickHouseNode node = ClickHouseNode.builder().port(protocol).build();

        try (ClickHouseClient client = ClickHouseClient.newInstance(protocol);
             ClickHouseResponse response = client.connect(node)
                     .format(format)
                     .query("select * from numbers(:limit)")
                     .params(1000).execute().get()) {
            for (ClickHouseRecord record : response.records()) {
                int num = record.getValue(0).asInteger();
                String str = record.getValue(0).asString();
            }

            ClickHouseResponseSummary summary = response.getSummary();
            long totalRows = summary.getTotalRowsToRead();
        } catch (Exception e) {
            log.warn("error call query");
        }
        */
    }

    @Override
    public void stop() {
        log.info("stop({})", this.id);
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
        log.debug("CLICKHOUSE received records" + records.size());
        ClickHouseConverter converter = new ClickHouseConverter();
        BufferedRecords br = new BufferedRecords();
        for (SinkRecord record : records) {
            if (this.deduplicator.isNew(record)) {
                Struct c = converter.convert(record);
                if (c != null) {
                    this.records.add(c);
                }
            } else {
                log.info("skip already seen record: " + record);
            }
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
     * @param currentOffsets
     * @return
     * @throws RetriableException
     */
    @Override
    public Map<TopicPartition, OffsetAndMetadata> preCommit(Map<TopicPartition, OffsetAndMetadata> currentOffsets)
            throws RetriableException {
        log.info("preCommit({}) {}", this.id, currentOffsets.size());

        Map<TopicPartition, OffsetAndMetadata> committedOffsets = new HashMap<>();
        try {
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
    public String version() {
        return Version.VERSION;
    }
}
