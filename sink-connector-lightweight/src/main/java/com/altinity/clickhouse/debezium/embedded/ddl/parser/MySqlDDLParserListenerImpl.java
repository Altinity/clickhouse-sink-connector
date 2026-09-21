package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DDLReplicationException;
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
import com.altinity.clickhouse.sink.connector.db.KeylessTableWarning;
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
     * Extracts the generation expression text from a
     * {@code GENERATED ALWAYS AS (expr)} clause, with MySQL charset introducers
     * stripped so the result is valid ClickHouse.
     * <p>
     * Shared by the CREATE TABLE and ALTER TABLE column paths so the two cannot
     * diverge: a generated column's expression must become a {@code DEFAULT}
     * expression, and must NEVER be mistaken for the column's data type. The
     * {@code IsNullPredicateContext} branch mirrors the grammar quirk the CREATE
     * path handles for expressions such as {@code (a IS NULL)}.
     *
     * @param ctx the {@code GeneratedColumnConstraintContext} parse node.
     * @return the expression text (charset introducers stripped), or "" if none.
     */
    static String extractGeneratedExpression(MySqlParser.GeneratedColumnConstraintContext ctx) {
        String expr = "";
        for (ParseTree child : ctx.children) {
            if (child instanceof MySqlParser.ExpressionContext) {
                for (ParseTree exprChild : ((MySqlParser.ExpressionContext) child).children) {
                    if (exprChild instanceof MySqlParser.IsNullPredicateContext) {
                        for (ParseTree inner : ((MySqlParser.IsNullPredicateContext) exprChild).children) {
                            if (inner instanceof MySqlParser.ExpressionAtomPredicateContext) {
                                expr = inner.getText();
                            }
                        }
                    } else {
                        expr = exprChild.getText();
                    }
                }
            }
        }
        return stripCharsetIntroducers(expr);
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
     * Clean table name (backticks and database prefix stripped). Set from the
     * name the caller passes and RE-COMPUTED in {@code enterAlterTable} from the
     * table named in the statement, because the production caller passes ""
     * (Spec 06.03 §3.3).
     */
    String cleanTableName;

    /**
     * How the existing ClickHouse schema of an ALTER's target table is read.
     * Null until first needed; then the injected lookup or the
     * DBMetadata-backed default (Spec 06.03 §3.4).
     */
    private TargetSchemaLookup targetSchemaLookup;

    /**
     * Per-statement cache of the target table's column nullability, filled
     * lazily from {@link #targetSchemaLookup}; reset in {@code enterAlterTable}.
     */
    private Map<String, Boolean> targetColumnNullability;

    /**
     * Per-statement cache of the target table's sorting-key column types,
     * filled lazily from {@link #targetSchemaLookup}; reset in
     * {@code enterAlterTable}.
     */
    private Map<String, String> targetSortingKeyTypes;

    /**
     * Columns named by a table-level {@code PRIMARY KEY (...)} of the CREATE
     * TABLE being parsed (lower-cased, backticks stripped). MySQL makes them
     * NOT NULL implicitly, so the translator must too (Spec 06.05 §3.3).
     */
    private final Set<String> tableLevelPrimaryKeyColumns = new HashSet<>();

    /**
     * Overrides how the target table's existing schema is read. Null selects
     * the DBMetadata-backed default.
     *
     * @param targetSchemaLookup the lookup to use, or null.
     */
    public void setTargetSchemaLookup(TargetSchemaLookup targetSchemaLookup) {
        this.targetSchemaLookup = targetSchemaLookup;
    }

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
        // Both forms -- `CREATE TABLE n LIKE o` and `CREATE TABLE n (LIKE o)` --
        // name the NEW table first and the source table second.
        List<String> tables = new ArrayList<>();
        for (ParseTree tree : copyCreateTableContext.children) {
            if (tree instanceof MySqlParser.TableNameContext) {
                tables.add(tree.getText());
            }
        }
        if (tables.size() < 2) {
            return;
        }
        // IF NOT EXISTS for the same reason as the column CREATE: a replay
        // after a restart must be a no-op, not Code: 57 TABLE_ALREADY_EXISTS.
        // BOTH operands are re-qualified with the destination database; a
        // source database prefix on either (`CREATE TABLE n LIKE db2.o`) is
        // not a ClickHouse database and used to yield `db`.db2.o.
        this.query.append(Constants.CREATE_TABLE).append(" ").append(Constants.IF_NOT_EXISTS)
                .append(qualifyWithDestinationDatabase(tables.get(0))).append(" ")
                .append(Constants.AS).append(" ")
                .append(qualifyWithDestinationDatabase(tables.get(1)));
    }

    /**
     * Qualifies a source table identifier with the destination database,
     * dropping any source database prefix: {@code db2.o} and {@code o} both
     * become {@code `db`.o}.
     */
    private String qualifyWithDestinationDatabase(String sourceTableName) {
        String table = sourceTableName;
        if (table.contains(".")) {
            table = table.split("\\.")[1];
        }
        return "`" + this.databaseName + "`." + table;
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

        // True when the emitted sorting key can name NULLABLE columns, which
        // ClickHouse rejects outright unless allow_nullable_key is enabled (see
        // the settings block at the end of this method).
        //
        // Only the all-columns fallback can: a PRIMARY KEY is NOT NULL by MySQL's
        // own rule, and a UNIQUE key is adopted below only when every one of its
        // columns is NOT NULL. Emitting the setting where it is not needed would
        // silently permit nullable keys ClickHouse is right to reject.
        boolean nullableSortingKey = false;

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

        // Neither a PRIMARY KEY nor a NOT NULL UNIQUE key: the table has no
        // declared row identity (MySQL's `alembic_version` is the canonical
        // example). ORDER BY tuple() would make every row compare equal, so
        // ReplacingMergeTree would keep exactly ONE row for the entire table --
        // a silent, total data loss that is invisible while the table holds a
        // single row and appears the moment it grows to two.
        //
        // The identity such a table ought to have comes from MySQL: the
        // GENERATED INVISIBLE PRIMARY KEY (8.0.30+, my_row_id), which is part
        // of the table definition and so arrives here as an ordinary keyed
        // table. Until the source has one, the sorting key is every stored
        // (non-generated) column in declaration order -- exactly what the
        // record-schema creation path builds (ClickHouseAutoCreateTable
        // .keylessSortingKey, Spec 08.05 §3.2), so both creation paths give
        // the same source table the same identity (Spec 06.05 §3.6). Rows are
        // then distinguished by value, which is MySQL's own semantics for a
        // table without an identity.
        //
        // The cost: ClickHouse forbids MODIFY/RENAME/DROP of a sorting-key
        // column, so later DDL on such a table meets the sorting-key policy
        // (Spec 06.05 §3.4) -- a suppressed clause or a loud, named rebuild.
        // That is recoverable; the row loss of an empty key is not. A hash of
        // the row's values is NOT an alternative: a column it names cannot be
        // dropped (Code: 44) and a column added later is absent from it.
        //
        // KeylessTablePreflight reports the table at startup; the banner here
        // repeats the fix at CREATE time so it cannot be missed.
        //
        // The schema-override primary_key is the operator's escape hatch and
        // wins over every derived key on both creation paths; when it is set
        // the fallback (and its allow_nullable_key) must not run.
        SchemaOverrideConfig.Table tableConfig = SchemaOverrideConfig.getTableConfig(this.databaseName,
                this.tableName, this.config.originalsStrings());
        boolean overridePrimaryKey = tableConfig.getPrimaryKey() != null && !tableConfig.getPrimaryKey().isEmpty();
        if (orderByColumns.length() == 0 && !overridePrimaryKey) {
            if (orderedColumnNames.isEmpty()) {
                throw new DDLReplicationException(String.format(
                        "Cannot derive a sorting key for `%s`.%s: the CREATE TABLE declares no stored "
                                + "(non-generated) column. Refusing to create a ReplacingMergeTree table "
                                + "with ORDER BY tuple(), which would collapse every row into one. "
                                + "Source DDL: [%s]",
                        this.databaseName, this.tableName, this.originalSql), null);
            }
            log.error(KeylessTableWarning.banner(this.databaseName, this.tableName));
            for (String column : orderedColumnNames) {
                if (!notNullColumnNames.contains(stripBackticks(column))) {
                    nullableSortingKey = true;
                }
            }
            log.warn("Table {}.{} has no PRIMARY KEY and no NOT NULL UNIQUE key; using every stored "
                            + "column as the ReplacingMergeTree sorting key so distinct rows stay "
                            + "distinct: {}. Rows identical in every column will still collapse, and a "
                            + "column added later is not part of this key.",
                    this.databaseName, this.tableName, orderedColumnNames);
            orderByColumns.append("(").append(String.join(",", orderedColumnNames)).append(")");
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

        if (DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine) {
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(isDeletedColumn).append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
        } else {
            this.query.append("`").append(SIGN_COLUMN).append("` ").append(SIGN_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE);
        }

        this.query.append(")");

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

        if (overridePrimaryKey) {
            // Use the primary_key from tableConfig if it exists
            this.query.append(Constants.ORDER_BY).append(tableConfig.getPrimaryKey());
        } else {
            // orderByColumns is never empty here: a declared PRIMARY KEY, an
            // adopted UNIQUE key or the all-columns fallback filled it above,
            // so ORDER BY tuple() is never emitted (Spec 06.05 §3.6).
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
        

        // The all-columns fallback sorting key necessarily includes every
        // nullable column of the source table, and ClickHouse rejects a nullable
        // sorting key outright (Code: 44 ILLEGAL_COLUMN) unless
        // allow_nullable_key is enabled.
        //
        // The other two paths never need it, and must not get it: a PRIMARY KEY
        // is NOT NULL by MySQL's own rule, and a UNIQUE key is adopted above only
        // when every one of its columns is NOT NULL.
        //
        // Append to any user-supplied settings rather than replacing them.
        String tableSettings = tableConfig.getSettings();
        boolean hasUserSettings = tableSettings != null && !tableSettings.isEmpty();
        if (nullableSortingKey && !containsIgnoreCase(hasUserSettings ? tableSettings : "", ALLOW_NULLABLE_KEY)) {
            this.query.append(Constants.SETTINGS);
            if (hasUserSettings) {
                this.query.append(tableSettings).append(",");
            }
            this.query.append(ALLOW_NULLABLE_KEY).append("=1");
        } else if (hasUserSettings) {
            // Use the settings from tableConfig if it exists
            this.query.append(Constants.SETTINGS).append(tableSettings);
        }
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

        // MySQL makes the columns of a table-level PRIMARY KEY (id, ...) NOT
        // NULL implicitly, whether or not the column itself is written so. The
        // key is declared AFTER the columns, so collect it before the columns
        // are emitted; otherwise a `id INT, PRIMARY KEY (id)` table gets a
        // Nullable sorting key, which ClickHouse rejects with Code: 44
        // (Spec 06.05 §3.3).
        tableLevelPrimaryKeyColumns.clear();
        for (ParseTree tree : pt) {
            if (tree instanceof MySqlParser.CreateDefinitionsContext) {
                for (ParseTree subtree : ((MySqlParser.CreateDefinitionsContext) tree).children) {
                    if (subtree instanceof MySqlParser.ConstraintDeclarationContext) {
                        for (ParseTree constraintTree : ((MySqlParser.ConstraintDeclarationContext) subtree).children) {
                            if (constraintTree instanceof MySqlParser.PrimaryKeyTableConstraintContext) {
                                for (ParseTree primaryKeyTree : ((MySqlParser.PrimaryKeyTableConstraintContext) constraintTree).children) {
                                    if (primaryKeyTree instanceof MySqlParser.IndexColumnNamesContext) {
                                        for (String column : indexColumnNames((MySqlParser.IndexColumnNamesContext) primaryKeyTree)) {
                                            tableLevelPrimaryKeyColumns.add(stripBackticks(column).toLowerCase());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Always emit CREATE TABLE IF NOT EXISTS. The ClickHouse side is a
        // replica of the source table, so a CREATE for a table that is already
        // present is a no-op by definition. A bare CREATE TABLE made replay of
        // a snapshot/binlog CREATE fail with Code: 57 TABLE_ALREADY_EXISTS,
        // which DBMetadata.executeSystemQuery treats as retryable: it blocks
        // the CDC thread for the whole errors.max.retries budget (linear
        // backoff) and every event arriving in that window is lost.
        this.query.append(Constants.CREATE_TABLE).append(" ").append(Constants.IF_NOT_EXISTS);
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
                // Already emitted unconditionally above; swallow the source
                // clause so the guard is not duplicated in the output.
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
                                        // Bare column names from the parse tree, never the
                                        // flattened text: `PRIMARY KEY (id ASC)` would
                                        // otherwise become ORDER BY (idASC) (Code: 47).
                                        List<String> primaryKeyColumns =
                                                indexColumnNames((MySqlParser.IndexColumnNamesContext) primaryKeyTree);
                                        if (!primaryKeyColumns.isEmpty()) {
                                            orderByColumns.append("(").append(String.join(",", primaryKeyColumns)).append(")");
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
                                            List<String> uniqueColumns =
                                                    indexColumnNames((MySqlParser.IndexColumnNamesContext) uniqueKeyTree);
                                            if (!uniqueColumns.isEmpty()) {
                                                uniqueKeyColumns.append("(").append(String.join(",", uniqueColumns)).append(")");
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

                // SERIAL is BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE
                // (Spec 07.01 §3.2 rule 5): NOT NULL, and the identity of a
                // table that declares no PRIMARY KEY.
                if (isSerial(((MySqlParser.ColumnDefinitionContext) colDefTree).dataType())) {
                    isNullColumn = false;
                    if (uniqueKeyColumns.length() == 0 && columnName != null) {
                        uniqueKeyColumns.append(columnName);
                    }
                }

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
                    } else if (isAutoIncrement(colDefinitionChildTree)) {
                        // MySQL forbids a nullable AUTO_INCREMENT column, so the
                        // constraint implies NOT NULL whether or not it is written
                        // (Spec 06.05 §3.6 rule 3). Matters for the UNIQUE-key and
                        // all-columns sorting keys below.
                        isNullColumn = false;
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

                // A column of a table-level PRIMARY KEY is NOT NULL in MySQL
                // whether or not it is written so (Spec 06.05 §3.3).
                if (columnName != null
                        && tableLevelPrimaryKeyColumns.contains(stripBackticks(columnName).toLowerCase())) {
                    isNullColumn = false;
                }

                if (isGeneratedColumn) {
                    // For generated columns, handle NULL and NOT NULL constraints.
                    if (isNullColumn) {
                        this.query.append(Constants.NULLABLE).append("(").append(colDataType).append(")");
                    } else {
                        this.query.append(colDataType);
                    }

                    // DEFAULT, not MATERIALIZED. A MATERIALIZED column REJECTS
                    // an INSERT that names it (Code: 44 ILLEGAL_COLUMN), and
                    // Debezium carries generated columns in the row image --
                    // so the value MySQL computed can never be replicated, and
                    // the replica silently keeps its own locally-derived answer
                    // whenever the two expressions disagree. DEFAULT keeps the
                    // same derive-when-omitted behaviour while accepting the
                    // binlog value, so the source stays authoritative.
                    this.query.append(" ").append(Constants.GENERATED_COLUMN_KIND)
                            .append(" ").append(generatedColumn).append(",");
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
     * True for an {@code AUTO_INCREMENT} column constraint. The grammar puts
     * {@code AUTO_INCREMENT} and {@code ON UPDATE CURRENT_TIMESTAMP} in the
     * same alternative, so the token itself has to be checked.
     */
    private static boolean isAutoIncrement(ParseTree constraint) {
        return constraint instanceof MySqlParser.AutoIncrementColumnConstraintContext
                && ((MySqlParser.AutoIncrementColumnConstraintContext) constraint).AUTO_INCREMENT() != null;
    }

    /** True for the {@code SERIAL} pseudo-type (Spec 07.01 §3.2 rule 5). */
    private static boolean isSerial(MySqlParser.DataTypeContext dataType) {
        return dataType instanceof MySqlParser.SimpleDataTypeContext
                && ((MySqlParser.SimpleDataTypeContext) dataType).SERIAL() != null;
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
     * Reads the column names of an index column list from the parse tree.
     *
     * <p>{@code getText()} on the list flattens every token together, so
     * {@code PRIMARY KEY (id ASC, name(10) DESC)} would yield
     * {@code (idASC,name(10)DESC)}. Only the column identifier of each entry
     * is taken; the sort direction and the prefix length narrow the MySQL
     * index and play no part in the ClickHouse sorting key.</p>
     *
     * @param ctx the index column list.
     * @return the column names (quoting preserved) in declaration order.
     */
    private static List<String> indexColumnNames(MySqlParser.IndexColumnNamesContext ctx) {
        List<String> columns = new ArrayList<>();
        for (ParseTree child : ctx.children) {
            if (child instanceof MySqlParser.IndexColumnNameContext) {
                MySqlParser.IndexColumnNameContext entry = (MySqlParser.IndexColumnNameContext) child;
                String name = null;
                for (ParseTree part : entry.children) {
                    if (part instanceof MySqlParser.UidContext) {
                        name = part.getText();
                        break;
                    }
                    if (part instanceof TerminalNodeImpl
                            && ((TerminalNodeImpl) part).symbol.getType() == MySqlParser.STRING_LITERAL) {
                        name = part.getText();
                        break;
                    }
                }
                columns.add(name != null ? name : entry.getText());
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
        } else if (parsedDataType.contains("(") && parsedDataType.contains(")")
                && (dt.jdbcType() == java.sql.Types.DECIMAL || dt.jdbcType() == java.sql.Types.NUMERIC)) {
            // DECIMAL(M) / DEC / NUMERIC / FIXED with a precision but no scale
            // means DECIMAL(M, 0) in MySQL. Emitting a bare `Decimal` here made
            // it Decimal(10, 0) in ClickHouse, which rejects any value with more
            // than ten digits (Code: 69). The declared dimension is parsed from
            // the text; the resolver's length() is only a fallback because it
            // reports the DECIMAL default (10) for a one-dimension declaration
            // (Spec 06.04 §3.5).
            Matcher dimension = Pattern.compile("\\((\\d+)\\)").matcher(parsedDataType);
            if (dimension.find()) {
                precision = Integer.parseInt(dimension.group(1));
            } else if (dt.length() > 0) {
                precision = (int) dt.length();
            }
            scale = 0;
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
     * This function handles the renaming of a column in the ALTER TABLE statement.
     * It appends the new column name to the query.
     *
     * @param tree The parse tree representing the ALTER TABLE RENAME COLUMN clause.
     */
    private void parseRenameColumn(ParseTree tree) {
        ListIterator<ParseTree> it = ((MySqlParser.AlterSpecificationContext) tree).children.listIterator();
        // this.query.append(" ").append(Constants.RENAME_COLUMN);
        // This path echoes the source tokens verbatim (RENAME, COLUMN, the two
        // names), so the existence guard has to be injected as the COLUMN
        // keyword goes past -- it cannot come from the RENAME_COLUMN constant
        // the way the other clauses get it.
        //
        // Without it, replaying an already-applied rename fails: the old name
        // no longer exists, so ClickHouse returns Code: 10
        // NOT_FOUND_COLUMN_IN_BLOCK "Cannot find column `x` to rename"
        // (measured on 24.8.14). DDL is retried indefinitely, so that single
        // failure stalls the ENTIRE replication stream. Debezium flushes
        // offsets periodically, so any restart can re-deliver DDL already
        // applied downstream.
        boolean guardEmitted = false;
        boolean oldNameSeen = false;
        while (it.hasNext()) {
            ParseTree child = it.next();
            if (child instanceof MySqlParser.UidContext) {
                String name = child.getText();
                if (!oldNameSeen) {
                    oldNameSeen = true;
                    // The OLD name must exist in ClickHouse: resolve MySQL's
                    // case-insensitive spelling to the case-sensitive one
                    // (Spec 06.03 §3.3) and refuse to rename a sorting-key
                    // column, which ClickHouse rejects with Code: 524
                    // (Spec 06.05 §3.4).
                    name = resolveExistingColumnName(name);
                    String keyType = targetSortingKeyTypes().get(stripBackticks(name));
                    if (keyType != null) {
                        throw keyColumnNotRepresentable(name, keyType, null,
                                "cannot be renamed (ALTER RENAME of a key column)");
                    }
                }
                // Append the column name to the query
                this.query.append(" ").append(name);
            } else if (child instanceof TerminalNodeImpl) {
                // Append the terminal node text to the query
                this.query.append(" ").append(child.getText());
                if (!guardEmitted && "COLUMN".equalsIgnoreCase(child.getText())) {
                    this.query.append(" ").append(Constants.IF_EXISTS.trim());
                    guardEmitted = true;
                }
            }
        }
    }

    /** Which single-column ALTER clause is being translated. */
    private enum ColumnClause { ADD, MODIFY, CHANGE }

    /**
     * Translates one ADD COLUMN / MODIFY COLUMN / CHANGE COLUMN clause: gathers
     * the column name(s), the column definition and the FIRST/AFTER position
     * from the clause's children and hands them to
     * {@link #translateColumnClause}.
     *
     * @param tree The parse tree representing the ALTER TABLE clause.
     */
    private void parseAlterTable(ParseTree tree) {
        ColumnClause clause;
        if (tree instanceof AlterByAddColumnContext) {
            clause = ColumnClause.ADD;
        } else if (tree instanceof MySqlParser.AlterByModifyColumnContext) {
            clause = ColumnClause.MODIFY;
        } else if (tree instanceof MySqlParser.AlterByChangeColumnContext) {
            clause = ColumnClause.CHANGE;
        } else {
            return;
        }

        String columnName = null;
        String newColumnName = null;
        MySqlParser.ColumnDefinitionContext columnDefinition = null;
        StringBuilder columnPositionModifier = new StringBuilder();

        ListIterator<ParseTree> it = ((MySqlParser.AlterSpecificationContext) tree).children.listIterator();
        while (it.hasNext()) {
            ParseTree columnChild = it.next();
            if (columnChild instanceof MySqlParser.UidContext) {
                columnName = columnChild.getText();
                if (clause == ColumnClause.CHANGE) {
                    // Change column comes in this format ALTER TABLE change column oldcol newcol.
                    newColumnName = it.next().getText();
                }
            } else if (columnChild instanceof MySqlParser.ColumnDefinitionContext) {
                columnDefinition = (MySqlParser.ColumnDefinitionContext) columnChild;
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

        if (columnName == null || columnDefinition == null) {
            return;
        }
        translateColumnClause(clause, columnName, newColumnName, columnDefinition, columnPositionModifier.toString());
    }

    /**
     * {@code ADD COLUMN (a INT, b INT)}: every {@code (uid, columnDefinition)}
     * pair goes through the single ADD COLUMN logic (Spec 06.04 §3.1).
     *
     * @param ctx       the parenthesised ADD COLUMN list.
     * @param headerEnd index at which the "ALTER TABLE <table>" header ends.
     */
    private void parseAddColumns(MySqlParser.AlterByAddColumnsContext ctx, int headerEnd) {
        List<MySqlParser.UidContext> names = ctx.uid();
        List<MySqlParser.ColumnDefinitionContext> definitions = ctx.columnDefinition();
        for (int i = 0; i < names.size() && i < definitions.size(); i++) {
            if (i > 0) {
                appendClauseSeparator(headerEnd);
            }
            translateColumnClause(ColumnClause.ADD, names.get(i).getText(), null, definitions.get(i), "");
        }
    }

    /**
     * {@code ADD (a INT, INDEX idx (a), ...)}: the column declarations go
     * through the single ADD COLUMN logic; index and constraint declarations
     * have no ClickHouse equivalent and are skipped (Spec 06.03 §3.2).
     *
     * @param ctx       the parenthesised definition list.
     * @param headerEnd index at which the "ALTER TABLE <table>" header ends.
     */
    private void parseAddDefinitions(MySqlParser.AlterByAddDefinitionsContext ctx, int headerEnd) {
        boolean first = true;
        for (MySqlParser.CreateDefinitionContext definition : ctx.createDefinition()) {
            if (definition instanceof MySqlParser.ColumnDeclarationContext) {
                MySqlParser.ColumnDeclarationContext column = (MySqlParser.ColumnDeclarationContext) definition;
                if (!first) {
                    appendClauseSeparator(headerEnd);
                }
                first = false;
                translateColumnClause(ColumnClause.ADD, column.fullColumnName().getText(), null,
                        column.columnDefinition(), "");
            } else {
                log.info("ALTER TABLE ADD (...) definition [{}] is an index or constraint, which is not "
                        + "representable in ClickHouse; skipping it", definition.getText());
            }
        }
    }

    /**
     * Translates one column clause into the ClickHouse ALTER body: type,
     * nullability (Spec 06.05), DEFAULT (Spec 06.04 §3.2), position, the
     * sorting-key policy (Spec 06.05 §3.4) and, for CHANGE, the separate
     * RENAME statement (Spec 06.04 §3.1).
     *
     * @param clause                 which clause this is.
     * @param columnName             the (old) column name as written in MySQL.
     * @param newColumnName          the new name for CHANGE, else null.
     * @param columnDefinition       the column definition (type and constraints).
     * @param columnPositionModifier "FIRST" / "AFTER x" or "".
     */
    private void translateColumnClause(ColumnClause clause, String columnName, String newColumnName,
                                       MySqlParser.ColumnDefinitionContext columnDefinition,
                                       String columnPositionModifier) {
        boolean isAlterChangeColumn = clause == ColumnClause.CHANGE;
        if (clause != ColumnClause.ADD) {
            // The column already exists in ClickHouse: emit its ClickHouse
            // spelling, not MySQL's case-insensitive one (Spec 06.03 §3.3).
            columnName = resolveExistingColumnName(columnName);
        }
        // CHANGE COLUMN c c <type> is a MODIFY: a self-rename is rejected by
        // ClickHouse with Code: 15 DUPLICATE_COLUMN.
        boolean renames = isAlterChangeColumn && newColumnName != null
                && !stripBackticks(columnName).equals(stripBackticks(newColumnName));

        String modifier;
        String modifierWithNull;
        // In MySQL, ADD/MODIFY/CHANGE COLUMN without an explicit NULL/NOT NULL
        // constraint makes the column nullable, so default to Nullable when the
        // current schema cannot be retrieved from ClickHouse.
        boolean isNullColumn = true;
        switch (clause) {
            case ADD:
                modifier = Constants.ADD_COLUMN;
                modifierWithNull = Constants.ADD_COLUMN_NULLABLE;
                break;
            case CHANGE:
                if (renames) {
                    // The MODIFY half names the OLD column, which is gone once
                    // the RENAME half has been applied, so a replay would fail
                    // with Code: 10 without the guard (Spec 06.04 §3.1).
                    modifier = Constants.MODIFY_COLUMN_IF_EXISTS;
                    modifierWithNull = Constants.MODIFY_COLUMN_IF_EXISTS_NULLABLE;
                } else {
                    modifier = Constants.MODIFY_COLUMN;
                    modifierWithNull = Constants.MODIFY_COLUMN_NULLABLE;
                }
                break;
            default:
                modifier = Constants.MODIFY_COLUMN;
                modifierWithNull = Constants.MODIFY_COLUMN_NULLABLE;
                break;
        }

        String columnType = null;
        String defaultModifier = null;
        boolean nullExplicitlySet = false;

        for (ParseTree columnDefChild : columnDefinition.children) {
            if (columnDefChild instanceof MySqlParser.DataTypeContext) {
                // The type comes from the data-type node only. Deriving it
                // again from every later constraint (AUTO_INCREMENT, ON UPDATE
                // CURRENT_TIMESTAMP, COLLATE, ...) lost the declared precision:
                // DATETIME(6) ... ON UPDATE CURRENT_TIMESTAMP(6) came out as
                // DateTime64(0, 0).
                String chDataType = getClickHouseDataType(columnDefChild.getText(), columnDefinition, columnName);
                columnType = chDataType != null ? chDataType : columnDefChild.getText();
                if (clause == ColumnClause.ADD && isSerial((MySqlParser.DataTypeContext) columnDefChild)) {
                    // SERIAL implies NOT NULL (Spec 07.01 §3.2 rule 5).
                    nullExplicitlySet = true;
                    isNullColumn = false;
                }
            } else if (columnDefChild instanceof MySqlParser.NullColumnConstraintContext) {
                nullExplicitlySet = true;
                if (columnDefChild.getText().equalsIgnoreCase(Constants.NULL))
                    isNullColumn = true;
                else if(columnDefChild.getText().equalsIgnoreCase(Constants.NOT_NULL)) {
                    // Honor NOT NULL only for ADD COLUMN. A brand-new
                    // column has no existing rows to violate the
                    // constraint, so ClickHouse accepts a non-Nullable
                    // ADD.
                    //
                    // For MODIFY/CHANGE COLUMN it is unsafe: when the
                    // column already exists as Nullable in ClickHouse --
                    // which is exactly what this translator emits for a
                    // preceding ADD COLUMN in the same migration --
                    // converting Nullable -> non-Nullable requires a
                    // DEFAULT expression or ClickHouse rejects it with
                    //   Code: 36 BAD_ARGUMENTS "Cannot convert column
                    //   '<c>' from nullable type ... to non-nullable
                    //   type ... Please specify DEFAULT expression in
                    //   ALTER MODIFY COLUMN statement" (measured on
                    //   24.8.14). DDL is retried indefinitely, so that
                    //   single failure stalls the ENTIRE stream.
                    //
                    // Keeping the column Nullable loses no source value
                    // (Nullable(T) is a superset of T), needs no
                    // fabricated DEFAULT that would overwrite existing
                    // rows, and is checksum-safe because the comparison
                    // is value-level, not nullability-level. A MODIFY of
                    // an already non-Nullable column to Nullable is a
                    // widening ClickHouse accepts without a DEFAULT.
                    if (clause == ColumnClause.ADD) {
                        isNullColumn = false;
                    }
                }
            } else if (isAutoIncrement(columnDefChild) && clause == ColumnClause.ADD) {
                // AUTO_INCREMENT implies NOT NULL (Spec 06.05 §3.6 rule 3); a
                // brand-new column has no existing rows to violate it.
                nullExplicitlySet = true;
                isNullColumn = false;
            } else if (columnDefChild instanceof MySqlParser.DefaultColumnConstraintContext) {
                defaultModifier = translateDefault((MySqlParser.DefaultColumnConstraintContext) columnDefChild,
                        columnName);
            } else if (columnDefChild instanceof MySqlParser.CommentColumnConstraintContext) {
                // Ignore comment for now.
            } else if (columnDefChild instanceof MySqlParser.GeneratedColumnConstraintContext) {
                // GENERATED ALWAYS AS (expr) on an ALTER: map the
                // generation expression to a DEFAULT expression, NOT to
                // the column type. Without this branch the clause fell
                // into the catch-all `else` below and OVERWROTE
                // columnType with the raw expression text, producing
                // malformed DDL like "ADD COLUMN c AS(a+b)" instead of
                // "ADD COLUMN c Int32 DEFAULT a+b". This mirrors the
                // CREATE TABLE path (Constants.GENERATED_COLUMN_KIND =
                // DEFAULT): the column keeps its declared type, and the
                // source value still wins because Debezium carries the
                // generated column's value in the row image (a
                // MATERIALIZED column would reject that INSERT, Code 44).
                String genExpr = extractGeneratedExpression(
                        (MySqlParser.GeneratedColumnConstraintContext) columnDefChild);
                if (!genExpr.isEmpty()) {
                    defaultModifier = Constants.GENERATED_COLUMN_KIND + " " + genExpr;
                }
            }
            // Every other column constraint (AUTO_INCREMENT, ON UPDATE,
            // COLLATE, UNIQUE, PRIMARY KEY, CHECK, VISIBLE, ...) has no
            // ClickHouse column equivalent and is ignored.
        }

        // If null is not explicitly set, the column's current nullability in
        // ClickHouse decides (clean identifiers, Spec 06.03 §3.3). Unknown ->
        // Nullable, which holds every source value.
        if (!nullExplicitlySet) {
            Boolean existingNullable = targetColumnNullability().get(stripBackticks(columnName));
            isNullColumn = existingNullable == null || existingNullable;
        }

        // Sorting-key policy (Spec 06.05 §3.4): ClickHouse rejects EVERY type
        // change and every rename of a sorting-key column with Code: 524, so a
        // MODIFY/CHANGE of one is never emitted. Skip it when the existing
        // column already holds every value of the requested type; otherwise
        // stop loudly rather than emit a statement that fails on every retry
        // and takes the neighbouring clauses down with it.
        if (clause != ColumnClause.ADD) {
            String existingKeyType = targetSortingKeyTypes().get(stripBackticks(columnName));
            if (existingKeyType != null) {
                if (renames) {
                    throw keyColumnNotRepresentable(columnName, existingKeyType, columnType,
                            "cannot be renamed to " + newColumnName + " (ALTER RENAME of a key column)");
                }
                KeyColumnTypeChange.Verdict verdict = KeyColumnTypeChange.compare(existingKeyType, columnType);
                if (verdict == KeyColumnTypeChange.Verdict.SAME_OR_NARROWER) {
                    log.warn("Sorting-key column {}.{}.{}: key column type change to {} is not representable "
                                    + "in ClickHouse (Code: 524, the sorting key is fixed at CREATE); keeping {} "
                                    + "which holds every value of the source type. Clause skipped.",
                            this.databaseName, this.cleanTableName, stripBackticks(columnName), columnType,
                            existingKeyType);
                    removeTrailingComma();
                    return;
                }
                throw keyColumnNotRepresentable(columnName, existingKeyType, columnType,
                        verdict == KeyColumnTypeChange.Verdict.WIDER
                                ? "the requested type is wider than the existing column"
                                : "the requested type is not comparable with the existing column");
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

        if (columnPositionModifier != null && !columnPositionModifier.isEmpty()) {
            this.query.append(" ").append(columnPositionModifier);
        }

        if (renames) {
            postProcessModifyColumn(columnName, newColumnName);
        }

        // Check for ALIAS companion column for ADD operations
        if (clause == ColumnClause.ADD && this.config != null) {
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
     * Translates a source {@code DEFAULT} clause (Spec 06.04 §3.2).
     *
     * <p>Only a literal default is carried to ClickHouse: {@code NULL} or
     * {@code unaryOperator? constant} (string, integer, decimal, hexadecimal,
     * bit-string, boolean). {@code CURRENT_TIMESTAMP[(n)]}, {@code NOW()},
     * {@code ... ON UPDATE CURRENT_TIMESTAMP}, {@code CAST(...)}, parenthesised
     * expressions and vendor forms are dropped: copied verbatim they are
     * invalid ClickHouse (Code: 47 / 62), the failure is not retryable, and
     * the column would never be added. Dropping them loses nothing -- every
     * replicated row carries the source value, so a ClickHouse DEFAULT only
     * ever affects rows that pre-date the column, which MySQL back-filled
     * one-shot on the source.</p>
     *
     * @param ctx        the DEFAULT constraint.
     * @param columnName the column, for the log line.
     * @return {@code "DEFAULT <literal>"}, or null when the default is dropped.
     */
    private static String translateDefault(MySqlParser.DefaultColumnConstraintContext ctx, String columnName) {
        if (ctx.getChildCount() < 2 || !(ctx.getChild(1) instanceof MySqlParser.DefaultValueContext)) {
            return null;
        }
        MySqlParser.DefaultValueContext defaultValue = (MySqlParser.DefaultValueContext) ctx.getChild(1);
        boolean literal = defaultValue.getChildCount() > 0;
        for (ParseTree part : defaultValue.children) {
            boolean nullLiteral = part instanceof TerminalNodeImpl
                    && ((TerminalNodeImpl) part).symbol.getType() == MySqlParser.NULL_LITERAL;
            boolean constant = part instanceof MySqlParser.ConstantContext
                    || part instanceof MySqlParser.UnaryOperatorContext;
            if (!nullLiteral && !constant) {
                literal = false;
                break;
            }
        }
        if (!literal) {
            log.info("Column {}: DEFAULT {} is a function, expression or ON UPDATE form with no ClickHouse "
                            + "equivalent; dropping the DEFAULT (replicated rows carry the source value)",
                    columnName, defaultValue.getText());
            return null;
        }
        return "DEFAULT " + stripCharsetIntroducers(defaultValue.getText());
    }

    /**
     * Function to create the RENAME COLUMN statement of a translated CHANGE
     * COLUMN, as a separate statement after the MODIFY.
     *
     * @param oldCol The old column name (already resolved against ClickHouse).
     * @param newCol The new column name.
     */
    private void postProcessModifyColumn(String oldCol, String newCol) {
        this.query.append("\n");
        // IF EXISTS, so replaying an already-applied rename is a no-op rather
        // than a stream-stalling failure. Debezium flushes offsets
        // periodically, so a restart re-delivers every DDL event committed
        // since the last flush; a rename is not self-idempotent, because once
        // applied the old name is gone. Measured on 24.8.14, the replay fails
        // with Code: 10 NOT_FOUND_COLUMN_IN_BLOCK "Cannot find column `x` to
        // rename", and since DDL is retried indefinitely that stalls the
        // ENTIRE replication stream, not just this table.
        //
        // Targets the DESTINATION database like the MODIFY half; the source
        // database name of a qualified identifier is not a ClickHouse database.
        String rename = "ALTER TABLE %s " + Constants.RENAME_COLUMN + " %s to %s";
        this.query.append(String.format(rename, qualifiedTargetTable(), oldCol, newCol));
    }

    /**
     * The ALTER target as ClickHouse must see it: the destination database
     * (backticked) and the table part of the source identifier, with any
     * source database prefix dropped.
     */
    private String qualifiedTargetTable() {
        String table = this.tableName;
        if (table.contains(".")) {
            table = table.split("\\.")[1];
        }
        return "`" + this.databaseName + "`." + table;
    }

    @Override
    public void enterAlterTable(MySqlParser.AlterTableContext alterTableContext) {
        List<ParseTree> pt = alterTableContext.children;
        // Index in this.query where the "ALTER TABLE <table>" header ends and the
        // clause list begins. Separators and the empty-statement check below are
        // measured against this so a clause that emits nothing (an index, a key,
        // ALGORITHM/LOCK hints, a suppressed key-column MODIFY) cannot leave a
        // stray comma or a bare header.
        int headerEnd = -1;
        // A RENAME TO clause becomes its own statement AFTER the ALTER body, so
        // "ADD COLUMN c INT, RENAME TO t2" keeps the ADD (Spec 06.04 §3.4).
        String renameTarget = null;
        // The target table's existing schema is read at most once per statement.
        this.targetColumnNullability = null;
        this.targetSortingKeyTypes = null;
        for (ParseTree tree : pt) {

            if (tree instanceof TableNameContext) {
                this.tableName = tree.getText();
                // The caller passes "" as the table name; every system.columns
                // lookup below needs the clean name of the table named HERE.
                this.cleanTableName = Utils.extractPlainTableName(this.tableName);
                this.query.append(String.format(Constants.ALTER_TABLE, qualifiedTargetTable()));
                headerEnd = this.query.length();
            } else if (tree instanceof AlterByAddColumnContext
                    || tree instanceof MySqlParser.AlterByModifyColumnContext
                    || tree instanceof MySqlParser.AlterByChangeColumnContext) {
                parseAlterTable(tree);
            } else if (tree instanceof MySqlParser.AlterByAddColumnsContext) {
                parseAddColumns((MySqlParser.AlterByAddColumnsContext) tree, headerEnd);
            } else if (tree instanceof MySqlParser.AlterByAddDefinitionsContext) {
                parseAddDefinitions((MySqlParser.AlterByAddDefinitionsContext) tree, headerEnd);
            } else if (tree instanceof MySqlParser.AlterByDropConstraintCheckContext) {
                // Drop Constraint.
                // DESTRUCTIVE: none -- a CHECK constraint holds no data; this
                // mirrors the constraint drop the SOURCE already performed.
                this.query.append(" ");
                for (ParseTree dropConstraintTree : ((MySqlParser.AlterByDropConstraintCheckContext) (tree)).children) {
                    if (dropConstraintTree instanceof MySqlParser.UidContext) {
                        this.query.append(String.format(Constants.DROP_CONSTRAINT, dropConstraintTree.getText()));
                    }
                }
            } else if (tree instanceof MySqlParser.AlterByDropColumnContext) {
                // Drop Column.
                // DESTRUCTIVE: renders the column drop the SOURCE database
                // already performed and Debezium is replicating; the connector
                // never originates a drop. Blast radius is the single named
                // column of the single mirrored table; IF EXISTS makes a
                // replay a no-op.
                this.query.append(" ");
                for (ParseTree dropColumnTree : ((MySqlParser.AlterByDropColumnContext) (tree)).children) {
                    if (dropColumnTree instanceof MySqlParser.UidContext) {
                        String droppedColumn = resolveExistingColumnName(dropColumnTree.getText());
                        this.query.append(String.format(Constants.DROP_COLUMN, droppedColumn));

                        // Check for ALIAS column companion drops
                        if (this.config != null) {
                            ColumnTypeOverrideConfig overrideConfig =
                                    ColumnTypeOverrideConfig.fromProperties(this.config.originalsStrings());
                            if (overrideConfig.hasOverrides()) {
                                String cleanTableName = this.cleanTableName;
                                String droppedColName = stripBackticks(droppedColumn);
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
            } else if (tree instanceof MySqlParser.AlterByRenameColumnContext) {
                parseRenameColumn(tree);
            } else if (tree instanceof MySqlParser.AlterByAddCheckTableConstraintContext) {
                // ADD CONSTRAINT ... CHECK (...) is echoed in clause order so
                // its separator survives (a listener that fired after this
                // method appended it after the trailing-comma cleanup, gluing
                // it onto the previous clause without a comma).
                this.query.append(" ");
                for (ParseTree checkTree : ((MySqlParser.AlterByAddCheckTableConstraintContext) tree).children) {
                    this.parseTreeHelper(checkTree);
                }
            } else if (tree instanceof MySqlParser.AlterByRenameContext) {
                renameTarget = renameTargetTable((MySqlParser.AlterByRenameContext) tree);
            } else if (isNoOpSpecification(tree)) {
                // Indexes, keys, foreign keys, DROP PRIMARY KEY, column
                // DEFAULT changes, charset/collation, table options,
                // ALGORITHM/LOCK hints and partition operations have no
                // ClickHouse equivalent and nothing is lost by skipping them
                // (Spec 06.03 §3.2). Emitting nothing but leaving the
                // separators behind produced "ALTER TABLE t, MODIFY ..." or a
                // bare "ALTER TABLE t" -- both Code: 62 -- so drop the
                // separator this clause was preceded by; the trailing cleanup
                // at the end removes any that followed it.
                log.info("ALTER TABLE clause [{}] is not representable in ClickHouse; skipping clause",
                        tree.getText());
                removeTrailingComma();
            } else if (tree instanceof TerminalNodeImpl) {
                if (((TerminalNodeImpl) tree).symbol.getType() == MySqlParser.COMMA) {
                    // Emit a separator only when a clause has already produced
                    // output after the header and the query does not already end
                    // in one. Appending eagerly let a no-op clause leave a
                    // leading "ALTER TABLE t," or a doubled comma (Code: 62).
                    appendClauseSeparator(headerEnd);
                }
            } else if (tree instanceof MySqlParser.AlterSpecificationContext) {
                // Every alterSpecification alternative of the grammar is either
                // translated above or classified as a no-op; reaching this
                // branch means the grammar gained a clause this translator
                // does not know. Stop loudly rather than emit a bare or partial
                // ALTER (Invariant I9).
                throw new DDLReplicationException("ALTER TABLE clause not supported by the DDL translator: ["
                        + tree.getText() + "] (" + tree.getClass().getSimpleName() + ") in ["
                        + this.originalSql + "]", null);
            }
        }
        // A skipped clause in trailing position leaves the separator that
        // preceded it dangling once the clause itself emits nothing.
        removeTrailingComma();
        // Every clause emitted nothing (e.g. a lone ADD PRIMARY KEY): the query
        // is just "ALTER TABLE t", which ClickHouse rejects with Code: 62. Clear
        // it so executeDDL's `!query.isEmpty()` guard skips it instead of
        // stalling the stream on an un-representable, retried-forever statement.
        if (headerEnd >= 0 && this.query.length() <= headerEnd) {
            this.query.setLength(0);
        }
        if (renameTarget != null) {
            if (this.query.length() > 0) {
                this.query.append("\n");
            }
            this.query.append(String.format(Constants.ALTER_RENAME_TABLE, qualifiedTargetTable(),
                    "`" + this.databaseName + "`." + renameTarget));
        }
    }

    /**
     * Returns true for an ALTER specification this translator deliberately
     * drops because ClickHouse has no equivalent and nothing is lost by
     * skipping it (Spec 06.03 §3.2): indexes and keys of every kind,
     * {@code DROP PRIMARY KEY}, foreign keys, column DEFAULT changes,
     * charset and collation, table options, {@code ALGORITHM}/{@code LOCK}
     * hints, key enable/disable, physical ORDER BY, tablespace and every
     * partition operation.
     */
    private static boolean isNoOpSpecification(ParseTree tree) {
        return tree instanceof MySqlParser.AlterByAddPrimaryKeyContext
                || tree instanceof MySqlParser.AlterByDropPrimaryKeyContext
                || tree instanceof MySqlParser.AlterByAddIndexContext
                || tree instanceof MySqlParser.AlterByAddUniqueKeyContext
                || tree instanceof MySqlParser.AlterByAddSpecialIndexContext
                || tree instanceof MySqlParser.AlterByAddForeignKeyContext
                || tree instanceof MySqlParser.AlterByDropIndexContext
                || tree instanceof MySqlParser.AlterByDropForeignKeyContext
                || tree instanceof MySqlParser.AlterByRenameIndexContext
                || tree instanceof MySqlParser.AlterByAlterIndexVisibilityContext
                || tree instanceof MySqlParser.AlterByChangeDefaultContext
                || tree instanceof MySqlParser.AlterByAlterColumnDefaultContext
                || tree instanceof MySqlParser.AlterByAlterCheckTableConstraintContext
                || tree instanceof MySqlParser.AlterByConvertCharsetContext
                || tree instanceof MySqlParser.AlterByDefaultCharsetContext
                || tree instanceof MySqlParser.AlterByTableOptionContext
                || tree instanceof MySqlParser.AlterBySetAlgorithmContext
                || tree instanceof MySqlParser.AlterByLockContext
                || tree instanceof MySqlParser.AlterByDisableKeysContext
                || tree instanceof MySqlParser.AlterByEnableKeysContext
                || tree instanceof MySqlParser.AlterByOrderContext
                || tree instanceof MySqlParser.AlterByForceContext
                || tree instanceof MySqlParser.AlterByValidateContext
                || tree instanceof MySqlParser.AlterByDiscardTablespaceContext
                || tree instanceof MySqlParser.AlterByImportTablespaceContext
                // Every partition operation (ADD/DROP/DISCARD/IMPORT/TRUNCATE/
                // COALESCE/REORGANIZE/EXCHANGE/ANALYZE/CHECK/OPTIMIZE/REBUILD/
                // REPAIR PARTITION, REMOVE/UPGRADE PARTITIONING) arrives wrapped
                // in this one alterSpecification alternative.
                || tree instanceof MySqlParser.AlterPartitionContext;
    }

    /**
     * Appends a clause separator comma only when it is safe to do so: at least
     * one clause has already emitted output past the ALTER TABLE header, and the
     * query does not already end in a comma. This prevents a leading comma
     * ("ALTER TABLE t, ...") or a doubled comma when a preceding ALTER clause
     * emits nothing (ADD PRIMARY KEY, ALGORITHM/LOCK hints).
     *
     * @param headerEnd index at which the "ALTER TABLE <table>" header ends.
     */
    private void appendClauseSeparator(int headerEnd) {
        if (headerEnd < 0) {
            this.query.append(",");
            return;
        }
        int end = this.query.length();
        while (end > 0 && Character.isWhitespace(this.query.charAt(end - 1))) {
            end--;
        }
        if (end <= headerEnd) {
            // No clause content emitted yet after the header.
            return;
        }
        if (this.query.charAt(end - 1) == ',') {
            // Already separated.
            return;
        }
        this.query.append(",");
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
     * Extracts the new table name of an {@code ALTER TABLE ... RENAME [TO|AS]}
     * clause. A qualified target ({@code RENAME TO db2.t2}) is reduced to its
     * table part; the caller qualifies it with the destination database, so
     * the result is never {@code `db`.db2.t2} (Spec 06.04 §3.4).
     *
     * @param tree The parse tree representing the RENAME clause.
     * @return the bare new table name, or null if none was found.
     */
    private static String renameTargetTable(MySqlParser.AlterByRenameContext tree) {
        String newTableName = null;
        for (ParseTree alterByRenameChildren : tree.children) {
            if (alterByRenameChildren instanceof MySqlParser.UidContext
                    || alterByRenameChildren instanceof MySqlParser.FullIdContext) {
                newTableName = alterByRenameChildren.getText();
            }
        }
        if (newTableName != null && newTableName.contains(".")) {
            newTableName = newTableName.split("\\.")[1];
        }
        return newTableName;
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

    // ------------------------------------------------------------------
    // Target-schema access (Spec 06.03 §3.3 / §3.4)
    // ------------------------------------------------------------------

    /** The injected lookup, or the DBMetadata-backed default. */
    private TargetSchemaLookup targetSchemaLookup() {
        if (this.targetSchemaLookup == null) {
            this.targetSchemaLookup = new MetadataTargetSchemaLookup();
        }
        return this.targetSchemaLookup;
    }

    /** Column name -> Nullable? of the ALTER target, read once per statement. */
    private Map<String, Boolean> targetColumnNullability() {
        if (this.targetColumnNullability == null) {
            this.targetColumnNullability = targetSchemaLookup().columnNullability(this.databaseName, this.cleanTableName);
            if (this.targetColumnNullability == null) {
                this.targetColumnNullability = Collections.emptyMap();
            }
        }
        return this.targetColumnNullability;
    }

    /** Sorting-key column -> ClickHouse type of the ALTER target, read once per statement. */
    private Map<String, String> targetSortingKeyTypes() {
        if (this.targetSortingKeyTypes == null) {
            this.targetSortingKeyTypes = targetSchemaLookup().sortingKeyTypes(this.databaseName, this.cleanTableName);
            if (this.targetSortingKeyTypes == null) {
                this.targetSortingKeyTypes = Collections.emptyMap();
            }
        }
        return this.targetSortingKeyTypes;
    }

    /**
     * Resolves the name of an EXISTING column against the ClickHouse table.
     * MySQL column names are case-insensitive, ClickHouse's are not, so
     * {@code MODIFY COLUMN customername} must address {@code CustomerName}.
     * The original quoting is kept. Unchanged when the schema is unknown or
     * the name already matches exactly.
     *
     * @param rawName the column name as written in the source DDL.
     * @return the name to emit.
     */
    private String resolveExistingColumnName(String rawName) {
        if (rawName == null) {
            return null;
        }
        String plain = stripBackticks(rawName);
        Map<String, Boolean> columns = targetColumnNullability();
        if (columns.isEmpty() || columns.containsKey(plain)) {
            return rawName;
        }
        for (String actual : columns.keySet()) {
            if (actual.equalsIgnoreCase(plain)) {
                log.info("Column {} of {}.{} is spelled {} in ClickHouse; using the ClickHouse spelling",
                        plain, this.databaseName, this.cleanTableName, actual);
                return rawName.startsWith("`") ? "`" + actual + "`" : actual;
            }
        }
        return rawName;
    }

    /**
     * The loud outcome for a sorting-key column change ClickHouse cannot apply
     * (Spec 06.05 §3.4 rule 3, Invariant I9): logged and returned as a
     * {@link DDLReplicationException} that names the required manual rebuild.
     */
    private DDLReplicationException keyColumnNotRepresentable(String columnName, String existingType,
                                                              String requestedType, String reason) {
        String message = String.format(
                "Sorting-key column %s.%s.%s %s (existing ClickHouse type %s, requested %s). ClickHouse fixes "
                        + "the sorting key at CREATE TABLE and rejects this with Code: 524, so it cannot be "
                        + "applied by ALTER and is not retried. Manual rebuild required: re-create `%s`.%s with "
                        + "the new key definition and re-snapshot the table. Source DDL: [%s]",
                this.databaseName, this.cleanTableName, stripBackticks(columnName), reason, existingType,
                requestedType == null ? "n/a" : requestedType, this.databaseName, this.cleanTableName,
                this.originalSql);
        log.error(message);
        return new DDLReplicationException(message, null);
    }

    /**
     * Production {@link TargetSchemaLookup}: reads {@code system.columns}
     * through {@link DBMetadata} on the writer's connection. Answers
     * "unknown" (empty) without a connection or on a failed query, which
     * restores the pre-lookup behaviour (Nullable, no key handling); a MODIFY
     * that ClickHouse then rejects still surfaces through the DDL retry path.
     */
    private final class MetadataTargetSchemaLookup implements TargetSchemaLookup {
        @Override
        public Map<String, Boolean> columnNullability(String database, String table) {
            if (writer == null) {
                log.debug("No ClickHouse connection; schema of {}.{} is unknown to the DDL translator",
                        database, table);
                return Collections.emptyMap();
            }
            try {
                return dbMetadata.getColumnsIsNullableForTable(table, writer.getConnection(), database);
            } catch (Exception e) {
                log.error("Error retrieving NULL column schema of {}.{} from ClickHouse", database, table, e);
                return Collections.emptyMap();
            }
        }

        @Override
        public Map<String, String> sortingKeyTypes(String database, String table) {
            if (writer == null) {
                return Collections.emptyMap();
            }
            try {
                java.sql.Connection conn = writer.getConnection();
                List<String> keyColumns = dbMetadata.getSortingKeyColumns(conn, database, table);
                if (keyColumns.isEmpty()) {
                    return Collections.emptyMap();
                }
                Map<String, String> types = dbMetadata.getColumnsDataTypesForTable(table, conn, database);
                Map<String, String> keyTypes = new LinkedHashMap<>();
                for (String keyColumn : keyColumns) {
                    keyTypes.put(keyColumn, types.get(keyColumn));
                }
                return keyTypes;
            } catch (Exception e) {
                log.error("Error retrieving sorting key of {}.{} from ClickHouse", database, table, e);
                return Collections.emptyMap();
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
        // Always IF EXISTS, regardless of the source statement: MySQL binlogs a
        // server-generated drop as `DROP TABLE db.t /* generated by server */`
        // WITHOUT the guard the user may have typed, and a replay of the bare
        // form after a restart fails and stalls the stream. Dropping a table
        // that is already gone is exactly the intended end state.
        //
        // DESTRUCTIVE: renders the table drop the SOURCE database already
        // performed and Debezium is replicating; the connector never
        // originates a drop. Blast radius is the named mirrored table(s), and
        // IF EXISTS only narrows it by making a repeat a no-op.
        this.query.append(Constants.DROP_TABLE).append(" ").append(Constants.IF_EXISTS);
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
                // Already emitted unconditionally above.
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
        // IF EXISTS (accepted by ClickHouse 24.8, applies to every clause of
        // the statement): a rename is not self-idempotent, so a replay after a
        // restart must be a no-op rather than a stream-stalling failure.
        this.query.append(Constants.RENAME_TABLE).append(" ").append(Constants.IF_EXISTS);
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
