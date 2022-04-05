package com.altinity.clickhouse.sink.connector.deduplicator;


import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DeDuplicator {
    private static final Logger log = LoggerFactory.getLogger(DeDuplicator.class);
    private Map<Object, Object> records;
    private LinkedList<Object> queue;
    private int maxPoolSize = 10;

    public DeDuplicator() {
        this.records = new HashMap<Object, Object>();
        this.queue = new LinkedList<Object>();
    }

    public boolean isNew(SinkRecord record) {
        if (this.records.containsKey(record.key())) {
            log.warn("already seen this key:" + record.key());
            return false;
        }

        log.debug("add new key to the pool:" + record.key());

        this.records.put(record.key(), record);
        this.queue.add(record.key());

        while (this.queue.size() > this.maxPoolSize) {
            log.info("records pool is too big, need to flush:" + this.queue.size());
            Object key = this.queue.removeFirst();
            if (key == null) {
                log.warn("unable to removeFirst() in the queue");
            } else {
                this.records.remove(key);
                log.info("removed key: " + key);
            }
        }

        return true;
    }
}
