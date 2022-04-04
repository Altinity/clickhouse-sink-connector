package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.db.DbWriter;
import org.apache.kafka.connect.data.Struct;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runnable object that will be called on
 * a schedule to perform the batch insert of
 * records to Clickhouse.
 */
public class ClickHouseBatchRunnable implements Runnable{

    private ConcurrentLinkedQueue<Struct> records;
    public ClickHouseBatchRunnable(ConcurrentLinkedQueue<Struct> records) {
        this.records = records;
    }

    @Override
    public void run() {
        System.out.println("*************** BULK INSERT TO CLICKHOUSE **************");
        DbWriter writer = new DbWriter();
        writer.insert(this.records);
    }
}
