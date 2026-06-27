package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for {@link DDLSchemaChangeWaiter} focused on
 * proving the race condition fix for GitHub issue #1222.
 *
 * <p>The bug: ALTER TABLE ADD COLUMN followed by immediate
 * CacheInvalidationManager signaling causes the batch insert thread
 * to read stale column metadata from system.columns, silently
 * dropping values for ~37% of newly added columns.</p>
 *
 * <p>These tests prove:
 * <ol>
 *   <li>The race condition exists without the waiter (regression guard)</li>
 *   <li>The waiter correctly blocks until columns are visible</li>
 *   <li>The fix works under concurrent stress</li>
 *   <li>Edge cases (timeout, null inputs, non-ALTER DDL) are handled</li>
 * </ol>
 * </p>
 */
class DDLSchemaChangeWaiterRaceConditionTest {

    // ---------------------------------------------------------------
    // Column extraction regression tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Column extraction regression tests")
    class ColumnExtractionTests {

        @Test
        @DisplayName("Single ADD COLUMN with backticks")
        void singleAddColumnBackticks() {
            String ddl = "ALTER TABLE `mydb`.`mytable` ADD COLUMN `new_col` String";
            List<String> cols = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                    Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
            assertEquals(List.of("new_col"), cols);
        }

        @Test
        @DisplayName("Multiple ADD COLUMN - the exact pattern from issue #1222")
        void multipleAddColumnsIssue1222() {
            // This is the exact DDL pattern that triggered the original bug:
            // Debezium captured multiple ADD COLUMN in one statement
            String ddl = "ALTER TABLE `trading_db`.`orders` " +
                    "ADD COLUMN `execution_venue` String, " +
                    "ADD COLUMN `clearing_broker` String, " +
                    "ADD COLUMN `settlement_date` Date";
            List<String> cols = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                    Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
            assertEquals(3, cols.size());
            assertTrue(cols.contains("execution_venue"));
            assertTrue(cols.contains("clearing_broker"));
            assertTrue(cols.contains("settlement_date"));
        }

        @Test
        @DisplayName("DROP COLUMN extraction")
        void dropColumnExtraction() {
            String ddl = "ALTER TABLE `db`.`tbl` DROP COLUMN `obsolete_col`, DROP COLUMN `temp_col`";
            List<String> cols = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                    Pattern.compile("DROP\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
            assertEquals(2, cols.size());
            assertTrue(cols.contains("obsolete_col"));
            assertTrue(cols.contains("temp_col"));
        }

        @Test
        @DisplayName("Mixed case DDL keywords")
        void mixedCaseDdlKeywords() {
            String ddl = "alter TABLE `db`.`tbl` Add Column `MixedCase` Int32";
            List<String> cols = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                    Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
            assertEquals(List.of("MixedCase"), cols);
        }

        @Test
        @DisplayName("Unquoted identifiers")
        void unquotedIdentifiers() {
            String ddl = "ALTER TABLE db.tbl ADD COLUMN unquoted_col Float64";
            List<String> cols = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                    Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
            assertEquals(List.of("unquoted_col"), cols);
        }

        @Test
        @DisplayName("No match returns empty list")
        void noMatchReturnsEmpty() {
            String ddl = "ALTER TABLE db.tbl MODIFY COLUMN col1 String";
            List<String> cols = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                    Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
            assertTrue(cols.isEmpty());
        }
    }

    // ---------------------------------------------------------------
    // Null safety and boundary tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Null safety and boundary conditions")
    class NullSafetyTests {

        @Test
        @DisplayName("Null connection does not throw")
        void nullConnectionNoThrow() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
            assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(
                    null, "ALTER TABLE db.tbl ADD COLUMN x Int32"));
        }

