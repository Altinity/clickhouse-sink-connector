package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import postgres.PostgreSQLParser;
import postgres.PostgreSQLParserBaseListener;

import java.util.ArrayList;
import java.util.List;

/**
 * ANTLR ParseTree listener that translates PostgreSQL DDL statements captured
 * by Debezium into the equivalent ClickHouse DDL.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>CREATE TABLE (with column definitions and PRIMARY KEY constraints)</li>
 *   <li>ALTER TABLE … ADD COLUMN</li>
 *   <li>ALTER TABLE … DROP COLUMN</li>
 *   <li>ALTER TABLE … ALTER COLUMN … TYPE …</li>
 *   <li>ALTER TABLE … RENAME COLUMN … TO …  (via renamestmt)</li>
 *   <li>DROP TABLE</li>
 *   <li>TRUNCATE TABLE</li>
 * </ul>
 *
 * <p>Type mapping delegates to
 * {@link PostgreSQLDDLParserService#mapPostgresTypeToClickHouse(String)}.
 */
public class PostgreSQLDDLParserListenerImpl extends PostgreSQLParserBaseListener {

    private static final Logger log = LogManager.getLogger(PostgreSQLDDLParserListenerImpl.class);

    // -----------------------------------------------------------------------
    // Construction-time state
    // -----------------------------------------------------------------------

    /** Buffer that receives the translated ClickHouse DDL. */
    private final StringBuffer query;

    /** Debezium-supplied table name hint (may include schema prefix). */
    private final String tableNameHint;

    /** ClickHouse destination database name. */
    private final String databaseName;

    /** Connector configuration — used for column type overrides. */
    private final ClickHouseSinkConnectorConfig config;

    /** Writer for optional online metadata look-ups (may be null). */
    @SuppressWarnings("unused")
    private final BaseDbWriter writer;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Creates a new listener instance.
     *
     * @param writer        live DB writer (may be {@code null} if not available).
     * @param query         output buffer for the translated DDL.
     * @param tableName     table-name hint supplied by Debezium.
     * @param databaseName  ClickHouse destination database.
     * @param config        connector configuration.
     */
    public PostgreSQLDDLParserListenerImpl(
            BaseDbWriter writer,
            StringBuffer query,
            String tableName,
            String databaseName,
            ClickHouseSinkConnectorConfig config) {
        this.writer        = writer;
        this.query         = query;
        this.tableNameHint = tableName;
        this.databaseName  = databaseName;
        this.config        = config;
    }

    // -----------------------------------------------------------------------
    // CREATE TABLE
    // -----------------------------------------------------------------------

