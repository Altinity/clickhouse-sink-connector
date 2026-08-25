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
import java.util.function.Supplier;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.ROW_KEY_COLUMN;
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
     * Supplies the target table's sorting-key columns, in key order.
     *
     * <p>A supplier rather than a value: the executor is constructed before the
     * DbWriter has necessarily resolved the table's metadata (a table created
     * by DDL rather than by auto-create resolves it a moment later), so a
     * snapshot taken at construction can be empty and would silently disable
     * the UPDATE tombstone. Reading through on use always sees current
     * metadata, including after a DDL refresh.</p>
     */
    private Supplier<List<String>> sortingKeyColumnsSupplier = ArrayList::new;

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
        this(replacingMergeTreeDeleteColumn, replacingMergeTreeWithIsDeletedColumn, signColumn,
                versionColumn, databaseName, serverTimeZone, ArrayList::new);
    }

    /**
     * Overload that additionally supplies the target table's sorting-key
     * columns, used to detect UPDATEs that relocate a row to a different
     * sorting key.
     *
     * @param replacingMergeTreeDeleteColumn The is_deleted column name.
     * @param replacingMergeTreeWithIsDeletedColumn Whether the new RMT engine is in use.
     * @param signColumn The sign column to mark updates and deletes.
     * @param versionColumn The version column for ReplacingMergeTree.
     * @param databaseName The name of the database.
     * @param serverTimeZone The time zone for the server.
     * @param sortingKeyColumnsSupplier Supplies the target table's sorting-key
     *                                  columns, in key order. Read on use, so
     *                                  it always reflects current metadata.
     */
    public PreparedStatementExecutor(String replacingMergeTreeDeleteColumn,
                                     boolean replacingMergeTreeWithIsDeletedColumn,
                                     String signColumn, String versionColumn,
                                     String databaseName, ZoneId serverTimeZone,
                                     Supplier<List<String>> sortingKeyColumnsSupplier) {

        this.databaseName = databaseName;
        this.serverTimeZone = serverTimeZone;
        this.sortingKeyColumnsSupplier =
                sortingKeyColumnsSupplier == null ? ArrayList::new : sortingKeyColumnsSupplier;
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

            DBMetadata metadata = new DBMetadata(config);
            ReplicationHistoryHandler replicationHistoryHandler = null;
            if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                replicationHistoryHandler = new ReplicationHistoryHandler(config, this.serverTimeZone, metadata);
            }
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
                        // A TRUNCATE must be applied at its binlog position, not at the
                        // end of the batch. Rows staged before it belong to the
                        // pre-truncate state and have to reach ClickHouse first; rows
                        // after it are the new state and must survive. Flush what is
                        // staged, truncate, then keep accumulating the remainder.
                        //
                        // Executing the truncate after executeBatch() (the previous
                        // behaviour) discarded every row the same batch had just
                        // inserted whenever a TRUNCATE was followed by more DML.
                        try {
                            ps.executeBatch();
                        } catch (SQLException e) {
                            throw new RuntimeException(String.format(
                                    "Failed to flush records staged before TRUNCATE for %s.%s",
                                    databaseName, tableName), e);
                        }
                        try {
                            metadata.truncateTable(conn, databaseName, tableName);
                        } catch (SQLException e) {
                            throw new RuntimeException(String.format(
                                    "TRUNCATE failed for %s.%s", databaseName, tableName), e);
                        }
                        continue;
                    }

                    // DELETE --> History Mode.
                    if (CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        if (replicationHistoryHandler != null &&
                            record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                                replicationHistoryHandler.executeHistoryUpdate(
                                    conn,
                                    tableName,
                                    record,
                                    columnToDataTypeMap,
                                    fieldMapper,
                                    entry.getKey().right,
                                    config,
                                    engine, true
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
                    }
                    // UPDATE HISTORY MODE.
                    else if (CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        if (engine != null && engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
                            fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                    true, config, columnToDataTypeMap, engine, tableName);
                        }
                        // ReplacingMergeTree deduplicates by SORTING KEY. An UPDATE that
                        // changes any sorting-key column therefore writes the new row at a
                        // DIFFERENT key, leaving the pre-update row in place forever: MySQL
                        // has one row, ClickHouse has two. Tombstone the before-image so the
                        // row's old position is retired.
                        //
                        // This matters most for tables with no PRIMARY KEY and no UNIQUE key,
                        // whose sorting key is every column (so any UPDATE relocates the row),
                        // but it is not specific to them -- an UPDATE of a real key column on
                        // a keyed table orphans the old row in exactly the same way.
                        //
                        // The tombstone is emitted ONLY when the key actually changes. Writing
                        // one for a same-key UPDATE would put a delete marker and the new row
                        // at the same key with the same _version, and ReplacingMergeTree breaks
                        // that tie by insertion order -- which can permanently drop a live row.
                        //
                        // Skipped in replication-history mode: that mode keeps its own SCD
                        // Type 2 history via ReplicationHistoryHandler, whose sorting key
                        // includes deleted_time, and it retires the old version itself.
                        else if (replicationHistoryHandler == null && isReplacingMergeTree(engine)
                                && updateRelocatesSortingKey(record)) {
                            fieldMapper.insertTombstonePreparedStatement(entry.getKey().right, ps,
                                    record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                    config, columnToDataTypeMap, engine, tableName);
                            ps.addBatch();
                        }
                        if (replicationHistoryHandler != null) {
                            // Use ReplicationHistoryHandler for SCD Type 2 updates
                            // tableName is already fully-qualified (e.g., binlog_history.employees_temporal_test)
                            replicationHistoryHandler.executeHistoryUpdate(
                                    conn,
                                    tableName,
                                    record,
                                    columnToDataTypeMap,
                                    fieldMapper,
                                    entry.getKey().right,
                                    config,
                                    engine, false
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

        });

        return result.get();
    }

    /**
     * Whether the engine deduplicates by sorting key, i.e. is a
     * ReplacingMergeTree variant.
     *
     * @param engine The target table engine.
     * @return true for (Replicated)ReplacingMergeTree.
     */
    private boolean isReplacingMergeTree(DBMetadata.TABLE_ENGINE engine) {
        if (engine == null) {
            return false;
        }
        return engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())
                || engine.getEngine().equalsIgnoreCase(
                        DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine());
    }

    /**
     * Whether an UPDATE moves the row to a different sorting key, which leaves
     * the pre-update row stranded under ReplacingMergeTree unless it is
     * tombstoned.
     *
     * <p>Compares only the sorting-key columns, and only those actually present
     * in both the before and after images. Returns {@code false} when the
     * sorting key is unknown or empty, so an unreadable sorting key degrades to
     * the previous behaviour rather than emitting a speculative tombstone.</p>
     *
     * <p>A keyless source table is sorted by the generated {@code _row_key}
     * column, a fingerprint of the whole row. No CDC record carries that column,
     * so the per-column loop below would skip it and report "not relocated" for
     * every UPDATE -- stranding the pre-update row and reintroducing exactly the
     * loss the fingerprint exists to prevent. For that key any change to any
     * value moves the row by construction, so the full before and after images
     * are compared instead.</p>
     *
     * @param record The CDC record carrying both before and after images.
     * @return true when at least one sorting-key column changed value.
     */
    boolean updateRelocatesSortingKey(ClickHouseStruct record) {
        List<String> sortingKeyColumns = sortingKeyColumnsSupplier.get();
        if (sortingKeyColumns == null || sortingKeyColumns.isEmpty()) {
            return false;
        }
        // Match the row-key SHAPE, not the literal constant: when the source
        // table declares a column of that name the generated one is renamed
        // with extra leading underscores. Comparing only to ROW_KEY_COLUMN
        // misses the renamed form, the loop below then skips it (no CDC record
        // carries it), and the method reports "not relocated" for every UPDATE
        // -- stranding the pre-update row and duplicating data for exactly the
        // name-clash case this path exists to support.
        String soleSortingKey = sortingKeyColumns.get(0).replace("`", "").trim();
        if (sortingKeyColumns.size() == 1
                && PreparedStatementFieldMapper.isRowKeyColumn(soleSortingKey)) {
            org.apache.kafka.connect.data.Struct beforeRow = record.getBeforeStruct();
            org.apache.kafka.connect.data.Struct afterRow = record.getAfterStruct();
            if (beforeRow == null || afterRow == null) {
                return false;
            }
            // A source column of the row-key shape is ordinary data carried by
            // the record; only the connector-generated column is absent from
            // it, and only that one needs the whole-row comparison.
            if (afterRow.schema().field(soleSortingKey) != null) {
                return !Objects.equals(beforeRow.get(soleSortingKey),
                        afterRow.get(soleSortingKey));
            }
            for (org.apache.kafka.connect.data.Field field : afterRow.schema().fields()) {
                if (beforeRow.schema().field(field.name()) == null) {
                    continue;
                }
                if (!Objects.equals(beforeRow.get(field.name()), afterRow.get(field.name()))) {
                    return true;
                }
            }
            return false;
        }
        // Fully qualified: java.sql.* is imported wholesale above and also
        // defines a Struct.
        org.apache.kafka.connect.data.Struct before = record.getBeforeStruct();
        org.apache.kafka.connect.data.Struct after = record.getAfterStruct();
        if (before == null || after == null) {
            return false;
        }
        for (String keyColumn : sortingKeyColumns) {
            // A sorting key may be an expression over columns the record does
            // not carry (for example toDate(deleted_time)); skip what is absent
            // from either image rather than guessing.
            if (before.schema().field(keyColumn) == null || after.schema().field(keyColumn) == null) {
                continue;
            }
            if (!Objects.equals(before.get(keyColumn), after.get(keyColumn))) {
                return true;
            }
        }
        return false;
    }
}
