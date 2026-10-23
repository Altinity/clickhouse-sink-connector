package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementFieldMapper;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.time.Year;
import io.debezium.time.ZonedTimestamp;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 section 3.1.15 and spec 07.01 section 3.4 through {@link PreparedStatementFieldMapper}: the bind loop
 * reports the zero TIMESTAMP and maps the stream value of the zero YEAR to the number MySQL holds. Removing either
 * call from the bind leaves the counts at zero and the bound year at 1900.
 */
public class ZeroTimestampYearCallSiteTest {

    private static final String SOURCE_TYPE = ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM;

    private static Schema timestampSchema(String propagatedType) {
        SchemaBuilder builder = ZonedTimestamp.builder().optional();
        if (propagatedType != null) {
            builder.parameter(SOURCE_TYPE, propagatedType);
        }
        return builder.build();
    }

    private static Schema yearSchema() {
        return Year.builder().optional().parameter(SOURCE_TYPE, "YEAR").build();
    }

    @BeforeEach
    void resetCounts() {
        LossyConversionReporter.resetForTests();
    }

    private static PreparedStatement recording(Map<Integer, Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setString":
                case "setInt":
                case "setObject":
                    bound.put((Integer) args[0], args[1]);
                    return null;
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

    private static ClickHouseSinkConnectorConfig config(boolean clamp) {
        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", Boolean.toString(clamp));
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    /** Binds one record with an id and a second column of the given schema, type and value; returns what was bound. */
    private static Map<Integer, Object> bind(Schema schema, String chType, Object value, boolean clamp)
            throws Exception {
        Schema row = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("v", schema)
                .build();
        Struct after = new Struct(row).put("id", 1).put("v", value);
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");

        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put("v", 2);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("v", chType);

        Map<Integer, Object> bound = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recording(bound), after.schema().fields(),
                        record, after, false, config(clamp), columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t");
        return bound;
    }

    private static long zeroTimestamps() {
        return LossyConversionReporter.count(LossyConversionReporter.Kind.ZERO_TIMESTAMP);
    }

    private static final String TS_TYPE = "Nullable(DateTime64(6, 'UTC'))";

    @Test
    public void aMysqlZeroTimestampIsReportedAndBoundAsTheEpoch() throws Exception {
        Map<Integer, Object> zero = bind(timestampSchema("TIMESTAMP"), TS_TYPE, "1970-01-01T00:00:00Z", true);

        assertEquals(1L, zeroTimestamps());
        Map<Integer, Object> real = bind(timestampSchema("TIMESTAMP"), TS_TYPE, "1970-01-01T00:00:01Z", true);
        assertEquals(1L, zeroTimestamps(), "the smallest real TIMESTAMP is not reported");
        assertTrue(!zero.get(2).equals(real.get(2)), "the zero TIMESTAMP and the smallest real one bind apart");
    }

    @Test
    public void thePrecisionSuffixOfThePropagatedTypeStillNamesATimestamp() throws Exception {
        bind(timestampSchema("TIMESTAMP(6)"), TS_TYPE, "1970-01-01T00:00:00Z", true);

        assertEquals(1L, zeroTimestamps());
    }

    @Test
    public void aPostgresTimestamptzAtTheEpochIsARealValue() throws Exception {
        bind(timestampSchema("timestamptz"), TS_TYPE, "1970-01-01T00:00:00Z", true);

        assertEquals(0L, zeroTimestamps());
    }

    @Test
    public void withoutAPropagatedTypeTheSourceCannotBeTold() throws Exception {
        // column.propagate.source.type is the connector default (".*"); a record from a source that
        // does not carry it is not reported rather than guessed (Spec 07.03 section 3.1.15, limit).
        bind(timestampSchema(null), TS_TYPE, "1970-01-01T00:00:00Z", true);

        assertEquals(0L, zeroTimestamps());
    }

    @Test
    public void strictModeRefusesTheRowNamingTheColumn() {
        DebeziumConverter.ValueOutOfRangeException refused = assertThrows(
                DebeziumConverter.ValueOutOfRangeException.class,
                () -> bind(timestampSchema("TIMESTAMP"), TS_TYPE, "1970-01-01T00:00:00Z", false));

        assertTrue(refused.getMessage().contains("db.t.v"), refused.getMessage());
    }

    @Test
    public void theStreamValueOfTheZeroYearIsBoundAsZero() throws Exception {
        assertEquals(0, bind(yearSchema(), "Nullable(Int32)", 1900, true).get(2));
        assertEquals(0, bind(yearSchema(), "Int32", 1900, true).get(2));
    }

    @Test
    public void everyOtherYearIsBoundUnchanged() throws Exception {
        for (int year : new int[]{0, 1, 1899, 1901, 1999, 2000, 2024, 2155}) {
            assertEquals(year, bind(yearSchema(), "Nullable(Int32)", year, true).get(2), "year " + year);
        }
    }

    @Test
    public void aWiderIntegerTargetReceivesTheMappedYearToo() throws Exception {
        assertEquals(0, bind(yearSchema(), "UInt64", 1900, true).get(2));
        assertEquals(2024, bind(yearSchema(), "UInt64", 2024, true).get(2));
    }

    @Test
    public void aNarrowIntegerTargetReceivesTheMappedYearToo() throws Exception {
        // A year column an operator overrode to a 16-bit type still gets 0, not 1900.
        assertEquals(0, bind(yearSchema(), "Nullable(UInt16)", 1900, true).get(2));
        assertEquals(1901, bind(yearSchema(), "Nullable(UInt16)", 1901, true).get(2));
    }

    @Test
    public void aPlainIntegerColumnHoldingNineteenHundredIsNotRemapped() throws Exception {
        // 1900 is a legal value of every other integer column; only the Year schema marks the zero year.
        Schema plain = SchemaBuilder.int32().optional().build();

        assertEquals(1900, bind(plain, "Nullable(Int32)", 1900, true).get(2));
    }

    @Test
    public void aNamedSchemaThatIsNotYearIsNotRemapped() throws Exception {
        // The mapping keys on the Year logical name, not on "an INT32 with some name".
        Schema other = SchemaBuilder.int32().name("com.example.custom.Counter").optional().build();
        assertEquals(1900, bind(other, "Nullable(Int32)", 1900, true).get(2));

        // A DATE arrives as INT32 days since 1970-01-01; 1900 days is a real date and must reach the date bind.
        Schema date = io.debezium.time.Date.builder().optional().build();
        assertEquals(java.time.LocalDate.ofEpochDay(1900).toString(), bind(date, "Nullable(Date32)", 1900, true).get(2));
    }

    @Test
    public void theMappedYearIsNotAReportedConversion() throws Exception {
        // The stored 0 is the number MySQL holds for YEAR 0000, so nothing is lossy and strict mode does not refuse it.
        assertEquals(0, bind(yearSchema(), "Int32", 1900, false).get(2));
        assertEquals(0L, LossyConversionReporter.count(LossyConversionReporter.Kind.INT_WRAP));
    }
}
