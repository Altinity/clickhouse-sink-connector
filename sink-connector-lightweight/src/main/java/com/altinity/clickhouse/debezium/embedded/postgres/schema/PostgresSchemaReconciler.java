package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
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
                    "%s `%s`.`%s` %s IF NOT EXISTS `%s` %s",
                    ClickHouseDbConstants.ALTER_TABLE, database, table,
                    ClickHouseDbConstants.ALTER_TABLE_ADD_COLUMN, columnName, chType);

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
     * <p>Delegates to the centralized
     * {@link ClickHouseDataTypeMapper#mapDebeziumSchemaToDDL(Schema)} so that
     * all DDL type-mapping logic lives in one place alongside the existing
     * enum-based mapping in {@code ClickHouseDataTypeMapper}.
     *
     * @param fieldSchema the Debezium/Kafka-Connect field schema
     * @return the ClickHouse column type string (always wrapped in {@code Nullable(…)})
     * @see ClickHouseDataTypeMapper#mapDebeziumSchemaToDDL(Schema)
     */
    String mapDebeziumTypeToClickHouse(Schema fieldSchema) {
        return ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(fieldSchema);
    }
}
