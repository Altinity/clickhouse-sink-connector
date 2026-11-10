package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.common.MissingTargetTableException;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.07 sections 3.1, 3.2 and 3.6: both execution engines fail the batch
 * when the ClickHouse catalog positively reports the target table missing, and
 * both keep retrying when the catalog cannot answer.
 *
 * <p><b>The incident.</b> On 2026-11-09 a dropped table in the staging target
 * left the connector unresolved: the column listing returned no rows, the
 * writer had no metadata, and the batch returned {@code false} -- a retry --
 * for hours. Six thousand empty {@code system.columns} reads later the handoff
 * FIFO hit its hard cap and the engine stopped, every thirteen minutes, with a
 * message about a row count and none about the table. The connector had no way
 * to say "that table is not there", so the one condition that can never settle
 * by retrying was retried like a transient one.</p>
 *
 * <p><b>What must hold.</b> A table the catalog positively reports missing
 * raises {@link MissingTargetTableException} out of the batch path, naming the
 * table and the remedy, so the offset is not committed and the records replay
 * once the table exists. An inconclusive probe -- no connection, or a read
 * that threw -- keeps the retry: an unreachable server says nothing about
 * whether the table exists, and stopping on it would take a healthy connector
 * down for a network blip.</p>
 *
 * <p>The tests drive both engines against an in-memory ClickHouse catalog
 * (JDK dynamic proxies, no mocking framework on this classpath), so no server
 * is required and every query the metadata code issues is observable.</p>
 */
public class MissingTargetTableEngineTest {

    /** A port nothing listens on, so any real connection attempt fails fast. */
    private static final int UNREACHABLE_PORT = 1;

    /** {@code DBMetadata}'s declared default, restored so this test leaks no state. */
    private static final int DEFAULT_MAX_RETRIES = 10;

