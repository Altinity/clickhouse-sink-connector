package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Auto-detected JUnit 5 extension that resets process-wide static state so tests cannot
 * leak resources into each other in the shared surefire JVM.
 *
 * <p>The lightweight IT suite runs in a single forked JVM ({@code forkCount=1,
 * reuseForks=true}), so any process-wide static state survives across tests and classes.
 * Two leaks turned isolated failures into large cascades:
 *
 * <ul>
 *   <li><b>Connection pools ({@code afterEach}).</b> The static ClickHouse test container is
 *   stopped after each test and restarted (on a NEW mapped port) by the next one, while
 *   {@link HikariDbSource} caches connection pools in a static map keyed by database name.
 *   When a test fails an assertion before its own {@code HikariDbSource.close()} runs, the
 *   stale pool (pointing at the previous, now-dead port) survives and poisons every
 *   subsequent test with dead/null connections ("conn/chConn is null" NPEs). Clearing the
 *   static pools (and cache-invalidation state) after every test isolates each one.</li>
 *
 *   <li><b>REST API server ({@code afterAll}).</b> The status/control REST API is a static
 *   {@link DebeziumEmbeddedRestApi} Javalin server bound to port 7000.
 *   {@code ClickHouseDebeziumEmbeddedApplication.stop()} does not stop it, and some tests
 *   (e.g. the REST API test) start it in {@code @BeforeAll} without stopping it, leaving port
 *   7000 bound. The next test class that starts the server then fails with
 *   "Port already in use ... 7000". Stopping the server after every test class releases the
 *   port. This is done in {@code afterAll} (not {@code afterEach}) so tests that share one
 *   server across multiple methods are not disrupted mid-class.</li>
 * </ul>
 *
 * <p>Applies to ALL test classes in the module regardless of class hierarchy (many ITs do
 * not extend a common base). Registered via {@code META-INF/services} with auto-detection
 * enabled in {@code junit-platform.properties}.
 */
public class ResetSharedStateExtension implements AfterEachCallback, AfterAllCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            HikariDbSource.close();
        } catch (Exception e) {
            // Best-effort cleanup: never let teardown mask the real test result.
        }
        try {
            CacheInvalidationManager.getInstance().clearAll();
        } catch (Exception e) {
            // Best-effort cleanup.
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        try {
            DebeziumEmbeddedRestApi.stop();
        } catch (Exception e) {
            // Best-effort cleanup: release port 7000 for the next test class.
        }
    }
}
