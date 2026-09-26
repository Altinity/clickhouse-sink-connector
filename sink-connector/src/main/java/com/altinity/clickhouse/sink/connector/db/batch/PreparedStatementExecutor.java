package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.*;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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
     * Executes the grouped batch: every segment in order, and within a
     * segment every query template as one JDBC prepared-statement batch.
     *
     * <p>A segment holding a replicated truncation event is executed as a
     * table truncation against THIS executor's database -- the resolved
     * target database (spec 03.04), which under
     * {@code clickhouse.database.override.map} differs from the source
     * database the record carries. Because segments are executed strictly in
     * order, every row grouped before the truncation has been written when it
     * runs and every row grouped after it is written afterwards; two
     * TRUNCATEs in one batch are two segments and both run (Spec 04.05 §3).</p>
     *
     * @param topicName The Kafka topic name.
     * @param querySegments The ordered segments, each a map of query template to records.
     * @param bmd Block metadata.
     * @param config Connector configuration.
     * @param conn The database connection.
     * @param tableName The name of the target table.
     * @param columnToDataTypeMap A map of column names to their data types.
     * @param engine The table engine to use.
     * @return true if all queries are successfully executed; false otherwise.
     * @throws Exception if an error occurs during execution.
     */
    public boolean addToPreparedStatementBatch(String topicName,
                                               List<Map<MutablePair<String, Map<String, Integer>>,
                                                       List<ClickHouseStruct>>> querySegments,
                                               BlockMetaData bmd,
                                               ClickHouseSinkConnectorConfig config,
                                               Connection conn,
                                               String tableName,
                                               Map<String, String> columnToDataTypeMap,
                                               DBMetadata.TABLE_ENGINE engine) throws Exception {

        if (querySegments == null || querySegments.isEmpty()) {
            // Returning false here made the caller keep the batch and retry
            // it on every tick, forever, although it could never produce a
            // statement. A batch that grouped into nothing is a defect to
            // surface, not a transient to wait out (Spec 04.01 section 3.3).
            throw new IllegalStateException(String.format(
                    "No statement group to execute for Database(%s), table(%s): the batch was "
                            + "grouped into nothing. Failing loudly instead of retrying it forever.",
                    databaseName, tableName));
        }
        boolean result = false;
        DBMetadata metadata = new DBMetadata(config);
        for (Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap
                : querySegments) {
            if (queryToRecordsMap.isEmpty()) {
                throw new IllegalStateException(String.format(
                        "Empty statement segment for Database(%s), table(%s): the grouping produced "
                                + "a segment with no query. Failing loudly instead of retrying it forever.",
                        databaseName, tableName));
            }
            Iterator<Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> iter =
                    queryToRecordsMap.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry = iter.next();
                if (isTruncateGroup(entry)) {
                    ClickHouseStruct truncateEvent = entry.getValue().get(0);
                    try {
                        bmd.update(truncateEvent);
                    } catch (Exception e) {
                        log.error("**** ERROR: updating Prometheus", e);
                    }
                    log.info(String.format("*** Applying replicated TRUNCATE to Database(%s), table(%s) "
                            + "at its binlog position ***", databaseName, tableName));
                    try {
                        // DESTRUCTIVE: applies a TRUNCATE that MySQL already
                        // executed (replicated change event op = t) to the
                        // resolved TARGET database/table of this executor;
                        // never issued on the connector's own initiative.
                        metadata.truncateTable(conn, databaseName, tableName);
                    } catch (SQLException e) {
                        // DESTRUCTIVE: error text only -- the truncation was NOT
                        // applied (every retry refused); the batch fails here.
                        throw new RuntimeException(String.format(
                                "TRUNCATE failed for %s.%s", databaseName, tableName), e);
                    }
                    result = true;
                    Metrics.updateCounters(topicName, entry.getValue().size());
                    continue;
                }
                String insertQuery = entry.getKey().getKey();
                // Per-batch progress line: INFO by design (spec 03.06 section 3.3) --
                // operators read the connector's progress from the log.
                log.info(String.format("*** INSERT QUERY for Database(%s) ***: %s", databaseName, insertQuery));
                // Create Hashmap of PreparedStatement(Query) -> Set of records
                // because the data will contain a mix of SQL statements(multiple columns)
                if (!executePreparedStatement(insertQuery, topicName, entry, bmd, config,
                        conn, tableName, columnToDataTypeMap, engine)) {
                    log.error(String.format("**** ERROR: executing prepared statement for Database(%s), " +
                            "table(%s), Query(%s) ****", databaseName, tableName, insertQuery));
                    return false;
                }
                result = true;
                if (entry.getValue().isEmpty()) {
                    // All records were processed.
                    iter.remove();
                }
                Metrics.updateCounters(topicName, entry.getValue().size());
            }
        }

        return result;
    }

    /**
     * Whether a query group is the marker group of a replicated TRUNCATE
     * event: exactly one record whose operation is TRUNCATE. The group's key
     * text is never executed; the statement is issued by
     * {@code DBMetadata.truncateTable} against the executor's database.
     */
    private static boolean isTruncateGroup(
            Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry) {
        List<ClickHouseStruct> records = entry.getValue();
        return records != null && records.size() == 1
                && GroupInsertQueryWithBatchRecords.isTruncate(records.get(0));
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
        // Chunks close on rows OR on estimated bytes (spec 03.06 section 3.1):
        // the driver renders a whole chunk as SQL text in memory before it is
        // sent, so on a wide-row table the row count alone bounds nothing.
        long maxBytesInBatch = config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_BYTES.toString());
        List<ClickHouseStruct> failedRecords = new ArrayList<>();

        BatchChunker.chunk(entry.getValue(), maxRecordsInBatch, maxBytesInBatch).forEach(batch -> {

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

                    if (GroupInsertQueryWithBatchRecords.isTruncate(record)) {
                        // A replicated truncation is applied at its binlog
                        // position by addToPreparedStatementBatch, as a
                        // segment of its own between the INSERT segments
                        // (Spec 04.05 section 3). It can never share an
                        // INSERT template's record list; if one does, the
                        // grouping is broken and binding it as a row would
                        // write garbage.
                        throw new IllegalStateException(String.format(
                                "Replicated truncation event (op = t) for %s.%s found inside an INSERT "
                                        + "group; the grouping must place it in a segment of its own.",
                                databaseName, tableName));
                    }

                    // DELETE --> History Mode.
                    if (CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        if (replicationHistoryHandler != null &&
                            record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                                // The SCD Type 2 delete reads the row it is closing
                                // straight back out of the target
                                // (INSERT ... SELECT ... FROM <table> FINAL WHERE ...),
                                // and it runs INLINE. Plain inserts in the same batch are
                                // only staged on the PreparedStatement and do not reach
                                // ClickHouse until executeBatch() below. So when one batch
                                // carries a row's CREATE and its DELETE -- ordinary for a
                                // busy source -- the delete's SELECT runs BEFORE the insert
                                // it depends on, matches nothing, and writes zero rows. No
                                // error is raised: the delete is silently dropped and the
                                // row stays visible in ClickHouse forever.
                                //
                                // Flush what is staged first so the delete observes the
                                // same state the source did at that binlog position. This
                                // is the same ordering rule the TRUNCATE branch above
                                // already applies, and it is why the defect looked
                                // intermittent -- it only bites when the CREATE and the
                                // DELETE land in one batch.
                                try {
                                    ps.executeBatch();
                                } catch (SQLException e) {
                                    throw new RuntimeException(String.format(
                                            "Failed to flush records staged before a replication-history "
                                                    + "DELETE for %s.%s", databaseName, tableName), e);
                                }
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
                            // CollapsingMergeTree: the before image is the -1
                            // cancel row that retires the pre-update row. It
                            // must be STAGED before the after image is bound,
                            // or the after image simply overwrites the same
                            // parameters and the only row that reaches
                            // ClickHouse is a second +1 (Spec 05.04 section 3.1).
                            fieldMapper.insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(),
                                    true, config, columnToDataTypeMap, engine, tableName);
                            ps.addBatch();
                            // The V2 driver's addBatch() keeps the bound values.
                            // The after image below binds sign=+1 and its own
                            // columns, but any parameter it does not rebind (a
                            // column absent from the after image) would silently
                            // inherit this cancel row's value -- including
                            // sign=-1, which turns the live row into a second
                            // cancel row and the UPDATE never lands. Clear the
                            // bind state, exactly as the ReplacingMergeTree
                            // relocation tombstone below does (Spec 07.07 section
                            // 3.2.2, Spec 05.04 section 3.1).
                            ps.clearParameters();
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
                            // The V2 driver's addBatch() keeps the bound values; a
                            // parameter the next row fails to bind would silently
                            // carry this row's value (Spec 07.07 section 3.2.2).
                            ps.clearParameters();
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
                        // Not reachable today, but staging the statement with
                        // whatever parameters the previous row left behind
                        // would write a duplicate of that row (Spec 04.01
                        // section 3.3).
                        throw new IllegalStateException(String.format(
                                "Record with operation %s for %s.%s has no recognised CDC record "
                                        + "state; nothing was bound for it and it is not staged.",
                                record.getCdcOperation(), databaseName, tableName));
                    }
                    if(!updateRecord) {
                        ps.addBatch();
                        // See above: no bind state may survive into the next row.
                        ps.clearParameters();
                    }
                }

                int[] batchResult = ps.executeBatch();

                long taskId = config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
                // Per-batch progress line: INFO by design (spec 03.06 section 3.3) --
                // operators read the connector's progress from the log.
                log.info("*************** EXECUTED BATCH Successfully " + "Records: " + batch.size() + "************** " +
                        "task(" + taskId + ")" + " Thread ID: " +
                        Thread.currentThread().getName() + " Result: " +
                        describeBatchResult(batchResult) + " Database: "
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
     * Renders the driver's {@code executeBatch()} answer for the per-batch
     * progress line (spec 03.06 section 3.3, line 3): how many statements the
     * driver acknowledged -- one entry per staged statement, so every row plus
     * one tombstone per sorting-key relocation -- and, only when the driver
     * flagged any, how many it marked {@link Statement#EXECUTE_FAILED}.
     *
     * <p>Never the array itself: {@code int[].toString()} renders as an
     * identity hash ({@code [I@6cee4818}) that changes on every line and
     * carries no information, which is what this field printed before.</p>
     */
    static String describeBatchResult(int[] batchResult) {
        if (batchResult == null) {
            return "no result";
        }
        int failed = 0;
        for (int updateCount : batchResult) {
            if (updateCount == Statement.EXECUTE_FAILED) {
                failed++;
            }
        }
        return batchResult.length + " statements acknowledged"
                + (failed == 0 ? "" : ", " + failed + " marked EXECUTE_FAILED");
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
     * @param record The CDC record carrying both before and after images.
     * @return true when at least one sorting-key column changed value.
     */
    boolean updateRelocatesSortingKey(ClickHouseStruct record) {
        List<String> sortingKeyColumns = sortingKeyColumnsSupplier.get();
        if (sortingKeyColumns == null || sortingKeyColumns.isEmpty()) {
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
            // Compare the STORED values, never the Connect-schema default.
            // Struct.get() answers schema.defaultValue() for a null field, and
            // Debezium fills that default from the MySQL column DEFAULT, so an
            // UPDATE of a key column from NULL to its DEFAULT compared equal,
            // was judged in-place, and the old row at the NULL key was never
            // tombstoned. The bind path reads stored values for the same
            // reason (Spec 04.03 section 3.3); this decision must observe the
            // same values the rows are written with (Spec 05.01 section 3.2).
            if (!Objects.equals(before.getWithoutDefault(keyColumn), after.getWithoutDefault(keyColumn))) {
                return true;
            }
        }
        return false;
    }
}
