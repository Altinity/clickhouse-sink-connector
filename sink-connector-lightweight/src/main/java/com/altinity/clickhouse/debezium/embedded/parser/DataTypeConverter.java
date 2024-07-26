package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.antlr.DataTypeResolver;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.connector.mysql.jdbc.MySqlValueConverters;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.jdbc.JdbcValueConverters;
import io.debezium.jdbc.TemporalPrecisionMode;
import io.debezium.relational.Column;
import io.debezium.relational.ddl.DataType;
import org.apache.kafka.connect.data.SchemaBuilder;

import java.sql.Types;
import java.time.ZoneId;
import java.util.Arrays;

/**
 *
 */
public class DataTypeConverter {

    /**
     * Function that takes the Antlr parsed column type
     * and converts it into ClickHouse type.
     *
     * @param columnName
     * @param columnDefChild
     * @return
     */
    public static ClickHouseDataType convert(String columnName, MySqlParser.DataTypeContext columnDefChild) {
        MySqlValueConverters mysqlConverter = new MySqlValueConverters(
                JdbcValueConverters.DecimalMode.PRECISE,
                TemporalPrecisionMode.ADAPTIVE,
                JdbcValueConverters.BigIntUnsignedMode.LONG,
                CommonConnectorConfig.BinaryHandlingMode.BYTES,
                x ->x, CommonConnectorConfig.EventConvertingFailureHandlingMode.WARN);


        DataType dataType = initializeDataTypeResolver().resolveDataType(columnDefChild);
        //DataType dataType = MySqlParser.dataTypeResolver.resolveDataType(dataTypeContext);
        Column column = Column.editor().name(columnName).type(dataType.name()).jdbcType(dataType.jdbcType()).length((int) dataType.length()).scale(dataType.scale()).create();
        SchemaBuilder schemaBuilder = mysqlConverter.schemaBuilder(column);

        return ClickHouseDataTypeMapper.getClickHouseDataType(schemaBuilder.schema().type(), schemaBuilder.schema().name());
    }

    public static DataType getDataType(MySqlParser.DataTypeContext columnDefChild) {
        String convertedDataType = null;
        return initializeDataTypeResolver().resolveDataType(columnDefChild);
    }

    public static String convertToString(String columnName, int scale, int precision, MySqlParser.DataTypeContext columnDefChild, ZoneId userProvidedTimeZone) {
        MySqlValueConverters mysqlConverter = new MySqlValueConverters(
                JdbcValueConverters.DecimalMode.PRECISE,
                TemporalPrecisionMode.ADAPTIVE,
                JdbcValueConverters.BigIntUnsignedMode.LONG,
                CommonConnectorConfig.BinaryHandlingMode.BYTES,
                x ->x, CommonConnectorConfig.EventConvertingFailureHandlingMode.WARN);


        String convertedDataType = null;
        DataType dataType = initializeDataTypeResolver().resolveDataType(columnDefChild);
        //DataType dataType = MySqlParser.dataTypeResolver.resolveDataType(dataTypeContext);
        Column column = Column.editor().name(columnName).type(dataType.name()).jdbcType(dataType.jdbcType()).length(precision).scale(scale).create();
        SchemaBuilder schemaBuilder = mysqlConverter.schemaBuilder(column);

        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(schemaBuilder.schema().type(), schemaBuilder.schema().name());

        // Separate handling for DateTime and DateTime64
        if(userProvidedTimeZone != null && (chDataType == ClickHouseDataType.DateTime || chDataType == ClickHouseDataType.DateTime32)) {
            return new StringBuffer().append(chDataType).append("(").append("'").append(userProvidedTimeZone).append("'").append(")").toString();
        } else if (userProvidedTimeZone != null && chDataType == ClickHouseDataType.DateTime64) {
            return new StringBuffer().append(chDataType).append("(").append(precision).append(",").append("'").append(userProvidedTimeZone).append("'").append(")").toString();
        }

        if (precision > 0) {
            StringBuffer convertedStringBuf = new StringBuffer();
            convertedStringBuf.append(chDataType.toString()).append("(").append(precision);
            if (scale > 0) {
                convertedStringBuf.append(",").append(scale).append(")");
            } else {
                convertedStringBuf.append(", 0)");
            }
            convertedDataType = convertedStringBuf.toString();
        } else {
            convertedDataType = chDataType.toString();
        }

        return convertedDataType;
    }


    protected static DataTypeResolver initializeDataTypeResolver() {
        DataTypeResolver.Builder dataTypeResolverBuilder = new DataTypeResolver.Builder();

        dataTypeResolverBuilder.registerDataTypes(MySqlParser.StringDataTypeContext.class.getCanonicalName(), Arrays.asList(
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
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.NationalStringDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.VARCHAR).setSuffixTokens(MySqlParser.BINARY),
                new DataTypeResolver.DataTypeEntry(Types.NCHAR, MySqlParser.NATIONAL, MySqlParser.CHARACTER).setSuffixTokens(MySqlParser.BINARY),
                new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NCHAR, MySqlParser.VARCHAR).setSuffixTokens(MySqlParser.BINARY)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.NationalVaryingStringDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.CHAR, MySqlParser.VARYING),
                new DataTypeResolver.DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.CHARACTER, MySqlParser.VARYING)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.DimensionDataTypeContext.class.getCanonicalName(), Arrays.asList(
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
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.SimpleDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeResolver.DataTypeEntry(Types.DATE, MySqlParser.DATE),
                new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.TINYBLOB),
                new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.MEDIUMBLOB),
                new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.LONGBLOB),
                new DataTypeResolver.DataTypeEntry(Types.BOOLEAN, MySqlParser.BOOL),
                new DataTypeResolver.DataTypeEntry(Types.BOOLEAN, MySqlParser.BOOLEAN),
                new DataTypeResolver.DataTypeEntry(Types.BIGINT, MySqlParser.SERIAL)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.CollectionDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeResolver.DataTypeEntry(Types.CHAR, MySqlParser.ENUM).setSuffixTokens(MySqlParser.BINARY),
                new DataTypeResolver.DataTypeEntry(Types.CHAR, MySqlParser.SET).setSuffixTokens(MySqlParser.BINARY)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.SpatialDataTypeContext.class.getCanonicalName(), Arrays.asList(
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
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.LongVarbinaryDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeResolver.DataTypeEntry(Types.BLOB, MySqlParser.LONG)
                        .setSuffixTokens(MySqlParser.VARBINARY)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.LongVarcharDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeResolver.DataTypeEntry(Types.VARCHAR, MySqlParser.LONG)
                        .setSuffixTokens(MySqlParser.VARCHAR)));

        return dataTypeResolverBuilder.build();
    }
}
