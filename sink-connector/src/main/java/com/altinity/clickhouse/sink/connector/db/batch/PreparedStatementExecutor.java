package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.altinity.clickhouse.sink.connector.db.batch.CdcOperation.getCdcSectionBasedOnOperation;

/**
 * The PreparedStatementExecutor class is responsible for executing prepared
 * SQL statements in batches for inserting data into ClickHouse. It handles the
 * logic for processing CDC (Change Data Capture) operations, including handling
 * truncations, updates, and inserts with the appropriate data mapping and conversions.
 */
public class PreparedStatementExecutor {
    /**
     * Logger instance for logging purposes.
     * This logger is used throughout the class to log messages related to database operations.
     */
    private static final Logger log = LogManager.getLogger(PreparedStatementExecutor.class);

    /**
     * The name of the database being used for the operations in this class.
     * This is typically set when connecting to the ClickHouse instance.
     */
    private String databaseName;

    /**
     * Field mapper responsible for inserting ClickHouseStruct fields into PreparedStatements.
     */
    private PreparedStatementFieldMapper fieldMapper;

    private ZoneId serverTimeZone;

    /**
     * Constructor for PreparedStatementExecutor.
     * Initializes the instance with the provided configuration values.
     *
     * @param replacingMergeTreeDeleteColumn The column used for deletion in ReplacingMergeTree.
     * @param replacingMergeTreeWithIsDeletedColumn Whether to use the "is_deleted" column for deletion.
     * @param signColumn The sign column to mark updates and deletes.
     * @param versionColumn The version column for ReplacingMergeTree.
     * @param databaseName The name of the database.
     * @param serverTimeZone The time zone for the server.
     */
    public PreparedStatementExecutor(String replacingMergeTreeDeleteColumn,
                                     boolean replacingMergeTreeWithIsDeletedColumn,
                                     String signColumn, String versionColumn,
                                     String databaseName, ZoneId serverTimeZone) {

        this.databaseName = databaseName;
        this.serverTimeZone = serverTimeZone;
        // Initialize the field mapper with the same configuration
        this.fieldMapper = new PreparedStatementFieldMapper(
                replacingMergeTreeDeleteColumn,
                replacingMergeTreeWithIsDeletedColumn,
                signColumn,
                versionColumn,
                databaseName,
                serverTimeZone
        );
    }

    /**
     * Iterates through records and adds them to a JDBC prepared statement batch
     * for execution. It processes each query, logging the insert query and
     * managing any errors that occur during execution.
     *
     * @param topicName The Kafka topic name.
     * @param queryToRecordsMap The map of queries to records.
     * @param bmd Block metadata.
     * @param config Connector configuration.
     * @param conn The database connection.
     * @param tableName The name of the target table.
     * @param columnToDataTypeMap A map of column names to their data types.
     * @param engine The table engine to use.
     * @return true if all queries are successfully executed; false otherwise.
     * @throws Exception if an error occurs during execution.
     */
    public boolean addToPreparedStatementBatch(String topicName, Map<MutablePair<String, Map<String, Integer>>,
            List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd,
                                               ClickHouseSinkConnectorConfig config,
                                               Connection conn,
                                               String tableName,
                                               Map<String, String> columnToDataTypeMap,
                                               DBMetadata.TABLE_ENGINE engine) throws Exception {

        boolean result = false;
        Iterator<Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> iter = queryToRecordsMap.entrySet().iterator();
        while(iter.hasNext()) {
            Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry = iter.next();
            String insertQuery = entry.getKey().getKey();
            log.info(String.format("*** INSERT QUERY for Database(%s) ***: %s", databaseName, insertQuery));
            // Create Hashmap of PreparedStatement(Query) -> Set of records
            // because the data will contain a mix of SQL statements(multiple columns)
            if (!executePreparedStatement(insertQuery, topicName, entry, bmd, config,
                    conn, tableName, columnToDataTypeMap, engine)) {
                log.error(String.format("**** ERROR: executing prepared statement for Database(%s), " +
                        "table(%s), Query(%s) ****", databaseName, tableName, insertQuery));
                result = false;
                break;
            } else {
                result = true;
            }
            if (entry.getValue().isEmpty()) {
                // All records were processed.
                iter.remove();
            }
            Metrics.updateCounters(topicName, entry.getValue().size());
        }

        return result;
    }

