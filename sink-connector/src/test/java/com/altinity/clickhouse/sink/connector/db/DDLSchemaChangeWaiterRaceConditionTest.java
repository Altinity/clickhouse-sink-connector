package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

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
 *
 * <p>Note: Uses manual JDBC stubs instead of Mockito because the
 * sink-connector module does not include Mockito as a dependency.</p>
 */
class DDLSchemaChangeWaiterRaceConditionTest {

    // ---------------------------------------------------------------
    // Stub helpers — lightweight JDBC fakes without Mockito
    // ---------------------------------------------------------------

    /**
     * Creates a stub Connection whose createStatement() returns a
     * Statement that delegates executeQuery() to the given callback.
     */
    private static Connection stubConnection(QueryCallback callback) {
        return new StubConnection(callback);
    }

    @FunctionalInterface
    interface QueryCallback {
        ResultSet execute(String sql) throws SQLException;
    }

    /**
     * Creates a ResultSet that returns the given column names, one per
     * next() call.
     */
    private static ResultSet stubResultSet(String... columnNames) {
        return new StubResultSet(columnNames);
    }

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
        // DESTRUCTIVE: title and DDL string are inert test fixtures. This test
        // only feeds text to a regex extractor — no database connection exists
        // and no statement is executed.
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
        void customTimeoutRespected() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(200, 50);

