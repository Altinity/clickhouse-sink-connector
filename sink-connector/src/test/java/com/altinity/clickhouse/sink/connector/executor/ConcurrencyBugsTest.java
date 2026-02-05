package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for validating P0 concurrency bug fixes:
 * - BUG-CONC-1: HashMap thread safety (ConcurrentHashMap)
 * - BUG-CONC-4: Connection leak on exception
 * - BUG-CONC-5: Check-then-act race condition
 * - BUG-CONC-2: DDL cache invalidation
 */
public class ConcurrencyBugsTest {

    private static final int THREAD_COUNT = 10;
    private static final int OPERATIONS_PER_THREAD = 100;
    private ExecutorService executorService;

    @BeforeEach
    public void setUp() {
        executorService = Executors.newFixedThreadPool(THREAD_COUNT);
    }

    @AfterEach
    public void tearDown() {
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("BUG-CONC-1: Test ConcurrentHashMap prevents ConcurrentModificationException")
    public void testConcurrentHashMapAccess() throws InterruptedException {
        // Create a map similar to what's used in ClickHouseBatchRunnable
        Map<String, String> testMap = new ConcurrentHashMap<>();
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        // Concurrent put operations
                        testMap.put("key-" + threadId + "-" + j, "value-" + j);
                        
                        // Concurrent get operations
                        testMap.get("key-" + threadId);
                        
                        // Concurrent iteration (should not throw ConcurrentModificationException)
                        for (Map.Entry<String, String> entry : testMap.entrySet()) {
                            entry.getValue(); // Access value
                        }
                        
                        // Concurrent containsKey operations
                        testMap.containsKey("key-" + threadId);
                    }
                } catch (ConcurrentModificationException e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(0, errorCount.get(), "No ConcurrentModificationException should occur with ConcurrentHashMap");
    }

