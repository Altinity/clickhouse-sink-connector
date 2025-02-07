package com.altinity.clickhouse.sink.connector.executor;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class ClickHouseBatchExecutor extends ScheduledThreadPoolExecutor {

    boolean isPaused = false;
    public ClickHouseBatchExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);


    }

    public void pause() {
        isPaused = true;
    }

    @Override
    public void beforeExecute(Thread t, Runnable r) {
            while (isPaused) {
                try {
                    TimeUnit.MILLISECONDS.sleep(100); // Polling
                } catch (InterruptedException ie) {
                    t.interrupt();
                }
            }

    }
    public void resume() {
        isPaused = false;
    }
}
