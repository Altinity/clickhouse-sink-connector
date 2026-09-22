package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseTableOperationsBase;
import io.debezium.antlr.CaseChangingCharStream;
import io.debezium.data.Bits;
import io.debezium.data.Enum;
import io.debezium.data.EnumSet;
import io.debezium.data.Json;
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.time.Date;
import io.debezium.time.MicroTime;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import io.debezium.time.Year;
import io.debezium.time.ZonedTimestamp;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.05 section 3.1.1 / Spec 06.05 section 3.4.1: for one MySQL column
 * type, the record-schema path (Kafka-mode auto-create and schema.evolution
 * ADD COLUMN, fed with the metadata Debezium propagates) and the DDL path
 * ({@link DataTypeConverter#convertToString}) must declare the same ClickHouse
 * type. A table created by one path and evolved by the other would otherwise
 * carry two types for the same source type.
 *
 * <p>Each row states the MySQL type, the Kafka Connect schema Debezium's
 * MySQL connector emits for it (with the propagated source type / length /
 * scale parameters), and the precision/scale the DDL listener hands to
 * {@code convertToString} for that type text.</p>
 */
public class RecordSchemaVsDdlTypeAgreementTest {

    private static final String LENGTH_PARAM = "__debezium.source.column.length";
    private static final String SCALE_PARAM = "__debezium.source.column.scale";

    /** One row of the matrix. */
    private static final class Case {
        final String mysqlType;
        final SchemaBuilder recordSchema;
        final int ddlPrecision;
        final int ddlScale;

        Case(String mysqlType, SchemaBuilder recordSchema, int ddlPrecision, int ddlScale) {
            this.mysqlType = mysqlType;
            this.recordSchema = recordSchema;
            this.ddlPrecision = ddlPrecision;
            this.ddlScale = ddlScale;
        }
    }

    private static SchemaBuilder typed(SchemaBuilder b, String sourceType) {
        return b.parameter(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, sourceType);
    }

    private static SchemaBuilder typed(SchemaBuilder b, String sourceType, int length) {
        return typed(b, sourceType).parameter(LENGTH_PARAM, String.valueOf(length));
    }

    private static SchemaBuilder decimal(int precision, int scale) {
        return typed(Decimal.builder(scale), "DECIMAL", precision)
                .parameter("connect.decimal.precision", String.valueOf(precision))
                .parameter(SCALE_PARAM, String.valueOf(scale));
    }

    private static List<Case> matrix() {
        List<Case> cases = new ArrayList<>();
        // Integers: Debezium widens unsigned types by one Kafka width.
        cases.add(new Case("TINYINT", typed(SchemaBuilder.int16(), "TINYINT"), 0, 0));
        cases.add(new Case("TINYINT UNSIGNED", typed(SchemaBuilder.int16(), "TINYINT UNSIGNED"), 0, 0));
        cases.add(new Case("SMALLINT", typed(SchemaBuilder.int16(), "SMALLINT"), 0, 0));
        cases.add(new Case("SMALLINT UNSIGNED", typed(SchemaBuilder.int32(), "SMALLINT UNSIGNED"), 0, 0));
        cases.add(new Case("MEDIUMINT", typed(SchemaBuilder.int32(), "MEDIUMINT"), 0, 0));
        cases.add(new Case("MEDIUMINT UNSIGNED", typed(SchemaBuilder.int32(), "MEDIUMINT UNSIGNED"), 0, 0));
        cases.add(new Case("INT", typed(SchemaBuilder.int32(), "INT"), 0, 0));
        cases.add(new Case("INT UNSIGNED", typed(SchemaBuilder.int64(), "INT UNSIGNED"), 0, 0));
        cases.add(new Case("BIGINT", typed(SchemaBuilder.int64(), "BIGINT"), 0, 0));
        cases.add(new Case("BIGINT UNSIGNED", typed(SchemaBuilder.int64(), "BIGINT UNSIGNED"), 0, 0));
        // Floating point: FLOAT is delivered as FLOAT64 (Spec 07.02 section 3.1).
        cases.add(new Case("FLOAT", typed(SchemaBuilder.float64(), "FLOAT"), 0, 0));
        cases.add(new Case("DOUBLE", typed(SchemaBuilder.float64(), "DOUBLE"), 0, 0));
        // Decimals: the listener passes (precision, scale) parsed from the text.
        cases.add(new Case("DECIMAL(10,2)", decimal(10, 2), 10, 2));
        cases.add(new Case("DECIMAL(12)", decimal(12, 0), 12, 0));
        cases.add(new Case("DECIMAL(30,10)", decimal(30, 10), 30, 10));
        // Strings, binary, JSON, ENUM, SET.
        cases.add(new Case("VARCHAR(255)", typed(SchemaBuilder.string(), "VARCHAR", 255), 0, 0));
        cases.add(new Case("CHAR(10)", typed(SchemaBuilder.string(), "CHAR", 10), 0, 0));
        cases.add(new Case("TEXT", typed(SchemaBuilder.string(), "TEXT"), 0, 0));
        cases.add(new Case("BINARY(16)", typed(SchemaBuilder.bytes(), "BINARY", 16), 0, 0));
        cases.add(new Case("BLOB", typed(SchemaBuilder.bytes(), "BLOB"), 0, 0));
        cases.add(new Case("JSON", typed(SchemaBuilder.string().name(Json.LOGICAL_NAME), "JSON"), 0, 0));
        cases.add(new Case("ENUM('a','b')", typed(SchemaBuilder.string().name(Enum.LOGICAL_NAME), "ENUM"), 0, 0));
        cases.add(new Case("SET('a','b')", typed(SchemaBuilder.string().name(EnumSet.LOGICAL_NAME), "SET"), 0, 0));
        // Temporal: the listener passes the fractional precision for DATETIME/TIMESTAMP.
        cases.add(new Case("DATE", typed(SchemaBuilder.int32().name(Date.SCHEMA_NAME), "DATE"), 0, 0));
        cases.add(new Case("DATETIME", typed(SchemaBuilder.int64().name(Timestamp.SCHEMA_NAME), "DATETIME"), 0, 0));
        cases.add(new Case("DATETIME(3)", typed(SchemaBuilder.int64().name(Timestamp.SCHEMA_NAME), "DATETIME", 3), 3, 0));
        cases.add(new Case("DATETIME(6)", typed(SchemaBuilder.int64().name(MicroTimestamp.SCHEMA_NAME), "DATETIME", 6), 6, 0));
        cases.add(new Case("TIMESTAMP", typed(SchemaBuilder.string().name(ZonedTimestamp.SCHEMA_NAME), "TIMESTAMP"), 0, 0));
        cases.add(new Case("TIMESTAMP(6)", typed(SchemaBuilder.string().name(ZonedTimestamp.SCHEMA_NAME), "TIMESTAMP", 6), 6, 0));
        cases.add(new Case("TIME", typed(SchemaBuilder.int64().name(MicroTime.SCHEMA_NAME), "TIME"), 0, 0));
        cases.add(new Case("TIME(6)", typed(SchemaBuilder.int64().name(MicroTime.SCHEMA_NAME), "TIME", 6), 0, 0));
        cases.add(new Case("YEAR", typed(SchemaBuilder.int32().name(Year.SCHEMA_NAME), "YEAR"), 0, 0));
        // BIT: only BIT(1) is a BOOLEAN schema.
        cases.add(new Case("BIT(1)", typed(SchemaBuilder.bool(), "BIT", 1), 0, 0));
        cases.add(new Case("BIT(8)", typed(SchemaBuilder.bytes().name(Bits.LOGICAL_NAME), "BIT", 8), 0, 0));
        return cases;
    }

    /** Same parse the DDL listener performs, reduced to one column's data type. */
    private static MySqlParser.DataTypeContext parseDataType(String dataTypeString) {
        String sql = "CREATE TABLE test_table (test_column " + dataTypeString + ")";
        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));
        MySqlParser parser = new MySqlParser(new CommonTokenStream(lexer));
        MySqlParser.ColumnCreateTableContext createTable =
                (MySqlParser.ColumnCreateTableContext) parser.root().sqlStatements().sqlStatement(0).ddlStatement().createTable();
        for (int i = 0; i < createTable.getChildCount(); i++) {
            if (createTable.getChild(i) instanceof MySqlParser.CreateDefinitionsContext) {
                MySqlParser.CreateDefinitionsContext defs = (MySqlParser.CreateDefinitionsContext) createTable.getChild(i);
                return ((MySqlParser.ColumnDeclarationContext) defs.createDefinition(0)).columnDefinition().dataType();
            }
        }
        throw new IllegalStateException("no data type parsed from " + dataTypeString);
    }

    /**
     * Whitespace-free form; the DDL path's {@code DateTime64(p, 0)} (no zone
     * configured) is accepted by ClickHouse as {@code DateTime64(p)} and is
     * compared as such.
     */
    private static String normalize(String type) {
        return type.replace(" ", "").replaceAll("DateTime64\\((\\d+),0\\)", "DateTime64($1)");
    }

    @Test
    @DisplayName("Record-schema path and DDL path declare the same ClickHouse type for each MySQL type")
    public void bothPathsDeclareTheSameType() {
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        ZoneId zone = ZoneId.of("UTC");
        List<String> disagreements = new ArrayList<>();

        for (Case c : matrix()) {
            Field field = new Field("c", 0, c.recordSchema.build());
            Map<String, String> record = new ClickHouseTableOperationsBase()
                    .getColumnNameToCHDataTypeMapping(new Field[]{field}, config);
            String recordType = record.get("c");
            String ddlType = DataTypeConverter.convertToString(config, "c", c.ddlScale, c.ddlPrecision,
                    parseDataType(c.mysqlType), zone);

            if (recordType == null || !normalize(recordType).equals(normalize(ddlType))) {
                disagreements.add(String.format("%-20s record-schema=%-28s ddl=%s", c.mysqlType, recordType, ddlType));
            }
        }

        assertTrue(disagreements.isEmpty(),
                "the two type-mapping paths disagree for:\n" + String.join("\n", disagreements));
    }
}
