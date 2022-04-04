package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkTask;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runnable object that will be called on
 * a schedule to perform the batch insert of
 * records to Clickhouse.
 */
public class ClickHouseBatchRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseBatchRunnable.class);
    private ConcurrentLinkedQueue<Struct> records;

    public ClickHouseBatchRunnable(ConcurrentLinkedQueue<Struct> records) {
        this.records = records;
    }

    @Override
    public void run() {
        log.info("*************** BULK INSERT TO CLICKHOUSE **************");
        DbWriter writer = new DbWriter();
        writer.insert(this.records);
    }
}
