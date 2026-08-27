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
        testCases.add(new TestCase("SMALLINT UNSIGNED data type", "SMALLINT UNSIGNED", "UInt16"));
        testCases.add(new TestCase("INT UNSIGNED data type", "INT UNSIGNED", "UInt32"));
        testCases.add(new TestCase("TINYINT UNSIGNED data type", "TINYINT UNSIGNED", "UInt8"));
        testCases.add(new TestCase("MEDIUMINT UNSIGNED data type", "MEDIUMINT UNSIGNED", "UInt32"));
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
        
        // Float types.
        // MySQL FLOAT is 4-byte -> Float32; DOUBLE/DOUBLE PRECISION/FLOAT8 are
        // 8-byte -> Float64. Mapping DOUBLE to Float32 silently truncated
        // ~15 significant digits to ~7 and overflowed large values to inf.
        testCases.add(new TestCase("FLOAT data type", "FLOAT", "Float32"));
        testCases.add(new TestCase("FLOAT4 data type", "FLOAT4", "Float32"));
        testCases.add(new TestCase("DOUBLE data type", "DOUBLE", "Float64"));
        testCases.add(new TestCase("DOUBLE PRECISION data type", "DOUBLE PRECISION", "Float64"));
        testCases.add(new TestCase("FLOAT8 data type", "FLOAT8", "Float64"));

        // REAL is Float32 even though MySQL stores it as an 8-byte double under
        // the default sql_mode. This case previously asserted Float64, which
        // pinned a column type the pipeline cannot honour.
        //
        // Debezium resolves MySQL REAL to Types.REAL and maps that to a float32
        // Kafka schema, while FLOAT and DOUBLE both map to float64
        // (io.debezium.jdbc.JdbcValueConverters, debezium 3.1.3.Final: switch
        // keys 6 -> float64, 7 -> float32, 8 -> float64). The value is narrowed
        // before any connector code runs, so a Float64 column cannot be filled
        // with more precision than arrived -- it only makes truncated data look
        // full-precision and doubles the stored width.
        //
        // Measured on 2.10.0 (MySQL 8.0.36 -> ClickHouse 24.8.14.10547) with the
        // column declared Nullable(Float64):
        //   REAL 9876543.210987654  -> 9876543
        //   REAL 0.1234567890123456 -> 0.12345679
        // and the stored value equals toFloat32(source) exactly in both cases.
        testCases.add(new TestCase("REAL data type", "REAL", "Float32"));
        
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

        // BIT types.
        // Only BIT(1) (and a bare BIT, whose MySQL default width is 1) is
        // emitted by Debezium as a BOOLEAN schema. Every wider BIT(n) arrives
        // as BYTES/io.debezium.data.Bits - a raw byte array - and must be
        // String, matching what the runtime value path already does. Creating
        // them as Bool made every insert fail with CANNOT_PARSE_BOOL.
        testCases.add(new TestCase("BIT data type (implicit width 1)", "BIT", "Bool"));
        testCases.add(new TestCase("BIT(1) data type", "BIT(1)", "Bool"));
        testCases.add(new TestCase("BIT(2) data type", "BIT(2)", "String"));
        testCases.add(new TestCase("BIT(8) data type", "BIT(8)", "String"));
        testCases.add(new TestCase("BIT(64) data type", "BIT(64)", "String"));
        testCases.add(new TestCase("BIT with spaces", "BIT( 16 )", "String"));
        
        // BLOB and special types
        testCases.add(new TestCase("BLOB data type", "BLOB", "String"));
        testCases.add(new TestCase("JSON data type", "JSON", "String"));
        testCases.add(new TestCase("ENUM data type", "ENUM('A', 'B', 'C')", "String"));
        testCases.add(new TestCase("SET data type", "SET('A', 'B', 'C')", "String"));
        testCases.add(new TestCase("TIME data type", "TIME", "String"));
        testCases.add(new TestCase("YEAR data type", "YEAR", "Int32"));
        
        return testCases;
    }

    /**
     * One MySQL source type must resolve to exactly ONE ClickHouse type,
     * however the column happens to be spelled.
     *
     * <p>Regression test: the DDL path looked the unsigned override up under
     * the raw resolved type name, so a display width, an explicit SIGNED, and
     * above all ZEROFILL (which in MySQL implies UNSIGNED, making a bare
     * "SMALLINT ZEROFILL" resolve to the name "SMALLINT UNSIGNED ZEROFILL")
     * each missed the override and silently fell through to the signed
     * mapping. The same source type therefore produced different ClickHouse
     * types within one database, decided only by declaration style.
     */
    @Test
    @DisplayName("Unsigned integer mapping is a function of the source type alone")
    public void testUnsignedConversionsAreDeterministic() {
        List<TestCase> cases = new ArrayList<>();

        // ZEROFILL implies UNSIGNED in MySQL, and the DDL parser folds both
        // into the resolved type name (a bare "SMALLINT ZEROFILL" resolves to
        // the name "SMALLINT UNSIGNED ZEROFILL"). The override map is keyed on
        // the exact resolved name, so every ZEROFILL spelling missed the
        // unsigned entry and silently fell through to the signed mapping --
        // the same source type landing on two different ClickHouse types
        // depending only on whether the column was declared ZEROFILL.
        cases.add(new TestCase("SMALLINT UNSIGNED ZEROFILL", "SMALLINT UNSIGNED ZEROFILL", "UInt16"));
        cases.add(new TestCase("SMALLINT ZEROFILL (implies UNSIGNED)", "SMALLINT ZEROFILL", "UInt16"));
        cases.add(new TestCase("SMALLINT(5) UNSIGNED ZEROFILL", "SMALLINT(5) UNSIGNED ZEROFILL", "UInt16"));
        cases.add(new TestCase("TINYINT UNSIGNED ZEROFILL", "TINYINT UNSIGNED ZEROFILL", "UInt8"));
        cases.add(new TestCase("TINYINT ZEROFILL (implies UNSIGNED)", "TINYINT ZEROFILL", "UInt8"));
        cases.add(new TestCase("MEDIUMINT UNSIGNED ZEROFILL", "MEDIUMINT UNSIGNED ZEROFILL", "UInt32"));
        cases.add(new TestCase("INT UNSIGNED ZEROFILL", "INT UNSIGNED ZEROFILL", "UInt32"));
        cases.add(new TestCase("INT ZEROFILL (implies UNSIGNED)", "INT ZEROFILL", "UInt32"));
        cases.add(new TestCase("BIGINT UNSIGNED ZEROFILL", "BIGINT UNSIGNED ZEROFILL", "UInt64"));
        cases.add(new TestCase("BIGINT ZEROFILL (implies UNSIGNED)", "BIGINT ZEROFILL", "UInt64"));

        // Display width must not change the resolved type either.
        cases.add(new TestCase("SMALLINT(5) UNSIGNED", "SMALLINT(5) UNSIGNED", "UInt16"));

        // MySQL numeric-type aliases are the same types under another name and
        // must map identically to their canonical spelling. INT1/INT2 are
        // TINYINT/SMALLINT, INT3/MIDDLEINT are MEDIUMINT, INT4 is INT and INT8
        // is BIGINT.
        cases.add(new TestCase("INT1 UNSIGNED (alias of TINYINT UNSIGNED)", "INT1 UNSIGNED", "UInt8"));
        cases.add(new TestCase("INT2 UNSIGNED (alias of SMALLINT UNSIGNED)", "INT2 UNSIGNED", "UInt16"));
        cases.add(new TestCase("INT3 UNSIGNED (alias of MEDIUMINT UNSIGNED)", "INT3 UNSIGNED", "UInt32"));
        cases.add(new TestCase("MIDDLEINT UNSIGNED (alias of MEDIUMINT UNSIGNED)", "MIDDLEINT UNSIGNED", "UInt32"));
        cases.add(new TestCase("INT4 UNSIGNED (alias of INT UNSIGNED)", "INT4 UNSIGNED", "UInt32"));
        cases.add(new TestCase("INT8 UNSIGNED (alias of BIGINT UNSIGNED)", "INT8 UNSIGNED", "UInt64"));

        // Control: the signed aliases keep their signed mapping.
        cases.add(new TestCase("INT1 (alias of TINYINT)", "INT1", "Int8"));
        cases.add(new TestCase("INT2 (alias of SMALLINT)", "INT2", "Int16"));
        cases.add(new TestCase("INT4 (alias of INT)", "INT4", "Int32"));
        cases.add(new TestCase("INT8 (alias of BIGINT)", "INT8", "Int64"));

        for (TestCase testCase : cases) {
            MySqlParser.DataTypeContext dataType = parseDataType(testCase.dataTypeString);
            String result = DataTypeConverter.convertToString(
                    config, "test_col", testCase.scale, testCase.precision,
                    dataType, testCase.timezone);
            Assert.assertEquals(
                    "Failed for test case: " + testCase.description,
                    testCase.expectedResult,
                    result);
        }
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