    /**
     * Handles {@code createstmt} — the grammar rule for {@code CREATE TABLE}.
     *
     * <p>Grammar:
     * <pre>
     * createstmt
     *     : CREATE opttemp? TABLE (IF_P NOT EXISTS)? qualified_name
     *         OPEN_PAREN opttableelementlist? CLOSE_PAREN …
     *     ;
     * columnDef : colid typename create_generic_options? colquallist ;
     * colconstraintelem : NOT NULL_P | NULL_P | PRIMARY KEY … | DEFAULT … | GENERATED … ;
     * </pre>
     */
    @Override
    public void enterCreatestmt(PostgreSQLParser.CreatestmtContext ctx) {
        try {
            // ── table name ──────────────────────────────────────────────────
            // qualified_name(0) is always present for CREATE TABLE; the grammar
            // has two qualified_names only for PARTITION OF variants.
            List<PostgreSQLParser.Qualified_nameContext> qnames = ctx.qualified_name();
            String rawTable = (qnames != null && !qnames.isEmpty())
                ? qnames.get(0).getText()
                : tableNameHint;
            String qualifiedTable = qualifyTableName(rawTable);

            // ── column definitions ──────────────────────────────────────────
            List<String> columnDdl   = new ArrayList<>();
            List<String> primaryKeys = new ArrayList<>();

            PostgreSQLParser.OpttableelementlistContext elemList = ctx.opttableelementlist();
            if (elemList != null && elemList.tableelementlist() != null) {
                // Pass 1: collect ALL primary key columns first (from both
                // inline column constraints and table-level constraints) so
                // that processColumnDef can determine nullable/non-nullable
                // status correctly.
                for (PostgreSQLParser.TableelementContext elem
                        : elemList.tableelementlist().tableelement()) {
                    if (elem.columnDef() != null) {
                        collectInlinePrimaryKey(elem.columnDef(), primaryKeys);
                    }
                    if (elem.tableconstraint() != null) {
                        collectPrimaryKeysFromTableConstraint(elem.tableconstraint(), primaryKeys);
                    }
                }

                // Pass 2: process column definitions with full PK knowledge.
                String plainTable = extractPlainTableName(rawTable);
                for (PostgreSQLParser.TableelementContext elem
                        : elemList.tableelementlist().tableelement()) {
                    if (elem.columnDef() != null) {
                        processColumnDef(elem.columnDef(), columnDdl, primaryKeys, plainTable);
                    }
                }
            }

            // ── ALIAS columns from column type overrides ─────────────────────
            if (config != null) {
                String plainTable = extractPlainTableName(rawTable);
                ColumnTypeOverrideConfig overrideConfig =
                        ColumnTypeOverrideConfig.fromProperties(config.originalsStrings());
                List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                        overrideConfig.getAliasOverrides(databaseName, plainTable);
                for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
                    columnDdl.add("`" + entry.getAliasColumnName() + "` "
                            + entry.getAliasType() + " ALIAS " + entry.getExpression());
                }
            }

            // ── mandatory CDC virtual columns ───────────────────────────────
            columnDdl.add("`_version` UInt64");
            columnDdl.add("`is_deleted` UInt8 DEFAULT 0");

            // ── ORDER BY / PRIMARY KEY ──────────────────────────────────────
            String orderBy = primaryKeys.isEmpty() ? "tuple()" : buildColumnList(primaryKeys);

            // ── emit DDL ────────────────────────────────────────────────────
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE IF NOT EXISTS ").append(qualifiedTable).append(" (\n");
            for (int i = 0; i < columnDdl.size(); i++) {
                sb.append("    ").append(columnDdl.get(i));
                if (i < columnDdl.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append(") ENGINE = ReplacingMergeTree(`_version`, `is_deleted`)\n");
            sb.append("ORDER BY ").append(orderBy).append(";");

            query.append(sb);
            log.info("PostgreSQL CREATE TABLE translated: {}", qualifiedTable);

        } catch (Exception e) {
            log.error("Error translating CREATE TABLE", e);
        }
    }

    /**
     * Collects inline PRIMARY KEY column names from a single {@code columnDef}
     * context without generating any DDL.  Used in the first pass to build the
     * complete set of PK columns before any column DDL is emitted.
     */
    private static void collectInlinePrimaryKey(PostgreSQLParser.ColumnDefContext col,
                                                List<String> primaryKeys) {
        if (isPrimaryKeyConstraint(col.colquallist())) {
            primaryKeys.add(unquoteId(col.colid().getText()));
        }
    }

    /**
     * Processes a single {@code columnDef} context, appending the ClickHouse
     * column DDL fragment to {@code columnDdl}.  The {@code primaryKeys} list
     * must already be fully populated (from both inline and table-level
     * constraints) before this method is called.
     */
    private void processColumnDef(PostgreSQLParser.ColumnDefContext col,
                                   List<String> columnDdl,
                                   List<String> primaryKeys,
                                   String tableName) {
        String colName  = unquoteId(col.colid().getText());
        String pgType   = extractTypeName(col.typename());
        String chType   = PostgreSQLDDLParserService.mapPostgresTypeToClickHouse(
                pgType, databaseName, tableName, colName, config);
        boolean notNull = isNotNullConstraint(col.colquallist());
        boolean isPk    = primaryKeys.contains(colName);

        if (isPk) {
            // PK columns are inherently NOT NULL — store without Nullable wrapper
            columnDdl.add("`" + colName + "` " + chType);
        } else {
            columnDdl.add("`" + colName + "` " + (notNull ? chType : wrapNullable(chType)));
        }
    }

    // -----------------------------------------------------------------------
    // ALTER TABLE
    // -----------------------------------------------------------------------

    /**
     * Handles {@code altertablestmt}.
     *
     * <p>Grammar:
     * <pre>
     * altertablestmt
     *     : ALTER TABLE (IF_P EXISTS)? relation_expr (alter_table_cmds | partition_cmd)
     *     | ALTER INDEX … | ALTER SEQUENCE … | ALTER VIEW … | …
     *     ;
     * alter_table_cmd
     *     : ADD_P (COLUMN)? (IF_P NOT EXISTS)? columnDef
     *     | DROP (COLUMN)? (IF_P EXISTS)? colid drop_behavior_?
     *     | ALTER (COLUMN)? colid (SET DATA)? TYPE_P typename …
     *     ;
     * </pre>
     *
     * <p>Only the {@code ALTER TABLE relation_expr alter_table_cmds} alternative
     * is translated; other alternatives (ALTER INDEX, ALTER SEQUENCE, etc.) are
     * silently ignored.
     */
    @Override
    public void enterAltertablestmt(PostgreSQLParser.AltertablestmtContext ctx) {
        try {
            // relation_expr() returns a single context for the ALTER TABLE alternatives.
            PostgreSQLParser.Relation_exprContext relExpr = ctx.relation_expr();
            if (relExpr == null) return;
            if (ctx.alter_table_cmds() == null) return;

            String rawTable      = relExpr.getText();
            String qualifiedTable = qualifyTableName(rawTable);

            for (PostgreSQLParser.Alter_table_cmdContext cmd
                    : ctx.alter_table_cmds().alter_table_cmd()) {
                translateAlterTableCmd(qualifiedTable, cmd);
            }

        } catch (Exception e) {
            log.error("Error translating ALTER TABLE", e);
        }
    }

    /**
     * Dispatches a single {@code alter_table_cmd} context to the appropriate
     * ADD / DROP / ALTER handler based on the first keyword child.
     */
    private void translateAlterTableCmd(String qualifiedTable,
                                        PostgreSQLParser.Alter_table_cmdContext cmd) {
        if (cmd.getChildCount() == 0) return;
        String firstText = cmd.getChild(0).getText().toUpperCase();

        switch (firstText) {
            case "ADD":
            case "ADD_P": {
                PostgreSQLParser.ColumnDefContext colDef = findColumnDef(cmd);
                if (colDef != null) translateAddColumn(qualifiedTable, colDef);
                break;
            }
            case "DROP": {
                translateDropColumn(qualifiedTable, cmd);
                break;
            }
            case "ALTER": {
                translateAlterColumnType(qualifiedTable, cmd);
                break;
            }
            default:
                log.debug("Unsupported alter_table_cmd keyword '{}' – skipped", firstText);
        }
    }

    /** Translates {@code ADD [COLUMN] columnDef} → {@code ALTER TABLE … ADD COLUMN}. */
    private void translateAddColumn(String qualifiedTable,
                                    PostgreSQLParser.ColumnDefContext colDef) {
        String colName    = unquoteId(colDef.colid().getText());
        String pgType     = extractTypeName(colDef.typename());
        String plainTable = extractPlainTableName(qualifiedTable);
        String chType     = PostgreSQLDDLParserService.mapPostgresTypeToClickHouse(
                pgType, databaseName, plainTable, colName, config);
        boolean notNull   = isNotNullConstraint(colDef.colquallist());
        String colSpec    = notNull ? chType : wrapNullable(chType);

        query.append("ALTER TABLE ").append(qualifiedTable)
             .append(" ADD COLUMN IF NOT EXISTS `").append(colName)
             .append("` ").append(colSpec).append(";");
        log.info("PostgreSQL ADD COLUMN translated: {}.{}", qualifiedTable, colName);

        // If an alias override exists for this column, also add the companion ALIAS column
        if (config != null) {
            ColumnTypeOverrideConfig overrideConfig =
                    ColumnTypeOverrideConfig.fromProperties(config.originalsStrings());
            List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                    overrideConfig.getAliasOverrides(databaseName, plainTable);
            for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
                if (entry.getColumn().equals(colName)) {
                    query.append("ALTER TABLE ").append(qualifiedTable)
                         .append(" ADD COLUMN IF NOT EXISTS `")
                         .append(entry.getAliasColumnName()).append("` ")
                         .append(entry.getAliasType())
                         .append(" ALIAS ").append(entry.getExpression()).append(";");
                    log.info("PostgreSQL ADD ALIAS COLUMN translated: {}.{}",
                             qualifiedTable, entry.getAliasColumnName());
                }
            }
        }
    }

    /** Translates {@code DROP [COLUMN] colid} → {@code ALTER TABLE … DROP COLUMN}. */
    private void translateDropColumn(String qualifiedTable,
                                     PostgreSQLParser.Alter_table_cmdContext cmd) {
        PostgreSQLParser.ColidContext colid = findFirstColid(cmd);
        if (colid == null) return;
        String colName = unquoteId(colid.getText());

        query.append("ALTER TABLE ").append(qualifiedTable)
             .append(" DROP COLUMN IF EXISTS `").append(colName).append("`;");
        log.info("PostgreSQL DROP COLUMN translated: {}.{}", qualifiedTable, colName);

        // If an alias override exists for this column, also drop the companion ALIAS column
        if (config != null) {
            String plainTable = extractPlainTableName(qualifiedTable);
            ColumnTypeOverrideConfig overrideConfig =
                    ColumnTypeOverrideConfig.fromProperties(config.originalsStrings());
            List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                    overrideConfig.getAliasOverrides(databaseName, plainTable);
            for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
                if (entry.getColumn().equals(colName)) {
                    query.append("ALTER TABLE ").append(qualifiedTable)
                         .append(" DROP COLUMN IF EXISTS `")
                         .append(entry.getAliasColumnName()).append("`;");
                    log.info("PostgreSQL DROP ALIAS COLUMN translated: {}.{}",
                             qualifiedTable, entry.getAliasColumnName());
                }
            }
        }
    }

