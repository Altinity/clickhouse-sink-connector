package com.altinity.clickhouse.sink.connector.executor;

import java.util.concurrent.ScheduledThreadPoolExecutor;

public class ClickHouseBatchExecutor extends ScheduledThreadPoolExecutor {

    public ClickHouseBatchExecutor(int corePoolSize) {
        super(corePoolSize);
    }
}
