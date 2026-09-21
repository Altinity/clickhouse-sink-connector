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
     * @throws IllegalStateException when a record cannot be grouped (no image
     *         for its operation, no column metadata, no template). Every
     *         record is grouped or the batch fails: a skipped record is
     *         written nowhere while the offset advances past it, so there is
     *         deliberately no boolean status to report one (Spec 04.01
     *         section 3.3).
     */
    public void groupQueryWithRecords(
            List<ClickHouseStruct> records,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            Map<TopicPartition, Long> partitionToOffsetMap,
            ClickHouseSinkConnectorConfig config,
            String tableName, String databaseName, Connection connection,
            Map<String, String> columnNameToDataTypeMap) {

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
                updateQueryToRecordsMap(record,
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
                updateQueryToRecordsMap(record,
                        record.getAfterModifiedFields(), queryToRecordsMap,
                        tableName, config, columnNameToDataTypeMap);
            }
            // UPDATE: the record carries a before and an after image. It is
            // grouped ONCE, under the template built from the after image;
            // PreparedStatementExecutor binds whichever images the engine
            // needs (the before image shares the schema, so it resolves to the
            // same template and the same parameter map). Grouping it once per
            // image -- the previous behaviour -- appended the SAME record twice
            // to the same list, so every UPDATE was bound and written twice:
            // 2x write amplification on ReplacingMergeTree, and two +1 rows
            // with no -1 row on CollapsingMergeTree (Spec 04.01 section 3.2,
            // 04.04 section 3.1).
            else if (CdcRecordState.CDC_RECORD_STATE_BOTH ==
                    getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                // In history mode the handler builds its own SCD Type 2
                // statement from the after image; the standard flow binds
                // both images from the one entry. Either way: one entry.
                //
                // No early `return` here: returning from inside the per-record
                // loop abandoned every remaining record in the batch the moment
                // the first UPDATE was seen -- silently, with no error and no
                // metric, while the offset still advanced past the discarded
                // rows. A single MySQL statement touching N rows emits N
                // records in one batch, so all but the first were lost.
                updateQueryToRecordsMap(record,
                        record.getAfterModifiedFields(), queryToRecordsMap,
                        tableName, config, columnNameToDataTypeMap);
            } else {
                // Not reachable today (getCdcSectionBasedOnOperation defaults
                // to AFTER), but a record that is neither BEFORE, AFTER nor
                // BOTH must never be logged and forgotten: it would be written
                // nowhere while the batch's offset advanced past it.
                throw new IllegalStateException(String.format(
                        "%s on table %s has no recognised CDC record state and cannot be "
                                + "grouped into a statement. Refusing to drop it (Spec 04.01 "
                                + "section 3.3).",
                        describe(record), tableName));
            }
        }
    }

    /**
     * Identifies a record in an error message: operation, topic, partition
     * and offset. Never throws for a sparsely populated record.
     */
    private static String describe(ClickHouseStruct record) {
        return String.format("Record(operation=%s, topic=%s, partition=%s, offset=%s)",
                record.getCdcOperation() == null ? null : record.getCdcOperation().getOperation(),
                record.getTopic(), record.getKafkaPartition(), record.getKafkaOffset());
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
     * @throws IllegalStateException when the record carries no image for the
     *         section its operation binds, when no column metadata is
     *         available, or when no template can be built. The record is
     *         never skipped (Spec 04.01 section 3.3).
     */
    public void updateQueryToRecordsMap(
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
            mp.setLeft(String.format("TRUNCATE TABLE `%s`", tableName));
            mp.setRight(new HashMap<String, Integer>());
            ArrayList<ClickHouseStruct> records = new ArrayList<>();
            records.add(record);
            queryToRecordsMap.put(mp, records);
            return;
        }

        if (columnNameToDataTypeMap == null || columnNameToDataTypeMap.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "No ClickHouse column metadata is available for table %s while grouping %s, "
                            + "so no INSERT can be built for it. Skipping the record would drop it "
                            + "while the batch's offset advances past it; failing the batch instead "
                            + "(Spec 04.01 section 3.3).",
                    tableName, describe(record)));
        }

        // A record whose operation binds an image it does not carry cannot
        // be grouped. Returning false here (the previous behaviour) dropped
        // the record silently -- and an UPDATE without its after image was
        // grouped by its BEFORE image alone, i.e. written as a live row
        // holding the pre-update values.
        if (modifiedFields == null && schemaFieldsFor(record, modifiedFields) == null) {
            boolean bindsBefore = CdcRecordState.CDC_RECORD_STATE_BEFORE
                    == getCdcSectionBasedOnOperation(record.getCdcOperation());
            throw new IllegalStateException(String.format(
                    "%s on table %s carries no %s image, so its row cannot be built. A %s event "
                            + "must carry the %s image (MySQL binlog_row_image=FULL). Refusing to "
                            + "drop the record (Spec 04.01 section 3.3).",
                    describe(record), tableName, bindsBefore ? "before" : "after",
                    bindsBefore ? "DELETE" : "row", bindsBefore ? "before" : "after"));
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

        if (response == null || response.getKey() == null || response.getValue() == null) {
            throw new IllegalStateException(String.format(
                    "No INSERT template could be built for %s on table %s (query or parameter "
                            + "map empty). Refusing to drop the record (Spec 04.01 section 3.3).",
                    describe(record), tableName));
        }
        String insertQueryTemplate = response.getKey();

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

        String fullyQualifiedTableName = databaseName + "." + tableName;
        CacheInvalidationManager invalidation = CacheInvalidationManager.getInstance();

        String unknown = null;
        for (Field field : struct.schema().fields()) {
            if (field == null || field.name() == null) {
                continue;
            }
            if (known.contains(field.name().toLowerCase())) {
                continue;
            }
            // A column already proven absent by a fresh read is not evidence of
            // staleness: ClickHouse owns it (MATERIALIZED / ALIAS) and no
            // re-read will ever produce it. Skipping it here is what keeps the
            // probe at one metadata query per column per DDL generation rather
            // than one per record.
            if (invalidation.isColumnProvenAbsent(
                    fullyQualifiedTableName, field.name())) {
                continue;
            }
            unknown = field.name();
            break;
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
                if (containsColumn(fresh, unknown)) {
                    // The re-read resolved the staleness. Bump the shared
                    // version so every other cached writer for this table
                    // rebuilds too, rather than each one rediscovering the
                    // staleness independently on its own next batch.
                    invalidation.invalidateTable(fullyQualifiedTableName);
                    return fresh;
                }
                // The re-read did NOT produce the column. Either ClickHouse
                // owns it (ALIAS / MATERIALIZED -- getColumnsDataTypesForTable
                // excludes both, because binding either makes ClickHouse
                // reject the INSERT), or the replica simply does not have it.
                // Its default_kind tells the three cases apart, and they are
                // NOT equivalent (Spec 08.04 section 3.1).
                String kind = new DBMetadata(config).getColumnDefaultKind(
                        tableName, databaseName, unknown, connection);

                if ("ALIAS".equalsIgnoreCase(kind)) {
                    // Not stored at all: computed at query time from other
                    // columns, so there is no stored value that can disagree
                    // with the source and nothing to enforce. Record the proof
                    // so the metadata read is not repeated for EVERY record --
                    // without it the invalidateTable() above would make every
                    // cached writer rebuild on its next batch, producing an
                    // unbounded system.columns query storm for as long as the
                    // table keeps receiving traffic. This is the ONLY kind
                    // that is ever proven absent.
                    log.debug("Column '{}' is an ALIAS on {} and is not stored, so no stored "
                                    + "value can disagree with the source and there is nothing "
                                    + "to enforce. The record's value for it is ignored.",
                            unknown, fullyQualifiedTableName);
                    invalidation.markColumnProvenAbsent(fullyQualifiedTableName, unknown);
                    return fresh;
                }

                if ("MATERIALIZED".equalsIgnoreCase(kind)) {
                    // Stored, computed by ClickHouse: when the source also
                    // supplies the column the replica silently holds
                    // ClickHouse's derived value instead of the source's. That
                    // is a real divergence, and the ClickHouse definition is
                    // what is wrong -- so it is corrected here rather than
                    // merely reported.
                    if (enforceSourceColumnIsWritable(unknown, tableName,
                            databaseName, fullyQualifiedTableName, connection,
                            config)) {
                        // The column is writable now. Re-read so this batch
                        // binds the source value, and bump the version so
                        // every other cached writer picks up the corrected
                        // schema. Do NOT record a proven-absent entry: the
                        // column is no longer absent, and caching that would
                        // suppress the very write the enforcement just enabled.
                        Map<String, String> enforced = new DBMetadata(config)
                                .getColumnsDataTypesForTable(tableName, connection,
                                        databaseName);
                        if (enforced != null && containsColumn(enforced, unknown)) {
                            invalidation.invalidateTable(fullyQualifiedTableName);
                            return enforced;
                        }
                    }
                    // Enforcement could not be performed (the type or the
                    // expression could not be read, the DDL was rejected or
                    // had no effect) or the re-read still lacks the column.
                    // Reporting is the fallback when enforcement fails, but
                    // reporting AND continuing is not: building the INSERT
                    // from the stale map writes the row with ClickHouse's
                    // computed value in place of the source's and advances
                    // the offset past it -- the same silent divergence as a
                    // missing column, and marking it proven-absent would
                    // repeat that for every later record. Fail the batch
                    // instead, naming the remediation (Spec 08.04 section 3.3).
                    throw new MissingTargetColumnException(String.format(
                            "Column '%s' is carried by the source record but is MATERIALIZED "
                                    + "in ClickHouse table %s.%s, so the source value cannot be "
                                    + "stored, and the automatic conversion (ALTER TABLE `%s`.`%s` "
                                    + "MODIFY COLUMN `%s` <type> DEFAULT <expression>) did not "
                                    + "make it writable. Redefine the column as DEFAULT over the "
                                    + "same expression, or grant the connector ALTER TABLE on "
                                    + "this table. Writing the row without the source value would "
                                    + "silently diverge with row counts intact; failing the batch "
                                    + "instead.",
                            unknown, databaseName, tableName, databaseName, tableName, unknown));
                }

                // Neither ALIAS nor MATERIALIZED: the column does not exist in
                // the replica (no system.columns row, or a row the writable
                // map still lacks). A column that exists in the source but not
                // in ClickHouse means the ClickHouse side is incomplete, and
                // the fix is on the ClickHouse side -- never a log line only.
                // Omitting it from the INSERT writes the row with the source
                // value silently lost, row counts intact, and marking it
                // proven-absent repeats that for every later record. Neither
                // is acceptable: add the column when allowed, otherwise fail
                // the batch loudly (Spec 08.04 section 3.3).
                boolean evolve = config.getBoolean(
                        ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString());
                if (evolve) {
                    log.warn("Column '{}' carried by the record does not exist in {} "
                                    + "(default_kind {}). schema.evolution is enabled, so it is "
                                    + "being added with ALTER TABLE ... ADD COLUMN before this "
                                    + "batch is written.",
                            unknown, fullyQualifiedTableName,
                            kind == null ? "not found" : "'" + kind + "'");
                    try {
                        new ClickHouseAlterTable().alterTable(struct.schema().fields(),
                                tableName, connection, fresh, config);
                        Map<String, String> added = new DBMetadata(config)
                                .getColumnsDataTypesForTable(tableName, connection,
                                        databaseName);
                        if (added != null && containsColumn(added, unknown)) {
                            invalidation.invalidateTable(fullyQualifiedTableName);
                            return added;
                        }
                    } catch (Exception e) {
                        log.error("ALTER TABLE ... ADD COLUMN for '{}' on {} failed",
                                unknown, fullyQualifiedTableName, e);
                    }
                }
                throw new MissingTargetColumnException(String.format(
                        "Column '%s' is carried by the source record but does not exist in "
                                + "ClickHouse table %s.%s (default_kind %s). Writing the row without "
                                + "it would silently drop the source value with row counts intact. %s "
                                + "Failing the batch instead.",
                        unknown, databaseName, tableName,
                        kind == null ? "not found" : "'" + kind + "'",
                        evolve
                                ? "The automatic ALTER TABLE ... ADD COLUMN did not produce a "
                                        + "writable column; add it to the ClickHouse table."
                                : "Set " + ClickHouseSinkConnectorConfigVariables
                                        .ENABLE_SCHEMA_EVOLUTION + "=true to let the connector add "
                                        + "it, or add the column to the ClickHouse table."));
            }
            log.warn("Re-read of {}.{} returned no columns; keeping the cached map. The "
                            + "bind-time check will fail the batch if a value would be dropped.",
                    databaseName, tableName);
        } catch (MissingTargetColumnException e) {
            // Deliberately loud: this is the outcome, not a metadata failure.
            throw e;
        } catch (Exception e) {
            log.warn("Could not re-read metadata for {}.{}; keeping the cached map. The "
                            + "bind-time check will fail the batch if a value would be dropped.",
                    databaseName, tableName, e);
        }
        return null;
    }

    /**
     * Makes ClickHouse able to store a source column that its current
     * definition refuses, and reports when it cannot.
     *
     * <p>The connector replicates a source database into ClickHouse: the
     * source is the authority on what the data is, and this connector is what
     * ENFORCES that on the replica. So when the ClickHouse side is in a shape
     * that prevents the source value from being stored, the answer is to
     * change the ClickHouse side -- not to note the problem and move on.</p>
     *
     * <p>Called only for a column whose {@code default_kind} the caller has
     * already read as MATERIALIZED (an ALIAS is ignored by the caller, and a
     * column that does not exist is added or fails the batch there). The kind
     * is re-read here so the DDL is never issued against a definition that
     * changed in between.</p>
     *
     * <ul>
     *   <li><b>ALIAS</b> -- not stored at all. The column is computed at
     *       query time from other columns, so there is no stored value that
     *       can disagree with the source and nothing to enforce. Logged at
     *       debug; returns false.</li>
     *   <li><b>MATERIALIZED</b> -- stored, and computed by ClickHouse. The
     *       source sends a value and the replica keeps a DIFFERENT one,
     *       silently: no error, no failed batch, identical row counts, so
     *       only a value-level checksum would ever reveal it. The
     *       MATERIALIZED definition is what is wrong, so the column is
     *       converted to DEFAULT over the same expression with
     *       {@code ALTER TABLE ... MODIFY COLUMN <col> <type> DEFAULT
     *       <expr>}. The source value now lands as sent, and the replica
     *       still derives the column whenever the connector omits it.
     *       Returns true on success.</li>
     *   <li><b>unknown</b> -- the kind could not be read, so there is nothing
     *       safe to alter. Logged at warn rather than assumed benign;
     *       returns false.</li>
     * </ul>
     *
     * <p><b>Enforcement fixes the write path forward, not history.</b> Rows
     * written while the column was MATERIALIZED still hold ClickHouse's
     * computed values. Those are reconciled by a backfill of the affected
     * range, and the log says so explicitly so the remaining work is visible
     * rather than assumed done.</p>
     *
     * <p>The DDL is metadata-only: {@code MODIFY COLUMN} restating the same
     * type does not rewrite existing parts, so it neither blocks nor costs
     * I/O. It runs on the same path and privilege the connector already uses
     * for schema evolution ({@code ClickHouseAlterTable}).</p>
     *
     * <p>Converting to DEFAULT rather than to a bare column is what keeps
     * the change safe to apply automatically. A bare column would stop the
     * replica deriving the value at all, so any row the connector writes
     * without that column would store a type zero -- trading a divergence
     * for a data-loss path. DEFAULT preserves the derivation and merely
     * lets the source override it.</p>
     *
     * @param columnName              the source column that cannot be written.
     * @param tableName               the ClickHouse table name.
     * @param databaseName            the ClickHouse database name.
     * @param fullyQualifiedTableName "database.table", for log messages.
     * @param connection              connection used to read metadata and
     *                                issue the DDL.
     * @param config                  the connector configuration.
     * @return true when the column is now writable and the caller should
     *         re-read the schema; false when there was nothing to enforce or
     *         enforcement did not succeed.
     */
    private boolean enforceSourceColumnIsWritable(
            String columnName, String tableName, String databaseName,
            String fullyQualifiedTableName, Connection connection,
            ClickHouseSinkConnectorConfig config) {

        DBMetadata metadata = new DBMetadata(config);
        String kind = metadata.getColumnDefaultKind(
                tableName, databaseName, columnName, connection);

        if ("ALIAS".equalsIgnoreCase(kind)) {
            log.debug("Column '{}' is an ALIAS on {} and is not stored, so no stored "
                            + "value can disagree with the source and there is nothing "
                            + "to enforce. The record's value for it is ignored.",
                    columnName, fullyQualifiedTableName);
            return false;
        }

        if ("MATERIALIZED".equalsIgnoreCase(kind)) {
            String columnType = metadata.getColumnType(
                    tableName, databaseName, columnName, connection);
            if (columnType == null || columnType.isEmpty()) {
                log.warn("SOURCE VALUE SHADOWED: {} defines column '{}' as MATERIALIZED, "
                                + "so ClickHouse stores its own computed value and the "
                                + "source's value is never written -- but the column's "
                                + "declared type could not be read, so the definition "
                                + "cannot be corrected automatically. Redefine '{}' as "
                                + "DEFAULT over the same expression so the replicated "
                                + "value is stored.",
                        fullyQualifiedTableName, columnName, columnName);
                return false;
            }

            log.warn("SOURCE VALUE SHADOWED: {} defines column '{}' as MATERIALIZED, so "
                            + "ClickHouse has been storing its own computed value instead "
                            + "of the source's. The source is the authority for replicated "
                            + "data, so the ClickHouse definition is being corrected: "
                            + "converting '{}' ({}) from MATERIALIZED to DEFAULT over the "
                            + "same expression, so the replicated value is stored from now "
                            + "on and the column is still derived when the connector omits "
                            + "it.",
                    fullyQualifiedTableName, columnName, columnName, columnType);

            if (metadata.makeColumnWritable(tableName, databaseName, columnName,
                    columnType, connection)) {
                log.warn("ENFORCED: '{}' on {} now stores the source value. Rows written "
                                + "BEFORE this point still hold ClickHouse's computed "
                                + "values -- backfill the affected range to bring the "
                                + "existing data into agreement with the source.",
                        columnName, fullyQualifiedTableName);
                return true;
            }
            return false;
        }

        log.warn("Column '{}' on {} is no longer MATERIALIZED (default_kind now {}); nothing "
                        + "to convert. The caller decides how a missing column is handled.",
                columnName, fullyQualifiedTableName, kind == null ? "unknown" : kind);
        return false;
    }

    /**
     * Case-insensitive membership test against a column map's key set.
     *
     * <p>The cached map is compared case-insensitively everywhere else in this
     * class, so the post-re-read check must be too -- otherwise a column whose
     * ClickHouse casing differs from the source's would be judged still-missing
     * and marked proven-absent even though the re-read did resolve it.</p>
     *
     * @param columns    the column map to search.
     * @param columnName the column name to look for.
     * @return true when the map contains the column under any casing.
     */
    private boolean containsColumn(Map<String, String> columns, String columnName) {
        if (columns == null || columnName == null) {
            return false;
        }
        for (String column : columns.keySet()) {
            if (column != null && column.equalsIgnoreCase(columnName)) {
                return true;
            }
        }
        return false;
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
