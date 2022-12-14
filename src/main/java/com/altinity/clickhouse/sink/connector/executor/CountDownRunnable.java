package com.altinity.clickhouse.sink.connector.executor;

import org.apache.kafka.connect.errors.ConnectException;
import java.util.concurrent.CountDownLatch;
/**
 * A Runnable that counts down, and then waits for the countdown to be finished.
 */
public class CountDownRunnable implements Runnable {
    private CountDownLatch countDownLatch;

    public CountDownRunnable(CountDownLatch countDownLatch) {
        this.countDownLatch = countDownLatch;
    }

    @Override
    public void run() {
        countDownLatch.countDown();
        try {
            /*
            * Hog this thread until ALL threads are finished counting down.  This is needed so that
            * this thread doesn't start processing another countdown. If countdown tasks are holding onto
            * all the threads, then we know that nothing that went in before the countdown is still
            * processing.
            */
            countDownLatch.await();
        } catch (InterruptedException err) {
            throw new ConnectException("Thread interrupted while waiting for countdown.", err);
        }
    }
    
}
