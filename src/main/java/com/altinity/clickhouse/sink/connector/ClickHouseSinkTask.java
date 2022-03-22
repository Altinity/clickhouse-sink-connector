package com.altinity.clickhouse.sink.connector;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  @Override
  public void start(final Map<String, String> config) {
    this.id = config.getOrDefault(Const.TASK_ID, "-1");
    final long count = Long.parseLong(config.get(ClickHouseSinkConnectorConfig.BUFFER_COUNT));
    log.info("start({}):{}", this.id, count);
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
  public void put(final Collection<SinkRecord> records) {
    log.info("out({}):{}", this.id, records.size());
  }

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