    private static final AtomicLong UNIQUE = new AtomicLong(System.nanoTime());

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.STRING_SCHEMA)
            .build();

    @BeforeEach
    public void setUp() {
        // Every metadata helper retries MAX_RETRIES times per query. One attempt
        // reaches the same verdict and keeps the tests quick.
        DBMetadata.setMaxRetries(1);
        HikariDbSource.close();
    }

    @AfterEach
    public void tearDown() {
        DBMetadata.setMaxRetries(DEFAULT_MAX_RETRIES);
        HikariDbSource.close();
    }

    /**
     * An in-memory ClickHouse catalog: {@code system.databases} holds the one
     * database, {@code system.tables} holds the one table or nothing, and
     * {@code system.columns} lists no columns for it either way.
     *
     * <p>{@code countQueriesToAnswer} bounds how many {@code system.tables}
     * count queries succeed; a later one is refused with a retriable code, the
     * way a server under load refuses one. That is how an inconclusive probe is
     * produced without also breaking the column reads before it.</p>
     */
    private static final class Catalog {

        private final String database;
        private final boolean tableListed;
        private final int countQueriesToAnswer;
        private final AtomicInteger countQueries = new AtomicInteger();
        private final List<String> queries = Collections.synchronizedList(new ArrayList<>());

        Catalog(String database, boolean tableListed, int countQueriesToAnswer) {
            this.database = database;
            this.tableListed = tableListed;
            this.countQueriesToAnswer = countQueriesToAnswer;
        }

        ResultSet answer(String sql) throws SQLException {
            this.queries.add(sql);
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (normalized.contains("is_in_sorting_key")) {
                // No sorting key: the catalog lists no columns for this table.
                return resultSet(false, null, 0L);
            }
            if (normalized.contains("system.databases")) {
                return resultSet(true, this.database, 0L);
            }
            if (normalized.contains("engine_full")) {
                // A table that does not exist has no row in system.tables at all.
                return resultSet(false, null, 0L);
            }
            if (normalized.contains("system.tables") && normalized.contains("count()")) {
                if (this.countQueries.incrementAndGet() > this.countQueriesToAnswer) {
                    throw new SQLException(
                            "Code: 202. DB::Exception: Too many simultaneous queries for user.");
                }
                return resultSet(true, null, this.tableListed ? 1L : 0L);
            }
            if (normalized.contains("system.columns")) {
                return resultSet(false, null, 0L);
            }
            throw new SQLException("The catalog fake has no answer for: " + sql);
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "createStatement":
                                return statement(false);
                            case "prepareStatement":
                                return statement(true);
                            case "isClosed":
                                return false;
                            case "isValid":
                                return true;
                            case "close":
                                return null;
                            case "getAutoCommit":
                                return true;
                            case "setAutoCommit":
                            case "commit":
                            case "rollback":
                                return null;
                            case "toString":
                                return "catalog-connection";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private Object statement(boolean prepared) {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        return answer((String) args[0]);
                    case "execute":
                        // Every statement this fake is asked to execute is DDL
                        // (CREATE DATABASE), which produces no result set. The
                        // no-argument form hands over the SQL at prepare time.
                        if (args != null && args.length == 1 && args[0] instanceof String) {
                            this.queries.add((String) args[0]);
                        }
                        return false;
                    case "getResultSet":
                        return null;
                    case "close":
                        return null;
                    case "toString":
                        return "catalog-statement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            Class<?> type = prepared ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }

        private static ResultSet resultSet(boolean oneRow, String text, long number) {
            // getLong is only meaningful after next() has returned a row, so the
            // cursor is driven from a single boolean and reads like a real
            // zero-or-one-row result set.
            final boolean[] advanced = {false};
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        if (!oneRow || advanced[0]) {
                            return false;
                        }
                        advanced[0] = true;
                        return true;
                    case "getString":
                        return text;
                    case "getLong":
                        return number;
                    case "getInt":
                        return (int) number;
                    case "close":
                        return null;
                    case "toString":
                        return "catalog-result-set";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }
    }

    /**
     * A worker whose every connection is the same in-memory catalog.
     *
     * <p>{@code openConnection} is the runnable's single connection seam: the
     * constructor's system connection, the {@code CREATE DATABASE} bootstrap
     * and the per-database connection all come through it. The constructor runs
     * before the field below is assigned, so its own attempt gets {@code null}
     * -- which is what an unreachable server produces and is never used here.</p>
     */
    private static final class CatalogWorker extends ClickHouseBatchRunnable {

        private final Connection connection;

        CatalogWorker(ClickHouseSinkConnectorConfig config, Connection connection) {
            super(new LinkedBlockingQueue<List<ClickHouseStruct>>(), config, new HashMap<>());
            this.connection = connection;
        }

        @Override
        Connection openConnection(String jdbcUrl, String databaseName) {
            return this.connection;
        }
    }

    /** Counts what the connector told Debezium about the batch. */
    private static final class CountingCommitter implements DebeziumEngine.RecordCommitter {

        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger batchesFinished = new AtomicInteger();

        @Override
        public void markProcessed(Object record) {
            this.processed.incrementAndGet();
        }

        @Override
        public void markBatchFinished() {
            this.batchesFinished.incrementAndGet();
        }

        @Override
        public void markProcessed(Object record, DebeziumEngine.Offsets offsets) {
            this.processed.incrementAndGet();
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", String.valueOf(UNREACHABLE_PORT));
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "testdb");
        // The scenario behind the incident: the user manages their own schemas,
        // so a table missing from ClickHouse is never created for them.
        props.put("auto.create.tables", "false");
        props.put("single.threaded", "true");
        props.put("connection.pool.disable", "true");
        props.put("errors.max.retries", "1");
        // Naming the sink connector class makes ConnectorType answer KAFKA, so
        // the offset-storage database is not created on this no-server config.
        props.put("connector.class",
                "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector");
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    /** A database name no other test (or earlier run in this JVM) has used. */
    private static String uniqueDatabase(String tag) {
        return "mtt_" + tag + "_" + Long.toUnsignedString(UNIQUE.incrementAndGet());
    }

    private static ClickHouseStruct record(String topic, String database, long offset,
                                           CountingCommitter committer, boolean lastInBatch) {
        Struct row = new Struct(ROW_SCHEMA)
                .put("id", (int) offset)
                .put("name", "row" + offset);
        ClickHouseStruct chStruct = new ClickHouseStruct(offset, topic, null, 0,
                System.currentTimeMillis(), null, row, null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        chStruct.setDatabase(database);
        chStruct.setCommitter(committer);
        chStruct.setSourceRecord((ChangeEvent<SourceRecord, SourceRecord>) null);
        chStruct.setLastRecordInBatch(lastInBatch);
        return chStruct;
    }

    /**
     * {@code ClickHouseBatchWriter} keeps its per-database connections private
     * and takes no connection argument, so the only way to hand it one without
     * a server is its connection map.
     */
    private static void injectConnection(ClickHouseBatchWriter writer, String database,
                                         Connection connection) throws Exception {
        Field field = ClickHouseBatchWriter.class.getDeclaredField("databaseToConnectionMap");
        field.setAccessible(true);
        Map<String, Connection> connections = new HashMap<>();
        connections.put(database, connection);
        field.set(writer, connections);
    }

    private static List<ClickHouseStruct> batchOf(String topic, String database,
                                                  CountingCommitter committer) {
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(record(topic, database, 1L, committer, false));
        batch.add(record(topic, database, 2L, committer, true));
        return batch;
    }

    /**
     * The regression, on the multi-threaded engine. Before the fix this batch
     * returned {@code false} -- a retry -- for as long as the table stayed
     * missing, which is what filled the handoff FIFO until the hard cap stopped
     * the engine every thirteen minutes.
     */
    @Test
    @DisplayName("The runnable fails the batch, naming the table, when the catalog reports it missing")
    public void runnableFailsTheBatchWhenTheCatalogReportsTheTableMissing() throws Exception {
        String database = uniqueDatabase("absent");
        String topic = "SERVER5432." + database + ".bond_accrual_fx";
        CatalogWorker worker = new CatalogWorker(config(),
                new Catalog(database, false, Integer.MAX_VALUE).connection());
        CountingCommitter committer = new CountingCommitter();

        Throwable thrown = null;
        try {
            worker.processRecordsByTopic(topic, batchOf(topic, database, committer));
        } catch (Exception e) {
            thrown = e;
        }

        assertNotNull(thrown,
                "a batch whose target table the catalog reports missing must fail loudly; returning "
                        + "false retries a condition that can never change by retrying, and the retry "
                        + "loop is what stopped the connector on 2026-11-09");
        assertTrue(thrown instanceof MissingTargetTableException,
                "expected MissingTargetTableException, got " + thrown.getClass().getName()
                        + ": " + thrown.getMessage());
        String message = thrown.getMessage();
        assertTrue(message.contains(database + ".bond_accrual_fx"),
                "the failure must name the table that is missing, got: " + message);
        assertTrue(message.contains("auto.create.tables"),
                "the failure must name the remedy, got: " + message);
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify((Exception) thrown),
                "a positively missing table carries no ClickHouse error code; without the terminal "
                        + "type registration it classifies UNKNOWN and the task retries it forever");
        assertEquals(0, committer.processed.get(),
                "no record may be acknowledged when the batch was never written");
        assertEquals(0, committer.batchesFinished.get(),
                "the batch must not be marked finished, or a later batch commits past its records");
    }

    /**
     * The same condition on the single-threaded engine. It wraps anything that
     * is not already a {@code BatchPersistenceException}, so the reason has to
     * survive as the cause -- that chain is what the classifier walks.
     */
    @Test
    @DisplayName("The single-threaded writer carries the missing table as the cause of the batch failure")
    public void writerCarriesTheMissingTableAsTheCauseOfTheBatchFailure() throws Exception {
        String database = uniqueDatabase("wrapped");
        String topic = "SERVER5432." + database + ".bond_accrual_fx";
        ClickHouseBatchWriter writer = new ClickHouseBatchWriter(config(), new HashMap<>());
        injectConnection(writer, database,
                new Catalog(database, false, Integer.MAX_VALUE).connection());
        CountingCommitter committer = new CountingCommitter();

        Throwable thrown = null;
        try {
            writer.persistRecords(batchOf(topic, database, committer));
        } catch (RuntimeException e) {
            thrown = e;
        }

        assertNotNull(thrown,
                "persistRecords returned normally for a batch it never wrote: the caller cannot tell, "
                        + "so the offset advances past records that never reached ClickHouse");
        assertTrue(thrown instanceof ClickHouseBatchWriter.BatchPersistenceException,
                "the failure must be reported as a batch persistence failure, got "
                        + thrown.getClass().getName());
        Throwable cause = thrown.getCause();
        assertNotNull(cause,
                "the batch failure must keep its reason as the cause; the classifier reads the "
                        + "cause chain and without it the missing table is invisible");
        assertTrue(cause instanceof MissingTargetTableException,
                "expected MissingTargetTableException as the cause, got "
                        + cause.getClass().getName() + ": " + cause.getMessage());
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify((Exception) thrown),
                "the terminal type must be found through the wrapper");
        assertEquals(0, committer.processed.get(),
                "no record may be acknowledged when the batch was never written");
        assertEquals(0, committer.batchesFinished.get(),
                "the batch must not be marked finished when it was never written");
    }

    /**
     * The safety half of the contract. An unreachable or overloaded catalog
     * must not be read as a dropped table: that would stop a connector on a
     * transient condition and name the wrong cause and the wrong remedy.
     */
    @Test
    @DisplayName("A catalog that cannot answer is still a retry, never a stop")
    public void inconclusiveProbeIsStillARetryNotAStop() throws Exception {
        String database = uniqueDatabase("unknown");
        String topic = "SERVER5432." + database + ".bond_accrual_fx";
        // Three count queries answer the column read of construction, its
        // re-read inside initializeTableEngine, and the retry block's refresh.
        // From the fourth on the server refuses -- which is when the absence
        // probe asks -- so the probe cannot answer.
        Catalog catalog = new Catalog(database, false, 3);
        CatalogWorker worker = new CatalogWorker(config(), catalog.connection());
        CountingCommitter committer = new CountingCommitter();

        boolean applied;
        try {
            applied = worker.processRecordsByTopic(topic, batchOf(topic, database, committer));
        } catch (Exception e) {
            throw new AssertionError("an unreadable catalog must not stop the connector: an "
                    + "unreachable server says nothing about whether the table exists, so the batch "
                    + "must be retried instead of being failed as a missing table", e);
        }

        assertFalse(applied, "the batch was not written, so the engine must retry it");
        assertTrue(catalog.countQueries.get() >= 4,
                "the absence probe must have asked the catalog and been refused; the catalog was "
                        + "asked " + catalog.countQueries.get() + " count queries, so the probe never "
                        + "ran and this test proved nothing");
        assertEquals(0, committer.processed.get(),
                "a retried batch acknowledges nothing");
    }
}
