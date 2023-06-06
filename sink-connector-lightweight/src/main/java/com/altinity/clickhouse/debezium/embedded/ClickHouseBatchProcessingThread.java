package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import java.util.List;

public class ClickHouseBatchProcessingThread extends ClickHouseBatchRunnable implements Runnable {


    public ClickHouseBatchProcessingThread(List<ClickHouseStruct> chStructs, ClickHouseSinkConnectorConfig config) {
        super(null, config, null);
    }

    @Override
    public void run() {

    }
}
