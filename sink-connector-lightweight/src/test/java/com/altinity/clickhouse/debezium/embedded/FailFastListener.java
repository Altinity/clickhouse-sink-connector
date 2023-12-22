package com.altinity.clickhouse.debezium.embedded;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public class FailFastListener extends RunListener {

    public void testFailure(Failure failure) throws Exception {
        System.err.println("FAILURE: " + failure);
        System.exit(-1);
    }

    @Override
    public void testFinished(Description description) throws Exception {
        System.exit(-1);
    }
}