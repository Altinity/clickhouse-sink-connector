package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.value.ClickHouseDoubleValue;
import com.clickhouse.data.value.ClickHouseGeoPointValue;
import com.clickhouse.data.value.ClickHouseGeoPolygonValue;
import com.google.common.io.BaseEncoding;
import io.debezium.data.*;
import io.debezium.data.Enum;
import io.debezium.data.EnumSet;
import io.debezium.data.geometry.Geometry;
import io.debezium.data.geometry.Point;
import io.debezium.time.*;
import io.debezium.time.Date;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.*;

/**
 * ClickHouseDataTypeMapper provides functions to map Debezium or
 * Kafka Connect data types to ClickHouse data types, as well as
 * to convert values for insertion into a ClickHouse database.
 *
 * <p>The mappings are defined in a static map, correlating
 * {@code Schema.Type} and logical names to ClickHouseDataType
 * enumerations. The conversion method supports various Debezium
 * logical types (e.g., date/time, geometry, decimal).
 */
public class ClickHouseDataTypeMapper {

    private static final Logger log = LogManager.getLogger(ClickHouseDataTypeMapper.class);

    /**
     * A map linking pairs of Kafka Connect schema type and schema name
     * to a corresponding ClickHouseDataType.
     */
    static Map<MutablePair<Schema.Type, String>, ClickHouseDataType> dataTypesMap;

    static {
        dataTypesMap = new HashMap<>();

        // Integer
        dataTypesMap.put(
                new MutablePair<>(Schema.INT16_SCHEMA.type(), null),
                ClickHouseDataType.Int16);
        dataTypesMap.put(
                new MutablePair<>(Schema.INT8_SCHEMA.type(), null),
                ClickHouseDataType.Int8);
        dataTypesMap.put(
                new MutablePair<>(Schema.INT32_SCHEMA.type(), null),
                ClickHouseDataType.Int32);
        dataTypesMap.put(
                new MutablePair<>(Schema.INT64_SCHEMA.type(), null),
                ClickHouseDataType.Int64);

        // Float
        dataTypesMap.put(
                new MutablePair<>(Schema.FLOAT32_SCHEMA.type(), null),
                ClickHouseDataType.Float32);
        dataTypesMap.put(
                new MutablePair<>(Schema.FLOAT64_SCHEMA.type(), null),
                ClickHouseDataType.Float32);

        // String
        dataTypesMap.put(
                new MutablePair<>(Schema.STRING_SCHEMA.type(), null),
                ClickHouseDataType.String);

        // BLOB -> String
        dataTypesMap.put(
                new MutablePair<>(Schema.BYTES_SCHEMA.type(),
                        Decimal.LOGICAL_NAME),
                ClickHouseDataType.Decimal);

        // DATE
        dataTypesMap.put(
                new MutablePair<>(Schema.INT32_SCHEMA.type(),
                        Date.SCHEMA_NAME),
                ClickHouseDataType.Date32);

        // TIME
        dataTypesMap.put(
                new MutablePair<>(Schema.INT32_SCHEMA.type(),
                        Time.SCHEMA_NAME),
                ClickHouseDataType.String);

        // Debezium.time.MicroTime -> String
        dataTypesMap.put(
                new MutablePair<>(Schema.INT64_SCHEMA.type(),
                        MicroTime.SCHEMA_NAME),
                ClickHouseDataType.String);

        // Timestamp -> DateTime
        dataTypesMap.put(
                new MutablePair<>(Schema.INT64_SCHEMA.type(),
                        Timestamp.SCHEMA_NAME),
                ClickHouseDataType.DateTime64);

        // Datetime with microseconds precision
        dataTypesMap.put(
                new MutablePair<>(Schema.INT64_SCHEMA.type(),
                        MicroTimestamp.SCHEMA_NAME),
                ClickHouseDataType.DateTime64);

        // BLOB -> String
        dataTypesMap.put(
                new MutablePair<>(Schema.Type.BYTES, null),
                ClickHouseDataType.String);

        // BYTES, BIT
        dataTypesMap.put(
                new MutablePair<>(Schema.Type.BYTES, Bits.LOGICAL_NAME),
                ClickHouseDataType.String);

        // Boolean -> Boolean
        dataTypesMap.put(
                new MutablePair<>(Schema.Type.BOOLEAN, null),
                ClickHouseDataType.Bool);

        // Timestamp -> ZonedTimeStamp -> DateTime

       dataTypesMap.put(
                new MutablePair<>(Schema.Type.STRING,
                        ZonedTimestamp.SCHEMA_NAME),
                ClickHouseDataType.DateTime64);
      
        dataTypesMap.put(new MutablePair<>(Schema.Type.STRING, 
                 ZonedTime.SCHEMA_NAME.toLowerCase()), 
                         ClickHouseDataType.String);
 
        dataTypesMap.put(
                new MutablePair<>(Schema.Type.STRING,
                        Enum.LOGICAL_NAME),
                ClickHouseDataType.String);

        dataTypesMap.put(
                new MutablePair<>(Schema.Type.STRING,
                        Json.LOGICAL_NAME),
                ClickHouseDataType.String);

        dataTypesMap.put(
                new MutablePair<>(Schema.INT32_SCHEMA.type(),
                        Year.SCHEMA_NAME),
                ClickHouseDataType.Int32);

        // EnumSet -> String
        dataTypesMap.put(
                new MutablePair<>(Schema.STRING_SCHEMA.type(),
                        EnumSet.LOGICAL_NAME),
                ClickHouseDataType.String);

        // Geometry -> Geometry
        dataTypesMap.put(
                new MutablePair<>(Schema.Type.STRUCT,
                        Geometry.LOGICAL_NAME),
                ClickHouseDataType.Polygon);

        // Point -> Point
        dataTypesMap.put(
                new MutablePair<>(Schema.Type.STRUCT,
                        Point.LOGICAL_NAME),
                ClickHouseDataType.Point);

        // PostgreSQL UUID -> UUID
        dataTypesMap.put(
                new MutablePair<>(Schema.Type.STRING,
                        Uuid.LOGICAL_NAME),
                ClickHouseDataType.UUID);

        dataTypesMap.put(
                new MutablePair<>(Schema.Type.STRUCT,
                        VariableScaleDecimal.LOGICAL_NAME),
                ClickHouseDataType.Decimal);

        dataTypesMap.put(
                new MutablePair<>(Schema.Type.ARRAY,
                        Schema.Type.STRING.name()),
                ClickHouseDataType.Array);
    }

