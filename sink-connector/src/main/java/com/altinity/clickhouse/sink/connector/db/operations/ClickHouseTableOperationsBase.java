package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import io.debezium.time.ZonedTimestamp;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.altinity.clickhouse.sink.connector.config.DefaultColumnDataTypeMappingConfig.loadDefaultColumnDataTypeMapping;

/**
 * Provides base operations to handle ClickHouse table creation and data-type
 * mapping. This class contains logic to map Kafka Connect {@link Schema}
 * fields to ClickHouse data types.
 */
public class ClickHouseTableOperationsBase {

    /**
     * The schema parameter key for scale.
     */
    public static final String SCALE = "scale";

    /**
     * The schema parameter key for precision in decimal types.
     */
    public static final String PRECISION = "connect.decimal.precision";

    /**
     * Default precision for decimal columns.
     */
    private static final int DEFAULT_PRECISION = 10;

    /**
     * Default scale for decimal columns.
     */
    private static final int DEFAULT_SCALE = 2;

    /**
     * String constant for default Decimal(10,2) type.
     */
    private static final String DEFAULT_DECIMAL_TYPE = "Decimal("
            + DEFAULT_PRECISION + "," + DEFAULT_SCALE + ")";

    /**
     * String constant for Decimal(64,18) type used by variable scale decimals.
     */
    private static final String DECIMAL_64_18 = "Decimal(64,18)";

    /**
     * String constant for DateTime64(3) type (millisecond precision).
     */
    private static final String DATETIME64_3 = "DateTime64(3)";

    /**
     * String constant for DateTime64(6) type (microsecond precision).
     */
    private static final String DATETIME64_6 = "DateTime64(6)";

    /**
     * Logger for this class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseTableOperationsBase.class.getName());

    /**
     * Default constructor.
     */
    public ClickHouseTableOperationsBase() {
        // No initialization needed here.
    }

