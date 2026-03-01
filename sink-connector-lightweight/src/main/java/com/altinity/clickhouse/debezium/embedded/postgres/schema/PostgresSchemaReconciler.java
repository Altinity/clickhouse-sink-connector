package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.util.Map;

/**
 * Reconciles schema differences between PostgreSQL (reflected via Debezium SourceRecord
 * schema metadata) and ClickHouse (reflected via {@code system.columns}).
 *
 * <p>Supported reconciliation actions:
 * <ol>
 *   <li>ADD COLUMN – new column present in Debezium schema but absent from ClickHouse</li>
 *   <li>Type widening – not yet implemented (logged as a warning, not applied)</li>
 *   <li>DROP COLUMN – not applied automatically; requires explicit configuration</li>
 * </ol>
 *
 * <p>All DDL errors are caught, logged at WARN level, and the connector continues
 * processing so that replication is never halted by a schema-reconciliation failure.
 */
public class PostgresSchemaReconciler {

    private static final Logger log = LogManager.getLogger(PostgresSchemaReconciler.class);

    /** The writer whose JDBC connection is used to execute DDL on ClickHouse. */
    private final BaseDbWriter writer;

    /** ClickHouse sink connector configuration (used for DBMetadata constructor). */
    private final ClickHouseSinkConnectorConfig config;

    /**
     * Constructs a PostgresSchemaReconciler.
     *
     * @param writer the {@link BaseDbWriter} connected to ClickHouse
     * @param config the connector configuration
     */
    public PostgresSchemaReconciler(BaseDbWriter writer, ClickHouseSinkConnectorConfig config) {
        this.writer = writer;
        this.config = config;
    }

    /**
     * Generates and executes {@code ALTER TABLE … ADD COLUMN IF NOT EXISTS} statements
     * for every entry in {@code newColumns}.
     *
     * <p>Each column's ClickHouse type is determined by
     * {@link #mapDebeziumTypeToClickHouse(Schema)}.
     *
     * @param database   ClickHouse target database name
     * @param table      ClickHouse target table name
     * @param newColumns map of {@code columnName → Debezium Schema} for columns to add
     */
    public void addMissingColumns(String database, String table, Map<String, Schema> newColumns) {
        if (newColumns == null || newColumns.isEmpty()) {
            return;
        }

        Connection conn = writer.getConnection();
        DBMetadata dbMetadata = new DBMetadata(config);

        for (Map.Entry<String, Schema> entry : newColumns.entrySet()) {
            String columnName = entry.getKey();
            Schema fieldSchema = entry.getValue();
            String chType = mapDebeziumTypeToClickHouse(fieldSchema);

            String ddl = String.format(
                    "ALTER TABLE `%s`.`%s` ADD COLUMN IF NOT EXISTS `%s` %s",
                    database, table, columnName, chType);

            try {
                log.info("Schema drift reconciliation – executing DDL: {}", ddl);
                dbMetadata.executeSystemQuery(conn, ddl);
                log.info("Schema drift reconciliation – DDL executed successfully for column '{}' in {}.{}",
                        columnName, database, table);
            } catch (Exception e) {
                log.warn("Schema drift reconciliation – failed to add column '{}' to {}.{}: {}. " +
                                "Replication will continue; the event may be written without this column.",
                        columnName, database, table, e.getMessage());
            }
        }
    }

    /**
     * Maps a Debezium {@link Schema} (Kafka Connect type) to the equivalent
     * ClickHouse column type string.
     *
     * <p>Mapping table:
     * <table border="1">
     *   <tr><th>Debezium / Kafka Connect type</th><th>ClickHouse type</th></tr>
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
     * @param fieldSchema the Debezium/Kafka-Connect field schema
     * @return the ClickHouse column type string (always wrapped in {@code Nullable(…)})
     */
    String mapDebeziumTypeToClickHouse(Schema fieldSchema) {
        if (fieldSchema == null) {
            return "Nullable(String)";
        }

        // Check the logical type (schema name) first – more specific than the base type
        String logicalType = fieldSchema.name();
        if (logicalType != null) {
            switch (logicalType) {
                case "io.debezium.time.MicroTimestamp":
                    return "Nullable(DateTime64(6))";
                case "io.debezium.time.Timestamp":
                    return "Nullable(DateTime64(3))";
                case "io.debezium.time.NanoTimestamp":
                    return "Nullable(DateTime64(9))";
                case "io.debezium.time.ZonedTimestamp":
                    return "Nullable(DateTime64(6, 'UTC'))";
                case "io.debezium.time.Date":
                    return "Nullable(Date32)";
                case "io.debezium.time.MicroTime":
                    // Stored as microseconds since midnight
                    return "Nullable(Int64)";
                case "io.debezium.data.Uuid":
                    return "Nullable(UUID)";
                case "io.debezium.data.Json":
                    return "Nullable(String)";
                case "io.debezium.data.Enum":
                    return "Nullable(String)";
                case "io.debezium.data.Bits":
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

                int scale = (scaleStr != null) ? parseInt(scaleStr, 9) : 9;
                int precision = (precisionStr != null) ? parseInt(precisionStr, 38) : 38;

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
                log.warn("mapDebeziumTypeToClickHouse: unknown Schema.Type '{}', defaulting to Nullable(String)",
                        baseType);
                return "Nullable(String)";
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