    /**
     * Converts a given value into the appropriate ClickHouse type
     * based on the Kafka Connect schema type and logical name.
     *
     * @param type the schema type of the value
     * @param schemaName the logical name, if present
     * @param value the actual value to convert
     * @param index the parameter index in the PreparedStatement
     * @param ps the PreparedStatement to which the converted value is
     *           bound
     * @param config the ClickHouse sink connector configuration
     * @param clickHouseDataType the determined ClickHouseDataType
     * @param serverTimeZone the server time zone for date/time conversions
     * @return true if the conversion was successful, false otherwise
     * @throws SQLException if an SQL error occurs while setting the
     *                     parameter
     */
    public static boolean convert(Schema.Type type, String schemaName,
                                  Object value, int index, PreparedStatement ps,
                                  ClickHouseSinkConnectorConfig config,
                                  ClickHouseDataType clickHouseDataType, ZoneId serverTimeZone)
            throws SQLException {

        boolean result = true;
        //TinyINT -> INT16 -> TinyInt
        boolean isFieldTinyInt = (type == Schema.INT16_SCHEMA.type());
        boolean isFieldTypeInt = (type == Schema.INT8_SCHEMA.type())
                || (type == Schema.INT32_SCHEMA.type());
        boolean isFieldTypeFloat = (type == Schema.FLOAT32_SCHEMA.type())
                || (type == Schema.FLOAT64_SCHEMA.type());

        // MySQL BigInt -> INT64
        boolean isFieldTypeBigInt = false;
        boolean isFieldTime = false;
        boolean isFieldDateTime = false;
        boolean isFieldTypeDecimal = false;

        // Decimal -> BigDecimal (JDBC)
        if (type == Schema.BYTES_SCHEMA.type()
                && (schemaName != null
                && schemaName.equalsIgnoreCase(Decimal.LOGICAL_NAME))) {
            isFieldTypeDecimal = true;
        }

        if (type == Schema.INT64_SCHEMA.type()) {
            // Time -> INT64 + io.debezium.time.MicroTime
            if (schemaName != null
                    && schemaName.equalsIgnoreCase(MicroTime.SCHEMA_NAME)) {
                isFieldTime = true;
            } else if ((schemaName != null
                    && schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME))
                    || (schemaName != null
                    && schemaName.equalsIgnoreCase(MicroTimestamp.SCHEMA_NAME))) {
                // DateTime -> INT64 + Timestamp (Debezium)
                // MicroTimestamp ("yyyy-MM-dd HH:mm:ss")
                isFieldDateTime = true;
            } else {
                isFieldTypeBigInt = true;
            }
        }

