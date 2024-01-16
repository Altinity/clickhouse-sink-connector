package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.DataTypeConverter;
import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.AlterByAddColumnContext;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.TableNameContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This class contains the only overridden functions from the generated parser.
 */
public class MySqlDDLParserListenerImpl extends MySQLDDLParserBaseListener {
    private static final Logger log = LoggerFactory.getLogger(MySqlDDLParserListenerImpl.class);
    StringBuffer query;
    String tableName;
    ClickHouseSinkConnectorConfig config;
    ZoneId userProvidedTimeZone;

    public MySqlDDLParserListenerImpl(StringBuffer transformedQuery, String tableName,
                                      ClickHouseSinkConnectorConfig config) {
        this.query = transformedQuery;
        this.tableName = tableName;
        this.config = config;

        this.userProvidedTimeZone = parseTimeZone();
    }


    public ZoneId parseTimeZone() {
        String userProvidedTimeZone = config.getString(ClickHouseSinkConnectorConfigVariables
                .CLICKHOUSE_DATETIME_TIMEZONE.toString());
        // Validate if timezone string is valid.
        ZoneId userProvidedTimeZoneId = null;
        try {
            if(!userProvidedTimeZone.isEmpty()) {

                userProvidedTimeZoneId = ZoneId.of(userProvidedTimeZone);
                if(userProvidedTimeZoneId != null) {
                    //log.info("**** OVERRIDE TIMEZONE for DateTime:" + userProvidedTimeZone);
                }
            }
        } catch (Exception e){
            log.error("**** Error parsing user provided timezone:"+ userProvidedTimeZone + e.toString());
        }

        return userProvidedTimeZoneId;
    }

    @Override
    public void enterCreateDatabase(MySqlParser.CreateDatabaseContext createDatabaseContext) {
        for (ParseTree tree : createDatabaseContext.children) {
            if (tree instanceof MySqlParser.UidContext) {
                this.query.append(String.format(Constants.CREATE_DATABASE, tree.getText()));
            }
        }
    }

    @Override
    public void enterCopyCreateTable(MySqlParser.CopyCreateTableContext copyCreateTableContext) {
        ListIterator<ParseTree> it = copyCreateTableContext.children.listIterator();

        String originalTableName = "";
        String newTableName = "";


        while(it.hasNext()) {
            ParseTree tree = it.next();
            if(tree instanceof MySqlParser.TableNameContext) {
                originalTableName = tree.getText();
                if(it.next().getText().equalsIgnoreCase(Constants.LIKE)) {
                    newTableName = it.next().getText();
                }
            }
        }
        this.query.append(Constants.CREATE_TABLE).append(" ").append(originalTableName).append(" ")
                .append(Constants.AS).append(" ").append(newTableName);
    }

    @Override
    public void enterColumnCreateTable(MySqlParser.ColumnCreateTableContext columnCreateTableContext) {
        StringBuilder orderByColumns = new StringBuilder();
        StringBuilder partitionByColumn = new StringBuilder();
        Set<String> columnNames = parseCreateTable(columnCreateTableContext, orderByColumns, partitionByColumn);
        //this.query.append(" Engine=")
        String isDeletedColumn = IS_DELETED_COLUMN;
        if(columnNames.contains(isDeletedColumn)) {
            isDeletedColumn = "__" + IS_DELETED_COLUMN;
        }
        if(DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine == true) {
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(isDeletedColumn).append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
        } else {
            this.query.append("`").append(SIGN_COLUMN).append("` ").append(SIGN_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE);
        }

        this.query.append(")");
        if(DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine == true) {
            this.query.append(" Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(",").append(isDeletedColumn).append(")");
        } else
            this.query.append(" Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(")");

        if(partitionByColumn.length() > 0) {
            this.query.append(Constants.PARTITION_BY).append(" ").append(partitionByColumn);
        }
        if(orderByColumns.length() == 0) {
        this.query.append(Constants.ORDER_BY_TUPLE);
        } else {
            this.query.append(Constants.ORDER_BY).append(orderByColumns.toString());
        }

    }

