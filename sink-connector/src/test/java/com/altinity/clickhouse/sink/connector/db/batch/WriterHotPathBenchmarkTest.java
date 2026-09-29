package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.time.Date;
import io.debezium.time.MicroTimestamp;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures the connector's OWN per-batch cost on the write path -- grouping
 * ({@link GroupInsertQueryWithBatchRecords}) and binding
 * ({@link PreparedStatementExecutor} / {@link PreparedStatementFieldMapper}) --
 * against a no-op JDBC surface, for a 10,000-row batch shaped like a 17-column
 * fee ledger table (ReplacingMergeTree(_version, is_deleted), two-column
 * sorting key). Runs only with {@code -Dwriter.bench=1}; prints the median
 * milliseconds per 10,000 rows for each step. Not a correctness test.
 */
public class WriterHotPathBenchmarkTest {

    static final Schema ROW = SchemaBuilder.struct().name("srv.fees.txn_fee.Value")
            .field("txn_fee_id", Schema.INT64_SCHEMA)
            .field("jump_trade_date", SchemaBuilder.int32().name(Date.SCHEMA_NAME).build())
            .field("txn_fee_key", Schema.INT64_SCHEMA)
            .field("version_num", Schema.INT32_SCHEMA)
            .field("txn_inst_id", Schema.INT64_SCHEMA)
            .field("quantity", Decimal.builder(10).build())
            .field("fee_metadata_id", Schema.INT64_SCHEMA)
            .field("fee_txn_type_id", Schema.INT64_SCHEMA)
            .field("jetrate_schedule_id", Schema.INT64_SCHEMA)
            .field("ref_billing_currency_id", Schema.OPTIONAL_INT64_SCHEMA)
            .field("ref_native_currency_id", Schema.OPTIONAL_INT64_SCHEMA)
            .field("db_from", SchemaBuilder.int64().name(MicroTimestamp.SCHEMA_NAME).build())
            .field("db_to", SchemaBuilder.int64().name(MicroTimestamp.SCHEMA_NAME).build())
            .field("billing_jump_currency_id", Schema.OPTIONAL_INT32_SCHEMA)
            .field("native_jump_currency_id", Schema.OPTIONAL_INT32_SCHEMA)
            .field("fx_rate", Decimal.builder(10).optional().build())
            .field("txn_source_type_id", Schema.OPTIONAL_INT16_SCHEMA)
            .build();

    static final Schema KEY = SchemaBuilder.struct().name("srv.fees.txn_fee.Key")
            .field("txn_fee_id", Schema.INT64_SCHEMA)
            .field("jump_trade_date", SchemaBuilder.int32().name(Date.SCHEMA_NAME).build())
            .build();

    static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("txn_fee_id", "Int64");
        m.put("jump_trade_date", "Date32");
        m.put("txn_fee_key", "Int64");
        m.put("version_num", "Int32");
        m.put("txn_inst_id", "Int64");
        m.put("quantity", "Decimal(30, 10)");
        m.put("fee_metadata_id", "Int64");
        m.put("fee_txn_type_id", "Int64");
        m.put("jetrate_schedule_id", "Int64");
        m.put("ref_billing_currency_id", "Nullable(Int64)");
        m.put("ref_native_currency_id", "Nullable(Int64)");
        m.put("db_from", "DateTime64(6, 'UTC')");
        m.put("db_to", "DateTime64(6, 'UTC')");
        m.put("billing_jump_currency_id", "Nullable(Int32)");
        m.put("native_jump_currency_id", "Nullable(Int32)");
        m.put("fx_rate", "Nullable(Decimal(24, 10))");
        m.put("txn_source_type_id", "Nullable(Int16)");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    static Struct row(long i) {
        return new Struct(ROW)
                .put("txn_fee_id", 1_000_000L + i)
                .put("jump_trade_date", 20_000 + (int) (i % 5))
                .put("txn_fee_key", 77L + i)
                .put("version_num", (int) (i % 3))
                .put("txn_inst_id", 500L + (i % 100))
                .put("quantity", new BigDecimal("123.4567890123").setScale(10))
                .put("fee_metadata_id", 9L)
                .put("fee_txn_type_id", 3L)
                .put("jetrate_schedule_id", 12L)
                .put("ref_billing_currency_id", i % 7 == 0 ? null : 840L)
                .put("ref_native_currency_id", 978L)
                .put("db_from", 1_700_000_000_000_000L + i)
                .put("db_to", 4_102_444_800_000_000L)
                .put("billing_jump_currency_id", 1)
                .put("native_jump_currency_id", i % 11 == 0 ? null : 2)
                .put("fx_rate", i % 2 == 0 ? new BigDecimal("1.0950000000").setScale(10) : null)
                .put("txn_source_type_id", (short) 4);
    }

