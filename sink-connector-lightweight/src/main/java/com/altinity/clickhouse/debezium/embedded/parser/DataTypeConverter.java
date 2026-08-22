package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.antlr.DataTypeResolver;
import io.debezium.bean.DefaultBeanRegistry;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.binlog.BinlogConnectorConfig;
import io.debezium.connector.binlog.charset.BinlogCharsetRegistry;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import io.debezium.connector.mysql.charset.MySqlCharsetRegistryServiceProvider;
import io.debezium.connector.mysql.jdbc.MySqlValueConverters;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.jdbc.JdbcValueConverters;
import io.debezium.jdbc.TemporalPrecisionMode;
import io.debezium.relational.Column;
import io.debezium.relational.ddl.DataType;
import io.debezium.service.DefaultServiceRegistry;
import io.debezium.service.spi.ServiceRegistry;
import org.apache.kafka.connect.data.SchemaBuilder;

import java.sql.Types;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class responsible for converting MySQL DDL data types to
 * corresponding ClickHouse data types. This includes parsing
 * the DDL, resolving data types with Debezium's {@link DataTypeResolver},
 * and applying custom logic for certain data types like DateTime.
 */
public class DataTypeConverter {

    /**
     * Retrieves a {@link DataType} by resolving the given MySQL parser
     * context using a {@link DataTypeResolver}.
     *
     * @param columnDefChild The MySQL parser context for the data type.
     * @return The resolved {@link DataType}.
     */
    public static DataType getDataType(MySqlParser.DataTypeContext columnDefChild) {
        String convertedDataType = null; // Not used currently but left as is.
        return initializeDataTypeResolver().resolveDataType(columnDefChild);
    }

    /** Matches the declared width in a parsed {@code BIT(n)} data type. */
    private static final Pattern BIT_LENGTH =
            Pattern.compile("(?i)^BIT\\s*\\(\\s*(\\d+)\\s*\\)");

    // Create a static map of overridden data types
    static Map<String, String> overriddenDataTypesMap = new HashMap<>();

