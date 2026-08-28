package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for issue #1253 -- the connection-pool cache is shared
 * process-wide but was built on plain HashMap and HashSet with no
 * synchronization anywhere in the class.
 *
 * <p>The maps are static, so every thread in the process shares them, and
 * three distinct threads reach them:
 *
 * <ul>
 *   <li>the pool threads: DebeziumChangeEventCapture creates one
 *       ClickHouseBatchRunnable per thread on a ClickHouseBatchExecutor
 *       (a ScheduledThreadPoolExecutor) sized by thread.pool.size, default
 *       10, and each one builds a DbWriter, which reaches HikariDbSource
 *       through BaseDbWriter;</li>
 *   <li>the Debezium event thread, via DebeziumChangeEventCapture directly;</li>
 *   <li>the DDL path, via DBMetadata.</li>
 * </ul>
 *
 * <p>Concurrent put on a HashMap can corrupt it: resize is not atomic, and
 * two threads resizing together can drop entries or, on older JDKs, leave a
 * cycle in a bucket chain that makes a later get spin forever. The failure is
 * timing-dependent and rare, which is what makes it worth pinning
 * structurally rather than only by racing threads -- a race test that happens
 * to pass proves nothing.
 *
 * <p>The pool-key defect (keying by database name alone, so two servers under
 * one name evicted each other) is already fixed on develop and is not what
 * this test covers.
 */
public class HikariDbSourceConcurrencyTest {

    /**
     * The cache fields must be concurrent types.
     *
     * <p>Asserted structurally, on purpose. The corruption this prevents
     * needs a specific interleaving during a resize; a test that spawns
     * threads and sees no corruption has not shown the code is safe, only
     * that it did not lose the race that run. The type is the invariant.
     */
    @Test
    @DisplayName("#1253 the shared pool caches are concurrent collections")
    void poolCaches_areConcurrentCollections() throws Exception {
        assertConcurrentField("instance", Map.class);
        assertConcurrentField("currentKey", Map.class);
        assertConcurrentField("liveEndpoints", Set.class);
        assertConcurrentField("deadEndpoints", Set.class);
    }

    /**
     * A field that is written by many threads and read by many others must
     * not be a plain java.util collection.
     *
     * @param name     the static field to inspect.
     * @param expected the collection interface it should implement.
     * @throws Exception if the field does not exist.
     */
    private static void assertConcurrentField(String name, Class<?> expected)
            throws Exception {
        Field field = HikariDbSource.class.getDeclaredField(name);
        field.setAccessible(true);

        assertTrue(Modifier.isStatic(field.getModifiers()),
                name + " is expected to be static (process-wide shared state)");

        Object value = field.get(null);
        assertTrue(expected.isInstance(value),
                name + " should be a " + expected.getSimpleName()
                        + ", was: " + value);

        String type = value.getClass().getName();
        assertTrue(type.startsWith("java.util.concurrent."),
                name + " is shared by the batch pool threads, the Debezium "
                        + "event thread and the DDL path, but is a "
                        + type + ". Concurrent mutation of a plain "
                        + "java.util collection can corrupt it during resize.");
    }

    /**
     * Control: the dead members are gone.
     *
     * <p>connectionPool was declared and never referenced anywhere in the
     * class, and the instance field databaseName was never written -- the
     * class is never instantiated, its constructor body was commented out.
     * Dead state next to live state is what makes a concurrency review of
     * this file harder than it needs to be.
     */
    @Test
    @DisplayName("#1253 control -- the unused cache field and instance state are gone")
    void deadMembers_areRemoved() {
        for (Field field : HikariDbSource.class.getDeclaredFields()) {
            assertTrue(!"connectionPool".equals(field.getName()),
                    "connectionPool was never read or written; it should not "
                            + "survive as apparent shared state");
            if (!Modifier.isStatic(field.getModifiers())) {
                throw new AssertionError(
                        "HikariDbSource is never instantiated, so a non-static "
                                + "field can never be written: "
                                + field.getName());
            }
        }
    }
}
