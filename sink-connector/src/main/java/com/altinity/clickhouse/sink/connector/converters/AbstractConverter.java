package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.source.SourceRecord;

import java.util.Map;

public interface AbstractConverter {
    /**
     * convertKey.
     * @param s
     * @return Map
     */
    Map<String, Object> convertKey(SinkRecord s);

    /**
     * convertValue.
     * @param sr
     * @return Map
     */
    Map<String, Object> convertValue(SinkRecord sr);

    /**
     * convertValue.
     * @param sr
     * @return Map
     */
    Map<String, Object> convertValue(SourceRecord sr);
}