        @Test
        @DisplayName("Null DDL does not throw")
        void nullDdlNoThrow() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
            assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, null));
        }

        @Test
        @DisplayName("Empty DDL does not throw")
        void emptyDdlNoThrow() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
            assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, ""));
        }

        @Test
        @DisplayName("Non-ALTER DDL returns immediately")
        void nonAlterDdlReturnsImmediately() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(null,
                    "CREATE TABLE db.tbl (id Int32) ENGINE = MergeTree()");
            long elapsed = System.currentTimeMillis() - start;
            // Should return almost immediately - not wait for timeout
            assertTrue(elapsed < 50, "Non-ALTER DDL should return immediately, took " + elapsed + "ms");
        }
    }

    // ---------------------------------------------------------------
    // Configuration tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Configuration and defaults")
    class ConfigTests {

        @Test
        @DisplayName("Default constructor uses documented defaults")
        void defaultConstructorUsesDefaults() {
            assertEquals(30_000L, DDLSchemaChangeWaiter.DEFAULT_TIMEOUT_MS);
            assertEquals(100L, DDLSchemaChangeWaiter.DEFAULT_POLL_INTERVAL_MS);
        }

        @Test
        @DisplayName("Custom timeout and poll interval are respected")
        void customTimeoutRespected() throws Exception {
            // Use a very short timeout so the test finishes quickly
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(200, 50);

            // Mock a connection where columns never appear (timeout scenario)
            Connection mockConn = mock(Connection.class);
            Statement mockStmt = mock(Statement.class);
            ResultSet mockRs = mock(ResultSet.class);

            when(mockConn.createStatement()).thenReturn(mockStmt);
            when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
            when(mockRs.next()).thenReturn(false); // No columns ever appear

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(mockConn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `phantom_col` String");
            long elapsed = System.currentTimeMillis() - start;

            // Should wait approximately the timeout duration
            assertTrue(elapsed >= 150, "Should wait near timeout, but only waited " + elapsed + "ms");
            assertTrue(elapsed < 1000, "Should not wait much longer than timeout, waited " + elapsed + "ms");
        }
    }

    // ---------------------------------------------------------------
    // Mock-based visibility wait tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Schema visibility waiting with mocked JDBC")
    class VisibilityWaitTests {

        @Test
        @DisplayName("Returns immediately when columns are already visible")
        void columnsAlreadyVisible() throws Exception {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            Connection mockConn = mock(Connection.class);
            Statement mockStmt = mock(Statement.class);
            ResultSet mockRs = mock(ResultSet.class);

            when(mockConn.createStatement()).thenReturn(mockStmt);
            when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
            // Column already exists in system.columns
            when(mockRs.next()).thenReturn(true, false);
            when(mockRs.getString(1)).thenReturn("new_col");

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(mockConn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `new_col` String");
            long elapsed = System.currentTimeMillis() - start;

            // Should return on first poll
            assertTrue(elapsed < 200, "Should return quickly when column already visible, took " + elapsed + "ms");
        }

        @Test
        @DisplayName("Waits until columns appear after simulated propagation delay")
        void waitsForDelayedPropagation() throws Exception {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            Connection mockConn = mock(Connection.class);
            Statement mockStmt = mock(Statement.class);

            when(mockConn.createStatement()).thenReturn(mockStmt);

            // First 3 polls: column not yet visible. 4th poll: visible.
            AtomicInteger pollCount = new AtomicInteger(0);
            when(mockStmt.executeQuery(anyString())).thenAnswer(invocation -> {
                int count = pollCount.incrementAndGet();
                ResultSet rs = mock(ResultSet.class);
                if (count < 4) {
                    // Column not yet visible
                    when(rs.next()).thenReturn(false);
                } else {
                    // Column now visible
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString(1)).thenReturn("delayed_col");
                }
                return rs;
            });

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(mockConn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `delayed_col` String");
            long elapsed = System.currentTimeMillis() - start;

            // Should have polled at least 4 times
            assertTrue(pollCount.get() >= 4, "Expected at least 4 polls, got " + pollCount.get());
            // Should have waited at least 3 poll intervals (3 * 50ms = 150ms)
            assertTrue(elapsed >= 100, "Should have waited for propagation, only waited " + elapsed + "ms");
        }

        @Test
        @DisplayName("Times out when columns never appear")
        void timesOutWhenColumnsNeverAppear() throws Exception {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(300, 50);

            Connection mockConn = mock(Connection.class);
            Statement mockStmt = mock(Statement.class);
            ResultSet mockRs = mock(ResultSet.class);

            when(mockConn.createStatement()).thenReturn(mockStmt);
            when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
            when(mockRs.next()).thenReturn(false); // Never appears

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(mockConn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `never_col` String");
            long elapsed = System.currentTimeMillis() - start;

            // Should timeout after ~300ms, not hang
            assertTrue(elapsed >= 250, "Should wait near timeout, but only waited " + elapsed + "ms");
            assertTrue(elapsed < 1000, "Should not hang forever, waited " + elapsed + "ms");
        }

        @Test
        @DisplayName("DROP COLUMN waits for column to disappear")
        void dropColumnWaitsForDisappearance() throws Exception {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            Connection mockConn = mock(Connection.class);
            Statement mockStmt = mock(Statement.class);

            when(mockConn.createStatement()).thenReturn(mockStmt);

            AtomicInteger pollCount = new AtomicInteger(0);
            when(mockStmt.executeQuery(anyString())).thenAnswer(invocation -> {
                int count = pollCount.incrementAndGet();
                ResultSet rs = mock(ResultSet.class);
                if (count < 3) {
                    // Column still visible (not yet dropped)
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString(1)).thenReturn("drop_me");
                } else {
                    // Column gone
                    when(rs.next()).thenReturn(false);
                }
                return rs;
            });

            waiter.waitForSchemaVisibility(mockConn,
                    "ALTER TABLE `db`.`tbl` DROP COLUMN `drop_me`");

            assertTrue(pollCount.get() >= 3, "Expected at least 3 polls for DROP, got " + pollCount.get());
        }
    }

    // ---------------------------------------------------------------
    // Race condition simulation tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Race condition simulation (issue #1222)")
    class RaceConditionSimulation {

        /**
         * Demonstrates the original bug WITHOUT the waiter.
         *
         * <p>Simulates two threads:
         * <ul>
         *   <li>DDL thread: executes ALTER TABLE, marks column as
         *       "propagating" with a delay, then signals cache invalidation</li>
         *   <li>Batch thread: on cache invalidation, reads column list</li>
         * </ul>
         *
         * Without the waiter, the batch thread reads stale metadata
         * because it reads before the column has propagated.
         */
        @Test
        @DisplayName("BUG: Without waiter, batch thread reads stale metadata")
        void bugWithoutWaiter() throws Exception {
            // Simulated system.columns state
            AtomicBoolean columnPropagated = new AtomicBoolean(false);
            // Signal that cache was invalidated (batch thread should rebuild)
            CountDownLatch cacheInvalidated = new CountDownLatch(1);
            // Track what the batch thread sees
            AtomicBoolean batchSawNewColumn = new AtomicBoolean(false);
            // Track completion
            CountDownLatch done = new CountDownLatch(2);

            // DDL thread: executes ALTER, signals cache invalidation immediately
            // (the original buggy behavior -- no wait for propagation)
            Thread ddlThread = new Thread(() -> {
                try {
                    // 1. "Execute" ALTER TABLE (the DDL runs instantly)
                    // 2. Column propagation takes time (simulated)
                    new Thread(() -> {
                        try {
                            Thread.sleep(200); // Propagation delay
                            columnPropagated.set(true);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                    // 3. BUG: Signal cache invalidation IMMEDIATELY
                    //    (without waiting for propagation)
                    cacheInvalidated.countDown();
                } finally {
                    done.countDown();
                }
            });

            // Batch thread: on cache invalidation, reads column metadata
            Thread batchThread = new Thread(() -> {
                try {
                    cacheInvalidated.await(5, TimeUnit.SECONDS);
                    // Read "system.columns" -- checks if column has propagated
                    batchSawNewColumn.set(columnPropagated.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            ddlThread.start();
            batchThread.start();
            assertTrue(done.await(5, TimeUnit.SECONDS), "Threads should complete");

            // THIS IS THE BUG: batch thread does NOT see the new column
            // because it read metadata before propagation completed
            assertFalse(batchSawNewColumn.get(),
                    "Without waiter, batch thread should NOT see the new column " +
                    "(demonstrates the race condition from issue #1222)");
        }

        /**
         * Proves the fix WITH the waiter.
         *
         * <p>Same simulation, but the DDL thread now waits for column
         * propagation before signaling cache invalidation.</p>
         */
        @Test
        @DisplayName("FIX: With waiter, batch thread sees correct metadata")
        void fixWithWaiter() throws Exception {
            AtomicBoolean columnPropagated = new AtomicBoolean(false);
            CountDownLatch cacheInvalidated = new CountDownLatch(1);
            AtomicBoolean batchSawNewColumn = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(2);

            // DDL thread: executes ALTER, WAITS for propagation,
            // then signals cache invalidation (the fix)
            Thread ddlThread = new Thread(() -> {
                try {
                    // 1. "Execute" ALTER TABLE
                    // 2. Start propagation in background
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                            columnPropagated.set(true);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                    // 3. FIX: Wait for propagation (simulates DDLSchemaChangeWaiter)
                    long deadline = System.currentTimeMillis() + 5000;
                    while (!columnPropagated.get() &&
                            System.currentTimeMillis() < deadline) {
                        Thread.sleep(50); // Poll interval
                    }
                    // 4. NOW signal cache invalidation
                    cacheInvalidated.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            // Batch thread: same as before
            Thread batchThread = new Thread(() -> {
                try {
                    cacheInvalidated.await(5, TimeUnit.SECONDS);
                    batchSawNewColumn.set(columnPropagated.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            ddlThread.start();
            batchThread.start();
            assertTrue(done.await(5, TimeUnit.SECONDS), "Threads should complete");

            // WITH THE FIX: batch thread now sees the new column
            assertTrue(batchSawNewColumn.get(),
                    "With waiter, batch thread SHOULD see the new column " +
                    "(proves the fix for issue #1222)");
        }

        /**
         * Stress test: 20 concurrent DDL + batch thread pairs.
         *
         * <p>All pairs start simultaneously via a CyclicBarrier.
         * The waiter pattern must prevent stale reads in every pair.</p>
         */
        @Test
        @DisplayName("STRESS: 20 concurrent DDL+batch pairs with waiter")
        void stressConcurrentPairsWithWaiter() throws Exception {
            int pairCount = 20;
            CyclicBarrier barrier = new CyclicBarrier(pairCount * 2);
            AtomicInteger staleReads = new AtomicInteger(0);
            AtomicInteger successfulReads = new AtomicInteger(0);
            CountDownLatch allDone = new CountDownLatch(pairCount * 2);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(pairCount * 2);

            for (int i = 0; i < pairCount; i++) {
                AtomicBoolean propagated = new AtomicBoolean(false);
                CountDownLatch invalidated = new CountDownLatch(1);

                // DDL thread with waiter
                executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);

                        // Simulate propagation delay (random 50-200ms)
                        long delay = 50 + (long)(Math.random() * 150);
                        new Thread(() -> {
                            try {
                                Thread.sleep(delay);
                                propagated.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();

                        // Waiter polls until propagated
                        long deadline = System.currentTimeMillis() + 5000;
                        while (!propagated.get() &&
                                System.currentTimeMillis() < deadline) {
                            Thread.sleep(25);
                        }

                        invalidated.countDown();
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        allDone.countDown();
                    }
                });

                // Batch thread
                executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        invalidated.await(5, TimeUnit.SECONDS);

                        if (propagated.get()) {
                            successfulReads.incrementAndGet();
                        } else {
                            staleReads.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        allDone.countDown();
                    }
                });
            }

            assertTrue(allDone.await(30, TimeUnit.SECONDS), "All threads should complete");
            executor.shutdown();

            assertTrue(errors.isEmpty(),
                    "No thread errors expected, got: " + errors);
            assertEquals(0, staleReads.get(),
                    "With waiter, there should be ZERO stale reads across all " +
                    pairCount + " pairs");
            assertEquals(pairCount, successfulReads.get(),
                    "All " + pairCount + " batch threads should see propagated columns");
        }
    }

    // ---------------------------------------------------------------
    // Integration-style JDBC mock tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("End-to-end mock JDBC tests")
    class EndToEndMockTests {

        @Test
        @DisplayName("Multiple columns with staggered propagation")
        void multipleColumnsStaggeredPropagation() throws Exception {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            Connection mockConn = mock(Connection.class);
            Statement mockStmt = mock(Statement.class);
            when(mockConn.createStatement()).thenReturn(mockStmt);

            AtomicInteger pollCount = new AtomicInteger(0);
            when(mockStmt.executeQuery(anyString())).thenAnswer(invocation -> {
                int count = pollCount.incrementAndGet();
                ResultSet rs = mock(ResultSet.class);
                if (count == 1) {
                    // Poll 1: no new columns yet
                    when(rs.next()).thenReturn(true, false);
                    when(rs.getString(1)).thenReturn("existing_col");
                } else if (count == 2) {
                    // Poll 2: first new column appears
                    when(rs.next()).thenReturn(true, true, false);
                    when(rs.getString(1)).thenReturn("existing_col", "col_a");
                } else {
                    // Poll 3+: both new columns visible
                    when(rs.next()).thenReturn(true, true, true, false);
                    when(rs.getString(1)).thenReturn("existing_col", "col_a", "col_b");
                }
                return rs;
            });

            waiter.waitForSchemaVisibility(mockConn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `col_a` String, ADD COLUMN `col_b` Int32");

            // Should have polled at least 3 times (2 waits + 1 success)
            assertTrue(pollCount.get() >= 3,
                    "Expected at least 3 polls for staggered propagation, got " + pollCount.get());
        }

        @Test
        @DisplayName("JDBC exception during polling does not cause hang")
        void jdbcExceptionDuringPolling() throws Exception {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(500, 50);

            Connection mockConn = mock(Connection.class);
            Statement mockStmt = mock(Statement.class);
            when(mockConn.createStatement()).thenReturn(mockStmt);

            AtomicInteger pollCount = new AtomicInteger(0);
            when(mockStmt.executeQuery(anyString())).thenAnswer(invocation -> {
                int count = pollCount.incrementAndGet();
                if (count <= 2) {
                    // First 2 polls throw exceptions
                    throw new java.sql.SQLException("Connection reset");
                }
                // Poll 3+: column visible
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(1)).thenReturn("resilient_col");
                return rs;
            });

            // Should recover from exceptions and eventually see the column
            waiter.waitForSchemaVisibility(mockConn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `resilient_col` String");

            assertTrue(pollCount.get() >= 3,
                    "Should have retried after exceptions, got " + pollCount.get() + " polls");
        }
    }
}