    static Struct key(long i) {
        return new Struct(KEY).put("txn_fee_id", 1_000_000L + i).put("jump_trade_date", 20_000 + (int) (i % 5));
    }

    /** 80% INSERTs, 20% UPDATEs on the same key (no sorting-key relocation). */
    static List<ClickHouseStruct> batch(int n) {
        List<ClickHouseStruct> out = new ArrayList<>(n);
        for (long i = 0; i < n; i++) {
            Struct after = row(i);
            boolean update = i % 5 == 4;
            ClickHouseStruct r = new ClickHouseStruct(i, "srv.fees.txn_fee", key(i), 0,
                    System.currentTimeMillis(), update ? row(i) : null, after, null,
                    update ? ClickHouseConverter.CDC_OPERATION.UPDATE : ClickHouseConverter.CDC_OPERATION.CREATE);
            r.setDatabase("fees");
            r.setTs_ms(1_700_000_000_000L + i);
            r.setFile("binary.000001");
            r.setPos(1000L + i);
            out.add(r);
        }
        return out;
    }

    static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** A JDBC surface that accepts everything and records nothing. */
    static Connection nullConnection() {
        InvocationHandler ps = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeBatch": return new int[0];
                case "execute": return Boolean.FALSE;
                case "executeUpdate": return 0;
                case "toString": return "NullPreparedStatement";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default:
                    Class<?> t = method.getReturnType();
                    if (!t.isPrimitive() || t == void.class) return null;
                    return t == boolean.class ? Boolean.FALSE : 0;
            }
        };
        InvocationHandler conn = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    return Proxy.newProxyInstance(WriterHotPathBenchmarkTest.class.getClassLoader(),
                            new Class<?>[]{PreparedStatement.class}, ps);
                case "isClosed": return Boolean.FALSE;
                case "toString": return "NullConnection";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default:
                    Class<?> t = method.getReturnType();
                    if (!t.isPrimitive() || t == void.class) return null;
                    return t == boolean.class ? Boolean.FALSE : 0;
            }
        };
        return (Connection) Proxy.newProxyInstance(WriterHotPathBenchmarkTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, conn);
    }

    static long[] once(List<ClickHouseStruct> records, ClickHouseSinkConnectorConfig config,
                       Connection conn, Map<String, String> columns) throws Exception {
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        Map<TopicPartition, Long> offsets = new HashMap<>();
        long t0 = System.nanoTime();
        new GroupInsertQueryWithBatchRecords("_version", null, "is_deleted")
                .groupQueryWithRecords(records, segments, offsets, config, "txn_fee", "fees", conn, columns);
        long t1 = System.nanoTime();
        PreparedStatementExecutor ex = new PreparedStatementExecutor("is_deleted", true, null, "_version",
                "fees", ZoneId.of("UTC"), () -> Arrays.asList("txn_fee_id", "jump_trade_date"));
        ex.addToPreparedStatementBatch("srv.fees.txn_fee", segments, new BlockMetaData(), config, conn,
                "txn_fee", columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        long t2 = System.nanoTime();
        return new long[]{(t1 - t0) / 1_000_000L, (t2 - t1) / 1_000_000L};
    }

    @Test
    public void measure() throws Exception {
        Assumptions.assumeTrue(System.getProperty("writer.bench") != null, "run with -Dwriter.bench=1");
        int rows = Integer.getInteger("writer.bench.rows", 10_000);
        int warm = Integer.getInteger("writer.bench.warmup", 8);
        int iters = Integer.getInteger("writer.bench.iters", 25);
        ClickHouseSinkConnectorConfig config = config();
        Connection conn = nullConnection();
        Map<String, String> columns = columns();
        List<ClickHouseStruct> records = batch(rows);
        for (int i = 0; i < warm; i++) {
            once(records, config, conn, columns);
        }
        long[] group = new long[iters];
        long[] bind = new long[iters];
        for (int i = 0; i < iters; i++) {
            long[] t = once(records, config, conn, columns);
            group[i] = t[0];
            bind[i] = t[1];
        }
        Arrays.sort(group);
        Arrays.sort(bind);
        System.out.printf("WRITER-BENCH rows=%d group_ms(median)=%d bind_ms(median)=%d total_ms=%d group_min=%d bind_min=%d%n",
                rows, group[iters / 2], bind[iters / 2], group[iters / 2] + bind[iters / 2], group[0], bind[0]);
    }

    /** Direct entry point for profiling outside surefire. */
    public static void main(String[] args) throws Exception {
        System.setProperty("writer.bench", "1");
        new WriterHotPathBenchmarkTest().measure();
    }
}
