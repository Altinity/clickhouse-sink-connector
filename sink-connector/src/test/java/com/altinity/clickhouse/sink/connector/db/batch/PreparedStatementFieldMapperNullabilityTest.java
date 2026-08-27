package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #1250: a NULL arriving for a column that ClickHouse declares
 * non-nullable was handed straight to {@code ps.setNull(index, Types.OTHER)}
 * with no check of the target column's type.
 *
 * <p>The nullability is available at the bind site and always has been.
 * {@code DBMetadata#getColumnsDataTypesForTable} reads
 * {@code SELECT name, type ... FROM system.columns} and returns the verbatim
 * type strings, and that map is passed into
 * {@code PreparedStatementFieldMapper#insertPreparedStatement} as
 * {@code columnNameToDataTypeMap} -- the very map the bind loop iterates. So
 * {@code "Nullable(String)"} and {@code "String"} are already distinguishable
 * one line above the {@code setNull} call; nothing was fetched, only ignored.
 * The source carried a {@code //ToDO} comment saying exactly that.</p>
 *
 * <p>Handing the NULL to the driver anyway has two outcomes, both bad. Either
 * the batch dies with an error that names neither the column nor the table, so
 * the operator has to bisect a batch of thousands of records to find the
 * offending field, or the NULL is coerced to the type's zero value and a row
 * that never existed in the source is written -- with the row count intact, so
 * a count-based checksum reports the table clean. The batch is therefore
 * failed at the bind site with the database, table, column and declared type
 * named, which is recoverable (retry, or a one-line ALTER) where a coerced row
 * is not.</p>
 *
 * <p>Nullable columns must keep working byte-for-byte; that is pinned by the
 * control assertions below.</p>
 */
public class PreparedStatementFieldMapperNullabilityTest {

    private static final String DB = "test_db";
    private static final String TABLE = "employees";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static PreparedStatementFieldMapper mapper() {
        return new PreparedStatementFieldMapper(
                "is_deleted", true, "_sign", "_version", DB, ZoneId.of("UTC"));
    }

    /**
     * Records every {@code setNull} the mapper performs. Mockito is not on the
     * test classpath, so this uses a JDK proxy in the same style as
     * {@code VersionFallbackWithoutGtidTest}.
     */
    private static PreparedStatement recordingStatement(final List<Integer> setNullIndices) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setNull":
                    setNullIndices.add((Integer) args[0]);
                    return null;
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) {
                        return false;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    /**
     * Drives the real bind loop end to end. The engine is null so the
     * sign/version handlers stay out of the way -- this test is about the NULL
     * bind, not about engine bookkeeping.
     */
    private static void bind(Struct struct,
                             Map<String, String> columnNameToDataTypeMap,
                             PreparedStatement ps) throws Exception {
        Map<String, Integer> columnNameToIndexMap = new LinkedHashMap<>();
        int index = 1;
        for (String column : columnNameToDataTypeMap.keySet()) {
            columnNameToIndexMap.put(column, index++);
        }
        List<Field> fields = new ArrayList<>(struct.schema().fields());
        mapper().insertPreparedStatement(columnNameToIndexMap, ps, fields,
                new ClickHouseStruct(), struct, false,
                new ClickHouseSinkConnectorConfig(new HashMap<>()),
                columnNameToDataTypeMap, null, TABLE);
    }

    private static Map<String, String> columnTypes(String nameColumnType) {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("name", nameColumnType);
        return types;
    }

    private static Struct rowWithNullName() {
        return new Struct(ROW_SCHEMA).put("id", 7).put("name", null);
    }

    // ------------------------------------------------------------------
    // The defect.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1250: a NULL for a non-nullable ClickHouse column fails loudly instead of reaching setNull")
    public void nullIntoNonNullableColumnIsRejected() {
        List<Integer> setNullIndices = new ArrayList<>();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(rowWithNullName(), columnTypes("String"), recordingStatement(setNullIndices)),
                "a NULL bound into a non-nullable String column must fail the batch here, "
                        + "not be handed to the driver as setNull(Types.OTHER)");

        Assert.assertTrue("nothing may be bound once the NULL is known to be unstorable; setNull was called at "
                        + setNullIndices,
                setNullIndices.isEmpty());
        Assert.assertTrue("the error must name the offending column so the operator does not have to "
                        + "bisect the batch; got: " + thrown.getMessage(),
                thrown.getMessage().contains("'name'"));
        Assert.assertTrue("the error must name the table; got: " + thrown.getMessage(),
                thrown.getMessage().contains(TABLE));
        Assert.assertTrue("the error must name the database; got: " + thrown.getMessage(),
                thrown.getMessage().contains(DB));
        Assert.assertTrue("the error must quote the declared ClickHouse type; got: " + thrown.getMessage(),
                thrown.getMessage().contains("String"));
    }

    @Test
    @DisplayName("#1250: the DataException path is guarded too -- a missing source column is not force-nulled")
    public void columnAbsentFromSourceIsRejectedForNonNullableTarget() {
        // The column is in the INSERT (so it has a bind index) but the record
        // does not carry it, which is the second setNull site: the catch block
        // that logged "might fail for non-nullable columns" and then did it
        // anyway.
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("officeCode", "String");

        List<Integer> setNullIndices = new ArrayList<>();
        Struct struct = new Struct(SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build()).put("id", 7);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bind(struct, types, recordingStatement(setNullIndices)),
                "a ClickHouse column absent from the source record must not be force-nulled "
                        + "when the column cannot store NULL");

        Assert.assertTrue("nothing may be bound; setNull was called at " + setNullIndices,
                setNullIndices.isEmpty());
        Assert.assertTrue("the error must name the offending column; got: " + thrown.getMessage(),
                thrown.getMessage().contains("'officeCode'"));
    }

    // ------------------------------------------------------------------
    // Controls: behaviour that must not change.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("control: a genuinely Nullable column still accepts NULL, unchanged")
    public void nullIntoNullableColumnStillBinds() throws Exception {
        List<Integer> setNullIndices = new ArrayList<>();

        bind(rowWithNullName(), columnTypes("Nullable(String)"), recordingStatement(setNullIndices));

        Assert.assertEquals("the Nullable column must still be bound with setNull exactly as before; "
                        + "recorded indices " + setNullIndices,
                1, setNullIndices.size());
        Assert.assertEquals("the NULL must be bound at the Nullable column's own index",
                Integer.valueOf(2), setNullIndices.get(0));
    }

    @Test
    @DisplayName("control: LowCardinality(Nullable(...)) is nullable and still accepts NULL")
    public void nullIntoLowCardinalityNullableColumnStillBinds() throws Exception {
        List<Integer> setNullIndices = new ArrayList<>();

        bind(rowWithNullName(), columnTypes("LowCardinality(Nullable(String))"),
                recordingStatement(setNullIndices));

        Assert.assertEquals("LowCardinality is a storage wrapper; the Nullable inside it still stores NULL",
                1, setNullIndices.size());
    }

    @Test
    @DisplayName("control: connector-populated columns are placeholder-nulled as before")
    public void connectorPopulatedColumnKeepsItsPlaceholderNull() throws Exception {
        // _version is non-nullable UInt64 and is never carried by the source
        // record; the mapper deliberately binds NULL first and overwrites it in
        // handleVersionColumn. Rejecting that would break every deployment.
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("_version", "UInt64");

        List<Integer> setNullIndices = new ArrayList<>();
        Struct struct = new Struct(SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build()).put("id", 7);

        assertDoesNotThrow(() -> bind(struct, types, recordingStatement(setNullIndices)),
                "the connector's own metadata columns must keep their placeholder NULL");
        Assert.assertEquals("the placeholder NULL must still be bound", 1, setNullIndices.size());
    }

    // ------------------------------------------------------------------
    // The type predicate itself.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("only Nullable(...) accepts NULL")
    public void acceptsNullRecognisesNullableTypes() {
        Assert.assertTrue(PreparedStatementFieldMapper.acceptsNull("Nullable(String)"));
        Assert.assertTrue(PreparedStatementFieldMapper.acceptsNull("Nullable(DateTime64(3))"));
        Assert.assertTrue(PreparedStatementFieldMapper.acceptsNull("LowCardinality(Nullable(String))"));
        Assert.assertTrue(PreparedStatementFieldMapper.acceptsNull(" Nullable(Int32) "));

        Assert.assertFalse(PreparedStatementFieldMapper.acceptsNull("String"));
        Assert.assertFalse(PreparedStatementFieldMapper.acceptsNull("Int32"));
        Assert.assertFalse(PreparedStatementFieldMapper.acceptsNull("DateTime64(3, 'UTC')"));
        Assert.assertFalse(PreparedStatementFieldMapper.acceptsNull("LowCardinality(String)"));
    }

    @Test
    @DisplayName("Array(Nullable(T)) is NOT a nullable column -- only its elements are")
    public void acceptsNullDoesNotMatchNestedNullable() {
        Assert.assertFalse("the array itself is required; a substring match would wave this through",
                PreparedStatementFieldMapper.acceptsNull("Array(Nullable(String))"));
        Assert.assertFalse(PreparedStatementFieldMapper.acceptsNull("Map(String, Nullable(Int32))"));
        Assert.assertFalse(PreparedStatementFieldMapper.acceptsNull("Tuple(a Nullable(Int32))"));
    }

    @Test
    @DisplayName("an undeterminable type never fails a batch")
    public void acceptsNullFailsOpenOnUnknownType() {
        Assert.assertTrue("a null type is not proof the column rejects NULL",
                PreparedStatementFieldMapper.acceptsNull(null));
        Assert.assertTrue(PreparedStatementFieldMapper.acceptsNull(""));
        Assert.assertTrue(PreparedStatementFieldMapper.acceptsNull("   "));
    }
}