    static {
        overriddenDataTypesMap.put("tinyint", "Int8");
        // Centralized MySQL-unsigned -> ClickHouse-UInt mapping, shared with
        // the record-schema auto-create path so both stay in lockstep.
        overriddenDataTypesMap.putAll(
                ClickHouseDataTypeMapper.UNSIGNED_MYSQL_TO_CLICKHOUSE_TYPE);
    }
    /**
     * Converts the given MySQL parser context to a ClickHouse data type string.
     * <p>
     * This method:
     * <ul>
     *   <li>Builds a Debezium/MySQL connector configuration</li>
     *   <li>Resolves the data type with Debezium's {@link DataTypeResolver}</li>
     *   <li>Determines the corresponding ClickHouse type (e.g. DateTime32, DateTime64, Decimal, etc.)</li>
     *   <li>Applies user-provided time zone settings</li>
     * </ul>
     *
     * @param config                 The ClickHouse sink connector config.
     * @param columnName             The name of the column.
     * @param scale                  The scale for decimal types.
     * @param precision              The precision for decimal types.
     * @param columnDefChild         The MySQL parser context for the column.
     * @param userProvidedTimeZone   The user-provided {@link ZoneId}, if any.
     * @return A String representing the ClickHouse data type.
     */
    public static String convertToString(ClickHouseSinkConnectorConfig config,
                                         String columnName,
                                         int scale,
                                         int precision,
                                         MySqlParser.DataTypeContext columnDefChild,
                                         ZoneId userProvidedTimeZone) {

        new DefaultBeanRegistry();

        // Create a minimal Configuration for MySQL connector
        Configuration configuration = Configuration.create()
                .with(BinlogConnectorConfig.DECIMAL_HANDLING_MODE, "decimalHandlingMode")
                .with(BinlogConnectorConfig.TIME_PRECISION_MODE, "temporalPrecisionMode")
                .with(BinlogConnectorConfig.BIGINT_UNSIGNED_HANDLING_MODE, "bigIntUnsignedHandlingMode")
                .with(BinlogConnectorConfig.BINARY_HANDLING_MODE, "binaryHandlingMode")
                .with(BinlogConnectorConfig.EVENT_CONVERTING_FAILURE_HANDLING_MODE, "eventConvertingFailureHandlingMode")
                .build();

        final MySqlConnectorConfig connectorConfig = new MySqlConnectorConfig(configuration);

        ServiceRegistry serviceRegistry = new DefaultServiceRegistry(
                Configuration.create().build(), new DefaultBeanRegistry());
        BinlogCharsetRegistry charsetRegistry =
                new MySqlCharsetRegistryServiceProvider().createService(
                        Configuration.create().build(), serviceRegistry);

        MySqlValueConverters mysqlConverter = new MySqlValueConverters(
                JdbcValueConverters.DecimalMode.PRECISE,
                TemporalPrecisionMode.ADAPTIVE,
                JdbcValueConverters.BigIntUnsignedMode.LONG,
                CommonConnectorConfig.BinaryHandlingMode.BYTES,
                x -> x,
                CommonConnectorConfig.EventConvertingFailureHandlingMode.WARN,
                connectorConfig.getServiceRegistry());

        String convertedDataType;

        // Resolve DataType using Debezium
        DataType dataType = initializeDataTypeResolver().resolveDataType(columnDefChild);
        Column column = Column.editor()
                .name(columnName)
                .type(dataType.name())
                .jdbcType(dataType.jdbcType())
                .length(precision)
                .scale(scale)
                .create();

        // Build the schema via the MySQL converter
        SchemaBuilder schemaBuilder = mysqlConverter.schemaBuilder(column);

        // if the data type is in the overriddenDataTypesMap, then return the overridden data type
        if (overriddenDataTypesMap.containsKey(dataType.name().toLowerCase())) {
            return overriddenDataTypesMap.get(dataType.name().toLowerCase());
        }

        // MySQL BIT(n) is a bit-string, not a boolean. Debezium emits it as
        // BYTES/io.debezium.data.Bits (BIT(1) is the only width that is
        // emitted as BOOLEAN), and the runtime value path already maps
        // Bits.LOGICAL_NAME -> String. Resolving the DDL-side type from the
        // JDBC type alone loses the width, so every BIT(n) was created as
        // Bool and every insert failed with CANNOT_PARSE_BOOL, retried
        // forever and blocked the whole batch pipeline.
        if (dataType.jdbcType() == Types.BIT) {
            return bitClickHouseType(columnDefChild);
        }

        // MySQL DOUBLE / DOUBLE PRECISION / FLOAT8 / REAL are 8-byte values
        // carrying ~15 significant digits. Debezium widens MySQL FLOAT to a
        // FLOAT64 Kafka schema as well, so FLOAT and DOUBLE are
        // indistinguishable by schema type alone and the shared type map can
        // only answer one of them correctly — it answers Float32, silently
        // truncating every DOUBLE to ~7 significant digits and overflowing
        // large magnitudes to inf.
        // On this DDL path the resolved source JDBC type IS available, so the
        // two can be told apart exactly: FLOAT/FLOAT4 keep Float32, and only
        // the genuine 8-byte types widen to Float64.
        if (dataType.jdbcType() == Types.DOUBLE || dataType.jdbcType() == Types.REAL) {
            return ClickHouseDataType.Float64.toString();
        }

        // Map the schema to the corresponding ClickHouse data type
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(
                schemaBuilder.schema().type(), schemaBuilder.schema().name());

        // Check for DateTime and time zone
        if (userProvidedTimeZone != null && isDateTimeType(chDataType)) {
            return addTimeZoneToDateTimeType(chDataType, precision, userProvidedTimeZone);
        }

        // Handle Decimal and other numeric types with precision/scale
        if (precision > 0) {
            StringBuffer convertedStringBuf = new StringBuffer();
            convertedStringBuf.append(chDataType.toString())
                    .append("(")
                    .append(precision);
            if (scale > 0) {
                convertedStringBuf.append(",")
                        .append(scale)
                        .append(")");
            } else {
                convertedStringBuf.append(", 0)");
            }
            convertedDataType = convertedStringBuf.toString();
        } else {
            convertedDataType = chDataType.toString();
        }

        return convertedDataType;
    }


    /**
     * Resolves the ClickHouse type for a MySQL {@code BIT(n)} column.
     * <p>
     * MySQL {@code BIT(1)} is emitted by Debezium as a BOOLEAN schema, and is
     * therefore representable as ClickHouse {@code Bool}. Every wider
     * {@code BIT(n)} is emitted as {@code BYTES} with the
     * {@code io.debezium.data.Bits} logical name, whose value is a raw byte
     * array — it must be stored as {@code String}, which is what the runtime
     * value path ({@code ClickHouseDataTypeMapper}) already does for
     * {@code Bits.LOGICAL_NAME}.
     *
     * @param columnDefChild the parsed data-type context for the column
     * @return {@code Bool} for BIT(1) (or an unspecified width), else {@code String}
     */
    static String bitClickHouseType(MySqlParser.DataTypeContext columnDefChild) {
        int length = bitLength(columnDefChild);
        return length > 1 ? ClickHouseDataType.String.toString()
                          : ClickHouseDataType.Bool.toString();
    }

