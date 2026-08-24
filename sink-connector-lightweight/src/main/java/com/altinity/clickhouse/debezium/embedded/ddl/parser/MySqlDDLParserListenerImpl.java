package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.DataTypeConverter;

import static com.altinity.clickhouse.sink.connector.config.DefaultColumnDataTypeMappingConfig.loadDefaultColumnDataTypeMapping;
import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;
import static org.apache.commons.lang3.StringUtils.containsIgnoreCase;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Utils;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.AlterByAddColumnContext;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.TableNameContext;
import io.debezium.relational.ddl.DataType;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is an implementation of the MySQL DDL parser listener. It overrides specific methods from the generated
 * parser to transform and customize the SQL queries for ClickHouse.
 */
public class MySqlDDLParserListenerImpl extends MySQLDDLParserBaseListener {

    /**
     * Logger instance for logging purposes.
     * This logger is used throughout the class to log messages related to DDL operations.
     */
    private static final Logger log = LogManager.getLogger(MySqlDDLParserListenerImpl.class);

    /**
     * A MySQL character-set introducer immediately preceding a string
     * literal, e.g. the {@code _utf8mb4} in {@code _utf8mb4' '}.
     * <p>
     * MySQL emits one whenever a literal's character set differs from the
     * connection's. It is valid MySQL and meaningless to ClickHouse, which
     * rejects the expression outright with a syntax error. Anchored on the
     * quote so it can only ever match an introducer that actually
     * introduces a literal -- an identifier such as {@code _utf8mb4_col}
     * is left alone. The leading boundary keeps it from biting into the
     * tail of a longer identifier (e.g. {@code my_utf8mb4'x'}).
     */
    private static final Pattern CHARSET_INTRODUCER =
            Pattern.compile("(?<![A-Za-z0-9_$])_[A-Za-z0-9]+(?=['\"])");

    /**
     * Removes MySQL character-set introducers from a generated-column
     * expression so the result is valid ClickHouse.
     * <p>
     * {@code concat(`a`,_utf8mb4' ',`b`)} becomes
     * {@code concat(`a`,' ',`b`)}. The literal itself, and everything
     * else in the expression, is preserved untouched.
     *
     * @param expression the raw expression text from the MySQL parse tree.
     * @return the expression with any charset introducers stripped.
     */
    static String stripCharsetIntroducers(String expression) {
        if (expression == null || expression.indexOf('_') < 0) {
            return expression;
        }
        return CHARSET_INTRODUCER.matcher(expression).replaceAll("");
    }

    /**
     * The query string that will be transformed.
     */
    StringBuffer query;

    /**
     * The name of the table that is part of the DDL operation.
     */
    String tableName;

    /**
     * The configuration object that contains connector settings for ClickHouse.
     */
    ClickHouseSinkConnectorConfig config;

    /**
     * The time zone provided by the user for handling time-related operations.
     */
    ZoneId userProvidedTimeZone;

    /**
     * A map that holds source to destination database mappings.
     */
    Map<String, String> sourceToDestinationMap = new HashMap<>();

    /**
     * The name of the database in the DDL operation.
     */
    String databaseName;

    /**
     * Writer used for database operations.
     */
    BaseDbWriter writer;

    /**
     * Database metadata used for operations.
     */
    DBMetadata dbMetadata;

    /**
     * The original SQL string for regex fallback parsing.
     */
    String originalSql;

    /**
     * Names of the columns the CREATE TABLE being parsed declares NOT NULL.
     *
     * <p>Used to decide whether a UNIQUE key is safe to adopt as the sorting
     * key. Cleared at the start of each CREATE TABLE so a listener reused
     * across statements cannot leak nullability from a previous table.</p>
     */
    private final Set<String> notNullColumnNames = new HashSet<>();

    /**
     * Pre-computed clean table name (backticks and database prefix stripped).
     */
    String cleanTableName;