    /**
     * Generates a mapping from column names to ClickHouse data types based on
     * a provided array of Kafka Connect {@link Field} objects. Handles special
     * cases like Decimal, DateTime64, and arrays.
     *
     * @param fields An array of {@link Field} representing schema fields.
     * @return A map where the key is the column name and the value is the
     *         corresponding ClickHouse data type as a String.
     */
    public Map<String, String> getColumnNameToCHDataTypeMapping(Field[] fields, ClickHouseSinkConnectorConfig config) {
        ClickHouseDataTypeMapper mapper = new ClickHouseDataTypeMapper();
        Map<String, String> columnToDataTypesMap = new LinkedHashMap<>();

        for (Field f : fields) {
            String colName = f.name();
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();

            if (type == Schema.Type.ARRAY) {
                schemaName = f.schema().valueSchema().type().name();
                ClickHouseDataType dt = mapper.getClickHouseDataType(
                        f.schema().valueSchema().type(), null);
                columnToDataTypesMap.put(
                        colName,
                        "Array(" + dt.name() + ")"
                );
                continue;
            }

            // MySQL unsigned integers are promoted by Debezium to a signed
            // Kafka Connect type (e.g. INT UNSIGNED -> INT64, SMALLINT UNSIGNED
            // -> INT32), which loses the unsigned range and is ambiguous. When
            // the original source column type is propagated, map it to the
            // matching ClickHouse UInt type so this path agrees with the DDL
            // parser path. Falls back to the signed mapping when the source
            // type is not available.
            if (f.schema().parameters() != null) {
                String sourceColumnType = f.schema().parameters().get(
                        ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM);
                String unsignedType = ClickHouseDataTypeMapper
                        .getUnsignedClickHouseType(sourceColumnType);
                if (unsignedType != null) {
                    columnToDataTypesMap.put(colName, unsignedType);
                    continue;
                }
            }
            // Input:
            ClickHouseDataType dataType =
                    mapper.getClickHouseDataType(type, schemaName);

            if (dataType != null) {
                if (dataType == ClickHouseDataType.Decimal) {
                    // Get Scale, precision from parameters.
                    Map<String, String> params = f.schema().parameters();

                    // Postgres numeric data type has no scale/precision.
                    if (schemaName.equalsIgnoreCase(
                            VariableScaleDecimal.LOGICAL_NAME)) {
                        columnToDataTypesMap.put(
                                colName,
                                DECIMAL_64_18
                        );
                        continue;
                    }

                    if (params != null
                            && params.containsKey(SCALE)
                            && params.containsKey(PRECISION)) {
                        columnToDataTypesMap.put(
                                colName,
                                "Decimal(" + params.get(PRECISION) + ","
                                        + params.get(SCALE) + ")"
                        );
                    } else {
                        columnToDataTypesMap.put(
                                colName,
                                DEFAULT_DECIMAL_TYPE
                        );
                    }
                } else if (dataType == ClickHouseDataType.DateTime64) {
                    // Timestamp (with milliseconds scale),
                    // DATETIME, DATETIME(0 -3) -> DateTime64(3)
                    if (f.schema().type() == Schema.INT64_SCHEMA.type()
                            && f.schema().name().equalsIgnoreCase(
                            Timestamp.SCHEMA_NAME)) {
                        columnToDataTypesMap.put(colName, DATETIME64_3);
                    } else if (
                            (f.schema().type() == Schema.INT64_SCHEMA.type()
                                    && f.schema().name().equalsIgnoreCase(
                                    MicroTimestamp.SCHEMA_NAME))
                                    || (f.schema().type() == Schema.STRING_SCHEMA.type()
                                    && f.schema().name().equalsIgnoreCase(
                                    ZonedTimestamp.SCHEMA_NAME))
                    ) {
                        // MicroTimestamp (with microseconds precision),
                        // DATETIME(3 -6) -> DateTime64(6)
                        // TIMESTAMP(1..6) -> ZONEDTIMESTAMP(Debezium)
                        // -> DateTime64(6)
                        columnToDataTypesMap.put(colName, DATETIME64_6);
                    } else {
                        columnToDataTypesMap.put(colName, dataType.name());
                    }
                } else {
                    columnToDataTypesMap.put(colName, dataType.name());
                }
            } else {
                log.error(" **** DATA TYPE MAPPING not found: TYPE:"
                        + type.getName() + "SCHEMA NAME:" + schemaName);
            }
        }

        // Print the columnToDataTypesMap entries to verify the changes
        /*log.info("No changes for columnToDataTypesMap:");
        for (Map.Entry<String, String> entry : columnToDataTypesMap.entrySet()) {
            log.info("Key: {}, Value: {}",entry.getKey(),entry.getValue());
        }*/

        // Call the method to load the default column data type mapping.
        Map<String, String> defaultColumnDataTypeMap = loadDefaultColumnDataTypeMapping(config.originalsStrings());

        // Iterate over columnToDataTypesMap using entrySet for efficient access to keys and values
        for (Map.Entry<String, String> entry : columnToDataTypesMap.entrySet()) {
            String key = entry.getKey();  // Get the current key from columnToDataTypesMap
            // Check if defaultColumnDataTypeMap contains the key
            if (defaultColumnDataTypeMap.containsKey(key)) {
                // If defaultColumnDataTypeMap contains the key, update columnToDataTypesMap's value
                // with the corresponding value from defaultColumnDataTypeMap
                entry.setValue(defaultColumnDataTypeMap.get(key));
            }
        }

        // Print the columnToDataTypesMap entries to verify the changes
        /*log.info("Updated columnToDataTypesMap:");
        for (Map.Entry<String, String> entry : columnToDataTypesMap.entrySet()) {
            log.info("Key: {}, Value: {}",entry.getKey(),entry.getValue());
        }*/

        return columnToDataTypesMap;
    }
}
