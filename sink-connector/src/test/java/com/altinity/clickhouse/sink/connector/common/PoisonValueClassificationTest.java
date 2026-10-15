package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier.ErrorCategory;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.batch.GroupInsertQueryWithBatchRecords;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementFieldMapper;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.geometry.Geometry;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Invariant I15 failure modes of the type system (domain 07) and the query
 * generation (domain 04): what the batch runnable does with a batch that holds
 * one row that can never be written.
 *
 * <p>{@code ClickHouseBatchRunnable.run} retries a failed batch forever (with
 * the backoff of spec 10.02) unless {@link ClickHouseErrorClassifier#classify}
 * returns {@code FATAL}; a FATAL batch kills the worker, the engine stops on
 * the next source batch and the process exits with code 3 (spec 10.04 section
 * 3.5). A poison row is deterministic: the same value meets the same column
 * type on every attempt, so a retry can never succeed and must not be the
 * answer. The enabled tests pin the refusals that are already terminal; the
 * disabled ones assert the terminal classification that I15 requires for the
 * refusals the connector raises itself, and fail on 2.11.0, where those
 * exceptions carry no ClickHouse error code and are classified UNKNOWN.</p>
 *
 * <p>Every exception is wrapped the way the write path wraps it
 * ({@code PreparedStatementExecutor} rethrows a failed chunk as
 * {@code new RuntimeException(e)}), and where possible it is produced by the
 * real code path rather than constructed by hand, so a fix that changes the
 * thrown type is exercised as it ships.</p>
 */
public class PoisonValueClassificationTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Exception asWritePathFailure(Throwable refusal) {
        return new RuntimeException("ClickHouseBatchRunnable exception",
                new RuntimeException(refusal));
    }

    private static PreparedStatement noOpStatement() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isWrapperFor":
                    return false;
                case "toString":
                    return "NoOpPreparedStatement";
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

    /**
     * FM-07.07-1: a source NULL for a non-Nullable ClickHouse column is
     * rejected by ClickHouse with {@code Code: 53} (message measured with
     * {@code clickhouse local} 24.8.14 under {@code input_format_null_as_default=0});
     * 53 is in {@code FATAL_ERROR_CODES}, so the refusal is terminal.
     */
    @Test
    @DisplayName("FM-07.07-1: NULL into a non-Nullable column (Code 53) is terminal")
    public void nullIntoNonNullableColumnIsFatal() {
        SQLException refusal = new SQLException("Code: 53. DB::Exception: Cannot insert NULL value into a "
                + "column of type 'Int32' at: NULL): While executing ValuesBlockInputFormat: data for INSERT "
                + "was parsed from query. (TYPE_MISMATCH)");
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(asWritePathFailure(refusal)));
    }

    /**
     * FM-07.02-2: a decimal with more integer digits than the ClickHouse column
     * precision is rejected with {@code Code: 69} (measured with
     * {@code clickhouse local} 24.8.14, unquoted literal, which is how the V2
     * driver renders a {@code BigDecimal}); 69 is terminal.
     */
    @Test
    @DisplayName("FM-07.02-2: a decimal wider than the column (Code 69) is terminal")
    public void decimalTooManyDigitsIsFatal() {
        SQLException refusal = new SQLException("Code: 69. DB::Exception: Too many digits (11 > 10) in decimal "
                + "value:  at row 0: While executing ValuesBlockInputFormat. (ARGUMENT_OUT_OF_BOUND)");
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(asWritePathFailure(refusal)));
    }

    /**
     * FM-07.06-1: a non-Polygon geometry bound for a {@code Polygon} column is
     * refused by the mapper with {@code IllegalArgumentException} (spec 07.06
     * section 3.2). The refusal is deterministic, but it carries no ClickHouse
     * code, so 2.11.0 classifies it UNKNOWN and retries the batch forever.
     */
    @Test
    @Disabled("DEFECT FM-07.06-1: the spatial refusal (IllegalArgumentException) is classified UNKNOWN and "
            + "retried forever instead of stopping the engine")
    @DisplayName("FM-07.06-1: a spatial value the column cannot hold is terminal, not retried forever")
    public void spatialValueRefusalIsFatal() {
        byte[] lineString = new WKBWriter().write(new GeometryFactory().createLineString(new Coordinate[]{
                new Coordinate(1, 2), new Coordinate(3, 4)}));
        Struct value = Geometry.createValue(Geometry.schema(), lineString, 0);
        Exception refusal = null;
        try {
            ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Geometry.LOGICAL_NAME, value, 1,
                    noOpStatement(), new ClickHouseSinkConnectorConfig(new HashMap<>()),
                    ClickHouseDataType.Polygon, UTC);
        } catch (Exception e) {
            refusal = e;
        }
        assertNotNull(refusal, "the mapper must refuse a LINESTRING for a Polygon column");
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(asWritePathFailure(refusal)));
    }

    /**
     * FM-07.07-3: a field whose Connect type has no binding (a {@code MAP}) is
     * refused with {@code DataException} (spec 07.07 section 3.2.2 rule 1);
     * deterministic, classified UNKNOWN on 2.11.0.
     */
    @Test
    @Disabled("DEFECT FM-07.07-3: the unhandled-type refusal (DataException) is classified UNKNOWN and "
            + "retried forever instead of stopping the engine")
    @DisplayName("FM-07.07-3: a field with no type binding is terminal, not retried forever")
    public void unhandledTypeRefusalIsFatal() {
        Schema row = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("attrs", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA).optional().build())
                .build();
        Struct after = new Struct(row).put("id", 1).put("attrs", Collections.singletonMap("k", "v"));
        ClickHouseStruct record = new ClickHouseStruct(0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");
        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put("attrs", 2);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("attrs", "String");
        Exception refusal = null;
        try {
            new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", UTC)
                    .insertPreparedStatement(indexMap, noOpStatement(), after.schema().fields(), record, after,
                            false, new ClickHouseSinkConnectorConfig(new HashMap<>()), columns,
                            DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        } catch (Exception e) {
            refusal = e;
        }
        assertNotNull(refusal, "the mapper must refuse a MAP field");
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(asWritePathFailure(refusal)));
    }

    /**
     * FM-04.01-1: a record that cannot be grouped (a DELETE without its before
     * image) is refused with {@code IllegalStateException} (spec 04.01 section
     * 3.3); deterministic for that record, classified UNKNOWN on 2.11.0.
     */
    @Test
    @Disabled("DEFECT FM-04.01-1: the grouping refusal (IllegalStateException) is classified UNKNOWN and "
            + "retried forever instead of stopping the engine")
    @DisplayName("FM-04.01-1: a record that can never be grouped is terminal, not retried forever")
    public void groupingRefusalIsFatal() {
        ClickHouseStruct delete = new ClickHouseStruct(9L, "topic", null, 0, System.currentTimeMillis(),
                null, null, null, ClickHouseConverter.CDC_OPERATION.DELETE);
        delete.setDatabase("db");
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("_version", "UInt64");
        columns.put("is_deleted", "UInt8");
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        Exception refusal = null;
        try {
            new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                    new ArrayList<>(Collections.singletonList(delete)), segments, new HashMap<>(),
                    new ClickHouseSinkConnectorConfig(new HashMap<>()), "t", "db", null, columns);
        } catch (Exception e) {
            refusal = e;
        }
        assertNotNull(refusal, "the grouper must refuse a DELETE without a before image");
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(asWritePathFailure(refusal)));
    }

    /**
     * FM-07.04-2: ClickHouse's own refusals of a value that its column type
     * cannot parse or hold, each measured with {@code clickhouse local}
     * 24.8.14: an ENUM label the {@code Enum8} lacks (691), a string longer than
     * a {@code FixedString(N)} (131), a non-UUID into {@code UUID} (376), an
     * unparseable {@code DateTime} (41), a non-boolean into {@code Bool} (467).
     * The same row fails the same way on every attempt; none of these codes is
     * in {@code FATAL_ERROR_CODES}, so 2.11.0 retries the batch forever.
     */
    @Test
    @Disabled("DEFECT FM-07.04-2: value-parse refusals 691/131/376/41/467 are RETRIABLE and retried forever")
    @DisplayName("FM-07.04-2: a value ClickHouse cannot parse into the column type is terminal")
    public void clickHouseValueRejectionsAreFatal() {
        String[] refusals = {
                "Code: 691. DB::Exception: Unknown element 'c' for enum, maybe you meant: ['a']",
                "Code: 131. DB::Exception: String too long for type FixedString(2)",
                "Code: 376. DB::Exception: Cannot parse uuid not-a-uuid: Cannot parse UUID from String",
                "Code: 41. DB::Exception: Cannot parse DateTime",
                "Code: 467. DB::Exception: Cannot parse boolean value here: 'deadbeef'",
        };
        for (String message : refusals) {
            assertEquals(ErrorCategory.FATAL,
                    ClickHouseErrorClassifier.classify(asWritePathFailure(new SQLException(message))), message);
        }
    }

    /**
     * FM-04.02-1: an identifier the formatter does not escape (a column or
     * table name containing a backtick) makes ClickHouse reject the INSERT
     * with {@code Code: 62} (measured with {@code clickhouse local} 24.8.14);
     * the statement is rebuilt identically on every attempt, and 62 is not
     * terminal on 2.11.0.
     */
    @Test
    @Disabled("DEFECT FM-04.02-1: SYNTAX_ERROR (62) from a generated statement is RETRIABLE and retried forever")
    @DisplayName("FM-04.02-1: a generated statement ClickHouse cannot parse is terminal")
    public void syntaxErrorInAGeneratedStatementIsFatal() {
        SQLException refusal = new SQLException("Code: 62. DB::Exception: Syntax error: failed at position 59 "
                + "('('): (`a`b`) VALUES (1). (SYNTAX_ERROR)");
        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(asWritePathFailure(refusal)));
    }
}
