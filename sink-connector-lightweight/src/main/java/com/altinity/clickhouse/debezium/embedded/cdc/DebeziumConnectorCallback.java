package com.altinity.clickhouse.debezium.embedded.cdc;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.DebeziumEngine.ConnectorCallback;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DebeziumConnectorCallback implements the ConnectorCallback
 * interface to provide custom handling for connector and task
 * lifecycle events.
 */
public class DebeziumConnectorCallback implements ConnectorCallback {

    /**
     * Logger instance for this class.
     */
    private static final Logger log = LogManager.getLogger(
            DebeziumConnectorCallback.class);

    /**
     * Default constructor.
     */
    public DebeziumConnectorCallback() {
    }

    /**
     * Called when the connector has started.
     * <p>
     * Invokes the default implementation.
     * </p>
     */
    @Override
    public void connectorStarted() {
        DebeziumEngine.ConnectorCallback.super.connectorStarted();
    }

    /**
     * Called when the connector has stopped.
     * <p>
     * Invokes the default implementation.
     * </p>
     */
    @Override
    public void connectorStopped() {
        DebeziumEngine.ConnectorCallback.super.connectorStopped();
    }

    /**
     * Called when a task has started.
     * <p>
     * Invokes the default implementation.
     * </p>
     */
    @Override
    public void taskStarted() {
        DebeziumEngine.ConnectorCallback.super.taskStarted();
    }

    /**
     * Called when a task has stopped.
     * <p>
     * Invokes the default implementation.
     * </p>
     */
    @Override
    public void taskStopped() {
        DebeziumEngine.ConnectorCallback.super.taskStopped();
    }
}