    private Set<String> parseCreateTable(MySqlParser.CreateTableContext ctx, StringBuilder orderByColumns,
                                  StringBuilder partitionByColumns) {
        List<ParseTree> pt = ctx.children;
        Set<String> columnNames = new HashSet<>();

        this.query.append(Constants.CREATE_TABLE).append(" ");
        for (ParseTree tree : pt) {

            if (tree instanceof TableNameContext) {
                this.query.append(tree.getText()).append("(");
            }else if(tree instanceof MySqlParser.IfNotExistsContext) {
                this.query.append(Constants.IF_NOT_EXISTS);
            }else if (tree instanceof MySqlParser.CreateDefinitionsContext) {
                for (ParseTree subtree : ((MySqlParser.CreateDefinitionsContext) tree).children) {
                    if (subtree instanceof TerminalNodeImpl) {
                       // this.query.append(subtree.getText());
                    } else if (subtree instanceof MySqlParser.ColumnDeclarationContext) {
                        parseColumnDefinitions(subtree, orderByColumns, columnNames);
                    } else if(subtree instanceof MySqlParser.ConstraintDeclarationContext) {
                        for(ParseTree constraintTree: ((MySqlParser.ConstraintDeclarationContext) subtree).children) {
                            if(constraintTree instanceof MySqlParser.PrimaryKeyTableConstraintContext) {
                                for(ParseTree primaryKeyTree: ((MySqlParser.PrimaryKeyTableConstraintContext) constraintTree).children) {
                                    if(primaryKeyTree instanceof MySqlParser.IndexColumnNamesContext) {
                                        String primaryKeyColumns = primaryKeyTree.getText();
                                        if(primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) {
                                            orderByColumns.append(primaryKeyColumns);
                                        }
                                        //log.info("PRIMARY KEY");
                                    }

                                }
                            }
                        }
                    }
                }
            } else if(tree instanceof MySqlParser.PartitionDefinitionsContext) {
                for(ParseTree partitionTree: ((MySqlParser.PartitionDefinitionsContext) tree).children) {
                    if(partitionTree instanceof MySqlParser.PartitionFunctionKeyContext) {
                        for(ParseTree partitionKeyTree: ((MySqlParser.PartitionFunctionKeyContext) partitionTree).children) {
                            if(partitionKeyTree instanceof MySqlParser.UidListContext) {
                                String partitionColumn = partitionKeyTree.getText();
                                partitionByColumns.append(partitionColumn);
                            }
                        }

                    } else if(partitionTree instanceof MySqlParser.PartitionFunctionRangeContext) {
                        for(ParseTree partitionFunctionRangeTree: ((MySqlParser.PartitionFunctionRangeContext) partitionTree).children) {
                            if(partitionFunctionRangeTree instanceof MySqlParser.UidListContext) {
                                partitionByColumns.append("(").append(partitionFunctionRangeTree.getText()).append(")");
                            }
                        }

                    }
                }
            }
        }

        return columnNames;
    }

