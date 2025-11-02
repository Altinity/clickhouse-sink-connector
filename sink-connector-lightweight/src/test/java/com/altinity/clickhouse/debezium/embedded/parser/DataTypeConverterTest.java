package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.antlr.CaseChangingCharStream;
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;

/**
 * Unit tests for DataTypeConverter.convertToString method.
 * This class tests the conversion of MySQL data types to ClickHouse data types.
 */
public class DataTypeConverterTest {

    private static ClickHouseSinkConnectorConfig config;

    @BeforeAll
    static void setUp() {
        config = new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    /**
     * Helper method to parse a MySQL data type string and extract the DataTypeContext.
     * This simulates the parsing that happens in the actual DDL processing.
     *
     * @param dataTypeString The MySQL data type string (e.g., "INT", "VARCHAR(255)", "DECIMAL(10,2)")
     * @return The parsed DataTypeContext
     */
    private MySqlParser.DataTypeContext parseDataType(String dataTypeString) {
        // Create a minimal CREATE TABLE statement with the data type
        String sql = "CREATE TABLE test_table (test_column " + dataTypeString + ")";
        
        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        
        // Parse the SQL and extract the data type context
        MySqlParser.RootContext root = parser.root();
        MySqlParser.ColumnCreateTableContext createTable = 
                (MySqlParser.ColumnCreateTableContext) root.sqlStatements().sqlStatement(0).ddlStatement().createTable();
        
        // Navigate through the parse tree to find the column definition
        // Iterate through children to find CreateDefinitionsContext
        for (int i = 0; i < createTable.getChildCount(); i++) {
            if (createTable.getChild(i) instanceof MySqlParser.CreateDefinitionsContext) {
                MySqlParser.CreateDefinitionsContext createDefinitions = 
                        (MySqlParser.CreateDefinitionsContext) createTable.getChild(i);
                // Get the first create definition (which should be our column)
                MySqlParser.ColumnDeclarationContext columnDeclaration = 
                        (MySqlParser.ColumnDeclarationContext) createDefinitions.createDefinition(0);
                MySqlParser.ColumnDefinitionContext columnDef = columnDeclaration.columnDefinition();
                return columnDef.dataType();
            }
        }
        
        throw new RuntimeException("Could not find DataTypeContext in parsed SQL");
    }

    @Test
    @DisplayName("Test INT data type conversion")
    public void testIntDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("INT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Int32", result);
    }

