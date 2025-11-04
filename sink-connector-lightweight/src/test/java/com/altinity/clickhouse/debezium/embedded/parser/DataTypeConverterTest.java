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
import java.util.*;

/**
 * Unit tests for DataTypeConverter.convertToString method.
 * This class tests the conversion of MySQL data types to ClickHouse data types.
 */
public class DataTypeConverterTest {

    private static ClickHouseSinkConnectorConfig config;

    /**
     * Test case data structure to hold input parameters and expected output
     */
    static class TestCase {
        String description;
        String dataTypeString;
        int scale;
        int precision;
        ZoneId timezone;
        String expectedResult;

        TestCase(String description, String dataTypeString, int scale, int precision, ZoneId timezone, String expectedResult) {
            this.description = description;
            this.dataTypeString = dataTypeString;
            this.scale = scale;
            this.precision = precision;
            this.timezone = timezone;
            this.expectedResult = expectedResult;
        }

        TestCase(String description, String dataTypeString, String expectedResult) {
            this(description, dataTypeString, 0, 0, null, expectedResult);
        }
    }

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

    /**
     * Provides all test cases for data type conversion
     */
    private static List<TestCase> getTestCases() {
        List<TestCase> testCases = new ArrayList<>();
        
        // Basic integer types
        testCases.add(new TestCase("INT data type", "INT", "Int32"));
        testCases.add(new TestCase("TINYINT data type - should be overridden to Int8", "TINYINT", "Int8"));
        testCases.add(new TestCase("BIGINT UNSIGNED data type - should be overridden to UInt64", "BIGINT UNSIGNED", "UInt64"));
        testCases.add(new TestCase("BIGINT (regular) data type", "BIGINT", "Int64"));
        testCases.add(new TestCase("SMALLINT data type", "SMALLINT", "Int16"));
        testCases.add(new TestCase("SMALLINT UNSIGNED data type", "SMALLINT UNSIGNED", "Int32"));
        testCases.add(new TestCase("MEDIUMINT data type", "MEDIUMINT", "Int32"));
        
        // String types
        testCases.add(new TestCase("VARCHAR data type", "VARCHAR(255)", "String"));
        testCases.add(new TestCase("TEXT data type", "TEXT", "String"));
        testCases.add(new TestCase("CHAR data type", "CHAR(10)", "String"));
        testCases.add(new TestCase("BINARY data type", "BINARY(16)", "String"));
        testCases.add(new TestCase("VARBINARY data type", "VARBINARY(255)", "String"));
        
        // Decimal types
        testCases.add(new TestCase("DECIMAL with precision and scale", "DECIMAL(10,2)", 2, 10, null, "Decimal(10,2)"));
        testCases.add(new TestCase("DECIMAL with precision only", "DECIMAL(10)", 0, 10, null, "Decimal(10, 0)"));
        testCases.add(new TestCase("DECIMAL with high precision", "DECIMAL(30,10)", 10, 30, null, "Decimal(30,10)"));
        
        // Float types
        testCases.add(new TestCase("FLOAT data type", "FLOAT", "Float32"));
        testCases.add(new TestCase("DOUBLE data type", "DOUBLE", "Float32"));
        
        // Date types
        testCases.add(new TestCase("DATE data type", "DATE", "Date32"));
        
        // DateTime types without timezone
        testCases.add(new TestCase("DATETIME without timezone", "DATETIME", "DateTime64"));
        testCases.add(new TestCase("DATETIME(6) with precision without timezone", "DATETIME(6)", 0, 6, null, "DateTime64(6, 0)"));
        testCases.add(new TestCase("TIMESTAMP without timezone", "TIMESTAMP", "DateTime64"));
        
        // DateTime types with timezone
        testCases.add(new TestCase("DATETIME with timezone UTC", "DATETIME", 0, 0, ZoneId.of("UTC"), "DateTime64(0,'UTC')"));
        testCases.add(new TestCase("DATETIME with timezone America/New_York", "DATETIME", 0, 0, ZoneId.of("America/New_York"), "DateTime64(0,'America/New_York')"));
        testCases.add(new TestCase("DATETIME(6) with precision and timezone", "DATETIME(6)", 0, 6, ZoneId.of("UTC"), "DateTime64(6,'UTC')"));
        testCases.add(new TestCase("DATETIME(3) with precision and timezone", "DATETIME(3)", 0, 3, ZoneId.of("Europe/London"), "DateTime64(3,'Europe/London')"));
        testCases.add(new TestCase("TIMESTAMP with timezone", "TIMESTAMP", 0, 0, ZoneId.of("UTC"), "DateTime64(0,'UTC')"));
        testCases.add(new TestCase("TIMESTAMP(6) with precision and timezone", "TIMESTAMP(6)", 0, 6, ZoneId.of("Asia/Tokyo"), "DateTime64(6,'Asia/Tokyo')"));
        
        // Boolean types
        testCases.add(new TestCase("BOOL/BOOLEAN data type", "BOOL", "Bool"));
        testCases.add(new TestCase("BIT data type", "BIT", "Bool"));
        
        // BLOB and special types
        testCases.add(new TestCase("BLOB data type", "BLOB", "String"));
        testCases.add(new TestCase("JSON data type", "JSON", "String"));
        testCases.add(new TestCase("ENUM data type", "ENUM('A', 'B', 'C')", "String"));
        testCases.add(new TestCase("SET data type", "SET('A', 'B', 'C')", "String"));
        testCases.add(new TestCase("TIME data type", "TIME", "String"));
        testCases.add(new TestCase("YEAR data type", "YEAR", "Int32"));
        
        return testCases;
    }

    @Test
    @DisplayName("Test all data type conversions")
    public void testAllDataTypeConversions() {
        List<TestCase> testCases = getTestCases();
        
        for (TestCase testCase : testCases) {
            MySqlParser.DataTypeContext dataType = parseDataType(testCase.dataTypeString);
            String result = DataTypeConverter.convertToString(
                config, 
                "test_col", 
                testCase.scale, 
                testCase.precision, 
                dataType, 
                testCase.timezone
            );
            
            Assert.assertEquals(
                "Failed for test case: " + testCase.description, 
                testCase.expectedResult, 
                result
            );
        }
    }
}

