package com.altinity.clickhouse.debezium.embedded.metrics;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLBaseIT;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Integration test to validate the Prometheus /metrics endpoint exposed by the Metrics class.
 * This test verifies that the metrics endpoint is properly exposed and returns valid metrics data.
 */
@Testcontainers
@DisplayName("Integration Test to validate Prometheus /metrics endpoint")
public class MetricsEndpointIT extends DDLBaseIT {

    private static final int METRICS_PORT = 8084;
    private static final String METRICS_URL = "http://localhost:" + METRICS_PORT + "/metrics";

    @BeforeEach
    @Override
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_add_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(15000);
    }

    @Override
    protected Properties getDebeziumProperties() throws Exception {
        Properties baseProps = super.getDebeziumProperties();
        
        // Enable metrics and set port
        baseProps.put("metrics.enable", "true");
        baseProps.put("metrics.port", String.valueOf(METRICS_PORT));
        
        return baseProps;
    }

    /**
     * Test to validate that the /metrics endpoint is exposed and returns valid Prometheus metrics.
     * This test:
     * 1. Initializes metrics with enabled flag
     * 2. Starts the Debezium change event capture
     * 3. Makes an HTTP GET request to the /metrics endpoint
     * 4. Validates that the response is not empty
     * 5. Validates that the response contains Prometheus format metrics
     * 6. Validates the Content-Type header for Prometheus 3.x compatibility
     */
    @Test
    @DisplayName("Validate /metrics endpoint returns valid Prometheus metrics")
    public void testMetricsEndpoint() throws Exception {
        // Initialize metrics
        Metrics.initialize("true", String.valueOf(METRICS_PORT));
        
        // Start Debezium engine in a separate thread
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for the engine to start and metrics to be initialized
        Thread.sleep(10000);

        // Make HTTP GET request to /metrics endpoint
        HttpURLConnection connection = null;
        StringBuilder response = new StringBuilder();
        
        try {
            URL url = new URL(METRICS_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            
            // Assert that the HTTP response code is 200
            Assert.assertEquals("Expected HTTP 200 OK response from /metrics endpoint", 
                    200, responseCode);

            // Validate Content-Type header for Prometheus 3.x compatibility
            String contentType = connection.getHeaderField("Content-Type");
            Assert.assertNotNull("Content-Type header should be present", contentType);
            Assert.assertTrue("Content-Type should be text/plain for Prometheus", 
                    contentType.contains("text/plain"));

            // Read the response
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            }

            String metricsResponse = response.toString();
            
            // Assert that the response is not empty
            Assert.assertFalse("Metrics response should not be empty", 
                    metricsResponse.isEmpty());
            
            // Validate that the response contains at least some standard metrics
            // JVM metrics should always be present
            Assert.assertTrue("Response should contain JVM memory metrics",
                    metricsResponse.contains("jvm_memory_"));
            
            Assert.assertTrue("Response should contain JVM thread metrics",
                    metricsResponse.contains("jvm_threads_"));
            
            // Validate the presence of custom ClickHouse metrics (if any data has been processed)
            // These metrics are registered in the Metrics class
            boolean hasCustomMetrics = metricsResponse.contains("clickhouse_sink") ||
                    metricsResponse.contains("HELP") ||
                    metricsResponse.contains("TYPE");
            
            Assert.assertTrue("Response should contain Prometheus metric format indicators (HELP/TYPE)",
                    hasCustomMetrics);

            System.out.println("Metrics endpoint validation successful!");
            System.out.println("Sample metrics output (first 500 chars):");
            System.out.println(metricsResponse.substring(0, 
                    Math.min(500, metricsResponse.length())));

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            
            // Clean up
            if (engine.get() != null) {
                engine.get().stop();
            }
            executorService.shutdown();
            
            // Stop the metrics server
            Metrics.stop();
        }
    }

    /**
     * Test to validate that metrics endpoint is not accessible when metrics are disabled.
     */
    @Test
    @DisplayName("Validate /metrics endpoint is not accessible when metrics are disabled")
    public void testMetricsEndpointDisabled() throws Exception {
        // Initialize metrics with disabled flag
        Metrics.initialize("false", String.valueOf(METRICS_PORT));
        
        // Wait a moment to ensure server would have started if metrics were enabled
        Thread.sleep(2000);

        // Try to make HTTP GET request to /metrics endpoint
        HttpURLConnection connection = null;
        
        try {
            URL url = new URL(METRICS_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);

            // This should fail as the server should not be running
            connection.getResponseCode();
            
            // If we reach here, the server is running when it shouldn't be
            Assert.fail("Metrics endpoint should not be accessible when metrics are disabled");
            
        } catch (Exception e) {
            // Expected: Connection should fail when metrics are disabled
            System.out.println("Expected behavior: Metrics endpoint not accessible when disabled");
            Assert.assertTrue("Expected connection to fail when metrics disabled", true);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            
            // Clean up
            Metrics.stop();
        }
    }
}