        // Text columns
        if (type == Schema.Type.STRING) {
            if (schemaName != null
                    && schemaName.equalsIgnoreCase(ZonedTimestamp.SCHEMA_NAME)) {
                // MySQL(Timestamp) -> String, name(ZonedTimestamp) ->
                // ClickHouse(DateTime)
                ps.setString(
                        index,
                        DebeziumConverter.ZonedTimestampConverter
                                .convert(value, serverTimeZone));
            } else if (schemaName != null
                    && schemaName.equalsIgnoreCase(Json.LOGICAL_NAME)) {
                // if the column is JSON,
                // it should be written as String or JSON in CH
                ps.setObject(index, value);
            } else {
                ps.setString(index, (String) value);
            }
        } else if (isFieldTypeInt) {
            if (schemaName != null
                    && schemaName.equalsIgnoreCase(Date.SCHEMA_NAME)) {
                // Date field arrives as INT32 with schema name
                // set to io.debezium.time.Date
                ps.setDate(index,
                        DebeziumConverter.DateConverter.convert(
                                value, clickHouseDataType));
            } else if (schemaName != null
                    && schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME)) {
                ps.setTimestamp(index, (java.sql.Timestamp) value);
            } else {
                ps.setInt(index, (Integer) value);
            }
        } else if (isFieldTypeFloat) {
            if (value instanceof Float) {
                ps.setFloat(index, (Float) value);
            } else if (value instanceof Double) {
                ps.setObject(index,
                        ClickHouseDoubleValue.of((Double) value)
                                .asBigDecimal());
            }
        } else if (type == Schema.BOOLEAN_SCHEMA.type()) {
            ps.setBoolean(index, (Boolean) value);
        } else if (isFieldTypeBigInt || isFieldTinyInt) {
            ps.setObject(index, value);
        } else if (isFieldDateTime || isFieldTime) {
            if (isFieldDateTime) {
                String sourceTimeZone = "UTC";

                if(config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString()) != null){
                    String configSourceTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
                    if(configSourceTimeZone != null && !configSourceTimeZone.isEmpty()) {
                        sourceTimeZone = configSourceTimeZone;
                    }
                }
                if  (schemaName != null && schemaName.equalsIgnoreCase(MicroTimestamp.SCHEMA_NAME)) {
                    // DATETIME(4), DATETIME(5), DATETIME(6)

                    ps.setString(index, DebeziumConverter.MicroTimestampConverter.convert(value, ZoneId.of(sourceTimeZone),
                            serverTimeZone, clickHouseDataType));
                }
                else if (value instanceof Long) {
                    // DATETIME(0), DATETIME(1), DATETIME(2), DATETIME(3)
                    boolean isColumnDateTime64 = false;
                    if(schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME) && type == Schema.INT64_SCHEMA.type()){
                        isColumnDateTime64 = true;
                    }
                    ps.setString(index, DebeziumConverter.TimestampConverter.convert(value, clickHouseDataType,
                        ZoneId.of(sourceTimeZone), serverTimeZone));
                }
            } else if (isFieldTime) {
                ps.setString(index, DebeziumConverter.MicroTimeConverter.convert(value));
            }
            // Convert this to string.
            // ps.setString(index, String.valueOf(value));
        } else if (isFieldTypeDecimal) {
            ps.setBigDecimal(index, (BigDecimal) value);
        } else if (type == Schema.Type.BYTES) {
            // Blob storage.
            if (value instanceof byte[]) {
                String hexValue = new String((byte[]) value);
                ps.setString(index, hexValue);
            } else if (value instanceof java.nio.ByteBuffer) {
                if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.PERSIST_RAW_BYTES.toString())) {
                    //String hexValue = new String((byte[]) value);
                    ps.setBytes(index, ((ByteBuffer) value).array());
                } else {
                    ps.setString(index, BaseEncoding.base16().lowerCase().encode(((ByteBuffer) value).array()));
                }
            }

        }  else if (type == Schema.Type.STRUCT && schemaName.equalsIgnoreCase(Geometry.LOGICAL_NAME)) {
            // Handle Geometry type (e.g., Polygon)
            if (value instanceof Struct) {
                Struct geometryValue = (Struct) value;
                Object wkbValue = geometryValue.get("wkb");

                byte[] wkbBytes;
                if (wkbValue instanceof byte[]) {
                    wkbBytes = (byte[]) wkbValue;
                } else if (wkbValue instanceof ByteBuffer) {
                    ByteBuffer byteBuffer = (ByteBuffer) wkbValue;
                    wkbBytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(wkbBytes);
                    byteBuffer.rewind();
                } else {
                    // Set an empty polygon if WKB value is not available
                    ps.setObject(index,
                            ClickHouseGeoPolygonValue.ofEmpty());
                    return true;
                }
                WKBReader wkbReader = new WKBReader();
                org.locationtech.jts.geom.Geometry geometry;
                try {
                    geometry = wkbReader.read(wkbBytes);
                } catch (ParseException e) {
                    ps.setObject(index,
                            ClickHouseGeoPolygonValue.ofEmpty());
                    return true;
                }
                if (geometry instanceof Polygon) {
                    Polygon polygon = (Polygon) geometry;
                    List<double[][]> rings = new ArrayList<>();
                    org.locationtech.jts.geom.Coordinate[] exteriorCoords =
                            polygon.getExteriorRing().getCoordinates();
                    double[][] exteriorPoints =
                            new double[exteriorCoords.length][2];
                    for (int i = 0; i < exteriorCoords.length; i++) {
                        exteriorPoints[i][0] =
                                exteriorCoords[i].getX();
                        exteriorPoints[i][1] =
                                exteriorCoords[i].getY();
                    }
                    rings.add(exteriorPoints);
                    int numInteriorRings = polygon.getNumInteriorRing();
                    for (int i = 0; i < numInteriorRings; i++) {
                        org.locationtech.jts.geom.Coordinate[] interiorCoords =
                                polygon.getInteriorRingN(i).getCoordinates();
                        double[][] interiorPoints =
                                new double[interiorCoords.length][2];
                        for (int j = 0; j < interiorCoords.length; j++) {
                            interiorPoints[j][0] =
                                    interiorCoords[j].getX();
                            interiorPoints[j][1] =
                                    interiorCoords[j].getY();
                        }
                        rings.add(interiorPoints);
                    }
                    double[][][] polygonCoordinates =
                            rings.toArray(new double[rings.size()][][]);
                    ClickHouseGeoPolygonValue geoPolygonValue =
                            ClickHouseGeoPolygonValue.of(polygonCoordinates);
                    ps.setObject(index, geoPolygonValue);
                } else {
                    ps.setObject(index,
                            ClickHouseGeoPolygonValue.ofEmpty());
                }
            } else {
                ps.setString(index,
                        ClickHouseGeoPolygonValue.ofEmpty().asString());
            }
        } else if (type == Schema.Type.STRUCT
                && schemaName.equalsIgnoreCase(Point.LOGICAL_NAME)) {
            // Handle Point type (ClickHouse expects (longitude, latitude))
            if (value instanceof Struct) {
                Struct pointValue = (Struct) value;
                Object xValue = pointValue.get("x");
                Object yValue = pointValue.get("y");
                double[] point = {(Double) xValue, (Double) yValue};
                ps.setObject(index,
                        ClickHouseGeoPointValue.of(point));
            } else {
                ps.setObject(index,
                        ClickHouseGeoPointValue.ofOrigin());
            }
        } else if (type == Schema.Type.STRUCT
                && schemaName.equalsIgnoreCase(
                VariableScaleDecimal.LOGICAL_NAME)) {
            if (value instanceof Struct) {
                Struct decimalValue = (Struct) value;
                Object scale = decimalValue.get("scale");
                Object unscaledValueObject = decimalValue.get("value");
                byte[] unscaledValueBytes;
                if (unscaledValueObject instanceof ByteBuffer) {
                    ByteBuffer unscaledByteBuffer =
                            (ByteBuffer) unscaledValueObject;
                    unscaledValueBytes =
                            new byte[unscaledByteBuffer.remaining()];
                    unscaledByteBuffer.get(unscaledValueBytes);
                    unscaledByteBuffer.rewind();
                } else if (unscaledValueObject instanceof byte[]) {
                    unscaledValueBytes =
                            (byte[]) unscaledValueObject;
                } else {
                    // Handle unexpected type
                    throw new IllegalArgumentException(
                            "Unexpected type for unscaled value");
                }
                BigDecimal bigDecimal = new BigDecimal(
                        new BigInteger(unscaledValueBytes),
                        (Integer) scale);
                BigDecimal truncated =
                        new DebeziumConverter.BigDecimalConverter()
                                .truncate(bigDecimal);
                ps.setBigDecimal(index, truncated);
            } else {
                ps.setBigDecimal(index, new BigDecimal(0));
            }
        } else if (type == Schema.Type.ARRAY) {
            ClickHouseDataType dt = getClickHouseDataType(
                    Schema.Type.valueOf(schemaName), null);
            ps.setArray(index, ps.getConnection().createArrayOf(
                    dt.name(), ((ArrayList) value).toArray()));
        } else {
            result = false;
        }
        return result;
    }

    /**
     * Determines the corresponding ClickHouseDataType for a given
     * Kafka Connect type and optional schemaName.
     *
     * @param kafkaConnectType the Kafka Connect schema type
     * @param schemaName       the logical schema name, if any
     * @return the matching ClickHouseDataType, or null if not found
     */
    public static ClickHouseDataType getClickHouseDataType(
            Schema.Type kafkaConnectType, String schemaName) {
        ClickHouseDataType matchingDataType = null;
        for (Map.Entry<MutablePair<Schema.Type, String>,
                ClickHouseDataType> entry : dataTypesMap.entrySet()) {
            MutablePair<Schema.Type, String> mp = entry.getKey();
            if ((schemaName == null && mp.right == null
                    && kafkaConnectType == mp.left)
                    || (kafkaConnectType == mp.left && schemaName != null
                    && schemaName.equalsIgnoreCase(mp.right))) {
                matchingDataType = entry.getValue();
            }
        }
        return matchingDataType;
    }

    // -----------------------------------------------------------------------
    // DDL-string mapping (used by schema reconciliation / ALTER TABLE)
    // -----------------------------------------------------------------------

    /**
     * Maps a Debezium {@link Schema} (Kafka Connect type + optional logical name)
     * to a ClickHouse column DDL type string, always wrapped in {@code Nullable(…)}.
     *
     * <p>This is the centralized DDL-type mapping used by both MySQL DDL parsing
     * (via {@code DataTypeConverter}) and PostgreSQL schema reconciliation
     * (via {@code PostgresSchemaReconciler}). Unlike {@link #getClickHouseDataType}
     * which returns a bare {@link ClickHouseDataType} enum for row-level value
     * conversion, this method returns fully-qualified DDL strings including
     * {@code Nullable(…)} wrapping, precision/scale parameters, and timezone info.
     *
     * <p>Mapping table:
     * <table border="1">
     *   <tr><th>Debezium / Kafka Connect type</th><th>ClickHouse DDL type</th></tr>
     *   <tr><td>INT8</td><td>Nullable(Int8)</td></tr>
     *   <tr><td>INT16</td><td>Nullable(Int16)</td></tr>
     *   <tr><td>INT32</td><td>Nullable(Int32)</td></tr>
     *   <tr><td>INT64</td><td>Nullable(Int64)</td></tr>
     *   <tr><td>FLOAT32</td><td>Nullable(Float32)</td></tr>
     *   <tr><td>FLOAT64</td><td>Nullable(Float64)</td></tr>
     *   <tr><td>BOOLEAN</td><td>Nullable(UInt8)</td></tr>
     *   <tr><td>STRING</td><td>Nullable(String)</td></tr>
     *   <tr><td>BYTES</td><td>Nullable(String)</td></tr>
     *   <tr><td>io.debezium.time.MicroTimestamp</td><td>Nullable(DateTime64(6))</td></tr>
     *   <tr><td>io.debezium.time.Timestamp</td><td>Nullable(DateTime64(3))</td></tr>
     *   <tr><td>io.debezium.time.NanoTimestamp</td><td>Nullable(DateTime64(9))</td></tr>
     *   <tr><td>io.debezium.time.ZonedTimestamp</td><td>Nullable(DateTime64(6, 'UTC'))</td></tr>
     *   <tr><td>io.debezium.time.Date</td><td>Nullable(Date32)</td></tr>
     *   <tr><td>io.debezium.time.MicroTime</td><td>Nullable(Int64)</td></tr>
     *   <tr><td>org.apache.kafka.connect.data.Decimal</td><td>Nullable(Decimal(p, s))</td></tr>
     *   <tr><td>io.debezium.data.Uuid</td><td>Nullable(UUID)</td></tr>
     *   <tr><td>(unknown / default)</td><td>Nullable(String)</td></tr>
     * </table>
     *
     * @param fieldSchema the Debezium/Kafka-Connect field schema (may be null)
     * @return the ClickHouse column type DDL string (always wrapped in {@code Nullable(…)})
     */
    public static String mapDebeziumSchemaToDDL(Schema fieldSchema) {
        if (fieldSchema == null) {
            return "Nullable(String)";
        }

        // Check the logical type (schema name) first – more specific than the base type
        String logicalType = fieldSchema.name();
        if (logicalType != null) {
            switch (logicalType) {
                case MicroTimestamp.SCHEMA_NAME:       // "io.debezium.time.MicroTimestamp"
                    return "Nullable(DateTime64(6))";
                case Timestamp.SCHEMA_NAME:            // "io.debezium.time.Timestamp"
                    return "Nullable(DateTime64(3))";
                case NanoTimestamp.SCHEMA_NAME:         // "io.debezium.time.NanoTimestamp"
                    return "Nullable(DateTime64(9))";
                case ZonedTimestamp.SCHEMA_NAME:        // "io.debezium.time.ZonedTimestamp"
                    return "Nullable(DateTime64(6, 'UTC'))";
                case Date.SCHEMA_NAME:                 // "io.debezium.time.Date"
                    return "Nullable(Date32)";
                case MicroTime.SCHEMA_NAME:            // "io.debezium.time.MicroTime"
                    // Stored as microseconds since midnight
                    return "Nullable(Int64)";
                case Uuid.LOGICAL_NAME:                // "io.debezium.data.Uuid"
                    return "Nullable(UUID)";
                case Json.LOGICAL_NAME:                // "io.debezium.data.Json"
                    return "Nullable(String)";
                case Enum.LOGICAL_NAME:                // "io.debezium.data.Enum"
                    return "Nullable(String)";
                case Bits.LOGICAL_NAME:                // "io.debezium.data.Bits"
                    return "Nullable(String)";
                default:
                    // Fall through to handle Decimal and other logical types below
                    break;
            }

            // Kafka Connect Decimal logical type (used for NUMERIC/DECIMAL columns)
            if (Decimal.LOGICAL_NAME.equals(logicalType)) {
                // Extract precision and scale from schema parameters
                String scaleStr = fieldSchema.parameters() != null
                        ? fieldSchema.parameters().get("scale") : null;
                String precisionStr = fieldSchema.parameters() != null
                        ? fieldSchema.parameters().get("connect.decimal.precision") : null;

                int scale = (scaleStr != null) ? parseIntSafe(scaleStr, 9) : 9;
                int precision = (precisionStr != null) ? parseIntSafe(precisionStr, 38) : 38;

                // ClickHouse Decimal(p, s): p must be >= s and in [1..76]
                if (precision < scale) precision = scale + 1;
                if (precision < 1) precision = 38;

                return String.format("Nullable(Decimal(%d, %d))", precision, scale);
            }
        }

        // Fall back to base Kafka Connect Schema.Type
        Schema.Type baseType = fieldSchema.type();
        if (baseType == null) {
            return "Nullable(String)";
        }

        switch (baseType) {
            case INT8:
                return "Nullable(Int8)";
            case INT16:
                return "Nullable(Int16)";
            case INT32:
                return "Nullable(Int32)";
            case INT64:
                return "Nullable(Int64)";
            case FLOAT32:
                return "Nullable(Float32)";
            case FLOAT64:
                return "Nullable(Float64)";
            case BOOLEAN:
                return "Nullable(UInt8)";
            case STRING:
                return "Nullable(String)";
            case BYTES:
                // BYTES covers BYTEA and Debezium Decimal (already handled above)
                return "Nullable(String)";
            case ARRAY:
                return "Nullable(String)";
            case MAP:
                return "Nullable(String)";
            case STRUCT:
                // Nested structs → JSON string
                return "Nullable(String)";
            default:
                log.warn("mapDebeziumSchemaToDDL: unknown Schema.Type '{}', defaulting to Nullable(String)",
                        baseType);
                return "Nullable(String)";
        }
    }

    /**
     * Parses an integer from a string, returning a default value on failure.
     *
     * @param s            the string to parse
     * @param defaultValue the value to return if parsing fails
     * @return the parsed integer, or {@code defaultValue} on {@link NumberFormatException}
     */
    private static int parseIntSafe(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