    @Test
    @DisplayName("Test TINYINT data type conversion - should be overridden to Int8")
    public void testTinyIntDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("TINYINT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Int8", result);
    }

    @Test
    @DisplayName("Test BIGINT UNSIGNED data type conversion - should be overridden to UInt64")
    public void testBigIntUnsignedDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("BIGINT UNSIGNED");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("UInt64", result);
    }

    @Test
    @DisplayName("Test BIGINT (regular) data type conversion")
    public void testBigIntDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("BIGINT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Int64", result);
    }

    @Test
    @DisplayName("Test VARCHAR data type conversion")
    public void testVarcharDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("VARCHAR(255)");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test TEXT data type conversion")
    public void testTextDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("TEXT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test DECIMAL with precision and scale")
    public void testDecimalWithPrecisionAndScale() {
        MySqlParser.DataTypeContext dataType = parseDataType("DECIMAL(10,2)");
        String result = DataTypeConverter.convertToString(config, "test_col", 2, 10, dataType, null);
        Assert.assertEquals("Decimal(10,2)", result);
    }

    @Test
    @DisplayName("Test DECIMAL with precision only")
    public void testDecimalWithPrecisionOnly() {
        MySqlParser.DataTypeContext dataType = parseDataType("DECIMAL(10)");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 10, dataType, null);
        Assert.assertEquals("Decimal(10, 0)", result);
    }

    @Test
    @DisplayName("Test DECIMAL with high precision")
    public void testDecimalWithHighPrecision() {
        MySqlParser.DataTypeContext dataType = parseDataType("DECIMAL(30,10)");
        String result = DataTypeConverter.convertToString(config, "test_col", 10, 30, dataType, null);
        Assert.assertEquals("Decimal(30,10)", result);
    }

    @Test
    @DisplayName("Test FLOAT data type conversion")
    public void testFloatDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("FLOAT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Float32", result);
    }

    @Test
    @DisplayName("Test DOUBLE data type conversion")
    public void testDoubleDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("DOUBLE");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Float32", result);
    }

    @Test
    @DisplayName("Test DATE data type conversion")
    public void testDateDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("DATE");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Date32", result);
    }

    @Test
    @DisplayName("Test DATETIME without timezone")
    public void testDateTimeWithoutTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("DATETIME");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("DateTime64", result);
    }

    @Test
    @DisplayName("Test DATETIME with timezone")
    public void testDateTimeWithTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("DATETIME");
        ZoneId timezone = ZoneId.of("UTC");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, timezone);
        Assert.assertEquals("DateTime64(0,'UTC')", result);
    }

    @Test
    @DisplayName("Test DATETIME with timezone America/New_York")
    public void testDateTimeWithTimeZoneNewYork() {
        MySqlParser.DataTypeContext dataType = parseDataType("DATETIME");
        ZoneId timezone = ZoneId.of("America/New_York");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, timezone);
        Assert.assertEquals("DateTime64(0,'America/New_York')", result);
    }

    @Test
    @DisplayName("Test DATETIME(6) with precision without timezone")
    public void testDateTime64WithPrecisionWithoutTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("DATETIME(6)");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 6, dataType, null);
        Assert.assertEquals("DateTime64(6, 0)", result);
    }

    @Test
    @DisplayName("Test DATETIME(6) with precision and timezone")
    public void testDateTime64WithPrecisionAndTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("DATETIME(6)");
        ZoneId timezone = ZoneId.of("UTC");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 6, dataType, timezone);
        Assert.assertEquals("DateTime64(6,'UTC')", result);
    }

    @Test
    @DisplayName("Test DATETIME(3) with precision and timezone")
    public void testDateTime64Precision3WithTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("DATETIME(3)");
        ZoneId timezone = ZoneId.of("Europe/London");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 3, dataType, timezone);
        Assert.assertEquals("DateTime64(3,'Europe/London')", result);
    }

    @Test
    @DisplayName("Test TIMESTAMP without timezone")
    public void testTimestampWithoutTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("TIMESTAMP");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("DateTime64", result);
    }

    @Test
    @DisplayName("Test TIMESTAMP with timezone")
    public void testTimestampWithTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("TIMESTAMP");
        ZoneId timezone = ZoneId.of("UTC");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, timezone);
        Assert.assertEquals("DateTime64(0,'UTC')", result);
    }

    @Test
    @DisplayName("Test TIMESTAMP(6) with precision and timezone")
    public void testTimestamp64WithPrecisionAndTimeZone() {
        MySqlParser.DataTypeContext dataType = parseDataType("TIMESTAMP(6)");
        ZoneId timezone = ZoneId.of("Asia/Tokyo");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 6, dataType, timezone);
        Assert.assertEquals("DateTime64(6,'Asia/Tokyo')", result);
    }

    @Test
    @DisplayName("Test SMALLINT data type conversion")
    public void testSmallIntDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("SMALLINT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Int16", result);
    }

    @Test
    @DisplayName("Test SMALLINT UNSIGNED data type conversion")
    public void testSmallIntUnsignedDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("SMALLINT UNSIGNED");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Int32", result);
    }

    @Test
    @DisplayName("Test MEDIUMINT data type conversion")
    public void testMediumIntDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("MEDIUMINT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Int32", result);
    }

    @Test
    @DisplayName("Test BOOL/BOOLEAN data type conversion")
    public void testBoolDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("BOOL");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Bool", result);
    }

    @Test
    @DisplayName("Test BIT data type conversion")
    public void testBitDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("BIT");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Bool", result);
    }

    @Test
    @DisplayName("Test BLOB data type conversion")
    public void testBlobDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("BLOB");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test JSON data type conversion")
    public void testJsonDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("JSON");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test ENUM data type conversion")
    public void testEnumDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("ENUM('A', 'B', 'C')");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test SET data type conversion")
    public void testSetDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("SET('A', 'B', 'C')");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test TIME data type conversion")
    public void testTimeDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("TIME");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test YEAR data type conversion")
    public void testYearDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("YEAR");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("Int32", result);
    }

    @Test
    @DisplayName("Test CHAR data type conversion")
    public void testCharDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("CHAR(10)");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test BINARY data type conversion")
    public void testBinaryDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("BINARY(16)");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }

    @Test
    @DisplayName("Test VARBINARY data type conversion")
    public void testVarbinaryDataType() {
        MySqlParser.DataTypeContext dataType = parseDataType("VARBINARY(255)");
        String result = DataTypeConverter.convertToString(config, "test_col", 0, 0, dataType, null);
        Assert.assertEquals("String", result);
    }
}