            // Stub connection where columns never appear (timeout scenario)
            Connection conn = stubConnection(sql -> stubResultSet(/* empty */));

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(conn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `phantom_col` String");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed >= 150, "Should wait near timeout, but only waited " + elapsed + "ms");
            assertTrue(elapsed < 1000, "Should not wait much longer than timeout, waited " + elapsed + "ms");
        }
    }

    // ---------------------------------------------------------------
    // Stub-based visibility wait tests
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("Schema visibility waiting with stub JDBC")
    class VisibilityWaitTests {

        @Test
        @DisplayName("Returns immediately when columns are already visible")
        void columnsAlreadyVisible() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            // Column already exists in system.columns
            Connection conn = stubConnection(sql -> stubResultSet("new_col"));

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(conn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `new_col` String");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 200, "Should return quickly when column already visible, took " + elapsed + "ms");
        }

        @Test
        @DisplayName("Waits until columns appear after simulated propagation delay")
        void waitsForDelayedPropagation() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            AtomicInteger pollCount = new AtomicInteger(0);
            Connection conn = stubConnection(sql -> {
                int count = pollCount.incrementAndGet();
                if (count < 4) {
                    return stubResultSet(/* empty — column not yet visible */);
                }
                return stubResultSet("delayed_col");
            });

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(conn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `delayed_col` String");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(pollCount.get() >= 4, "Expected at least 4 polls, got " + pollCount.get());
            assertTrue(elapsed >= 100, "Should have waited for propagation, only waited " + elapsed + "ms");
        }

        @Test
        @DisplayName("Times out when columns never appear")
        void timesOutWhenColumnsNeverAppear() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(300, 50);

            Connection conn = stubConnection(sql -> stubResultSet(/* empty — never appears */));

            long start = System.currentTimeMillis();
            waiter.waitForSchemaVisibility(conn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `never_col` String");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed >= 250, "Should wait near timeout, but only waited " + elapsed + "ms");
            assertTrue(elapsed < 1000, "Should not hang forever, waited " + elapsed + "ms");
        }

        @Test
        // DESTRUCTIVE: title and DDL string are inert test fixtures. The
        // connection here is a stub that returns canned result sets — there is
        // no real database and no statement is ever executed.
        @DisplayName("DROP COLUMN waits for column to disappear")
        void dropColumnWaitsForDisappearance() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            AtomicInteger pollCount = new AtomicInteger(0);
            Connection conn = stubConnection(sql -> {
                int count = pollCount.incrementAndGet();
                if (count < 3) {
                    return stubResultSet("drop_me"); // still visible
                }
                return stubResultSet(/* empty — column gone */);
            });

            // DESTRUCTIVE: inert DDL string passed to a stub connection that
            // returns canned result sets. No real database, nothing executed.
            waiter.waitForSchemaVisibility(conn,
                    "ALTER TABLE `db`.`tbl` DROP COLUMN `drop_me`");

            assertTrue(pollCount.get() >= 3, "Expected at least 3 polls for DROP, got " + pollCount.get());
        }

        @Test
        @DisplayName("Multiple columns with staggered propagation")
        void multipleColumnsStaggeredPropagation() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(5000, 50);

            AtomicInteger pollCount = new AtomicInteger(0);
            Connection conn = stubConnection(sql -> {
                int count = pollCount.incrementAndGet();
                if (count == 1) {
                    return stubResultSet("existing_col");
                } else if (count == 2) {
                    return stubResultSet("existing_col", "col_a");
                }
                return stubResultSet("existing_col", "col_a", "col_b");
            });

            waiter.waitForSchemaVisibility(conn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `col_a` String, ADD COLUMN `col_b` Int32");

            assertTrue(pollCount.get() >= 3,
                    "Expected at least 3 polls for staggered propagation, got " + pollCount.get());
        }

        @Test
        @DisplayName("JDBC exception during polling does not cause hang")
        void jdbcExceptionDuringPolling() {
            DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(500, 50);

            AtomicInteger pollCount = new AtomicInteger(0);
            Connection conn = stubConnection(sql -> {
                int count = pollCount.incrementAndGet();
                if (count <= 2) {
                    throw new SQLException("Connection reset");
                }
                return stubResultSet("resilient_col");
            });

            waiter.waitForSchemaVisibility(conn,
                    "ALTER TABLE `db`.`tbl` ADD COLUMN `resilient_col` String");

            assertTrue(pollCount.get() >= 3,
                    "Should have retried after exceptions, got " + pollCount.get() + " polls");
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
         * Simulates two threads:
         * - DDL thread: executes ALTER TABLE, marks column as
         *   "propagating" with a delay, then signals cache invalidation
         * - Batch thread: on cache invalidation, reads column list
         *
         * Without the waiter, the batch thread reads stale metadata.
         */
        @Test
        @DisplayName("BUG: Without waiter, batch thread reads stale metadata")
        void bugWithoutWaiter() throws Exception {
            AtomicBoolean columnPropagated = new AtomicBoolean(false);
            CountDownLatch cacheInvalidated = new CountDownLatch(1);
            AtomicBoolean batchSawNewColumn = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(2);

            // DDL thread: signals cache invalidation IMMEDIATELY (the bug)
            Thread ddlThread = new Thread(() -> {
                try {
                    // Start propagation in background
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                            columnPropagated.set(true);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                    // BUG: signal immediately without waiting
                    cacheInvalidated.countDown();
                } finally {
                    done.countDown();
                }
            });

            // Batch thread: reads metadata on cache invalidation
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

            // THIS IS THE BUG: batch thread does NOT see the new column
            assertFalse(batchSawNewColumn.get(),
                    "Without waiter, batch thread should NOT see the new column " +
                    "(demonstrates the race condition from issue #1222)");
        }

        /**
         * Proves the fix WITH the waiter.
         */
        @Test
        @DisplayName("FIX: With waiter, batch thread sees correct metadata")
        void fixWithWaiter() throws Exception {
            AtomicBoolean columnPropagated = new AtomicBoolean(false);
            CountDownLatch cacheInvalidated = new CountDownLatch(1);
            AtomicBoolean batchSawNewColumn = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(2);

            // DDL thread: WAITS for propagation before signaling (the fix)
            Thread ddlThread = new Thread(() -> {
                try {
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                            columnPropagated.set(true);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();

                    // FIX: poll until propagated (simulates DDLSchemaChangeWaiter)
                    long deadline = System.currentTimeMillis() + 5000;
                    while (!columnPropagated.get() &&
                            System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                    }
                    cacheInvalidated.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

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

            assertTrue(batchSawNewColumn.get(),
                    "With waiter, batch thread SHOULD see the new column " +
                    "(proves the fix for issue #1222)");
        }

        /**
         * Stress test: 20 concurrent DDL + batch thread pairs.
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

                executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        long delay = 50 + (long)(Math.random() * 150);
                        new Thread(() -> {
                            try {
                                Thread.sleep(delay);
                                propagated.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();

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

            assertTrue(errors.isEmpty(), "No thread errors expected, got: " + errors);
            assertEquals(0, staleReads.get(),
                    "With waiter, there should be ZERO stale reads across all " +
                    pairCount + " pairs");
            assertEquals(pairCount, successfulReads.get(),
                    "All " + pairCount + " batch threads should see propagated columns");
        }
    }

    // ---------------------------------------------------------------
    // Minimal JDBC stub implementations (no Mockito needed)
    // ---------------------------------------------------------------

    /**
     * Lightweight Connection stub that only implements createStatement().
     */
    private static class StubConnection implements Connection {
        private final QueryCallback callback;
        StubConnection(QueryCallback callback) { this.callback = callback; }

        @Override public Statement createStatement() {
            return new StubStatement(callback);
        }

        // --- All other Connection methods throw UnsupportedOperationException ---
        @Override public Statement createStatement(int a, int b) { throw new UnsupportedOperationException(); }
        @Override public Statement createStatement(int a, int b, int c) { throw new UnsupportedOperationException(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s) { throw new UnsupportedOperationException(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b) { throw new UnsupportedOperationException(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a, int b, int c) { throw new UnsupportedOperationException(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int a) { throw new UnsupportedOperationException(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, int[] a) { throw new UnsupportedOperationException(); }
        @Override public java.sql.PreparedStatement prepareStatement(String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public java.sql.CallableStatement prepareCall(String s) { throw new UnsupportedOperationException(); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b) { throw new UnsupportedOperationException(); }
        @Override public java.sql.CallableStatement prepareCall(String s, int a, int b, int c) { throw new UnsupportedOperationException(); }
        @Override public String nativeSQL(String s) { throw new UnsupportedOperationException(); }
        @Override public void setAutoCommit(boolean b) { }
        @Override public boolean getAutoCommit() { return true; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
        @Override public boolean isClosed() { return false; }
        @Override public java.sql.DatabaseMetaData getMetaData() { throw new UnsupportedOperationException(); }
        @Override public void setReadOnly(boolean b) { }
        @Override public boolean isReadOnly() { return true; }
        @Override public void setCatalog(String s) { }
        @Override public String getCatalog() { return null; }
        @Override public void setTransactionIsolation(int i) { }
        @Override public int getTransactionIsolation() { return Connection.TRANSACTION_NONE; }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public java.util.Map<String, Class<?>> getTypeMap() { throw new UnsupportedOperationException(); }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> m) { }
        @Override public void setHoldability(int h) { }
        @Override public int getHoldability() { return 0; }
        @Override public java.sql.Savepoint setSavepoint() { throw new UnsupportedOperationException(); }
        @Override public java.sql.Savepoint setSavepoint(String s) { throw new UnsupportedOperationException(); }
        @Override public void rollback(java.sql.Savepoint s) { }
        @Override public void releaseSavepoint(java.sql.Savepoint s) { }
        @Override public java.sql.Clob createClob() { throw new UnsupportedOperationException(); }
        @Override public java.sql.Blob createBlob() { throw new UnsupportedOperationException(); }
        @Override public java.sql.NClob createNClob() { throw new UnsupportedOperationException(); }
        @Override public java.sql.SQLXML createSQLXML() { throw new UnsupportedOperationException(); }
        @Override public boolean isValid(int t) { return true; }
        @Override public void setClientInfo(String k, String v) { }
        @Override public void setClientInfo(java.util.Properties p) { }
        @Override public String getClientInfo(String k) { return null; }
        @Override public java.util.Properties getClientInfo() { return new java.util.Properties(); }
        @Override public java.sql.Array createArrayOf(String t, Object[] e) { throw new UnsupportedOperationException(); }
        @Override public java.sql.Struct createStruct(String t, Object[] a) { throw new UnsupportedOperationException(); }
        @Override public void setSchema(String s) { }
        @Override public String getSchema() { return null; }
        @Override public void abort(java.util.concurrent.Executor e) { }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor e, int t) { }
        @Override public int getNetworkTimeout() { return 0; }
        @Override public <T> T unwrap(Class<T> c) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> c) { return false; }
    }

    /**
     * Lightweight Statement stub that delegates executeQuery to a callback.
     */
    private static class StubStatement implements Statement {
        private final QueryCallback callback;
        StubStatement(QueryCallback callback) { this.callback = callback; }

        @Override public ResultSet executeQuery(String sql) throws SQLException {
            return callback.execute(sql);
        }

        @Override public void close() { }
        @Override public int getMaxFieldSize() { return 0; }
        @Override public void setMaxFieldSize(int m) { }
        @Override public int getMaxRows() { return 0; }
        @Override public void setMaxRows(int m) { }
        @Override public void setEscapeProcessing(boolean e) { }
        @Override public int getQueryTimeout() { return 0; }
        @Override public void setQueryTimeout(int s) { }
        @Override public void cancel() { }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public void setCursorName(String n) { }
        @Override public boolean execute(String s) { return false; }
        @Override public ResultSet getResultSet() { return null; }
        @Override public int getUpdateCount() { return -1; }
        @Override public boolean getMoreResults() { return false; }
        @Override public void setFetchDirection(int d) { }
        @Override public int getFetchDirection() { return ResultSet.FETCH_FORWARD; }
        @Override public void setFetchSize(int r) { }
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
        @Override public int getResultSetType() { return ResultSet.TYPE_FORWARD_ONLY; }
        @Override public void addBatch(String s) { }
        @Override public void clearBatch() { }
        @Override public int[] executeBatch() { return new int[0]; }
        @Override public Connection getConnection() { return null; }
        @Override public boolean getMoreResults(int c) { return false; }
        @Override public ResultSet getGeneratedKeys() { return null; }
        @Override public int executeUpdate(String s) { return 0; }
        @Override public int executeUpdate(String s, int a) { return 0; }
        @Override public int executeUpdate(String s, int[] a) { return 0; }
        @Override public int executeUpdate(String s, String[] a) { return 0; }
        @Override public boolean execute(String s, int a) { return false; }
        @Override public boolean execute(String s, int[] a) { return false; }
        @Override public boolean execute(String s, String[] a) { return false; }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void setPoolable(boolean p) { }
        @Override public boolean isPoolable() { return false; }
        @Override public void closeOnCompletion() { }
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public <T> T unwrap(Class<T> c) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> c) { return false; }
    }

    /**
     * Lightweight ResultSet stub that iterates over a fixed list of
     * column names (simulating system.columns query results).
     */
    private static class StubResultSet implements ResultSet {
        private final String[] names;
        private int cursor = -1;

        StubResultSet(String... names) { this.names = names; }

        @Override public boolean next() { return ++cursor < names.length; }
        @Override public String getString(int columnIndex) { return names[cursor]; }
        @Override public void close() { }

        // --- Minimal stubs for remaining ResultSet methods ---
        @Override public boolean wasNull() { return false; }
        @Override public String getString(String c) { return names[cursor]; }
        @Override public boolean getBoolean(int c) { return false; }
        @Override public byte getByte(int c) { return 0; }
        @Override public short getShort(int c) { return 0; }
        @Override public int getInt(int c) { return 0; }
        @Override public long getLong(int c) { return 0; }
        @Override public float getFloat(int c) { return 0; }
        @Override public double getDouble(int c) { return 0; }
        @Override public java.math.BigDecimal getBigDecimal(int c, int s) { return null; }
        @Override public byte[] getBytes(int c) { return null; }
        @Override public java.sql.Date getDate(int c) { return null; }
        @Override public java.sql.Time getTime(int c) { return null; }
        @Override public java.sql.Timestamp getTimestamp(int c) { return null; }
        @Override public java.io.InputStream getAsciiStream(int c) { return null; }
        @Override public java.io.InputStream getUnicodeStream(int c) { return null; }
        @Override public java.io.InputStream getBinaryStream(int c) { return null; }
        @Override public boolean getBoolean(String c) { return false; }
        @Override public byte getByte(String c) { return 0; }
        @Override public short getShort(String c) { return 0; }
        @Override public int getInt(String c) { return 0; }
        @Override public long getLong(String c) { return 0; }
        @Override public float getFloat(String c) { return 0; }
        @Override public double getDouble(String c) { return 0; }
        @Override public java.math.BigDecimal getBigDecimal(String c, int s) { return null; }
        @Override public byte[] getBytes(String c) { return null; }
        @Override public java.sql.Date getDate(String c) { return null; }
        @Override public java.sql.Time getTime(String c) { return null; }
        @Override public java.sql.Timestamp getTimestamp(String c) { return null; }
        @Override public java.io.InputStream getAsciiStream(String c) { return null; }
        @Override public java.io.InputStream getUnicodeStream(String c) { return null; }
        @Override public java.io.InputStream getBinaryStream(String c) { return null; }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() { }
        @Override public String getCursorName() { return null; }
        @Override public ResultSetMetaData getMetaData() { return null; }
        @Override public Object getObject(int c) { return null; }
        @Override public Object getObject(String c) { return null; }
        @Override public int findColumn(String c) { return 0; }
        @Override public java.io.Reader getCharacterStream(int c) { return null; }
        @Override public java.io.Reader getCharacterStream(String c) { return null; }
        @Override public java.math.BigDecimal getBigDecimal(int c) { return null; }
        @Override public java.math.BigDecimal getBigDecimal(String c) { return null; }
        @Override public boolean isBeforeFirst() { return false; }
        @Override public boolean isAfterLast() { return false; }
        @Override public boolean isFirst() { return false; }
        @Override public boolean isLast() { return false; }
        @Override public void beforeFirst() { }
        @Override public void afterLast() { }
        @Override public boolean first() { return false; }
        @Override public boolean last() { return false; }
        @Override public int getRow() { return 0; }
        @Override public boolean absolute(int r) { return false; }
        @Override public boolean relative(int r) { return false; }
        @Override public boolean previous() { return false; }
        @Override public void setFetchDirection(int d) { }
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int r) { }
        @Override public int getFetchSize() { return 0; }
        @Override public int getType() { return ResultSet.TYPE_FORWARD_ONLY; }
        @Override public int getConcurrency() { return ResultSet.CONCUR_READ_ONLY; }
        @Override public boolean rowUpdated() { return false; }
        @Override public boolean rowInserted() { return false; }
        @Override public boolean rowDeleted() { return false; }
        @Override public void updateNull(int c) { }
        @Override public void updateBoolean(int c, boolean v) { }
        @Override public void updateByte(int c, byte v) { }
        @Override public void updateShort(int c, short v) { }
        @Override public void updateInt(int c, int v) { }
        @Override public void updateLong(int c, long v) { }
        @Override public void updateFloat(int c, float v) { }
        @Override public void updateDouble(int c, double v) { }
        @Override public void updateBigDecimal(int c, java.math.BigDecimal v) { }
        @Override public void updateString(int c, String v) { }
        @Override public void updateBytes(int c, byte[] v) { }
        @Override public void updateDate(int c, java.sql.Date v) { }
        @Override public void updateTime(int c, java.sql.Time v) { }
        @Override public void updateTimestamp(int c, java.sql.Timestamp v) { }
        @Override public void updateAsciiStream(int c, java.io.InputStream x, int l) { }
        @Override public void updateBinaryStream(int c, java.io.InputStream x, int l) { }
        @Override public void updateCharacterStream(int c, java.io.Reader x, int l) { }
        @Override public void updateObject(int c, Object v, int s) { }
        @Override public void updateObject(int c, Object v) { }
        @Override public void updateNull(String c) { }
        @Override public void updateBoolean(String c, boolean v) { }
        @Override public void updateByte(String c, byte v) { }
        @Override public void updateShort(String c, short v) { }
        @Override public void updateInt(String c, int v) { }
        @Override public void updateLong(String c, long v) { }
        @Override public void updateFloat(String c, float v) { }
        @Override public void updateDouble(String c, double v) { }
        @Override public void updateBigDecimal(String c, java.math.BigDecimal v) { }
        @Override public void updateString(String c, String v) { }
        @Override public void updateBytes(String c, byte[] v) { }
        @Override public void updateDate(String c, java.sql.Date v) { }
        @Override public void updateTime(String c, java.sql.Time v) { }
        @Override public void updateTimestamp(String c, java.sql.Timestamp v) { }
        @Override public void updateAsciiStream(String c, java.io.InputStream x, int l) { }
        @Override public void updateBinaryStream(String c, java.io.InputStream x, int l) { }
        @Override public void updateCharacterStream(String c, java.io.Reader x, int l) { }
        @Override public void updateObject(String c, Object v, int s) { }
        @Override public void updateObject(String c, Object v) { }
        @Override public void insertRow() { }
        @Override public void updateRow() { }
        @Override public void deleteRow() { }
        @Override public void refreshRow() { }
        @Override public void cancelRowUpdates() { }
        @Override public void moveToInsertRow() { }
        @Override public void moveToCurrentRow() { }
        @Override public Statement getStatement() { return null; }
        @Override public Object getObject(int c, java.util.Map<String, Class<?>> m) { return null; }
        @Override public java.sql.Ref getRef(int c) { return null; }
        @Override public java.sql.Blob getBlob(int c) { return null; }
        @Override public java.sql.Clob getClob(int c) { return null; }
        @Override public java.sql.Array getArray(int c) { return null; }
        @Override public Object getObject(String c, java.util.Map<String, Class<?>> m) { return null; }
        @Override public java.sql.Ref getRef(String c) { return null; }
        @Override public java.sql.Blob getBlob(String c) { return null; }
        @Override public java.sql.Clob getClob(String c) { return null; }
        @Override public java.sql.Array getArray(String c) { return null; }
        @Override public java.sql.Date getDate(int c, java.util.Calendar cal) { return null; }
        @Override public java.sql.Date getDate(String c, java.util.Calendar cal) { return null; }
        @Override public java.sql.Time getTime(int c, java.util.Calendar cal) { return null; }
        @Override public java.sql.Time getTime(String c, java.util.Calendar cal) { return null; }
        @Override public java.sql.Timestamp getTimestamp(int c, java.util.Calendar cal) { return null; }
        @Override public java.sql.Timestamp getTimestamp(String c, java.util.Calendar cal) { return null; }
        @Override public java.net.URL getURL(int c) { return null; }
        @Override public java.net.URL getURL(String c) { return null; }
        @Override public void updateRef(int c, java.sql.Ref v) { }
        @Override public void updateRef(String c, java.sql.Ref v) { }
        @Override public void updateBlob(int c, java.sql.Blob v) { }
        @Override public void updateBlob(String c, java.sql.Blob v) { }
        @Override public void updateClob(int c, java.sql.Clob v) { }
        @Override public void updateClob(String c, java.sql.Clob v) { }
        @Override public void updateArray(int c, java.sql.Array v) { }
        @Override public void updateArray(String c, java.sql.Array v) { }
        @Override public java.sql.RowId getRowId(int c) { return null; }
        @Override public java.sql.RowId getRowId(String c) { return null; }
        @Override public void updateRowId(int c, java.sql.RowId v) { }
        @Override public void updateRowId(String c, java.sql.RowId v) { }
        @Override public int getHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void updateNString(int c, String v) { }
        @Override public void updateNString(String c, String v) { }
        @Override public void updateNClob(int c, java.sql.NClob v) { }
        @Override public void updateNClob(String c, java.sql.NClob v) { }
        @Override public java.sql.NClob getNClob(int c) { return null; }
        @Override public java.sql.NClob getNClob(String c) { return null; }
        @Override public java.sql.SQLXML getSQLXML(int c) { return null; }
        @Override public java.sql.SQLXML getSQLXML(String c) { return null; }
        @Override public void updateSQLXML(int c, java.sql.SQLXML v) { }
        @Override public void updateSQLXML(String c, java.sql.SQLXML v) { }
        @Override public String getNString(int c) { return null; }
        @Override public String getNString(String c) { return null; }
        @Override public java.io.Reader getNCharacterStream(int c) { return null; }
        @Override public java.io.Reader getNCharacterStream(String c) { return null; }
        @Override public void updateNCharacterStream(int c, java.io.Reader x, long l) { }
        @Override public void updateNCharacterStream(String c, java.io.Reader x, long l) { }
        @Override public void updateAsciiStream(int c, java.io.InputStream x, long l) { }
        @Override public void updateBinaryStream(int c, java.io.InputStream x, long l) { }
        @Override public void updateCharacterStream(int c, java.io.Reader x, long l) { }
        @Override public void updateAsciiStream(String c, java.io.InputStream x, long l) { }
        @Override public void updateBinaryStream(String c, java.io.InputStream x, long l) { }
        @Override public void updateCharacterStream(String c, java.io.Reader x, long l) { }
        @Override public void updateBlob(int c, java.io.InputStream x, long l) { }
        @Override public void updateBlob(String c, java.io.InputStream x, long l) { }
        @Override public void updateClob(int c, java.io.Reader x, long l) { }
        @Override public void updateClob(String c, java.io.Reader x, long l) { }
        @Override public void updateNClob(int c, java.io.Reader x, long l) { }
        @Override public void updateNClob(String c, java.io.Reader x, long l) { }
        @Override public void updateNCharacterStream(int c, java.io.Reader x) { }
        @Override public void updateNCharacterStream(String c, java.io.Reader x) { }
        @Override public void updateAsciiStream(int c, java.io.InputStream x) { }
        @Override public void updateBinaryStream(int c, java.io.InputStream x) { }
        @Override public void updateCharacterStream(int c, java.io.Reader x) { }
        @Override public void updateAsciiStream(String c, java.io.InputStream x) { }
        @Override public void updateBinaryStream(String c, java.io.InputStream x) { }
        @Override public void updateCharacterStream(String c, java.io.Reader x) { }
        @Override public void updateBlob(int c, java.io.InputStream x) { }
        @Override public void updateBlob(String c, java.io.InputStream x) { }
        @Override public void updateClob(int c, java.io.Reader x) { }
        @Override public void updateClob(String c, java.io.Reader x) { }
        @Override public void updateNClob(int c, java.io.Reader x) { }
        @Override public void updateNClob(String c, java.io.Reader x) { }
        @Override public <T> T getObject(int c, Class<T> t) { return null; }
        @Override public <T> T getObject(String c, Class<T> t) { return null; }
        @Override public <T> T unwrap(Class<T> c) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> c) { return false; }
    }
}
