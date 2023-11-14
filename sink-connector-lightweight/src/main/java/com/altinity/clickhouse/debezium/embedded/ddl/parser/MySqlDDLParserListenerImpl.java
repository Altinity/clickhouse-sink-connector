package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.DataTypeConverter;
import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.AlterByAddColumnContext;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.TableNameContext;
import io.debezium.ddl.parser.mysql.generated.MySqlParserListener;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ListIterator;

public class MySqlDDLParserListenerImpl implements MySqlParserListener {

    private static final Logger log = LoggerFactory.getLogger(MySqlDDLParserListenerImpl.class);

    StringBuffer query;

    String tableName;

    public MySqlDDLParserListenerImpl(StringBuffer transformedQuery, String tableName) {
        this.query = transformedQuery;
        this.tableName = tableName;
    }

    @Override
    public void enterRoot(MySqlParser.RootContext rootContext) {

    }

    @Override
    public void exitRoot(MySqlParser.RootContext rootContext) {

    }

    @Override
    public void enterSqlStatements(MySqlParser.SqlStatementsContext sqlStatementsContext) {

    }

    @Override
    public void exitSqlStatements(MySqlParser.SqlStatementsContext sqlStatementsContext) {

    }

    @Override
    public void enterSqlStatement(MySqlParser.SqlStatementContext sqlStatementContext) {

    }

    @Override
    public void exitSqlStatement(MySqlParser.SqlStatementContext sqlStatementContext) {

    }

    @Override
    public void enterSetStatementFor(MySqlParser.SetStatementForContext setStatementForContext) {

    }

    @Override
    public void exitSetStatementFor(MySqlParser.SetStatementForContext setStatementForContext) {

    }

    @Override
    public void enterEmptyStatement(MySqlParser.EmptyStatementContext emptyStatementContext) {

    }

    @Override
    public void exitEmptyStatement(MySqlParser.EmptyStatementContext emptyStatementContext) {

    }

    @Override
    public void enterDdlStatement(MySqlParser.DdlStatementContext ddlStatementContext) {

    }

    @Override
    public void exitDdlStatement(MySqlParser.DdlStatementContext ddlStatementContext) {

    }

    @Override
    public void enterDmlStatement(MySqlParser.DmlStatementContext dmlStatementContext) {

    }

    @Override
    public void exitDmlStatement(MySqlParser.DmlStatementContext dmlStatementContext) {

    }

    @Override
    public void enterTransactionStatement(MySqlParser.TransactionStatementContext transactionStatementContext) {

    }

    @Override
    public void exitTransactionStatement(MySqlParser.TransactionStatementContext transactionStatementContext) {

    }

    @Override
    public void enterReplicationStatement(MySqlParser.ReplicationStatementContext replicationStatementContext) {

    }

    @Override
    public void exitReplicationStatement(MySqlParser.ReplicationStatementContext replicationStatementContext) {

    }

    @Override
    public void enterPreparedStatement(MySqlParser.PreparedStatementContext preparedStatementContext) {

    }

    @Override
    public void exitPreparedStatement(MySqlParser.PreparedStatementContext preparedStatementContext) {

    }

    @Override
    public void enterCompoundStatement(MySqlParser.CompoundStatementContext compoundStatementContext) {

    }

    @Override
    public void exitCompoundStatement(MySqlParser.CompoundStatementContext compoundStatementContext) {

    }

    @Override
    public void enterAdministrationStatement(MySqlParser.AdministrationStatementContext administrationStatementContext) {

    }

    @Override
    public void exitAdministrationStatement(MySqlParser.AdministrationStatementContext administrationStatementContext) {

    }

    @Override
    public void enterUtilityStatement(MySqlParser.UtilityStatementContext utilityStatementContext) {

    }

    @Override
    public void exitUtilityStatement(MySqlParser.UtilityStatementContext utilityStatementContext) {

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
    public void exitCreateDatabase(MySqlParser.CreateDatabaseContext createDatabaseContext) {

    }

    @Override
    public void enterCreateEvent(MySqlParser.CreateEventContext createEventContext) {

    }

    @Override
    public void exitCreateEvent(MySqlParser.CreateEventContext createEventContext) {

    }

    @Override
    public void enterCreateIndex(MySqlParser.CreateIndexContext createIndexContext) {

    }

    @Override
    public void exitCreateIndex(MySqlParser.CreateIndexContext createIndexContext) {

    }

    @Override
    public void enterCreateLogfileGroup(MySqlParser.CreateLogfileGroupContext createLogfileGroupContext) {

    }

    @Override
    public void exitCreateLogfileGroup(MySqlParser.CreateLogfileGroupContext createLogfileGroupContext) {

    }

    @Override
    public void enterCreateProcedure(MySqlParser.CreateProcedureContext createProcedureContext) {

    }

    @Override
    public void exitCreateProcedure(MySqlParser.CreateProcedureContext createProcedureContext) {

    }

    @Override
    public void enterCreateFunction(MySqlParser.CreateFunctionContext createFunctionContext) {

    }

    @Override
    public void exitCreateFunction(MySqlParser.CreateFunctionContext createFunctionContext) {

    }

    @Override
    public void enterCreateRole(MySqlParser.CreateRoleContext createRoleContext) {

    }

    @Override
    public void exitCreateRole(MySqlParser.CreateRoleContext createRoleContext) {

    }

    @Override
    public void enterCreateServer(MySqlParser.CreateServerContext createServerContext) {

    }

    @Override
    public void exitCreateServer(MySqlParser.CreateServerContext createServerContext) {

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
    public void exitCopyCreateTable(MySqlParser.CopyCreateTableContext copyCreateTableContext) {

    }

    @Override
    public void enterQueryCreateTable(MySqlParser.QueryCreateTableContext ctx) {

    }

    @Override
    public void exitQueryCreateTable(MySqlParser.QueryCreateTableContext ctx) {
    }

    @Override
    public void enterColumnCreateTable(MySqlParser.ColumnCreateTableContext columnCreateTableContext) {
        StringBuilder orderByColumns = new StringBuilder();
        StringBuilder partitionByColumn = new StringBuilder();
        parseCreateTable(columnCreateTableContext, orderByColumns, partitionByColumn);
        //this.query.append(" Engine=")
        if(DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine == true) {
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(IS_DELETED_COLUMN).append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
        } else {
            this.query.append("`").append(SIGN_COLUMN).append("` ").append(SIGN_COLUMN_DATA_TYPE).append(",");
            this.query.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE);
        }

        this.query.append(")");
        if(DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine == true) {
            this.query.append(" Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(",").append(IS_DELETED_COLUMN).append(")");
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

    private void parseCreateTable(MySqlParser.CreateTableContext ctx, StringBuilder orderByColumns,
                                  StringBuilder partitionByColumns) {
        List<ParseTree> pt = ctx.children;


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
                        String columnName = null;
                        String colDataType = null;
                        boolean isNullColumn = true;
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
                                    }
                                }
                                if(isNullColumn) {
                                    this.query.append(Constants.NULLABLE).append("(").append(colDataType)
                                            .append(")").append(",");
                                } else {
                                    this.query.append(colDataType).append(" ").append(Constants.NOT_NULLABLE).append(" ").append(",");
                                }
                            }
                        }
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
        if(parsedDataType.contains("(") && parsedDataType.contains(")") && parsedDataType.contains(",")) {
            try {
                precision = Integer.parseInt(parsedDataType.substring(parsedDataType.indexOf("(") + 1, parsedDataType.indexOf(",")));
                scale = Integer.parseInt(parsedDataType.substring(parsedDataType.indexOf(",") + 1, parsedDataType.indexOf(")")));
            } catch(Exception e) {
                log.error("Error parsing precision, scale");
            }
        }

        MySqlParser.DataTypeContext dtc = ((MySqlParser.ColumnDefinitionContext) colDefTree).dataType();
        chDataType = DataTypeConverter.convertToString(columnName,
                scale, precision, dtc);

        return chDataType;

    }


    @Override
    public void exitColumnCreateTable(MySqlParser.ColumnCreateTableContext columnCreateTableContext) {

    }

    @Override
    public void enterCreateTablespaceInnodb(MySqlParser.CreateTablespaceInnodbContext createTablespaceInnodbContext) {

    }

    @Override
    public void exitCreateTablespaceInnodb(MySqlParser.CreateTablespaceInnodbContext createTablespaceInnodbContext) {

    }

    @Override
    public void enterCreateTablespaceNdb(MySqlParser.CreateTablespaceNdbContext createTablespaceNdbContext) {

    }

    @Override
    public void exitCreateTablespaceNdb(MySqlParser.CreateTablespaceNdbContext createTablespaceNdbContext) {

    }

    @Override
    public void enterCreateTrigger(MySqlParser.CreateTriggerContext createTriggerContext) {

    }

    @Override
    public void exitCreateTrigger(MySqlParser.CreateTriggerContext createTriggerContext) {

    }

    @Override
    public void enterWithClause(MySqlParser.WithClauseContext withClauseContext) {

    }

    @Override
    public void exitWithClause(MySqlParser.WithClauseContext withClauseContext) {

    }

    @Override
    public void enterCommonTableExpressions(MySqlParser.CommonTableExpressionsContext commonTableExpressionsContext) {

    }

    @Override
    public void exitCommonTableExpressions(MySqlParser.CommonTableExpressionsContext commonTableExpressionsContext) {

    }

    @Override
    public void enterCteName(MySqlParser.CteNameContext cteNameContext) {

    }

    @Override
    public void exitCteName(MySqlParser.CteNameContext cteNameContext) {

    }

    @Override
    public void enterCteColumnName(MySqlParser.CteColumnNameContext cteColumnNameContext) {

    }

    @Override
    public void exitCteColumnName(MySqlParser.CteColumnNameContext cteColumnNameContext) {

    }

    @Override
    public void enterCreateView(MySqlParser.CreateViewContext createViewContext) {

    }

    @Override
    public void exitCreateView(MySqlParser.CreateViewContext createViewContext) {

    }

    @Override
    public void enterCreateSequence(MySqlParser.CreateSequenceContext createSequenceContext) {

    }

    @Override
    public void exitCreateSequence(MySqlParser.CreateSequenceContext createSequenceContext) {

    }

    @Override
    public void enterSequenceSpec(MySqlParser.SequenceSpecContext sequenceSpecContext) {

    }

    @Override
    public void exitSequenceSpec(MySqlParser.SequenceSpecContext sequenceSpecContext) {

    }

    @Override
    public void enterCreateDatabaseOption(MySqlParser.CreateDatabaseOptionContext createDatabaseOptionContext) {

    }

    @Override
    public void exitCreateDatabaseOption(MySqlParser.CreateDatabaseOptionContext createDatabaseOptionContext) {

    }

    @Override
    public void enterCharSet(MySqlParser.CharSetContext charSetContext) {

    }

    @Override
    public void exitCharSet(MySqlParser.CharSetContext charSetContext) {

    }

    @Override
    public void enterCurrentUserExpression(MySqlParser.CurrentUserExpressionContext currentUserExpressionContext) {

    }

    @Override
    public void exitCurrentUserExpression(MySqlParser.CurrentUserExpressionContext currentUserExpressionContext) {

    }

    @Override
    public void enterOwnerStatement(MySqlParser.OwnerStatementContext ownerStatementContext) {

    }

    @Override
    public void exitOwnerStatement(MySqlParser.OwnerStatementContext ownerStatementContext) {

    }

    @Override
    public void enterPreciseSchedule(MySqlParser.PreciseScheduleContext preciseScheduleContext) {

    }

    @Override
    public void exitPreciseSchedule(MySqlParser.PreciseScheduleContext preciseScheduleContext) {

    }

    @Override
    public void enterIntervalSchedule(MySqlParser.IntervalScheduleContext intervalScheduleContext) {

    }

    @Override
    public void exitIntervalSchedule(MySqlParser.IntervalScheduleContext intervalScheduleContext) {

    }

    @Override
    public void enterTimestampValue(MySqlParser.TimestampValueContext timestampValueContext) {

    }

    @Override
    public void exitTimestampValue(MySqlParser.TimestampValueContext timestampValueContext) {

    }

    @Override
    public void enterIntervalExpr(MySqlParser.IntervalExprContext intervalExprContext) {

    }

    @Override
    public void exitIntervalExpr(MySqlParser.IntervalExprContext intervalExprContext) {

    }

    @Override
    public void enterIntervalType(MySqlParser.IntervalTypeContext intervalTypeContext) {

    }

    @Override
    public void exitIntervalType(MySqlParser.IntervalTypeContext intervalTypeContext) {

    }

    @Override
    public void enterEnableType(MySqlParser.EnableTypeContext enableTypeContext) {

    }

    @Override
    public void exitEnableType(MySqlParser.EnableTypeContext enableTypeContext) {

    }

    @Override
    public void enterIndexType(MySqlParser.IndexTypeContext indexTypeContext) {

    }

    @Override
    public void exitIndexType(MySqlParser.IndexTypeContext indexTypeContext) {

    }

    @Override
    public void enterIndexOption(MySqlParser.IndexOptionContext indexOptionContext) {

    }

    @Override
    public void exitIndexOption(MySqlParser.IndexOptionContext indexOptionContext) {

    }

    @Override
    public void enterProcedureParameter(MySqlParser.ProcedureParameterContext procedureParameterContext) {

    }

    @Override
    public void exitProcedureParameter(MySqlParser.ProcedureParameterContext procedureParameterContext) {

    }

    @Override
    public void enterFunctionParameter(MySqlParser.FunctionParameterContext functionParameterContext) {

    }

    @Override
    public void exitFunctionParameter(MySqlParser.FunctionParameterContext functionParameterContext) {

    }

    @Override
    public void enterRoutineComment(MySqlParser.RoutineCommentContext routineCommentContext) {

    }

    @Override
    public void exitRoutineComment(MySqlParser.RoutineCommentContext routineCommentContext) {

    }

    @Override
    public void enterRoutineLanguage(MySqlParser.RoutineLanguageContext routineLanguageContext) {

    }

    @Override
    public void exitRoutineLanguage(MySqlParser.RoutineLanguageContext routineLanguageContext) {

    }

    @Override
    public void enterRoutineBehavior(MySqlParser.RoutineBehaviorContext routineBehaviorContext) {

    }

    @Override
    public void exitRoutineBehavior(MySqlParser.RoutineBehaviorContext routineBehaviorContext) {

    }

    @Override
    public void enterRoutineData(MySqlParser.RoutineDataContext routineDataContext) {

    }

    @Override
    public void exitRoutineData(MySqlParser.RoutineDataContext routineDataContext) {

    }

    @Override
    public void enterRoutineSecurity(MySqlParser.RoutineSecurityContext routineSecurityContext) {

    }

    @Override
    public void exitRoutineSecurity(MySqlParser.RoutineSecurityContext routineSecurityContext) {

    }

    @Override
    public void enterServerOption(MySqlParser.ServerOptionContext serverOptionContext) {

    }

    @Override
    public void exitServerOption(MySqlParser.ServerOptionContext serverOptionContext) {

    }

    @Override
    public void enterCreateDefinitions(MySqlParser.CreateDefinitionsContext createDefinitionsContext) {

    }

    @Override
    public void exitCreateDefinitions(MySqlParser.CreateDefinitionsContext createDefinitionsContext) {

    }

    @Override
    public void enterColumnDeclaration(MySqlParser.ColumnDeclarationContext columnDeclarationContext) {

    }

    @Override
    public void exitColumnDeclaration(MySqlParser.ColumnDeclarationContext columnDeclarationContext) {

    }

    @Override
    public void enterConstraintDeclaration(MySqlParser.ConstraintDeclarationContext constraintDeclarationContext) {

    }

    @Override
    public void exitConstraintDeclaration(MySqlParser.ConstraintDeclarationContext constraintDeclarationContext) {

    }

    @Override
    public void enterIndexDeclaration(MySqlParser.IndexDeclarationContext indexDeclarationContext) {

    }

    @Override
    public void exitIndexDeclaration(MySqlParser.IndexDeclarationContext indexDeclarationContext) {

    }

    @Override
    public void enterColumnDefinition(MySqlParser.ColumnDefinitionContext columnDefinitionContext) {

    }

    @Override
    public void exitColumnDefinition(MySqlParser.ColumnDefinitionContext columnDefinitionContext) {

    }

    @Override
    public void enterNullColumnConstraint(MySqlParser.NullColumnConstraintContext nullColumnConstraintContext) {

    }

    @Override
    public void exitNullColumnConstraint(MySqlParser.NullColumnConstraintContext nullColumnConstraintContext) {

    }

    @Override
    public void enterDefaultColumnConstraint(MySqlParser.DefaultColumnConstraintContext defaultColumnConstraintContext) {

    }

    @Override
    public void exitDefaultColumnConstraint(MySqlParser.DefaultColumnConstraintContext defaultColumnConstraintContext) {

    }

    @Override
    public void enterVisibilityColumnConstraint(MySqlParser.VisibilityColumnConstraintContext visibilityColumnConstraintContext) {

    }

    @Override
    public void exitVisibilityColumnConstraint(MySqlParser.VisibilityColumnConstraintContext visibilityColumnConstraintContext) {

    }

    @Override
    public void enterInvisibilityColumnConstraint(MySqlParser.InvisibilityColumnConstraintContext invisibilityColumnConstraintContext) {

    }

    @Override
    public void exitInvisibilityColumnConstraint(MySqlParser.InvisibilityColumnConstraintContext invisibilityColumnConstraintContext) {

    }

    @Override
    public void enterAutoIncrementColumnConstraint(MySqlParser.AutoIncrementColumnConstraintContext autoIncrementColumnConstraintContext) {

    }

    @Override
    public void exitAutoIncrementColumnConstraint(MySqlParser.AutoIncrementColumnConstraintContext autoIncrementColumnConstraintContext) {

    }

    @Override
    public void enterPrimaryKeyColumnConstraint(MySqlParser.PrimaryKeyColumnConstraintContext primaryKeyColumnConstraintContext) {

    }

    @Override
    public void exitPrimaryKeyColumnConstraint(MySqlParser.PrimaryKeyColumnConstraintContext primaryKeyColumnConstraintContext) {

    }

    @Override
    public void enterClusteringKeyColumnConstraint(MySqlParser.ClusteringKeyColumnConstraintContext clusteringKeyColumnConstraintContext) {

    }

    @Override
    public void exitClusteringKeyColumnConstraint(MySqlParser.ClusteringKeyColumnConstraintContext clusteringKeyColumnConstraintContext) {

    }