    /**
     * Constructor for initializing the MySqlDDLParserListenerImpl instance.
     *
     * @param writer         The database writer instance.
     * @param transformedQuery The transformed SQL query.
     * @param tableName      The name of the table involved in the operation.
     * @param databaseName   The name of the database.
     * @param config         The configuration object containing connector settings.
     * @param originalSql    The original SQL string for regex fallback parsing.
     */
    public MySqlDDLParserListenerImpl(BaseDbWriter writer, StringBuffer transformedQuery, String tableName,
                                      String databaseName, ClickHouseSinkConnectorConfig config, String originalSql) {
        this.config = config;
        try {
            if (this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString()) != null)
                sourceToDestinationMap = Utils.parseSourceToDestinationDatabaseMap(this.config.
                        getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString()));
        } catch(Exception e) {
            log.error("enterCreateDatabase: Error parsing source to destination database map:" + e.toString());
        }

        // Override the database name based on the provided configuration.
        this.databaseName = overrideDatabaseName(databaseName);

        this.query = transformedQuery;
        this.tableName = tableName;

        this.config = config;
        this.dbMetadata = new DBMetadata(config);
        this.writer = writer;
        this.userProvidedTimeZone = parseTimeZone();
        this.originalSql = originalSql;
        this.cleanTableName = Utils.extractPlainTableName(tableName);
    }

    /**
     * Function to override the database name based on the source-to-destination map.
     *
     * @param databaseName The original database name from the DDL operation.
     * @return The overridden database name if present in the source-to-destination map.
     */
    private String overrideDatabaseName(String databaseName) {
        // Remove backticks from the database name if present.
        if(databaseName.contains("`")) {
            databaseName = databaseName.replace("`", "");
        }

        // If the source database name is present in the map, override it.
        if(sourceToDestinationMap.containsKey(databaseName)) {
            return sourceToDestinationMap.get(databaseName);
        }
        return databaseName;
    }

    /**
     * Parse the user-provided time zone string and return a ZoneId object.
     *
     * @return The ZoneId object representing the user-provided time zone, or null if not provided.
     */
    public ZoneId parseTimeZone() {
        String userProvidedTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables
                .CLICKHOUSE_DATETIME_TIMEZONE.toString());
        ZoneId userProvidedTimeZoneId = null;
        try {
            if(userProvidedTimeZone != null && !userProvidedTimeZone.isEmpty()) {
                userProvidedTimeZoneId = ZoneId.of(userProvidedTimeZone);
            }
        } catch (Exception e){
            log.error("**** Error parsing user provided timezone:"+ userProvidedTimeZone + e.toString());
        }
        return userProvidedTimeZoneId;
    }



    /**
     * Override the enterCreateDatabase method from the parser listener to handle CREATE DATABASE statements.
     * This method transforms the original CREATE DATABASE query.
     *
     * @param createDatabaseContext The context for the CREATE DATABASE statement.
     */
    @Override
    public void enterCreateDatabase(MySqlParser.CreateDatabaseContext createDatabaseContext) {
        for (ParseTree tree : createDatabaseContext.children) {
            if (tree instanceof MySqlParser.UidContext) {
                String databaseName = tree.getText();
                if(!databaseName.isEmpty()) {
                    String overrideDatabaseName = overrideDatabaseName(tree.getText());
                    this.query.append(String.format(Constants.CREATE_DATABASE, overrideDatabaseName));

                    boolean isReplicatedReplacingMergeTree = config.getBoolean(ClickHouseSinkConnectorConfigVariables
                            .AUTO_CREATE_TABLES_REPLICATED.toString());
                    if(isReplicatedReplacingMergeTree) {
                        this.query.append(" ON CLUSTER `{cluster}`");
                    }
                }
            }
        }
    }

    /**
     * Override the enterDropDatabase method from the parser listener to handle DROP DATABASE statements.
     * This method transforms the original DROP DATABASE query.
     *
     * @param dropDatabaseContext The context for the DROP DATABASE statement.
     */
    @Override
    public void enterDropDatabase(MySqlParser.DropDatabaseContext dropDatabaseContext) {
        for (ParseTree child : dropDatabaseContext.children) {
            if (child instanceof MySqlParser.UidContext) {
                String databaseName = child.getText();
                String overrideDatabaseName = overrideDatabaseName(databaseName);
                this.query.append(String.format(Constants.DROP_DATABASE, overrideDatabaseName));
            }
        }
    }

    /**
     * Override the enterCopyCreateTable method from the parser listener to handle CREATE TABLE LIKE statements.
     * This method transforms the original CREATE TABLE LIKE query.
     *
     * @param copyCreateTableContext The context for the CREATE TABLE LIKE statement.
     */
    @Override
    public void enterCopyCreateTable(MySqlParser.CopyCreateTableContext copyCreateTableContext) {
        ListIterator<ParseTree> it = copyCreateTableContext.children.listIterator();
        String originalTableName = "";
        String newTableName = "";

        while (it.hasNext()) {
            ParseTree tree = it.next();
            if (tree instanceof MySqlParser.TableNameContext) {
                originalTableName = tree.getText();
                if (it.next().getText().equalsIgnoreCase(Constants.LIKE)) {
                    newTableName = it.next().getText();
                }
            }
        }

        // Handle the case where the table name includes the database name.
        if (originalTableName.contains(".")) {
            this.query.append(Constants.CREATE_TABLE).append(" ").append(originalTableName).append(" ")
                    .append(Constants.AS).append(" ").append(newTableName);
        } else {
            this.query.append(Constants.CREATE_TABLE).append(" ").append("`").append(databaseName).append("`").append(".").append(originalTableName).append(" ")
                    .append(Constants.AS).append(" ").append("`").append(databaseName).append("`").append(".").append(newTableName);
        }
    }

    /**
     * Override the enterColumnCreateTable method from the parser listener to handle column definitions
     * for CREATE TABLE statements. It also handles the engine type and versioning for ReplacingMergeTree.
     *
     * @param columnCreateTableContext The context for the column definitions in CREATE TABLE.
     */
    @Override
    public void enterColumnCreateTable(MySqlParser.ColumnCreateTableContext columnCreateTableContext) {
        StringBuilder orderByColumns = new StringBuilder();
        StringBuilder partitionByColumn = new StringBuilder();
        StringBuilder uniqueKeyColumns = new StringBuilder();
        List<String> orderedColumnNames = new ArrayList<>();
        notNullColumnNames.clear();
        Set<String> columnNames = parseCreateTable(columnCreateTableContext, orderByColumns, partitionByColumn,
                uniqueKeyColumns, orderedColumnNames);

        // A table with a UNIQUE key but no PRIMARY KEY would otherwise be created
        // with ORDER BY tuple(): every row compares equal, so ReplacingMergeTree
        // collapses the whole table into one row. The UNIQUE key is the source's
        // stable row identity, so use it as the sorting key. Only applied when no
        // PRIMARY KEY was found -- the PRIMARY KEY always wins.
        //
        // ONLY when every column of that UNIQUE key is NOT NULL. MySQL does not
        // treat NULLs as equal for uniqueness, so a nullable UNIQUE index permits
        // any number of rows whose key is NULL -- it is not a row identity at
        // all. ClickHouse compares NULLs as equal in a sorting key, so adopting
        // such a key makes ReplacingMergeTree collapse those distinct source rows
        // into one.
        //
        // Measured on MySQL 8.0.36 -> ClickHouse 24.8.14.10547 with
        // UNIQUE KEY(a) over a nullable `a`: four source rows, three of them
        // a IS NULL, arrived as TWO -- 'first' and 'second' silently lost. A
        // partially-nullable composite UNIQUE key loses rows the same way.
        //
        // Such a table has no usable declared identity, so it falls through to
        // the all-columns fallback below, which reproduces MySQL's own semantics
        // for a table without a row identity: rows are distinguished by value.
        List<String> uniqueKeyColumnNames = splitIndexColumns(uniqueKeyColumns.toString());
        boolean uniqueKeyIsNotNull = !uniqueKeyColumnNames.isEmpty()
                && notNullColumnNames.containsAll(uniqueKeyColumnNames);

        if (orderByColumns.length() == 0 && uniqueKeyColumns.length() > 0) {
            if (uniqueKeyIsNotNull) {
                log.info("Table has no PRIMARY KEY; using UNIQUE key as the ClickHouse sorting key: "
                        + uniqueKeyColumns);
                orderByColumns.append(uniqueKeyColumns);
            } else {
                log.warn("Table {}.{} has no PRIMARY KEY and its UNIQUE key ({}) spans nullable "
                                + "columns. MySQL does not treat NULLs as equal, so that index permits "
                                + "many NULL-keyed rows and is not a row identity; ClickHouse would "
                                + "collapse them. Falling back to all columns as the sorting key.",
                        this.databaseName, this.tableName, uniqueKeyColumns);
            }
        }

        // Neither a PRIMARY KEY nor a UNIQUE key: the table has no declared row
        // identity at all (MySQL's `alembic_version` is the canonical example).
        // ORDER BY tuple() makes every row compare equal, so ReplacingMergeTree
        // keeps exactly ONE row for the entire table -- a silent, total data loss
        // that is invisible while the table holds a single row and appears the
        // moment it grows to two.
        //
        // Making the full row its own identity is precisely MySQL's own semantics
        // for a keyless table: rows are distinguished by their values. Paired with
        // the before-image tombstone emitted by PreparedStatementExecutor, this
        // reproduces INSERT, UPDATE and DELETE exactly (verified against MySQL for
        // 1-row, N-row, growth, NULL-bearing and value-round-trip cases).
        //
        // That identity is carried by ONE generated column holding a fingerprint
        // of the row, NOT by listing every data column in the sorting key.
        //
        // Listing the data columns was the first implementation and it FROZE THE
        // TABLE'S SCHEMA. ClickHouse forbids altering any column that participates
        // in the sorting key, so on a keyless table every one of these failed with
        // Code: 524 ALTER_OF_COLUMN_IS_FORBIDDEN, measured on 24.8.14.10547:
        //
        //   ALTER TABLE t MODIFY COLUMN b Nullable(Int32)
        //     -> "ALTER of key column b ... is not safe because it can change the
        //         representation of primary key"
        //   ALTER TABLE t RENAME COLUMN b TO b_new
        //     -> "Trying to ALTER RENAME key b column which is a part of key
        //         expression"
        //   ALTER TABLE t DROP COLUMN v
        //     -> Code: 47 UNKNOWN_IDENTIFIER, the key expression still names it
        //
        // The connector retries a failed DDL ten times (~45s) and then gives up, so
        // the change never lands and replication of that table's schema is stuck.
        // A fix for silent data loss must not cost the table its ability to take
        // any other operation.
        //
        // The fingerprint column is MATERIALIZED, so it is computed by ClickHouse
        // on insert and never appears in the INSERT column list the writer builds.
        // Each column contributes an explicit null-flag as well as its text, so a
        // genuine NULL cannot collide with any literal a column might hold (''
        // and '0' and NULL all hash differently -- verified). The flag also keeps
        // the expression non-Nullable, which a sorting key requires: hashing the
        // raw Nullable columns yields Nullable(UInt64) and rejects the row with
        // Code: 349, and cityHash64(tuple(...)) throws Code: 48 on a NULL member.
        //
        // PRIMARY KEY tuple() keeps the sparse index empty; the fingerprint exists
        // only to give ReplacingMergeTree a full-row identity to deduplicate on.
        //
        // Not representable: a table holding two byte-identical rows. Identical
        // rows are indistinguishable under ANY sorting key, so ClickHouse keeps
        // one. Collapsing/Summing engines track the multiplicity but still
        // collapse the physical rows at merge time, so they do not help a plain
        // consumer query. This case is logged as a warning below and is unchanged
        // by moving from an all-column key to the fingerprint.
        boolean isKeylessTable = false;
        String rowKeyColumnDefinition = null;
        if (orderByColumns.length() == 0 && !orderedColumnNames.isEmpty()) {
            isKeylessTable = true;
            // The source may legitimately hold a column of this name. Emitting
            // ours unconditionally then produces two definitions of it and
            // ClickHouse rejects the CREATE outright (Code: 44 "column with
            // this name already exists"), stalling that table. Disambiguate the
            // same way the delete marker already does.
            String rowKeyColumn = resolveRowKeyColumnName(orderedColumnNames);
            rowKeyColumnDefinition = "`" + rowKeyColumn + "` UInt64 MATERIALIZED "
                    + rowFingerprintExpression(orderedColumnNames);
            orderByColumns.append("`").append(rowKeyColumn).append("`");
            log.warn("Table {}.{} declares no PRIMARY KEY and no UNIQUE key. Adding the generated column "
                            + "`{}` as the ClickHouse sorting key -- a fingerprint over all {} columns -- so "
                            + "rows are not collapsed while the data columns stay alterable. NOTE: if this "
                            + "table can hold two byte-identical rows, ClickHouse will retain only one of "
                            + "them -- identical rows cannot be told apart by any sorting key.",
                    this.databaseName, this.tableName, rowKeyColumn, orderedColumnNames.size());
        }

        String isDeletedColumn = IS_DELETED_COLUMN;

        // Iterate through columnNames and match isDeletedColumn with elements in columnNames.
        for (String columnName: columnNames) {
            if (columnName.contains("`")) {
                columnName = columnName.replace("`", "");
            }
            if (columnName.equalsIgnoreCase(isDeletedColumn)) {
                isDeletedColumn = "_" + IS_DELETED_COLUMN;
                break;
            }
        }

        // Check if the destination is ReplicatedReplacingMergeTree.
        boolean isReplicatedReplacingMergeTree = config.getBoolean(ClickHouseSinkConnectorConfigVariables
                .AUTO_CREATE_TABLES_REPLICATED.toString());

        String chDataTypeWithTimeZone = DataTypeConverter.addTimeZoneToDateTimeType(ClickHouseDataType.DateTime, 0, userProvidedTimeZone);
        // append this to the chDataTypeWithTimeZone
        chDataTypeWithTimeZone = chDataTypeWithTimeZone + " DEFAULT " + "'" + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME32_MAX_TTL) + "'";
        // If Replication history is enabled, add the
        // deleted_time DateTime DEFAULT '2149-06-06',
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {

            this.query.append("`").append(DELETED_FROM_TIME_COLUMN)
                    .append("` ").append(chDataTypeWithTimeZone)
                    .append(",");

            this.query.append("`").append(DELETED_TIME_COLUMN)
                    .append("` ").append(chDataTypeWithTimeZone)
                    .append(",");

            this.query.append("`").append(OPERATION_COLUMN)
                    .append("` ").append(OPERATION_COLUMN_DATA_TYPE)
                    .append(",");
        }


        // ALIAS columns from column_type_override.alias.*
        if (this.config != null) {
            ColumnTypeOverrideConfig overrideConfig =
                    ColumnTypeOverrideConfig.fromProperties(this.config.originalsStrings());
            if (overrideConfig.hasOverrides()) {
                String cleanTableName = this.cleanTableName;
                List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                        overrideConfig.getAliasOverrides(this.databaseName, cleanTableName);
                for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
                    this.query.append("`").append(entry.getAliasColumnName()).append("` ")
                            .append(entry.getAliasType())
                            .append(" ALIAS ").append(entry.getExpression()).append(",");
                }
            }
        }

        // The keyless-table row fingerprint. MATERIALIZED, so ClickHouse computes
        // it on insert and it never appears in the writer's INSERT column list.
        if (rowKeyColumnDefinition != null) {
            this.query.append(rowKeyColumnDefinition).append(",");
        }

        if (DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine) {
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(isDeletedColumn).append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
        } else {
            this.query.append("`").append(SIGN_COLUMN).append("` ").append(SIGN_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE);
        }

        this.query.append(")");

        // Retrieve table from configuration setting
        SchemaOverrideConfig.Table tableConfig = SchemaOverrideConfig.getTableConfig(this.databaseName, this.tableName, this.config.originalsStrings());

        // Add engine type based on table configuration.
        if (DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine) {
            if (isReplicatedReplacingMergeTree) {
                this.query.append(String.format(" Engine=ReplicatedReplacingMergeTree(%s, %s)", VERSION_COLUMN, isDeletedColumn));
            } else {
                this.query.append(" Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(",").append(isDeletedColumn).append(")");
            }
        } else {
            if (isReplicatedReplacingMergeTree) {
                this.query.append(String.format(" Engine=ReplicatedReplacingMergeTree(%s)", VERSION_COLUMN));
            } else {
                this.query.append(" Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(")");
            }
        }

        // Append partitioning and ordering clauses, using values from tableConfig if they exist

        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            String deletedTimeColumnToDate = String.format(DELETED_TIME_COLUMN_TO_DATE, DELETED_TIME_COLUMN);
            this.query.append(" PARTITION BY ").append(deletedTimeColumnToDate);
        } else if (tableConfig.getPartitionBy() != null && !tableConfig.getPartitionBy().isEmpty()) {
            // Use the partition_by from tableConfig if it exists
            this.query.append(Constants.PARTITION_BY).append(" ").append(tableConfig.getPartitionBy());
        } else if (partitionByColumn.length() > 0) {
            // Fallback to partitionByColumn if tableConfig does not provide a partition_by value
            this.query.append(Constants.PARTITION_BY).append(" ").append(partitionByColumn);
        }

        // For the all-columns fallback, pin an EMPTY primary key. In ClickHouse the
        // primary key defaults to the sorting key and is held in memory for every
        // active part, so a wide all-column sorting key would otherwise inflate the
        // sparse index for no benefit. PRIMARY KEY tuple() keeps the index empty
        // while ORDER BY still gives ReplacingMergeTree its full-row identity.
        // Emitted before ORDER BY because ClickHouse requires that clause order.
        if (isKeylessTable && (tableConfig.getPrimaryKey() == null || tableConfig.getPrimaryKey().isEmpty())) {
            this.query.append(" PRIMARY KEY tuple()");
        }

        if (tableConfig.getPrimaryKey() != null && !tableConfig.getPrimaryKey().isEmpty()) {
            // Use the primary_key from tableConfig if it exists
            this.query.append(Constants.ORDER_BY).append(tableConfig.getPrimaryKey());
        }else if (orderByColumns.length() == 0) {
            this.query.append(Constants.ORDER_BY_TUPLE);
        } else{
            // Convert the orderByColumns object to a string
            String orderByStr = orderByColumns.toString();

            // Regex pattern to detect invalid column suffix like id_registro(10)
            String regex = "\\b(\\w+)\\(\\d+\\)";

            if (orderByStr.matches(".*" + regex + ".*")) {
                // If pattern is matched: clean up suffix and append ORDER BY
                String fixedOrderBy = orderByStr.replaceAll(regex, "$1");

                // Append the sanitized ORDER BY clause to the query
                this.query.append(Constants.ORDER_BY).append(fixedOrderBy);
            } else {
                // Otherwise, use the orderByColumns for ordering

                if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                    this.query.append(Constants.ORDER_BY);
                    this.query.append("(");
                    this.query.append(orderByColumns.toString());
                    this.query.append(",`").append(DELETED_TIME_COLUMN).append("`");

                    this.query.append(")");
                }
                else {
                    this.query.append(Constants.ORDER_BY).append(orderByStr);
                }
            }
        }

        if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            this.query.append(" TTL `").append(DELETED_TIME_COLUMN)
                      .append("` + toIntervalDay(").append(config.getInt(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_TTL.toString()))
                      .append(")");
        }
        

        // No path emits allow_nullable_key any more.
        //
        // A PRIMARY KEY is NOT NULL by MySQL's own rule; a UNIQUE key is adopted
        // above only when every one of its columns is NOT NULL; and the keyless
        // fallback now keys on the generated `_row_key`, which is UInt64 and
        // non-Nullable by construction. Emitting the setting where it is not
        // needed would silently permit nullable keys ClickHouse is right to
        // reject.
        String tableSettings = tableConfig.getSettings();
        if (tableSettings != null && !tableSettings.isEmpty()) {
            // Use the settings from tableConfig if it exists
            this.query.append(Constants.SETTINGS).append(tableSettings);
        }
    }

    /**
     * Builds the {@code _row_key} fingerprint expression for a keyless table.
     *
     * <p>Each column contributes an explicit null-flag as well as its text, so a
     * genuine NULL cannot collide with any literal the column might legitimately
     * hold: {@code NULL}, {@code ''} and {@code '0'} all hash to different
     * values (verified on ClickHouse 24.8.14.10547).</p>
     *
     * <p>The null-flag also keeps every argument non-Nullable, which a sorting
     * key requires. Hashing the raw {@code Nullable} columns yields
     * {@code Nullable(UInt64)} and the insert is rejected with Code: 349, and
     * passing a {@code Nullable} straight into the tuple throws Code: 48 the
     * moment any member is NULL -- both measured before settling on this
     * form.</p>
     *
     * <p>The arguments are passed as a {@code tuple}, NOT concatenated into one
     * string. A delimiter-joined string is not an unambiguous encoding: with a
     * 0x01 separator, the rows {@code ('x', 0x01+'0z')} and
     * {@code ('x'+0x01+'0', 'z')} flatten to the identical byte sequence and so
     * to the identical fingerprint, and ReplacingMergeTree then collapses two
     * genuinely distinct rows. Verified on 24.8.14.10547: the concatenated form
     * returns collision=1 for that pair, the tuple form returns 0, because
     * tuple members keep their boundaries.</p>
     *
     * @param columns the table's columns in declaration order.
     * @return a non-Nullable UInt64 expression fingerprinting the whole row.
     */
    /**
     * Picks a name for the generated row-key column that no source column
     * already uses.
     *
     * <p>A keyless MySQL table may itself declare a column called
     * {@code _row_key}. Emitting ours unconditionally then renders two
     * definitions of the same name and ClickHouse rejects the CREATE with
     * Code: 44 "column with this name already exists", stalling replication of
     * that table. Underscores are prepended until the name is free, the same
     * disambiguation the delete-marker column already uses.</p>
     *
     * @param columns the table's declared columns.
     * @return a column name not present in the source table.
     */
    private static String resolveRowKeyColumnName(List<String> columns) {
        Set<String> taken = new HashSet<>();
        for (String column : columns) {
            taken.add(stripBackticks(column).toLowerCase());
        }
        String candidate = ROW_KEY_COLUMN;
        while (taken.contains(candidate.toLowerCase())) {
            candidate = "_" + candidate;
        }
        return candidate;
    }

    private static String rowFingerprintExpression(List<String> columns) {
        StringBuilder parts = new StringBuilder();
        for (String column : columns) {
            if (parts.length() > 0) {
                parts.append(",");
            }
            String quoted = "`" + stripBackticks(column) + "`";
            parts.append("isNull(").append(quoted).append(")")
                    .append(",ifNull(toString(").append(quoted).append("),'')");
        }
        return "cityHash64(tuple(" + parts + "))";
    }

    /**
     * Finds partitioning options using regex when ANTLR parser fails to identify partitions.
     * This method provides a fallback mechanism to extract partition columns from the raw DDL text.
     *
     * @param source The raw DDL source string to search for partitioning patterns.
     * @return The partitioning options string, or empty string if no partitioning is found.
     */
    private String findPartitioningOptions(String source) {
        // First try to match PARTITION BY RANGE COLUMNS(...)
        Pattern pattern = Pattern.compile("PARTITION\\s+BY\\s+RANGE\\s+COLUMNS\\((.*?)\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(source);
        String partitioningKeys = null;
        if (matcher.find()) {
            partitioningKeys = matcher.group(1);
            log.info("Partitioning key (RANGE COLUMNS): " + partitioningKeys);
        }

        // If not found, try to match function-based partitioning like PARTITION BY RANGE( YEAR(...) )
        if (partitioningKeys == null) {
            // Match PARTITION BY RANGE( <function_or_expression> ) but stop before the partition definitions
            Pattern functionPattern = Pattern.compile("PARTITION\\s+BY\\s+RANGE\\s*\\(\\s*([^)]+)\\s*\\)\\s*\\(", Pattern.CASE_INSENSITIVE);
            Matcher functionMatcher = functionPattern.matcher(source);
            if (functionMatcher.find()) {
                String functionExpression = functionMatcher.group(1).trim();
                log.info("Found function-based partitioning: " + functionExpression);
                // Convert MySQL function to ClickHouse equivalent
                partitioningKeys = convertMySQLPartitionFunctionToClickHouse(functionExpression);
                if (partitioningKeys != null) {
                    log.info("Converted to ClickHouse partition: " + partitioningKeys);
                }
            }
        }

        String partitioningOptions = "";
        if (partitioningKeys != null) {
            partitioningOptions = "PARTITION BY " + partitioningKeys;
        }
        return partitioningOptions;
    }

    /**
     * Convert MySQL partition function expressions to ClickHouse equivalents
     * @param mysqlFunction MySQL partition function expression (e.g., "YEAR(order_date)")
     * @return ClickHouse partition expression or null if conversion not supported
     */
    private String convertMySQLPartitionFunctionToClickHouse(String mysqlFunction) {
        if (mysqlFunction == null || mysqlFunction.isEmpty()) {
            return null;
        }

        // Extract column name from function like YEAR(column_name) or TO_DAYS(column_name)
        Pattern columnPattern = Pattern.compile("(\\w+)\\s*\\(\\s*([\\w`]+)\\s*\\)", Pattern.CASE_INSENSITIVE);
        Matcher columnMatcher = columnPattern.matcher(mysqlFunction);

        if (columnMatcher.find()) {
            String function = columnMatcher.group(1).toUpperCase();
            String columnName = columnMatcher.group(2).replaceAll("`", "");

            // Convert MySQL functions to ClickHouse equivalents
            switch (function) {
                case "YEAR":
                    return "toYear(" + columnName + ")";
                case "MONTH":
                    return "toMonth(" + columnName + ")";
                case "DAY":
                case "DAYOFMONTH":
                    return "toDayOfMonth(" + columnName + ")";
                case "TO_DAYS":
                    // ClickHouse doesn't have exact TO_DAYS equivalent, use date directly
                    return columnName;
                case "UNIX_TIMESTAMP":
                    return "toUnixTimestamp(" + columnName + ")";
                default:
                    log.warn("Unsupported MySQL partition function: " + function + ". Using column directly.");
                    return columnName;
            }
        }

        // If no function pattern matches, return the expression as-is
        log.info("Could not parse partition function, using expression as-is: " + mysqlFunction);
        return mysqlFunction;
    }

    /**
     * This function parses the CREATE TABLE statement and processes the columns,
     * order by clauses, and partitioning specifications.
     *
     * @param ctx The context of the CREATE TABLE statement.
     * @param orderByColumns A StringBuilder to store the ORDER BY columns.
     * @param partitionByColumns A StringBuilder to store the PARTITION BY columns.
     * @return A set of column names defined in the CREATE TABLE statement.
     */
    private Set<String> parseCreateTable(MySqlParser.CreateTableContext ctx, StringBuilder orderByColumns,
                                         StringBuilder partitionByColumns) {
        return parseCreateTable(ctx, orderByColumns, partitionByColumns, new StringBuilder());
    }

    /**
     * Overload that additionally collects the first UNIQUE key of the table.
     *
     * <p>A MySQL table may declare a UNIQUE key but no PRIMARY KEY. Such a
     * table was previously created as
     * {@code ReplacingMergeTree(...) ORDER BY tuple()}: with an empty sorting
     * key every row compares equal, so ReplacingMergeTree collapses the entire
     * table down to a single row. The UNIQUE key is the source's stable row
     * identity, which is exactly what the sorting key must be, so it is used
     * as the fallback when no PRIMARY KEY is present.</p>
     *
     * @param ctx The context of the CREATE TABLE statement.
     * @param orderByColumns A StringBuilder to store the PRIMARY KEY columns.
     * @param partitionByColumns A StringBuilder to store the PARTITION BY columns.
     * @param uniqueKeyColumns A StringBuilder receiving the first UNIQUE key
     *                         declared, used only when no PRIMARY KEY exists.
     * @return A set of column names defined in the CREATE TABLE statement.
     */
    private Set<String> parseCreateTable(MySqlParser.CreateTableContext ctx, StringBuilder orderByColumns,
                                         StringBuilder partitionByColumns, StringBuilder uniqueKeyColumns) {
        return parseCreateTable(ctx, orderByColumns, partitionByColumns, uniqueKeyColumns, new ArrayList<>());
    }

    /**
     * Overload that additionally records the declared columns <em>in DDL order</em>.
     *
     * <p>{@code columnNames} is a {@link HashSet} and therefore unordered. That
     * is fine for the membership test it exists for, but unusable as a sorting
     * key: {@code ORDER BY} must be deterministic, or two connectors replicating
     * the same source would build tables whose sorting keys differ purely by
     * hash iteration order. This overload preserves declaration order so the
     * all-columns fallback sorting key is stable and reproducible.</p>
     *
     * @param ctx The context of the CREATE TABLE statement.
     * @param orderByColumns A StringBuilder to store the PRIMARY KEY columns.
     * @param partitionByColumns A StringBuilder to store the PARTITION BY columns.
     * @param uniqueKeyColumns A StringBuilder receiving the first UNIQUE key.
     * @param orderedColumnNames A list receiving the sortable columns in
     *                           declaration order. Generated columns are
     *                           excluded: they are a pure function of the stored
     *                           columns and so add nothing to row identity, and
     *                           they are absent from the CDC record payload.
     * @return A set of column names defined in the CREATE TABLE statement.
     */
    private Set<String> parseCreateTable(MySqlParser.CreateTableContext ctx, StringBuilder orderByColumns,
                                         StringBuilder partitionByColumns, StringBuilder uniqueKeyColumns,
                                         List<String> orderedColumnNames) {
        List<ParseTree> pt = ctx.children;
        Set<String> columnNames = new HashSet<>();

        this.query.append(Constants.CREATE_TABLE).append(" ");
        for (ParseTree tree : pt) {

            if (tree instanceof TableNameContext) {
                this.tableName = tree.getText();
                // If tableName already includes the database name, don't include database name in the query.
                if (tableName.contains(".")) {
                    // Split tableName into databaseName and tableName
                    String[] tableNameSplit = tableName.split("\\.");
                    this.query.append("`").append(this.databaseName).append("`").append(".").append(tableNameSplit[1]);
                } else {
                    this.query.append("`").append(databaseName).append("`").append(".").append(tree.getText());
                }

                // If it's ReplicatedReplacingMergeTree, add ON CLUSTER {cluster} to the query.
                boolean isReplicatedReplacingMergeTree = config.getBoolean(ClickHouseSinkConnectorConfigVariables
                        .AUTO_CREATE_TABLES_REPLICATED.toString());
                if (isReplicatedReplacingMergeTree) {
                    this.query.append(" ON CLUSTER `{cluster}`");
                }
                this.query.append("(");
            } else if (tree instanceof MySqlParser.IfNotExistsContext) {
                this.query.append(Constants.IF_NOT_EXISTS);
            } else if (tree instanceof MySqlParser.CreateDefinitionsContext) {
                for (ParseTree subtree : ((MySqlParser.CreateDefinitionsContext) tree).children) {
                    if (subtree instanceof TerminalNodeImpl) {
                        // Do nothing for TerminalNodeImpl, just skip it
                    } else if (subtree instanceof MySqlParser.ColumnDeclarationContext) {
                        // Parse column definitions
                        parseColumnDefinitions(subtree, orderByColumns, columnNames, uniqueKeyColumns,
                                orderedColumnNames);
                    } else if(subtree instanceof MySqlParser.ConstraintDeclarationContext) {
                        for (ParseTree constraintTree: ((MySqlParser.ConstraintDeclarationContext) subtree).children) {
                            if (constraintTree instanceof MySqlParser.PrimaryKeyTableConstraintContext) {
                                for (ParseTree primaryKeyTree: ((MySqlParser.PrimaryKeyTableConstraintContext) constraintTree).children) {
                                    if (primaryKeyTree instanceof MySqlParser.IndexColumnNamesContext) {
                                        String primaryKeyColumns = primaryKeyTree.getText();
                                        if (primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) {
                                            orderByColumns.append(primaryKeyColumns);
                                        }
                                    }
                                }
                            } else if (constraintTree instanceof MySqlParser.UniqueKeyTableConstraintContext) {
                                // Table-level: UNIQUE KEY (col, ...). Only the FIRST unique
                                // key is retained; it is used as the sorting key when the
                                // table declares no PRIMARY KEY.
                                if (uniqueKeyColumns.length() == 0) {
                                    for (ParseTree uniqueKeyTree: ((MySqlParser.UniqueKeyTableConstraintContext) constraintTree).children) {
                                        if (uniqueKeyTree instanceof MySqlParser.IndexColumnNamesContext) {
                                            String uniqueColumns = uniqueKeyTree.getText();
                                            if (uniqueColumns != null && !uniqueColumns.isEmpty()) {
                                                uniqueKeyColumns.append(uniqueColumns);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (tree instanceof MySqlParser.PartitionDefinitionsContext) {
                for (ParseTree partitionTree: ((MySqlParser.PartitionDefinitionsContext) tree).children) {
                    if (partitionTree instanceof MySqlParser.PartitionFunctionKeyContext) {
                        for (ParseTree partitionKeyTree: ((MySqlParser.PartitionFunctionKeyContext) partitionTree).children) {
                            if (partitionKeyTree instanceof MySqlParser.UidListContext) {
                                String partitionColumn = partitionKeyTree.getText();
                                partitionByColumns.append(partitionColumn);
                            }
                        }
                    } else if (partitionTree instanceof MySqlParser.PartitionFunctionRangeContext) {
                        for (ParseTree partitionFunctionRangeTree: ((MySqlParser.PartitionFunctionRangeContext) partitionTree).children) {
                            if (partitionFunctionRangeTree instanceof MySqlParser.UidListContext) {
                                partitionByColumns.append("(").append(partitionFunctionRangeTree.getText()).append(")");
                            } else if (partitionFunctionRangeTree instanceof MySqlParser.PredicateExpressionContext) {
                                // Handle function-based partitioning like PARTITION BY RANGE( YEAR(order_date) )
                                String functionExpression = partitionFunctionRangeTree.getText();
                                log.info("Found partition function expression: " + functionExpression);
                                // Convert MySQL function to ClickHouse equivalent
                                String clickhousePartition = convertMySQLPartitionFunctionToClickHouse(functionExpression);
                                if (clickhousePartition != null && !clickhousePartition.isEmpty()) {
                                    partitionByColumns.append(clickhousePartition);
                                    log.info("Converted partition to ClickHouse format: " + clickhousePartition);
                                }
                            }
                        }
                    }
                }
            }
        }

        // If ANTLR parser didn't find partition columns, try regex as fallback
        if (partitionByColumns.length() == 0 && originalSql != null) {
            try {
                // Use the original DDL text for regex parsing (handles MySQL comments)
                String regexPartitioning = findPartitioningOptions(originalSql);
                if (!regexPartitioning.isEmpty()) {
                    // Extract only the partition columns part (remove "PARTITION BY " prefix)
                    String partitionColumns = regexPartitioning.substring("PARTITION BY ".length());
                    partitionByColumns.append(partitionColumns);
                    log.info("Regex fallback found partitioning: " + partitionColumns);
                }
            } catch (Exception e) {
                log.warn("Regex fallback for partition parsing failed: " + e.getMessage());
            }
        }

        return columnNames;
    }

    /**
     * Function to parse the column definitions in a CREATE TABLE statement.
     * It processes column names, data types, and constraints like NOT NULL, PRIMARY KEY, and GENERATED columns.
     *
     * @param subtree The subtree representing the column definition in the DDL.
     * @param orderByColumns A StringBuilder to append order by columns for indexing.
     * @param columnNames A set to hold the column names parsed from the statement.
     */
    private void parseColumnDefinitions(ParseTree subtree, StringBuilder orderByColumns, Set<String> columnNames) {
        parseColumnDefinitions(subtree, orderByColumns, columnNames, new StringBuilder());
    }

    /**
     * Overload that additionally records a column-level {@code UNIQUE}
     * constraint (for example {@code uk INT NOT NULL UNIQUE}) so that a table
     * without a PRIMARY KEY can still be given a real sorting key.
     *
     * @param subtree The parse subtree of the column declaration.
     * @param orderByColumns A StringBuilder to append PRIMARY KEY columns to.
     * @param columnNames A set to hold the column names parsed from the statement.
     * @param uniqueKeyColumns A StringBuilder receiving the first UNIQUE column,
     *                         used only when no PRIMARY KEY exists.
     */
    private void parseColumnDefinitions(ParseTree subtree, StringBuilder orderByColumns, Set<String> columnNames,
                                        StringBuilder uniqueKeyColumns) {
        parseColumnDefinitions(subtree, orderByColumns, columnNames, uniqueKeyColumns, new ArrayList<>());
    }

    /**
     * Overload that additionally records the column in declaration order, for
     * use as an all-columns fallback sorting key.
     *
     * @param subtree The parse subtree of the column declaration.
     * @param orderByColumns A StringBuilder to append PRIMARY KEY columns to.
     * @param columnNames A set to hold the column names parsed from the statement.
     * @param uniqueKeyColumns A StringBuilder receiving the first UNIQUE column.
     * @param orderedColumnNames A list receiving sortable columns in declaration
     *                           order. Generated columns are skipped.
     */
    private void parseColumnDefinitions(ParseTree subtree, StringBuilder orderByColumns, Set<String> columnNames,
                                        StringBuilder uniqueKeyColumns, List<String> orderedColumnNames) {
        String columnName = null;
        String colDataType = null;
        boolean isNullColumn = true;
        boolean isGeneratedColumn = false;
        String generatedColumn = "";

        for (ParseTree colDefTree : ((MySqlParser.ColumnDeclarationContext) subtree).children) {
            if (colDefTree instanceof MySqlParser.FullColumnNameContext) {
                columnName = colDefTree.getText();
                this.query.append(columnName).append(" ");
            } else if (colDefTree instanceof MySqlParser.ColumnDefinitionContext) {
                String colDataTypeDefinition = colDefTree.getText();

                // Get the corresponding ClickHouse data type for the column.
                colDataType = getClickHouseDataType(colDataTypeDefinition, colDefTree, columnName);

                // Handle constraints such as NOT NULL, PRIMARY KEY, and GENERATED column.
                for (ParseTree colDefinitionChildTree: ((MySqlParser.ColumnDefinitionContext) colDefTree).children) {
                    if (colDefinitionChildTree instanceof MySqlParser.NullColumnConstraintContext) {
                        if (colDefinitionChildTree.getText().equalsIgnoreCase(Constants.NOT_NULL)) {
                            isNullColumn = false;
                        }
                    } else if (colDefinitionChildTree instanceof MySqlParser.PrimaryKeyColumnConstraintContext) {
                        for (ParseTree primaryKeyTree: ((MySqlParser.PrimaryKeyColumnConstraintContext) colDefinitionChildTree).children) {
                            isNullColumn = false;
                            orderByColumns.append(columnName);
                            break;
                        }
                    } else if (colDefinitionChildTree instanceof MySqlParser.UniqueKeyColumnConstraintContext) {
                        // Column-level: `uk INT NOT NULL UNIQUE`. Retained only as a
                        // fallback sorting key for tables that declare no PRIMARY KEY.
                        // Unlike PRIMARY KEY this does NOT force the column NOT NULL:
                        // MySQL permits NULLs in a UNIQUE column, so the ClickHouse
                        // column nullability must keep following the source DDL.
                        if (uniqueKeyColumns.length() == 0 && columnName != null) {
                            uniqueKeyColumns.append(columnName);
                        }
                    } else if (colDefinitionChildTree instanceof MySqlParser.GeneratedColumnConstraintContext) {
                        for (ParseTree generatedColumnTree: ((MySqlParser.GeneratedColumnConstraintContext) colDefinitionChildTree).children) {
                            if (generatedColumnTree instanceof MySqlParser.ExpressionContext) {
                                for(ParseTree generatedColumnTreeChildren: ((MySqlParser.ExpressionContext) generatedColumnTree).children) {
                                    //System.out.println(generatedColumnTreeChildren.getText().trim());
                                    // iterate over the children of the generatedColumnTreeChildren
                                    if(generatedColumnTreeChildren instanceof MySqlParser.IsNullPredicateContext) {
                                        for (ParseTree generatedColumnTreeChildrenChildren : ((MySqlParser.IsNullPredicateContext) generatedColumnTreeChildren).children) {
                                            if (generatedColumnTreeChildrenChildren instanceof MySqlParser.ExpressionAtomPredicateContext) {
                                                //System.out.println(generatedColumnTreeChildrenChildren.getText().trim());
                                                generatedColumn = generatedColumnTreeChildrenChildren.getText();
                                            }
                                        }
                                    } else {
                                        generatedColumn = generatedColumnTreeChildren.getText();
                                    }
                                }
                                isGeneratedColumn = true;
                                generatedColumn =
                                        stripCharsetIntroducers(generatedColumn);
                                //generatedColumn = generatedColumnTree.getText();
                            }
                        }
                    }
                }

                if (isGeneratedColumn) {
                    // For generated columns, handle NULL and NOT NULL constraints.
                    if (isNullColumn) {
                        this.query.append(Constants.NULLABLE).append("(").append(colDataType).append(")");
                    } else {
                        this.query.append(colDataType);
                    }

                    this.query.append(" ").append(Constants.ALIAS).append(" ").append(generatedColumn).append(",");
                    continue;
                }

                // For non-generated columns, apply nullable constraints if applicable.
                String lowerCaseDataType = colDataType.toLowerCase();
                if (!Constants.NULLABLE_NOT_SUPPORTED_DATA_TYPES.contains(lowerCaseDataType) && isNullColumn) {
                    this.query.append(Constants.NULLABLE).append("(").append(colDataType)
                            .append(")").append(",");
                } else {
                    this.query.append(colDataType).append(" ").append(Constants.NOT_NULLABLE).append(" ").append(",");
                }

                // Add column name to the set of column names.
                columnNames.add(columnName);
                // Record it in declaration order too. Only reached for
                // non-generated columns -- the generated-column branch above
                // exits via `continue`, which is deliberate: a generated column
                // is a pure function of the stored columns, so it adds nothing
                // to row identity, and it is not carried in the CDC payload for
                // the writer to compare.
                if (columnName != null) {
                    orderedColumnNames.add(columnName);
                    if (!isNullColumn) {
                        notNullColumnNames.add(stripBackticks(columnName));
                    }
                }
            }
        }
    }

    /**
     * Strips backticks so a column name from the parse tree can be compared
     * with one taken from an index-column list, which may quote differently.
     *
     * @param name a column name, possibly backtick-quoted.
     * @return the name without backticks, or null if the input was null.
     */
    private static String stripBackticks(String name) {
        return name == null ? null : name.replace("`", "");
    }

    /**
     * Splits a MySQL index column list into its individual column names.
     *
     * <p>The parse tree hands back the list already flattened, e.g.
     * {@code (a,b)} or {@code (a(10),b)}. Any prefix length is dropped: it
     * narrows the index, not the column, and plays no part in nullability.</p>
     *
     * @param indexColumns the raw index column list text.
     * @return the bare column names in declaration order.
     */
    private static List<String> splitIndexColumns(String indexColumns) {
        List<String> columns = new ArrayList<>();
        if (indexColumns == null || indexColumns.isEmpty()) {
            return columns;
        }
        String stripped = indexColumns.trim();
        if (stripped.startsWith("(") && stripped.endsWith(")")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        for (String part : stripped.split(",")) {
            String column = stripBackticks(part).trim().replaceAll("\\(\\d+\\)$", "");
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * Function to get the ClickHouse data type based on the MySQL data type in the CREATE TABLE statement.
     * It handles precision and scale for data types such as numeric and datetime.
     *
     * @param parsedDataType The parsed data type from the MySQL statement.
     * @param colDefTree The column definition context for retrieving the data type.
     * @param columnName The name of the column.
     * @return The corresponding ClickHouse data type as a string.
     */
    private String getClickHouseDataType(String parsedDataType, ParseTree colDefTree, String columnName) {
        int precision = 0;
        int scale = 0;

        String chDataType = null;
        MySqlParser.DataTypeContext dtc = ((MySqlParser.ColumnDefinitionContext) colDefTree).dataType();
        DataType dt = DataTypeConverter.getDataType(dtc);

        if (dt.name().equalsIgnoreCase("ENUM") || dt.name().equalsIgnoreCase("SET")) {
            // Skip precision and scale for ENUM and SET types
        } else if (parsedDataType.contains("(") && parsedDataType.contains(")") && parsedDataType.contains(",")) {
            String sanitizedDataType = parsedDataType.split("COMMENT")[0].trim();
            try {
                precision = Integer.parseInt(sanitizedDataType.substring(sanitizedDataType.indexOf("(") + 1, sanitizedDataType.indexOf(",")));
                scale = Integer.parseInt(sanitizedDataType.substring(sanitizedDataType.indexOf(",") + 1, sanitizedDataType.indexOf(")")));
            } catch (Exception e) {
                log.error("Error parsing precision, scale : columnName" + columnName);
            }
        } else if (parsedDataType.contains("(") && parsedDataType.contains(")") &&
                (containsIgnoreCase(parsedDataType, "datetime") || containsIgnoreCase(parsedDataType, "timestamp"))) {
            try {
                precision = Integer.parseInt(parsedDataType.substring(parsedDataType.indexOf("(") + 1, parsedDataType.indexOf(")")));
            } catch (Exception e) {
                log.error("Error parsing precision:ColumnName:" + columnName);
            }
        }

        // Convert MySQL data type to the equivalent ClickHouse data type.
        chDataType = DataTypeConverter.convertToString(this.config, columnName,
                scale, precision, dtc, this.userProvidedTimeZone);

        Map<String, String> defaultColumnDataTypeMap = loadDefaultColumnDataTypeMapping(this.config.originalsStrings());

        // Use a single null check with optional.
        if (defaultColumnDataTypeMap != null) {
            chDataType = defaultColumnDataTypeMap.getOrDefault(columnName, chDataType);
        }

        // column_type_override.direct.* takes highest priority (over default_column_datatype_mapping)
        if (this.config != null) {
            ColumnTypeOverrideConfig overrideConfig =
                    ColumnTypeOverrideConfig.fromProperties(this.config.originalsStrings());
            if (overrideConfig.hasOverrides()) {
                String cleanColumnName = columnName != null ? columnName.replace("`", "") : columnName;
                String cleanTableName = this.cleanTableName;
                Optional<String> directOverride =
                        overrideConfig.getDirectOverride(this.databaseName, cleanTableName, cleanColumnName);
                if (directOverride.isPresent()) {
                    chDataType = directOverride.get();
                }
            }
        }

        return chDataType;
    }

    /**
     * This function processes the addition of an index in the ALTER TABLE statement.
     * It parses the index name, type, columns, and options like the granularity of the index.
     *
     * @param tree The parse tree representing the ALTER TABLE ADD INDEX clause.
     */
    private void parseAddIndex(ParseTree tree) {

        // Add index col3_index(col3) TYPE minmax GRANULARITY 4;
        for (ParseTree columnChild : ((MySqlParser.AlterByAddIndexContext) tree).children) {

            if (columnChild instanceof MySqlParser.IfNotExistsContext) {
                // Ignore if "IF NOT EXISTS" is present, as it's not relevant for the query.
            } else if (columnChild instanceof MySqlParser.UidContext) {
                // The name of the index.
            } else if (columnChild instanceof MySqlParser.IndexTypeContext) {
                // The type of the index.
            } else if (columnChild instanceof MySqlParser.IndexColumnNamesContext) {
                // Process the column names in the index.
                for (ParseTree columnNameChild : ((MySqlParser.IndexColumnNamesContext) (columnChild)).children) {
                    // Column Name
                }
            } else if (columnChild instanceof MySqlParser.IndexOptionContext) {
                // Index options like comment, type, granularity, etc.
            }
        }
    }

    /**
     * This function handles the renaming of a column in the ALTER TABLE statement.
     * It appends the new column name to the query.
     *
     * @param tree The parse tree representing the ALTER TABLE RENAME COLUMN clause.
     */
    private void parseRenameColumn(ParseTree tree) {
        ListIterator<ParseTree> it = ((MySqlParser.AlterSpecificationContext) tree).children.listIterator();
        // this.query.append(" ").append(Constants.RENAME_COLUMN);
        while (it.hasNext()) {
            ParseTree child = it.next();
            if (child instanceof MySqlParser.UidContext) {
                // Append the column name to the query
                this.query.append(" ").append(child.getText());
            } else if (child instanceof TerminalNodeImpl) {
                // Append the terminal node text to the query
                this.query.append(" ").append(child.getText());
            }
        }
    }

    /**
     * This function processes an ALTER TABLE statement, handling column addition, modification, renaming,
     * and other operations like index creation and constraints.
     *
     * @param tree The parse tree representing the ALTER TABLE statement.
     */
    private void parseAlterTable(ParseTree tree) {

        String columnName = null;
        String columnType = null;
        String newColumnName = null;

        String modifier = Constants.ADD_COLUMN;
        String modifierWithNull = Constants.ADD_COLUMN_NULLABLE;

        String defaultModifier = null;

        StringBuffer columnPositionModifier = new StringBuffer();

        boolean isNullColumn = false;
        boolean isAlterChangeColumn = false;
        boolean nullExplicitlySet = false;

        // Determine the type of alter operation (Add, Modify, Rename, etc.)
        if (tree instanceof AlterByAddColumnContext) {
            modifier = Constants.ADD_COLUMN;
            modifierWithNull = Constants.ADD_COLUMN_NULLABLE;
            isNullColumn = true;

        } else if (tree instanceof MySqlParser.AlterByModifyColumnContext) {
            modifier = Constants.MODIFY_COLUMN;
            modifierWithNull = Constants.MODIFY_COLUMN_NULLABLE;
            // In MySQL, MODIFY COLUMN without an explicit NULL/NOT NULL constraint
            // makes the column nullable, so default to Nullable when the current
            // schema cannot be retrieved from ClickHouse.
            isNullColumn = true;
        } else if (tree instanceof MySqlParser.AlterByRenameColumnContext) {
            modifier = Constants.RENAME_COLUMN;
            modifierWithNull = Constants.RENAME_COLUMN_NULLABLE;

        } else if (tree instanceof MySqlParser.AlterByChangeColumnContext) {
            isAlterChangeColumn = true;
            modifier = Constants.MODIFY_COLUMN;
            modifierWithNull = Constants.MODIFY_COLUMN_NULLABLE;
            // Same MySQL semantics as MODIFY COLUMN above.
            isNullColumn = true;
        } else if (tree instanceof MySqlParser.AlterByAddIndexContext) {
            modifier = Constants.ADD_INDEX;
        } else {
            return;
        }

        ListIterator<ParseTree> it = ((MySqlParser.AlterSpecificationContext) tree).children.listIterator();

        while (it.hasNext()) {
            ParseTree columnChild = it.next();
            if (columnChild instanceof MySqlParser.UidContext) {
                columnName = columnChild.getText();
                if (isAlterChangeColumn) {
                    // Change column comes in this format ALTER TABLE change column oldcol newcol.
                    ParseTree newColumnChild = it.next();
                    newColumnName = newColumnChild.getText();
                }
            } else if (columnChild instanceof MySqlParser.ColumnDefinitionContext) {

                for (ParseTree columnDefChild : ((MySqlParser.ColumnDefinitionContext) columnChild).children) {
                    if (columnDefChild instanceof MySqlParser.NullColumnConstraintContext) {
                        nullExplicitlySet = true;
                        if (columnDefChild.getText().equalsIgnoreCase(Constants.NULL))
                            isNullColumn = true;
                        else if(columnDefChild.getText().equalsIgnoreCase(Constants.NOT_NULL)) {
                            // if (!modifier.equalsIgnoreCase(Constants.ADD_COLUMN))
                            {
                                isNullColumn = false;
                            }
                        }
                    } else if (columnDefChild instanceof MySqlParser.DefaultColumnConstraintContext) {
                        if (columnDefChild.getChildCount() >= 2) {
                            defaultModifier = "DEFAULT " + columnDefChild.getChild(1).getText();
                        }
                    } else if (columnDefChild instanceof MySqlParser.CommentColumnConstraintContext) {
                        // Ignore comment for now.
                    }
                    else {
                        columnType = columnDefChild.getText();
                        String chDataType = getClickHouseDataType(columnType, columnChild, columnName);
                        if (chDataType != null) {
                            columnType = chDataType;
                        }
                    }
                }
            } else if (columnChild instanceof TerminalNodeImpl) {
                String columnPosition = columnChild.getText();
                if (columnPosition.equalsIgnoreCase(Constants.AFTER)) {
                    if (it.hasNext()) {
                        columnPositionModifier.append(columnPosition).append(" ").append(it.next().getText());
                    }
                } else if (columnPosition.equalsIgnoreCase(Constants.FIRST)) {
                    columnPositionModifier.append(columnPosition);
                }
            }
        }

        // If null is not explicitly set, determine if the column is nullable.
        if (!nullExplicitlySet) {
            try {
                if (writer == null) {
                    log.error("Error with DB connection");
                    throw new SQLException("Error with DB connection");
                }
                else {
                    Map<String, Boolean> isNullableList = dbMetadata.getColumnsIsNullableForTable(tableName, writer.getConnection(), databaseName);
                    if (isNullableList.get(columnName) != null && isNullableList.get(columnName)) {
                        isNullColumn = true;
                    } else if (isNullableList.get(columnName) == null) {
                        isNullColumn = true;
                    } else {
                        isNullColumn = false;
                    }
                }
            } catch (Exception e) {
                log.error("Error retrieving NULL column schema from ClickHouse", e);
            }
        }

        // If column name and column type are defined, append them to the query.
        if (columnName != null && columnType != null)
            if (isNullColumn) {
                this.query.append(" ").append(String.format(modifierWithNull, columnName, columnType)).append(" ");
            }
            else {
                this.query.append(" ").append(String.format(modifier, columnName, columnType));
            }

        if (defaultModifier != null && defaultModifier.isEmpty() == false) {
            this.query.append(" ").append(defaultModifier);
        }

        if (columnPositionModifier.length() != 0) {
            this.query.append(" ").append(columnPositionModifier);
        }

        if (isAlterChangeColumn) {
            postProcessModifyColumn(this.tableName, columnName, newColumnName, columnType);
        }

        // Check for ALIAS companion column for ADD operations
        if (tree instanceof AlterByAddColumnContext && this.config != null) {
            ColumnTypeOverrideConfig overrideConfig =
                    ColumnTypeOverrideConfig.fromProperties(this.config.originalsStrings());
            if (overrideConfig.hasOverrides()) {
                String cleanTableName = this.cleanTableName;
                String cleanColumnName = columnName != null ? columnName.replace("`", "") : "";
                List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                        overrideConfig.getAliasOverrides(this.databaseName, cleanTableName);
                for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
                    if (entry.getColumn().equalsIgnoreCase(cleanColumnName)) {
                        // Append companion ALIAS column as additional ALTER TABLE statement
                        this.query.append("\n")
                                .append("ALTER TABLE ").append(this.tableName)
                                .append(" ADD COLUMN `").append(entry.getAliasColumnName()).append("` ")
                                .append(entry.getAliasType())
                                .append(" ALIAS ").append(entry.getExpression());
                    }
                }
            }
        }

        String trimmedQuery = this.query.toString().trim();
        this.query.delete(0, this.query.toString().length()).append(trimmedQuery);
    }

    /**
     * Function to create MODIFY column to rename the column name.
     *
     * @param tableName The name of the table being modified.
     * @param oldCol The old column name.
     * @param newCol The new column name.
     * @param dataType The data type of the column.
     */
    public void postProcessModifyColumn(String tableName, String oldCol, String newCol, String dataType) {
        this.query.append("\n");
        // If the tableName already includes the databaseName don't include databaseName in the query.
        if (tableName.contains(".")) {
            this.query.append(String.format("ALTER TABLE %s RENAME COLUMN %s to %s", tableName, oldCol, newCol));
        } else {
            this.query.append(String.format("ALTER TABLE `%s`.%s RENAME COLUMN %s to %s", databaseName, tableName, oldCol, newCol));
        }
    }

    @Override
    public void enterAlterTable(MySqlParser.AlterTableContext alterTableContext) {
        List<ParseTree> pt = alterTableContext.children;
        for (ParseTree tree : pt) {

            if (tree instanceof TableNameContext) {
                this.tableName = tree.getText();
                // If the table name already includes the database name don't include database name in the query.
                if (this.tableName.contains(".")) {
                    // Split database and table name.
                    String[] tableNameSplit = this.tableName.split("\\.");
                    this.query.append(String.format(Constants.ALTER_TABLE, "`" + databaseName+ "`." + tableNameSplit[1]));
                } else {
                    this.query.append(String.format(Constants.ALTER_TABLE, "`" + databaseName + "`." + this.tableName));
                }
            }

            if (tree instanceof AlterByAddColumnContext) {
                parseAlterTable(tree);

            } else if (tree instanceof MySqlParser.AlterByDropConstraintCheckContext) {
                // Drop Constraint.
                this.query.append(" ");
                for (ParseTree dropConstraintTree : ((MySqlParser.AlterByDropConstraintCheckContext) (tree)).children) {
                    if (dropConstraintTree instanceof MySqlParser.UidContext) {
                        this.query.append(String.format(Constants.DROP_CONSTRAINT, dropConstraintTree.getText()));
                    }
                }
            } else if (tree instanceof MySqlParser.AlterByModifyColumnContext) {
                parseAlterTable(tree);
            } else if (tree instanceof MySqlParser.AlterByDropColumnContext) {
                // Drop Column.
                this.query.append(" ");
                for (ParseTree dropColumnTree : ((MySqlParser.AlterByDropColumnContext) (tree)).children) {
                    if (dropColumnTree instanceof MySqlParser.UidContext) {
                        for (ParseTree dropColumnChild: ((MySqlParser.UidContext) dropColumnTree).children) {
                            if (dropColumnChild instanceof MySqlParser.SimpleIdContext || dropColumnChild instanceof TerminalNodeImpl) {
                                this.query.append(String.format(Constants.DROP_COLUMN, dropColumnChild.getText()));

                                // Check for ALIAS column companion drops
                                if (this.config != null) {
                                    ColumnTypeOverrideConfig overrideConfig =
                                            ColumnTypeOverrideConfig.fromProperties(this.config.originalsStrings());
                                    if (overrideConfig.hasOverrides()) {
                                        String cleanTableName = this.cleanTableName;
                                        String droppedColName = dropColumnChild.getText().replace("`", "");
                                        List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                                                overrideConfig.getAliasOverrides(this.databaseName, cleanTableName);
                                        for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
                                            if (entry.getColumn().equalsIgnoreCase(droppedColName)) {
                                                this.query.append(",");
                                                this.query.append(String.format(Constants.DROP_COLUMN,
                                                        entry.getAliasColumnName()));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (tree instanceof MySqlParser.AlterByRenameColumnContext) {
                parseRenameColumn(tree);
            } else if (tree instanceof MySqlParser.AlterByAddPrimaryKeyContext) {
                parseAlterTable(tree);
            } else if (tree instanceof MySqlParser.AlterByChangeColumnContext) {
                parseAlterTable(tree);
            } else if (tree instanceof MySqlParser.AlterByAddIndexContext) {
                parseAddIndex(tree);
            } else if (tree instanceof MySqlParser.AlterBySetAlgorithmContext
                    || tree instanceof MySqlParser.AlterByLockContext) {
                // ALGORITHM=/LOCK= are MySQL execution hints with no ClickHouse
                // equivalent, so they emit nothing. Drop the separator that was
                // emitted for them and keep walking: an ALTER may carry further
                // operations after the hint, e.g.
                //   ALTER TABLE t ADD COLUMN a INT, ALGORITHM=INSTANT,
                //                  ADD COLUMN b BIGINT, ALGORITHM=INSTANT
                // Terminating the walk here would silently discard every
                // operation after the first hint.
                log.info("ALGORITHM/LOCK clause not supported in ClickHouse, skipping clause");
                removeTrailingComma();
            } else if (tree instanceof TerminalNodeImpl) {
                if (((TerminalNodeImpl) tree).symbol.getType() == MySqlParser.COMMA) {
                    this.query.append(",");
                }
            } else if(tree instanceof MySqlParser.AlterByRenameContext) {
                parseAlterTableByRename(tableName, (MySqlParser.AlterByRenameContext) tree);
            }
        }
        // A hint in trailing position leaves the separator that preceded it
        // dangling once the hint itself emits nothing.
        removeTrailingComma();
    }

    /**
     * Drops a single trailing comma from the generated query, if present.
     * <p>
     * Separators are emitted eagerly as the ALTER clause list is walked, so a
     * clause that turns out to emit nothing (an ALGORITHM or LOCK hint) leaves
     * a dangling comma behind. No-op when the query is empty.
     */
    private void removeTrailingComma() {
        int length = this.query.length();
        if (length > 0 && this.query.charAt(length - 1) == ',') {
            this.query.deleteCharAt(length - 1);
        }
    }

    /**
     * This function processes the renaming of a table in the ALTER TABLE statement.
     * It appends the necessary SQL query to rename the table.
     *
     * @param originalTableName The original name of the table.
     * @param tree The parse tree representing the ALTER TABLE RENAME TABLE clause.
     */
    private void parseAlterTableByRename(String originalTableName, MySqlParser.AlterByRenameContext tree) {
        String newTableName = null;
        // Iterate over the children of the parse tree to find the new table name
        for (ParseTree alterByRenameChildren: tree.children) {
            if (alterByRenameChildren instanceof MySqlParser.UidContext) {
                newTableName = alterByRenameChildren.getText();
            } else if(alterByRenameChildren instanceof MySqlParser.FullIdContext) {
                newTableName = alterByRenameChildren.getText();
            }
        }

        // If the database name already includes the table name, don't include it in the query.
        if (originalTableName.contains(".")) {
            this.query.delete(0, this.query.toString().length()).append(String.format
                    (Constants.ALTER_RENAME_TABLE, originalTableName, newTableName));
        } else {
            this.query.delete(0, this.query.toString().length()).append(String.format
                    (Constants.ALTER_RENAME_TABLE, "`" + databaseName + "`." + originalTableName, "`" + databaseName + "`." + newTableName));
        }
    }

    /**
     * This function processes the addition of a check table constraint in the ALTER TABLE statement.
     * It appends the corresponding SQL query to add the check constraint.
     *
     * @param alterByAddCheckTableConstraintContext The context representing the ALTER TABLE ADD CHECK CONSTRAINT clause.
     */
    @Override
    public void enterAlterByAddCheckTableConstraint(MySqlParser.AlterByAddCheckTableConstraintContext alterByAddCheckTableConstraintContext) {
        // Append the relevant part of the query for the check constraint
        this.query.append(" ");
        for (ParseTree tree : alterByAddCheckTableConstraintContext.children) {
            this.parseTreeHelper(tree);
        }
    }

    /**
     * A helper function to recursively process each tree node in the ALTER TABLE statement.
     *
     * @param child The parse tree node to be processed.
     */
    private void parseTreeHelper(ParseTree child) {
        if (child instanceof MySqlParser.UidContext) {
            this.query.append(child.getText()).append(" ");
        } else if (child instanceof MySqlParser.ComparisonOperatorContext) {
            this.query.append(child.getText());
        } else if (child instanceof TerminalNodeImpl) {
            this.query.append(child.getText()).append(" ");
        } else if (child instanceof ParserRuleContext) {
            // Recursively process child nodes
            for (ParseTree child2 : ((ParserRuleContext) child).children) {
                this.parseTreeHelper(child2);
            }
        }
    }

    /**
     * This function processes the DROP TABLE statement.
     * It appends the necessary SQL query to drop the specified table.
     *
     * @param dropTableContext The context representing the DROP TABLE clause.
     */
    @Override
    public void enterDropTable(MySqlParser.DropTableContext dropTableContext) {
        log.debug("DROP TABLE enter");
        this.query.append(Constants.DROP_TABLE).append(" ");
        for (ParseTree child : dropTableContext.children) {
            if (child instanceof MySqlParser.TablesContext) {
                for (ParseTree tableNameChild : ((MySqlParser.TablesContext) child).children) {
                    if (tableNameChild instanceof MySqlParser.TableNameContext) {
                        String tableName = tableNameChild.getText();
                        if (tableName.contains(".")) {
                            String[] parts = tableName.split("\\.");
                            this.query.append(databaseName).append(".").append(parts[1]);
                        } else {
                            this.query.append(databaseName).append(".").append(tableName);
                        }
                    } else if (tableNameChild instanceof TerminalNodeImpl) {
                        this.query.append(tableNameChild.getText());
                    }
                }
            } else if (child instanceof MySqlParser.IfExistsContext) {
                this.query.append(Constants.IF_EXISTS);
            }
        }
    }

    /**
     * This function processes the RENAME TABLE statement.
     * It appends the corresponding SQL query to rename the table.
     *
     * @param renameTableContext The context representing the RENAME TABLE clause.
     */
    @Override
    public void enterRenameTable(MySqlParser.RenameTableContext renameTableContext) {
        this.query.append(Constants.RENAME_TABLE).append(" ");
        String originalTableName = null;
        String newTableName = null;
        for (ParseTree child : renameTableContext.children) {
            if (child instanceof MySqlParser.RenameTableClauseContext) {
                List<ParseTree> renameTableContextChildren = ((MySqlParser.RenameTableClauseContext) child).children;

                if (renameTableContextChildren.size() >= 3) {
                    originalTableName = renameTableContextChildren.get(0).getText();
                    newTableName = renameTableContextChildren.get(2).getText();
                    // If the table name already includes the database name don't include it in the query.
                    if (originalTableName.contains(".") || newTableName.contains(".")) {
                        // Split database and table name.
                        String origTable = originalTableName.contains(".")
                                ? originalTableName.split("\\.")[1] : originalTableName;
                        String newTable = newTableName.contains(".")
                                ? newTableName.split("\\.")[1] : newTableName;
                        this.query.append(this.databaseName).append(".").append(origTable).append(" to ").append(this.databaseName)
                                .append(".").append(newTable);
                    } else {
                        this.query.append(databaseName).append(".").append(originalTableName).append(" to ").append(databaseName)
                                .append(".").append(newTableName);
                    }
                }
            } else if(child instanceof TerminalNodeImpl) {
                if (((TerminalNodeImpl) child).symbol.getType() == MySqlParser.COMMA) {
                    this.query.append(",");
                }
            }
        }
    }

    /**
     * This function processes the TRUNCATE TABLE statement.
     * It appends the necessary SQL query to truncate the specified table.
     *
     * @param truncateTableContext The context representing the TRUNCATE TABLE clause.
     */
    @Override
    public void enterTruncateTable(MySqlParser.TruncateTableContext truncateTableContext) {
        for (ParseTree child : truncateTableContext.children) {
            if (child instanceof MySqlParser.TableNameContext) {
                String tableName = child.getText();
                if (tableName.contains(".")) {
                    String[] parts = tableName.split("\\.");
                    this.query.append(String.format(Constants.TRUNCATE_TABLE,
                            "`" + databaseName + "`." + parts[1]));
                } else {
                    this.query.append(String.format(Constants.TRUNCATE_TABLE,
                            "`" + databaseName + "`." + tableName));
                }
            }
        }
    }
}
