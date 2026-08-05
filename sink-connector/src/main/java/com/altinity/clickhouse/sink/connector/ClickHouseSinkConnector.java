package com.altinity.clickhouse.sink.connector;


import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.Version;
import org.apache.kafka.common.config.Config;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.sink.SinkConnector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The main connector class for the ClickHouse sink connector, which extends
 * {@link SinkConnector} for integration with Kafka Connect. This connector
 * is responsible for creating and configuring tasks that will write data
 * to ClickHouse.
 */
public class ClickHouseSinkConnector extends SinkConnector {

    /**
     * Interval (in milliseconds) to sleep when waiting for the connector to
     * become ready.
     */
    private static final int THREAD_SLEEP_INTERVAL_MS = 5000;

    /**
     * Holds the configuration map for the connector, containing key-value
     * pairs set by the user or the system.
     */
    private Map<String, String> config;

    /**
     * Logger for this class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseSinkConnector.class);

    /**
     * Flag indicating if the connector is ready to accept data.
     */
    private boolean ready;

    /**
     * Default constructor. Initializes the ClickHouseSinkConnector and sets
     * the {@code ready} flag to false.
     */
    public ClickHouseSinkConnector() {
        log.info("ClickHouseSinkConnector()");
        // Connector is not yet ready to accept data
        this.ready = false;
    }

    /**
     * Starts the connector by reading the given configuration, storing it,
     * and initializing the metrics subsystem. Once this method completes,
     * the connector is considered ready to accept data.
     *
     * @param conf the configuration map containing all necessary settings
     */
    @Override
    public void start(final Map<String, String> conf) {
        log.info("start()");
        // Instantiate main connector's config and fill it with default values
        this.config = conf;
        // From now on connector is ready to accept data
        this.ready = true;

        // Initialize Metrics
        Metrics.initialize(
                this.config.get(ClickHouseSinkConnectorConfigVariables
                        .ENABLE_METRICS.toString()),
                this.config.get(ClickHouseSinkConnectorConfigVariables
                        .METRICS_ENDPOINT_PORT.toString())
        );
    }

    /**
     * Stops the connector, marking it as no longer ready to accept data,
     * and stops the metrics subsystem.
     */
    @Override
    public void stop() {
        log.info("stop()");
        // Connector is no more ready to accept data
        this.ready = false;
        Metrics.stop();
    }

    /**
     * Returns the {@link Task} implementation class for this connector.
     *
     * @return the task class that this connector uses
     */
    @Override
    public Class<? extends Task> taskClass() {
        return ClickHouseSinkTask.class;
    }

    /**
     * Provides each of the {@code maxTasks} with its own configuration map.
     * This method blocks until the connector is ready.
     *
     * @param maxTasks The maximum number of tasks to create
     * @return A list of configuration maps, one per task
     */
    @Override
    public List<Map<String, String>> taskConfigs(final int maxTasks) {
        while (!this.ready) {
            try {
                Thread.sleep(THREAD_SLEEP_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Create personal configuration for each task
        List<Map<String, String>> configs = new ArrayList<>(maxTasks);
        for (int i = 0; i < maxTasks; i++) {
            // Instantiate config from the main connector's config
            // and personalize with additional params
            Map<String, String> conf = new HashMap<>(this.config);
            conf.put(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString(),
                    Integer.toString(i));
            configs.add(conf);
        }
        return configs;
    }

    /**
     * Defines the configuration for this connector, including validation.
     *
     * @return The {@link ConfigDef} describing the connector's configuration
     *         properties
     */
    @Override
    public ConfigDef config() {
        return ClickHouseSinkConnectorConfig.newConfigDef();
    }

    /**
     * Validates the given connector configuration. Logs the validation
     * process and can be extended to verify connectivity to a ClickHouse
     * server and the validity of the target database and schema.
     *
     * @param conf The configuration map to validate
     * @return The validated {@link Config} object
     */
    @Override
    public Config validate(Map<String, String> conf) {
        log.debug("validate()");
        Config result = super.validate(conf);
        log.info("Config validated");

        // ToDo: Also validate connection to clickhouse server
        // and check if database and schema is valid.

        return result;
    }

    /**
     * Returns the version of the connector.
     *
     * @return The current version string of the connector
     */
    @Override
    public String version() {
        return Version.VERSION;
    }
}
