package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The idleness clock the restart monitor ({@code restart.event.loop}, spec 01.01
 * section 3.2) measures from: {@code ReplicationStatusSingleton.getLastRecordTimestamp()}.
 *
 * <p><b>What the monitor is for.</b> It is the only in-process watchdog that can
 * notice a replication that stands still without an error -- a half-open binlog
 * connection (spec 01.07 section 6, FM-01.07-2), a reader wedged behind a stalled
 * writer. For that it must measure how long ago the SOURCE CONNECTION last
 * delivered something, in the connector's own clock.</p>
 *
 * <p><b>What 2.11.0 measures instead.</b> {@code processEveryChangeRecord} stores
 * {@code chStruct.getTs_ms()} -- the source STATEMENT time of the last row -- and no
 * control record (heartbeat) touches the clock at all. Two consequences, both
 * pinned here as DEFECTs:</p>
 * <ol>
 *   <li>A connector that is replicating at full speed but is more than
 *   {@code restart.event.loop.timeout.period.secs} behind the source (a catch-up
 *   after an outage, a long transaction whose statements executed long before its
 *   commit) looks idle, and the monitor stops and restarts a healthy engine on
 *   every tick (FM-01.01-3).</li>
 *   <li>On an idle source only heartbeats arrive (the MySQL server heartbeat the
 *   binlog client requests every 0.8 x {@code connect.keep.alive.interval.ms},
 *   48 s by default, each followed by a Debezium heartbeat record), so a healthy
 *   idle connection and a dead half-open one look the same to the monitor: it
 *   restarts the first for nothing and cannot tell the second apart (FM-01.07-2).</li>
 * </ol>
 */
public class RestartMonitorLivenessTest {

    private long savedTimestamp;

    @BeforeEach
    public void save() {
        savedTimestamp = ReplicationStatusSingleton.getInstance().getLastRecordTimestamp();
        ReplicationStatusSingleton.getInstance().setLastRecordTimestamp(-1L);
    }

    @AfterEach
    public void restore() {
        ReplicationStatusSingleton.getInstance().setLastRecordTimestamp(savedTimestamp);
    }

    /** Records what the engine's committer was asked to do. */
    private static final class NoopCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
        }

        @Override
        public void markBatchFinished() {
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> event(SourceRecord record) {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return record.topic();
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /** A heartbeat exactly as Debezium emits one: a value with no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> heartbeat() {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ts_ms", System.currentTimeMillis());
        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("server", "embeddedconnector");
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("file", "mysql-bin.000042");
        offset.put("pos", 4L);
        return event(new SourceRecord(partition, offset,
                "__debezium-heartbeat.embeddedconnector", 0, null, null, valueSchema, value));
    }

    /** A row change record (a value WITH {@code op}) of table {@code db.orders}. */
    private static ChangeEvent<SourceRecord, SourceRecord> rowEvent() {
        Schema valueSchema = SchemaBuilder.struct().name("envelope")
                .field("op", Schema.STRING_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema).put("op", "u");
        return event(new SourceRecord(Collections.emptyMap(), Collections.emptyMap(),
                "embeddedconnector.db.orders", valueSchema, value));
    }

    /** A parser that returns a row whose source statement time is {@code sourceTsMs}. */
    private static DebeziumRecordParserService parserReturning(long sourceTsMs) {
        return (record, committer, lastRecordInBatch) -> {
            ClickHouseStruct row = new ClickHouseStruct();
            row.setTopic("embeddedconnector.db.orders");
            row.setTs_ms(sourceTsMs);
            row.setFile("mysql-bin.000042");
            row.setPos(1_000L);
            return row;
        };
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static Object invokeProcess(DebeziumChangeEventCapture capture,
                                        ChangeEvent<SourceRecord, SourceRecord> record,
                                        DebeziumRecordParserService parser) throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class,
                ChangeEvent.class,
                DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class,
                DebeziumEngine.RecordCommitter.class,
                boolean.class,
                VersionSequencer.VersionAssignment.class);
        m.setAccessible(true);
        try {
            return m.invoke(capture, new Properties(), record, parser, config(), new NoopCommitter(), true,
                    new VersionSequencer.VersionAssignment(1_000_000_001L, 1_000L));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    @Test
    @Disabled("DEFECT FM-01.01-3: the restart monitor measures idleness from the source statement time "
            + "of the last row (ClickHouseStruct.getTs_ms), so a connector replicating a backlog older "
            + "than restart.event.loop.timeout.period.secs is restarted on every tick")
    @DisplayName("A row that arrives now refreshes the monitor clock even when its source statement time is old")
    public void laggingRowRefreshesTheMonitorClock() throws Exception {
        long twoHoursAgo = System.currentTimeMillis() - 2L * 3600L * 1000L;
        long before = System.currentTimeMillis();

        Object row = invokeProcess(new DebeziumChangeEventCapture(), rowEvent(), parserReturning(twoHoursAgo));

        assertTrue(row instanceof ClickHouseStruct, "sanity: the row is produced");
        long clock = ReplicationStatusSingleton.getInstance().getLastRecordTimestamp();
        assertTrue(clock >= before,
                "the monitor must see that a record ARRIVED now; it saw " + clock + " (the row's source "
                        + "statement time, " + (before - clock) / 1000 + " s ago), so with the ansible "
                        + "default restart.event.loop.timeout.period.secs=3000 a connector more than 50 "
                        + "minutes behind is stopped and restarted on every tick while it catches up");
    }

    @Test
    @Disabled("DEFECT FM-01.07-2: heartbeats never refresh the restart monitor's clock, so a healthy idle "
            + "binlog connection and a half-open dead one are indistinguishable to the only in-process "
            + "watchdog")
    @DisplayName("A heartbeat proves the binlog connection is alive and refreshes the monitor clock")
    public void heartbeatRefreshesTheMonitorClock() throws Exception {
        long before = System.currentTimeMillis();

        new DebeziumChangeEventCapture().handleChangeEventBatch(Collections.singletonList(heartbeat()),
                new NoopCommitter(), new Properties(), new SourceRecordParserService(), config());

        long clock = ReplicationStatusSingleton.getInstance().getLastRecordTimestamp();
        assertTrue(clock >= before,
                "a heartbeat delivered by the source connection must count as liveness; the monitor clock "
                        + "stayed at " + clock);
    }
}
