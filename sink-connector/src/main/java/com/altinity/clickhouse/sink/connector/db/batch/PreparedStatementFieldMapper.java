package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.altinity.clickhouse.sink.connector.metadata.TableMetaDataWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.data.ClickHouseColumn;
import com.clickhouse.data.ClickHouseDataType;
import com.google.common.annotations.VisibleForTesting;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;

/**
 * Handles the mapping and insertion of ClickHouseStruct fields into PreparedStatements.
 * This class is responsible for:
 * - Mapping column names to PreparedStatement indices
 * - Converting data types
 * - Handling Kafka metadata columns
 * - Managing sign, version, and delete columns for various ClickHouse engines
 * - Handling replication history columns
 */
public class PreparedStatementFieldMapper {

    /**
     * Columns that the generated INSERT supplies as SQL literals rather than
     * as bind parameters, so they are legitimately absent from the
     * column-to-parameter-index map.
     *
     * <p>{@code QueryFormatter#getInsertQueryForUpdate} and
     * {@code #getInsertQueryForDelete} hardcode the bitemporal metadata
     * columns to guarantee their values, and record no index for them. Their
     * absence is by design; every other missing column is a real defect that
     * silently drops the value.</p>
     *
     * @param columnName the ClickHouse column being bound
     * @return true when the column is intentionally not a bind parameter
     */
    static boolean isUnboundByDesign(String columnName) {
        return VERSION_COLUMN.equalsIgnoreCase(columnName)
                || IS_DELETED_COLUMN.equalsIgnoreCase(columnName)
                || OPERATION_COLUMN.equalsIgnoreCase(columnName)
                || SIGN_COLUMN.equalsIgnoreCase(columnName);
    }

    /**
     * The connector's own replication-history columns
     * ({@code _valid_from}, {@code _valid_to}, {@code _operation}): never in the
     * source record, bound by {@code handleReplicationHistoryColumns} after the
     * data columns (Spec 12.03 section 3.1). The engine columns
     * ({@code _version}, {@code is_deleted}) are recognised by their configured
     * names, as in standard mode.
     *
     * @param columnName the ClickHouse column being bound
     * @return true when history mode populates the column itself
     */
    static boolean isReplicationHistoryColumn(String columnName) {
        return DELETED_FROM_TIME_COLUMN.equalsIgnoreCase(columnName)
                || DELETED_TIME_COLUMN.equalsIgnoreCase(columnName)
                || OPERATION_COLUMN.equalsIgnoreCase(columnName);
    }

    /**
     * Whether the incoming change event actually carries this column.
     *
     * <p>A column the record does not carry is intentionally absent from the
     * generated INSERT, so ClickHouse applies the column's DEFAULT. That is
     * the intended behaviour for a pre-ALTER record, and for a column the
     * source event omits because it is NULL. A column the record DOES carry
     * but that has no placeholder is a real defect: nothing binds it and the
     * value never reaches ClickHouse. Only the latter is an error -- reporting
     * both buries the one that loses data.</p>
     *
     * @param fields the record's schema fields, may be null
     * @param columnName the ClickHouse column being bound
     * @return true when the record carries a field of that name
     */
    static boolean recordCarries(List<Field> fields, String columnName) {
        if (fields == null || columnName == null) {
            return false;
        }
        for (Field f : fields) {
            if (f != null && columnName.equalsIgnoreCase(f.name())) {
                return true;
            }
        }
        return false;
    }


    /**
     * Logger instance for logging purposes.
     */
    private static final Logger log = LogManager.getLogger(PreparedStatementFieldMapper.class);

    /**
     * The column name used for "delete" operations in the ReplacingMergeTree engine.
     */
    private final String replacingMergeTreeDeleteColumn;

    /**
     * Flag indicating whether the ReplacingMergeTree engine uses an "isDeleted" column.
     */
    private final boolean replacingMergeTreeWithIsDeletedColumn;

    /**
     * The name of the column used for the sign of a record (CollapsingMergeTree).
     */
    private final String signColumn;

    /**
     * The name of the version column (ReplacingMergeTree).
     */
    private final String versionColumn;

    /**
     * The server's time zone used for converting timestamps.
     */
    private final ZoneId serverTimeZone;

    /**
     * The name of the database being used for the operations.
     */
    private final String databaseName;

    /**
     * Constructor for PreparedStatementFieldMapper.
     *
     * @param replacingMergeTreeDeleteColumn The column used for deletion in ReplacingMergeTree.
     * @param replacingMergeTreeWithIsDeletedColumn Whether to use the "is_deleted" column for deletion.
     * @param signColumn The sign column to mark updates and deletes.
     * @param versionColumn The version column for ReplacingMergeTree.
     * @param databaseName The name of the database.
     * @param serverTimeZone The time zone for the server.
     */
    public PreparedStatementFieldMapper(String replacingMergeTreeDeleteColumn,
                                       boolean replacingMergeTreeWithIsDeletedColumn,
                                       String signColumn,
                                       String versionColumn,
                                       String databaseName,
                                       ZoneId serverTimeZone) {
        this.replacingMergeTreeDeleteColumn = replacingMergeTreeDeleteColumn;
        this.replacingMergeTreeWithIsDeletedColumn = replacingMergeTreeWithIsDeletedColumn;
        this.signColumn = signColumn;
        this.versionColumn = versionColumn;
        this.databaseName = databaseName;
        this.serverTimeZone = serverTimeZone;
    }