//    @Override
//    public void enterClusteringKeyColumnConstraint(MySqlParser.ClusteringKeyColumnConstraintContext clusteringKeyColumnConstraintContext) {
//
//    }
//
//    @Override
//    public void exitClusteringKeyColumnConstraint(MySqlParser.ClusteringKeyColumnConstraintContext clusteringKeyColumnConstraintContext) {
//
//    }

    @Override
    public void enterUniqueKeyColumnConstraint(MySqlParser.UniqueKeyColumnConstraintContext uniqueKeyColumnConstraintContext) {

    }

    @Override
    public void exitUniqueKeyColumnConstraint(MySqlParser.UniqueKeyColumnConstraintContext uniqueKeyColumnConstraintContext) {

    }

    @Override
    public void enterCommentColumnConstraint(MySqlParser.CommentColumnConstraintContext commentColumnConstraintContext) {

    }

    @Override
    public void exitCommentColumnConstraint(MySqlParser.CommentColumnConstraintContext commentColumnConstraintContext) {

    }

    @Override
    public void enterFormatColumnConstraint(MySqlParser.FormatColumnConstraintContext formatColumnConstraintContext) {

    }

    @Override
    public void exitFormatColumnConstraint(MySqlParser.FormatColumnConstraintContext formatColumnConstraintContext) {

    }

    @Override
    public void enterStorageColumnConstraint(MySqlParser.StorageColumnConstraintContext storageColumnConstraintContext) {

    }

    @Override
    public void exitStorageColumnConstraint(MySqlParser.StorageColumnConstraintContext storageColumnConstraintContext) {

    }

    @Override
    public void enterReferenceColumnConstraint(MySqlParser.ReferenceColumnConstraintContext referenceColumnConstraintContext) {

    }

    @Override
    public void exitReferenceColumnConstraint(MySqlParser.ReferenceColumnConstraintContext referenceColumnConstraintContext) {

    }

    @Override
    public void enterCollateColumnConstraint(MySqlParser.CollateColumnConstraintContext collateColumnConstraintContext) {

    }

    @Override
    public void exitCollateColumnConstraint(MySqlParser.CollateColumnConstraintContext collateColumnConstraintContext) {

    }

    @Override
    public void enterGeneratedColumnConstraint(MySqlParser.GeneratedColumnConstraintContext generatedColumnConstraintContext) {

    }

    @Override
    public void exitGeneratedColumnConstraint(MySqlParser.GeneratedColumnConstraintContext generatedColumnConstraintContext) {

    }

    @Override
    public void enterSerialDefaultColumnConstraint(MySqlParser.SerialDefaultColumnConstraintContext serialDefaultColumnConstraintContext) {

    }

    @Override
    public void exitSerialDefaultColumnConstraint(MySqlParser.SerialDefaultColumnConstraintContext serialDefaultColumnConstraintContext) {

    }

    @Override
    public void enterCheckColumnConstraint(MySqlParser.CheckColumnConstraintContext checkColumnConstraintContext) {

    }

    @Override
    public void exitCheckColumnConstraint(MySqlParser.CheckColumnConstraintContext checkColumnConstraintContext) {

    }

    @Override
    public void enterPrimaryKeyTableConstraint(MySqlParser.PrimaryKeyTableConstraintContext primaryKeyTableConstraintContext) {

    }

    @Override
    public void exitPrimaryKeyTableConstraint(MySqlParser.PrimaryKeyTableConstraintContext primaryKeyTableConstraintContext) {

    }

    @Override
    public void enterUniqueKeyTableConstraint(MySqlParser.UniqueKeyTableConstraintContext uniqueKeyTableConstraintContext) {

    }

    @Override
    public void exitUniqueKeyTableConstraint(MySqlParser.UniqueKeyTableConstraintContext uniqueKeyTableConstraintContext) {

    }

    @Override
    public void enterForeignKeyTableConstraint(MySqlParser.ForeignKeyTableConstraintContext foreignKeyTableConstraintContext) {

    }

    @Override
    public void exitForeignKeyTableConstraint(MySqlParser.ForeignKeyTableConstraintContext foreignKeyTableConstraintContext) {

    }

    @Override
    public void enterCheckTableConstraint(MySqlParser.CheckTableConstraintContext checkTableConstraintContext) {

    }

    @Override
    public void exitCheckTableConstraint(MySqlParser.CheckTableConstraintContext checkTableConstraintContext) {

    }

    @Override
    public void enterClusteringKeyTableConstraint(MySqlParser.ClusteringKeyTableConstraintContext clusteringKeyTableConstraintContext) {

    }

    @Override
    public void exitClusteringKeyTableConstraint(MySqlParser.ClusteringKeyTableConstraintContext clusteringKeyTableConstraintContext) {

    }

