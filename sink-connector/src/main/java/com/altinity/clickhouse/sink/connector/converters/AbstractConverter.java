package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.source.SourceRecord;

import java.util.Map;

public interface AbstractConverter {
    Map<String, Object> convertKey(SinkRecord s);

    Map<String, Object> convertValue(SinkRecord sr);

    Map<String, Object> convertValue(SourceRecord sr);
}