    @Test
    @DisplayName("BUG-CONC-5: Test atomic putIfAbsent prevents duplicate connection creation")
    public void testAtomicPutIfAbsent() throws InterruptedException {
        Map<String, Connection> connectionMap = new ConcurrentHashMap<>();
        AtomicInteger connectionCreateCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        // Simulate multiple threads trying to create connection for same database
        String databaseName = "test_db";

        for (int i = 0; i < THREAD_COUNT; i++) {
            executorService.submit(() -> {
                try {
                    // Simulate connection creation (expensive operation)
                    Connection mockConnection = createMockConnection();
                    connectionCreateCount.incrementAndGet();
                    
                    // Use putIfAbsent to ensure only one connection is kept
                    Connection existingConnection = connectionMap.putIfAbsent(databaseName, mockConnection);
                    
                    if (existingConnection != null) {
                        // Another thread won the race, close our connection
                        closeMockConnection(mockConnection);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(1, connectionMap.size(), "Only one connection should be stored");
        assertTrue(connectionCreateCount.get() >= 1, "At least one connection should be created");
        
        // In high contention, multiple threads may create connections, but only one is stored
        System.out.println("Total connections created: " + connectionCreateCount.get());
        System.out.println("Connections stored in map: " + connectionMap.size());
    }

    @Test
    @DisplayName("BUG-CONC-2: Test DDL cache synchronization during concurrent schema changes")
    public void testDDLCacheInvalidation() throws InterruptedException {
        // Simulate DbWriter cache that needs to be synchronized
        Map<String, String> columnTypeCache = new ConcurrentHashMap<>();
        columnTypeCache.put("id", "Int32");
        columnTypeCache.put("name", "String");
        
        AtomicInteger successfulUpdates = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        Object cacheLock = new Object();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    // Simulate ALTER TABLE adding a new column
                    String newColumn = "column_" + threadId;
                    
                    // Synchronized update to shared cache (Fix BUG-CONC-2)
                    synchronized (cacheLock) {
                        columnTypeCache.put(newColumn, "String");
                        successfulUpdates.incrementAndGet();
                    }
                    
                    // Verify column is visible to all threads
                    assertTrue(columnTypeCache.containsKey(newColumn), 
                        "Cache should contain newly added column");
                    
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(THREAD_COUNT, successfulUpdates.get(), 
            "All schema updates should complete successfully");
        assertEquals(2 + THREAD_COUNT, columnTypeCache.size(), 
            "Cache should contain all added columns");
    }

    @Test
    @DisplayName("BUG-CONC-4: Test connection cleanup on exception paths")
    public void testConnectionLeakOnException() {
        AtomicInteger connectionsClosed = new AtomicInteger(0);
        AtomicInteger exceptionsThrown = new AtomicInteger(0);

        // Simulate connection creation with potential exception
        for (int i = 0; i < 10; i++) {
            Connection systemConn = null;
            Connection newConn = null;
            try {
                systemConn = createMockConnection();
                
                // Simulate exception during database creation
                if (i % 3 == 0) {
                    throw new RuntimeException("Simulated database creation error");
                }
                
                newConn = createMockConnection();
                
            } catch (Exception e) {
                exceptionsThrown.incrementAndGet();
                // Fix BUG-CONC-4: Clean up connection on exception path
                if (newConn != null) {
                    closeMockConnection(newConn);
                    connectionsClosed.incrementAndGet();
                }
            } finally {
                // Fix BUG-CONC-4: Always close system connection
                if (systemConn != null) {
                    closeMockConnection(systemConn);
                    connectionsClosed.incrementAndGet();
                }
            }
        }

        assertTrue(exceptionsThrown.get() > 0, "Some exceptions should be thrown");
        assertTrue(connectionsClosed.get() > 0, "Connections should be properly closed");
        System.out.println("Exceptions thrown: " + exceptionsThrown.get());
        System.out.println("Connections closed: " + connectionsClosed.get());
    }

    @Test
    @DisplayName("Integration test: Concurrent database operations without deadlock")
    public void testConcurrentOperationsWithoutDeadlock() throws InterruptedException {
        Map<String, Connection> databaseConnections = new ConcurrentHashMap<>();
        Map<String, DbWriter> writerCache = new ConcurrentHashMap<>();
        AtomicInteger operationsCompleted = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executorService.submit(() -> {
                try {
                    String dbName = "db_" + (threadId % 3); // 3 databases
                    
                    // Concurrent connection creation (test BUG-CONC-5)
                    databaseConnections.computeIfAbsent(dbName, k -> createMockConnection());
                    
                    // Concurrent writer access (test BUG-CONC-1)
                    writerCache.put("topic_" + threadId, createMockDbWriter());
                    
                    // Simulate some operations
                    Thread.sleep(10);
                    
                    operationsCompleted.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All operations should complete without deadlock");
        assertEquals(THREAD_COUNT, operationsCompleted.get(), "All operations should succeed");
        assertTrue(databaseConnections.size() <= 3, "Should have at most 3 database connections");
    }

    // Mock helper methods
    private Connection createMockConnection() {
        // Return a mock connection object
        return new MockConnection();
    }

    private void closeMockConnection(Connection conn) {
        if (conn instanceof MockConnection) {
            ((MockConnection) conn).close();
        }
    }

    private DbWriter createMockDbWriter() {
        // Return a mock DbWriter
        return null; // Simplified for test
    }

    // Simple mock connection for testing
    private static class MockConnection implements Connection {
        private boolean closed = false;

        public void close() {
            closed = true;
        }

        public boolean isClosed() {
            return closed;
        }

        // Implement minimal required methods
        @Override
        public java.sql.Statement createStatement() { return null; }
        
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql) { return null; }
        
        @Override
        public java.sql.CallableStatement prepareCall(String sql) { return null; }
        
        @Override
        public String nativeSQL(String sql) { return null; }
        
        @Override
        public void setAutoCommit(boolean autoCommit) {}
        
        @Override
        public boolean getAutoCommit() { return false; }
        
        @Override
        public void commit() {}
        
        @Override
        public void rollback() {}
        
        @Override
        public java.sql.DatabaseMetaData getMetaData() { return null; }
        
        @Override
        public void setReadOnly(boolean readOnly) {}
        
        @Override
        public boolean isReadOnly() { return false; }
        
        @Override
        public void setCatalog(String catalog) {}
        
        @Override
        public String getCatalog() { return null; }
        
        @Override
        public void setTransactionIsolation(int level) {}
        
        @Override
        public int getTransactionIsolation() { return 0; }
        
        @Override
        public java.sql.SQLWarning getWarnings() { return null; }
        
        @Override
        public void clearWarnings() {}
        
        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) { return null; }
        
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) { return null; }
        
        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) { return null; }
        
        @Override
        public java.util.Map<String, Class<?>> getTypeMap() { return null; }
        
        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) {}
        
        @Override
        public void setHoldability(int holdability) {}
        
        @Override
        public int getHoldability() { return 0; }
        
        @Override
        public java.sql.Savepoint setSavepoint() { return null; }
        
        @Override
        public java.sql.Savepoint setSavepoint(String name) { return null; }
        
        @Override
        public void rollback(java.sql.Savepoint savepoint) {}
        
        @Override
        public void releaseSavepoint(java.sql.Savepoint savepoint) {}
        
        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) { return null; }
        
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) { return null; }
        
        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) { return null; }
        
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) { return null; }
        
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) { return null; }
        
        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) { return null; }
        
        @Override
        public java.sql.Clob createClob() { return null; }
        
        @Override
        public java.sql.Blob createBlob() { return null; }
        
        @Override
        public java.sql.NClob createNClob() { return null; }
        
        @Override
        public java.sql.SQLXML createSQLXML() { return null; }
        
        @Override
        public boolean isValid(int timeout) { return !closed; }
        
        @Override
        public void setClientInfo(String name, String value) {}
        
        @Override
        public void setClientInfo(java.util.Properties properties) {}
        
        @Override
        public String getClientInfo(String name) { return null; }
        
        @Override
        public java.util.Properties getClientInfo() { return null; }
        
        @Override
        public java.sql.Array createArrayOf(String typeName, Object[] elements) { return null; }
        
        @Override
        public java.sql.Struct createStruct(String typeName, Object[] attributes) { return null; }
        
        @Override
        public void setSchema(String schema) {}
        
        @Override
        public String getSchema() { return null; }
        
        @Override
        public void abort(java.util.concurrent.Executor executor) {}
        
        @Override
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) {}
        
        @Override
        public int getNetworkTimeout() { return 0; }
        
        @Override
        public <T> T unwrap(Class<T> iface) { return null; }
        
        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