    /**
     * Executes the prepared statement in batches, processing CDC operations
     * such as insert, update, delete, and truncate.
     *
     * @param insertQuery The SQL insert query.
     * @param topicName The Kafka topic name.
     * @param entry The entry from the query-to-record map.
     * @param bmd Block metadata.
     * @param config Connector configuration.
     * @param conn The database connection.
     * @param tableName The name of the table.
     * @param columnToDataTypeMap A map of column names to data types.
     * @param engine The table engine to use.
     * @return true if the batch is successfully executed; false otherwise.
     * @throws Exception if an error occurs during batch execution.
     */
    private boolean executePreparedStatement(String insertQuery, String topicName,
                                             Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry,
                                             BlockMetaData bmd, ClickHouseSinkConnectorConfig config,
                                             Connection conn, String tableName, Map<String, String> columnToDataTypeMap,
                                             DBMetadata.TABLE_ENGINE engine) throws Exception {

        AtomicBoolean result = new AtomicBoolean(false);
        long maxRecordsInBatch = config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS.toString());
        List<ClickHouseStruct> failedRecords = new ArrayList<>();

        Lists.partition(entry.getValue(), (int)maxRecordsInBatch).forEach(batch -> {

            String databaseName = null;
            ArrayList<ClickHouseStruct> truncatedRecords = new ArrayList<>();

            DBMetadata metadata = new DBMetadata(config);
            try (PreparedStatement ps = metadata.getPreparedStatement(conn, insertQuery)) {

                for (ClickHouseStruct record : batch) {
                    boolean updateRecord = false;
                    if (record.getDatabase() != null)
                        databaseName = record.getDatabase();

                    try {
                        bmd.update(record);
                    } catch (Exception e) {
                        log.error("**** ERROR: updating Prometheus", e);
                    }

                    if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation())) {
                        truncatedRecords.add(record);
                        continue;
                    }

                    if (CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString()) &&
                            record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                                // Use ReplicationHistoryHandler for SCD Type 2 deletes
                                // tableName is already fully-qualified (e.g., binlog_history.employees_temporal_test)
                            fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                    true, config, columnToDataTypeMap, engine, tableName);
                            ps.addBatch();
//                            fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
//                                    false, config, columnToDataTypeMap, engine, tableName);
                                ReplicationHistoryHandler historyHandler = new ReplicationHistoryHandler(config, this.serverTimeZone);
                                historyHandler.executeHistoryDelete(
                                        conn,
                                        tableName,
                                        record,
                                        columnToDataTypeMap,
                                        fieldMapper,
                                        entry.getKey().right,
                                        config,
                                        engine
                                );
                                updateRecord = true;
                        }
                        else {
                            fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                    true, config, columnToDataTypeMap, engine, tableName);
                        }
                    } else if (CdcRecordState.CDC_RECORD_STATE_AFTER == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getAfterModifiedFields(), record, record.getAfterStruct(),
                                false, config, columnToDataTypeMap, engine, tableName);
                    } else if (CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        if (engine != null && engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
                            fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                    true, config, columnToDataTypeMap, engine, tableName);
                        }
                        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                            // Use ReplicationHistoryHandler for SCD Type 2 updates
                            // tableName is already fully-qualified (e.g., binlog_history.employees_temporal_test)
                            ReplicationHistoryHandler historyHandler = new ReplicationHistoryHandler(config, this.serverTimeZone);
                            historyHandler.executeHistoryUpdate(
                                    conn,
                                    tableName,
                                    record,
                                    columnToDataTypeMap,
                                    fieldMapper,
                                    entry.getKey().right,
                                    config,
                                    engine
                            );
                            updateRecord = true;
                        } else {
                            fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getAfterModifiedFields(), record, record.getAfterStruct(),
                                    false, config, columnToDataTypeMap, engine, tableName);
                        }
                    } else {
                        log.error("INVALID CDC RECORD STATE");
                    }
                    if(!updateRecord)
                        ps.addBatch();
                }

                int[] batchResult = ps.executeBatch();

                long taskId = config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
                log.info("*************** EXECUTED BATCH Successfully " + "Records: " + batch.size() + "************** " +
                        "task(" + taskId + ")" + " Thread ID: " +
                        Thread.currentThread().getName() + " Result: " +
                        batchResult.toString() + " Database: "
                        + databaseName + " Table: " + tableName);
                result.set(true);

            } catch (Exception e) {
                Metrics.updateErrorCounters(topicName, entry.getValue().size());
                log.error(String.format("******* ERROR inserting Batch Database(%s), Table(%s) *****************",
                        databaseName, tableName), e);
                failedRecords.addAll(batch);
                throw new RuntimeException(e);
            }

            if (!truncatedRecords.isEmpty()) {
                try {
                    metadata.truncateTable(conn, databaseName, tableName);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return result.get();
    }
}
