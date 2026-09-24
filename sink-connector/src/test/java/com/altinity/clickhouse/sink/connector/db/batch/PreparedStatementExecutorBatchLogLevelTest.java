package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 03.06 section 3.3: a successful batch reports its progress at DEBUG,
 * never at INFO.
 *
 * <p><b>The defect.</b> Every batch wrote the full INSERT template
 * ("*** INSERT QUERY for Database(db) ***: insert into ...") and an
 * "EXECUTED BATCH Successfully" line at INFO. With ten workers flushing every
 * few milliseconds that was 97% of a busy deployment's log -- ~2,600 lines a
 * minute, the INSERT template (every column of every table) most of the
 * bytes -- and the lines that matter scrolled out of the retained history
 * within hours.</p>
 *
 * <p>The test drives the real {@code addToPreparedStatementBatch} against a
 * recording JDBC surface (as {@code PreparedStatementExecutorClearParametersTest}
 * does) with the executor's logger opened up to DEBUG, so both lines are
 * still observed -- at DEBUG -- and nothing reaches INFO.</p>
 */
public class PreparedStatementExecutorBatchLogLevelTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /** Collects everything the executor logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-batch-log-level", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static PreparedStatement recordingStatement() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeBatch":
                    return new int[0];
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    private static Connection connectionReturning(PreparedStatement ps) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    return ps;
                case "isClosed":
                    return false;
                case "toString":
                    return "RecordingConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
    }

    private static ClickHouseStruct insertRecord(int id, String name) {
        Struct after = new Struct(ROW).put("id", id).put("name", name);
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");
        return record;
    }

    private static String describe(LogEvent e) {
        return e.getLevel() + ": " + e.getMessage().getFormattedMessage();
    }

    @Test
    @DisplayName("A successful batch logs its INSERT template and its EXECUTED line at DEBUG, and nothing at INFO or above")
    public void successfulBatchLogsItsProgressAtDebugOnly() throws Exception {
        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put("name", 2);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("name", "Nullable(String)");

        String insertQuery = "insert into db.orders(`id`,`name`) select `id`,`name` "
                + "from input('`id` Int32,`name` Nullable(String)')";
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords = new HashMap<>();
        queryToRecords.put(new MutablePair<>(insertQuery, indexMap),
                new ArrayList<>(Arrays.asList(insertRecord(1, "a"), insertRecord(2, "b"))));

        Logger coreLogger = (Logger) LogManager.getLogger(PreparedStatementExecutor.class);
        Level savedLevel = coreLogger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        Configurator.setLevel(coreLogger.getName(), Level.DEBUG);
        try {
            new PreparedStatementExecutor("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                    .addToPreparedStatementBatch("topic", Collections.singletonList(queryToRecords),
                            new BlockMetaData(), new ClickHouseSinkConnectorConfig(new HashMap<>()),
                            connectionReturning(recordingStatement()),
                            "orders", columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        } finally {
            Configurator.setLevel(coreLogger.getName(), savedLevel);
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        List<LogEvent> events = new ArrayList<>(appender.events);
        List<String> all = events.stream().map(PreparedStatementExecutorBatchLogLevelTest::describe)
                .collect(Collectors.toList());

        // The lines still exist, for an operator who turns DEBUG on ...
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                        && e.getMessage().getFormattedMessage().contains("INSERT QUERY for Database(db)")
                        && e.getMessage().getFormattedMessage().contains(insertQuery)),
                "the INSERT template line must be logged at DEBUG: " + all);
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                        && e.getMessage().getFormattedMessage().contains("EXECUTED BATCH Successfully")
                        && e.getMessage().getFormattedMessage().contains("Records: 2")),
                "the EXECUTED BATCH line must be logged at DEBUG: " + all);

        // ... and a successful batch says nothing at INFO or above (pre-fix: both lines at INFO).
        List<String> infoAndAbove = events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(Level.INFO))
                .map(PreparedStatementExecutorBatchLogLevelTest::describe)
                .collect(Collectors.toList());
        assertEquals(Collections.emptyList(), infoAndAbove,
                "a successful batch must not log at INFO or above (spec 03.06 section 3.3)");
    }
}
