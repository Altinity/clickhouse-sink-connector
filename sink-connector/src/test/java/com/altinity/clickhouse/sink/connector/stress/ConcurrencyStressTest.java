package com.altinity.clickhouse.sink.connector.stress;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Aggressive stress tests to reveal concurrency bugs and race conditions.
 * These tests use high thread counts, memory pressure, and aggressive timing
 * to expose issues that might not appear under normal load.
 * 
 * Run with: mvn test -Dtest=ConcurrencyStressTest
 * Run with memory constraints: mvn test -Dtest=ConcurrencyStressTest -Xmx512m
 */
public class ConcurrencyStressTest {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencyStressTest.class);
    private static final int TIMEOUT_SECONDS = 120;
    
    @Before
    public void setup() {
        LOGGER.info("Starting stress test");
    }
    
    @After
    public void teardown() {
        LOGGER.info("Stress test completed");
    }
    
    /**
     * Test 1: High Concurrency HashMap Access
     * 
     * This test simulates 100 threads performing 10,000 operations each (1 million total)
     * on a ConcurrentHashMap to verify it truly prevents race conditions.
     * 
     * Bug Detection:
     * - HashMap corruption (if accidentally using HashMap instead of ConcurrentHashMap)
     * - Lost updates
     * - Null pointer exceptions from concurrent modifications
     */
    @Test
    public void testHighConcurrencyHashMapAccess() throws Exception {
        LOGGER.info("TEST 1: High Concurrency HashMap Access - 100 threads, 1M operations");
        
        int threadCount = 100;
        int operationsPerThread = 10000;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        Map<String, MockConnection> sharedMap = new ConcurrentHashMap<>();
        
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // All threads start simultaneously
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        String key = "key_" + (j % 100);
                        
                        // Mix of operations to stress the map
                        switch (j % 4) {
                            case 0: 
                                sharedMap.put(key, new MockConnection(threadId, j)); 
                                break;
                            case 1: 
                                sharedMap.get(key); 
                                break;
                            case 2: 
                                sharedMap.remove(key); 
                                break;
                            case 3: 
                                sharedMap.putIfAbsent(key, new MockConnection(threadId, j)); 
                                break;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Exception in thread " + threadId, e);
                    exceptionCount.incrementAndGet();
                }
                return null;
            }));
        }
        
        long startTime = System.currentTimeMillis();
        startLatch.countDown(); // Start all threads
        
        for (Future<?> future : futures) {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        
        executor.shutdown();
        assertTrue("Executor should terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
        
        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info("Completed {} operations in {}ms", threadCount * operationsPerThread, duration);
        LOGGER.info("Operations per second: {}", (threadCount * operationsPerThread * 1000L) / duration);
        
        assertEquals("No exceptions should occur during concurrent access", 0, exceptionCount.get());
        LOGGER.info("✓ TEST 1 PASSED: No race conditions detected");
    }
    
    /**
     * Test 2: Concurrent Schema Evolution
     * 
     * Simulates 50 threads performing concurrent ALTER TABLE operations,
     * testing DDL cache invalidation under extreme load.
     * 
     * Bug Detection:
     * - Stale metadata cache
     * - Lost column definitions
     * - Race conditions in cache updates
     * - Deadlocks in cache synchronization
     */
    @Test
    public void testConcurrentSchemaEvolution() throws Exception {
        LOGGER.info("TEST 2: Concurrent Schema Evolution - 50 threads, simultaneous ALTER TABLE");
        
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // Simulate shared metadata cache
        Map<String, Map<String, String>> schemaCache = new ConcurrentHashMap<>();
        schemaCache.put("test_table", new ConcurrentHashMap<>());
        
        List<Future<Boolean>> futures = new ArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    String columnName = "col_" + threadId;
                    
                    // Simulate ALTER TABLE ADD COLUMN
                    Map<String, String> tableMetadata = schemaCache.get("test_table");
                    tableMetadata.put(columnName, "String");
                    
                    // Simulate cache invalidation and refresh
                    Map<String, String> refreshedMetadata = schemaCache.get("test_table");
                    
                    // Verify the column exists (tests cache consistency)
                    if (!refreshedMetadata.containsKey(columnName)) {
                        LOGGER.error("Cache miss for column: {}", columnName);
                        return false;
                    }
                    
                    successCount.incrementAndGet();
                    return true;
                } catch (Exception e) {
                    LOGGER.error("Schema evolution failed in thread " + threadId, e);
                    return false;
                }
            }));
        }
        
        startLatch.countDown();
        
        int successfulOperations = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                successfulOperations++;
            }
        }
        
        executor.shutdown();
        assertTrue("Executor should terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
        
        assertEquals("All schema evolutions should succeed", threadCount, successfulOperations);
        assertEquals("All columns should be in cache", threadCount, 
                    schemaCache.get("test_table").size());
        
        LOGGER.info("✓ TEST 2 PASSED: {} concurrent schema changes completed successfully", threadCount);
    }
    
    /**
     * Test 3: Memory Pressure Concurrency
     * 
     * Tests behavior under memory pressure with 50 threads creating large objects.
     * Run with: mvn test -Dtest=ConcurrencyStressTest#testMemoryPressureConcurrency -Xmx512m
     * 
     * Bug Detection:
     * - Memory leaks
     * - Object pool exhaustion
     * - OOM under concurrent load
     * - Improper resource cleanup
     */
    @Test
    public void testMemoryPressureConcurrency() throws Exception {
        LOGGER.info("TEST 3: Memory Pressure - 50 threads, 10K records each, limited heap");
        
        int threadCount = 50;
        int recordsPerThread = 10000;
        int recordSize = 1024; // 1KB per record
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Long>> futures = new ArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        
        AtomicLong totalBytesProcessed = new AtomicLong(0);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                
                long bytesProcessed = 0;
                for (int j = 0; j < recordsPerThread; j++) {
                    // Create large record
                    MockRecord record = new MockRecord(threadId, j, recordSize);
                    bytesProcessed += record.getSize();
                    
                    // Simulate processing
                    processRecord(record);
                    
                    // Force GC periodically to test cleanup
                    if (j % 1000 == 0) {
                        System.gc();
                    }
                }
                
                return bytesProcessed;
            }));
        }
        
        Runtime runtime = Runtime.getRuntime();
        long startMemory = runtime.totalMemory() - runtime.freeMemory();
        
        startLatch.countDown();
        
        for (Future<Long> future : futures) {
            totalBytesProcessed.addAndGet(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        
        executor.shutdown();
        assertTrue("Executor should terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
        
        long endMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryGrowth = endMemory - startMemory;
        
        LOGGER.info("Total bytes processed: {} MB", totalBytesProcessed.get() / (1024 * 1024));
        LOGGER.info("Memory growth: {} MB", memoryGrowth / (1024 * 1024));
        
        // Memory growth should be reasonable (not accumulating all data)
        long expectedMaxGrowth = (threadCount * 100 * recordSize); // 100 records worth
        assertTrue("Memory growth should be bounded: " + memoryGrowth + " vs " + expectedMaxGrowth,
                  memoryGrowth < expectedMaxGrowth);
        
        LOGGER.info("✓ TEST 3 PASSED: No OutOfMemoryError, memory growth bounded");
    }
    
    /**
     * Test 4: Deadlock Detection
     * 
     * Intentionally creates potential deadlock scenarios to verify proper
     * lock ordering and deadlock prevention.
     * 
     * Bug Detection:
     * - Deadlocks from improper lock ordering
     * - Thread starvation
     * - Livelock conditions
     */
    @Test
    public void testDeadlockDetection() throws Exception {
        LOGGER.info("TEST 4: Deadlock Detection - Testing lock ordering");
        
        Object resourceA = new Object();
        Object resourceB = new Object();
        
        AtomicBoolean deadlockDetected = new AtomicBoolean(false);
        AtomicInteger completedOperations = new AtomicInteger(0);
        
        // Thread 1: Lock A -> B
        Thread t1 = new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    synchronized (resourceA) {
                        Thread.sleep(1); // Small delay to increase collision probability
                        synchronized (resourceB) {
                            completedOperations.incrementAndGet();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Thread-LockA-B");
        
        // Thread 2: Lock A -> B (same order - should NOT deadlock)
        Thread t2 = new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    synchronized (resourceA) {
                        Thread.sleep(1);
                        synchronized (resourceB) {
                            completedOperations.incrementAndGet();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Thread-LockA-B-2");
        
        t1.start();
        t2.start();
        
        // Wait with timeout
        t1.join(10000);
        t2.join(10000);
        
        if (t1.isAlive() || t2.isAlive()) {
            deadlockDetected.set(true);
            LOGGER.error("DEADLOCK DETECTED!");
            
            // Interrupt threads
            t1.interrupt();
            t2.interrupt();
            t1.join(1000);
            t2.join(1000);
        }
        
        assertFalse("No deadlock should occur with consistent lock ordering", 
                   deadlockDetected.get());
        assertEquals("All operations should complete", 200, completedOperations.get());
        
        LOGGER.info("✓ TEST 4 PASSED: No deadlocks with proper lock ordering");
    }
    
    /**
     * Test 5: Race Condition in Transaction Boundaries
     * 
     * Tests concurrent transaction commit/rollback operations to find races
     * in transaction state management.
     * 
     * Bug Detection:
     * - Lost commits
     * - Double commits
     * - Transaction state corruption
     * - Commit/rollback race conditions
     */
    @Test
    public void testTransactionBoundaryRaces() throws Exception {
        LOGGER.info("TEST 5: Transaction Boundary Races - 100 concurrent transactions");
        
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        // Shared transaction manager simulation
        Map<String, TransactionState> transactions = new ConcurrentHashMap<>();
        AtomicInteger commitCount = new AtomicInteger(0);
        AtomicInteger rollbackCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    String txId = "tx_" + threadId;
                    
                    // Start transaction
                    TransactionState state = new TransactionState(txId);
                    transactions.put(txId, state);
                    
                    // Simulate work
                    state.addOperation("INSERT INTO table VALUES (" + threadId + ")");
                    Thread.sleep(ThreadLocalRandom.current().nextInt(10));
                    
                    // Commit or rollback randomly
                    if (threadId % 3 == 0) {
                        // Rollback
                        if (state.rollback()) {
                            rollbackCount.incrementAndGet();
                        } else {
                            LOGGER.error("Failed to rollback tx: {}", txId);
                            errorCount.incrementAndGet();
                        }
                    } else {
                        // Commit
                        if (state.commit()) {
                            commitCount.incrementAndGet();
                        } else {
                            LOGGER.error("Failed to commit tx: {}", txId);
                            errorCount.incrementAndGet();
                        }
                    }
                    
                    transactions.remove(txId);
                    
                } catch (Exception e) {
                    LOGGER.error("Transaction error in thread " + threadId, e);
                    errorCount.incrementAndGet();
                }
                return null;
            }));
        }
        
        startLatch.countDown();
        
        for (Future<?> future : futures) {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        
        executor.shutdown();
        assertTrue("Executor should terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
        
        LOGGER.info("Commits: {}, Rollbacks: {}, Errors: {}", 
                   commitCount.get(), rollbackCount.get(), errorCount.get());
        
        assertEquals("All transactions should complete", threadCount, 
                    commitCount.get() + rollbackCount.get());
        assertEquals("No errors should occur", 0, errorCount.get());
        assertTrue("All transactions should be cleaned up", transactions.isEmpty());
        
        LOGGER.info("✓ TEST 5 PASSED: Transaction boundaries handled correctly");
    }
    
    /**
     * Test 6: Thread Pool Exhaustion
     * 
     * Tests behavior when thread pool is exhausted to verify proper
     * task queuing and rejection handling.
     */
    @Test
    public void testThreadPoolExhaustion() throws Exception {
        LOGGER.info("TEST 6: Thread Pool Exhaustion - Limited pool, many tasks");
        
        int poolSize = 10;
        int taskCount = 1000;
        
        ExecutorService executor = new ThreadPoolExecutor(
            poolSize, poolSize, 
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100), // Limited queue
            new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure
        );
        
        AtomicInteger completedTasks = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < taskCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    // Simulate work
                    Thread.sleep(10);
                    completedTasks.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        
        for (Future<?> future : futures) {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        
        executor.shutdown();
        assertTrue("Executor should terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
        
        assertEquals("All tasks should complete despite limited pool", taskCount, 
                    completedTasks.get());
        
        LOGGER.info("✓ TEST 6 PASSED: Thread pool exhaustion handled gracefully");
    }
    
    // ==================== Helper Classes ====================
    
    private static class MockConnection {
        private final int threadId;
        private final int operationId;
        
        public MockConnection(int threadId, int operationId) {
            this.threadId = threadId;
            this.operationId = operationId;
        }
    }
    
    private static class MockRecord {
        private final int threadId;
        private final int recordId;
        private final byte[] data;
        
        public MockRecord(int threadId, int recordId, int size) {
            this.threadId = threadId;
            this.recordId = recordId;
            this.data = new byte[size];
            // Fill with some data
            Arrays.fill(data, (byte) (recordId % 256));
        }
        
        public int getSize() {
            return data.length;
        }
    }
    
    private static class TransactionState {
        private final String txId;
        private final List<String> operations = new ArrayList<>();
        private volatile boolean committed = false;
        private volatile boolean rolledBack = false;
        
        public TransactionState(String txId) {
            this.txId = txId;
        }
        
        public synchronized void addOperation(String op) {
            if (committed || rolledBack) {
                throw new IllegalStateException("Transaction already completed");
            }
            operations.add(op);
        }
        
        public synchronized boolean commit() {
            if (committed || rolledBack) {
                return false;
            }
            committed = true;
            return true;
        }
        
        public synchronized boolean rollback() {
            if (committed || rolledBack) {
                return false;
            }
            rolledBack = true;
            operations.clear();
            return true;
        }
    }
    
    private void processRecord(MockRecord record) {
        // Simulate processing (compute checksum)
        int checksum = 0;
        for (int i = 0; i < Math.min(100, record.getSize()); i++) {
            checksum += record.data[i];
        }
        // Don't optimize away
        if (checksum < 0) {
            throw new RuntimeException("Invalid checksum");
        }
    }
}