//    @Override
//    public void enterClusteringKeyTableConstraint(MySqlParser.ClusteringKeyTableConstraintContext clusteringKeyTableConstraintContext) {
//
//    }
//
//    @Override
//    public void exitClusteringKeyTableConstraint(MySqlParser.ClusteringKeyTableConstraintContext clusteringKeyTableConstraintContext) {
//
//    }

    @Override
    public void enterReferenceDefinition(MySqlParser.ReferenceDefinitionContext referenceDefinitionContext) {

    }

    @Override
    public void exitReferenceDefinition(MySqlParser.ReferenceDefinitionContext referenceDefinitionContext) {

    }

    @Override
    public void enterReferenceAction(MySqlParser.ReferenceActionContext referenceActionContext) {

    }

    @Override
    public void exitReferenceAction(MySqlParser.ReferenceActionContext referenceActionContext) {

    }

    @Override
    public void enterReferenceControlType(MySqlParser.ReferenceControlTypeContext referenceControlTypeContext) {

    }

    @Override
    public void exitReferenceControlType(MySqlParser.ReferenceControlTypeContext referenceControlTypeContext) {

    }

    @Override
    public void enterSimpleIndexDeclaration(MySqlParser.SimpleIndexDeclarationContext simpleIndexDeclarationContext) {

    }

    @Override
    public void exitSimpleIndexDeclaration(MySqlParser.SimpleIndexDeclarationContext simpleIndexDeclarationContext) {

    }

    @Override
    public void enterSpecialIndexDeclaration(MySqlParser.SpecialIndexDeclarationContext specialIndexDeclarationContext) {

    }

    @Override
    public void exitSpecialIndexDeclaration(MySqlParser.SpecialIndexDeclarationContext specialIndexDeclarationContext) {

    }

    @Override
    public void enterTableOptionEngine(MySqlParser.TableOptionEngineContext tableOptionEngineContext) {

    }

    @Override
    public void exitTableOptionEngine(MySqlParser.TableOptionEngineContext tableOptionEngineContext) {

    }

    @Override
    public void enterTableOptionEngineAttribute(MySqlParser.TableOptionEngineAttributeContext tableOptionEngineAttributeContext) {

    }

    @Override
    public void exitTableOptionEngineAttribute(MySqlParser.TableOptionEngineAttributeContext tableOptionEngineAttributeContext) {

    }

    @Override
    public void enterTableOptionAutoextendSize(MySqlParser.TableOptionAutoextendSizeContext tableOptionAutoextendSizeContext) {

    }

    @Override
    public void exitTableOptionAutoextendSize(MySqlParser.TableOptionAutoextendSizeContext tableOptionAutoextendSizeContext) {

    }

    @Override
    public void enterTableOptionAutoIncrement(MySqlParser.TableOptionAutoIncrementContext tableOptionAutoIncrementContext) {

    }

    @Override
    public void exitTableOptionAutoIncrement(MySqlParser.TableOptionAutoIncrementContext tableOptionAutoIncrementContext) {

    }

    @Override
    public void enterTableOptionAverage(MySqlParser.TableOptionAverageContext tableOptionAverageContext) {

    }

    @Override
    public void exitTableOptionAverage(MySqlParser.TableOptionAverageContext tableOptionAverageContext) {

    }

    @Override
    public void enterTableOptionCharset(MySqlParser.TableOptionCharsetContext tableOptionCharsetContext) {

    }

    @Override
    public void exitTableOptionCharset(MySqlParser.TableOptionCharsetContext tableOptionCharsetContext) {

    }

    @Override
    public void enterTableOptionChecksum(MySqlParser.TableOptionChecksumContext tableOptionChecksumContext) {

    }

    @Override
    public void exitTableOptionChecksum(MySqlParser.TableOptionChecksumContext tableOptionChecksumContext) {

    }

    @Override
    public void enterTableOptionCollate(MySqlParser.TableOptionCollateContext tableOptionCollateContext) {

    }

    @Override
    public void exitTableOptionCollate(MySqlParser.TableOptionCollateContext tableOptionCollateContext) {

    }

    @Override
    public void enterTableOptionComment(MySqlParser.TableOptionCommentContext tableOptionCommentContext) {

    }

    @Override
    public void exitTableOptionComment(MySqlParser.TableOptionCommentContext tableOptionCommentContext) {

    }

    @Override
    public void enterTableOptionCompression(MySqlParser.TableOptionCompressionContext tableOptionCompressionContext) {

    }

    @Override
    public void exitTableOptionCompression(MySqlParser.TableOptionCompressionContext tableOptionCompressionContext) {

    }

    @Override
    public void enterTableOptionConnection(MySqlParser.TableOptionConnectionContext tableOptionConnectionContext) {

    }

    @Override
    public void exitTableOptionConnection(MySqlParser.TableOptionConnectionContext tableOptionConnectionContext) {

    }

    @Override
    public void enterTableOptionDataDirectory(MySqlParser.TableOptionDataDirectoryContext tableOptionDataDirectoryContext) {

    }

    @Override
    public void exitTableOptionDataDirectory(MySqlParser.TableOptionDataDirectoryContext tableOptionDataDirectoryContext) {

    }

    @Override
    public void enterTableOptionDelay(MySqlParser.TableOptionDelayContext tableOptionDelayContext) {

    }

    @Override
    public void exitTableOptionDelay(MySqlParser.TableOptionDelayContext tableOptionDelayContext) {

    }

    @Override
    public void enterTableOptionEncryption(MySqlParser.TableOptionEncryptionContext tableOptionEncryptionContext) {

    }

    @Override
    public void exitTableOptionEncryption(MySqlParser.TableOptionEncryptionContext tableOptionEncryptionContext) {

    }

    @Override
    public void enterTableOptionEncrypted(MySqlParser.TableOptionEncryptedContext tableOptionEncryptedContext) {

    }

    @Override
    public void exitTableOptionEncrypted(MySqlParser.TableOptionEncryptedContext tableOptionEncryptedContext) {

    }

    @Override
    public void enterTableOptionPageCompressed(MySqlParser.TableOptionPageCompressedContext tableOptionPageCompressedContext) {

    }

    @Override
    public void exitTableOptionPageCompressed(MySqlParser.TableOptionPageCompressedContext tableOptionPageCompressedContext) {

    }

    @Override
    public void enterTableOptionPageCompressionLevel(MySqlParser.TableOptionPageCompressionLevelContext tableOptionPageCompressionLevelContext) {

    }

    @Override
    public void exitTableOptionPageCompressionLevel(MySqlParser.TableOptionPageCompressionLevelContext tableOptionPageCompressionLevelContext) {

    }

    @Override
    public void enterTableOptionEncryptionKeyId(MySqlParser.TableOptionEncryptionKeyIdContext tableOptionEncryptionKeyIdContext) {

    }

    @Override
    public void exitTableOptionEncryptionKeyId(MySqlParser.TableOptionEncryptionKeyIdContext tableOptionEncryptionKeyIdContext) {

    }

    @Override
    public void enterTableOptionIndexDirectory(MySqlParser.TableOptionIndexDirectoryContext tableOptionIndexDirectoryContext) {

    }

    @Override
    public void exitTableOptionIndexDirectory(MySqlParser.TableOptionIndexDirectoryContext tableOptionIndexDirectoryContext) {

    }

    @Override
    public void enterTableOptionInsertMethod(MySqlParser.TableOptionInsertMethodContext tableOptionInsertMethodContext) {

    }

    @Override
    public void exitTableOptionInsertMethod(MySqlParser.TableOptionInsertMethodContext tableOptionInsertMethodContext) {

    }

    @Override
    public void enterTableOptionKeyBlockSize(MySqlParser.TableOptionKeyBlockSizeContext tableOptionKeyBlockSizeContext) {

    }

    @Override
    public void exitTableOptionKeyBlockSize(MySqlParser.TableOptionKeyBlockSizeContext tableOptionKeyBlockSizeContext) {

    }

    @Override
    public void enterTableOptionMaxRows(MySqlParser.TableOptionMaxRowsContext tableOptionMaxRowsContext) {

    }

    @Override
    public void exitTableOptionMaxRows(MySqlParser.TableOptionMaxRowsContext tableOptionMaxRowsContext) {

    }

    @Override
    public void enterTableOptionMinRows(MySqlParser.TableOptionMinRowsContext tableOptionMinRowsContext) {

    }

    @Override
    public void exitTableOptionMinRows(MySqlParser.TableOptionMinRowsContext tableOptionMinRowsContext) {

    }

    @Override
    public void enterTableOptionPackKeys(MySqlParser.TableOptionPackKeysContext tableOptionPackKeysContext) {

    }

    @Override
    public void exitTableOptionPackKeys(MySqlParser.TableOptionPackKeysContext tableOptionPackKeysContext) {

    }

    @Override
    public void enterTableOptionPassword(MySqlParser.TableOptionPasswordContext tableOptionPasswordContext) {

    }

    @Override
    public void exitTableOptionPassword(MySqlParser.TableOptionPasswordContext tableOptionPasswordContext) {

    }

    @Override
    public void enterTableOptionRowFormat(MySqlParser.TableOptionRowFormatContext tableOptionRowFormatContext) {

    }

    @Override
    public void exitTableOptionRowFormat(MySqlParser.TableOptionRowFormatContext tableOptionRowFormatContext) {

    }

    @Override
    public void enterTableOptionStartTransaction(MySqlParser.TableOptionStartTransactionContext tableOptionStartTransactionContext) {

    }

    @Override
    public void exitTableOptionStartTransaction(MySqlParser.TableOptionStartTransactionContext tableOptionStartTransactionContext) {

    }

    @Override
    public void enterTableOptionSecondaryEngineAttribute(MySqlParser.TableOptionSecondaryEngineAttributeContext tableOptionSecondaryEngineAttributeContext) {

    }

    @Override
    public void exitTableOptionSecondaryEngineAttribute(MySqlParser.TableOptionSecondaryEngineAttributeContext tableOptionSecondaryEngineAttributeContext) {

    }

    @Override
    public void enterTableOptionRecalculation(MySqlParser.TableOptionRecalculationContext tableOptionRecalculationContext) {

    }

    @Override
    public void exitTableOptionRecalculation(MySqlParser.TableOptionRecalculationContext tableOptionRecalculationContext) {

    }

    @Override
    public void enterTableOptionPersistent(MySqlParser.TableOptionPersistentContext tableOptionPersistentContext) {

    }

    @Override
    public void exitTableOptionPersistent(MySqlParser.TableOptionPersistentContext tableOptionPersistentContext) {

    }

    @Override
    public void enterTableOptionSamplePage(MySqlParser.TableOptionSamplePageContext tableOptionSamplePageContext) {

    }

    @Override
    public void exitTableOptionSamplePage(MySqlParser.TableOptionSamplePageContext tableOptionSamplePageContext) {

    }

    @Override
    public void enterTableOptionTablespace(MySqlParser.TableOptionTablespaceContext tableOptionTablespaceContext) {

    }

    @Override
    public void exitTableOptionTablespace(MySqlParser.TableOptionTablespaceContext tableOptionTablespaceContext) {

    }

    @Override
    public void enterTableOptionTableType(MySqlParser.TableOptionTableTypeContext tableOptionTableTypeContext) {

    }

    @Override
    public void exitTableOptionTableType(MySqlParser.TableOptionTableTypeContext tableOptionTableTypeContext) {

    }

    @Override
    public void enterTableOptionTransactional(MySqlParser.TableOptionTransactionalContext tableOptionTransactionalContext) {

    }

    @Override
    public void exitTableOptionTransactional(MySqlParser.TableOptionTransactionalContext tableOptionTransactionalContext) {

    }

    @Override
    public void enterTableOptionUnion(MySqlParser.TableOptionUnionContext tableOptionUnionContext) {

    }

    @Override
    public void exitTableOptionUnion(MySqlParser.TableOptionUnionContext tableOptionUnionContext) {

    }

    @Override
    public void enterTableOptionWithSystemVersioning(MySqlParser.TableOptionWithSystemVersioningContext tableOptionWithSystemVersioningContext) {

    }

    @Override
    public void exitTableOptionWithSystemVersioning(MySqlParser.TableOptionWithSystemVersioningContext tableOptionWithSystemVersioningContext) {

    }

    @Override
    public void enterTableType(MySqlParser.TableTypeContext tableTypeContext) {

    }

    @Override
    public void exitTableType(MySqlParser.TableTypeContext tableTypeContext) {

    }

    @Override
    public void enterTablespaceStorage(MySqlParser.TablespaceStorageContext tablespaceStorageContext) {

    }

    @Override
    public void exitTablespaceStorage(MySqlParser.TablespaceStorageContext tablespaceStorageContext) {

    }

    @Override
    public void enterPartitionDefinitions(MySqlParser.PartitionDefinitionsContext partitionDefinitionsContext) {

    }

    @Override
    public void exitPartitionDefinitions(MySqlParser.PartitionDefinitionsContext partitionDefinitionsContext) {

    }

    @Override
    public void enterPartitionFunctionHash(MySqlParser.PartitionFunctionHashContext partitionFunctionHashContext) {

    }

    @Override
    public void exitPartitionFunctionHash(MySqlParser.PartitionFunctionHashContext partitionFunctionHashContext) {

    }

    @Override
    public void enterPartitionFunctionKey(MySqlParser.PartitionFunctionKeyContext partitionFunctionKeyContext) {

    }

    @Override
    public void exitPartitionFunctionKey(MySqlParser.PartitionFunctionKeyContext partitionFunctionKeyContext) {

    }

    @Override
    public void enterPartitionFunctionRange(MySqlParser.PartitionFunctionRangeContext partitionFunctionRangeContext) {

    }

    @Override
    public void exitPartitionFunctionRange(MySqlParser.PartitionFunctionRangeContext partitionFunctionRangeContext) {

    }

    @Override
    public void enterPartitionFunctionList(MySqlParser.PartitionFunctionListContext partitionFunctionListContext) {

    }

    @Override
    public void exitPartitionFunctionList(MySqlParser.PartitionFunctionListContext partitionFunctionListContext) {

    }

    @Override
    public void enterSubPartitionFunctionHash(MySqlParser.SubPartitionFunctionHashContext subPartitionFunctionHashContext) {

    }

    @Override
    public void exitSubPartitionFunctionHash(MySqlParser.SubPartitionFunctionHashContext subPartitionFunctionHashContext) {

    }

    @Override
    public void enterSubPartitionFunctionKey(MySqlParser.SubPartitionFunctionKeyContext subPartitionFunctionKeyContext) {

    }

    @Override
    public void exitSubPartitionFunctionKey(MySqlParser.SubPartitionFunctionKeyContext subPartitionFunctionKeyContext) {

    }

    @Override
    public void enterPartitionComparison(MySqlParser.PartitionComparisonContext partitionComparisonContext) {

    }

    @Override
    public void exitPartitionComparison(MySqlParser.PartitionComparisonContext partitionComparisonContext) {

    }

    @Override
    public void enterPartitionListAtom(MySqlParser.PartitionListAtomContext partitionListAtomContext) {

    }

    @Override
    public void exitPartitionListAtom(MySqlParser.PartitionListAtomContext partitionListAtomContext) {

    }

    @Override
    public void enterPartitionListVector(MySqlParser.PartitionListVectorContext partitionListVectorContext) {

    }

    @Override
    public void exitPartitionListVector(MySqlParser.PartitionListVectorContext partitionListVectorContext) {

    }

    @Override
    public void enterPartitionSimple(MySqlParser.PartitionSimpleContext partitionSimpleContext) {

    }

    @Override
    public void exitPartitionSimple(MySqlParser.PartitionSimpleContext partitionSimpleContext) {

    }

    @Override
    public void enterPartitionDefinerAtom(MySqlParser.PartitionDefinerAtomContext partitionDefinerAtomContext) {

    }

    @Override
    public void exitPartitionDefinerAtom(MySqlParser.PartitionDefinerAtomContext partitionDefinerAtomContext) {

    }

    @Override
    public void enterPartitionDefinerVector(MySqlParser.PartitionDefinerVectorContext partitionDefinerVectorContext) {

    }

    @Override
    public void exitPartitionDefinerVector(MySqlParser.PartitionDefinerVectorContext partitionDefinerVectorContext) {

    }

    @Override
    public void enterSubpartitionDefinition(MySqlParser.SubpartitionDefinitionContext subpartitionDefinitionContext) {

    }

    @Override
    public void exitSubpartitionDefinition(MySqlParser.SubpartitionDefinitionContext subpartitionDefinitionContext) {

    }

    @Override
    public void enterPartitionOptionEngine(MySqlParser.PartitionOptionEngineContext partitionOptionEngineContext) {

    }

    @Override
    public void exitPartitionOptionEngine(MySqlParser.PartitionOptionEngineContext partitionOptionEngineContext) {

    }

    @Override
    public void enterPartitionOptionComment(MySqlParser.PartitionOptionCommentContext partitionOptionCommentContext) {

    }

    @Override
    public void exitPartitionOptionComment(MySqlParser.PartitionOptionCommentContext partitionOptionCommentContext) {

    }

    @Override
    public void enterPartitionOptionDataDirectory(MySqlParser.PartitionOptionDataDirectoryContext partitionOptionDataDirectoryContext) {

    }

    @Override
    public void exitPartitionOptionDataDirectory(MySqlParser.PartitionOptionDataDirectoryContext partitionOptionDataDirectoryContext) {

    }

    @Override
    public void enterPartitionOptionIndexDirectory(MySqlParser.PartitionOptionIndexDirectoryContext partitionOptionIndexDirectoryContext) {

    }

    @Override
    public void exitPartitionOptionIndexDirectory(MySqlParser.PartitionOptionIndexDirectoryContext partitionOptionIndexDirectoryContext) {

    }

    @Override
    public void enterPartitionOptionMaxRows(MySqlParser.PartitionOptionMaxRowsContext partitionOptionMaxRowsContext) {

    }

    @Override
    public void exitPartitionOptionMaxRows(MySqlParser.PartitionOptionMaxRowsContext partitionOptionMaxRowsContext) {

    }

    @Override
    public void enterPartitionOptionMinRows(MySqlParser.PartitionOptionMinRowsContext partitionOptionMinRowsContext) {

    }

    @Override
    public void exitPartitionOptionMinRows(MySqlParser.PartitionOptionMinRowsContext partitionOptionMinRowsContext) {

    }

    @Override
    public void enterPartitionOptionTablespace(MySqlParser.PartitionOptionTablespaceContext partitionOptionTablespaceContext) {

    }

    @Override
    public void exitPartitionOptionTablespace(MySqlParser.PartitionOptionTablespaceContext partitionOptionTablespaceContext) {

    }

    @Override
    public void enterPartitionOptionNodeGroup(MySqlParser.PartitionOptionNodeGroupContext partitionOptionNodeGroupContext) {

    }

    @Override
    public void exitPartitionOptionNodeGroup(MySqlParser.PartitionOptionNodeGroupContext partitionOptionNodeGroupContext) {

    }

    @Override
    public void enterAlterSimpleDatabase(MySqlParser.AlterSimpleDatabaseContext alterSimpleDatabaseContext) {

    }

    @Override
    public void exitAlterSimpleDatabase(MySqlParser.AlterSimpleDatabaseContext alterSimpleDatabaseContext) {

    }

    @Override
    public void enterAlterUpgradeName(MySqlParser.AlterUpgradeNameContext alterUpgradeNameContext) {

    }

    @Override
    public void exitAlterUpgradeName(MySqlParser.AlterUpgradeNameContext alterUpgradeNameContext) {

    }

    @Override
    public void enterAlterEvent(MySqlParser.AlterEventContext alterEventContext) {

    }

    @Override
    public void exitAlterEvent(MySqlParser.AlterEventContext alterEventContext) {

    }

    @Override
    public void enterAlterFunction(MySqlParser.AlterFunctionContext alterFunctionContext) {

    }

    @Override
    public void exitAlterFunction(MySqlParser.AlterFunctionContext alterFunctionContext) {

    }

    @Override
    public void enterAlterInstance(MySqlParser.AlterInstanceContext alterInstanceContext) {

    }

    @Override
    public void exitAlterInstance(MySqlParser.AlterInstanceContext alterInstanceContext) {

    }

    @Override
    public void enterAlterLogfileGroup(MySqlParser.AlterLogfileGroupContext alterLogfileGroupContext) {

    }

    @Override
    public void exitAlterLogfileGroup(MySqlParser.AlterLogfileGroupContext alterLogfileGroupContext) {

    }

    @Override
    public void enterAlterProcedure(MySqlParser.AlterProcedureContext alterProcedureContext) {

    }

    @Override
    public void exitAlterProcedure(MySqlParser.AlterProcedureContext alterProcedureContext) {

    }

    @Override
    public void enterAlterServer(MySqlParser.AlterServerContext alterServerContext) {

    }

    @Override
    public void exitAlterServer(MySqlParser.AlterServerContext alterServerContext) {

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
    public void exitAlterTable(MySqlParser.AlterTableContext alterTableContext) {
    }

    @Override
    public void enterAlterTablespace(MySqlParser.AlterTablespaceContext alterTablespaceContext) {

    }

    @Override
    public void exitAlterTablespace(MySqlParser.AlterTablespaceContext alterTablespaceContext) {

    }

    @Override
    public void enterAlterView(MySqlParser.AlterViewContext alterViewContext) {

    }

    @Override
    public void exitAlterView(MySqlParser.AlterViewContext alterViewContext) {

    }

    @Override
    public void enterAlterSequence(MySqlParser.AlterSequenceContext alterSequenceContext) {

    }

    @Override
    public void exitAlterSequence(MySqlParser.AlterSequenceContext alterSequenceContext) {

    }

    @Override
    public void enterAlterByTableOption(MySqlParser.AlterByTableOptionContext alterByTableOptionContext) {

    }

    @Override
    public void exitAlterByTableOption(MySqlParser.AlterByTableOptionContext alterByTableOptionContext) {

    }

    @Override
    public void enterAlterByAddColumn(AlterByAddColumnContext alterByAddColumnContext) {

    }

    @Override
    public void exitAlterByAddColumn(AlterByAddColumnContext alterByAddColumnContext) {

    }

    @Override
    public void enterAlterByAddColumns(MySqlParser.AlterByAddColumnsContext alterByAddColumnsContext) {

    }

    @Override
    public void exitAlterByAddColumns(MySqlParser.AlterByAddColumnsContext alterByAddColumnsContext) {

    }

    @Override
    public void enterAlterByAddIndex(MySqlParser.AlterByAddIndexContext alterByAddIndexContext) {

    }

    @Override
    public void exitAlterByAddIndex(MySqlParser.AlterByAddIndexContext alterByAddIndexContext) {

    }

    @Override
    public void enterAlterByAddPrimaryKey(MySqlParser.AlterByAddPrimaryKeyContext alterByAddPrimaryKeyContext) {

    }

    @Override
    public void exitAlterByAddPrimaryKey(MySqlParser.AlterByAddPrimaryKeyContext alterByAddPrimaryKeyContext) {

    }

    @Override
    public void enterAlterByAddUniqueKey(MySqlParser.AlterByAddUniqueKeyContext alterByAddUniqueKeyContext) {

    }

    @Override
    public void exitAlterByAddUniqueKey(MySqlParser.AlterByAddUniqueKeyContext alterByAddUniqueKeyContext) {

    }

    @Override
    public void enterAlterByAddSpecialIndex(MySqlParser.AlterByAddSpecialIndexContext alterByAddSpecialIndexContext) {

    }

    @Override
    public void exitAlterByAddSpecialIndex(MySqlParser.AlterByAddSpecialIndexContext alterByAddSpecialIndexContext) {

    }

    @Override
    public void enterAlterByAddForeignKey(MySqlParser.AlterByAddForeignKeyContext alterByAddForeignKeyContext) {

    }

    @Override
    public void exitAlterByAddForeignKey(MySqlParser.AlterByAddForeignKeyContext alterByAddForeignKeyContext) {

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
    public void exitAlterByAddCheckTableConstraint(MySqlParser.AlterByAddCheckTableConstraintContext alterByAddCheckTableConstraintContext) {
    }

    @Override
    public void enterAlterByAlterCheckTableConstraint(MySqlParser.AlterByAlterCheckTableConstraintContext alterByAlterCheckTableConstraintContext) {

    }

    @Override
    public void exitAlterByAlterCheckTableConstraint(MySqlParser.AlterByAlterCheckTableConstraintContext alterByAlterCheckTableConstraintContext) {

    }

//    @Override
//    public void enterAlterByAlterCheckTableConstraint(MySqlParser.AlterByAlterCheckTableConstraintContext alterByAlterCheckTableConstraintContext) {
//
//    }
//
//    @Override
//    public void exitAlterByAlterCheckTableConstraint(MySqlParser.AlterByAlterCheckTableConstraintContext alterByAlterCheckTableConstraintContext) {
//
//    }

    @Override
    public void enterAlterBySetAlgorithm(MySqlParser.AlterBySetAlgorithmContext alterBySetAlgorithmContext) {

    }

    @Override
    public void exitAlterBySetAlgorithm(MySqlParser.AlterBySetAlgorithmContext alterBySetAlgorithmContext) {

    }

    @Override
    public void enterAlterByChangeDefault(MySqlParser.AlterByChangeDefaultContext alterByChangeDefaultContext) {

    }

    @Override
    public void exitAlterByChangeDefault(MySqlParser.AlterByChangeDefaultContext alterByChangeDefaultContext) {

    }

    @Override
    public void enterAlterByChangeColumn(MySqlParser.AlterByChangeColumnContext alterByChangeColumnContext) {

    }

    @Override
    public void exitAlterByChangeColumn(MySqlParser.AlterByChangeColumnContext alterByChangeColumnContext) {
    }

    @Override
    public void enterAlterByRenameColumn(MySqlParser.AlterByRenameColumnContext alterByRenameColumnContext) {

    }

    @Override
    public void exitAlterByRenameColumn(MySqlParser.AlterByRenameColumnContext alterByRenameColumnContext) {

    }

    @Override
    public void enterAlterByLock(MySqlParser.AlterByLockContext alterByLockContext) {

    }

    @Override
    public void exitAlterByLock(MySqlParser.AlterByLockContext alterByLockContext) {

    }

    @Override
    public void enterAlterByModifyColumn(MySqlParser.AlterByModifyColumnContext alterByModifyColumnContext) {

    }

    @Override
    public void exitAlterByModifyColumn(MySqlParser.AlterByModifyColumnContext alterByModifyColumnContext) {

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
    public void exitAlterByDropColumn(MySqlParser.AlterByDropColumnContext alterByDropColumnContext) {

    }

    @Override
    public void enterAlterByDropConstraintCheck(MySqlParser.AlterByDropConstraintCheckContext alterByDropConstraintCheckContext) {

    }

    @Override
    public void exitAlterByDropConstraintCheck(MySqlParser.AlterByDropConstraintCheckContext alterByDropConstraintCheckContext) {

    }

    @Override
    public void enterAlterByDropPrimaryKey(MySqlParser.AlterByDropPrimaryKeyContext alterByDropPrimaryKeyContext) {

    }

    @Override
    public void exitAlterByDropPrimaryKey(MySqlParser.AlterByDropPrimaryKeyContext alterByDropPrimaryKeyContext) {

    }

    @Override
    public void enterAlterByDropIndex(MySqlParser.AlterByDropIndexContext alterByDropIndexContext) {

    }

    @Override
    public void exitAlterByDropIndex(MySqlParser.AlterByDropIndexContext alterByDropIndexContext) {

    }

    @Override
    public void enterAlterByRenameIndex(MySqlParser.AlterByRenameIndexContext alterByRenameIndexContext) {

    }

    @Override
    public void exitAlterByRenameIndex(MySqlParser.AlterByRenameIndexContext alterByRenameIndexContext) {

    }

    @Override
    public void enterAlterByAlterColumnDefault(MySqlParser.AlterByAlterColumnDefaultContext alterByAlterColumnDefaultContext) {

    }

    @Override
    public void exitAlterByAlterColumnDefault(MySqlParser.AlterByAlterColumnDefaultContext alterByAlterColumnDefaultContext) {

    }

//    @Override
//    public void enterAlterByAlterColumnDefault(MySqlParser.AlterByAlterColumnDefaultContext alterByAlterColumnDefaultContext) {
//
//    }
//
//    @Override
//    public void exitAlterByAlterColumnDefault(MySqlParser.AlterByAlterColumnDefaultContext alterByAlterColumnDefaultContext) {
//
//    }

    @Override
    public void enterAlterByAlterIndexVisibility(MySqlParser.AlterByAlterIndexVisibilityContext alterByAlterIndexVisibilityContext) {

    }

    @Override
    public void exitAlterByAlterIndexVisibility(MySqlParser.AlterByAlterIndexVisibilityContext alterByAlterIndexVisibilityContext) {

    }

    @Override
    public void enterAlterByDropForeignKey(MySqlParser.AlterByDropForeignKeyContext alterByDropForeignKeyContext) {

    }

    @Override
    public void exitAlterByDropForeignKey(MySqlParser.AlterByDropForeignKeyContext alterByDropForeignKeyContext) {

    }

    @Override
    public void enterAlterByDisableKeys(MySqlParser.AlterByDisableKeysContext alterByDisableKeysContext) {

    }

    @Override
    public void exitAlterByDisableKeys(MySqlParser.AlterByDisableKeysContext alterByDisableKeysContext) {

    }

    @Override
    public void enterAlterByEnableKeys(MySqlParser.AlterByEnableKeysContext alterByEnableKeysContext) {

    }

    @Override
    public void exitAlterByEnableKeys(MySqlParser.AlterByEnableKeysContext alterByEnableKeysContext) {

    }

    @Override
    public void enterAlterByRename(MySqlParser.AlterByRenameContext alterByRenameContext) {

    }

    @Override
    public void exitAlterByRename(MySqlParser.AlterByRenameContext alterByRenameContext) {

    }

    @Override
    public void enterAlterByOrder(MySqlParser.AlterByOrderContext alterByOrderContext) {

    }

    @Override
    public void exitAlterByOrder(MySqlParser.AlterByOrderContext alterByOrderContext) {

    }

    @Override
    public void enterAlterByConvertCharset(MySqlParser.AlterByConvertCharsetContext alterByConvertCharsetContext) {

    }

    @Override
    public void exitAlterByConvertCharset(MySqlParser.AlterByConvertCharsetContext alterByConvertCharsetContext) {

    }

    @Override
    public void enterAlterByDefaultCharset(MySqlParser.AlterByDefaultCharsetContext alterByDefaultCharsetContext) {

    }

    @Override
    public void exitAlterByDefaultCharset(MySqlParser.AlterByDefaultCharsetContext alterByDefaultCharsetContext) {

    }

    @Override
    public void enterAlterByDiscardTablespace(MySqlParser.AlterByDiscardTablespaceContext alterByDiscardTablespaceContext) {

    }

    @Override
    public void exitAlterByDiscardTablespace(MySqlParser.AlterByDiscardTablespaceContext alterByDiscardTablespaceContext) {

    }

    @Override
    public void enterAlterByImportTablespace(MySqlParser.AlterByImportTablespaceContext alterByImportTablespaceContext) {

    }

    @Override
    public void exitAlterByImportTablespace(MySqlParser.AlterByImportTablespaceContext alterByImportTablespaceContext) {

    }

    @Override
    public void enterAlterByForce(MySqlParser.AlterByForceContext alterByForceContext) {

    }

    @Override
    public void exitAlterByForce(MySqlParser.AlterByForceContext alterByForceContext) {

    }

    @Override
    public void enterAlterByValidate(MySqlParser.AlterByValidateContext alterByValidateContext) {

    }

    @Override
    public void exitAlterByValidate(MySqlParser.AlterByValidateContext alterByValidateContext) {

    }

    @Override
    public void enterAlterByAddPartition(MySqlParser.AlterByAddPartitionContext alterByAddPartitionContext) {

    }

    @Override
    public void exitAlterByAddPartition(MySqlParser.AlterByAddPartitionContext alterByAddPartitionContext) {

    }

    @Override
    public void enterAlterByDropPartition(MySqlParser.AlterByDropPartitionContext alterByDropPartitionContext) {

    }

    @Override
    public void exitAlterByDropPartition(MySqlParser.AlterByDropPartitionContext alterByDropPartitionContext) {

    }

    @Override
    public void enterAlterByDiscardPartition(MySqlParser.AlterByDiscardPartitionContext alterByDiscardPartitionContext) {

    }

    @Override
    public void exitAlterByDiscardPartition(MySqlParser.AlterByDiscardPartitionContext alterByDiscardPartitionContext) {

    }

    @Override
    public void enterAlterByImportPartition(MySqlParser.AlterByImportPartitionContext alterByImportPartitionContext) {

    }

    @Override
    public void exitAlterByImportPartition(MySqlParser.AlterByImportPartitionContext alterByImportPartitionContext) {

    }

    @Override
    public void enterAlterByTruncatePartition(MySqlParser.AlterByTruncatePartitionContext alterByTruncatePartitionContext) {

    }

    @Override
    public void exitAlterByTruncatePartition(MySqlParser.AlterByTruncatePartitionContext alterByTruncatePartitionContext) {

    }

    @Override
    public void enterAlterByCoalescePartition(MySqlParser.AlterByCoalescePartitionContext alterByCoalescePartitionContext) {

    }

    @Override
    public void exitAlterByCoalescePartition(MySqlParser.AlterByCoalescePartitionContext alterByCoalescePartitionContext) {

    }

    @Override
    public void enterAlterByReorganizePartition(MySqlParser.AlterByReorganizePartitionContext alterByReorganizePartitionContext) {

    }

    @Override
    public void exitAlterByReorganizePartition(MySqlParser.AlterByReorganizePartitionContext alterByReorganizePartitionContext) {

    }

    @Override
    public void enterAlterByExchangePartition(MySqlParser.AlterByExchangePartitionContext alterByExchangePartitionContext) {

    }

    @Override
    public void exitAlterByExchangePartition(MySqlParser.AlterByExchangePartitionContext alterByExchangePartitionContext) {

    }

    @Override
    public void enterAlterByAnalyzePartition(MySqlParser.AlterByAnalyzePartitionContext alterByAnalyzePartitionContext) {

    }

    @Override
    public void exitAlterByAnalyzePartition(MySqlParser.AlterByAnalyzePartitionContext alterByAnalyzePartitionContext) {

    }

    @Override
    public void enterAlterByCheckPartition(MySqlParser.AlterByCheckPartitionContext alterByCheckPartitionContext) {

    }

    @Override
    public void exitAlterByCheckPartition(MySqlParser.AlterByCheckPartitionContext alterByCheckPartitionContext) {

    }

    @Override
    public void enterAlterByOptimizePartition(MySqlParser.AlterByOptimizePartitionContext alterByOptimizePartitionContext) {

    }

    @Override
    public void exitAlterByOptimizePartition(MySqlParser.AlterByOptimizePartitionContext alterByOptimizePartitionContext) {

    }

    @Override
    public void enterAlterByRebuildPartition(MySqlParser.AlterByRebuildPartitionContext alterByRebuildPartitionContext) {

    }

    @Override
    public void exitAlterByRebuildPartition(MySqlParser.AlterByRebuildPartitionContext alterByRebuildPartitionContext) {

    }

    @Override
    public void enterAlterByRepairPartition(MySqlParser.AlterByRepairPartitionContext alterByRepairPartitionContext) {

    }

    @Override
    public void exitAlterByRepairPartition(MySqlParser.AlterByRepairPartitionContext alterByRepairPartitionContext) {

    }

    @Override
    public void enterAlterByRemovePartitioning(MySqlParser.AlterByRemovePartitioningContext alterByRemovePartitioningContext) {

    }

    @Override
    public void exitAlterByRemovePartitioning(MySqlParser.AlterByRemovePartitioningContext alterByRemovePartitioningContext) {

    }

    @Override
    public void enterAlterByUpgradePartitioning(MySqlParser.AlterByUpgradePartitioningContext alterByUpgradePartitioningContext) {

    }

    @Override
    public void exitAlterByUpgradePartitioning(MySqlParser.AlterByUpgradePartitioningContext alterByUpgradePartitioningContext) {

    }

    @Override
    public void enterAlterByAddDefinitions(MySqlParser.AlterByAddDefinitionsContext alterByAddDefinitionsContext) {

    }

    @Override
    public void exitAlterByAddDefinitions(MySqlParser.AlterByAddDefinitionsContext alterByAddDefinitionsContext) {

    }

    @Override
    public void enterAlterPartition(MySqlParser.AlterPartitionContext alterPartitionContext) {

    }

    @Override
    public void exitAlterPartition(MySqlParser.AlterPartitionContext alterPartitionContext) {

    }

    @Override
    public void enterDropDatabase(MySqlParser.DropDatabaseContext dropDatabaseContext) {
        log.debug("ENTER DROP DATABASE");
    }

    @Override
    public void exitDropDatabase(MySqlParser.DropDatabaseContext dropDatabaseContext) {

    }

    @Override
    public void enterDropEvent(MySqlParser.DropEventContext dropEventContext) {

    }

    @Override
    public void exitDropEvent(MySqlParser.DropEventContext dropEventContext) {

    }

    @Override
    public void enterDropIndex(MySqlParser.DropIndexContext dropIndexContext) {

    }

    @Override
    public void exitDropIndex(MySqlParser.DropIndexContext dropIndexContext) {

    }

    @Override
    public void enterDropLogfileGroup(MySqlParser.DropLogfileGroupContext dropLogfileGroupContext) {

    }

    @Override
    public void exitDropLogfileGroup(MySqlParser.DropLogfileGroupContext dropLogfileGroupContext) {

    }

    @Override
    public void enterDropProcedure(MySqlParser.DropProcedureContext dropProcedureContext) {

    }

    @Override
    public void exitDropProcedure(MySqlParser.DropProcedureContext dropProcedureContext) {

    }

    @Override
    public void enterDropFunction(MySqlParser.DropFunctionContext dropFunctionContext) {

    }

    @Override
    public void exitDropFunction(MySqlParser.DropFunctionContext dropFunctionContext) {

    }

    @Override
    public void enterDropServer(MySqlParser.DropServerContext dropServerContext) {

    }

    @Override
    public void exitDropServer(MySqlParser.DropServerContext dropServerContext) {

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
    public void exitDropTable(MySqlParser.DropTableContext dropTableContext) {
        log.debug("DROP TABLE exit");
    }

    @Override
    public void enterDropTablespace(MySqlParser.DropTablespaceContext dropTablespaceContext) {

    }

    @Override
    public void exitDropTablespace(MySqlParser.DropTablespaceContext dropTablespaceContext) {

    }

    @Override
    public void enterDropTrigger(MySqlParser.DropTriggerContext dropTriggerContext) {

    }

    @Override
    public void exitDropTrigger(MySqlParser.DropTriggerContext dropTriggerContext) {

    }

    @Override
    public void enterDropView(MySqlParser.DropViewContext dropViewContext) {

    }

    @Override

    public void exitDropView(MySqlParser.DropViewContext dropViewContext) {

    }

    @Override
    public void enterDropRole(MySqlParser.DropRoleContext dropRoleContext) {

    }

    @Override
    public void exitDropRole(MySqlParser.DropRoleContext dropRoleContext) {

    }

    @Override
    public void enterSetRole(MySqlParser.SetRoleContext setRoleContext) {

    }

    @Override
    public void exitSetRole(MySqlParser.SetRoleContext setRoleContext) {

    }

    @Override
    public void enterDropSequence(MySqlParser.DropSequenceContext dropSequenceContext) {

    }

    @Override
    public void exitDropSequence(MySqlParser.DropSequenceContext dropSequenceContext) {

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
    public void exitRenameTable(MySqlParser.RenameTableContext renameTableContext) {
    }

    @Override
    public void enterRenameTableClause(MySqlParser.RenameTableClauseContext renameTableClauseContext) {

    }

    @Override
    public void exitRenameTableClause(MySqlParser.RenameTableClauseContext renameTableClauseContext) {

    }

    @Override
    public void enterTruncateTable(MySqlParser.TruncateTableContext truncateTableContext) {
        for (ParseTree child : truncateTableContext.children) {
            if (child instanceof MySqlParser.TableNameContext) {
                this.query.append(String.format(Constants.TRUNCATE_TABLE, child.getText()));
            }
        }
    }

    @Override
    public void exitTruncateTable(MySqlParser.TruncateTableContext truncateTableContext) {
    }

    @Override
    public void enterCallStatement(MySqlParser.CallStatementContext callStatementContext) {

    }

    @Override
    public void exitCallStatement(MySqlParser.CallStatementContext callStatementContext) {

    }

    @Override
    public void enterDeleteStatement(MySqlParser.DeleteStatementContext deleteStatementContext) {

    }

    @Override
    public void exitDeleteStatement(MySqlParser.DeleteStatementContext deleteStatementContext) {

    }

    @Override
    public void enterDoStatement(MySqlParser.DoStatementContext doStatementContext) {

    }

    @Override
    public void exitDoStatement(MySqlParser.DoStatementContext doStatementContext) {

    }

    @Override
    public void enterHandlerStatement(MySqlParser.HandlerStatementContext handlerStatementContext) {

    }

    @Override
    public void exitHandlerStatement(MySqlParser.HandlerStatementContext handlerStatementContext) {

    }

    @Override
    public void enterInsertStatement(MySqlParser.InsertStatementContext insertStatementContext) {

    }

    @Override
    public void exitInsertStatement(MySqlParser.InsertStatementContext insertStatementContext) {

    }

    @Override
    public void enterLoadDataStatement(MySqlParser.LoadDataStatementContext loadDataStatementContext) {

    }

    @Override
    public void exitLoadDataStatement(MySqlParser.LoadDataStatementContext loadDataStatementContext) {

    }

    @Override
    public void enterLoadXmlStatement(MySqlParser.LoadXmlStatementContext loadXmlStatementContext) {

    }

    @Override
    public void exitLoadXmlStatement(MySqlParser.LoadXmlStatementContext loadXmlStatementContext) {

    }

    @Override
    public void enterReplaceStatement(MySqlParser.ReplaceStatementContext replaceStatementContext) {

    }

    @Override
    public void exitReplaceStatement(MySqlParser.ReplaceStatementContext replaceStatementContext) {

    }

    @Override
    public void enterSimpleSelect(MySqlParser.SimpleSelectContext simpleSelectContext) {

    }

    @Override
    public void exitSimpleSelect(MySqlParser.SimpleSelectContext simpleSelectContext) {

    }

    @Override
    public void enterParenthesisSelect(MySqlParser.ParenthesisSelectContext parenthesisSelectContext) {

    }

    @Override
    public void exitParenthesisSelect(MySqlParser.ParenthesisSelectContext parenthesisSelectContext) {

    }

    @Override
    public void enterUnionSelect(MySqlParser.UnionSelectContext unionSelectContext) {

    }

    @Override
    public void exitUnionSelect(MySqlParser.UnionSelectContext unionSelectContext) {

    }

    @Override
    public void enterUnionParenthesisSelect(MySqlParser.UnionParenthesisSelectContext unionParenthesisSelectContext) {

    }

    @Override
    public void exitUnionParenthesisSelect(MySqlParser.UnionParenthesisSelectContext unionParenthesisSelectContext) {

    }

    @Override
    public void enterWithLateralStatement(MySqlParser.WithLateralStatementContext withLateralStatementContext) {

    }

    @Override
    public void exitWithLateralStatement(MySqlParser.WithLateralStatementContext withLateralStatementContext) {

    }

    @Override
    public void enterValuesStatement(MySqlParser.ValuesStatementContext valuesStatementContext) {

    }

    @Override
    public void exitValuesStatement(MySqlParser.ValuesStatementContext valuesStatementContext) {

    }

    @Override
    public void enterWithStatement(MySqlParser.WithStatementContext withStatementContext) {

    }

    @Override
    public void exitWithStatement(MySqlParser.WithStatementContext withStatementContext) {

    }

    @Override
    public void enterTableStatement(MySqlParser.TableStatementContext tableStatementContext) {

    }

    @Override
    public void exitTableStatement(MySqlParser.TableStatementContext tableStatementContext) {

    }

    @Override
    public void enterUpdateStatement(MySqlParser.UpdateStatementContext updateStatementContext) {

    }

    @Override
    public void exitUpdateStatement(MySqlParser.UpdateStatementContext updateStatementContext) {

    }

    @Override
    public void enterInsertStatementValue(MySqlParser.InsertStatementValueContext insertStatementValueContext) {

    }

    @Override
    public void exitInsertStatementValue(MySqlParser.InsertStatementValueContext insertStatementValueContext) {

    }

    @Override
    public void enterUpdatedElement(MySqlParser.UpdatedElementContext updatedElementContext) {

    }

    @Override
    public void exitUpdatedElement(MySqlParser.UpdatedElementContext updatedElementContext) {

    }

    @Override
    public void enterAssignmentField(MySqlParser.AssignmentFieldContext assignmentFieldContext) {

    }

    @Override
    public void exitAssignmentField(MySqlParser.AssignmentFieldContext assignmentFieldContext) {

    }

    @Override
    public void enterLockClause(MySqlParser.LockClauseContext lockClauseContext) {

    }

    @Override
    public void exitLockClause(MySqlParser.LockClauseContext lockClauseContext) {

    }

    @Override
    public void enterSingleDeleteStatement(MySqlParser.SingleDeleteStatementContext singleDeleteStatementContext) {

    }

    @Override
    public void exitSingleDeleteStatement(MySqlParser.SingleDeleteStatementContext singleDeleteStatementContext) {

    }

    @Override
    public void enterMultipleDeleteStatement(MySqlParser.MultipleDeleteStatementContext multipleDeleteStatementContext) {

    }

    @Override
    public void exitMultipleDeleteStatement(MySqlParser.MultipleDeleteStatementContext multipleDeleteStatementContext) {

    }

    @Override
    public void enterHandlerOpenStatement(MySqlParser.HandlerOpenStatementContext handlerOpenStatementContext) {

    }

    @Override
    public void exitHandlerOpenStatement(MySqlParser.HandlerOpenStatementContext handlerOpenStatementContext) {

    }

    @Override
    public void enterHandlerReadIndexStatement(MySqlParser.HandlerReadIndexStatementContext handlerReadIndexStatementContext) {

    }

    @Override
    public void exitHandlerReadIndexStatement(MySqlParser.HandlerReadIndexStatementContext handlerReadIndexStatementContext) {

    }

    @Override
    public void enterHandlerReadStatement(MySqlParser.HandlerReadStatementContext handlerReadStatementContext) {

    }

    @Override
    public void exitHandlerReadStatement(MySqlParser.HandlerReadStatementContext handlerReadStatementContext) {

    }

    @Override
    public void enterHandlerCloseStatement(MySqlParser.HandlerCloseStatementContext handlerCloseStatementContext) {

    }

    @Override
    public void exitHandlerCloseStatement(MySqlParser.HandlerCloseStatementContext handlerCloseStatementContext) {

    }

    @Override
    public void enterSingleUpdateStatement(MySqlParser.SingleUpdateStatementContext singleUpdateStatementContext) {

    }

    @Override
    public void exitSingleUpdateStatement(MySqlParser.SingleUpdateStatementContext singleUpdateStatementContext) {

    }

    @Override
    public void enterMultipleUpdateStatement(MySqlParser.MultipleUpdateStatementContext multipleUpdateStatementContext) {

    }

    @Override
    public void exitMultipleUpdateStatement(MySqlParser.MultipleUpdateStatementContext multipleUpdateStatementContext) {

    }

    @Override
    public void enterOrderByClause(MySqlParser.OrderByClauseContext orderByClauseContext) {

    }

    @Override
    public void exitOrderByClause(MySqlParser.OrderByClauseContext orderByClauseContext) {

    }

    @Override
    public void enterOrderByExpression(MySqlParser.OrderByExpressionContext orderByExpressionContext) {

    }

    @Override
    public void exitOrderByExpression(MySqlParser.OrderByExpressionContext orderByExpressionContext) {

    }

    @Override
    public void enterTableSources(MySqlParser.TableSourcesContext tableSourcesContext) {

    }

    @Override
    public void exitTableSources(MySqlParser.TableSourcesContext tableSourcesContext) {

    }

    @Override
    public void enterTableSourceBase(MySqlParser.TableSourceBaseContext tableSourceBaseContext) {

    }

    @Override
    public void exitTableSourceBase(MySqlParser.TableSourceBaseContext tableSourceBaseContext) {

    }

    @Override
    public void enterTableSourceNested(MySqlParser.TableSourceNestedContext tableSourceNestedContext) {

    }

    @Override
    public void exitTableSourceNested(MySqlParser.TableSourceNestedContext tableSourceNestedContext) {

    }

    @Override
    public void enterTableJson(MySqlParser.TableJsonContext tableJsonContext) {

    }

    @Override
    public void exitTableJson(MySqlParser.TableJsonContext tableJsonContext) {

    }

    @Override
    public void enterAtomTableItem(MySqlParser.AtomTableItemContext atomTableItemContext) {

    }

    @Override
    public void exitAtomTableItem(MySqlParser.AtomTableItemContext atomTableItemContext) {

    }

    @Override
    public void enterSubqueryTableItem(MySqlParser.SubqueryTableItemContext subqueryTableItemContext) {

    }

    @Override
    public void exitSubqueryTableItem(MySqlParser.SubqueryTableItemContext subqueryTableItemContext) {

    }

    @Override
    public void enterTableSourcesItem(MySqlParser.TableSourcesItemContext tableSourcesItemContext) {

    }

    @Override
    public void exitTableSourcesItem(MySqlParser.TableSourcesItemContext tableSourcesItemContext) {

    }

    @Override
    public void enterIndexHint(MySqlParser.IndexHintContext indexHintContext) {

    }

    @Override
    public void exitIndexHint(MySqlParser.IndexHintContext indexHintContext) {

    }

    @Override
    public void enterIndexHintType(MySqlParser.IndexHintTypeContext indexHintTypeContext) {

    }

    @Override
    public void exitIndexHintType(MySqlParser.IndexHintTypeContext indexHintTypeContext) {

    }

    @Override
    public void enterInnerJoin(MySqlParser.InnerJoinContext innerJoinContext) {

    }

    @Override
    public void exitInnerJoin(MySqlParser.InnerJoinContext innerJoinContext) {

    }

    @Override
    public void enterStraightJoin(MySqlParser.StraightJoinContext straightJoinContext) {

    }

    @Override
    public void exitStraightJoin(MySqlParser.StraightJoinContext straightJoinContext) {

    }

    @Override
    public void enterOuterJoin(MySqlParser.OuterJoinContext outerJoinContext) {

    }

    @Override
    public void exitOuterJoin(MySqlParser.OuterJoinContext outerJoinContext) {

    }

    @Override
    public void enterNaturalJoin(MySqlParser.NaturalJoinContext naturalJoinContext) {

    }

    @Override
    public void exitNaturalJoin(MySqlParser.NaturalJoinContext naturalJoinContext) {

    }

    @Override
    public void enterJoinSpec(MySqlParser.JoinSpecContext joinSpecContext) {

    }

    @Override
    public void exitJoinSpec(MySqlParser.JoinSpecContext joinSpecContext) {

    }

    @Override
    public void enterQueryExpression(MySqlParser.QueryExpressionContext queryExpressionContext) {

    }

    @Override
    public void exitQueryExpression(MySqlParser.QueryExpressionContext queryExpressionContext) {

    }

    @Override
    public void enterQueryExpressionNointo(MySqlParser.QueryExpressionNointoContext queryExpressionNointoContext) {

    }

    @Override
    public void exitQueryExpressionNointo(MySqlParser.QueryExpressionNointoContext queryExpressionNointoContext) {

    }

    @Override
    public void enterQuerySpecification(MySqlParser.QuerySpecificationContext querySpecificationContext) {

    }

    @Override
    public void exitQuerySpecification(MySqlParser.QuerySpecificationContext querySpecificationContext) {

    }

    @Override
    public void enterQuerySpecificationNointo(MySqlParser.QuerySpecificationNointoContext querySpecificationNointoContext) {

    }

    @Override
    public void exitQuerySpecificationNointo(MySqlParser.QuerySpecificationNointoContext querySpecificationNointoContext) {

    }

    @Override
    public void enterUnionParenthesis(MySqlParser.UnionParenthesisContext unionParenthesisContext) {

    }

    @Override
    public void exitUnionParenthesis(MySqlParser.UnionParenthesisContext unionParenthesisContext) {

    }

    @Override
    public void enterUnionStatement(MySqlParser.UnionStatementContext unionStatementContext) {

    }

    @Override
    public void exitUnionStatement(MySqlParser.UnionStatementContext unionStatementContext) {

    }

    @Override
    public void enterLateralStatement(MySqlParser.LateralStatementContext lateralStatementContext) {

    }

    @Override
    public void exitLateralStatement(MySqlParser.LateralStatementContext lateralStatementContext) {

    }

    @Override
    public void enterJsonTable(MySqlParser.JsonTableContext jsonTableContext) {

    }

    @Override
    public void exitJsonTable(MySqlParser.JsonTableContext jsonTableContext) {

    }

    @Override
    public void enterJsonColumnList(MySqlParser.JsonColumnListContext jsonColumnListContext) {

    }

    @Override
    public void exitJsonColumnList(MySqlParser.JsonColumnListContext jsonColumnListContext) {

    }

    @Override
    public void enterJsonColumn(MySqlParser.JsonColumnContext jsonColumnContext) {

    }

    @Override
    public void exitJsonColumn(MySqlParser.JsonColumnContext jsonColumnContext) {

    }

    @Override
    public void enterJsonOnEmpty(MySqlParser.JsonOnEmptyContext jsonOnEmptyContext) {

    }

    @Override
    public void exitJsonOnEmpty(MySqlParser.JsonOnEmptyContext jsonOnEmptyContext) {

    }

    @Override
    public void enterJsonOnError(MySqlParser.JsonOnErrorContext jsonOnErrorContext) {

    }

    @Override
    public void exitJsonOnError(MySqlParser.JsonOnErrorContext jsonOnErrorContext) {

    }

    @Override
    public void enterSelectSpec(MySqlParser.SelectSpecContext selectSpecContext) {

    }

    @Override
    public void exitSelectSpec(MySqlParser.SelectSpecContext selectSpecContext) {

    }

    @Override
    public void enterSelectElements(MySqlParser.SelectElementsContext selectElementsContext) {

    }

    @Override
    public void exitSelectElements(MySqlParser.SelectElementsContext selectElementsContext) {

    }

    @Override
    public void enterSelectStarElement(MySqlParser.SelectStarElementContext selectStarElementContext) {

    }

    @Override
    public void exitSelectStarElement(MySqlParser.SelectStarElementContext selectStarElementContext) {

    }

    @Override
    public void enterSelectColumnElement(MySqlParser.SelectColumnElementContext selectColumnElementContext) {

    }

    @Override
    public void exitSelectColumnElement(MySqlParser.SelectColumnElementContext selectColumnElementContext) {

    }

    @Override
    public void enterSelectFunctionElement(MySqlParser.SelectFunctionElementContext selectFunctionElementContext) {

    }

    @Override
    public void exitSelectFunctionElement(MySqlParser.SelectFunctionElementContext selectFunctionElementContext) {

    }

    @Override
    public void enterSelectExpressionElement(MySqlParser.SelectExpressionElementContext selectExpressionElementContext) {

    }

    @Override
    public void exitSelectExpressionElement(MySqlParser.SelectExpressionElementContext selectExpressionElementContext) {

    }

    @Override
    public void enterSelectIntoVariables(MySqlParser.SelectIntoVariablesContext selectIntoVariablesContext) {

    }

    @Override
    public void exitSelectIntoVariables(MySqlParser.SelectIntoVariablesContext selectIntoVariablesContext) {

    }

    @Override
    public void enterSelectIntoDumpFile(MySqlParser.SelectIntoDumpFileContext selectIntoDumpFileContext) {

    }

    @Override
    public void exitSelectIntoDumpFile(MySqlParser.SelectIntoDumpFileContext selectIntoDumpFileContext) {

    }

    @Override
    public void enterSelectIntoTextFile(MySqlParser.SelectIntoTextFileContext selectIntoTextFileContext) {

    }

    @Override
    public void exitSelectIntoTextFile(MySqlParser.SelectIntoTextFileContext selectIntoTextFileContext) {

    }

    @Override
    public void enterSelectFieldsInto(MySqlParser.SelectFieldsIntoContext selectFieldsIntoContext) {

    }

    @Override
    public void exitSelectFieldsInto(MySqlParser.SelectFieldsIntoContext selectFieldsIntoContext) {

    }

    @Override
    public void enterSelectLinesInto(MySqlParser.SelectLinesIntoContext selectLinesIntoContext) {

    }

    @Override
    public void exitSelectLinesInto(MySqlParser.SelectLinesIntoContext selectLinesIntoContext) {

    }

    @Override
    public void enterFromClause(MySqlParser.FromClauseContext fromClauseContext) {

    }

    @Override
    public void exitFromClause(MySqlParser.FromClauseContext fromClauseContext) {

    }

    @Override
    public void enterGroupByClause(MySqlParser.GroupByClauseContext groupByClauseContext) {

    }

    @Override
    public void exitGroupByClause(MySqlParser.GroupByClauseContext groupByClauseContext) {

    }

    @Override
    public void enterHavingClause(MySqlParser.HavingClauseContext havingClauseContext) {

    }

    @Override
    public void exitHavingClause(MySqlParser.HavingClauseContext havingClauseContext) {

    }

    @Override
    public void enterWindowClause(MySqlParser.WindowClauseContext windowClauseContext) {

    }

    @Override
    public void exitWindowClause(MySqlParser.WindowClauseContext windowClauseContext) {

    }

    @Override
    public void enterGroupByItem(MySqlParser.GroupByItemContext groupByItemContext) {

    }

    @Override
    public void exitGroupByItem(MySqlParser.GroupByItemContext groupByItemContext) {

    }

    @Override
    public void enterLimitClause(MySqlParser.LimitClauseContext limitClauseContext) {

    }

    @Override
    public void exitLimitClause(MySqlParser.LimitClauseContext limitClauseContext) {

    }

    @Override
    public void enterLimitClauseAtom(MySqlParser.LimitClauseAtomContext limitClauseAtomContext) {

    }

    @Override
    public void exitLimitClauseAtom(MySqlParser.LimitClauseAtomContext limitClauseAtomContext) {

    }

    @Override
    public void enterStartTransaction(MySqlParser.StartTransactionContext startTransactionContext) {

    }

    @Override
    public void exitStartTransaction(MySqlParser.StartTransactionContext startTransactionContext) {

    }

    @Override
    public void enterBeginWork(MySqlParser.BeginWorkContext beginWorkContext) {

    }

    @Override
    public void exitBeginWork(MySqlParser.BeginWorkContext beginWorkContext) {

    }

    @Override
    public void enterCommitWork(MySqlParser.CommitWorkContext commitWorkContext) {

    }

    @Override
    public void exitCommitWork(MySqlParser.CommitWorkContext commitWorkContext) {

    }

    @Override
    public void enterRollbackWork(MySqlParser.RollbackWorkContext rollbackWorkContext) {

    }

    @Override
    public void exitRollbackWork(MySqlParser.RollbackWorkContext rollbackWorkContext) {

    }

    @Override
    public void enterSavepointStatement(MySqlParser.SavepointStatementContext savepointStatementContext) {

    }

    @Override
    public void exitSavepointStatement(MySqlParser.SavepointStatementContext savepointStatementContext) {

    }

    @Override
    public void enterRollbackStatement(MySqlParser.RollbackStatementContext rollbackStatementContext) {

    }

    @Override
    public void exitRollbackStatement(MySqlParser.RollbackStatementContext rollbackStatementContext) {

    }

    @Override
    public void enterReleaseStatement(MySqlParser.ReleaseStatementContext releaseStatementContext) {

    }

    @Override
    public void exitReleaseStatement(MySqlParser.ReleaseStatementContext releaseStatementContext) {

    }

    @Override
    public void enterLockTables(MySqlParser.LockTablesContext lockTablesContext) {

    }

    @Override
    public void exitLockTables(MySqlParser.LockTablesContext lockTablesContext) {

    }

    @Override
    public void enterUnlockTables(MySqlParser.UnlockTablesContext unlockTablesContext) {

    }

    @Override
    public void exitUnlockTables(MySqlParser.UnlockTablesContext unlockTablesContext) {

    }

    @Override
    public void enterSetAutocommitStatement(MySqlParser.SetAutocommitStatementContext setAutocommitStatementContext) {

    }

    @Override
    public void exitSetAutocommitStatement(MySqlParser.SetAutocommitStatementContext setAutocommitStatementContext) {

    }

    @Override
    public void enterSetTransactionStatement(MySqlParser.SetTransactionStatementContext setTransactionStatementContext) {

    }

    @Override
    public void exitSetTransactionStatement(MySqlParser.SetTransactionStatementContext setTransactionStatementContext) {

    }

    @Override
    public void enterTransactionMode(MySqlParser.TransactionModeContext transactionModeContext) {

    }

    @Override
    public void exitTransactionMode(MySqlParser.TransactionModeContext transactionModeContext) {

    }

    @Override
    public void enterLockTableElement(MySqlParser.LockTableElementContext lockTableElementContext) {

    }

    @Override
    public void exitLockTableElement(MySqlParser.LockTableElementContext lockTableElementContext) {

    }

    @Override
    public void enterLockAction(MySqlParser.LockActionContext lockActionContext) {

    }

    @Override
    public void exitLockAction(MySqlParser.LockActionContext lockActionContext) {

    }

    @Override
    public void enterTransactionOption(MySqlParser.TransactionOptionContext transactionOptionContext) {

    }

    @Override
    public void exitTransactionOption(MySqlParser.TransactionOptionContext transactionOptionContext) {

    }

    @Override
    public void enterTransactionLevel(MySqlParser.TransactionLevelContext transactionLevelContext) {

    }

    @Override
    public void exitTransactionLevel(MySqlParser.TransactionLevelContext transactionLevelContext) {

    }

    @Override
    public void enterChangeMaster(MySqlParser.ChangeMasterContext changeMasterContext) {

    }

    @Override
    public void exitChangeMaster(MySqlParser.ChangeMasterContext changeMasterContext) {

    }

    @Override
    public void enterChangeReplicationFilter(MySqlParser.ChangeReplicationFilterContext changeReplicationFilterContext) {

    }

    @Override
    public void exitChangeReplicationFilter(MySqlParser.ChangeReplicationFilterContext changeReplicationFilterContext) {

    }

    @Override
    public void enterPurgeBinaryLogs(MySqlParser.PurgeBinaryLogsContext purgeBinaryLogsContext) {

    }

    @Override
    public void exitPurgeBinaryLogs(MySqlParser.PurgeBinaryLogsContext purgeBinaryLogsContext) {

    }

    @Override
    public void enterResetMaster(MySqlParser.ResetMasterContext resetMasterContext) {

    }

    @Override
    public void exitResetMaster(MySqlParser.ResetMasterContext resetMasterContext) {

    }

    @Override
    public void enterResetSlave(MySqlParser.ResetSlaveContext resetSlaveContext) {

    }

    @Override
    public void exitResetSlave(MySqlParser.ResetSlaveContext resetSlaveContext) {

    }

    @Override
    public void enterStartSlave(MySqlParser.StartSlaveContext startSlaveContext) {

    }

    @Override
    public void exitStartSlave(MySqlParser.StartSlaveContext startSlaveContext) {

    }

    @Override
    public void enterStopSlave(MySqlParser.StopSlaveContext stopSlaveContext) {

    }

    @Override
    public void exitStopSlave(MySqlParser.StopSlaveContext stopSlaveContext) {

    }

    @Override
    public void enterStartGroupReplication(MySqlParser.StartGroupReplicationContext startGroupReplicationContext) {

    }

    @Override
    public void exitStartGroupReplication(MySqlParser.StartGroupReplicationContext startGroupReplicationContext) {

    }

    @Override
    public void enterStopGroupReplication(MySqlParser.StopGroupReplicationContext stopGroupReplicationContext) {

    }

    @Override
    public void exitStopGroupReplication(MySqlParser.StopGroupReplicationContext stopGroupReplicationContext) {

    }

    @Override
    public void enterMasterStringOption(MySqlParser.MasterStringOptionContext masterStringOptionContext) {

    }

    @Override
    public void exitMasterStringOption(MySqlParser.MasterStringOptionContext masterStringOptionContext) {

    }

    @Override
    public void enterMasterDecimalOption(MySqlParser.MasterDecimalOptionContext masterDecimalOptionContext) {

    }

    @Override
    public void exitMasterDecimalOption(MySqlParser.MasterDecimalOptionContext masterDecimalOptionContext) {

    }

    @Override
    public void enterMasterBoolOption(MySqlParser.MasterBoolOptionContext masterBoolOptionContext) {

    }

    @Override
    public void exitMasterBoolOption(MySqlParser.MasterBoolOptionContext masterBoolOptionContext) {

    }

    @Override
    public void enterMasterRealOption(MySqlParser.MasterRealOptionContext masterRealOptionContext) {

    }

    @Override
    public void exitMasterRealOption(MySqlParser.MasterRealOptionContext masterRealOptionContext) {

    }

    @Override
    public void enterMasterUidListOption(MySqlParser.MasterUidListOptionContext masterUidListOptionContext) {

    }

    @Override
    public void exitMasterUidListOption(MySqlParser.MasterUidListOptionContext masterUidListOptionContext) {

    }

    @Override
    public void enterStringMasterOption(MySqlParser.StringMasterOptionContext stringMasterOptionContext) {

    }

    @Override
    public void exitStringMasterOption(MySqlParser.StringMasterOptionContext stringMasterOptionContext) {

    }

    @Override
    public void enterDecimalMasterOption(MySqlParser.DecimalMasterOptionContext decimalMasterOptionContext) {

    }

    @Override
    public void exitDecimalMasterOption(MySqlParser.DecimalMasterOptionContext decimalMasterOptionContext) {

    }

    @Override
    public void enterBoolMasterOption(MySqlParser.BoolMasterOptionContext boolMasterOptionContext) {

    }

    @Override
    public void exitBoolMasterOption(MySqlParser.BoolMasterOptionContext boolMasterOptionContext) {

    }

    @Override
    public void enterChannelOption(MySqlParser.ChannelOptionContext channelOptionContext) {

    }

    @Override
    public void exitChannelOption(MySqlParser.ChannelOptionContext channelOptionContext) {

    }

    @Override
    public void enterDoDbReplication(MySqlParser.DoDbReplicationContext doDbReplicationContext) {

    }

    @Override
    public void exitDoDbReplication(MySqlParser.DoDbReplicationContext doDbReplicationContext) {

    }

    @Override
    public void enterIgnoreDbReplication(MySqlParser.IgnoreDbReplicationContext ignoreDbReplicationContext) {

    }

    @Override
    public void exitIgnoreDbReplication(MySqlParser.IgnoreDbReplicationContext ignoreDbReplicationContext) {

    }

    @Override
    public void enterDoTableReplication(MySqlParser.DoTableReplicationContext doTableReplicationContext) {

    }

    @Override
    public void exitDoTableReplication(MySqlParser.DoTableReplicationContext doTableReplicationContext) {

    }

    @Override
    public void enterIgnoreTableReplication(MySqlParser.IgnoreTableReplicationContext ignoreTableReplicationContext) {

    }

    @Override
    public void exitIgnoreTableReplication(MySqlParser.IgnoreTableReplicationContext ignoreTableReplicationContext) {

    }

    @Override
    public void enterWildDoTableReplication(MySqlParser.WildDoTableReplicationContext wildDoTableReplicationContext) {

    }

    @Override
    public void exitWildDoTableReplication(MySqlParser.WildDoTableReplicationContext wildDoTableReplicationContext) {

    }

    @Override
    public void enterWildIgnoreTableReplication(MySqlParser.WildIgnoreTableReplicationContext wildIgnoreTableReplicationContext) {

    }

    @Override
    public void exitWildIgnoreTableReplication(MySqlParser.WildIgnoreTableReplicationContext wildIgnoreTableReplicationContext) {

    }

    @Override
    public void enterRewriteDbReplication(MySqlParser.RewriteDbReplicationContext rewriteDbReplicationContext) {

    }

    @Override
    public void exitRewriteDbReplication(MySqlParser.RewriteDbReplicationContext rewriteDbReplicationContext) {

    }

    @Override
    public void enterTablePair(MySqlParser.TablePairContext tablePairContext) {

    }

    @Override
    public void exitTablePair(MySqlParser.TablePairContext tablePairContext) {

    }

    @Override
    public void enterThreadType(MySqlParser.ThreadTypeContext threadTypeContext) {

    }

    @Override
    public void exitThreadType(MySqlParser.ThreadTypeContext threadTypeContext) {

    }

    @Override
    public void enterGtidsUntilOption(MySqlParser.GtidsUntilOptionContext gtidsUntilOptionContext) {

    }

    @Override
    public void exitGtidsUntilOption(MySqlParser.GtidsUntilOptionContext gtidsUntilOptionContext) {

    }

    @Override
    public void enterMasterLogUntilOption(MySqlParser.MasterLogUntilOptionContext masterLogUntilOptionContext) {

    }

    @Override
    public void exitMasterLogUntilOption(MySqlParser.MasterLogUntilOptionContext masterLogUntilOptionContext) {

    }

    @Override
    public void enterRelayLogUntilOption(MySqlParser.RelayLogUntilOptionContext relayLogUntilOptionContext) {

    }

    @Override
    public void exitRelayLogUntilOption(MySqlParser.RelayLogUntilOptionContext relayLogUntilOptionContext) {

    }

    @Override
    public void enterSqlGapsUntilOption(MySqlParser.SqlGapsUntilOptionContext sqlGapsUntilOptionContext) {

    }

    @Override
    public void exitSqlGapsUntilOption(MySqlParser.SqlGapsUntilOptionContext sqlGapsUntilOptionContext) {

    }

    @Override
    public void enterUserConnectionOption(MySqlParser.UserConnectionOptionContext userConnectionOptionContext) {

    }

    @Override
    public void exitUserConnectionOption(MySqlParser.UserConnectionOptionContext userConnectionOptionContext) {

    }

    @Override
    public void enterPasswordConnectionOption(MySqlParser.PasswordConnectionOptionContext passwordConnectionOptionContext) {

    }

    @Override
    public void exitPasswordConnectionOption(MySqlParser.PasswordConnectionOptionContext passwordConnectionOptionContext) {

    }

    @Override
    public void enterDefaultAuthConnectionOption(MySqlParser.DefaultAuthConnectionOptionContext defaultAuthConnectionOptionContext) {

    }

    @Override
    public void exitDefaultAuthConnectionOption(MySqlParser.DefaultAuthConnectionOptionContext defaultAuthConnectionOptionContext) {

    }

    @Override
    public void enterPluginDirConnectionOption(MySqlParser.PluginDirConnectionOptionContext pluginDirConnectionOptionContext) {

    }

    @Override
    public void exitPluginDirConnectionOption(MySqlParser.PluginDirConnectionOptionContext pluginDirConnectionOptionContext) {

    }

    @Override
    public void enterGtuidSet(MySqlParser.GtuidSetContext gtuidSetContext) {

    }

    @Override
    public void exitGtuidSet(MySqlParser.GtuidSetContext gtuidSetContext) {

    }

    @Override
    public void enterXaStartTransaction(MySqlParser.XaStartTransactionContext xaStartTransactionContext) {

    }

    @Override
    public void exitXaStartTransaction(MySqlParser.XaStartTransactionContext xaStartTransactionContext) {

    }

    @Override
    public void enterXaEndTransaction(MySqlParser.XaEndTransactionContext xaEndTransactionContext) {

    }

    @Override
    public void exitXaEndTransaction(MySqlParser.XaEndTransactionContext xaEndTransactionContext) {

    }

    @Override
    public void enterXaPrepareStatement(MySqlParser.XaPrepareStatementContext xaPrepareStatementContext) {

    }

    @Override
    public void exitXaPrepareStatement(MySqlParser.XaPrepareStatementContext xaPrepareStatementContext) {

    }

    @Override
    public void enterXaCommitWork(MySqlParser.XaCommitWorkContext xaCommitWorkContext) {

    }

    @Override
    public void exitXaCommitWork(MySqlParser.XaCommitWorkContext xaCommitWorkContext) {

    }

    @Override
    public void enterXaRollbackWork(MySqlParser.XaRollbackWorkContext xaRollbackWorkContext) {

    }

    @Override
    public void exitXaRollbackWork(MySqlParser.XaRollbackWorkContext xaRollbackWorkContext) {

    }

    @Override
    public void enterXaRecoverWork(MySqlParser.XaRecoverWorkContext xaRecoverWorkContext) {

    }

    @Override
    public void exitXaRecoverWork(MySqlParser.XaRecoverWorkContext xaRecoverWorkContext) {

    }

    @Override
    public void enterPrepareStatement(MySqlParser.PrepareStatementContext prepareStatementContext) {

    }

    @Override
    public void exitPrepareStatement(MySqlParser.PrepareStatementContext prepareStatementContext) {

    }

    @Override
    public void enterExecuteStatement(MySqlParser.ExecuteStatementContext executeStatementContext) {

    }

    @Override
    public void exitExecuteStatement(MySqlParser.ExecuteStatementContext executeStatementContext) {

    }

    @Override
    public void enterDeallocatePrepare(MySqlParser.DeallocatePrepareContext deallocatePrepareContext) {

    }

    @Override
    public void exitDeallocatePrepare(MySqlParser.DeallocatePrepareContext deallocatePrepareContext) {

    }

    @Override
    public void enterRoutineBody(MySqlParser.RoutineBodyContext routineBodyContext) {

    }

    @Override
    public void exitRoutineBody(MySqlParser.RoutineBodyContext routineBodyContext) {

    }

    @Override
    public void enterBlockStatement(MySqlParser.BlockStatementContext blockStatementContext) {

    }

    @Override
    public void exitBlockStatement(MySqlParser.BlockStatementContext blockStatementContext) {

    }

    @Override
    public void enterCaseStatement(MySqlParser.CaseStatementContext caseStatementContext) {

    }

    @Override
    public void exitCaseStatement(MySqlParser.CaseStatementContext caseStatementContext) {

    }

    @Override
    public void enterIfStatement(MySqlParser.IfStatementContext ifStatementContext) {

    }

    @Override
    public void exitIfStatement(MySqlParser.IfStatementContext ifStatementContext) {

    }

    @Override
    public void enterIterateStatement(MySqlParser.IterateStatementContext iterateStatementContext) {

    }

    @Override
    public void exitIterateStatement(MySqlParser.IterateStatementContext iterateStatementContext) {

    }

    @Override
    public void enterLeaveStatement(MySqlParser.LeaveStatementContext leaveStatementContext) {

    }

    @Override
    public void exitLeaveStatement(MySqlParser.LeaveStatementContext leaveStatementContext) {

    }

    @Override
    public void enterLoopStatement(MySqlParser.LoopStatementContext loopStatementContext) {

    }

    @Override
    public void exitLoopStatement(MySqlParser.LoopStatementContext loopStatementContext) {

    }

    @Override
    public void enterRepeatStatement(MySqlParser.RepeatStatementContext repeatStatementContext) {

    }

    @Override
    public void exitRepeatStatement(MySqlParser.RepeatStatementContext repeatStatementContext) {

    }

    @Override
    public void enterReturnStatement(MySqlParser.ReturnStatementContext returnStatementContext) {

    }

    @Override
    public void exitReturnStatement(MySqlParser.ReturnStatementContext returnStatementContext) {

    }

    @Override
    public void enterWhileStatement(MySqlParser.WhileStatementContext whileStatementContext) {

    }

    @Override
    public void exitWhileStatement(MySqlParser.WhileStatementContext whileStatementContext) {

    }

    @Override
    public void enterCloseCursor(MySqlParser.CloseCursorContext closeCursorContext) {

    }

    @Override
    public void exitCloseCursor(MySqlParser.CloseCursorContext closeCursorContext) {

    }

    @Override
    public void enterFetchCursor(MySqlParser.FetchCursorContext fetchCursorContext) {

    }

    @Override
    public void exitFetchCursor(MySqlParser.FetchCursorContext fetchCursorContext) {

    }

    @Override
    public void enterOpenCursor(MySqlParser.OpenCursorContext openCursorContext) {

    }

    @Override
    public void exitOpenCursor(MySqlParser.OpenCursorContext openCursorContext) {

    }

    @Override
    public void enterDeclareVariable(MySqlParser.DeclareVariableContext declareVariableContext) {

    }

    @Override
    public void exitDeclareVariable(MySqlParser.DeclareVariableContext declareVariableContext) {

    }

    @Override
    public void enterDeclareCondition(MySqlParser.DeclareConditionContext declareConditionContext) {

    }

    @Override
    public void exitDeclareCondition(MySqlParser.DeclareConditionContext declareConditionContext) {

    }

    @Override
    public void enterDeclareCursor(MySqlParser.DeclareCursorContext declareCursorContext) {

    }

    @Override
    public void exitDeclareCursor(MySqlParser.DeclareCursorContext declareCursorContext) {

    }

    @Override
    public void enterDeclareHandler(MySqlParser.DeclareHandlerContext declareHandlerContext) {

    }

    @Override
    public void exitDeclareHandler(MySqlParser.DeclareHandlerContext declareHandlerContext) {

    }

    @Override
    public void enterHandlerConditionCode(MySqlParser.HandlerConditionCodeContext handlerConditionCodeContext) {

    }

    @Override
    public void exitHandlerConditionCode(MySqlParser.HandlerConditionCodeContext handlerConditionCodeContext) {

    }

    @Override
    public void enterHandlerConditionState(MySqlParser.HandlerConditionStateContext handlerConditionStateContext) {

    }

    @Override
    public void exitHandlerConditionState(MySqlParser.HandlerConditionStateContext handlerConditionStateContext) {

    }

    @Override
    public void enterHandlerConditionName(MySqlParser.HandlerConditionNameContext handlerConditionNameContext) {

    }

    @Override
    public void exitHandlerConditionName(MySqlParser.HandlerConditionNameContext handlerConditionNameContext) {

    }

    @Override
    public void enterHandlerConditionWarning(MySqlParser.HandlerConditionWarningContext handlerConditionWarningContext) {

    }

    @Override
    public void exitHandlerConditionWarning(MySqlParser.HandlerConditionWarningContext handlerConditionWarningContext) {

    }

    @Override
    public void enterHandlerConditionNotfound(MySqlParser.HandlerConditionNotfoundContext handlerConditionNotfoundContext) {

    }

    @Override
    public void exitHandlerConditionNotfound(MySqlParser.HandlerConditionNotfoundContext handlerConditionNotfoundContext) {

    }

    @Override
    public void enterHandlerConditionException(MySqlParser.HandlerConditionExceptionContext handlerConditionExceptionContext) {

    }

    @Override
    public void exitHandlerConditionException(MySqlParser.HandlerConditionExceptionContext handlerConditionExceptionContext) {

    }

    @Override
    public void enterProcedureSqlStatement(MySqlParser.ProcedureSqlStatementContext procedureSqlStatementContext) {

    }

    @Override
    public void exitProcedureSqlStatement(MySqlParser.ProcedureSqlStatementContext procedureSqlStatementContext) {

    }

    @Override
    public void enterCaseAlternative(MySqlParser.CaseAlternativeContext caseAlternativeContext) {

    }

    @Override
    public void exitCaseAlternative(MySqlParser.CaseAlternativeContext caseAlternativeContext) {

    }

    @Override
    public void enterElifAlternative(MySqlParser.ElifAlternativeContext elifAlternativeContext) {

    }

    @Override
    public void exitElifAlternative(MySqlParser.ElifAlternativeContext elifAlternativeContext) {

    }

    @Override
    public void enterAlterUserMysqlV56(MySqlParser.AlterUserMysqlV56Context alterUserMysqlV56Context) {

    }

    @Override
    public void exitAlterUserMysqlV56(MySqlParser.AlterUserMysqlV56Context alterUserMysqlV56Context) {

    }

    @Override
    public void enterAlterUserMysqlV80(MySqlParser.AlterUserMysqlV80Context alterUserMysqlV80Context) {

    }

    @Override
    public void exitAlterUserMysqlV80(MySqlParser.AlterUserMysqlV80Context alterUserMysqlV80Context) {

    }

    @Override
    public void enterCreateUserMysqlV56(MySqlParser.CreateUserMysqlV56Context createUserMysqlV56Context) {

    }

    @Override
    public void exitCreateUserMysqlV56(MySqlParser.CreateUserMysqlV56Context createUserMysqlV56Context) {

    }

    @Override
    public void enterCreateUserMysqlV80(MySqlParser.CreateUserMysqlV80Context createUserMysqlV80Context) {

    }

    @Override
    public void exitCreateUserMysqlV80(MySqlParser.CreateUserMysqlV80Context createUserMysqlV80Context) {

    }

    @Override
    public void enterDropUser(MySqlParser.DropUserContext dropUserContext) {

    }

    @Override
    public void exitDropUser(MySqlParser.DropUserContext dropUserContext) {

    }

    @Override
    public void enterGrantStatement(MySqlParser.GrantStatementContext grantStatementContext) {

    }

    @Override
    public void exitGrantStatement(MySqlParser.GrantStatementContext grantStatementContext) {

    }

    @Override
    public void enterRoleOption(MySqlParser.RoleOptionContext roleOptionContext) {

    }

    @Override
    public void exitRoleOption(MySqlParser.RoleOptionContext roleOptionContext) {

    }

    @Override
    public void enterGrantProxy(MySqlParser.GrantProxyContext grantProxyContext) {

    }

    @Override
    public void exitGrantProxy(MySqlParser.GrantProxyContext grantProxyContext) {

    }

    @Override
    public void enterRenameUser(MySqlParser.RenameUserContext renameUserContext) {

    }

    @Override
    public void exitRenameUser(MySqlParser.RenameUserContext renameUserContext) {

    }

    @Override
    public void enterDetailRevoke(MySqlParser.DetailRevokeContext detailRevokeContext) {

    }

    @Override
    public void exitDetailRevoke(MySqlParser.DetailRevokeContext detailRevokeContext) {

    }

    @Override
    public void enterShortRevoke(MySqlParser.ShortRevokeContext shortRevokeContext) {

    }

    @Override
    public void exitShortRevoke(MySqlParser.ShortRevokeContext shortRevokeContext) {

    }

    @Override
    public void enterRoleRevoke(MySqlParser.RoleRevokeContext roleRevokeContext) {

    }

    @Override
    public void exitRoleRevoke(MySqlParser.RoleRevokeContext roleRevokeContext) {

    }

    @Override
    public void enterRevokeProxy(MySqlParser.RevokeProxyContext revokeProxyContext) {

    }

    @Override
    public void exitRevokeProxy(MySqlParser.RevokeProxyContext revokeProxyContext) {

    }

    @Override
    public void enterSetPasswordStatement(MySqlParser.SetPasswordStatementContext setPasswordStatementContext) {

    }

    @Override
    public void exitSetPasswordStatement(MySqlParser.SetPasswordStatementContext setPasswordStatementContext) {

    }

    @Override
    public void enterUserSpecification(MySqlParser.UserSpecificationContext userSpecificationContext) {

    }

    @Override
    public void exitUserSpecification(MySqlParser.UserSpecificationContext userSpecificationContext) {

    }

    @Override
    public void enterHashAuthOption(MySqlParser.HashAuthOptionContext hashAuthOptionContext) {

    }

    @Override
    public void exitHashAuthOption(MySqlParser.HashAuthOptionContext hashAuthOptionContext) {

    }

    @Override
    public void enterRandomAuthOption(MySqlParser.RandomAuthOptionContext randomAuthOptionContext) {

    }

    @Override
    public void exitRandomAuthOption(MySqlParser.RandomAuthOptionContext randomAuthOptionContext) {

    }

    @Override
    public void enterStringAuthOption(MySqlParser.StringAuthOptionContext stringAuthOptionContext) {

    }

    @Override
    public void exitStringAuthOption(MySqlParser.StringAuthOptionContext stringAuthOptionContext) {

    }

    @Override
    public void enterModuleAuthOption(MySqlParser.ModuleAuthOptionContext moduleAuthOptionContext) {

    }

    @Override
    public void exitModuleAuthOption(MySqlParser.ModuleAuthOptionContext moduleAuthOptionContext) {

    }

    @Override
    public void enterSimpleAuthOption(MySqlParser.SimpleAuthOptionContext simpleAuthOptionContext) {

    }

    @Override
    public void exitSimpleAuthOption(MySqlParser.SimpleAuthOptionContext simpleAuthOptionContext) {

    }

    @Override
    public void enterAuthOptionClause(MySqlParser.AuthOptionClauseContext authOptionClauseContext) {

    }

    @Override
    public void exitAuthOptionClause(MySqlParser.AuthOptionClauseContext authOptionClauseContext) {

    }

    @Override
    public void enterModule(MySqlParser.ModuleContext moduleContext) {

    }

    @Override
    public void exitModule(MySqlParser.ModuleContext moduleContext) {

    }

    @Override
    public void enterPasswordModuleOption(MySqlParser.PasswordModuleOptionContext passwordModuleOptionContext) {

    }

    @Override
    public void exitPasswordModuleOption(MySqlParser.PasswordModuleOptionContext passwordModuleOptionContext) {

    }

    @Override
    public void enterTlsOption(MySqlParser.TlsOptionContext tlsOptionContext) {

    }

    @Override
    public void exitTlsOption(MySqlParser.TlsOptionContext tlsOptionContext) {

    }

    @Override
    public void enterUserResourceOption(MySqlParser.UserResourceOptionContext userResourceOptionContext) {

    }

    @Override
    public void exitUserResourceOption(MySqlParser.UserResourceOptionContext userResourceOptionContext) {

    }

    @Override
    public void enterUserPasswordOption(MySqlParser.UserPasswordOptionContext userPasswordOptionContext) {

    }

    @Override
    public void exitUserPasswordOption(MySqlParser.UserPasswordOptionContext userPasswordOptionContext) {

    }

    @Override
    public void enterUserLockOption(MySqlParser.UserLockOptionContext userLockOptionContext) {

    }

    @Override
    public void exitUserLockOption(MySqlParser.UserLockOptionContext userLockOptionContext) {

    }

    @Override
    public void enterPrivelegeClause(MySqlParser.PrivelegeClauseContext privelegeClauseContext) {

    }

    @Override
    public void exitPrivelegeClause(MySqlParser.PrivelegeClauseContext privelegeClauseContext) {

    }

    @Override
    public void enterPrivilege(MySqlParser.PrivilegeContext privilegeContext) {

    }

    @Override
    public void exitPrivilege(MySqlParser.PrivilegeContext privilegeContext) {

    }

    @Override
    public void enterCurrentSchemaPriviLevel(MySqlParser.CurrentSchemaPriviLevelContext currentSchemaPriviLevelContext) {

    }

    @Override
    public void exitCurrentSchemaPriviLevel(MySqlParser.CurrentSchemaPriviLevelContext currentSchemaPriviLevelContext) {

    }

    @Override
    public void enterGlobalPrivLevel(MySqlParser.GlobalPrivLevelContext globalPrivLevelContext) {

    }

    @Override
    public void exitGlobalPrivLevel(MySqlParser.GlobalPrivLevelContext globalPrivLevelContext) {

    }

    @Override
    public void enterDefiniteSchemaPrivLevel(MySqlParser.DefiniteSchemaPrivLevelContext definiteSchemaPrivLevelContext) {

    }

    @Override
    public void exitDefiniteSchemaPrivLevel(MySqlParser.DefiniteSchemaPrivLevelContext definiteSchemaPrivLevelContext) {

    }

    @Override
    public void enterDefiniteFullTablePrivLevel(MySqlParser.DefiniteFullTablePrivLevelContext definiteFullTablePrivLevelContext) {

    }

    @Override
    public void exitDefiniteFullTablePrivLevel(MySqlParser.DefiniteFullTablePrivLevelContext definiteFullTablePrivLevelContext) {

    }

    @Override
    public void enterDefiniteFullTablePrivLevel2(MySqlParser.DefiniteFullTablePrivLevel2Context definiteFullTablePrivLevel2Context) {

    }

    @Override
    public void exitDefiniteFullTablePrivLevel2(MySqlParser.DefiniteFullTablePrivLevel2Context definiteFullTablePrivLevel2Context) {

    }

    @Override
    public void enterDefiniteTablePrivLevel(MySqlParser.DefiniteTablePrivLevelContext definiteTablePrivLevelContext) {

    }

    @Override
    public void exitDefiniteTablePrivLevel(MySqlParser.DefiniteTablePrivLevelContext definiteTablePrivLevelContext) {

    }

    @Override
    public void enterRenameUserClause(MySqlParser.RenameUserClauseContext renameUserClauseContext) {

    }

    @Override
    public void exitRenameUserClause(MySqlParser.RenameUserClauseContext renameUserClauseContext) {

    }

    @Override
    public void enterAnalyzeTable(MySqlParser.AnalyzeTableContext analyzeTableContext) {

    }

    @Override
    public void exitAnalyzeTable(MySqlParser.AnalyzeTableContext analyzeTableContext) {

    }

    @Override
    public void enterCheckTable(MySqlParser.CheckTableContext checkTableContext) {

    }

    @Override
    public void exitCheckTable(MySqlParser.CheckTableContext checkTableContext) {

    }

    @Override
    public void enterChecksumTable(MySqlParser.ChecksumTableContext checksumTableContext) {

    }

    @Override
    public void exitChecksumTable(MySqlParser.ChecksumTableContext checksumTableContext) {

    }

    @Override
    public void enterOptimizeTable(MySqlParser.OptimizeTableContext optimizeTableContext) {

    }

    @Override
    public void exitOptimizeTable(MySqlParser.OptimizeTableContext optimizeTableContext) {

    }

    @Override
    public void enterRepairTable(MySqlParser.RepairTableContext repairTableContext) {

    }

    @Override
    public void exitRepairTable(MySqlParser.RepairTableContext repairTableContext) {

    }

    @Override
    public void enterCheckTableOption(MySqlParser.CheckTableOptionContext checkTableOptionContext) {

    }

    @Override
    public void exitCheckTableOption(MySqlParser.CheckTableOptionContext checkTableOptionContext) {

    }

    @Override
    public void enterCreateUdfunction(MySqlParser.CreateUdfunctionContext createUdfunctionContext) {

    }

    @Override
    public void exitCreateUdfunction(MySqlParser.CreateUdfunctionContext createUdfunctionContext) {

    }

    @Override
    public void enterInstallPlugin(MySqlParser.InstallPluginContext installPluginContext) {

    }

    @Override
    public void exitInstallPlugin(MySqlParser.InstallPluginContext installPluginContext) {

    }

    @Override
    public void enterUninstallPlugin(MySqlParser.UninstallPluginContext uninstallPluginContext) {

    }

    @Override
    public void exitUninstallPlugin(MySqlParser.UninstallPluginContext uninstallPluginContext) {

    }

    @Override
    public void enterSetVariable(MySqlParser.SetVariableContext setVariableContext) {

    }

    @Override
    public void exitSetVariable(MySqlParser.SetVariableContext setVariableContext) {

    }

    @Override
    public void enterSetCharset(MySqlParser.SetCharsetContext setCharsetContext) {

    }

    @Override
    public void exitSetCharset(MySqlParser.SetCharsetContext setCharsetContext) {

    }

    @Override
    public void enterSetNames(MySqlParser.SetNamesContext setNamesContext) {

    }

    @Override
    public void exitSetNames(MySqlParser.SetNamesContext setNamesContext) {

    }

    @Override
    public void enterSetPassword(MySqlParser.SetPasswordContext setPasswordContext) {

    }

    @Override
    public void exitSetPassword(MySqlParser.SetPasswordContext setPasswordContext) {

    }

    @Override
    public void enterSetTransaction(MySqlParser.SetTransactionContext setTransactionContext) {

    }

    @Override
    public void exitSetTransaction(MySqlParser.SetTransactionContext setTransactionContext) {

    }

    @Override
    public void enterSetAutocommit(MySqlParser.SetAutocommitContext setAutocommitContext) {

    }

    @Override
    public void exitSetAutocommit(MySqlParser.SetAutocommitContext setAutocommitContext) {

    }

    @Override
    public void enterSetNewValueInsideTrigger(MySqlParser.SetNewValueInsideTriggerContext setNewValueInsideTriggerContext) {

    }

    @Override
    public void exitSetNewValueInsideTrigger(MySqlParser.SetNewValueInsideTriggerContext setNewValueInsideTriggerContext) {

    }

    @Override
    public void enterShowMasterLogs(MySqlParser.ShowMasterLogsContext showMasterLogsContext) {

    }

    @Override
    public void exitShowMasterLogs(MySqlParser.ShowMasterLogsContext showMasterLogsContext) {

    }

    @Override
    public void enterShowLogEvents(MySqlParser.ShowLogEventsContext showLogEventsContext) {

    }

    @Override
    public void exitShowLogEvents(MySqlParser.ShowLogEventsContext showLogEventsContext) {

    }

    @Override
    public void enterShowObjectFilter(MySqlParser.ShowObjectFilterContext showObjectFilterContext) {

    }

    @Override
    public void exitShowObjectFilter(MySqlParser.ShowObjectFilterContext showObjectFilterContext) {

    }

    @Override
    public void enterShowColumns(MySqlParser.ShowColumnsContext showColumnsContext) {

    }

    @Override
    public void exitShowColumns(MySqlParser.ShowColumnsContext showColumnsContext) {

    }

    @Override
    public void enterShowCreateDb(MySqlParser.ShowCreateDbContext showCreateDbContext) {

    }

    @Override
    public void exitShowCreateDb(MySqlParser.ShowCreateDbContext showCreateDbContext) {

    }

    @Override
    public void enterShowCreateFullIdObject(MySqlParser.ShowCreateFullIdObjectContext showCreateFullIdObjectContext) {

    }

    @Override
    public void exitShowCreateFullIdObject(MySqlParser.ShowCreateFullIdObjectContext showCreateFullIdObjectContext) {

    }

    @Override
    public void enterShowCreateUser(MySqlParser.ShowCreateUserContext showCreateUserContext) {

    }

    @Override
    public void exitShowCreateUser(MySqlParser.ShowCreateUserContext showCreateUserContext) {

    }

    @Override
    public void enterShowEngine(MySqlParser.ShowEngineContext showEngineContext) {

    }

    @Override
    public void exitShowEngine(MySqlParser.ShowEngineContext showEngineContext) {

    }

    @Override
    public void enterShowGlobalInfo(MySqlParser.ShowGlobalInfoContext showGlobalInfoContext) {

    }

    @Override
    public void exitShowGlobalInfo(MySqlParser.ShowGlobalInfoContext showGlobalInfoContext) {

    }

    @Override
    public void enterShowErrors(MySqlParser.ShowErrorsContext showErrorsContext) {

    }

    @Override
    public void exitShowErrors(MySqlParser.ShowErrorsContext showErrorsContext) {

    }

    @Override
    public void enterShowCountErrors(MySqlParser.ShowCountErrorsContext showCountErrorsContext) {

    }

    @Override
    public void exitShowCountErrors(MySqlParser.ShowCountErrorsContext showCountErrorsContext) {

    }

    @Override
    public void enterShowSchemaFilter(MySqlParser.ShowSchemaFilterContext showSchemaFilterContext) {

    }

    @Override
    public void exitShowSchemaFilter(MySqlParser.ShowSchemaFilterContext showSchemaFilterContext) {

    }

    @Override
    public void enterShowRoutine(MySqlParser.ShowRoutineContext showRoutineContext) {

    }

    @Override
    public void exitShowRoutine(MySqlParser.ShowRoutineContext showRoutineContext) {

    }

    @Override
    public void enterShowGrants(MySqlParser.ShowGrantsContext showGrantsContext) {

    }

    @Override
    public void exitShowGrants(MySqlParser.ShowGrantsContext showGrantsContext) {

    }

    @Override
    public void enterShowIndexes(MySqlParser.ShowIndexesContext showIndexesContext) {

    }

    @Override
    public void exitShowIndexes(MySqlParser.ShowIndexesContext showIndexesContext) {

    }

    @Override
    public void enterShowOpenTables(MySqlParser.ShowOpenTablesContext showOpenTablesContext) {

    }

    @Override
    public void exitShowOpenTables(MySqlParser.ShowOpenTablesContext showOpenTablesContext) {

    }

    @Override
    public void enterShowProfile(MySqlParser.ShowProfileContext showProfileContext) {

    }

    @Override
    public void exitShowProfile(MySqlParser.ShowProfileContext showProfileContext) {

    }

    @Override
    public void enterShowSlaveStatus(MySqlParser.ShowSlaveStatusContext showSlaveStatusContext) {

    }

    @Override
    public void exitShowSlaveStatus(MySqlParser.ShowSlaveStatusContext showSlaveStatusContext) {

    }

    @Override
    public void enterShowUserstatPlugin(MySqlParser.ShowUserstatPluginContext showUserstatPluginContext) {

    }

    @Override
    public void exitShowUserstatPlugin(MySqlParser.ShowUserstatPluginContext showUserstatPluginContext) {

    }

    @Override
    public void enterVariableClause(MySqlParser.VariableClauseContext variableClauseContext) {

    }

    @Override
    public void exitVariableClause(MySqlParser.VariableClauseContext variableClauseContext) {

    }

    @Override
    public void enterShowCommonEntity(MySqlParser.ShowCommonEntityContext showCommonEntityContext) {

    }

    @Override
    public void exitShowCommonEntity(MySqlParser.ShowCommonEntityContext showCommonEntityContext) {

    }

    @Override
    public void enterShowFilter(MySqlParser.ShowFilterContext showFilterContext) {

    }

    @Override
    public void exitShowFilter(MySqlParser.ShowFilterContext showFilterContext) {

    }

    @Override
    public void enterShowGlobalInfoClause(MySqlParser.ShowGlobalInfoClauseContext showGlobalInfoClauseContext) {

    }

    @Override
    public void exitShowGlobalInfoClause(MySqlParser.ShowGlobalInfoClauseContext showGlobalInfoClauseContext) {

    }

    @Override
    public void enterShowSchemaEntity(MySqlParser.ShowSchemaEntityContext showSchemaEntityContext) {

    }

    @Override
    public void exitShowSchemaEntity(MySqlParser.ShowSchemaEntityContext showSchemaEntityContext) {

    }

    @Override
    public void enterShowProfileType(MySqlParser.ShowProfileTypeContext showProfileTypeContext) {

    }

    @Override
    public void exitShowProfileType(MySqlParser.ShowProfileTypeContext showProfileTypeContext) {

    }

    @Override
    public void enterBinlogStatement(MySqlParser.BinlogStatementContext binlogStatementContext) {

    }

    @Override
    public void exitBinlogStatement(MySqlParser.BinlogStatementContext binlogStatementContext) {

    }

    @Override
    public void enterCacheIndexStatement(MySqlParser.CacheIndexStatementContext cacheIndexStatementContext) {

    }

    @Override
    public void exitCacheIndexStatement(MySqlParser.CacheIndexStatementContext cacheIndexStatementContext) {

    }

    @Override
    public void enterFlushStatement(MySqlParser.FlushStatementContext flushStatementContext) {

    }

    @Override
    public void exitFlushStatement(MySqlParser.FlushStatementContext flushStatementContext) {

    }

    @Override
    public void enterKillStatement(MySqlParser.KillStatementContext killStatementContext) {

    }

    @Override
    public void exitKillStatement(MySqlParser.KillStatementContext killStatementContext) {

    }

    @Override
    public void enterLoadIndexIntoCache(MySqlParser.LoadIndexIntoCacheContext loadIndexIntoCacheContext) {

    }

    @Override
    public void exitLoadIndexIntoCache(MySqlParser.LoadIndexIntoCacheContext loadIndexIntoCacheContext) {

    }

    @Override
    public void enterResetStatement(MySqlParser.ResetStatementContext resetStatementContext) {

    }

    @Override
    public void exitResetStatement(MySqlParser.ResetStatementContext resetStatementContext) {

    }

    @Override
    public void enterShutdownStatement(MySqlParser.ShutdownStatementContext shutdownStatementContext) {

    }

    @Override
    public void exitShutdownStatement(MySqlParser.ShutdownStatementContext shutdownStatementContext) {

    }

    @Override
    public void enterTableIndexes(MySqlParser.TableIndexesContext tableIndexesContext) {

    }

    @Override
    public void exitTableIndexes(MySqlParser.TableIndexesContext tableIndexesContext) {

    }

    @Override
    public void enterSimpleFlushOption(MySqlParser.SimpleFlushOptionContext simpleFlushOptionContext) {

    }

    @Override
    public void exitSimpleFlushOption(MySqlParser.SimpleFlushOptionContext simpleFlushOptionContext) {

    }

    @Override
    public void enterChannelFlushOption(MySqlParser.ChannelFlushOptionContext channelFlushOptionContext) {

    }

    @Override
    public void exitChannelFlushOption(MySqlParser.ChannelFlushOptionContext channelFlushOptionContext) {

    }

    @Override
    public void enterTableFlushOption(MySqlParser.TableFlushOptionContext tableFlushOptionContext) {

    }

    @Override
    public void exitTableFlushOption(MySqlParser.TableFlushOptionContext tableFlushOptionContext) {

    }

    @Override
    public void enterFlushTableOption(MySqlParser.FlushTableOptionContext flushTableOptionContext) {

    }

    @Override
    public void exitFlushTableOption(MySqlParser.FlushTableOptionContext flushTableOptionContext) {

    }

    @Override
    public void enterLoadedTableIndexes(MySqlParser.LoadedTableIndexesContext loadedTableIndexesContext) {

    }

    @Override
    public void exitLoadedTableIndexes(MySqlParser.LoadedTableIndexesContext loadedTableIndexesContext) {

    }

    @Override
    public void enterSimpleDescribeStatement(MySqlParser.SimpleDescribeStatementContext simpleDescribeStatementContext) {

    }

    @Override
    public void exitSimpleDescribeStatement(MySqlParser.SimpleDescribeStatementContext simpleDescribeStatementContext) {

    }

    @Override
    public void enterFullDescribeStatement(MySqlParser.FullDescribeStatementContext fullDescribeStatementContext) {

    }

    @Override
    public void exitFullDescribeStatement(MySqlParser.FullDescribeStatementContext fullDescribeStatementContext) {

    }

    @Override
    public void enterHelpStatement(MySqlParser.HelpStatementContext helpStatementContext) {

    }

    @Override
    public void exitHelpStatement(MySqlParser.HelpStatementContext helpStatementContext) {

    }

    @Override
    public void enterUseStatement(MySqlParser.UseStatementContext useStatementContext) {

    }

    @Override
    public void exitUseStatement(MySqlParser.UseStatementContext useStatementContext) {

    }

    @Override
    public void enterSignalStatement(MySqlParser.SignalStatementContext signalStatementContext) {

    }

    @Override
    public void exitSignalStatement(MySqlParser.SignalStatementContext signalStatementContext) {

    }

    @Override
    public void enterResignalStatement(MySqlParser.ResignalStatementContext resignalStatementContext) {

    }

    @Override
    public void exitResignalStatement(MySqlParser.ResignalStatementContext resignalStatementContext) {

    }

    @Override
    public void enterSignalConditionInformation(MySqlParser.SignalConditionInformationContext signalConditionInformationContext) {

    }

    @Override
    public void exitSignalConditionInformation(MySqlParser.SignalConditionInformationContext signalConditionInformationContext) {

    }

    @Override
    public void enterDiagnosticsStatement(MySqlParser.DiagnosticsStatementContext diagnosticsStatementContext) {

    }

    @Override
    public void exitDiagnosticsStatement(MySqlParser.DiagnosticsStatementContext diagnosticsStatementContext) {

    }

    @Override
    public void enterDiagnosticsConditionInformationName(MySqlParser.DiagnosticsConditionInformationNameContext diagnosticsConditionInformationNameContext) {

    }

    @Override
    public void exitDiagnosticsConditionInformationName(MySqlParser.DiagnosticsConditionInformationNameContext diagnosticsConditionInformationNameContext) {

    }

    @Override
    public void enterDescribeStatements(MySqlParser.DescribeStatementsContext describeStatementsContext) {

    }

    @Override
    public void exitDescribeStatements(MySqlParser.DescribeStatementsContext describeStatementsContext) {

    }

    @Override
    public void enterDescribeConnection(MySqlParser.DescribeConnectionContext describeConnectionContext) {

    }

    @Override
    public void exitDescribeConnection(MySqlParser.DescribeConnectionContext describeConnectionContext) {

    }

    @Override
    public void enterFullId(MySqlParser.FullIdContext fullIdContext) {

    }

    @Override
    public void exitFullId(MySqlParser.FullIdContext fullIdContext) {

    }

    @Override
    public void enterTableName(TableNameContext tableNameContext) {

    }

    @Override
    public void exitTableName(TableNameContext tableNameContext) {

    }

    @Override
    public void enterRoleName(MySqlParser.RoleNameContext roleNameContext) {

    }

    @Override
    public void exitRoleName(MySqlParser.RoleNameContext roleNameContext) {

    }

    @Override
    public void enterFullColumnName(MySqlParser.FullColumnNameContext fullColumnNameContext) {

    }

    @Override
    public void exitFullColumnName(MySqlParser.FullColumnNameContext fullColumnNameContext) {

    }

    @Override
    public void enterIndexColumnName(MySqlParser.IndexColumnNameContext indexColumnNameContext) {

    }

    @Override
    public void exitIndexColumnName(MySqlParser.IndexColumnNameContext indexColumnNameContext) {

    }

    @Override
    public void enterSimpleUserName(MySqlParser.SimpleUserNameContext simpleUserNameContext) {

    }

    @Override
    public void exitSimpleUserName(MySqlParser.SimpleUserNameContext simpleUserNameContext) {

    }

    @Override
    public void enterHostName(MySqlParser.HostNameContext hostNameContext) {

    }

    @Override
    public void exitHostName(MySqlParser.HostNameContext hostNameContext) {

    }

    @Override
    public void enterUserName(MySqlParser.UserNameContext userNameContext) {

    }

    @Override
    public void exitUserName(MySqlParser.UserNameContext userNameContext) {

    }

    @Override
    public void enterMysqlVariable(MySqlParser.MysqlVariableContext mysqlVariableContext) {

    }

    @Override
    public void exitMysqlVariable(MySqlParser.MysqlVariableContext mysqlVariableContext) {

    }

    @Override
    public void enterCharsetName(MySqlParser.CharsetNameContext charsetNameContext) {

    }

    @Override
    public void exitCharsetName(MySqlParser.CharsetNameContext charsetNameContext) {

    }

    @Override
    public void enterCollationName(MySqlParser.CollationNameContext collationNameContext) {

    }

    @Override
    public void exitCollationName(MySqlParser.CollationNameContext collationNameContext) {

    }

    @Override
    public void enterEngineName(MySqlParser.EngineNameContext engineNameContext) {

    }

    @Override
    public void exitEngineName(MySqlParser.EngineNameContext engineNameContext) {

    }

    @Override
    public void enterEngineNameBase(MySqlParser.EngineNameBaseContext engineNameBaseContext) {

    }

    @Override
    public void exitEngineNameBase(MySqlParser.EngineNameBaseContext engineNameBaseContext) {

    }

    @Override
    public void enterEncryptedLiteral(MySqlParser.EncryptedLiteralContext encryptedLiteralContext) {

    }

    @Override
    public void exitEncryptedLiteral(MySqlParser.EncryptedLiteralContext encryptedLiteralContext) {

    }

    @Override
    public void enterUuidSet(MySqlParser.UuidSetContext uuidSetContext) {

    }

    @Override
    public void exitUuidSet(MySqlParser.UuidSetContext uuidSetContext) {

    }

    @Override
    public void enterXid(MySqlParser.XidContext xidContext) {

    }

    @Override
    public void exitXid(MySqlParser.XidContext xidContext) {

    }

    @Override
    public void enterXuidStringId(MySqlParser.XuidStringIdContext xuidStringIdContext) {

    }

    @Override
    public void exitXuidStringId(MySqlParser.XuidStringIdContext xuidStringIdContext) {

    }

    @Override
    public void enterAuthPlugin(MySqlParser.AuthPluginContext authPluginContext) {

    }

    @Override
    public void exitAuthPlugin(MySqlParser.AuthPluginContext authPluginContext) {

    }

    @Override
    public void enterUid(MySqlParser.UidContext uidContext) {

    }

    @Override
    public void exitUid(MySqlParser.UidContext uidContext) {

    }

    @Override
    public void enterSimpleId(MySqlParser.SimpleIdContext simpleIdContext) {

    }

    @Override
    public void exitSimpleId(MySqlParser.SimpleIdContext simpleIdContext) {

    }

    @Override
    public void enterDottedId(MySqlParser.DottedIdContext dottedIdContext) {

    }

    @Override
    public void exitDottedId(MySqlParser.DottedIdContext dottedIdContext) {

    }

    @Override
    public void enterDecimalLiteral(MySqlParser.DecimalLiteralContext decimalLiteralContext) {

    }

    @Override
    public void exitDecimalLiteral(MySqlParser.DecimalLiteralContext decimalLiteralContext) {

    }

    @Override
    public void enterFileSizeLiteral(MySqlParser.FileSizeLiteralContext fileSizeLiteralContext) {

    }

    @Override
    public void exitFileSizeLiteral(MySqlParser.FileSizeLiteralContext fileSizeLiteralContext) {

    }

    @Override
    public void enterStringLiteral(MySqlParser.StringLiteralContext stringLiteralContext) {

    }

    @Override
    public void exitStringLiteral(MySqlParser.StringLiteralContext stringLiteralContext) {

    }

    @Override
    public void enterBooleanLiteral(MySqlParser.BooleanLiteralContext booleanLiteralContext) {

    }

    @Override
    public void exitBooleanLiteral(MySqlParser.BooleanLiteralContext booleanLiteralContext) {

    }

    @Override
    public void enterHexadecimalLiteral(MySqlParser.HexadecimalLiteralContext hexadecimalLiteralContext) {

    }

    @Override
    public void exitHexadecimalLiteral(MySqlParser.HexadecimalLiteralContext hexadecimalLiteralContext) {

    }

    @Override
    public void enterNullNotnull(MySqlParser.NullNotnullContext nullNotnullContext) {

    }

    @Override
    public void exitNullNotnull(MySqlParser.NullNotnullContext nullNotnullContext) {

    }

    @Override
    public void enterConstant(MySqlParser.ConstantContext constantContext) {

    }

    @Override
    public void exitConstant(MySqlParser.ConstantContext constantContext) {

    }

    @Override
    public void enterStringDataType(MySqlParser.StringDataTypeContext stringDataTypeContext) {

    }

    @Override
    public void exitStringDataType(MySqlParser.StringDataTypeContext stringDataTypeContext) {

    }

    @Override
    public void enterNationalStringDataType(MySqlParser.NationalStringDataTypeContext nationalStringDataTypeContext) {

    }

    @Override
    public void exitNationalStringDataType(MySqlParser.NationalStringDataTypeContext nationalStringDataTypeContext) {

    }

    @Override
    public void enterNationalVaryingStringDataType(MySqlParser.NationalVaryingStringDataTypeContext nationalVaryingStringDataTypeContext) {

    }

    @Override
    public void exitNationalVaryingStringDataType(MySqlParser.NationalVaryingStringDataTypeContext nationalVaryingStringDataTypeContext) {

    }

    @Override
    public void enterDimensionDataType(MySqlParser.DimensionDataTypeContext dimensionDataTypeContext) {

    }

    @Override
    public void exitDimensionDataType(MySqlParser.DimensionDataTypeContext dimensionDataTypeContext) {

    }

    @Override
    public void enterSimpleDataType(MySqlParser.SimpleDataTypeContext simpleDataTypeContext) {

    }

    @Override
    public void exitSimpleDataType(MySqlParser.SimpleDataTypeContext simpleDataTypeContext) {

    }

    @Override
    public void enterCollectionDataType(MySqlParser.CollectionDataTypeContext collectionDataTypeContext) {

    }

    @Override
    public void exitCollectionDataType(MySqlParser.CollectionDataTypeContext collectionDataTypeContext) {

    }

    @Override
    public void enterSpatialDataType(MySqlParser.SpatialDataTypeContext spatialDataTypeContext) {

    }

    @Override
    public void exitSpatialDataType(MySqlParser.SpatialDataTypeContext spatialDataTypeContext) {

    }

    @Override
    public void enterLongVarcharDataType(MySqlParser.LongVarcharDataTypeContext longVarcharDataTypeContext) {

    }

    @Override
    public void exitLongVarcharDataType(MySqlParser.LongVarcharDataTypeContext longVarcharDataTypeContext) {

    }

    @Override
    public void enterLongVarbinaryDataType(MySqlParser.LongVarbinaryDataTypeContext longVarbinaryDataTypeContext) {

    }

    @Override
    public void exitLongVarbinaryDataType(MySqlParser.LongVarbinaryDataTypeContext longVarbinaryDataTypeContext) {

    }

    @Override
    public void enterUuidDataType(MySqlParser.UuidDataTypeContext uuidDataTypeContext) {

    }

    @Override
    public void exitUuidDataType(MySqlParser.UuidDataTypeContext uuidDataTypeContext) {

    }

//    @Override
//    public void enterUuidDataType(MySqlParser.UuidDataTypeContext uuidDataTypeContext) {
//
//    }
//
//    @Override
//    public void exitUuidDataType(MySqlParser.UuidDataTypeContext uuidDataTypeContext) {
//
//    }

    @Override
    public void enterCollectionOptions(MySqlParser.CollectionOptionsContext collectionOptionsContext) {

    }

    @Override
    public void exitCollectionOptions(MySqlParser.CollectionOptionsContext collectionOptionsContext) {

    }

    @Override
    public void enterCollectionOption(MySqlParser.CollectionOptionContext collectionOptionContext) {

    }

    @Override
    public void exitCollectionOption(MySqlParser.CollectionOptionContext collectionOptionContext) {

    }

    @Override
    public void enterConvertedDataType(MySqlParser.ConvertedDataTypeContext convertedDataTypeContext) {

    }

    @Override
    public void exitConvertedDataType(MySqlParser.ConvertedDataTypeContext convertedDataTypeContext) {

    }

    @Override
    public void enterLengthOneDimension(MySqlParser.LengthOneDimensionContext lengthOneDimensionContext) {

    }

    @Override
    public void exitLengthOneDimension(MySqlParser.LengthOneDimensionContext lengthOneDimensionContext) {

    }

    @Override
    public void enterLengthTwoDimension(MySqlParser.LengthTwoDimensionContext lengthTwoDimensionContext) {

    }

    @Override
    public void exitLengthTwoDimension(MySqlParser.LengthTwoDimensionContext lengthTwoDimensionContext) {

    }

    @Override
    public void enterLengthTwoOptionalDimension(MySqlParser.LengthTwoOptionalDimensionContext lengthTwoOptionalDimensionContext) {

    }

    @Override
    public void exitLengthTwoOptionalDimension(MySqlParser.LengthTwoOptionalDimensionContext lengthTwoOptionalDimensionContext) {

    }

    @Override
    public void enterUidList(MySqlParser.UidListContext uidListContext) {

    }

    @Override
    public void exitUidList(MySqlParser.UidListContext uidListContext) {

    }

    @Override
    public void enterFullColumnNameList(MySqlParser.FullColumnNameListContext fullColumnNameListContext) {

    }

    @Override
    public void exitFullColumnNameList(MySqlParser.FullColumnNameListContext fullColumnNameListContext) {

    }

    @Override
    public void enterTables(MySqlParser.TablesContext tablesContext) {

    }

    @Override
    public void exitTables(MySqlParser.TablesContext tablesContext) {

    }

    @Override
    public void enterIndexColumnNames(MySqlParser.IndexColumnNamesContext indexColumnNamesContext) {

    }

    @Override
    public void exitIndexColumnNames(MySqlParser.IndexColumnNamesContext indexColumnNamesContext) {

    }

    @Override
    public void enterExpressions(MySqlParser.ExpressionsContext expressionsContext) {

    }

    @Override
    public void exitExpressions(MySqlParser.ExpressionsContext expressionsContext) {

    }

    @Override
    public void enterExpressionsWithDefaults(MySqlParser.ExpressionsWithDefaultsContext expressionsWithDefaultsContext) {

    }

    @Override
    public void exitExpressionsWithDefaults(MySqlParser.ExpressionsWithDefaultsContext expressionsWithDefaultsContext) {

    }

    @Override
    public void enterConstants(MySqlParser.ConstantsContext constantsContext) {

    }

    @Override
    public void exitConstants(MySqlParser.ConstantsContext constantsContext) {

    }

    @Override
    public void enterSimpleStrings(MySqlParser.SimpleStringsContext simpleStringsContext) {

    }

    @Override
    public void exitSimpleStrings(MySqlParser.SimpleStringsContext simpleStringsContext) {

    }

    @Override
    public void enterUserVariables(MySqlParser.UserVariablesContext userVariablesContext) {

    }

    @Override
    public void exitUserVariables(MySqlParser.UserVariablesContext userVariablesContext) {

    }

    @Override
    public void enterDefaultValue(MySqlParser.DefaultValueContext defaultValueContext) {

    }

    @Override
    public void exitDefaultValue(MySqlParser.DefaultValueContext defaultValueContext) {

    }

    @Override
    public void enterCurrentTimestamp(MySqlParser.CurrentTimestampContext currentTimestampContext) {

    }

    @Override
    public void exitCurrentTimestamp(MySqlParser.CurrentTimestampContext currentTimestampContext) {

    }

    @Override
    public void enterExpressionOrDefault(MySqlParser.ExpressionOrDefaultContext expressionOrDefaultContext) {

    }

    @Override
    public void exitExpressionOrDefault(MySqlParser.ExpressionOrDefaultContext expressionOrDefaultContext) {

    }

    @Override
    public void enterIfExists(MySqlParser.IfExistsContext ifExistsContext) {

    }

    @Override
    public void exitIfExists(MySqlParser.IfExistsContext ifExistsContext) {

    }

    @Override
    public void enterIfNotExists(MySqlParser.IfNotExistsContext ifNotExistsContext) {

    }

    @Override
    public void exitIfNotExists(MySqlParser.IfNotExistsContext ifNotExistsContext) {

    }

    @Override
    public void enterOrReplace(MySqlParser.OrReplaceContext orReplaceContext) {

    }

    @Override
    public void exitOrReplace(MySqlParser.OrReplaceContext orReplaceContext) {

    }

    @Override
    public void enterWaitNowaitClause(MySqlParser.WaitNowaitClauseContext waitNowaitClauseContext) {

    }

    @Override
    public void exitWaitNowaitClause(MySqlParser.WaitNowaitClauseContext waitNowaitClauseContext) {

    }

    @Override
    public void enterLockOption(MySqlParser.LockOptionContext lockOptionContext) {

    }

    @Override
    public void exitLockOption(MySqlParser.LockOptionContext lockOptionContext) {

    }

    @Override
    public void enterSpecificFunctionCall(MySqlParser.SpecificFunctionCallContext specificFunctionCallContext) {

    }

    @Override
    public void exitSpecificFunctionCall(MySqlParser.SpecificFunctionCallContext specificFunctionCallContext) {

    }

    @Override
    public void enterAggregateFunctionCall(MySqlParser.AggregateFunctionCallContext aggregateFunctionCallContext) {

    }

    @Override
    public void exitAggregateFunctionCall(MySqlParser.AggregateFunctionCallContext aggregateFunctionCallContext) {

    }

    @Override
    public void enterNonAggregateFunctionCall(MySqlParser.NonAggregateFunctionCallContext nonAggregateFunctionCallContext) {

    }

    @Override
    public void exitNonAggregateFunctionCall(MySqlParser.NonAggregateFunctionCallContext nonAggregateFunctionCallContext) {

    }

    @Override
    public void enterScalarFunctionCall(MySqlParser.ScalarFunctionCallContext scalarFunctionCallContext) {

    }

    @Override
    public void exitScalarFunctionCall(MySqlParser.ScalarFunctionCallContext scalarFunctionCallContext) {

    }

    @Override
    public void enterUdfFunctionCall(MySqlParser.UdfFunctionCallContext udfFunctionCallContext) {

    }

    @Override
    public void exitUdfFunctionCall(MySqlParser.UdfFunctionCallContext udfFunctionCallContext) {

    }

    @Override
    public void enterPasswordFunctionCall(MySqlParser.PasswordFunctionCallContext passwordFunctionCallContext) {

    }

    @Override
    public void exitPasswordFunctionCall(MySqlParser.PasswordFunctionCallContext passwordFunctionCallContext) {

    }

    @Override
    public void enterSimpleFunctionCall(MySqlParser.SimpleFunctionCallContext simpleFunctionCallContext) {

    }

    @Override
    public void exitSimpleFunctionCall(MySqlParser.SimpleFunctionCallContext simpleFunctionCallContext) {

    }

    @Override
    public void enterCurrentUser(MySqlParser.CurrentUserContext currentUserContext) {

    }

    @Override
    public void exitCurrentUser(MySqlParser.CurrentUserContext currentUserContext) {

    }

    @Override
    public void enterDataTypeFunctionCall(MySqlParser.DataTypeFunctionCallContext dataTypeFunctionCallContext) {

    }

    @Override
    public void exitDataTypeFunctionCall(MySqlParser.DataTypeFunctionCallContext dataTypeFunctionCallContext) {

    }

    @Override
    public void enterValuesFunctionCall(MySqlParser.ValuesFunctionCallContext valuesFunctionCallContext) {

    }

    @Override
    public void exitValuesFunctionCall(MySqlParser.ValuesFunctionCallContext valuesFunctionCallContext) {

    }

    @Override
    public void enterCaseExpressionFunctionCall(MySqlParser.CaseExpressionFunctionCallContext caseExpressionFunctionCallContext) {

    }

    @Override
    public void exitCaseExpressionFunctionCall(MySqlParser.CaseExpressionFunctionCallContext caseExpressionFunctionCallContext) {

    }

    @Override
    public void enterCaseFunctionCall(MySqlParser.CaseFunctionCallContext caseFunctionCallContext) {

    }

    @Override
    public void exitCaseFunctionCall(MySqlParser.CaseFunctionCallContext caseFunctionCallContext) {

    }

    @Override
    public void enterCharFunctionCall(MySqlParser.CharFunctionCallContext charFunctionCallContext) {

    }

    @Override
    public void exitCharFunctionCall(MySqlParser.CharFunctionCallContext charFunctionCallContext) {

    }

    @Override
    public void enterPositionFunctionCall(MySqlParser.PositionFunctionCallContext positionFunctionCallContext) {

    }

    @Override
    public void exitPositionFunctionCall(MySqlParser.PositionFunctionCallContext positionFunctionCallContext) {

    }

    @Override
    public void enterSubstrFunctionCall(MySqlParser.SubstrFunctionCallContext substrFunctionCallContext) {

    }

    @Override
    public void exitSubstrFunctionCall(MySqlParser.SubstrFunctionCallContext substrFunctionCallContext) {

    }

    @Override
    public void enterTrimFunctionCall(MySqlParser.TrimFunctionCallContext trimFunctionCallContext) {

    }

    @Override
    public void exitTrimFunctionCall(MySqlParser.TrimFunctionCallContext trimFunctionCallContext) {

    }

    @Override
    public void enterWeightFunctionCall(MySqlParser.WeightFunctionCallContext weightFunctionCallContext) {

    }

    @Override
    public void exitWeightFunctionCall(MySqlParser.WeightFunctionCallContext weightFunctionCallContext) {

    }

    @Override
    public void enterExtractFunctionCall(MySqlParser.ExtractFunctionCallContext extractFunctionCallContext) {

    }

    @Override
    public void exitExtractFunctionCall(MySqlParser.ExtractFunctionCallContext extractFunctionCallContext) {

    }

    @Override
    public void enterGetFormatFunctionCall(MySqlParser.GetFormatFunctionCallContext getFormatFunctionCallContext) {

    }

    @Override
    public void exitGetFormatFunctionCall(MySqlParser.GetFormatFunctionCallContext getFormatFunctionCallContext) {

    }

    @Override
    public void enterJsonValueFunctionCall(MySqlParser.JsonValueFunctionCallContext jsonValueFunctionCallContext) {

    }

    @Override
    public void exitJsonValueFunctionCall(MySqlParser.JsonValueFunctionCallContext jsonValueFunctionCallContext) {

    }

    @Override
    public void enterCaseFuncAlternative(MySqlParser.CaseFuncAlternativeContext caseFuncAlternativeContext) {

    }

    @Override
    public void exitCaseFuncAlternative(MySqlParser.CaseFuncAlternativeContext caseFuncAlternativeContext) {

    }

    @Override
    public void enterLevelWeightList(MySqlParser.LevelWeightListContext levelWeightListContext) {

    }

    @Override
    public void exitLevelWeightList(MySqlParser.LevelWeightListContext levelWeightListContext) {

    }

    @Override
    public void enterLevelWeightRange(MySqlParser.LevelWeightRangeContext levelWeightRangeContext) {

    }

    @Override
    public void exitLevelWeightRange(MySqlParser.LevelWeightRangeContext levelWeightRangeContext) {

    }

    @Override
    public void enterLevelInWeightListElement(MySqlParser.LevelInWeightListElementContext levelInWeightListElementContext) {

    }

    @Override
    public void exitLevelInWeightListElement(MySqlParser.LevelInWeightListElementContext levelInWeightListElementContext) {

    }

    @Override
    public void enterAggregateWindowedFunction(MySqlParser.AggregateWindowedFunctionContext aggregateWindowedFunctionContext) {

    }

    @Override
    public void exitAggregateWindowedFunction(MySqlParser.AggregateWindowedFunctionContext aggregateWindowedFunctionContext) {

    }

    @Override
    public void enterNonAggregateWindowedFunction(MySqlParser.NonAggregateWindowedFunctionContext nonAggregateWindowedFunctionContext) {

    }

    @Override
    public void exitNonAggregateWindowedFunction(MySqlParser.NonAggregateWindowedFunctionContext nonAggregateWindowedFunctionContext) {

    }

    @Override
    public void enterOverClause(MySqlParser.OverClauseContext overClauseContext) {

    }

    @Override
    public void exitOverClause(MySqlParser.OverClauseContext overClauseContext) {

    }

    @Override
    public void enterWindowSpec(MySqlParser.WindowSpecContext windowSpecContext) {

    }

    @Override
    public void exitWindowSpec(MySqlParser.WindowSpecContext windowSpecContext) {

    }

    @Override
    public void enterWindowName(MySqlParser.WindowNameContext windowNameContext) {

    }

    @Override
    public void exitWindowName(MySqlParser.WindowNameContext windowNameContext) {

    }

    @Override
    public void enterFrameClause(MySqlParser.FrameClauseContext frameClauseContext) {

    }

    @Override
    public void exitFrameClause(MySqlParser.FrameClauseContext frameClauseContext) {

    }

    @Override
    public void enterFrameUnits(MySqlParser.FrameUnitsContext frameUnitsContext) {

    }

    @Override
    public void exitFrameUnits(MySqlParser.FrameUnitsContext frameUnitsContext) {

    }

    @Override
    public void enterFrameExtent(MySqlParser.FrameExtentContext frameExtentContext) {

    }

    @Override
    public void exitFrameExtent(MySqlParser.FrameExtentContext frameExtentContext) {

    }

    @Override
    public void enterFrameBetween(MySqlParser.FrameBetweenContext frameBetweenContext) {

    }

    @Override
    public void exitFrameBetween(MySqlParser.FrameBetweenContext frameBetweenContext) {

    }

    @Override
    public void enterFrameRange(MySqlParser.FrameRangeContext frameRangeContext) {

    }

    @Override
    public void exitFrameRange(MySqlParser.FrameRangeContext frameRangeContext) {

    }

    @Override
    public void enterPartitionClause(MySqlParser.PartitionClauseContext partitionClauseContext) {

    }

    @Override
    public void exitPartitionClause(MySqlParser.PartitionClauseContext partitionClauseContext) {

    }

    @Override
    public void enterScalarFunctionName(MySqlParser.ScalarFunctionNameContext scalarFunctionNameContext) {

    }

    @Override
    public void exitScalarFunctionName(MySqlParser.ScalarFunctionNameContext scalarFunctionNameContext) {

    }

    @Override
    public void enterPasswordFunctionClause(MySqlParser.PasswordFunctionClauseContext passwordFunctionClauseContext) {

    }

    @Override
    public void exitPasswordFunctionClause(MySqlParser.PasswordFunctionClauseContext passwordFunctionClauseContext) {

    }

    @Override
    public void enterFunctionArgs(MySqlParser.FunctionArgsContext functionArgsContext) {

    }

    @Override
    public void exitFunctionArgs(MySqlParser.FunctionArgsContext functionArgsContext) {

    }

    @Override
    public void enterFunctionArg(MySqlParser.FunctionArgContext functionArgContext) {

    }

    @Override
    public void exitFunctionArg(MySqlParser.FunctionArgContext functionArgContext) {

    }

    @Override
    public void enterIsExpression(MySqlParser.IsExpressionContext isExpressionContext) {

    }

    @Override
    public void exitIsExpression(MySqlParser.IsExpressionContext isExpressionContext) {

    }

    @Override
    public void enterNotExpression(MySqlParser.NotExpressionContext notExpressionContext) {

    }

    @Override
    public void exitNotExpression(MySqlParser.NotExpressionContext notExpressionContext) {

    }

    @Override
    public void enterLogicalExpression(MySqlParser.LogicalExpressionContext logicalExpressionContext) {

    }

    @Override
    public void exitLogicalExpression(MySqlParser.LogicalExpressionContext logicalExpressionContext) {

    }

    @Override
    public void enterPredicateExpression(MySqlParser.PredicateExpressionContext predicateExpressionContext) {

    }

    @Override
    public void exitPredicateExpression(MySqlParser.PredicateExpressionContext predicateExpressionContext) {

    }

    @Override
    public void enterSoundsLikePredicate(MySqlParser.SoundsLikePredicateContext soundsLikePredicateContext) {

    }

    @Override
    public void exitSoundsLikePredicate(MySqlParser.SoundsLikePredicateContext soundsLikePredicateContext) {

    }

    @Override
    public void enterExpressionAtomPredicate(MySqlParser.ExpressionAtomPredicateContext expressionAtomPredicateContext) {

    }

    @Override
    public void exitExpressionAtomPredicate(MySqlParser.ExpressionAtomPredicateContext expressionAtomPredicateContext) {

    }

    @Override
    public void enterSubqueryComparisonPredicate(MySqlParser.SubqueryComparisonPredicateContext subqueryComparisonPredicateContext) {

    }

    @Override
    public void exitSubqueryComparisonPredicate(MySqlParser.SubqueryComparisonPredicateContext subqueryComparisonPredicateContext) {

    }

    @Override
    public void enterJsonMemberOfPredicate(MySqlParser.JsonMemberOfPredicateContext jsonMemberOfPredicateContext) {

    }

    @Override
    public void exitJsonMemberOfPredicate(MySqlParser.JsonMemberOfPredicateContext jsonMemberOfPredicateContext) {

    }

    @Override
    public void enterBinaryComparisonPredicate(MySqlParser.BinaryComparisonPredicateContext binaryComparisonPredicateContext) {

    }

    @Override
    public void exitBinaryComparisonPredicate(MySqlParser.BinaryComparisonPredicateContext binaryComparisonPredicateContext) {

    }

    @Override
    public void enterInPredicate(MySqlParser.InPredicateContext inPredicateContext) {

    }

    @Override
    public void exitInPredicate(MySqlParser.InPredicateContext inPredicateContext) {

    }

    @Override
    public void enterBetweenPredicate(MySqlParser.BetweenPredicateContext betweenPredicateContext) {

    }

    @Override
    public void exitBetweenPredicate(MySqlParser.BetweenPredicateContext betweenPredicateContext) {

    }

    @Override
    public void enterIsNullPredicate(MySqlParser.IsNullPredicateContext isNullPredicateContext) {

    }

    @Override
    public void exitIsNullPredicate(MySqlParser.IsNullPredicateContext isNullPredicateContext) {

    }

    @Override
    public void enterLikePredicate(MySqlParser.LikePredicateContext likePredicateContext) {

    }

    @Override
    public void exitLikePredicate(MySqlParser.LikePredicateContext likePredicateContext) {

    }

    @Override
    public void enterRegexpPredicate(MySqlParser.RegexpPredicateContext regexpPredicateContext) {

    }

    @Override
    public void exitRegexpPredicate(MySqlParser.RegexpPredicateContext regexpPredicateContext) {

    }

    @Override
    public void enterUnaryExpressionAtom(MySqlParser.UnaryExpressionAtomContext unaryExpressionAtomContext) {

    }

    @Override
    public void exitUnaryExpressionAtom(MySqlParser.UnaryExpressionAtomContext unaryExpressionAtomContext) {

    }

    @Override
    public void enterCollateExpressionAtom(MySqlParser.CollateExpressionAtomContext collateExpressionAtomContext) {

    }

    @Override
    public void exitCollateExpressionAtom(MySqlParser.CollateExpressionAtomContext collateExpressionAtomContext) {

    }

    @Override
    public void enterVariableAssignExpressionAtom(MySqlParser.VariableAssignExpressionAtomContext variableAssignExpressionAtomContext) {

    }

    @Override
    public void exitVariableAssignExpressionAtom(MySqlParser.VariableAssignExpressionAtomContext variableAssignExpressionAtomContext) {

    }

    @Override
    public void enterMysqlVariableExpressionAtom(MySqlParser.MysqlVariableExpressionAtomContext mysqlVariableExpressionAtomContext) {

    }

    @Override
    public void exitMysqlVariableExpressionAtom(MySqlParser.MysqlVariableExpressionAtomContext mysqlVariableExpressionAtomContext) {

    }

    @Override
    public void enterNestedExpressionAtom(MySqlParser.NestedExpressionAtomContext nestedExpressionAtomContext) {

    }

    @Override
    public void exitNestedExpressionAtom(MySqlParser.NestedExpressionAtomContext nestedExpressionAtomContext) {

    }

    @Override
    public void enterNestedRowExpressionAtom(MySqlParser.NestedRowExpressionAtomContext nestedRowExpressionAtomContext) {

    }

    @Override
    public void exitNestedRowExpressionAtom(MySqlParser.NestedRowExpressionAtomContext nestedRowExpressionAtomContext) {

    }

    @Override
    public void enterMathExpressionAtom(MySqlParser.MathExpressionAtomContext mathExpressionAtomContext) {

    }

    @Override
    public void exitMathExpressionAtom(MySqlParser.MathExpressionAtomContext mathExpressionAtomContext) {

    }

    @Override
    public void enterExistsExpressionAtom(MySqlParser.ExistsExpressionAtomContext existsExpressionAtomContext) {

    }

    @Override
    public void exitExistsExpressionAtom(MySqlParser.ExistsExpressionAtomContext existsExpressionAtomContext) {

    }

    @Override
    public void enterIntervalExpressionAtom(MySqlParser.IntervalExpressionAtomContext intervalExpressionAtomContext) {

    }

    @Override
    public void exitIntervalExpressionAtom(MySqlParser.IntervalExpressionAtomContext intervalExpressionAtomContext) {

    }

    @Override
    public void enterJsonExpressionAtom(MySqlParser.JsonExpressionAtomContext jsonExpressionAtomContext) {

    }

    @Override
    public void exitJsonExpressionAtom(MySqlParser.JsonExpressionAtomContext jsonExpressionAtomContext) {

    }

    @Override
    public void enterSubqueryExpressionAtom(MySqlParser.SubqueryExpressionAtomContext subqueryExpressionAtomContext) {

    }

    @Override
    public void exitSubqueryExpressionAtom(MySqlParser.SubqueryExpressionAtomContext subqueryExpressionAtomContext) {

    }

    @Override
    public void enterConstantExpressionAtom(MySqlParser.ConstantExpressionAtomContext constantExpressionAtomContext) {

    }

    @Override
    public void exitConstantExpressionAtom(MySqlParser.ConstantExpressionAtomContext constantExpressionAtomContext) {

    }

    @Override
    public void enterFunctionCallExpressionAtom(MySqlParser.FunctionCallExpressionAtomContext functionCallExpressionAtomContext) {

    }

    @Override
    public void exitFunctionCallExpressionAtom(MySqlParser.FunctionCallExpressionAtomContext functionCallExpressionAtomContext) {

    }

    @Override
    public void enterBinaryExpressionAtom(MySqlParser.BinaryExpressionAtomContext binaryExpressionAtomContext) {

    }

    @Override
    public void exitBinaryExpressionAtom(MySqlParser.BinaryExpressionAtomContext binaryExpressionAtomContext) {

    }

    @Override
    public void enterFullColumnNameExpressionAtom(MySqlParser.FullColumnNameExpressionAtomContext fullColumnNameExpressionAtomContext) {

    }

    @Override
    public void exitFullColumnNameExpressionAtom(MySqlParser.FullColumnNameExpressionAtomContext fullColumnNameExpressionAtomContext) {

    }

    @Override
    public void enterBitExpressionAtom(MySqlParser.BitExpressionAtomContext bitExpressionAtomContext) {

    }

    @Override
    public void exitBitExpressionAtom(MySqlParser.BitExpressionAtomContext bitExpressionAtomContext) {

    }

    @Override
    public void enterUnaryOperator(MySqlParser.UnaryOperatorContext unaryOperatorContext) {

    }

    @Override
    public void exitUnaryOperator(MySqlParser.UnaryOperatorContext unaryOperatorContext) {

    }

    @Override
    public void enterComparisonOperator(MySqlParser.ComparisonOperatorContext comparisonOperatorContext) {

    }

    @Override
    public void exitComparisonOperator(MySqlParser.ComparisonOperatorContext comparisonOperatorContext) {

    }

    @Override
    public void enterLogicalOperator(MySqlParser.LogicalOperatorContext logicalOperatorContext) {

    }

    @Override
    public void exitLogicalOperator(MySqlParser.LogicalOperatorContext logicalOperatorContext) {

    }

    @Override
    public void enterBitOperator(MySqlParser.BitOperatorContext bitOperatorContext) {

    }

    @Override
    public void exitBitOperator(MySqlParser.BitOperatorContext bitOperatorContext) {

    }

    @Override
    public void enterMathOperator(MySqlParser.MathOperatorContext mathOperatorContext) {

    }

    @Override
    public void exitMathOperator(MySqlParser.MathOperatorContext mathOperatorContext) {

    }

    @Override
    public void enterJsonOperator(MySqlParser.JsonOperatorContext jsonOperatorContext) {

    }

    @Override
    public void exitJsonOperator(MySqlParser.JsonOperatorContext jsonOperatorContext) {

    }

    @Override
    public void enterCharsetNameBase(MySqlParser.CharsetNameBaseContext charsetNameBaseContext) {

    }

    @Override
    public void exitCharsetNameBase(MySqlParser.CharsetNameBaseContext charsetNameBaseContext) {

    }

    @Override
    public void enterTransactionLevelBase(MySqlParser.TransactionLevelBaseContext transactionLevelBaseContext) {

    }

    @Override
    public void exitTransactionLevelBase(MySqlParser.TransactionLevelBaseContext transactionLevelBaseContext) {

    }

    @Override
    public void enterPrivilegesBase(MySqlParser.PrivilegesBaseContext privilegesBaseContext) {

    }

    @Override
    public void exitPrivilegesBase(MySqlParser.PrivilegesBaseContext privilegesBaseContext) {

    }

    @Override
    public void enterIntervalTypeBase(MySqlParser.IntervalTypeBaseContext intervalTypeBaseContext) {

    }

    @Override
    public void exitIntervalTypeBase(MySqlParser.IntervalTypeBaseContext intervalTypeBaseContext) {

    }

    @Override
    public void enterDataTypeBase(MySqlParser.DataTypeBaseContext dataTypeBaseContext) {

    }

    @Override
    public void exitDataTypeBase(MySqlParser.DataTypeBaseContext dataTypeBaseContext) {

    }

    @Override
    public void enterKeywordsCanBeId(MySqlParser.KeywordsCanBeIdContext keywordsCanBeIdContext) {

    }

    @Override
    public void exitKeywordsCanBeId(MySqlParser.KeywordsCanBeIdContext keywordsCanBeIdContext) {

    }

    @Override
    public void enterFunctionNameBase(MySqlParser.FunctionNameBaseContext functionNameBaseContext) {

    }

    @Override
    public void exitFunctionNameBase(MySqlParser.FunctionNameBaseContext functionNameBaseContext) {

    }

    @Override
    public void visitTerminal(TerminalNode terminalNode) {

    }

    @Override
    public void visitErrorNode(ErrorNode errorNode) {

    }

    @Override
    public void enterEveryRule(ParserRuleContext parserRuleContext) {

    }

    @Override
    public void exitEveryRule(ParserRuleContext parserRuleContext) {

    }
}