    /**
     * Translates {@code ALTER [COLUMN] colid [SET DATA] TYPE typename}
     * → {@code ALTER TABLE … MODIFY COLUMN}.
     */
    private void translateAlterColumnType(String qualifiedTable,
                                          PostgreSQLParser.Alter_table_cmdContext cmd) {
        // The colid child is the column name; typename gives the new type.
        PostgreSQLParser.ColidContext colid    = findFirstColid(cmd);
        PostgreSQLParser.TypenameContext tyCtx = cmd.typename();
        if (colid == null || tyCtx == null) return;

        String colName    = unquoteId(colid.getText());
        String pgType     = extractTypeName(tyCtx);
        String plainTable = extractPlainTableName(qualifiedTable);
        String chType     = PostgreSQLDDLParserService.mapPostgresTypeToClickHouse(
                pgType, databaseName, plainTable, colName, config);

        query.append("ALTER TABLE ").append(qualifiedTable)
             .append(" MODIFY COLUMN `").append(colName)
             .append("` ").append(wrapNullable(chType)).append(";");
        log.info("PostgreSQL ALTER COLUMN TYPE translated: {}.{}", qualifiedTable, colName);

        // If an alias override exists for this column, recreate the companion ALIAS column
        if (config != null) {
            ColumnTypeOverrideConfig overrideConfig =
                    ColumnTypeOverrideConfig.fromProperties(config.originalsStrings());
            List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                    overrideConfig.getAliasOverrides(databaseName, plainTable);
            for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
                if (entry.getColumn().equals(colName)) {
                    // Drop the old ALIAS column and re-add with updated definition
                    query.append("ALTER TABLE ").append(qualifiedTable)
                         .append(" DROP COLUMN IF EXISTS `")
                         .append(entry.getAliasColumnName()).append("`;");
                    query.append("ALTER TABLE ").append(qualifiedTable)
                         .append(" ADD COLUMN IF NOT EXISTS `")
                         .append(entry.getAliasColumnName()).append("` ")
                         .append(entry.getAliasType())
                         .append(" ALIAS ").append(entry.getExpression()).append(";");
                    log.info("PostgreSQL ALTER ALIAS COLUMN recreated: {}.{}",
                             qualifiedTable, entry.getAliasColumnName());
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // RENAME (renamestmt)
    // -----------------------------------------------------------------------

    /**
     * Handles {@code renamestmt} — covers both table renames and column renames.
     *
     * <p>Grammar alternatives of interest:
     * <pre>
     * ALTER TABLE relation_expr RENAME TO name                            ← table rename
     * ALTER TABLE (IF_P EXISTS)? relation_expr RENAME column_? name TO name  ← column rename
     * </pre>
     *
     * <p>The {@code name()} list in the generated context contains all {@code name}
     * rule instances in the matched alternative.  For column-rename alternatives
     * the list is {@code [oldName, newName]}.  For table-rename it is
     * {@code [newName]}.
     */
    @Override
    public void enterRenamestmt(PostgreSQLParser.RenamestmtContext ctx) {
        try {
            // Only process ALTER TABLE variants (not ALTER INDEX, ALTER VIEW, etc.)
            // The quickest check: does this alternative contain a relation_expr?
            PostgreSQLParser.Relation_exprContext relExpr = ctx.relation_expr();
            if (relExpr == null) return;

            String rawTable       = relExpr.getText();
            String qualifiedTable = qualifyTableName(rawTable);

            List<PostgreSQLParser.NameContext> names = ctx.name();
            if (names == null || names.isEmpty()) return;

            // Determine which alternative matched by inspecting children for COLUMN keyword.
            boolean hasColumnKeyword = hasChildToken(ctx, "COLUMN");

            // Also detect RENAME … TO without a column name in between:
            // ALTER TABLE relation_expr RENAME TO name  → names.size() == 1
            // ALTER TABLE relation_expr RENAME [COLUMN] oldName TO name → names.size() == 2
            if (!hasColumnKeyword && names.size() == 1) {
                // Table rename
                String newName      = unquoteId(names.get(0).getText());
                String newQualified = qualifyTableName(newName);
                query.append("RENAME TABLE ").append(qualifiedTable)
                     .append(" TO ").append(newQualified).append(";");
                log.info("PostgreSQL RENAME TABLE translated: {} → {}", qualifiedTable, newQualified);
            } else if (names.size() >= 2) {
                // Column rename: first name is old column, last name is new column
                String oldCol = unquoteId(names.get(0).getText());
                String newCol = unquoteId(names.get(names.size() - 1).getText());
                query.append("ALTER TABLE ").append(qualifiedTable)
                     .append(" RENAME COLUMN `").append(oldCol)
                     .append("` TO `").append(newCol).append("`;");
                log.info("PostgreSQL RENAME COLUMN translated: {}.{} → {}",
                         qualifiedTable, oldCol, newCol);
            }

        } catch (Exception e) {
            log.error("Error translating RENAME statement", e);
        }
    }

    // -----------------------------------------------------------------------
    // DROP TABLE
    // -----------------------------------------------------------------------

    /**
     * Handles {@code dropstmt}.
     *
     * <p>Grammar:
     * <pre>
     * dropstmt
     *     : DROP object_type_any_name (IF_P EXISTS)? any_name_list_ drop_behavior_?
     *     | DROP drop_type_name … | DROP TYPE_P … | DROP DOMAIN_P … | …
     *     ;
     * object_type_any_name : TABLE | SEQUENCE | VIEW | MATERIALIZED VIEW | INDEX | …
     * </pre>
     *
     * <p>Only {@code DROP TABLE} is translated; other object types are ignored.
     */
    @Override
    public void enterDropstmt(PostgreSQLParser.DropstmtContext ctx) {
        try {
            if (ctx.object_type_any_name() == null) return;
            String objType = ctx.object_type_any_name().getText().toUpperCase();
            if (!"TABLE".equals(objType)) return;

            if (ctx.any_name_list_() == null) return;

            for (PostgreSQLParser.Any_nameContext anyName : ctx.any_name_list_().any_name()) {
                String rawTable      = anyName.getText();
                String qualifiedTable = qualifyTableName(rawTable);
                query.append("DROP TABLE IF EXISTS ").append(qualifiedTable).append(";");
                log.info("PostgreSQL DROP TABLE translated: {}", qualifiedTable);
            }

        } catch (Exception e) {
            log.error("Error translating DROP TABLE", e);
        }
    }

    // -----------------------------------------------------------------------
    // TRUNCATE TABLE
    // -----------------------------------------------------------------------

    /**
     * Handles {@code truncatestmt}.
     *
     * <p>Grammar:
     * <pre>
     * truncatestmt : TRUNCATE table_? relation_expr_list restart_seqs_? drop_behavior_? ;
     * </pre>
     */
    @Override
    public void enterTruncatestmt(PostgreSQLParser.TruncatestmtContext ctx) {
        try {
            if (ctx.relation_expr_list() == null) return;

            for (PostgreSQLParser.Relation_exprContext re
                    : ctx.relation_expr_list().relation_expr()) {
                String rawTable      = re.getText();
                String qualifiedTable = qualifyTableName(rawTable);
                query.append("TRUNCATE TABLE IF EXISTS ").append(qualifiedTable).append(";");
                log.info("PostgreSQL TRUNCATE TABLE translated: {}", qualifiedTable);
            }

        } catch (Exception e) {
            log.error("Error translating TRUNCATE TABLE", e);
        }
    }

    // -----------------------------------------------------------------------
    // Type extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts a normalised PostgreSQL type string from a {@code typename} parse
     * tree node.  The result is suitable for passing to
     * {@link PostgreSQLDDLParserService#mapPostgresTypeToClickHouse(String)}.
     *
     * @param ctx the {@code typename} context (may be {@code null}).
     * @return the type string, e.g. {@code "VARCHAR(255)"}, {@code "TIMESTAMPTZ"}.
     */
    static String extractTypeName(PostgreSQLParser.TypenameContext ctx) {
        if (ctx == null) return "TEXT";

        // Array suffix: type[] or type ARRAY
        boolean isArray = ctx.getText().toUpperCase().endsWith("[]")
                       || ctx.getText().toUpperCase().contains("ARRAY");

        if (ctx.simpletypename() != null) {
            PostgreSQLParser.SimpletypenameContext st = ctx.simpletypename();
            String base = extractSimpleTypeName(st);
            return isArray ? base + "[]" : base;
        }

        // Fallback: return the raw text uppercased
        return ctx.getText().toUpperCase();
    }

    /**
     * Extracts the base type name from a {@code simpletypename} context.
     */
    private static String extractSimpleTypeName(PostgreSQLParser.SimpletypenameContext st) {
        // numeric: INT, INTEGER, BIGINT, SMALLINT, REAL, FLOAT, DOUBLE PRECISION, DECIMAL, NUMERIC, BOOLEAN
        if (st.numeric() != null) {
            return normaliseSpaces(st.numeric());
        }
        // character: CHAR, CHARACTER, VARCHAR, CHARACTER VARYING
        if (st.character() != null) {
            return normaliseSpaces(st.character());
        }
        // datetime: DATE, TIME, TIMESTAMP and their TZ variants
        if (st.constdatetime() != null) {
            return normaliseSpaces(st.constdatetime());
        }
        // interval
        if (st.constinterval() != null) {
            return "INTERVAL";
        }
        // JSON / JSONB  (jsonType rule)
        if (st.jsonType() != null) {
            return st.jsonType().getText().toUpperCase();
        }
        // generictype: covers OID, UUID, TEXT, BYTEA, INET, CIDR, TSVECTOR, user-defined types
        if (st.generictype() != null) {
            PostgreSQLParser.GenerictypeContext gt = st.generictype();
            String baseName = gt.type_function_name().getText().toUpperCase();
            if (gt.type_modifiers_() != null) {
                // Preserve modifiers e.g. VARCHAR(255) → "VARCHAR(255)"
                return baseName + gt.type_modifiers_().getText();
            }
            return baseName;
        }
        // bit / varbit
        if (st.bit() != null) {
            return "BIT";
        }
        return st.getText().toUpperCase();
    }

    /**
     * Concatenates all terminal tokens in a rule context, separated by single
     * spaces, producing an upper-cased normalised type string.
     *
     * @param ctx any parser rule context.
     * @return normalised upper-case text.
     */
    private static String normaliseSpaces(ParserRuleContext ctx) {
        StringBuilder sb = new StringBuilder();
        collectTerminals(ctx, sb);
        return sb.toString().trim().toUpperCase();
    }

    /** Recursively appends text of all terminal nodes to {@code sb}. */
    private static void collectTerminals(ParseTree node, StringBuilder sb) {
        if (node instanceof TerminalNode) {
            String t = node.getText();
            if (!t.isEmpty() && !"<EOF>".equals(t)) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(t);
            }
        } else {
            for (int i = 0; i < node.getChildCount(); i++) {
                collectTerminals(node.getChild(i), sb);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Constraint helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the column's {@code colquallist} contains a
     * {@code NOT NULL} constraint element.
     */
    private static boolean isNotNullConstraint(PostgreSQLParser.ColquallistContext cql) {
        if (cql == null) return false;
        for (PostgreSQLParser.ColconstraintContext cc : cql.colconstraint()) {
            if (cc.colconstraintelem() != null) {
                PostgreSQLParser.ColconstraintelemContext elem = cc.colconstraintelem();
                // NOT NULL_P : grammar token names
                if (elem.NOT() != null && elem.NULL_P() != null) return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the column's {@code colquallist} contains a
     * {@code PRIMARY KEY} constraint element.
     */
    private static boolean isPrimaryKeyConstraint(PostgreSQLParser.ColquallistContext cql) {
        if (cql == null) return false;
        for (PostgreSQLParser.ColconstraintContext cc : cql.colconstraint()) {
            if (cc.colconstraintelem() != null) {
                PostgreSQLParser.ColconstraintelemContext elem = cc.colconstraintelem();
                if (elem.PRIMARY() != null && elem.KEY() != null) return true;
            }
        }
        return false;
    }

    /**
     * Inspects a table-level {@code tableconstraint} and appends declared
     * primary-key column names to {@code primaryKeys}.
     *
     * <p>Grammar:
     * <pre>
     * constraintelem : … | PRIMARY KEY OPEN_PAREN columnlist CLOSE_PAREN … | …
     * columnlist     : columnElem (COMMA columnElem)* ;
     * columnElem     : colid ;
     * </pre>
     */
    private static void collectPrimaryKeysFromTableConstraint(
            PostgreSQLParser.TableconstraintContext tc,
            List<String> primaryKeys) {
        if (tc == null) return;
        PostgreSQLParser.ConstraintelemContext ce = tc.constraintelem();
        if (ce == null) return;
        if (ce.PRIMARY() != null && ce.KEY() != null && ce.columnlist() != null) {
            for (PostgreSQLParser.ColumnElemContext col : ce.columnlist().columnElem()) {
                primaryKeys.add(unquoteId(col.getText()));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Parse-tree navigation helpers
    // -----------------------------------------------------------------------

    /**
     * Searches the direct children of an {@code alter_table_cmd} context for a
     * {@code columnDef} node.
     */
    private static PostgreSQLParser.ColumnDefContext findColumnDef(
            PostgreSQLParser.Alter_table_cmdContext cmd) {
        for (int i = 0; i < cmd.getChildCount(); i++) {
            ParseTree child = cmd.getChild(i);
            if (child instanceof PostgreSQLParser.ColumnDefContext) {
                return (PostgreSQLParser.ColumnDefContext) child;
            }
        }
        return null;
    }

    /**
     * Searches the direct children of an {@code alter_table_cmd} context for
     * the first {@code colid} node (used to identify the target column in DROP
     * and ALTER COLUMN commands).
     */
    private static PostgreSQLParser.ColidContext findFirstColid(
            PostgreSQLParser.Alter_table_cmdContext cmd) {
        for (int i = 0; i < cmd.getChildCount(); i++) {
            ParseTree child = cmd.getChild(i);
            if (child instanceof PostgreSQLParser.ColidContext) {
                return (PostgreSQLParser.ColidContext) child;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the given parse-tree context has a terminal child
     * node whose text (case-insensitive) equals {@code tokenText}.
     */
    private static boolean hasChildToken(ParserRuleContext ctx, String tokenText) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child instanceof TerminalNode
                    && tokenText.equalsIgnoreCase(child.getText())) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Name / identifier helpers
    // -----------------------------------------------------------------------

    /**
     * Qualifies a raw (possibly schema-prefixed) table name with the ClickHouse
     * destination database, returning a back-tick quoted form suitable for use
     * in ClickHouse DDL.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code public.users} → {@code `mydb`.`users`}</li>
     *   <li>{@code users}       → {@code `mydb`.`users`}</li>
     * </ul>
     *
     * @param rawTableName the table name as it appears in the DDL.
     * @return back-tick quoted {@code `db`.`table`} form.
     */
    private String qualifyTableName(String rawTableName) {
        if (rawTableName == null || rawTableName.isEmpty()) {
            return databaseName != null ? "`" + databaseName + "`" : "``";
        }

        // Strip SQL double-quote identifiers
        String name = rawTableName.replace("\"", "");
        // Strip "public." (PostgreSQL default schema)
        name = name.replaceAll("(?i)^public\\.", "");

        if (name.contains(".")) {
            String[] parts = name.split("\\.", 2);
            String db = (databaseName != null && !databaseName.isEmpty()) ? databaseName : parts[0];
            return "`" + db + "`.`" + parts[1] + "`";
        }

        if (databaseName != null && !databaseName.isEmpty()) {
            return "`" + databaseName + "`.`" + name + "`";
        }
        return "`" + name + "`";
    }

    /**
     * Extracts the plain (unqualified, unquoted) table name from either a raw
     * DDL table reference (e.g. {@code "public"."events"}) or an already
     * qualified back-tick form (e.g. {@code `mydb`.`events`}).
     *
     * @param tableRef the table reference string.
     * @return the plain table name, e.g. {@code "events"}.
     */
    private static String extractPlainTableName(String tableRef) {
        if (tableRef == null || tableRef.isEmpty()) return "";
        // Remove backticks and double-quotes
        String clean = tableRef.replace("`", "").replace("\"", "");
        // Take the last segment after any dot
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
    }

    /**
     * Removes surrounding double-quotes from an SQL identifier token.
     *
     * @param id the raw identifier token text (e.g. {@code "my_col"}).
     * @return the unquoted identifier.
     */
    private static String unquoteId(String id) {
        if (id == null) return "";
        if (id.length() > 1 && id.startsWith("\"") && id.endsWith("\"")) {
            return id.substring(1, id.length() - 1);
        }
        return id;
    }

    /**
     * Wraps a ClickHouse type string with {@code Nullable(…)} unless the type
     * is already wrapped or belongs to a family that does not support
     * {@code Nullable} (Array, Nested, Tuple).
     *
     * @param chType the ClickHouse type string.
     * @return the nullable-wrapped type string.
     */
    private static String wrapNullable(String chType) {
        if (chType == null) return "Nullable(String)";
        String upper = chType.toUpperCase();
        if (upper.startsWith("NULLABLE(")) return chType;
        if (upper.startsWith("ARRAY") || upper.startsWith("NESTED")
                || upper.startsWith("TUPLE")) {
            return chType;
        }
        return "Nullable(" + chType + ")";
    }

    /**
     * Formats a list of column names as a parenthesised, back-tick-quoted
     * sequence for use in ORDER BY / PRIMARY KEY clauses.
     *
     * @param columns column names.
     * @return e.g. {@code (`id`, `tenant_id`)}.
     */
    private static String buildColumnList(List<String> columns) {
        if (columns.size() == 1) return "(`" + columns.get(0) + "`)";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("`").append(columns.get(i)).append("`");
        }
        sb.append(")");
        return sb.toString();
    }
}
