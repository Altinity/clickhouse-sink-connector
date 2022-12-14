package com.altinity.clickhouse.sink.connector.executor;

// import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clickhouse.client.ClickHouseException;

/** 
public class ClickHouseBatchExecutor extends ScheduledThreadPoolExecutor {


    public ClickHouseBatchExecutor(int corePoolSize) {
        super(corePoolSize);
    }
}
**/

public class ClickHouseBatchExecutor extends ThreadPoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseBatchExecutor.class);
  
    private final AtomicReference<Throwable> encounteredError = new AtomicReference<>();
  
    /**
     * @param workQueue the queue for storing tasks.
     */
    public ClickHouseBatchExecutor(int corePoolSize, BlockingQueue<Runnable> workQueue) {
      super(corePoolSize,corePoolSize,
            // the following line is irrelevant because the core and max thread counts are the same.
            1, TimeUnit.SECONDS,
            workQueue);
    }

  
    /**
     * Wait for all the currently queued tasks to complete, and then return.
     * @throws InterruptedException if interrupted while waiting.
     */
    public void awaitCurrentTasks() throws InterruptedException{
      /*
       * create CountDownRunnables equal to the number of threads in the pool and add them to the
       * queue. Then wait for all CountDownRunnables to complete. This way we can be sure that all
       * tasks added before this method was called are complete.
       */
      int maximumPoolSize = getMaximumPoolSize();
      CountDownLatch countDownLatch = new CountDownLatch(maximumPoolSize);
      for (int i = 0; i < maximumPoolSize; i++) {
        execute(new CountDownRunnable(countDownLatch));
      }
      countDownLatch.await();
    //   maybeThrowEncounteredError();
    }

    // public void maybeThrowEncounteredError() {
    //   Optional.ofNullable(encounteredError.get()).ifPresent(t -> {
    //     throw new ClickHouseException(-1, "A write thread has failed with an unrecoverable error", t);
    //   });
    // }
  }