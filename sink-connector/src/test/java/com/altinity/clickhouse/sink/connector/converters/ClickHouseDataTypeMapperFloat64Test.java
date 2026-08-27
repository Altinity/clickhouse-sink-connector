package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseTableOperationsBase;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Regression tests for MySQL DOUBLE / Kafka Connect FLOAT64 columns being
 * auto-created as ClickHouse Float32.
 *
 * <p>{@code dataTypesMap} mapped BOTH FLOAT32 and FLOAT64 to
 * {@link ClickHouseDataType#Float32}. That map is what
 * {@link ClickHouseTableOperationsBase#getColumnNameToCHDataTypeMapping} uses
 * to pick the column type in the generated CREATE TABLE statement and in the
 * ADD COLUMN clause of an auto-ALTER, so every replicated MySQL DOUBLE landed
 * in a 32-bit column: ~15 significant decimal digits truncated to ~7, with no
 * warning logged and row counts still matching, so checksum jobs reported the
 * table clean.
 *
 * <p>The defect was invisible to the existing auto-create tests because
 * {@code ClickHouseAutoCreateTableBase.getExpectedColumnToDataTypesMap} hand
 * builds the column map with the CORRECT Float64 entries instead of deriving
 * it from the mapper, so the wrong lookup was never exercised. These tests
 * drive the real mapper.
 *
 * <p>The write path is deliberately NOT changed: {@code convert} binds a
 * Double through {@code ClickHouseDoubleValue.of(v).asBigDecimal}, which is
 * already lossless (verified: 0.1234567890123456 binds unchanged).
 * {@code setFloat} is reached only for a genuine Float. The narrowing was
 * purely in the declared column type.
 */
public class ClickHouseDataTypeMapperFloat64Test {

    /** A MySQL DOUBLE has ~15-17 significant decimal digits; Float32 keeps ~7. */
    private static final double PRECISE_VALUE = 0.1234567890123456;

    /** The value from issue #1262. */
    private static final double ISSUE_VALUE = 123456789.123456789;

    private static ClickHouseSinkConnectorConfig emptyConfig() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    /** FLOAT64 must map to Float64. Before the fix this returned Float32. */
    @Test
    public void float64MapsToFloat64() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper
                .getClickHouseDataType(Schema.FLOAT64_SCHEMA.type(), null);

        Assert.assertEquals(ClickHouseDataType.Float64, chDataType);
    }

    /**
     * FLOAT32 must keep mapping to Float32 -- the fix must not widen the
     * single-precision type, whose narrowing already happened in Debezium.
     */
    @Test
    public void float32StillMapsToFloat32() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper
                .getClickHouseDataType(Schema.FLOAT32_SCHEMA.type(), null);

        Assert.assertEquals(ClickHouseDataType.Float32, chDataType);
    }

    /**
     * The consumer that actually types auto-created columns. This is the
     * user-visible defect: the CREATE TABLE column list said Float32.
     */
    @Test
    public void autoCreatedColumnForDoubleIsFloat64() {
        Field[] fields = new Field[] {
                new Field("amount_float", 0, Schema.FLOAT32_SCHEMA),
                new Field("amount_double", 1, Schema.FLOAT64_SCHEMA),
        };

        Map<String, String> columnToDataTypes = new ClickHouseTableOperationsBase()
                .getColumnNameToCHDataTypeMapping(fields, emptyConfig());

        Assert.assertEquals(ClickHouseDataType.Float64.name(),
                columnToDataTypes.get("amount_double"));
        Assert.assertEquals(ClickHouseDataType.Float32.name(),
                columnToDataTypes.get("amount_float"));
    }

    /**
     * Quantifies what the wrong mapping destroyed: a Float32 column cannot
     * hold a MySQL DOUBLE. Guards against anyone "simplifying" the two float
     * entries back into one on the grounds that they look redundant.
     */
    @Test
    public void float32ColumnCannotRepresentADoubleValue() {
        // Truncation is real, not theoretical.
        Assert.assertNotEquals(PRECISE_VALUE, (double) (float) PRECISE_VALUE, 0.0);
        Assert.assertNotEquals(ISSUE_VALUE, (double) (float) ISSUE_VALUE, 0.0);

        // The exact corruption reported in the issue: 123456789.123456789
        // becomes 123456792 once it is squeezed through 32 bits.
        Assert.assertEquals(123456792.0d, (double) (float) ISSUE_VALUE, 0.0);

        // The whole representable range of a DOUBLE does not survive either:
        // Double.MAX_VALUE overflows a Float32 column to infinity.
        Assert.assertTrue(Float.isInfinite((float) Double.MAX_VALUE));
        Assert.assertFalse(Double.isInfinite(Double.MAX_VALUE));
    }
}