    /**
     * Inserts the fields of a ClickHouseStruct into the prepared statement for execution.
     * This method maps the column names from the record to the corresponding prepared
     * statement indices and handles various data types, including Kafka metadata, sign,
     * and version columns. It also handles special operations like handling deletes in
     * ReplacingMergeTree engines.
     *
     * @param columnNameToIndexMap A map of column names to their respective index positions
     *                             in the prepared statement.
     * @param ps The prepared statement where the values will be set.
     * @param fields The list of fields from the Kafka record schema.
     * @param record The ClickHouse struct containing the actual data.
     * @param struct The Kafka struct representing the record data.
     * @param beforeSection Flag indicating whether the operation is before or after the change.
     * @param config The configuration for the ClickHouse Sink connector.
     * @param columnNameToDataTypeMap A map of column names to their corresponding data types.
     * @param engine The table engine being used (e.g., COLLAPSING_MERGE_TREE, REPLACING_MERGE_TREE).
     * @param tableName The name of the target ClickHouse table.
     * @throws Exception if an error occurs while setting values or executing the prepared statement.
     */
    public void insertPreparedStatement(Map<String, Integer> columnNameToIndexMap,
                                        PreparedStatement ps, List<Field> fields,
                                        ClickHouseStruct record, Struct struct, boolean beforeSection,
                                        ClickHouseSinkConnectorConfig config,
                                        Map<String, String> columnNameToDataTypeMap,
                                        DBMetadata.TABLE_ENGINE engine, String tableName) throws Exception {

        // Iterate through the column names and map the values to their indices in the prepared statement.
        for (Map.Entry<String, String> entry : columnNameToDataTypeMap.entrySet()) {
            String colName = entry.getKey();

            // Skip processing if the column name is null.
            if (colName == null) {
                continue;
            }

            // Log error if columnNameToIndexMap is null.
            if (columnNameToIndexMap == null) {
                log.error("Column Name to Index map error");
            }

            // Get the index position of the column in the prepared statement.
            int index = -1;
            if (columnNameToIndexMap.containsKey(colName)) {
                index = columnNameToIndexMap.get(colName);
            } else {
                // Not every column in the target table is a bind parameter.
                // In replication-history mode QueryFormatter deliberately
                // hardcodes the bitemporal metadata columns as SQL literals
                // and omits them from the index map, so their absence is
                // expected and must not be reported as an error -- on a busy
                // history-mode connector that logged tens of thousands of
                // spurious ERROR lines and buried the real ones.
                if (isUnboundByDesign(colName)) {
                    log.debug("Column {} is emitted as a SQL literal; no parameter binding required.", colName);
                } else if (!recordCarries(fields, colName)) {
                    // The record does not carry this column, so createColumns
                    // deliberately left it out of the INSERT and ClickHouse
                    // applies the column's DEFAULT. Intended for a pre-ALTER
                    // record, or a column the source event omits because it is
                    // NULL -- not a dropped value.
                    log.debug("Column {} absent from this record's schema; ClickHouse DEFAULT applies.", colName);
                } else {
                    // A genuine data column with no placeholder is dropped from
                    // the INSERT: nothing binds it here and the handlers below
                    // are guarded by the same map, so the value never reaches
                    // ClickHouse.
                    //
                    // This is UNCONDITIONALLY a correctness failure and must
                    // never be survivable. Logging and continuing is what let
                    // 65,577 of these writes land in production over two days
                    // with full row counts and no failed batch -- the daily
                    // value-level checksum was the only thing that noticed.
                    //
                    // The condition means the index map was built from a
                    // different view of the table than the column map the
                    // binder is walking now, i.e. the cached schema is stale
                    // with respect to the source metadata. Failing the batch
                    // turns a silent divergence into a retry against freshly
                    // read metadata, which is the only outcome that preserves
                    // the data.
                    throw new StaleSchemaCacheException(String.format(
                            "Column %s is present in the ClickHouse table and carried by the "
                                    + "record, but has no placeholder in the generated INSERT. "
                                    + "The cached schema is stale relative to the source "
                                    + "metadata, so this column's value would be silently "
                                    + "dropped. Failing the batch instead. Database(%s), Table(%s)",
                            colName, databaseName, tableName));
                }
                continue;
            }

            //String colName = entry.getKey();

            //ToDO: Setting null to a non-nullable field)
            // will throw an error.
            // If the Received column is not a clickhouse column
            try {
                // Read the STORED value, never the Connect-schema default.
                // Struct.get() returns schema.defaultValue() for a null field,
                // and Debezium propagates the MySQL column DEFAULT into that
                // schema, so a source NULL in any column with a MySQL default
                // would be bound as the default ('new', 0, 1970-01-01) -- with
                // matching row counts. This used to be gated behind
                // non.default.value=true, whose default was false; the source
                // value is the only value there is to bind (Spec 07.07).
                //
                // The field is resolved against the record's schema, exact
                // name first and then case-insensitively, because membership
                // was decided case-insensitively: reading the ClickHouse name
                // verbatim (case-sensitive in Kafka Connect) threw for a table
                // hand-created as `ID` for source column `id`, and the batch
                // stalled forever on a "stale cache" that was never stale
                // (Spec 04.03 section 3.4). A name that matches no field under
                // either comparison still throws DataException below.
                Field sourceField = resolveSourceField(struct, colName);
                Object value = struct.getWithoutDefault(sourceField == null ? colName : sourceField.name());
                if (value == null) {
                    ps.setNull(index, Types.OTHER);
                    continue;
                }
            } catch (DataException e) {
                // Struct.get throws a DataException when the field is not
                // present in the record's schema.
                //
                // Reaching here for a genuine data column is the SAME
                // cache-staleness condition as the missing-index branch above,
                // arriving from the opposite direction: there, the index map
                // was behind the column map; here, the record is behind the
                // column map. Both mean the cached schema and the source
                // metadata disagree.
                //
                // Binding NULL is the dangerous response. The column exists in
                // the ClickHouse table and already holds a value for this row
                // on an UPDATE, so writing NULL over it destroys real data --
                // again with matching row counts and a successful batch. This
                // is the RENAME half of the NULL-fill defect noted as a known
                // limitation in #1389.
                //
                // Connector-managed columns legitimately never appear in the
                // source record and keep the previous behaviour.
                if (colName.equalsIgnoreCase(versionColumn) || colName.equalsIgnoreCase(signColumn) ||
                        colName.equalsIgnoreCase(replacingMergeTreeDeleteColumn)) {
                    // Ignore version and sign columns
                    ps.setNull(index, Types.OTHER);
                    continue;
                }
                if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())
                        && isReplicationHistoryColumn(colName)) {
                    // History mode carries its own bitemporal metadata columns
                    // (_valid_from, _valid_to, _operation) that are absent from
                    // the source record by design; handleReplicationHistoryColumns
                    // binds them below. ONLY those: exempting every unknown column
                    // in history mode disabled the stale-schema-cache defence of
                    // Spec 08.03 for SCD2 tables, so a renamed or added source
                    // column was NULL-filled with a successful batch
                    // (Spec 12.03 section 3.2, Gap G-12.03-5).
                    ps.setNull(index, Types.OTHER);
                    continue;
                }
                throw new StaleSchemaCacheException(String.format(
                        "Column %s is present in the ClickHouse table but absent from the "
                                + "record's schema, and the INSERT reserved a placeholder for "
                                + "it. Binding NULL here would overwrite the stored value with "
                                + "NULL. The cached schema is stale relative to the source "
                                + "metadata. Failing the batch instead. Database(%s), Table(%s)",
                        colName, databaseName, tableName));
            }

