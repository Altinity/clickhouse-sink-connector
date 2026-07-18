package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Auto-detected JUnit 5 extension that resets process-wide static state after every test.
 *
 * <p>The lightweight IT suite runs in a single forked surefire JVM
 * ({@code forkCount=1, reuseForks=true}). The static ClickHouse test container is stopped
 * after each test and restarted (on a NEW mapped port) by the next one, while
 * {@link HikariDbSource} caches connection pools in a static map keyed by database name.
 * When a test fails an assertion before its own {@code HikariDbSource.close()} runs, the
 * stale pool (pointing at the previous, now-dead port) survives and poisons every
 * subsequent test with dead/null connections -- turning a single failure into a cascade of
 * "conn/chConn is null" NPEs.
 *
 * <p>Clearing the static pools (and cache-invalidation state) after every test isolates
 * each test so one failure can no longer cascade. Unlike a base-class {@code @AfterEach},
 * this extension applies to ALL test classes in the module regardless of their class
 * hierarchy (many ITs do not extend a common base). It is registered via
 * {@code META-INF/services} with auto-detection enabled in {@code junit-platform.properties}.
 */
public class ResetSharedStateExtension implements AfterEachCallback {

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
}
