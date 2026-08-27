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
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.PreparedStatement;
import java.sql.SQLException;
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
     * Whether a ClickHouse column declaration can store SQL NULL.
     *
     * <p>ClickHouse has no per-column NULL flag: nullability is part of the
     * type, and {@code Nullable(T)} is the only form that stores NULL. The
     * type strings compared here are the verbatim
     * {@code system.columns.type} values that
     * {@code DBMetadata#getColumnsDataTypesForTable} puts into
     * {@code columnNameToDataTypeMap}, so this is a read of the target
     * table's real schema, not a guess.</p>
     *
     * <p>Two shapes need care:</p>
     * <ul>
     *   <li>{@code LowCardinality(...)} is a storage wrapper, not a type. The
     *       nullability is the one inside it, so it is unwrapped:
     *       {@code LowCardinality(Nullable(String))} accepts NULL,
     *       {@code LowCardinality(String)} does not.</li>
     *   <li>{@code Array(Nullable(String))} does NOT accept NULL. The array
     *       itself is required; only its elements may be NULL. Matching
     *       "contains Nullable" rather than "starts with Nullable" would
     *       wrongly wave it through.</li>
     * </ul>
     *
     * <p>An unknown (null/blank) type returns true: an undeterminable type is
     * not proof that the column rejects NULL, and a guess must never fail a
     * batch that works today.</p>
     *
     * @param clickHouseType the verbatim ClickHouse type string, may be null
     * @return true when the column can store NULL
     */
    static boolean acceptsNull(String clickHouseType) {
        if (clickHouseType == null) {
            return true;
        }
        String type = clickHouseType.trim();
        while (type.regionMatches(true, 0, LOW_CARDINALITY_PREFIX, 0, LOW_CARDINALITY_PREFIX.length())
                && type.endsWith(")")) {
            type = type.substring(LOW_CARDINALITY_PREFIX.length(), type.length() - 1).trim();
        }
        if (type.isEmpty()) {
            return true;
        }
        return type.regionMatches(true, 0, NULLABLE_PREFIX, 0, NULLABLE_PREFIX.length());
    }

    /** {@code LowCardinality(} -- a storage wrapper around the real type. */
    private static final String LOW_CARDINALITY_PREFIX = "LowCardinality(";

    /** {@code Nullable(} -- the only ClickHouse type that stores NULL. */
    private static final String NULLABLE_PREFIX = "Nullable(";

    /**
     * Whether this column is filled in by the connector itself later in
     * {@code insertPreparedStatement}, rather than by the source record.
     *
     * <p>These columns are deliberately bound to NULL first and overwritten by
     * {@code handleKafkaMetadata} / {@code handleSignColumn} /
     * {@code handleVersionColumn} / {@code handleReplicationHistoryColumns} /
     * {@code handleReplacingMergeTreeDeleteColumn} / {@code handleRawDataStorage}
     * a few lines further down. A placeholder NULL for one of them is not a
     * source NULL landing in a non-nullable column, so rejecting it would break
     * working deployments -- every one of these columns is non-nullable in the
     * schemas the connector generates.</p>
     *
     * @param colName the ClickHouse column being bound
     * @param config the connector configuration, used for the raw-data column name
     * @return true when the connector populates this column itself
     */
    private boolean isConnectorPopulatedColumn(String colName, ClickHouseSinkConnectorConfig config) {
        if (colName == null) {
            return false;
        }
        if (colName.equalsIgnoreCase(versionColumn)
                || colName.equalsIgnoreCase(signColumn)
                || colName.equalsIgnoreCase(replacingMergeTreeDeleteColumn)
                || colName.equalsIgnoreCase(DELETED_TIME_COLUMN)
                || colName.equalsIgnoreCase(DELETED_FROM_TIME_COLUMN)
                || colName.equalsIgnoreCase(OPERATION_COLUMN)) {
            return true;
        }
        for (KafkaMetaData metaDataColumn : KafkaMetaData.values()) {
            if (colName.equalsIgnoreCase(metaDataColumn.getColumn())) {
                return true;
            }
        }
        if (config == null) {
            return false;
        }
        String rawDataColumn = config.getString(
                ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString());
        return rawDataColumn != null && colName.equalsIgnoreCase(rawDataColumn);
    }

    /**
     * Binds SQL NULL, refusing when the target ClickHouse column cannot hold it
     * (issue #1250).
     *
     * <p>{@code setNull} on a non-nullable column has no good outcome. The
     * driver either rejects the whole batch with a message that names neither
     * the column nor the table -- so the operator has to bisect the batch to
     * find out what happened -- or coerces the NULL to the type's zero value
     * ({@code 0}, {@code ''}, the epoch), writing a row that never existed in
     * the source while the row count still matches. The second outcome is the
     * dangerous one: count-based checksums report the table clean.</p>
     *
     * <p>So the batch is failed here, with the database, table, column and
     * declared type in the message. Failing is recoverable -- the batch is
     * retried or surfaced to the operator, and the fix is a one-line ALTER --
     * whereas a silently coerced row is not detectable after the fact. This is
     * the same reasoning, and the same exception type, as
     * {@code rejectUnderivableVersion} above.</p>
     *
     * <p>Genuinely {@code Nullable(...)} columns are untouched: they reach
     * {@code ps.setNull} exactly as before.</p>
     *
     * @param ps the prepared statement being populated
     * @param index the bind index of the column
     * @param colName the ClickHouse column being bound
     * @param columnNameToDataTypeMap the target table's column-to-type map
     * @param config the connector configuration
     * @param tableName the target ClickHouse table
     * @throws SQLException if the bind itself fails
     */
    private void setNullChecked(PreparedStatement ps, int index, String colName,
                                Map<String, String> columnNameToDataTypeMap,
                                ClickHouseSinkConnectorConfig config,
                                String tableName) throws SQLException {
        String chType = columnNameToDataTypeMap == null ? null : columnNameToDataTypeMap.get(colName);
        if (!acceptsNull(chType) && !isConnectorPopulatedColumn(colName, config)) {
            throw new IllegalStateException(String.format(
                    "Database(%s), Table(%s): received NULL for column '%s', whose ClickHouse type"
                            + " is %s -- a type that cannot store NULL. Refusing to bind NULL to a"
                            + " non-nullable column: the driver would either fail the batch with an"
                            + " error naming neither the column nor the table, or coerce the NULL to"
                            + " the type's zero value and silently write a row that does not exist in"
                            + " the source. Change the column to Nullable(%s) in ClickHouse, or stop"
                            + " the source from emitting NULL for it.",
                    databaseName, tableName, colName, chType, chType));
        }
        ps.setNull(index, Types.OTHER);
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
                    // A genuine data column with no placeholder is silently
                    // dropped from the INSERT: nothing binds it here and the
                    // handlers below are guarded by the same map, so the value
                    // never reaches ClickHouse.
                    log.error("***** Column index missing for column ****" + colName
                            + " -- this column is present in the ClickHouse table but has no"
                            + " placeholder in the generated INSERT, so its value will NOT be"
                            + " written. Database(" + databaseName + "), Table(" + tableName + ")");
                }
                continue;
            }

            //String colName = entry.getKey();

            // A NULL for a column ClickHouse declares non-nullable is rejected by
            // setNullChecked below instead of being handed to setNull (issue #1250).
            // If the Received column is not a clickhouse column
            try {
                Object value = struct.get(colName);

                boolean nonDefault = config.getBoolean(ClickHouseSinkConnectorConfigVariables.NON_DEFAULT_VALUE.toString());
                // if config non.default.value is set, use it.
                if (nonDefault) {
                    value = struct.getWithoutDefault(colName);
                }
                if (value == null) {
                    setNullChecked(ps, index, colName, columnNameToDataTypeMap, config, tableName);
                    continue;
                }
            } catch (DataException e) {
                // Struct .get throws a DataException
                // if the field is not present.
                // If the record was not supplied, we need to set it as null.
                // Ignore version and sign columns.
                if (colName.equalsIgnoreCase(versionColumn) || colName.equalsIgnoreCase(signColumn) ||
                        colName.equalsIgnoreCase(replacingMergeTreeDeleteColumn)) {
                    // Ignore version and sign columns
                } else {
                    if(!config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())){
                        log.error(String.format("********** ERROR: Database(%s), Table(%s), ClickHouse column %s not present in source ************", databaseName, tableName, colName));
                        log.error(String.format("********** ERROR: Database(%s), Table(%s), Column %s will be bound to NULL; this fails the batch if the column is not Nullable ************", databaseName, tableName, colName));
                    }
                                    }
                setNullChecked(ps, index, colName, columnNameToDataTypeMap, config, tableName);
                continue;
            }

            // If the column is not in the column data type map, log an error.
            if (!columnNameToDataTypeMap.containsKey(colName)) {
                log.error(" ***** ERROR: Column:{} not found in ClickHouse", colName);
                continue;
            }

            // Get the field information for the column and handle its data type.
            Field f = getFieldByColumnName(fields, colName);
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();
            Object value = struct.get(f);
            if (type == Schema.Type.ARRAY) {
                schemaName = f.schema().valueSchema().type().name();
            }
            // This will throw an exception, unknown data type.
            ClickHouseDataType chDataType = getClickHouseDataType(colName, columnNameToDataTypeMap);
            if (!ClickHouseDataTypeMapper.convert(type, schemaName, value, index, ps, config, chDataType, serverTimeZone)) {
                log.error(String.format("**** DATA TYPE NOT HANDLED type(%s), name(%s), column name(%s)", type.toString(),
                        schemaName, colName));
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
        handleReplacingMergeTreeDeleteColumn(columnNameToIndexMap, ps, record, config, columnNameToDataTypeMap, beforeSection);

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
     * <p>The tombstone deliberately carries the record's own version, one less
     * than the after-image is written with, so the after-image is unambiguously
     * newer. Note the two rows normally land on different sorting keys and so
     * never compete; the ordering matters for the case where they collide.</p>
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

        // Force the delete marker on. insertPreparedStatement() derived it from
        // the CDC operation (UPDATE => not deleted); this row is a tombstone.
        if (this.replacingMergeTreeDeleteColumn != null
                && columnNameToDataTypeMap.containsKey(this.replacingMergeTreeDeleteColumn)
                && columnNameToIndexMap.containsKey(this.replacingMergeTreeDeleteColumn)
                && !config.getBoolean(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())) {
            int deleteColumnIndex = columnNameToIndexMap.get(this.replacingMergeTreeDeleteColumn);
            ps.setInt(deleteColumnIndex, this.replacingMergeTreeWithIsDeletedColumn ? 1 : -1);
        }

        // Keep the tombstone strictly older than the after-image.
        if (this.versionColumn != null
                && columnNameToDataTypeMap.containsKey(this.versionColumn)
                && columnNameToIndexMap.containsKey(this.versionColumn)) {
            if (record.getVersion() == -1) {
                record.calculateVersion(config.getBoolean(
                        ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString()));
            }
            rejectUnderivableVersion(record);
            long tombstoneVersion = record.getVersion() > 0 ? record.getVersion() - 1 : record.getVersion();
            ps.setLong(columnNameToIndexMap.get(this.versionColumn), tombstoneVersion);
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
     * <p>After {@code calculateVersion()} this is only reachable when the record
     * carries no ordering key AND no source commit timestamp, which indicates a
     * malformed or unsupported change event rather than a normal GTID-less source.</p>
     *
     * @param record The CDC record whose version is about to be bound.
     */
    private static void rejectUnderivableVersion(ClickHouseStruct record) {
        if (record.getVersion() == -1) {
            throw new IllegalStateException(
                    "Cannot derive a _version for record from topic '" + record.getTopic()
                            + "' at kafka offset " + record.getKafkaOffset()
                            + ": no GTID, sequence number, LSN or source timestamp is present. "
                            + "Refusing to write the uninitialized sentinel, which is stored as "
                            + "UInt64 18446744073709551615 and would win every ReplacingMergeTree "
                            + "deduplication for this key permanently, silently discarding all "
                            + "later updates and deletes.");
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
            if (columnNameToDataTypeMap.containsKey(versionColumn)) {
                if (columnNameToIndexMap.containsKey(versionColumn)) {
                    // Calculate version if not already set
                    if (record.getVersion() == -1) {
                        boolean useSnowflakeId = config.getBoolean(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString());
                        record.calculateVersion(useSnowflakeId);
                    }
                    rejectUnderivableVersion(record);
                    ps.setLong(columnNameToIndexMap.get(versionColumn), record.getVersion());
                }
            }
        }
    }

    /**
     * Handles delete column for ReplacingMergeTree.
     */
    private void handleReplacingMergeTreeDeleteColumn(Map<String, Integer> columnNameToIndexMap,
                                                       PreparedStatement ps,
                                                       ClickHouseStruct record,
                                                       ClickHouseSinkConnectorConfig config,
                                                       Map<String, String> columnNameToDataTypeMap,
                                                       boolean beforeSection) throws Exception {
        if (this.replacingMergeTreeDeleteColumn != null && columnNameToDataTypeMap.containsKey(replacingMergeTreeDeleteColumn)) {
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
     * Retrieves a field from a list of fields based on the column name. The search
     * is case-insensitive.
     *
     * @param fields The list of fields to search through.
     * @param colName The column name to search for.
     * @return The matching field, or null if no field matches the column name.
     */
    private Field getFieldByColumnName(List<Field> fields, String colName) {
        // ToDo: Change it to a map so that multiple loops are avoided
        Field matchingField = null;
        for (Field f : fields) {
            // Case-insensitive comparison of field name with column name
            if (f.name().equalsIgnoreCase(colName)) {
                matchingField = f;
                break;
            }
        }
        return matchingField;
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

        ClickHouseDataType chDataType = null;
        try {
            // Retrieve the column data type from the map
            String columnDataType = columnNameToDataTypeMap.get(columnName);
            // Create a ClickHouse column object based on the column name and type
            ClickHouseColumn column = ClickHouseColumn.of(columnName, columnDataType);

            // Retrieve the data type from the ClickHouse column if available
            if (column != null) {
                chDataType = column.getDataType();
            }
        } catch (Exception e) {
            // Log any error related to unknown data types
            log.debug("Unknown data type for column: " + columnName, e);
        }

        return chDataType;
    }
}

