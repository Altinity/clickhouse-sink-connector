package com.altinity.clickhouse.sink.connector.config;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 03.02 section 6, FM-03.02-3: {@code thread.pool.size} has no range
 * validator. With {@code 0}, {@code DebeziumChangeEventCapture.setupProcessingThread}
 * takes the legacy branch and its scheduling loop ({@code for i < 0}) schedules
 * NO worker: batches are handed to the shared queue and nothing ever drains it.
 * {@code workerFutures} is empty, so the dead-worker check has nothing to
 * inspect; the stall is silent until the handoff hard cap
 * ({@code sink.connector.handoff.max.outstanding.records}, then
 * {@code sink.connector.handoff.wait.timeout.ms} = 10 min) -- and on a quiet
 * source, forever.
 */
public class ThreadPoolSizeValidationTest {

    @Test
    @Disabled("DEFECT FM-03.02-3: thread.pool.size=0 is accepted and starts a connector with no writer thread")
    @DisplayName("thread.pool.size below 1 is rejected when the configuration is loaded")
    public void threadPoolSizeBelowOneIsRejected() {
        Map<String, String> props = new HashMap<>();
        props.put("thread.pool.size", "0");
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(props),
                "a pool of zero writers can never write or acknowledge a batch");
    }
}
