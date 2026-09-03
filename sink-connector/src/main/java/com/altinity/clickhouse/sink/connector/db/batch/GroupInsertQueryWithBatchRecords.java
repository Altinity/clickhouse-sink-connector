package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.util.*;

import static com.altinity.clickhouse.sink.connector.db.batch.CdcOperation
        .getCdcSectionBasedOnOperation;

/**
 * This class groups insert queries with their batch records.
 * <p>
 * It processes a list of ClickHouseStruct records to create a mapping
 * between an insert query template and the corresponding batch of records.
 * It also updates the topic-partition offset map with the latest offsets.
 * </p>
 */
public class GroupInsertQueryWithBatchRecords {

    private static final Logger log =
            LogManager.getLogger(GroupInsertQueryWithBatchRecords.class);

    /**
     * Groups records by their insert query template and updates the
     * topic-partition offset map.
     * <p>
     * This function processes records to build a mapping between an
     * insert query (and its associated column-to-index map) and a list of
     * records that share that query. It also updates the partition-to-offset
     * map with the highest offset per topic partition.
     * </p>
     *
     * @param records              list of ClickHouseStruct records.
     * @param queryToRecordsMap    map of query template to list of records.
     * @param partitionToOffsetMap map of TopicPartition to latest offset.
     * @param config               connector configuration.
     * @param tableName            target table name.
     * @param databaseName         target database name.
     * @param connection           JDBC connection.
     * @param columnNameToDataTypeMap map of column names to their data types.
     * @return true if grouping is successful; false otherwise.
     */
    public boolean groupQueryWithRecords(
            List<ClickHouseStruct> records,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            Map<TopicPartition, Long> partitionToOffsetMap,
            ClickHouseSinkConnectorConfig config,
            String tableName, String databaseName, Connection connection,
            Map<String, String> columnNameToDataTypeMap) {
        boolean result = false;

        // Co4 = {ClickHouseStruct@9220} de block to create a Map of Query ->
        // list of records so that all records belonging to the same query
        // can be inserted as a batch.
        Iterator iterator = records.iterator();
        while (iterator.hasNext()) {
            ClickHouseStruct record = (ClickHouseStruct) iterator.next();
            if (record != null && record.getKafkaPartition() != null &&
                    record.getTopic() != null) {
                updatePartitionOffsetMap(partitionToOffsetMap,
                        record.getKafkaPartition(), record.getTopic(),
                        record.getKafkaOffset());
            }
            boolean enableSchemaEvolution = config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION
                            .toString());

            // GUARANTEE THE CACHE MATCHES THE SOURCE BEFORE USING IT.
            //
            // columnNameToDataTypeMap is a cached view of the ClickHouse table.
            // It is refreshed when a DDL event is parsed and matched to this
            // table, which covers the common path but is not a guarantee: the
            // refresh depends on the DDL being recognised and its table name
            // resolved, and any DDL that arrives on another connector instance,
            // is applied out of band, or whose table name does not resolve
            // leaves this map describing a table that no longer exists in that
            // shape.
            //
            // The record itself is the cheapest available witness of the source
            // metadata. If it carries a column the cached map does not know
            // about, the cache is provably behind the source and must not be
            // used to build an INSERT -- doing so drops that column's value
            // silently, with row counts intact.
            //
            // Re-reading costs one metadata query and only happens on an actual
            // mismatch, so the steady state is unaffected.
            Map<String, String> verified = refreshIfRecordHasUnknownColumn(
                    record, columnNameToDataTypeMap, tableName, databaseName,
                    connection, config);
            if (verified != null) {
                columnNameToDataTypeMap = verified;
            }

            if (CdcRecordState.CDC_RECORD_STATE_BEFORE ==
                    getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                result = updateQueryToRecordsMap(record,
                        record.getBeforeModifiedFields(), queryToRecordsMap,
                        tableName, config, columnNameToDataTypeMap);
            } else if (CdcRecordState.CDC_RECORD_STATE_AFTER ==
                    getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                if (enableSchemaEvolution) {
                    try {
                        new ClickHouseAlterTable().alterTable(
                                record.getAfterStruct().schema().fields(),
                                tableName, connection, columnNameToDataTypeMap, config);
                        columnNameToDataTypeMap = new DBMetadata(config)
                                .getColumnsDataTypesForTable(tableName,
                                        connection, databaseName);
                    } catch (Exception e) {
                        log.error("**** ERROR ALTER TABLE: " + tableName, e);
                    }
                }
                // columnNameToDataTypeMap = new DBMetadata().getColumnsDataTypesForTable(
                // tableName, connection, databaseName, config );
                result = updateQueryToRecordsMap(record,
                        record.getAfterModifiedFields(), queryToRecordsMap,
                        tableName, config, columnNameToDataTypeMap);
            }
            // UPDATE: This creates 2 records, one with before and another one with after.
            else if (CdcRecordState.CDC_RECORD_STATE_BOTH ==
                    getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                // if replication history is enabled, then dont split to 2 records.
                if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                        result = updateQueryToRecordsMap(record,
                                record.getAfterModifiedFields(), queryToRecordsMap,
                                tableName, config, columnNameToDataTypeMap);
                        // `continue`, NOT `return`: this is inside the per-record
                        // loop. Returning here abandoned every remaining record in
                        // the batch the moment the first UPDATE was seen -- silently,
                        // with no error and no metric, while the offset still
                        // advanced past the discarded rows. A single MySQL statement
                        // touching N rows emits N records in one batch, so all but
                        // the first were lost. Only history mode took this branch,
                        // which is why the standard flow was unaffected.
                        continue;
                }
                                
                if (record.getBeforeModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record,
                            record.getBeforeModifiedFields(), queryToRecordsMap,
                            tableName, config, columnNameToDataTypeMap);
                }
                if (record.getAfterModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record,
                            record.getAfterModifiedFields(), queryToRecordsMap,
                            tableName, config, columnNameToDataTypeMap);
                }
            } else {
                log.error("************ RECORD DROPPED: INVALID CDC RECORD " +
                        "STATE *****************" + record.getSourceRecord());
            }
        }
        return result;
    }

    /**
     * Updates the mapping of query template to records.
     * <p>
     * For a given record, this function builds the insert query template
     * (using a prepared statement) and updates the mapping from that
     * template (and its column index map) to the list of records.
     * If the operation is TRUNCATE, a TRUNCATE TABLE command is added.
     * </p>
     *
     * @param record             a ClickHouseStruct record.
     * @param modifiedFields     list of modified fields.
     * @param queryToRecordsMap  map from query template to list of records.
     * @param tableName          target table name.
     * @param config             connector configuration.
     * @param columnNameToDataTypeMap map of column names to data types.
     * @return true if the mapping is updated; false otherwise.
     */
    public boolean updateQueryToRecordsMap(
            ClickHouseStruct record, List<Field> modifiedFields,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            String tableName, ClickHouseSinkConnectorConfig config,
            Map<String, String> columnNameToDataTypeMap) {

        // Step 1: If its a TRUNCATE OPERATION, add a TRUNCATE TABLE command.
        if (record.getCdcOperation().getOperation()
                .equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE
                        .getOperation())) {
            MutablePair<String, Map<String, Integer>> mp = new MutablePair<>();
            mp.setLeft(String.format("TRUNCATE TABLE %s", tableName));
            mp.setRight(new HashMap<String, Integer>());
            ArrayList<ClickHouseStruct> records = new ArrayList<>();
            records.add(record);
            queryToRecordsMap.put(mp, records);
            return true;
        }

        // Step 2: Create the Prepared Statement Query.
        //
        // `modifiedFields` is value-filtered: ClickHouseStruct#setAfterStruct
        // keeps only the fields whose value is != null, so a column that is
        // genuinely NULL in the source row is missing from it. Deciding INSERT
        // membership from that list drops the column from the statement and
        // ClickHouse substitutes the column DEFAULT (0 / '' / 1970-01-01)
        // instead of NULL -- silent divergence with matching row counts.
        //
        // The record's unfiltered SCHEMA is the correct authority for
        // membership, so it is passed alongside. A column absent from the
        // SCHEMA is genuinely not in this record (the pre-ALTER case, #1389)
        // and is still omitted.
        List<Field> schemaFields = schemaFieldsFor(record, modifiedFields);

        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        tableName, modifiedFields, columnNameToDataTypeMap,
                        config.getBoolean(
                                ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA
                                        .toString()),
                        config.getBoolean(
                                ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA
                                        .toString()),
                        config.getString(
                                ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN
                                        .toString()),
                        record.getDatabase(),
                        config.getString(
                                ClickHouseSinkConnectorConfigVariables
                                        .REPLACING_MERGE_TREE_DELETE_COLUMN.toString()),
                        schemaFields);

        String insertQueryTemplate = response.getKey();
        if (response.getKey() == null || response.getValue() == null) {
            log.error("********* QUERY or COLUMN TO INDEX MAP EMPTY");
            return false;
            // this.columnNametoIndexMap = response.right;
        }

        MutablePair<String, Map<String, Integer>> mp =
                new MutablePair<>();
        mp.setLeft(insertQueryTemplate);
        mp.setRight(response.getValue());

        if (!queryToRecordsMap.containsKey(mp)) {
            List<ClickHouseStruct> newList = new ArrayList<>();
            newList.add(record);
            queryToRecordsMap.put(mp, newList);
        } else {
            List<ClickHouseStruct> recordsList = queryToRecordsMap.get(mp);
            recordsList.add(record);
            queryToRecordsMap.put(mp, recordsList);
        }
        return true;
    }

    /**
     * Re-reads the table's column map from ClickHouse when the incoming record
     * carries a column the cached map does not contain.
     *
     * <p>The record's schema is a witness of the source table's shape at the
     * moment the event was captured. A column present there but missing from
     * the cached ClickHouse column map means the cache predates a schema change
     * -- the DDL either has not been seen by this instance, was applied out of
     * band, or was seen but not matched to this table. Whatever the reason, the
     * cache is behind the source and building an INSERT from it drops that
     * column's value with no error and no row-count change.</p>
     *
     * <p>Only connector-managed columns are excluded from the comparison; they
     * are populated by the connector and never appear in the ClickHouse table
     * under a source-record name.</p>
     *
     * <p>Returns {@code null} when the cache is already consistent (the common
     * case, costing one set lookup per column) or when the re-read fails. A
     * failed re-read is logged and the caller keeps the existing map: the
     * bind-time check in {@code PreparedStatementFieldMapper} is the backstop
     * and will fail the batch rather than write a dropped column, so a metadata
     * outage degrades to a retry rather than to silent loss.</p>
     *
     * @param record           the CDC record whose schema is the witness.
     * @param cached           the currently cached column-to-type map.
     * @param tableName        the ClickHouse table name.
     * @param databaseName     the ClickHouse database name.
     * @param connection       the ClickHouse connection to re-read with.
     * @param config           the connector configuration.
     * @return a freshly read column map, or null to keep the cached one.
     */
    private Map<String, String> refreshIfRecordHasUnknownColumn(
            ClickHouseStruct record, Map<String, String> cached,
            String tableName, String databaseName, Connection connection,
            ClickHouseSinkConnectorConfig config) {

        if (cached == null || cached.isEmpty() || connection == null) {
            return null;
        }

        Struct struct = record.getAfterStruct() != null
                ? record.getAfterStruct() : record.getBeforeStruct();
        if (struct == null || struct.schema() == null) {
            return null;
        }

        Set<String> known = new HashSet<>();
        for (String column : cached.keySet()) {
            if (column != null) {
                known.add(column.toLowerCase());
            }
        }

        String unknown = null;
        for (Field field : struct.schema().fields()) {
            if (field == null || field.name() == null) {
                continue;
            }
            if (!known.contains(field.name().toLowerCase())) {
                unknown = field.name();
                break;
            }
        }
        if (unknown == null) {
            return null;
        }

        log.warn("Cached schema for {}.{} does not contain column '{}' carried by the "
                        + "incoming record; the cache is stale relative to the source. "
                        + "Re-reading table metadata before building the INSERT.",
                databaseName, tableName, unknown);
        try {
            Map<String, String> fresh = new DBMetadata(config)
                    .getColumnsDataTypesForTable(tableName, connection, databaseName);
            if (fresh != null && !fresh.isEmpty()) {
                // Bump the shared version so every other cached writer for this
                // table rebuilds too, rather than each one rediscovering the
                // staleness independently on its own next batch.
                CacheInvalidationManager.getInstance()
                        .invalidateTable(databaseName + "." + tableName);
                return fresh;
            }
            log.warn("Re-read of {}.{} returned no columns; keeping the cached map. The "
                            + "bind-time check will fail the batch if a value would be dropped.",
                    databaseName, tableName);
        } catch (Exception e) {
            log.warn("Could not re-read metadata for {}.{}; keeping the cached map. The "
                            + "bind-time check will fail the batch if a value would be dropped.",
                    databaseName, tableName, e);
        }
        return null;
    }

    /**
     * Returns the unfiltered schema fields of whichever image
     * {@code modifiedFields} was derived from.
     *
     * <p>The caller passes either {@code getBeforeModifiedFields()} or
     * {@code getAfterModifiedFields()}, both of which are value-filtered copies
     * of their struct's schema. Identity comparison picks the matching struct,
     * so the before-image is never resolved against the after-image's schema
     * (they differ for an UPDATE that changes which columns are NULL).</p>
     *
     * <p>Returns null when the originating struct cannot be identified, which
     * makes {@code getInsertQueryUsingInputFunction} fall back to the
     * value-filtered list -- the previous behaviour.</p>
     *
     * @param record         the CDC record carrying the before/after structs.
     * @param modifiedFields the value-filtered list handed to this call.
     * @return the corresponding struct's full schema fields, or null.
     */
    private List<Field> schemaFieldsFor(ClickHouseStruct record,
                                        List<Field> modifiedFields) {
        if (record == null || modifiedFields == null) {
            return null;
        }
        if (modifiedFields == record.getAfterModifiedFields()
                && record.getAfterStruct() != null) {
            return record.getAfterStruct().schema().fields();
        }
        if (modifiedFields == record.getBeforeModifiedFields()
                && record.getBeforeStruct() != null) {
            return record.getBeforeStruct().schema().fields();
        }
        return null;
    }

    /**
     * Updates the map of TopicPartition to offset (max).
     * <p>
     * This function updates the offset map with the highest offset for a given
     * topic and partition.
     * </p>
     *
     * @param offsetToPartitionMap map from TopicPartition to offset.
     * @param partition            partition number.
     * @param topic                topic name.
     * @param offset               current record offset.
     */
    private void updatePartitionOffsetMap(
            Map<TopicPartition, Long> offsetToPartitionMap, int partition,
            String topic, long offset) {

        TopicPartition tp = new TopicPartition(topic, partition);

        // Check if record exists.
        if (!offsetToPartitionMap.containsKey(tp)) {
            // Record does not exist.
            offsetToPartitionMap.put(tp, offset);
        } else {
            // Record exists. Update only if the current offset
            // is greater than the offset stored.
            long storedOffset = offsetToPartitionMap.get(tp);
            if (offset > storedOffset) {
                offsetToPartitionMap.put(tp, offset);
            }
        }
    }
}