    /**
     * Extracts the declared width of a {@code BIT(n)} column.
     *
     * @param columnDefChild the parsed data-type context for the column
     * @return the declared width, or 1 when no width is declared or it
     *         cannot be parsed (MySQL's own default for {@code BIT})
     */
    static int bitLength(MySqlParser.DataTypeContext columnDefChild) {
        if (columnDefChild == null) {
            return 1;
        }
        String text = columnDefChild.getText();
        if (text == null) {
            return 1;
        }
        Matcher matcher = BIT_LENGTH.matcher(text);
        if (!matcher.find()) {
            return 1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Checks if the given ClickHouse data type is a DateTime type that supports time zones.
     *
     * @param chDataType The ClickHouse data type to check.
     * @return true if the data type is DateTime, DateTime32, or DateTime64, false otherwise.
     */
    private static boolean isDateTimeType(ClickHouseDataType chDataType) {
        return chDataType == ClickHouseDataType.DateTime
                || chDataType == ClickHouseDataType.DateTime32
                || chDataType == ClickHouseDataType.DateTime64;
    }

    /**
     * Adds timezone information to DateTime column types.
     *
     * @param chDataType The ClickHouse DateTime data type.
     * @param precision The precision for DateTime64 types.
     * @param userProvidedTimeZone The timezone to add, or null if no timezone should be added.
     * @return A string representation of the DateTime type with timezone.
     */
    public static String addTimeZoneToDateTimeType(ClickHouseDataType chDataType, int precision, ZoneId userProvidedTimeZone) {
        // If no timezone is provided, return the data type without timezone
        if (userProvidedTimeZone == null) {
            if (chDataType == ClickHouseDataType.DateTime64) {
                return new StringBuffer()
                        .append(chDataType)
                        .append("(")
                        .append(precision)
                        .append(", 0)")
                        .toString();
            }
            return chDataType.toString();
        }
        
        if (chDataType == ClickHouseDataType.DateTime || chDataType == ClickHouseDataType.DateTime32) {
            return new StringBuffer()
                    .append(chDataType)
                    .append("(")
                    .append("'")
                    .append(userProvidedTimeZone)
                    .append("'")
                    .append(")")
                    .toString();
        } else if (chDataType == ClickHouseDataType.DateTime64) {
            return new StringBuffer()
                    .append(chDataType)
                    .append("(")
                    .append(precision)
                    .append(",")
                    .append("'")
                    .append(userProvidedTimeZone)
                    .append("'")
                    .append(")")
                    .toString();
        }
        // Fallback - should not reach here for valid DateTime types
        return chDataType.toString();
    }

    /**
     * Initializes and builds a {@link DataTypeResolver} for MySQL, registering
     * various data types and their corresponding mappings (e.g., TINYINT, INT,
     * REAL, FLOAT).
     *
     * @return The fully built {@link DataTypeResolver}.
     */
    protected static DataTypeResolver initializeDataTypeResolver() {
        DataTypeResolver.Builder dataTypeResolverBuilder = new DataTypeResolver.Builder();

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.StringDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.CHAR, MySqlParser.CHAR),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.CHAR, MySqlParser.VARYING),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.VARCHAR),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.TINYTEXT),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.TEXT),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.MEDIUMTEXT),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.LONGTEXT),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.LONG),
                        new DataTypeResolver.DataTypeEntry(Types.NCHAR, MySqlParser.NCHAR),
                        new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NCHAR, MySqlParser.VARYING),
                        new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NVARCHAR),
                        new DataTypeResolver.DataTypeEntry(Types.CHAR, MySqlParser.CHAR, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.VARCHAR, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.TINYTEXT, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.TEXT, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.MEDIUMTEXT, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.LONGTEXT, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.NCHAR, MySqlParser.NCHAR, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NVARCHAR, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.CHAR, MySqlParser.CHARACTER),
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.CHARACTER, MySqlParser.VARYING)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.NationalStringDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.VARCHAR)
                                .setSuffixTokens(MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.NCHAR, MySqlParser.NATIONAL, MySqlParser.CHARACTER)
                                .setSuffixTokens(MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NCHAR, MySqlParser.VARCHAR)
                                .setSuffixTokens(MySqlParser.BINARY)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.NationalVaryingStringDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.CHAR, MySqlParser.VARYING),
                        new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.CHARACTER, MySqlParser.VARYING)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.DimensionDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.SMALLINT, MySqlParser.TINYINT)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.SMALLINT, MySqlParser.INT1)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.SMALLINT, MySqlParser.SMALLINT)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.SMALLINT, MySqlParser.INT2)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.INTEGER, MySqlParser.MEDIUMINT)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.INTEGER, MySqlParser.INT3)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.INTEGER, MySqlParser.MIDDLEINT)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.INTEGER, MySqlParser.INT)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.INTEGER, MySqlParser.INTEGER)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.INTEGER, MySqlParser.INT4)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.BIGINT, MySqlParser.BIGINT)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.BIGINT, MySqlParser.INT8)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.REAL, MySqlParser.REAL)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.DOUBLE, MySqlParser.DOUBLE)
                                .setSuffixTokens(MySqlParser.PRECISION, MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.DOUBLE, MySqlParser.FLOAT8)
                                .setSuffixTokens(MySqlParser.PRECISION, MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.FLOAT, MySqlParser.FLOAT)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.FLOAT, MySqlParser.FLOAT4)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                        new DataTypeResolver.DataTypeEntry(Types.DECIMAL, MySqlParser.DECIMAL)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                                .setDefaultLengthScaleDimension(10, 0),
                        new DataTypeResolver.DataTypeEntry(Types.DECIMAL, MySqlParser.DEC)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                                .setDefaultLengthScaleDimension(10, 0),
                        new DataTypeResolver.DataTypeEntry(Types.DECIMAL, MySqlParser.FIXED)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                                .setDefaultLengthScaleDimension(10, 0),
                        new DataTypeResolver.DataTypeEntry(Types.NUMERIC, MySqlParser.NUMERIC)
                                .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                                .setDefaultLengthScaleDimension(10, 0),
                        new DataTypeResolver.DataTypeEntry(Types.BIT, MySqlParser.BIT),
                        new DataTypeResolver.DataTypeEntry(Types.TIME, MySqlParser.TIME),
                        new DataTypeResolver.DataTypeEntry(Types.TIMESTAMP_WITH_TIMEZONE, MySqlParser.TIMESTAMP),
                        new DataTypeResolver.DataTypeEntry(Types.TIMESTAMP, MySqlParser.DATETIME),
                        new DataTypeResolver.DataTypeEntry(Types.BINARY, MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.VARBINARY, MySqlParser.VARBINARY),
                        new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.BLOB),
                        new DataTypeResolver.DataTypeEntry(Types.INTEGER, MySqlParser.YEAR)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.SimpleDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.DATE, MySqlParser.DATE),
                        new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.TINYBLOB),
                        new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.MEDIUMBLOB),
                        new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.LONGBLOB),
                        new DataTypeResolver.DataTypeEntry(Types.BOOLEAN, MySqlParser.BOOL),
                        new DataTypeResolver.DataTypeEntry(Types.BOOLEAN, MySqlParser.BOOLEAN),
                        new DataTypeResolver.DataTypeEntry(Types.BIGINT, MySqlParser.SERIAL)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.CollectionDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.CHAR, MySqlParser.ENUM)
                                .setSuffixTokens(MySqlParser.BINARY),
                        new DataTypeResolver.DataTypeEntry(Types.CHAR, MySqlParser.SET)
                                .setSuffixTokens(MySqlParser.BINARY)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.SpatialDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.GEOMETRYCOLLECTION),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.GEOMCOLLECTION),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.LINESTRING),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.MULTILINESTRING),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.MULTIPOINT),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.MULTIPOLYGON),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.POINT),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.POLYGON),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.JSON),
                        new DataTypeResolver.DataTypeEntry(Types.OTHER, MySqlParser.GEOMETRY)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.LongVarbinaryDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.LONG)
                                .setSuffixTokens(MySqlParser.VARBINARY)));

        dataTypeResolverBuilder.registerDataTypes(
                MySqlParser.LongVarcharDataTypeContext.class.getCanonicalName(),
                Arrays.asList(
                        new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.LONG)
                                .setSuffixTokens(MySqlParser.VARCHAR)));

        return dataTypeResolverBuilder.build();
    }
}
