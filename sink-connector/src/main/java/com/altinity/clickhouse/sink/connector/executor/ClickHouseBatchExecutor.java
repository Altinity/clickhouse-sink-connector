package com.altinity.clickhouse.sink.connector.executor;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

public class ClickHouseBatchExecutor extends ScheduledThreadPoolExecutor {

    public ClickHouseBatchExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
    }
}