            // If the column is not in the column data type map, log an error.
            if (!columnNameToDataTypeMap.containsKey(colName)) {
                log.error(" ***** ERROR: Column:{} not found in ClickHouse", colName);
                continue;
            }

            // Get the field information for the column and handle its data type.
            // Non-null here: a null resolution threw DataException above.
            Field f = resolveSourceField(struct, colName);
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();
            // Same rule as above: the stored value, not the schema default.
            Object value = struct.getWithoutDefault(f.name());
            if (type == Schema.Type.ARRAY) {
                // Check if the ClickHouse column is a non-Array type (e.g. String/Nullable(String)).
                // PG text[] columns may be auto-created as Nullable(String) in ClickHouse,
                // so we serialize the array as a JSON string instead of using setArray().
                String chColumnType = columnNameToDataTypeMap.get(colName);
                if (chColumnType != null && !chColumnType.startsWith("Array")) {
                    if (value == null) {
                        ps.setNull(index, Types.VARCHAR);
                    } else {
                        ps.setString(index, value.toString());
                    }
                    continue;
                }
                schemaName = f.schema().valueSchema().type().name();
            }
            // This will throw an exception, unknown data type.
            ClickHouseColumn column = parseColumn(colName, columnNameToDataTypeMap);
            ClickHouseDataType chDataType = column == null ? null : column.getDataType();
            // ClickHouse parses a DateTime literal in the COLUMN's declared
            // zone, so instants must be rendered in it (Spec 07.03 section 3.1.3).
            ZoneId columnTimeZone = ClickHouseDataTypeMapper.columnTimeZoneOf(column);
            // A value outside the ClickHouse type's range fails the batch unless
            // clamp.out.of.range=true; either way the column is named (Spec
            // 07.03 section 3.3).
            DebeziumConverter.RangePolicy rangePolicy = DebeziumConverter.RangePolicy.of(config,
                    databaseName + "." + tableName + "." + colName);
            if (!ClickHouseDataTypeMapper.convert(type, schemaName, value, index, ps, config, chDataType,
                    serverTimeZone, columnTimeZone, rangePolicy)) {
                // An unhandled type leaves the parameter unbound. Logging and
                // continuing (the previous behaviour) let the V2 JDBC driver
                // -- whose addBatch() does not clear its bound values -- write
                // the PREVIOUS row's value at this index for every row after
                // the first, silently (Spec 07.07 section 3.2.2).
                throw new DataException(String.format(
                        "No ClickHouse binding for type(%s), name(%s) of column %s in Database(%s), Table(%s); "
                                + "the parameter would be left unbound. Failing the batch instead.",
                        type, schemaName, colName, databaseName, tableName));
            }
        }

        // Handle Kafka metadata columns if configured to store Kafka metadata.
        handleKafkaMetadata(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap);

        // Handle Sign column for COLLAPSING_MERGE_TREE engine.
        handleSignColumn(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, engine, beforeSection);

        // Handle replication history columns
        handleReplicationHistoryColumns(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, beforeSection);

        // Handle Version column for REPLACING_MERGE_TREE engines.
        handleVersionColumn(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, engine);

        // Handle Sign column to mark deletes in ReplacingMergeTree.
        handleReplacingMergeTreeDeleteColumn(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, engine, tableName, beforeSection);

        // Store raw data in JSON form if configured.
        handleRawDataStorage(columnNameToIndexMap, ps, struct, config, columnNameToDataTypeMap);
    }

    /**
     * Writes the row as an explicit ReplacingMergeTree tombstone: the supplied
     * (before-image) values with the delete marker set.
     *
     * <p>Used for an UPDATE that relocates a row to a different sorting key.
     * The plain before-image write cannot be reused for this, because its
     * delete marker is driven by the record's CDC operation -- an UPDATE record
     * yields {@code is_deleted = 0}, which would insert a second LIVE row at the
     * old key instead of retiring it.</p>
     *
     * <p>The tombstone carries the record's own version {@code V}, the same
     * version the after-image is written with (Spec 05.02). The two rows never
     * share a sorting key, so they never compete. What the tombstone must beat
     * is the live row already stored at the OLD key -- and under GTID
     * versioning that row can carry the very same {@code V}: an
     * {@code INSERT (k='a')} followed in the same transaction by
     * {@code UPDATE ... SET k='b'} gives both events one version. A tombstone
     * at {@code V - 1} is then OLDER than the live row, loses the
     * ReplacingMergeTree merge, and leaves a ghost row at {@code 'a'} next to
     * the new row at {@code 'b'}. At {@code V} it ties, and ClickHouse resolves
     * an equal-version tie to the later-inserted row, which the tombstone is.</p>
     *
     * @param columnNameToIndexMap A map of column names to prepared-statement indices.
     * @param ps The prepared statement to populate.
     * @param fields The before-image fields.
     * @param record The CDC record.
     * @param struct The before-image struct.
     * @param config The connector configuration.
     * @param columnNameToDataTypeMap A map of column names to data types.
     * @param engine The target table engine.
     * @param tableName The target table name.
     * @throws Exception if the values cannot be set.
     */
    public void insertTombstonePreparedStatement(Map<String, Integer> columnNameToIndexMap,
                                                 PreparedStatement ps, List<Field> fields,
                                                 ClickHouseStruct record, Struct struct,
                                                 ClickHouseSinkConnectorConfig config,
                                                 Map<String, String> columnNameToDataTypeMap,
                                                 DBMetadata.TABLE_ENGINE engine, String tableName) throws Exception {

        insertPreparedStatement(columnNameToIndexMap, ps, fields, record, struct, true, config,
                columnNameToDataTypeMap, engine, tableName);

        // A tombstone IS a delete marker: a table that cannot carry one would
        // get the before image back as a LIVE row at the old key (Spec 08.01
        // section 3.2).
        requireDeleteColumn(config, columnNameToDataTypeMap, engine, tableName,
                "The tombstone of an UPDATE that moves the row to another sorting key");

        // Force the delete marker on. insertPreparedStatement() derived it from
        // the CDC operation (UPDATE => not deleted); this row is a tombstone.
        if (this.replacingMergeTreeDeleteColumn != null
                && columnNameToDataTypeMap.containsKey(this.replacingMergeTreeDeleteColumn)
                && columnNameToIndexMap.containsKey(this.replacingMergeTreeDeleteColumn)
                && !config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())) {
            int deleteColumnIndex = columnNameToIndexMap.get(this.replacingMergeTreeDeleteColumn);
            ps.setInt(deleteColumnIndex, this.replacingMergeTreeWithIsDeletedColumn ? 1 : -1);
        }

        // Bind the record's own version, unchanged. Decrementing it made the
        // tombstone lose to a same-transaction (same-version) live row at the
        // old key; see the class comment above and Spec 05.02 section 3.2.
        if (this.versionColumn != null
                && columnNameToDataTypeMap.containsKey(this.versionColumn)
                && columnNameToIndexMap.containsKey(this.versionColumn)) {
            if (record.getVersion() == -1) {
                record.calculateVersion(config.getBoolean(
                        ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString()));
            }
            rejectUnderivableVersion(record);
            ps.setLong(columnNameToIndexMap.get(this.versionColumn), record.getVersion());
        }
    }

    /**
     * Handles Kafka metadata columns.
     */
    private void handleKafkaMetadata(Map<String, Integer> columnNameToIndexMap,
                                     PreparedStatement ps,
                                     ClickHouseStruct record,
                                     ClickHouseSinkConnectorConfig config,
                                     Map<String, String> columnNameToDataTypeMap) throws Exception {
        for (KafkaMetaData metaDataColumn : KafkaMetaData.values()) {
            String metaDataColName = metaDataColumn.getColumn();
            if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString())) {
                if (columnNameToDataTypeMap.containsKey(metaDataColName)) {
                    if (columnNameToIndexMap != null && columnNameToIndexMap.containsKey(metaDataColName)) {
                        TableMetaDataWriter.addKafkaMetaData(metaDataColName, record, columnNameToIndexMap.get(metaDataColName), ps);
                    }
                }
            }
        }
    }

    /**
     * Handles Sign column for COLLAPSING_MERGE_TREE engine.
     *
     * <p>The engine test compares the enum CONSTANT, not the engine string.
     * This previously read
     * {@code engine.getEngine() == TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine()}
     * -- reference equality on a String. It happens to hold today only because
     * both sides resolve to the same interned literal from the enum, so the
     * moment the engine is carried as a runtime-built String (a value read from
     * a JDBC ResultSet is the obvious candidate, and DBMetadata already derives
     * the engine from {@code SHOW CREATE TABLE}) the comparison silently
     * becomes false and the sign column is never bound -- writing the engine's
     * default sign for every row, so no +1/-1 pair ever collapses.</p>
     *
     * <p>Comparing the enum constant cannot degrade that way, and it matches
     * what {@code PreparedStatementExecutor} already does for the same engine
     * (it uses {@code equalsIgnoreCase}). Behaviour is unchanged today; this
     * removes a latent trap rather than fixing a live miss.</p>
     */
    private void handleSignColumn(Map<String, Integer> columnNameToIndexMap,
                                   PreparedStatement ps,
                                   ClickHouseStruct record,
                                   ClickHouseSinkConnectorConfig config,
                                   Map<String, String> columnNameToDataTypeMap,
                                   DBMetadata.TABLE_ENGINE engine,
                                   boolean beforeSection) throws Exception {
        if (engine == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE && signColumn != null) {
            requireEngineColumnPlaceholder(columnNameToIndexMap, config, columnNameToDataTypeMap,
                    signColumn, "sign", "CollapsingMergeTree", "0, so no +1/-1 pair ever collapses");
            if (columnNameToDataTypeMap.containsKey(signColumn) && columnNameToIndexMap.containsKey(signColumn)) {
                int signColumnIndex = columnNameToIndexMap.get(signColumn);
                if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    ps.setInt(signColumnIndex, -1);
                } else if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
                    if (beforeSection) {
                        ps.setInt(signColumnIndex, -1);
                    } else {
                        ps.setInt(signColumnIndex, 1);
                    }
                } else {
                    ps.setInt(signColumnIndex, 1);
                }
            }
        }
    }

    /**
     * Handles replication history columns (deleted_time, deleted_from_time, operation).
     */
    private void handleReplicationHistoryColumns(Map<String, Integer> columnNameToIndexMap,
                                                  PreparedStatement ps,
                                                  ClickHouseStruct record,
                                                  ClickHouseSinkConnectorConfig config,
                                                  Map<String, String> columnNameToDataTypeMap,
                                                  boolean beforeSection) throws Exception {
        if (!config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            return;
        }

        String sourceTimeZone = "UTC";
        if(config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString()) != null){
            String configSourceTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString());
            if(configSourceTimeZone != null && !configSourceTimeZone.isEmpty()) {
                sourceTimeZone = configSourceTimeZone;
            }
        }

        // Handle deleted_time column
        if (columnNameToDataTypeMap.containsKey(DELETED_TIME_COLUMN) && columnNameToIndexMap.containsKey(DELETED_TIME_COLUMN)) {
            if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                ps.setString(columnNameToIndexMap.get(DELETED_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(record.getTsSec() * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));
            } else if(record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
                ps.setString(columnNameToIndexMap.get(DELETED_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(DataTypeRange.DATETIME32_MAX_TTL * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));

            } else {
                ps.setString(columnNameToIndexMap.get(DELETED_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(DataTypeRange.DATETIME32_MAX_TTL * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));
            }
        }

        // Handle deleted_from_time column
        if (columnNameToDataTypeMap.containsKey(DELETED_FROM_TIME_COLUMN) && columnNameToIndexMap.containsKey(DELETED_FROM_TIME_COLUMN)) {
            if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation()) ||
                    record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
                ps.setString(columnNameToIndexMap.get(DELETED_FROM_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(record.getTsSec() * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));
            } else {
                ps.setString(columnNameToIndexMap.get(DELETED_FROM_TIME_COLUMN),
                        DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(record.getTsSec() * 1000, ClickHouseDataType.DateTime,
                                ZoneId.of(sourceTimeZone), serverTimeZone));
            }
        }

        // Handle operation column
        if(columnNameToDataTypeMap.containsKey(OPERATION_COLUMN) && columnNameToIndexMap.containsKey(OPERATION_COLUMN)) {
            ps.setString(columnNameToIndexMap.get(OPERATION_COLUMN), record.getCdcOperation().getOperation());
        }
    }

    /**
     * Refuses to bind a version that could not be derived (issue #1213).
     *
     * <p>{@code ClickHouseStruct.version} starts at the {@code -1} uninitialized
     * sentinel. Binding it with {@code setLong} into the {@code UInt64}
     * {@code _version} column stores 18446744073709551615 -- the maximum UInt64.
     * Under ReplacingMergeTree that row then wins every future deduplication
     * permanently, so every later UPDATE and DELETE for the same key is silently
     * discarded on merge: unbounded, undetectable data loss. Failing the batch is
     * strictly preferable, since the batch is retried or surfaced to the operator
     * whereas the corrupt row is not recoverable once merged.</p>
     *
     * <p>After {@code calculateVersion()} the sentinel is only reachable when the
     * record carries no ordering key AND no source commit timestamp, which
     * indicates a malformed or unsupported change event rather than a normal
     * GTID-less source.</p>
     *
     * <p>{@code 0} is rejected as well (Spec 02.05 section 3.2). No branch of
     * {@code calculateVersion()} produces it from a real source coordinate --
     * GTID transaction numbers start at 1, the SnowFlakeId forms embed a
     * positive timestamp, a PostgreSQL LSN of 0 is invalid and the lightweight
     * sequence counter starts far above 0 -- so a zero is a corrupt or
     * hand-built record whose ordering against its own history is undefined.
     * It is refused for the same reason as the sentinel: fail loudly rather
     * than write an unordered row.</p>
     *
     * <p>Package-private: {@code ReplicationHistoryHandler} applies the same rule
     * to the version its SCD2 statements embed (Spec 12.03 section 3.5).</p>
     *
     * @param record The CDC record whose version is about to be bound.
     */
    static void rejectUnderivableVersion(ClickHouseStruct record) {
        if (record.getVersion() <= 0) {
            throw new IllegalStateException(
                    "Cannot bind _version " + record.getVersion() + " for record from topic '"
                            + record.getTopic() + "' at kafka offset " + record.getKafkaOffset()
                            + ": a version must be a positive number derived from the GTID, "
                            + "sequence number, LSN or source timestamp. The uninitialized "
                            + "sentinel -1 is stored as UInt64 18446744073709551615 and would win "
                            + "every ReplacingMergeTree deduplication for this key permanently, "
                            + "silently discarding all later updates and deletes; 0 is not "
                            + "producible by any source coordinate. Refusing to write the row.");
        }
    }

    /**
     * Handles Version column for REPLACING_MERGE_TREE engines.
     * Uses the version already calculated and stored in the ClickHouseStruct.
     */
    private void handleVersionColumn(Map<String, Integer> columnNameToIndexMap,
                                      PreparedStatement ps,
                                      ClickHouseStruct record,
                                      ClickHouseSinkConnectorConfig config,
                                      Map<String, String> columnNameToDataTypeMap,
                                      DBMetadata.TABLE_ENGINE engine) throws Exception {
        if (engine != null &&
                (engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine() ||
                        engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine())
                && versionColumn != null) {
            requireEngineColumnPlaceholder(columnNameToIndexMap, config, columnNameToDataTypeMap,
                    versionColumn, "version", "ReplacingMergeTree",
                    "0, so a redelivered older row wins every merge");
            if (columnNameToDataTypeMap.containsKey(versionColumn)) {
                if (columnNameToIndexMap.containsKey(versionColumn)) {
                    // Calculate version if not already set
                    if (record.getVersion() == -1) {
                        boolean useSnowflakeId = config.getBoolean(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString());
                        record.calculateVersion(useSnowflakeId);
                    }
                    rejectUnderivableVersion(record);
                    if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                        // An SCD2 table carries ONE version domain for every row it
                        // holds, whichever statement or release wrote it: the
                        // snowflake encoding of the record's ordering key (Spec 12.03
                        // section 3.5.1). Binding the raw sequence number here while
                        // the UPDATE/DELETE rows of earlier releases sit at 2.1e18
                        // froze every previously updated key at the upgrade.
                        ps.setLong(columnNameToIndexMap.get(versionColumn),
                                ReplicationHistoryHandler.historyVersion(record));
                        return;
                    }
                    ps.setLong(columnNameToIndexMap.get(versionColumn), record.getVersion());
                }
            }
        }
    }


    /**
     * A delete marker for a ReplacingMergeTree target that has no delete
     * column cannot be replicated: the only way a DELETE (or the tombstone of
     * an UPDATE that moves a row to another sorting key) reaches a
     * ReplacingMergeTree is a row with the delete marker set, and without the
     * column that row would be inserted as a LIVE row with a higher version
     * and resurrect the key (Spec 08.01 §3.2). INSERTs and UPDATEs to such a
     * table replicate correctly, so the table is not refused up front; the row
     * that needs the marker is, here, loudly. Replication-history mode retires
     * rows through its own SCD Type 2 statement and is exempt, like
     * {@link #requireEngineColumnPlaceholder}.
     *
     * @param what the row being refused, for the message ("A DELETE", ...).
     */
    @VisibleForTesting
    void requireDeleteColumn(ClickHouseSinkConnectorConfig config,
                             Map<String, String> columnNameToDataTypeMap,
                             DBMetadata.TABLE_ENGINE engine,
                             String tableName,
                             String what) {
        if (engine != DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE
                && engine != DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE) {
            return;
        }
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())
                || config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            return;
        }
        if (this.replacingMergeTreeDeleteColumn != null
                && columnNameToDataTypeMap.containsKey(this.replacingMergeTreeDeleteColumn)) {
            return;
        }
        throw new IllegalStateException(String.format(
                "%s for ReplacingMergeTree table %s.%s cannot be replicated: the table has no "
                        + "delete column '%s' (table columns: %s). Written as is, the before image "
                        + "would become a LIVE row with a higher version and resurrect the key. "
                        + "Declare the table as ReplacingMergeTree(<version>, <delete column>) or "
                        + "point replacingmergetree.delete.column at an existing column, or set "
                        + "ignore_delete=true if deletes must not be replicated. Refusing the row.",
                what, databaseName, tableName, this.replacingMergeTreeDeleteColumn,
                columnNameToDataTypeMap.keySet()));
    }

    private void requireDeleteColumnForDelete(ClickHouseStruct record,
                                              ClickHouseSinkConnectorConfig config,
                                              Map<String, String> columnNameToDataTypeMap,
                                              DBMetadata.TABLE_ENGINE engine,
                                              String tableName) {
        if (record.getCdcOperation() == null || !record.getCdcOperation().getOperation()
                .equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
            return;
        }
        requireDeleteColumn(config, columnNameToDataTypeMap, engine, tableName, "A DELETE");
    }

    /**
     * Handles delete column for ReplacingMergeTree.
     */
    private void handleReplacingMergeTreeDeleteColumn(Map<String, Integer> columnNameToIndexMap,
                                                       PreparedStatement ps,
                                                       ClickHouseStruct record,
                                                       ClickHouseSinkConnectorConfig config,
                                                       Map<String, String> columnNameToDataTypeMap,
                                                       DBMetadata.TABLE_ENGINE engine,
                                                       String tableName,
                                                       boolean beforeSection) throws Exception {
        requireDeleteColumnForDelete(record, config, columnNameToDataTypeMap, engine, tableName);
        if (this.replacingMergeTreeDeleteColumn != null && columnNameToDataTypeMap.containsKey(replacingMergeTreeDeleteColumn)) {
            if (!config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())) {
                requireEngineColumnPlaceholder(columnNameToIndexMap, config, columnNameToDataTypeMap,
                        replacingMergeTreeDeleteColumn, "delete", "ReplacingMergeTree",
                        "its default, so a DELETE inserts a LIVE row and the row is resurrected");
            }
            if (columnNameToIndexMap.containsKey(replacingMergeTreeDeleteColumn) &&
                    !config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())) {
                if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    // if after section and REPLICATION HISTORY ENABLE is set to true in config
                    if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                        if(!beforeSection){
                            if (replacingMergeTreeWithIsDeletedColumn)
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                            else
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), -1);
                        } else {
                            // before section.
                            if (replacingMergeTreeWithIsDeletedColumn)
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 0);
                            else
                                ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                        }
                    } else {
                        if (replacingMergeTreeWithIsDeletedColumn)
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                        else
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), -1);
                    }
                } else {
                    if (replacingMergeTreeWithIsDeletedColumn)
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 0);
                    else
                        ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                }
            }
        }
    }

    /**
     * Refuses to write a row whose engine column exists in the table but has
     * no placeholder in the generated INSERT.
     *
     * <p>Every engine column the table declares (version, sign, delete) is
     * populated by the connector, never by the source, so it can only reach
     * ClickHouse through a bind parameter. When the column is in the table
     * but not in the parameter map, nothing binds it and ClickHouse stores
     * the type default -- a silent, per-row corruption of the very column
     * that decides which row survives a merge. That used to be skipped at
     * DEBUG. It is the same class of defect as a dropped data column
     * ({@code StaleSchemaCacheException} above) and is refused the same way
     * (Spec 04.02 §3.1, 05.04 §3).</p>
     *
     * <p>Not applied in replication-history mode: there
     * {@code QueryFormatter.getInsertQueryForUpdate} deliberately emits the
     * engine columns as SQL literals and records no index for them
     * ({@link #isUnboundByDesign}).</p>
     */
    private void requireEngineColumnPlaceholder(Map<String, Integer> columnNameToIndexMap,
                                                ClickHouseSinkConnectorConfig config,
                                                Map<String, String> columnNameToDataTypeMap,
                                                String column, String role, String engineName,
                                                String consequence) {
        if (column == null || !columnNameToDataTypeMap.containsKey(column)
                || columnNameToIndexMap.containsKey(column)) {
            return;
        }
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            return;
        }
        throw new IllegalStateException(String.format(
                "The %s %s column '%s' exists in the ClickHouse table but the generated INSERT has "
                        + "no placeholder for it, so nothing would bind it and ClickHouse would store "
                        + "%s. The engine column names resolved from the table must reach query "
                        + "construction (Spec 04.02 section 3.1). Refusing to write the row. "
                        + "Database(%s)",
                engineName, role, column, consequence, databaseName));
    }

    /**
     * Handles raw data storage if configured.
     */
    private void handleRawDataStorage(Map<String, Integer> columnNameToIndexMap,
                                       PreparedStatement ps,
                                       Struct struct,
                                       ClickHouseSinkConnectorConfig config,
                                       Map<String, String> columnNameToDataTypeMap) throws Exception {
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString())) {
            String userProvidedColName = config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString());
            String rawDataColumnDataType = columnNameToDataTypeMap.get(userProvidedColName);
            if (columnNameToDataTypeMap.containsKey(userProvidedColName) && rawDataColumnDataType.contains("String")) {
                if (columnNameToIndexMap.containsKey(userProvidedColName)) {
                    TableMetaDataWriter.addRawData(struct, columnNameToIndexMap.get(userProvidedColName), ps);
                }
            }
        }
    }

    /**
     * Resolves the record field a ClickHouse column is bound from: an exact
     * name match against the record's schema first, then a case-insensitive
     * one. The schema (not the modified-field list, which omits NULL-valued
     * fields) is consulted so a NULL source value in a case-mismatched column
     * is still bound as NULL (Spec 04.03 section 3.4).
     *
     * @param struct  the record image being bound
     * @param colName the ClickHouse column name
     * @return the matching field, or null when no field matches under either comparison
     */
    static Field resolveSourceField(Struct struct, String colName) {
        Field exact = struct.schema().field(colName);
        if (exact != null) {
            return exact;
        }
        for (Field f : struct.schema().fields()) {
            if (f.name().equalsIgnoreCase(colName)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Retrieves the ClickHouse data type for a given column by looking up the
     * column's data type in the provided map.
     *
     * @param columnName The name of the column.
     * @param columnNameToDataTypeMap A map that contains column names as keys
     *                                and their corresponding data types as values.
     * @return The ClickHouse data type for the column, or null if the type is unknown.
     */
    public ClickHouseDataType getClickHouseDataType(String columnName,
                                                    Map<String, String> columnNameToDataTypeMap) {
        ClickHouseColumn column = parseColumn(columnName, columnNameToDataTypeMap);
        return column == null ? null : column.getDataType();
    }

    /**
     * Parses the column's declared ClickHouse type from the map into a
     * {@link ClickHouseColumn}, which carries both the data type and the
     * declared time zone.
     *
     * @param columnName The name of the column.
     * @param columnNameToDataTypeMap A map of column names to declared types.
     * @return The parsed column, or null if the type is unknown or unparseable.
     */
    private ClickHouseColumn parseColumn(String columnName,
                                         Map<String, String> columnNameToDataTypeMap) {
        try {
            // Retrieve the column data type from the map
            String columnDataType = columnNameToDataTypeMap.get(columnName);
            // Create a ClickHouse column object based on the column name and type
            return ClickHouseColumn.of(columnName, columnDataType);
        } catch (Exception e) {
            // Log any error related to unknown data types
            log.debug("Unknown data type for column: " + columnName, e);
            return null;
        }
    }
}