    /**
     * Function to parse column definitions.
     * @param subtree
     * @param orderByColumns
     *
     * @return list of column names
     */
    private void parseColumnDefinitions(ParseTree subtree, StringBuilder orderByColumns, Set<String> columnNames) {
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

                colDataType = getClickHouseDataType(colDataTypeDefinition, colDefTree, columnName);
                // Null Column and DimensionDataType are children of ColumnDefinition
                for(ParseTree colDefinitionChildTree: ((MySqlParser.ColumnDefinitionContext) colDefTree).children) {
                    if (colDefinitionChildTree instanceof MySqlParser.NullColumnConstraintContext) {
                        if (colDefinitionChildTree.getText().equalsIgnoreCase(Constants.NOT_NULL))
                            isNullColumn = false;
                    } else if(colDefinitionChildTree instanceof MySqlParser.DimensionDataTypeContext) {
                        if (colDefinitionChildTree.getText() != null) {

                        }
                    } else if (colDefinitionChildTree instanceof MySqlParser.PrimaryKeyColumnConstraintContext) {
                        for(ParseTree primaryKeyTree: ((MySqlParser.PrimaryKeyColumnConstraintContext) colDefinitionChildTree).children) {
                            System.out.println(primaryKeyTree.getText());
                            orderByColumns.append(columnName);
                            break;
                        }
                    } else if (colDefinitionChildTree instanceof MySqlParser.GeneratedColumnConstraintContext) {
                        for(ParseTree generatedColumnTree: ((MySqlParser.GeneratedColumnConstraintContext) colDefinitionChildTree).children) {
                            if(generatedColumnTree instanceof MySqlParser.ExpressionContext) {
                                isGeneratedColumn = true;
                                generatedColumn = generatedColumnTree.getText();
                                //this.query.append(Constants.AS).append(" ").append(expression);
                            }
                        }

                    }
                }
                if(isGeneratedColumn) {
                    if(isNullColumn){
                        this.query.append(Constants.NULLABLE).append("(").append(colDataType)
                                .append(")");
                    } else
                        this.query.append(colDataType);

                    this.query.append(" ").append(Constants.ALIAS).append(" ").append(generatedColumn).append(",");
                    continue;
                }

                if(isNullColumn) {
                    this.query.append(Constants.NULLABLE).append("(").append(colDataType)
                            .append(")").append(",");
                }
                else {
                    this.query.append(colDataType).append(" ").append(Constants.NOT_NULLABLE).append(" ").append(",");
                }
                columnNames.add(columnName);
            }
        }
    }

    /**
     * Function to get the ClickHouse Data type from DDL Datatype.
     * @param parsedDataType
     * @return
     */
    private String getClickHouseDataType(String parsedDataType, ParseTree colDefTree, String columnName) {
        int precision = 0;
        int scale = 0;

        String chDataType = null;
        MySqlParser.DataTypeContext dtc = ((MySqlParser.ColumnDefinitionContext) colDefTree).dataType();

        if(parsedDataType.contains("(") && parsedDataType.contains(")") && parsedDataType.contains(",")) {
            try {
                precision = Integer.parseInt(parsedDataType.substring(parsedDataType.indexOf("(") + 1, parsedDataType.indexOf(",")));
                scale = Integer.parseInt(parsedDataType.substring(parsedDataType.indexOf(",") + 1, parsedDataType.indexOf(")")));
            } catch(Exception e) {
                log.error("Error parsing precision, scale : columnName" + columnName);
            }
        }  // datetime(6)
        else if(parsedDataType.contains("(") && parsedDataType.contains(")") &&
                (parsedDataType.contains("datetime") || parsedDataType.contains("timestamp"))){
            try {
                precision = Integer.parseInt(parsedDataType.substring(parsedDataType.indexOf("(") + 1, parsedDataType.indexOf(")")));
            } catch(Exception e) {
                log.error("Error parsing precision:ColumnName:" + columnName);
            }
        }

        chDataType = DataTypeConverter.convertToString(columnName,
                scale, precision, dtc, this.userProvidedTimeZone);

        return chDataType;

    }


    private void parseAddIndex(ParseTree tree) {

        // add index col3_index(col3) TYPE minmax GRANULARITY 4;
        for (ParseTree columnChild : ((MySqlParser.AlterByAddIndexContext) tree).children) {

            if (columnChild instanceof MySqlParser.IfNotExistsContext) {

            } else if (columnChild instanceof MySqlParser.UidContext) {
                // Name of index.

            } else if (columnChild instanceof MySqlParser.IndexTypeContext) {
                // Index type

            } else if (columnChild instanceof MySqlParser.IndexColumnNamesContext) {
                for (ParseTree columnNameChild : ((MySqlParser.IndexColumnNamesContext) (columnChild)).children) {
                    // Column Name
                }

            } else if (columnChild instanceof MySqlParser.IndexOptionContext) {
                // Index option
                // comment
            }
        }
    }

    private void parseRenameColumn(ParseTree tree) {
        ListIterator<ParseTree> it = ((MySqlParser.AlterSpecificationContext) tree).children.listIterator();
        // this.query.append(" ").append(Constants.RENAME_COLUMN);
        //for (ParseTree columnChild : ((MySqlParser.AlterSpecificationContext) tree).children) {
        while (it.hasNext()) {
            ParseTree child = it.next();
            if (child instanceof MySqlParser.UidContext) {
                this.query.append(" ").append(child.getText());
            } else if (child instanceof TerminalNodeImpl) {
                this.query.append(" ").append(child.getText());
            }
        }
    }

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
        if (tree instanceof AlterByAddColumnContext) {
            modifier = Constants.ADD_COLUMN;
            modifierWithNull = Constants.ADD_COLUMN_NULLABLE;
            isNullColumn = true;

        } else if (tree instanceof MySqlParser.AlterByModifyColumnContext) {
            modifier = Constants.MODIFY_COLUMN;
            modifierWithNull = Constants.MODIFY_COLUMN_NULLABLE;
        } else if (tree instanceof MySqlParser.AlterByRenameColumnContext) {
            modifier = Constants.RENAME_COLUMN;
            modifierWithNull = Constants.RENAME_COLUMN_NULLABLE;

        } else if (tree instanceof MySqlParser.AlterByChangeColumnContext) {
            isAlterChangeColumn = true;
            modifier = Constants.MODIFY_COLUMN;
            modifierWithNull = Constants.MODIFY_COLUMN_NULLABLE;
        } else if (tree instanceof MySqlParser.AlterByAddIndexContext) {
            modifier = Constants.ADD_INDEX;
        } else {
            //log.error("Not support Alter specification context");
            return;
        }
        ListIterator<ParseTree> it = ((MySqlParser.AlterSpecificationContext) tree).children.listIterator();

        //for (ParseTree columnChild : ((MySqlParser.AlterSpecificationContext) tree).children) {
        while (it.hasNext()) {
            ParseTree columnChild = it.next();
            if (columnChild instanceof MySqlParser.UidContext) {
                columnName = (columnChild).getText();
                if (isAlterChangeColumn) {
                    // Change column comes in this format ALTER TABLE change column oldcol newcol.
                    ParseTree newColumnChild = it.next();
                    newColumnName = newColumnChild.getText();
                }
            } else if (columnChild instanceof MySqlParser.ColumnDefinitionContext) {

                for (ParseTree columnDefChild : ((MySqlParser.ColumnDefinitionContext) columnChild).children) {
                    if (columnDefChild instanceof MySqlParser.NullColumnConstraintContext) {
                        if (columnDefChild.getText().equalsIgnoreCase(Constants.NULL))
                            isNullColumn = true;
                        else if(columnDefChild.getText().equalsIgnoreCase(Constants.NOT_NULL)) {
                            isNullColumn = false;
                        }
                    } else if (columnDefChild instanceof MySqlParser.DefaultColumnConstraintContext) {
                        if (columnDefChild.getChildCount() >= 2) {
                            defaultModifier = "DEFAULT " + columnDefChild.getChild(1).getText();
                        }
                    } else if (columnDefChild instanceof MySqlParser.DimensionDataTypeContext || columnDefChild instanceof MySqlParser.SimpleDataTypeContext
                            || columnDefChild instanceof MySqlParser.StringDataTypeContext) {
                        columnType = (columnDefChild.getText());
                        String chDataType = getClickHouseDataType(columnType, columnChild, columnName);
                           if (chDataType != null) {
                                columnType = chDataType;
                            }

                        }

                }
            } else if (columnChild instanceof TerminalNodeImpl) {
                String columnPosition = columnChild.getText();
                if (columnPosition.equalsIgnoreCase(Constants.AFTER)) {
                    // The next element in the tree will be the column
                    if (it.hasNext()) {
                        columnPositionModifier.append(columnPosition).append(" ").append(it.next().getText());
                    }
                } else if (columnPosition.equalsIgnoreCase(Constants.FIRST)) {
                    columnPositionModifier.append(columnPosition);
                }
                // columnName = columnName + " " + columnChild.getText();

            }
        }

        if (columnName != null && columnType != null)
            if (isNullColumn) {
                this.query.append(" ").append(String.format(modifierWithNull, columnName, columnType)).append(" ");
            } else
                this.query.append(" ").append(String.format(modifier, columnName, columnType));
        if (defaultModifier != null && defaultModifier.isEmpty() == false) {
            this.query.append(" ").append(defaultModifier);
        }
        if (columnPositionModifier.length() != 0) {
            this.query.append(" ").append(columnPositionModifier);
        }

        if (isAlterChangeColumn) {
            postProcessModifyColumn(this.tableName, columnName, newColumnName, columnType);
        }

        String trimmedQuery = this.query.toString().trim();
        this.query.delete(0, this.query.toString().length()).append(trimmedQuery);
    }

    /**
     * Function to create MODIFY column to rename the column name
     *
     * @param tableName
     * @param oldCol
     * @param newCol
     * @param dataType
     */
    public void postProcessModifyColumn(String tableName, String oldCol, String newCol, String dataType) {
        this.query.append("\n");
        this.query.append(String.format("ALTER TABLE %s RENAME COLUMN %s to %s", tableName, oldCol, newCol));

    }

    @Override
    public void enterAlterTable(MySqlParser.AlterTableContext alterTableContext) {


        List<ParseTree> pt = alterTableContext.children;
        for (ParseTree tree : pt) {

            if (tree instanceof TableNameContext) {
                this.tableName = tree.getText();
                this.query.append(String.format(Constants.ALTER_TABLE, tree.getText()));
            }

            if (tree instanceof AlterByAddColumnContext) {
                parseAlterTable(tree);

            } else if (tree instanceof MySqlParser.AlterByModifyColumnContext) {
                parseAlterTable(tree);
            } else if (tree instanceof MySqlParser.AlterByDropColumnContext) {
            } else if (tree instanceof MySqlParser.AlterByRenameColumnContext) {
                parseRenameColumn(tree);
            } else if (tree instanceof MySqlParser.AlterByAddPrimaryKeyContext) {
                parseAlterTable(tree);
            } else if (tree instanceof MySqlParser.AlterByChangeColumnContext) {
                parseAlterTable(tree);
            } else if (tree instanceof MySqlParser.AlterByAddIndexContext) {
                parseAddIndex(tree);
            } else if (tree instanceof TerminalNodeImpl) {
                if (((TerminalNodeImpl) tree).symbol.getType() == MySqlParser.COMMA) {
                    this.query.append(",");
                }
            } else if(tree instanceof MySqlParser.AlterByRenameContext) {
                parseAlterTableByRename(tableName, (MySqlParser.AlterByRenameContext) tree);
            }
        }
    }

    private void parseAlterTableByRename(String originalTableName, MySqlParser.AlterByRenameContext tree) {
        String newTableName = null;
        for(ParseTree alterByRenameChildren: tree.children) {
            if(alterByRenameChildren instanceof MySqlParser.UidContext) {
                newTableName = alterByRenameChildren.getText();
            }
        }

        this.query.delete(0, this.query.toString().length()).append(String.format(Constants.ALTER_RENAME_TABLE, originalTableName, newTableName));

    }
    @Override
    public void enterAlterByAddCheckTableConstraint(MySqlParser.AlterByAddCheckTableConstraintContext alterByAddCheckTableConstraintContext) {
        // log.info("Enter check table constraint: " + alterByAddCheckTableConstraintContext.getText() );
        this.query.append(" ");
        for (ParseTree tree : alterByAddCheckTableConstraintContext.children) {
            if (tree instanceof MySqlParser.PredicateExpressionContext) {
                this.query.append(tree.getText());
            } else if (tree instanceof MySqlParser.UidContext) {
                this.query.append(tree.getText()).append(" ");
            } else if (tree instanceof TerminalNodeImpl) {
                this.query.append(tree.getText()).append(" ");
            }
        }
    }

    @Override
    public void enterAlterByDropColumn(MySqlParser.AlterByDropColumnContext alterByDropColumnContext) {
        this.query.append(" ");
        for (ParseTree tree : alterByDropColumnContext.children) {
            if (tree instanceof MySqlParser.UidContext) {
                this.query.append(String.format(Constants.DROP_COLUMN, tree.getText()));
            }
        }
    }

    @Override
    public void enterDropTable(MySqlParser.DropTableContext dropTableContext) {
        log.debug("DROP TABLE enter");
        this.query.append(Constants.DROP_TABLE).append(" ");
        for (ParseTree child : dropTableContext.children) {
            if (child instanceof MySqlParser.TablesContext) {
                for (ParseTree tableNameChild : ((MySqlParser.TablesContext) child).children) {
                    if (tableNameChild instanceof MySqlParser.TableNameContext) {
                        this.query.append(tableNameChild.getText());
                    } else if (tableNameChild instanceof TerminalNodeImpl) {
                        this.query.append(tableNameChild.getText());
                    }
                }
            } else if(child instanceof MySqlParser.IfExistsContext) {
                this.query.append(Constants.IF_EXISTS);
            }
        }
    }


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
                    this.query.append(originalTableName).append(" to ").append(newTableName);
                }
            } else if(child instanceof TerminalNodeImpl) {
                if (((TerminalNodeImpl) child).symbol.getType() == MySqlParser.COMMA) {
                    this.query.append(",");
                }
            }
        }
//
//        if (originalTableName != null && originalTableName.isEmpty() == false && newTableName != null &&
//                newTableName.isEmpty() == false) {
//            this.query.append(String.format(Constants.RENAME_TABLE, originalTableName, newTableName));
//        }
    }

    @Override
    public void enterTruncateTable(MySqlParser.TruncateTableContext truncateTableContext) {
        for (ParseTree child : truncateTableContext.children) {
            if (child instanceof MySqlParser.TableNameContext) {
                this.query.append(String.format(Constants.TRUNCATE_TABLE, child.getText()));
            }
        }
    }
}