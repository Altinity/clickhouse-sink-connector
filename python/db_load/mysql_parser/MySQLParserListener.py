# Generated from MySQLParser.g4 by ANTLR 4.11.1
from antlr4 import *
if __name__ is not None and "." in __name__:
    from .MySQLParser import MySQLParser
else:
    from MySQLParser import MySQLParser
#/*
# * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
# *
# * This program is free software; you can redistribute it and/or modify
# * it under the terms of the GNU General Public License, version 2.0,
# * as published by the Free Software Foundation.
# *
# * This program is also distributed with certain software (including
# * but not limited to OpenSSL) that is licensed under separate terms, as
# * designated in a particular file or component or in included license
# * documentation. The authors of MySQL hereby grant you an additional
# * permission to link the program and your derivative works with the
# * separately licensed software that they have included with MySQL.
# * This program is distributed in the hope that it will be useful, but
# * WITHOUT ANY WARRANTY; without even the implied warranty of
# * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
# * the GNU General Public License, version 2.0, for more details.
# *
# * You should have received a copy of the GNU General Public License
# * along with this program; if not, write to the Free Software Foundation, Inc.,
# * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA
# */


# This class defines a complete listener for a parse tree produced by MySQLParser.
class MySQLParserListener(ParseTreeListener):

    # Enter a parse tree produced by MySQLParser#query.
    def enterQuery(self, ctx:MySQLParser.QueryContext):
        pass

    # Exit a parse tree produced by MySQLParser#query.
    def exitQuery(self, ctx:MySQLParser.QueryContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleStatement.
    def enterSimpleStatement(self, ctx:MySQLParser.SimpleStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleStatement.
    def exitSimpleStatement(self, ctx:MySQLParser.SimpleStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterStatement.
    def enterAlterStatement(self, ctx:MySQLParser.AlterStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterStatement.
    def exitAlterStatement(self, ctx:MySQLParser.AlterStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterDatabase.
    def enterAlterDatabase(self, ctx:MySQLParser.AlterDatabaseContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterDatabase.
    def exitAlterDatabase(self, ctx:MySQLParser.AlterDatabaseContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterEvent.
    def enterAlterEvent(self, ctx:MySQLParser.AlterEventContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterEvent.
    def exitAlterEvent(self, ctx:MySQLParser.AlterEventContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterLogfileGroup.
    def enterAlterLogfileGroup(self, ctx:MySQLParser.AlterLogfileGroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterLogfileGroup.
    def exitAlterLogfileGroup(self, ctx:MySQLParser.AlterLogfileGroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterLogfileGroupOptions.
    def enterAlterLogfileGroupOptions(self, ctx:MySQLParser.AlterLogfileGroupOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterLogfileGroupOptions.
    def exitAlterLogfileGroupOptions(self, ctx:MySQLParser.AlterLogfileGroupOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterLogfileGroupOption.
    def enterAlterLogfileGroupOption(self, ctx:MySQLParser.AlterLogfileGroupOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterLogfileGroupOption.
    def exitAlterLogfileGroupOption(self, ctx:MySQLParser.AlterLogfileGroupOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterServer.
    def enterAlterServer(self, ctx:MySQLParser.AlterServerContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterServer.
    def exitAlterServer(self, ctx:MySQLParser.AlterServerContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterTable.
    def enterAlterTable(self, ctx:MySQLParser.AlterTableContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterTable.
    def exitAlterTable(self, ctx:MySQLParser.AlterTableContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterTableActions.
    def enterAlterTableActions(self, ctx:MySQLParser.AlterTableActionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterTableActions.
    def exitAlterTableActions(self, ctx:MySQLParser.AlterTableActionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterCommandList.
    def enterAlterCommandList(self, ctx:MySQLParser.AlterCommandListContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterCommandList.
    def exitAlterCommandList(self, ctx:MySQLParser.AlterCommandListContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterCommandsModifierList.
    def enterAlterCommandsModifierList(self, ctx:MySQLParser.AlterCommandsModifierListContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterCommandsModifierList.
    def exitAlterCommandsModifierList(self, ctx:MySQLParser.AlterCommandsModifierListContext):
        pass


    # Enter a parse tree produced by MySQLParser#standaloneAlterCommands.
    def enterStandaloneAlterCommands(self, ctx:MySQLParser.StandaloneAlterCommandsContext):
        pass

    # Exit a parse tree produced by MySQLParser#standaloneAlterCommands.
    def exitStandaloneAlterCommands(self, ctx:MySQLParser.StandaloneAlterCommandsContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterPartition.
    def enterAlterPartition(self, ctx:MySQLParser.AlterPartitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterPartition.
    def exitAlterPartition(self, ctx:MySQLParser.AlterPartitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterList.
    def enterAlterList(self, ctx:MySQLParser.AlterListContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterList.
    def exitAlterList(self, ctx:MySQLParser.AlterListContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterCommandsModifier.
    def enterAlterCommandsModifier(self, ctx:MySQLParser.AlterCommandsModifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterCommandsModifier.
    def exitAlterCommandsModifier(self, ctx:MySQLParser.AlterCommandsModifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterListItem.
    def enterAlterListItem(self, ctx:MySQLParser.AlterListItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterListItem.
    def exitAlterListItem(self, ctx:MySQLParser.AlterListItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#place.
    def enterPlace(self, ctx:MySQLParser.PlaceContext):
        pass

    # Exit a parse tree produced by MySQLParser#place.
    def exitPlace(self, ctx:MySQLParser.PlaceContext):
        pass


    # Enter a parse tree produced by MySQLParser#restrict.
    def enterRestrict(self, ctx:MySQLParser.RestrictContext):
        pass

    # Exit a parse tree produced by MySQLParser#restrict.
    def exitRestrict(self, ctx:MySQLParser.RestrictContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterOrderList.
    def enterAlterOrderList(self, ctx:MySQLParser.AlterOrderListContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterOrderList.
    def exitAlterOrderList(self, ctx:MySQLParser.AlterOrderListContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterAlgorithmOption.
    def enterAlterAlgorithmOption(self, ctx:MySQLParser.AlterAlgorithmOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterAlgorithmOption.
    def exitAlterAlgorithmOption(self, ctx:MySQLParser.AlterAlgorithmOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterLockOption.
    def enterAlterLockOption(self, ctx:MySQLParser.AlterLockOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterLockOption.
    def exitAlterLockOption(self, ctx:MySQLParser.AlterLockOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexLockAndAlgorithm.
    def enterIndexLockAndAlgorithm(self, ctx:MySQLParser.IndexLockAndAlgorithmContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexLockAndAlgorithm.
    def exitIndexLockAndAlgorithm(self, ctx:MySQLParser.IndexLockAndAlgorithmContext):
        pass


    # Enter a parse tree produced by MySQLParser#withValidation.
    def enterWithValidation(self, ctx:MySQLParser.WithValidationContext):
        pass

    # Exit a parse tree produced by MySQLParser#withValidation.
    def exitWithValidation(self, ctx:MySQLParser.WithValidationContext):
        pass


    # Enter a parse tree produced by MySQLParser#removePartitioning.
    def enterRemovePartitioning(self, ctx:MySQLParser.RemovePartitioningContext):
        pass

    # Exit a parse tree produced by MySQLParser#removePartitioning.
    def exitRemovePartitioning(self, ctx:MySQLParser.RemovePartitioningContext):
        pass


    # Enter a parse tree produced by MySQLParser#allOrPartitionNameList.
    def enterAllOrPartitionNameList(self, ctx:MySQLParser.AllOrPartitionNameListContext):
        pass

    # Exit a parse tree produced by MySQLParser#allOrPartitionNameList.
    def exitAllOrPartitionNameList(self, ctx:MySQLParser.AllOrPartitionNameListContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterTablespace.
    def enterAlterTablespace(self, ctx:MySQLParser.AlterTablespaceContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterTablespace.
    def exitAlterTablespace(self, ctx:MySQLParser.AlterTablespaceContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterUndoTablespace.
    def enterAlterUndoTablespace(self, ctx:MySQLParser.AlterUndoTablespaceContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterUndoTablespace.
    def exitAlterUndoTablespace(self, ctx:MySQLParser.AlterUndoTablespaceContext):
        pass


    # Enter a parse tree produced by MySQLParser#undoTableSpaceOptions.
    def enterUndoTableSpaceOptions(self, ctx:MySQLParser.UndoTableSpaceOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#undoTableSpaceOptions.
    def exitUndoTableSpaceOptions(self, ctx:MySQLParser.UndoTableSpaceOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#undoTableSpaceOption.
    def enterUndoTableSpaceOption(self, ctx:MySQLParser.UndoTableSpaceOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#undoTableSpaceOption.
    def exitUndoTableSpaceOption(self, ctx:MySQLParser.UndoTableSpaceOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterTablespaceOptions.
    def enterAlterTablespaceOptions(self, ctx:MySQLParser.AlterTablespaceOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterTablespaceOptions.
    def exitAlterTablespaceOptions(self, ctx:MySQLParser.AlterTablespaceOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterTablespaceOption.
    def enterAlterTablespaceOption(self, ctx:MySQLParser.AlterTablespaceOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterTablespaceOption.
    def exitAlterTablespaceOption(self, ctx:MySQLParser.AlterTablespaceOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#changeTablespaceOption.
    def enterChangeTablespaceOption(self, ctx:MySQLParser.ChangeTablespaceOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#changeTablespaceOption.
    def exitChangeTablespaceOption(self, ctx:MySQLParser.ChangeTablespaceOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterView.
    def enterAlterView(self, ctx:MySQLParser.AlterViewContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterView.
    def exitAlterView(self, ctx:MySQLParser.AlterViewContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewTail.
    def enterViewTail(self, ctx:MySQLParser.ViewTailContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewTail.
    def exitViewTail(self, ctx:MySQLParser.ViewTailContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewSelect.
    def enterViewSelect(self, ctx:MySQLParser.ViewSelectContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewSelect.
    def exitViewSelect(self, ctx:MySQLParser.ViewSelectContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewCheckOption.
    def enterViewCheckOption(self, ctx:MySQLParser.ViewCheckOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewCheckOption.
    def exitViewCheckOption(self, ctx:MySQLParser.ViewCheckOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createStatement.
    def enterCreateStatement(self, ctx:MySQLParser.CreateStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#createStatement.
    def exitCreateStatement(self, ctx:MySQLParser.CreateStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#createDatabase.
    def enterCreateDatabase(self, ctx:MySQLParser.CreateDatabaseContext):
        pass

    # Exit a parse tree produced by MySQLParser#createDatabase.
    def exitCreateDatabase(self, ctx:MySQLParser.CreateDatabaseContext):
        pass


    # Enter a parse tree produced by MySQLParser#createDatabaseOption.
    def enterCreateDatabaseOption(self, ctx:MySQLParser.CreateDatabaseOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#createDatabaseOption.
    def exitCreateDatabaseOption(self, ctx:MySQLParser.CreateDatabaseOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createTable.
    def enterCreateTable(self, ctx:MySQLParser.CreateTableContext):
        pass

    # Exit a parse tree produced by MySQLParser#createTable.
    def exitCreateTable(self, ctx:MySQLParser.CreateTableContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableElementList.
    def enterTableElementList(self, ctx:MySQLParser.TableElementListContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableElementList.
    def exitTableElementList(self, ctx:MySQLParser.TableElementListContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableElement.
    def enterTableElement(self, ctx:MySQLParser.TableElementContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableElement.
    def exitTableElement(self, ctx:MySQLParser.TableElementContext):
        pass


    # Enter a parse tree produced by MySQLParser#duplicateAsQueryExpression.
    def enterDuplicateAsQueryExpression(self, ctx:MySQLParser.DuplicateAsQueryExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#duplicateAsQueryExpression.
    def exitDuplicateAsQueryExpression(self, ctx:MySQLParser.DuplicateAsQueryExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#queryExpressionOrParens.
    def enterQueryExpressionOrParens(self, ctx:MySQLParser.QueryExpressionOrParensContext):
        pass

    # Exit a parse tree produced by MySQLParser#queryExpressionOrParens.
    def exitQueryExpressionOrParens(self, ctx:MySQLParser.QueryExpressionOrParensContext):
        pass


    # Enter a parse tree produced by MySQLParser#createRoutine.
    def enterCreateRoutine(self, ctx:MySQLParser.CreateRoutineContext):
        pass

    # Exit a parse tree produced by MySQLParser#createRoutine.
    def exitCreateRoutine(self, ctx:MySQLParser.CreateRoutineContext):
        pass


    # Enter a parse tree produced by MySQLParser#createProcedure.
    def enterCreateProcedure(self, ctx:MySQLParser.CreateProcedureContext):
        pass

    # Exit a parse tree produced by MySQLParser#createProcedure.
    def exitCreateProcedure(self, ctx:MySQLParser.CreateProcedureContext):
        pass


    # Enter a parse tree produced by MySQLParser#createFunction.
    def enterCreateFunction(self, ctx:MySQLParser.CreateFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#createFunction.
    def exitCreateFunction(self, ctx:MySQLParser.CreateFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createUdf.
    def enterCreateUdf(self, ctx:MySQLParser.CreateUdfContext):
        pass

    # Exit a parse tree produced by MySQLParser#createUdf.
    def exitCreateUdf(self, ctx:MySQLParser.CreateUdfContext):
        pass


    # Enter a parse tree produced by MySQLParser#routineCreateOption.
    def enterRoutineCreateOption(self, ctx:MySQLParser.RoutineCreateOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#routineCreateOption.
    def exitRoutineCreateOption(self, ctx:MySQLParser.RoutineCreateOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#routineAlterOptions.
    def enterRoutineAlterOptions(self, ctx:MySQLParser.RoutineAlterOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#routineAlterOptions.
    def exitRoutineAlterOptions(self, ctx:MySQLParser.RoutineAlterOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#routineOption.
    def enterRoutineOption(self, ctx:MySQLParser.RoutineOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#routineOption.
    def exitRoutineOption(self, ctx:MySQLParser.RoutineOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createIndex.
    def enterCreateIndex(self, ctx:MySQLParser.CreateIndexContext):
        pass

    # Exit a parse tree produced by MySQLParser#createIndex.
    def exitCreateIndex(self, ctx:MySQLParser.CreateIndexContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexNameAndType.
    def enterIndexNameAndType(self, ctx:MySQLParser.IndexNameAndTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexNameAndType.
    def exitIndexNameAndType(self, ctx:MySQLParser.IndexNameAndTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#createIndexTarget.
    def enterCreateIndexTarget(self, ctx:MySQLParser.CreateIndexTargetContext):
        pass

    # Exit a parse tree produced by MySQLParser#createIndexTarget.
    def exitCreateIndexTarget(self, ctx:MySQLParser.CreateIndexTargetContext):
        pass


    # Enter a parse tree produced by MySQLParser#createLogfileGroup.
    def enterCreateLogfileGroup(self, ctx:MySQLParser.CreateLogfileGroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#createLogfileGroup.
    def exitCreateLogfileGroup(self, ctx:MySQLParser.CreateLogfileGroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#logfileGroupOptions.
    def enterLogfileGroupOptions(self, ctx:MySQLParser.LogfileGroupOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#logfileGroupOptions.
    def exitLogfileGroupOptions(self, ctx:MySQLParser.LogfileGroupOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#logfileGroupOption.
    def enterLogfileGroupOption(self, ctx:MySQLParser.LogfileGroupOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#logfileGroupOption.
    def exitLogfileGroupOption(self, ctx:MySQLParser.LogfileGroupOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createServer.
    def enterCreateServer(self, ctx:MySQLParser.CreateServerContext):
        pass

    # Exit a parse tree produced by MySQLParser#createServer.
    def exitCreateServer(self, ctx:MySQLParser.CreateServerContext):
        pass


    # Enter a parse tree produced by MySQLParser#serverOptions.
    def enterServerOptions(self, ctx:MySQLParser.ServerOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#serverOptions.
    def exitServerOptions(self, ctx:MySQLParser.ServerOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#serverOption.
    def enterServerOption(self, ctx:MySQLParser.ServerOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#serverOption.
    def exitServerOption(self, ctx:MySQLParser.ServerOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createTablespace.
    def enterCreateTablespace(self, ctx:MySQLParser.CreateTablespaceContext):
        pass

    # Exit a parse tree produced by MySQLParser#createTablespace.
    def exitCreateTablespace(self, ctx:MySQLParser.CreateTablespaceContext):
        pass


    # Enter a parse tree produced by MySQLParser#createUndoTablespace.
    def enterCreateUndoTablespace(self, ctx:MySQLParser.CreateUndoTablespaceContext):
        pass

    # Exit a parse tree produced by MySQLParser#createUndoTablespace.
    def exitCreateUndoTablespace(self, ctx:MySQLParser.CreateUndoTablespaceContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsDataFileName.
    def enterTsDataFileName(self, ctx:MySQLParser.TsDataFileNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsDataFileName.
    def exitTsDataFileName(self, ctx:MySQLParser.TsDataFileNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsDataFile.
    def enterTsDataFile(self, ctx:MySQLParser.TsDataFileContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsDataFile.
    def exitTsDataFile(self, ctx:MySQLParser.TsDataFileContext):
        pass


    # Enter a parse tree produced by MySQLParser#tablespaceOptions.
    def enterTablespaceOptions(self, ctx:MySQLParser.TablespaceOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#tablespaceOptions.
    def exitTablespaceOptions(self, ctx:MySQLParser.TablespaceOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#tablespaceOption.
    def enterTablespaceOption(self, ctx:MySQLParser.TablespaceOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#tablespaceOption.
    def exitTablespaceOption(self, ctx:MySQLParser.TablespaceOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionInitialSize.
    def enterTsOptionInitialSize(self, ctx:MySQLParser.TsOptionInitialSizeContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionInitialSize.
    def exitTsOptionInitialSize(self, ctx:MySQLParser.TsOptionInitialSizeContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionUndoRedoBufferSize.
    def enterTsOptionUndoRedoBufferSize(self, ctx:MySQLParser.TsOptionUndoRedoBufferSizeContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionUndoRedoBufferSize.
    def exitTsOptionUndoRedoBufferSize(self, ctx:MySQLParser.TsOptionUndoRedoBufferSizeContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionAutoextendSize.
    def enterTsOptionAutoextendSize(self, ctx:MySQLParser.TsOptionAutoextendSizeContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionAutoextendSize.
    def exitTsOptionAutoextendSize(self, ctx:MySQLParser.TsOptionAutoextendSizeContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionMaxSize.
    def enterTsOptionMaxSize(self, ctx:MySQLParser.TsOptionMaxSizeContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionMaxSize.
    def exitTsOptionMaxSize(self, ctx:MySQLParser.TsOptionMaxSizeContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionExtentSize.
    def enterTsOptionExtentSize(self, ctx:MySQLParser.TsOptionExtentSizeContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionExtentSize.
    def exitTsOptionExtentSize(self, ctx:MySQLParser.TsOptionExtentSizeContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionNodegroup.
    def enterTsOptionNodegroup(self, ctx:MySQLParser.TsOptionNodegroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionNodegroup.
    def exitTsOptionNodegroup(self, ctx:MySQLParser.TsOptionNodegroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionEngine.
    def enterTsOptionEngine(self, ctx:MySQLParser.TsOptionEngineContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionEngine.
    def exitTsOptionEngine(self, ctx:MySQLParser.TsOptionEngineContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionWait.
    def enterTsOptionWait(self, ctx:MySQLParser.TsOptionWaitContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionWait.
    def exitTsOptionWait(self, ctx:MySQLParser.TsOptionWaitContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionComment.
    def enterTsOptionComment(self, ctx:MySQLParser.TsOptionCommentContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionComment.
    def exitTsOptionComment(self, ctx:MySQLParser.TsOptionCommentContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionFileblockSize.
    def enterTsOptionFileblockSize(self, ctx:MySQLParser.TsOptionFileblockSizeContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionFileblockSize.
    def exitTsOptionFileblockSize(self, ctx:MySQLParser.TsOptionFileblockSizeContext):
        pass


    # Enter a parse tree produced by MySQLParser#tsOptionEncryption.
    def enterTsOptionEncryption(self, ctx:MySQLParser.TsOptionEncryptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#tsOptionEncryption.
    def exitTsOptionEncryption(self, ctx:MySQLParser.TsOptionEncryptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createView.
    def enterCreateView(self, ctx:MySQLParser.CreateViewContext):
        pass

    # Exit a parse tree produced by MySQLParser#createView.
    def exitCreateView(self, ctx:MySQLParser.CreateViewContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewReplaceOrAlgorithm.
    def enterViewReplaceOrAlgorithm(self, ctx:MySQLParser.ViewReplaceOrAlgorithmContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewReplaceOrAlgorithm.
    def exitViewReplaceOrAlgorithm(self, ctx:MySQLParser.ViewReplaceOrAlgorithmContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewAlgorithm.
    def enterViewAlgorithm(self, ctx:MySQLParser.ViewAlgorithmContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewAlgorithm.
    def exitViewAlgorithm(self, ctx:MySQLParser.ViewAlgorithmContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewSuid.
    def enterViewSuid(self, ctx:MySQLParser.ViewSuidContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewSuid.
    def exitViewSuid(self, ctx:MySQLParser.ViewSuidContext):
        pass


    # Enter a parse tree produced by MySQLParser#createTrigger.
    def enterCreateTrigger(self, ctx:MySQLParser.CreateTriggerContext):
        pass

    # Exit a parse tree produced by MySQLParser#createTrigger.
    def exitCreateTrigger(self, ctx:MySQLParser.CreateTriggerContext):
        pass


    # Enter a parse tree produced by MySQLParser#triggerFollowsPrecedesClause.
    def enterTriggerFollowsPrecedesClause(self, ctx:MySQLParser.TriggerFollowsPrecedesClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#triggerFollowsPrecedesClause.
    def exitTriggerFollowsPrecedesClause(self, ctx:MySQLParser.TriggerFollowsPrecedesClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#createEvent.
    def enterCreateEvent(self, ctx:MySQLParser.CreateEventContext):
        pass

    # Exit a parse tree produced by MySQLParser#createEvent.
    def exitCreateEvent(self, ctx:MySQLParser.CreateEventContext):
        pass


    # Enter a parse tree produced by MySQLParser#createRole.
    def enterCreateRole(self, ctx:MySQLParser.CreateRoleContext):
        pass

    # Exit a parse tree produced by MySQLParser#createRole.
    def exitCreateRole(self, ctx:MySQLParser.CreateRoleContext):
        pass


    # Enter a parse tree produced by MySQLParser#createSpatialReference.
    def enterCreateSpatialReference(self, ctx:MySQLParser.CreateSpatialReferenceContext):
        pass

    # Exit a parse tree produced by MySQLParser#createSpatialReference.
    def exitCreateSpatialReference(self, ctx:MySQLParser.CreateSpatialReferenceContext):
        pass


    # Enter a parse tree produced by MySQLParser#srsAttribute.
    def enterSrsAttribute(self, ctx:MySQLParser.SrsAttributeContext):
        pass

    # Exit a parse tree produced by MySQLParser#srsAttribute.
    def exitSrsAttribute(self, ctx:MySQLParser.SrsAttributeContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropStatement.
    def enterDropStatement(self, ctx:MySQLParser.DropStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropStatement.
    def exitDropStatement(self, ctx:MySQLParser.DropStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropDatabase.
    def enterDropDatabase(self, ctx:MySQLParser.DropDatabaseContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropDatabase.
    def exitDropDatabase(self, ctx:MySQLParser.DropDatabaseContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropEvent.
    def enterDropEvent(self, ctx:MySQLParser.DropEventContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropEvent.
    def exitDropEvent(self, ctx:MySQLParser.DropEventContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropFunction.
    def enterDropFunction(self, ctx:MySQLParser.DropFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropFunction.
    def exitDropFunction(self, ctx:MySQLParser.DropFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropProcedure.
    def enterDropProcedure(self, ctx:MySQLParser.DropProcedureContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropProcedure.
    def exitDropProcedure(self, ctx:MySQLParser.DropProcedureContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropIndex.
    def enterDropIndex(self, ctx:MySQLParser.DropIndexContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropIndex.
    def exitDropIndex(self, ctx:MySQLParser.DropIndexContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropLogfileGroup.
    def enterDropLogfileGroup(self, ctx:MySQLParser.DropLogfileGroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropLogfileGroup.
    def exitDropLogfileGroup(self, ctx:MySQLParser.DropLogfileGroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropLogfileGroupOption.
    def enterDropLogfileGroupOption(self, ctx:MySQLParser.DropLogfileGroupOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropLogfileGroupOption.
    def exitDropLogfileGroupOption(self, ctx:MySQLParser.DropLogfileGroupOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropServer.
    def enterDropServer(self, ctx:MySQLParser.DropServerContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropServer.
    def exitDropServer(self, ctx:MySQLParser.DropServerContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropTable.
    def enterDropTable(self, ctx:MySQLParser.DropTableContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropTable.
    def exitDropTable(self, ctx:MySQLParser.DropTableContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropTableSpace.
    def enterDropTableSpace(self, ctx:MySQLParser.DropTableSpaceContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropTableSpace.
    def exitDropTableSpace(self, ctx:MySQLParser.DropTableSpaceContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropTrigger.
    def enterDropTrigger(self, ctx:MySQLParser.DropTriggerContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropTrigger.
    def exitDropTrigger(self, ctx:MySQLParser.DropTriggerContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropView.
    def enterDropView(self, ctx:MySQLParser.DropViewContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropView.
    def exitDropView(self, ctx:MySQLParser.DropViewContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropRole.
    def enterDropRole(self, ctx:MySQLParser.DropRoleContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropRole.
    def exitDropRole(self, ctx:MySQLParser.DropRoleContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropSpatialReference.
    def enterDropSpatialReference(self, ctx:MySQLParser.DropSpatialReferenceContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropSpatialReference.
    def exitDropSpatialReference(self, ctx:MySQLParser.DropSpatialReferenceContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropUndoTablespace.
    def enterDropUndoTablespace(self, ctx:MySQLParser.DropUndoTablespaceContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropUndoTablespace.
    def exitDropUndoTablespace(self, ctx:MySQLParser.DropUndoTablespaceContext):
        pass


    # Enter a parse tree produced by MySQLParser#renameTableStatement.
    def enterRenameTableStatement(self, ctx:MySQLParser.RenameTableStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#renameTableStatement.
    def exitRenameTableStatement(self, ctx:MySQLParser.RenameTableStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#renamePair.
    def enterRenamePair(self, ctx:MySQLParser.RenamePairContext):
        pass

    # Exit a parse tree produced by MySQLParser#renamePair.
    def exitRenamePair(self, ctx:MySQLParser.RenamePairContext):
        pass


    # Enter a parse tree produced by MySQLParser#truncateTableStatement.
    def enterTruncateTableStatement(self, ctx:MySQLParser.TruncateTableStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#truncateTableStatement.
    def exitTruncateTableStatement(self, ctx:MySQLParser.TruncateTableStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#importStatement.
    def enterImportStatement(self, ctx:MySQLParser.ImportStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#importStatement.
    def exitImportStatement(self, ctx:MySQLParser.ImportStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#callStatement.
    def enterCallStatement(self, ctx:MySQLParser.CallStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#callStatement.
    def exitCallStatement(self, ctx:MySQLParser.CallStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#deleteStatement.
    def enterDeleteStatement(self, ctx:MySQLParser.DeleteStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#deleteStatement.
    def exitDeleteStatement(self, ctx:MySQLParser.DeleteStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionDelete.
    def enterPartitionDelete(self, ctx:MySQLParser.PartitionDeleteContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionDelete.
    def exitPartitionDelete(self, ctx:MySQLParser.PartitionDeleteContext):
        pass


    # Enter a parse tree produced by MySQLParser#deleteStatementOption.
    def enterDeleteStatementOption(self, ctx:MySQLParser.DeleteStatementOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#deleteStatementOption.
    def exitDeleteStatementOption(self, ctx:MySQLParser.DeleteStatementOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#doStatement.
    def enterDoStatement(self, ctx:MySQLParser.DoStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#doStatement.
    def exitDoStatement(self, ctx:MySQLParser.DoStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#handlerStatement.
    def enterHandlerStatement(self, ctx:MySQLParser.HandlerStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#handlerStatement.
    def exitHandlerStatement(self, ctx:MySQLParser.HandlerStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#handlerReadOrScan.
    def enterHandlerReadOrScan(self, ctx:MySQLParser.HandlerReadOrScanContext):
        pass

    # Exit a parse tree produced by MySQLParser#handlerReadOrScan.
    def exitHandlerReadOrScan(self, ctx:MySQLParser.HandlerReadOrScanContext):
        pass


    # Enter a parse tree produced by MySQLParser#insertStatement.
    def enterInsertStatement(self, ctx:MySQLParser.InsertStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#insertStatement.
    def exitInsertStatement(self, ctx:MySQLParser.InsertStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#insertLockOption.
    def enterInsertLockOption(self, ctx:MySQLParser.InsertLockOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#insertLockOption.
    def exitInsertLockOption(self, ctx:MySQLParser.InsertLockOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#insertFromConstructor.
    def enterInsertFromConstructor(self, ctx:MySQLParser.InsertFromConstructorContext):
        pass

    # Exit a parse tree produced by MySQLParser#insertFromConstructor.
    def exitInsertFromConstructor(self, ctx:MySQLParser.InsertFromConstructorContext):
        pass


    # Enter a parse tree produced by MySQLParser#fields.
    def enterFields(self, ctx:MySQLParser.FieldsContext):
        pass

    # Exit a parse tree produced by MySQLParser#fields.
    def exitFields(self, ctx:MySQLParser.FieldsContext):
        pass


    # Enter a parse tree produced by MySQLParser#insertValues.
    def enterInsertValues(self, ctx:MySQLParser.InsertValuesContext):
        pass

    # Exit a parse tree produced by MySQLParser#insertValues.
    def exitInsertValues(self, ctx:MySQLParser.InsertValuesContext):
        pass


    # Enter a parse tree produced by MySQLParser#insertQueryExpression.
    def enterInsertQueryExpression(self, ctx:MySQLParser.InsertQueryExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#insertQueryExpression.
    def exitInsertQueryExpression(self, ctx:MySQLParser.InsertQueryExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#valueList.
    def enterValueList(self, ctx:MySQLParser.ValueListContext):
        pass

    # Exit a parse tree produced by MySQLParser#valueList.
    def exitValueList(self, ctx:MySQLParser.ValueListContext):
        pass


    # Enter a parse tree produced by MySQLParser#values.
    def enterValues(self, ctx:MySQLParser.ValuesContext):
        pass

    # Exit a parse tree produced by MySQLParser#values.
    def exitValues(self, ctx:MySQLParser.ValuesContext):
        pass


    # Enter a parse tree produced by MySQLParser#valuesReference.
    def enterValuesReference(self, ctx:MySQLParser.ValuesReferenceContext):
        pass

    # Exit a parse tree produced by MySQLParser#valuesReference.
    def exitValuesReference(self, ctx:MySQLParser.ValuesReferenceContext):
        pass


    # Enter a parse tree produced by MySQLParser#insertUpdateList.
    def enterInsertUpdateList(self, ctx:MySQLParser.InsertUpdateListContext):
        pass

    # Exit a parse tree produced by MySQLParser#insertUpdateList.
    def exitInsertUpdateList(self, ctx:MySQLParser.InsertUpdateListContext):
        pass


    # Enter a parse tree produced by MySQLParser#loadStatement.
    def enterLoadStatement(self, ctx:MySQLParser.LoadStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#loadStatement.
    def exitLoadStatement(self, ctx:MySQLParser.LoadStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#dataOrXml.
    def enterDataOrXml(self, ctx:MySQLParser.DataOrXmlContext):
        pass

    # Exit a parse tree produced by MySQLParser#dataOrXml.
    def exitDataOrXml(self, ctx:MySQLParser.DataOrXmlContext):
        pass


    # Enter a parse tree produced by MySQLParser#xmlRowsIdentifiedBy.
    def enterXmlRowsIdentifiedBy(self, ctx:MySQLParser.XmlRowsIdentifiedByContext):
        pass

    # Exit a parse tree produced by MySQLParser#xmlRowsIdentifiedBy.
    def exitXmlRowsIdentifiedBy(self, ctx:MySQLParser.XmlRowsIdentifiedByContext):
        pass


    # Enter a parse tree produced by MySQLParser#loadDataFileTail.
    def enterLoadDataFileTail(self, ctx:MySQLParser.LoadDataFileTailContext):
        pass

    # Exit a parse tree produced by MySQLParser#loadDataFileTail.
    def exitLoadDataFileTail(self, ctx:MySQLParser.LoadDataFileTailContext):
        pass


    # Enter a parse tree produced by MySQLParser#loadDataFileTargetList.
    def enterLoadDataFileTargetList(self, ctx:MySQLParser.LoadDataFileTargetListContext):
        pass

    # Exit a parse tree produced by MySQLParser#loadDataFileTargetList.
    def exitLoadDataFileTargetList(self, ctx:MySQLParser.LoadDataFileTargetListContext):
        pass


    # Enter a parse tree produced by MySQLParser#fieldOrVariableList.
    def enterFieldOrVariableList(self, ctx:MySQLParser.FieldOrVariableListContext):
        pass

    # Exit a parse tree produced by MySQLParser#fieldOrVariableList.
    def exitFieldOrVariableList(self, ctx:MySQLParser.FieldOrVariableListContext):
        pass


    # Enter a parse tree produced by MySQLParser#replaceStatement.
    def enterReplaceStatement(self, ctx:MySQLParser.ReplaceStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#replaceStatement.
    def exitReplaceStatement(self, ctx:MySQLParser.ReplaceStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#selectStatement.
    def enterSelectStatement(self, ctx:MySQLParser.SelectStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#selectStatement.
    def exitSelectStatement(self, ctx:MySQLParser.SelectStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#selectStatementWithInto.
    def enterSelectStatementWithInto(self, ctx:MySQLParser.SelectStatementWithIntoContext):
        pass

    # Exit a parse tree produced by MySQLParser#selectStatementWithInto.
    def exitSelectStatementWithInto(self, ctx:MySQLParser.SelectStatementWithIntoContext):
        pass


    # Enter a parse tree produced by MySQLParser#queryExpression.
    def enterQueryExpression(self, ctx:MySQLParser.QueryExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#queryExpression.
    def exitQueryExpression(self, ctx:MySQLParser.QueryExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#queryExpressionBody.
    def enterQueryExpressionBody(self, ctx:MySQLParser.QueryExpressionBodyContext):
        pass

    # Exit a parse tree produced by MySQLParser#queryExpressionBody.
    def exitQueryExpressionBody(self, ctx:MySQLParser.QueryExpressionBodyContext):
        pass


    # Enter a parse tree produced by MySQLParser#queryExpressionParens.
    def enterQueryExpressionParens(self, ctx:MySQLParser.QueryExpressionParensContext):
        pass

    # Exit a parse tree produced by MySQLParser#queryExpressionParens.
    def exitQueryExpressionParens(self, ctx:MySQLParser.QueryExpressionParensContext):
        pass


    # Enter a parse tree produced by MySQLParser#queryPrimary.
    def enterQueryPrimary(self, ctx:MySQLParser.QueryPrimaryContext):
        pass

    # Exit a parse tree produced by MySQLParser#queryPrimary.
    def exitQueryPrimary(self, ctx:MySQLParser.QueryPrimaryContext):
        pass


    # Enter a parse tree produced by MySQLParser#querySpecification.
    def enterQuerySpecification(self, ctx:MySQLParser.QuerySpecificationContext):
        pass

    # Exit a parse tree produced by MySQLParser#querySpecification.
    def exitQuerySpecification(self, ctx:MySQLParser.QuerySpecificationContext):
        pass


    # Enter a parse tree produced by MySQLParser#subquery.
    def enterSubquery(self, ctx:MySQLParser.SubqueryContext):
        pass

    # Exit a parse tree produced by MySQLParser#subquery.
    def exitSubquery(self, ctx:MySQLParser.SubqueryContext):
        pass


    # Enter a parse tree produced by MySQLParser#querySpecOption.
    def enterQuerySpecOption(self, ctx:MySQLParser.QuerySpecOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#querySpecOption.
    def exitQuerySpecOption(self, ctx:MySQLParser.QuerySpecOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#limitClause.
    def enterLimitClause(self, ctx:MySQLParser.LimitClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#limitClause.
    def exitLimitClause(self, ctx:MySQLParser.LimitClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleLimitClause.
    def enterSimpleLimitClause(self, ctx:MySQLParser.SimpleLimitClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleLimitClause.
    def exitSimpleLimitClause(self, ctx:MySQLParser.SimpleLimitClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#limitOptions.
    def enterLimitOptions(self, ctx:MySQLParser.LimitOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#limitOptions.
    def exitLimitOptions(self, ctx:MySQLParser.LimitOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#limitOption.
    def enterLimitOption(self, ctx:MySQLParser.LimitOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#limitOption.
    def exitLimitOption(self, ctx:MySQLParser.LimitOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#intoClause.
    def enterIntoClause(self, ctx:MySQLParser.IntoClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#intoClause.
    def exitIntoClause(self, ctx:MySQLParser.IntoClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#procedureAnalyseClause.
    def enterProcedureAnalyseClause(self, ctx:MySQLParser.ProcedureAnalyseClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#procedureAnalyseClause.
    def exitProcedureAnalyseClause(self, ctx:MySQLParser.ProcedureAnalyseClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#havingClause.
    def enterHavingClause(self, ctx:MySQLParser.HavingClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#havingClause.
    def exitHavingClause(self, ctx:MySQLParser.HavingClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowClause.
    def enterWindowClause(self, ctx:MySQLParser.WindowClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowClause.
    def exitWindowClause(self, ctx:MySQLParser.WindowClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowDefinition.
    def enterWindowDefinition(self, ctx:MySQLParser.WindowDefinitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowDefinition.
    def exitWindowDefinition(self, ctx:MySQLParser.WindowDefinitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowSpec.
    def enterWindowSpec(self, ctx:MySQLParser.WindowSpecContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowSpec.
    def exitWindowSpec(self, ctx:MySQLParser.WindowSpecContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowSpecDetails.
    def enterWindowSpecDetails(self, ctx:MySQLParser.WindowSpecDetailsContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowSpecDetails.
    def exitWindowSpecDetails(self, ctx:MySQLParser.WindowSpecDetailsContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFrameClause.
    def enterWindowFrameClause(self, ctx:MySQLParser.WindowFrameClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFrameClause.
    def exitWindowFrameClause(self, ctx:MySQLParser.WindowFrameClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFrameUnits.
    def enterWindowFrameUnits(self, ctx:MySQLParser.WindowFrameUnitsContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFrameUnits.
    def exitWindowFrameUnits(self, ctx:MySQLParser.WindowFrameUnitsContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFrameExtent.
    def enterWindowFrameExtent(self, ctx:MySQLParser.WindowFrameExtentContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFrameExtent.
    def exitWindowFrameExtent(self, ctx:MySQLParser.WindowFrameExtentContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFrameStart.
    def enterWindowFrameStart(self, ctx:MySQLParser.WindowFrameStartContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFrameStart.
    def exitWindowFrameStart(self, ctx:MySQLParser.WindowFrameStartContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFrameBetween.
    def enterWindowFrameBetween(self, ctx:MySQLParser.WindowFrameBetweenContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFrameBetween.
    def exitWindowFrameBetween(self, ctx:MySQLParser.WindowFrameBetweenContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFrameBound.
    def enterWindowFrameBound(self, ctx:MySQLParser.WindowFrameBoundContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFrameBound.
    def exitWindowFrameBound(self, ctx:MySQLParser.WindowFrameBoundContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFrameExclusion.
    def enterWindowFrameExclusion(self, ctx:MySQLParser.WindowFrameExclusionContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFrameExclusion.
    def exitWindowFrameExclusion(self, ctx:MySQLParser.WindowFrameExclusionContext):
        pass


    # Enter a parse tree produced by MySQLParser#withClause.
    def enterWithClause(self, ctx:MySQLParser.WithClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#withClause.
    def exitWithClause(self, ctx:MySQLParser.WithClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#commonTableExpression.
    def enterCommonTableExpression(self, ctx:MySQLParser.CommonTableExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#commonTableExpression.
    def exitCommonTableExpression(self, ctx:MySQLParser.CommonTableExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#groupByClause.
    def enterGroupByClause(self, ctx:MySQLParser.GroupByClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#groupByClause.
    def exitGroupByClause(self, ctx:MySQLParser.GroupByClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#olapOption.
    def enterOlapOption(self, ctx:MySQLParser.OlapOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#olapOption.
    def exitOlapOption(self, ctx:MySQLParser.OlapOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#orderClause.
    def enterOrderClause(self, ctx:MySQLParser.OrderClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#orderClause.
    def exitOrderClause(self, ctx:MySQLParser.OrderClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#direction.
    def enterDirection(self, ctx:MySQLParser.DirectionContext):
        pass

    # Exit a parse tree produced by MySQLParser#direction.
    def exitDirection(self, ctx:MySQLParser.DirectionContext):
        pass


    # Enter a parse tree produced by MySQLParser#fromClause.
    def enterFromClause(self, ctx:MySQLParser.FromClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#fromClause.
    def exitFromClause(self, ctx:MySQLParser.FromClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableReferenceList.
    def enterTableReferenceList(self, ctx:MySQLParser.TableReferenceListContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableReferenceList.
    def exitTableReferenceList(self, ctx:MySQLParser.TableReferenceListContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableValueConstructor.
    def enterTableValueConstructor(self, ctx:MySQLParser.TableValueConstructorContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableValueConstructor.
    def exitTableValueConstructor(self, ctx:MySQLParser.TableValueConstructorContext):
        pass


    # Enter a parse tree produced by MySQLParser#explicitTable.
    def enterExplicitTable(self, ctx:MySQLParser.ExplicitTableContext):
        pass

    # Exit a parse tree produced by MySQLParser#explicitTable.
    def exitExplicitTable(self, ctx:MySQLParser.ExplicitTableContext):
        pass


    # Enter a parse tree produced by MySQLParser#rowValueExplicit.
    def enterRowValueExplicit(self, ctx:MySQLParser.RowValueExplicitContext):
        pass

    # Exit a parse tree produced by MySQLParser#rowValueExplicit.
    def exitRowValueExplicit(self, ctx:MySQLParser.RowValueExplicitContext):
        pass


    # Enter a parse tree produced by MySQLParser#selectOption.
    def enterSelectOption(self, ctx:MySQLParser.SelectOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#selectOption.
    def exitSelectOption(self, ctx:MySQLParser.SelectOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#lockingClauseList.
    def enterLockingClauseList(self, ctx:MySQLParser.LockingClauseListContext):
        pass

    # Exit a parse tree produced by MySQLParser#lockingClauseList.
    def exitLockingClauseList(self, ctx:MySQLParser.LockingClauseListContext):
        pass


    # Enter a parse tree produced by MySQLParser#lockingClause.
    def enterLockingClause(self, ctx:MySQLParser.LockingClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#lockingClause.
    def exitLockingClause(self, ctx:MySQLParser.LockingClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#lockStrengh.
    def enterLockStrengh(self, ctx:MySQLParser.LockStrenghContext):
        pass

    # Exit a parse tree produced by MySQLParser#lockStrengh.
    def exitLockStrengh(self, ctx:MySQLParser.LockStrenghContext):
        pass


    # Enter a parse tree produced by MySQLParser#lockedRowAction.
    def enterLockedRowAction(self, ctx:MySQLParser.LockedRowActionContext):
        pass

    # Exit a parse tree produced by MySQLParser#lockedRowAction.
    def exitLockedRowAction(self, ctx:MySQLParser.LockedRowActionContext):
        pass


    # Enter a parse tree produced by MySQLParser#selectItemList.
    def enterSelectItemList(self, ctx:MySQLParser.SelectItemListContext):
        pass

    # Exit a parse tree produced by MySQLParser#selectItemList.
    def exitSelectItemList(self, ctx:MySQLParser.SelectItemListContext):
        pass


    # Enter a parse tree produced by MySQLParser#selectItem.
    def enterSelectItem(self, ctx:MySQLParser.SelectItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#selectItem.
    def exitSelectItem(self, ctx:MySQLParser.SelectItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#selectAlias.
    def enterSelectAlias(self, ctx:MySQLParser.SelectAliasContext):
        pass

    # Exit a parse tree produced by MySQLParser#selectAlias.
    def exitSelectAlias(self, ctx:MySQLParser.SelectAliasContext):
        pass


    # Enter a parse tree produced by MySQLParser#whereClause.
    def enterWhereClause(self, ctx:MySQLParser.WhereClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#whereClause.
    def exitWhereClause(self, ctx:MySQLParser.WhereClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableReference.
    def enterTableReference(self, ctx:MySQLParser.TableReferenceContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableReference.
    def exitTableReference(self, ctx:MySQLParser.TableReferenceContext):
        pass


    # Enter a parse tree produced by MySQLParser#escapedTableReference.
    def enterEscapedTableReference(self, ctx:MySQLParser.EscapedTableReferenceContext):
        pass

    # Exit a parse tree produced by MySQLParser#escapedTableReference.
    def exitEscapedTableReference(self, ctx:MySQLParser.EscapedTableReferenceContext):
        pass


    # Enter a parse tree produced by MySQLParser#joinedTable.
    def enterJoinedTable(self, ctx:MySQLParser.JoinedTableContext):
        pass

    # Exit a parse tree produced by MySQLParser#joinedTable.
    def exitJoinedTable(self, ctx:MySQLParser.JoinedTableContext):
        pass


    # Enter a parse tree produced by MySQLParser#naturalJoinType.
    def enterNaturalJoinType(self, ctx:MySQLParser.NaturalJoinTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#naturalJoinType.
    def exitNaturalJoinType(self, ctx:MySQLParser.NaturalJoinTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#innerJoinType.
    def enterInnerJoinType(self, ctx:MySQLParser.InnerJoinTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#innerJoinType.
    def exitInnerJoinType(self, ctx:MySQLParser.InnerJoinTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#outerJoinType.
    def enterOuterJoinType(self, ctx:MySQLParser.OuterJoinTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#outerJoinType.
    def exitOuterJoinType(self, ctx:MySQLParser.OuterJoinTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableFactor.
    def enterTableFactor(self, ctx:MySQLParser.TableFactorContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableFactor.
    def exitTableFactor(self, ctx:MySQLParser.TableFactorContext):
        pass


    # Enter a parse tree produced by MySQLParser#singleTable.
    def enterSingleTable(self, ctx:MySQLParser.SingleTableContext):
        pass

    # Exit a parse tree produced by MySQLParser#singleTable.
    def exitSingleTable(self, ctx:MySQLParser.SingleTableContext):
        pass


    # Enter a parse tree produced by MySQLParser#singleTableParens.
    def enterSingleTableParens(self, ctx:MySQLParser.SingleTableParensContext):
        pass

    # Exit a parse tree produced by MySQLParser#singleTableParens.
    def exitSingleTableParens(self, ctx:MySQLParser.SingleTableParensContext):
        pass


    # Enter a parse tree produced by MySQLParser#derivedTable.
    def enterDerivedTable(self, ctx:MySQLParser.DerivedTableContext):
        pass

    # Exit a parse tree produced by MySQLParser#derivedTable.
    def exitDerivedTable(self, ctx:MySQLParser.DerivedTableContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableReferenceListParens.
    def enterTableReferenceListParens(self, ctx:MySQLParser.TableReferenceListParensContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableReferenceListParens.
    def exitTableReferenceListParens(self, ctx:MySQLParser.TableReferenceListParensContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableFunction.
    def enterTableFunction(self, ctx:MySQLParser.TableFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableFunction.
    def exitTableFunction(self, ctx:MySQLParser.TableFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnsClause.
    def enterColumnsClause(self, ctx:MySQLParser.ColumnsClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnsClause.
    def exitColumnsClause(self, ctx:MySQLParser.ColumnsClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#jtColumn.
    def enterJtColumn(self, ctx:MySQLParser.JtColumnContext):
        pass

    # Exit a parse tree produced by MySQLParser#jtColumn.
    def exitJtColumn(self, ctx:MySQLParser.JtColumnContext):
        pass


    # Enter a parse tree produced by MySQLParser#onEmptyOrError.
    def enterOnEmptyOrError(self, ctx:MySQLParser.OnEmptyOrErrorContext):
        pass

    # Exit a parse tree produced by MySQLParser#onEmptyOrError.
    def exitOnEmptyOrError(self, ctx:MySQLParser.OnEmptyOrErrorContext):
        pass


    # Enter a parse tree produced by MySQLParser#onEmpty.
    def enterOnEmpty(self, ctx:MySQLParser.OnEmptyContext):
        pass

    # Exit a parse tree produced by MySQLParser#onEmpty.
    def exitOnEmpty(self, ctx:MySQLParser.OnEmptyContext):
        pass


    # Enter a parse tree produced by MySQLParser#onError.
    def enterOnError(self, ctx:MySQLParser.OnErrorContext):
        pass

    # Exit a parse tree produced by MySQLParser#onError.
    def exitOnError(self, ctx:MySQLParser.OnErrorContext):
        pass


    # Enter a parse tree produced by MySQLParser#jtOnResponse.
    def enterJtOnResponse(self, ctx:MySQLParser.JtOnResponseContext):
        pass

    # Exit a parse tree produced by MySQLParser#jtOnResponse.
    def exitJtOnResponse(self, ctx:MySQLParser.JtOnResponseContext):
        pass


    # Enter a parse tree produced by MySQLParser#unionOption.
    def enterUnionOption(self, ctx:MySQLParser.UnionOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#unionOption.
    def exitUnionOption(self, ctx:MySQLParser.UnionOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableAlias.
    def enterTableAlias(self, ctx:MySQLParser.TableAliasContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableAlias.
    def exitTableAlias(self, ctx:MySQLParser.TableAliasContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexHintList.
    def enterIndexHintList(self, ctx:MySQLParser.IndexHintListContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexHintList.
    def exitIndexHintList(self, ctx:MySQLParser.IndexHintListContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexHint.
    def enterIndexHint(self, ctx:MySQLParser.IndexHintContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexHint.
    def exitIndexHint(self, ctx:MySQLParser.IndexHintContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexHintType.
    def enterIndexHintType(self, ctx:MySQLParser.IndexHintTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexHintType.
    def exitIndexHintType(self, ctx:MySQLParser.IndexHintTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyOrIndex.
    def enterKeyOrIndex(self, ctx:MySQLParser.KeyOrIndexContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyOrIndex.
    def exitKeyOrIndex(self, ctx:MySQLParser.KeyOrIndexContext):
        pass


    # Enter a parse tree produced by MySQLParser#constraintKeyType.
    def enterConstraintKeyType(self, ctx:MySQLParser.ConstraintKeyTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#constraintKeyType.
    def exitConstraintKeyType(self, ctx:MySQLParser.ConstraintKeyTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexHintClause.
    def enterIndexHintClause(self, ctx:MySQLParser.IndexHintClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexHintClause.
    def exitIndexHintClause(self, ctx:MySQLParser.IndexHintClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexList.
    def enterIndexList(self, ctx:MySQLParser.IndexListContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexList.
    def exitIndexList(self, ctx:MySQLParser.IndexListContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexListElement.
    def enterIndexListElement(self, ctx:MySQLParser.IndexListElementContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexListElement.
    def exitIndexListElement(self, ctx:MySQLParser.IndexListElementContext):
        pass


    # Enter a parse tree produced by MySQLParser#updateStatement.
    def enterUpdateStatement(self, ctx:MySQLParser.UpdateStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#updateStatement.
    def exitUpdateStatement(self, ctx:MySQLParser.UpdateStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#transactionOrLockingStatement.
    def enterTransactionOrLockingStatement(self, ctx:MySQLParser.TransactionOrLockingStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#transactionOrLockingStatement.
    def exitTransactionOrLockingStatement(self, ctx:MySQLParser.TransactionOrLockingStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#transactionStatement.
    def enterTransactionStatement(self, ctx:MySQLParser.TransactionStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#transactionStatement.
    def exitTransactionStatement(self, ctx:MySQLParser.TransactionStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#beginWork.
    def enterBeginWork(self, ctx:MySQLParser.BeginWorkContext):
        pass

    # Exit a parse tree produced by MySQLParser#beginWork.
    def exitBeginWork(self, ctx:MySQLParser.BeginWorkContext):
        pass


    # Enter a parse tree produced by MySQLParser#transactionCharacteristic.
    def enterTransactionCharacteristic(self, ctx:MySQLParser.TransactionCharacteristicContext):
        pass

    # Exit a parse tree produced by MySQLParser#transactionCharacteristic.
    def exitTransactionCharacteristic(self, ctx:MySQLParser.TransactionCharacteristicContext):
        pass


    # Enter a parse tree produced by MySQLParser#savepointStatement.
    def enterSavepointStatement(self, ctx:MySQLParser.SavepointStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#savepointStatement.
    def exitSavepointStatement(self, ctx:MySQLParser.SavepointStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#lockStatement.
    def enterLockStatement(self, ctx:MySQLParser.LockStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#lockStatement.
    def exitLockStatement(self, ctx:MySQLParser.LockStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#lockItem.
    def enterLockItem(self, ctx:MySQLParser.LockItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#lockItem.
    def exitLockItem(self, ctx:MySQLParser.LockItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#lockOption.
    def enterLockOption(self, ctx:MySQLParser.LockOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#lockOption.
    def exitLockOption(self, ctx:MySQLParser.LockOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#xaStatement.
    def enterXaStatement(self, ctx:MySQLParser.XaStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#xaStatement.
    def exitXaStatement(self, ctx:MySQLParser.XaStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#xaConvert.
    def enterXaConvert(self, ctx:MySQLParser.XaConvertContext):
        pass

    # Exit a parse tree produced by MySQLParser#xaConvert.
    def exitXaConvert(self, ctx:MySQLParser.XaConvertContext):
        pass


    # Enter a parse tree produced by MySQLParser#xid.
    def enterXid(self, ctx:MySQLParser.XidContext):
        pass

    # Exit a parse tree produced by MySQLParser#xid.
    def exitXid(self, ctx:MySQLParser.XidContext):
        pass


    # Enter a parse tree produced by MySQLParser#replicationStatement.
    def enterReplicationStatement(self, ctx:MySQLParser.ReplicationStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#replicationStatement.
    def exitReplicationStatement(self, ctx:MySQLParser.ReplicationStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#resetOption.
    def enterResetOption(self, ctx:MySQLParser.ResetOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#resetOption.
    def exitResetOption(self, ctx:MySQLParser.ResetOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#masterResetOptions.
    def enterMasterResetOptions(self, ctx:MySQLParser.MasterResetOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#masterResetOptions.
    def exitMasterResetOptions(self, ctx:MySQLParser.MasterResetOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#replicationLoad.
    def enterReplicationLoad(self, ctx:MySQLParser.ReplicationLoadContext):
        pass

    # Exit a parse tree produced by MySQLParser#replicationLoad.
    def exitReplicationLoad(self, ctx:MySQLParser.ReplicationLoadContext):
        pass


    # Enter a parse tree produced by MySQLParser#changeMaster.
    def enterChangeMaster(self, ctx:MySQLParser.ChangeMasterContext):
        pass

    # Exit a parse tree produced by MySQLParser#changeMaster.
    def exitChangeMaster(self, ctx:MySQLParser.ChangeMasterContext):
        pass


    # Enter a parse tree produced by MySQLParser#changeMasterOptions.
    def enterChangeMasterOptions(self, ctx:MySQLParser.ChangeMasterOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#changeMasterOptions.
    def exitChangeMasterOptions(self, ctx:MySQLParser.ChangeMasterOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#masterOption.
    def enterMasterOption(self, ctx:MySQLParser.MasterOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#masterOption.
    def exitMasterOption(self, ctx:MySQLParser.MasterOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#privilegeCheckDef.
    def enterPrivilegeCheckDef(self, ctx:MySQLParser.PrivilegeCheckDefContext):
        pass

    # Exit a parse tree produced by MySQLParser#privilegeCheckDef.
    def exitPrivilegeCheckDef(self, ctx:MySQLParser.PrivilegeCheckDefContext):
        pass


    # Enter a parse tree produced by MySQLParser#tablePrimaryKeyCheckDef.
    def enterTablePrimaryKeyCheckDef(self, ctx:MySQLParser.TablePrimaryKeyCheckDefContext):
        pass

    # Exit a parse tree produced by MySQLParser#tablePrimaryKeyCheckDef.
    def exitTablePrimaryKeyCheckDef(self, ctx:MySQLParser.TablePrimaryKeyCheckDefContext):
        pass


    # Enter a parse tree produced by MySQLParser#masterTlsCiphersuitesDef.
    def enterMasterTlsCiphersuitesDef(self, ctx:MySQLParser.MasterTlsCiphersuitesDefContext):
        pass

    # Exit a parse tree produced by MySQLParser#masterTlsCiphersuitesDef.
    def exitMasterTlsCiphersuitesDef(self, ctx:MySQLParser.MasterTlsCiphersuitesDefContext):
        pass


    # Enter a parse tree produced by MySQLParser#masterFileDef.
    def enterMasterFileDef(self, ctx:MySQLParser.MasterFileDefContext):
        pass

    # Exit a parse tree produced by MySQLParser#masterFileDef.
    def exitMasterFileDef(self, ctx:MySQLParser.MasterFileDefContext):
        pass


    # Enter a parse tree produced by MySQLParser#serverIdList.
    def enterServerIdList(self, ctx:MySQLParser.ServerIdListContext):
        pass

    # Exit a parse tree produced by MySQLParser#serverIdList.
    def exitServerIdList(self, ctx:MySQLParser.ServerIdListContext):
        pass


    # Enter a parse tree produced by MySQLParser#changeReplication.
    def enterChangeReplication(self, ctx:MySQLParser.ChangeReplicationContext):
        pass

    # Exit a parse tree produced by MySQLParser#changeReplication.
    def exitChangeReplication(self, ctx:MySQLParser.ChangeReplicationContext):
        pass


    # Enter a parse tree produced by MySQLParser#filterDefinition.
    def enterFilterDefinition(self, ctx:MySQLParser.FilterDefinitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#filterDefinition.
    def exitFilterDefinition(self, ctx:MySQLParser.FilterDefinitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#filterDbList.
    def enterFilterDbList(self, ctx:MySQLParser.FilterDbListContext):
        pass

    # Exit a parse tree produced by MySQLParser#filterDbList.
    def exitFilterDbList(self, ctx:MySQLParser.FilterDbListContext):
        pass


    # Enter a parse tree produced by MySQLParser#filterTableList.
    def enterFilterTableList(self, ctx:MySQLParser.FilterTableListContext):
        pass

    # Exit a parse tree produced by MySQLParser#filterTableList.
    def exitFilterTableList(self, ctx:MySQLParser.FilterTableListContext):
        pass


    # Enter a parse tree produced by MySQLParser#filterStringList.
    def enterFilterStringList(self, ctx:MySQLParser.FilterStringListContext):
        pass

    # Exit a parse tree produced by MySQLParser#filterStringList.
    def exitFilterStringList(self, ctx:MySQLParser.FilterStringListContext):
        pass


    # Enter a parse tree produced by MySQLParser#filterWildDbTableString.
    def enterFilterWildDbTableString(self, ctx:MySQLParser.FilterWildDbTableStringContext):
        pass

    # Exit a parse tree produced by MySQLParser#filterWildDbTableString.
    def exitFilterWildDbTableString(self, ctx:MySQLParser.FilterWildDbTableStringContext):
        pass


    # Enter a parse tree produced by MySQLParser#filterDbPairList.
    def enterFilterDbPairList(self, ctx:MySQLParser.FilterDbPairListContext):
        pass

    # Exit a parse tree produced by MySQLParser#filterDbPairList.
    def exitFilterDbPairList(self, ctx:MySQLParser.FilterDbPairListContext):
        pass


    # Enter a parse tree produced by MySQLParser#slave.
    def enterSlave(self, ctx:MySQLParser.SlaveContext):
        pass

    # Exit a parse tree produced by MySQLParser#slave.
    def exitSlave(self, ctx:MySQLParser.SlaveContext):
        pass


    # Enter a parse tree produced by MySQLParser#slaveUntilOptions.
    def enterSlaveUntilOptions(self, ctx:MySQLParser.SlaveUntilOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#slaveUntilOptions.
    def exitSlaveUntilOptions(self, ctx:MySQLParser.SlaveUntilOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#slaveConnectionOptions.
    def enterSlaveConnectionOptions(self, ctx:MySQLParser.SlaveConnectionOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#slaveConnectionOptions.
    def exitSlaveConnectionOptions(self, ctx:MySQLParser.SlaveConnectionOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#slaveThreadOptions.
    def enterSlaveThreadOptions(self, ctx:MySQLParser.SlaveThreadOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#slaveThreadOptions.
    def exitSlaveThreadOptions(self, ctx:MySQLParser.SlaveThreadOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#slaveThreadOption.
    def enterSlaveThreadOption(self, ctx:MySQLParser.SlaveThreadOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#slaveThreadOption.
    def exitSlaveThreadOption(self, ctx:MySQLParser.SlaveThreadOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#groupReplication.
    def enterGroupReplication(self, ctx:MySQLParser.GroupReplicationContext):
        pass

    # Exit a parse tree produced by MySQLParser#groupReplication.
    def exitGroupReplication(self, ctx:MySQLParser.GroupReplicationContext):
        pass


    # Enter a parse tree produced by MySQLParser#preparedStatement.
    def enterPreparedStatement(self, ctx:MySQLParser.PreparedStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#preparedStatement.
    def exitPreparedStatement(self, ctx:MySQLParser.PreparedStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#executeStatement.
    def enterExecuteStatement(self, ctx:MySQLParser.ExecuteStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#executeStatement.
    def exitExecuteStatement(self, ctx:MySQLParser.ExecuteStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#executeVarList.
    def enterExecuteVarList(self, ctx:MySQLParser.ExecuteVarListContext):
        pass

    # Exit a parse tree produced by MySQLParser#executeVarList.
    def exitExecuteVarList(self, ctx:MySQLParser.ExecuteVarListContext):
        pass


    # Enter a parse tree produced by MySQLParser#cloneStatement.
    def enterCloneStatement(self, ctx:MySQLParser.CloneStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#cloneStatement.
    def exitCloneStatement(self, ctx:MySQLParser.CloneStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#dataDirSSL.
    def enterDataDirSSL(self, ctx:MySQLParser.DataDirSSLContext):
        pass

    # Exit a parse tree produced by MySQLParser#dataDirSSL.
    def exitDataDirSSL(self, ctx:MySQLParser.DataDirSSLContext):
        pass


    # Enter a parse tree produced by MySQLParser#ssl.
    def enterSsl(self, ctx:MySQLParser.SslContext):
        pass

    # Exit a parse tree produced by MySQLParser#ssl.
    def exitSsl(self, ctx:MySQLParser.SslContext):
        pass


    # Enter a parse tree produced by MySQLParser#accountManagementStatement.
    def enterAccountManagementStatement(self, ctx:MySQLParser.AccountManagementStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#accountManagementStatement.
    def exitAccountManagementStatement(self, ctx:MySQLParser.AccountManagementStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterUser.
    def enterAlterUser(self, ctx:MySQLParser.AlterUserContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterUser.
    def exitAlterUser(self, ctx:MySQLParser.AlterUserContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterUserTail.
    def enterAlterUserTail(self, ctx:MySQLParser.AlterUserTailContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterUserTail.
    def exitAlterUserTail(self, ctx:MySQLParser.AlterUserTailContext):
        pass


    # Enter a parse tree produced by MySQLParser#userFunction.
    def enterUserFunction(self, ctx:MySQLParser.UserFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#userFunction.
    def exitUserFunction(self, ctx:MySQLParser.UserFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#createUser.
    def enterCreateUser(self, ctx:MySQLParser.CreateUserContext):
        pass

    # Exit a parse tree produced by MySQLParser#createUser.
    def exitCreateUser(self, ctx:MySQLParser.CreateUserContext):
        pass


    # Enter a parse tree produced by MySQLParser#createUserTail.
    def enterCreateUserTail(self, ctx:MySQLParser.CreateUserTailContext):
        pass

    # Exit a parse tree produced by MySQLParser#createUserTail.
    def exitCreateUserTail(self, ctx:MySQLParser.CreateUserTailContext):
        pass


    # Enter a parse tree produced by MySQLParser#defaultRoleClause.
    def enterDefaultRoleClause(self, ctx:MySQLParser.DefaultRoleClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#defaultRoleClause.
    def exitDefaultRoleClause(self, ctx:MySQLParser.DefaultRoleClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#requireClause.
    def enterRequireClause(self, ctx:MySQLParser.RequireClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#requireClause.
    def exitRequireClause(self, ctx:MySQLParser.RequireClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#connectOptions.
    def enterConnectOptions(self, ctx:MySQLParser.ConnectOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#connectOptions.
    def exitConnectOptions(self, ctx:MySQLParser.ConnectOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#accountLockPasswordExpireOptions.
    def enterAccountLockPasswordExpireOptions(self, ctx:MySQLParser.AccountLockPasswordExpireOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#accountLockPasswordExpireOptions.
    def exitAccountLockPasswordExpireOptions(self, ctx:MySQLParser.AccountLockPasswordExpireOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropUser.
    def enterDropUser(self, ctx:MySQLParser.DropUserContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropUser.
    def exitDropUser(self, ctx:MySQLParser.DropUserContext):
        pass


    # Enter a parse tree produced by MySQLParser#grant.
    def enterGrant(self, ctx:MySQLParser.GrantContext):
        pass

    # Exit a parse tree produced by MySQLParser#grant.
    def exitGrant(self, ctx:MySQLParser.GrantContext):
        pass


    # Enter a parse tree produced by MySQLParser#grantTargetList.
    def enterGrantTargetList(self, ctx:MySQLParser.GrantTargetListContext):
        pass

    # Exit a parse tree produced by MySQLParser#grantTargetList.
    def exitGrantTargetList(self, ctx:MySQLParser.GrantTargetListContext):
        pass


    # Enter a parse tree produced by MySQLParser#grantOptions.
    def enterGrantOptions(self, ctx:MySQLParser.GrantOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#grantOptions.
    def exitGrantOptions(self, ctx:MySQLParser.GrantOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#exceptRoleList.
    def enterExceptRoleList(self, ctx:MySQLParser.ExceptRoleListContext):
        pass

    # Exit a parse tree produced by MySQLParser#exceptRoleList.
    def exitExceptRoleList(self, ctx:MySQLParser.ExceptRoleListContext):
        pass


    # Enter a parse tree produced by MySQLParser#withRoles.
    def enterWithRoles(self, ctx:MySQLParser.WithRolesContext):
        pass

    # Exit a parse tree produced by MySQLParser#withRoles.
    def exitWithRoles(self, ctx:MySQLParser.WithRolesContext):
        pass


    # Enter a parse tree produced by MySQLParser#grantAs.
    def enterGrantAs(self, ctx:MySQLParser.GrantAsContext):
        pass

    # Exit a parse tree produced by MySQLParser#grantAs.
    def exitGrantAs(self, ctx:MySQLParser.GrantAsContext):
        pass


    # Enter a parse tree produced by MySQLParser#versionedRequireClause.
    def enterVersionedRequireClause(self, ctx:MySQLParser.VersionedRequireClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#versionedRequireClause.
    def exitVersionedRequireClause(self, ctx:MySQLParser.VersionedRequireClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#renameUser.
    def enterRenameUser(self, ctx:MySQLParser.RenameUserContext):
        pass

    # Exit a parse tree produced by MySQLParser#renameUser.
    def exitRenameUser(self, ctx:MySQLParser.RenameUserContext):
        pass


    # Enter a parse tree produced by MySQLParser#revoke.
    def enterRevoke(self, ctx:MySQLParser.RevokeContext):
        pass

    # Exit a parse tree produced by MySQLParser#revoke.
    def exitRevoke(self, ctx:MySQLParser.RevokeContext):
        pass


    # Enter a parse tree produced by MySQLParser#onTypeTo.
    def enterOnTypeTo(self, ctx:MySQLParser.OnTypeToContext):
        pass

    # Exit a parse tree produced by MySQLParser#onTypeTo.
    def exitOnTypeTo(self, ctx:MySQLParser.OnTypeToContext):
        pass


    # Enter a parse tree produced by MySQLParser#aclType.
    def enterAclType(self, ctx:MySQLParser.AclTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#aclType.
    def exitAclType(self, ctx:MySQLParser.AclTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleOrPrivilegesList.
    def enterRoleOrPrivilegesList(self, ctx:MySQLParser.RoleOrPrivilegesListContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleOrPrivilegesList.
    def exitRoleOrPrivilegesList(self, ctx:MySQLParser.RoleOrPrivilegesListContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleOrPrivilege.
    def enterRoleOrPrivilege(self, ctx:MySQLParser.RoleOrPrivilegeContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleOrPrivilege.
    def exitRoleOrPrivilege(self, ctx:MySQLParser.RoleOrPrivilegeContext):
        pass


    # Enter a parse tree produced by MySQLParser#grantIdentifier.
    def enterGrantIdentifier(self, ctx:MySQLParser.GrantIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#grantIdentifier.
    def exitGrantIdentifier(self, ctx:MySQLParser.GrantIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#requireList.
    def enterRequireList(self, ctx:MySQLParser.RequireListContext):
        pass

    # Exit a parse tree produced by MySQLParser#requireList.
    def exitRequireList(self, ctx:MySQLParser.RequireListContext):
        pass


    # Enter a parse tree produced by MySQLParser#requireListElement.
    def enterRequireListElement(self, ctx:MySQLParser.RequireListElementContext):
        pass

    # Exit a parse tree produced by MySQLParser#requireListElement.
    def exitRequireListElement(self, ctx:MySQLParser.RequireListElementContext):
        pass


    # Enter a parse tree produced by MySQLParser#grantOption.
    def enterGrantOption(self, ctx:MySQLParser.GrantOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#grantOption.
    def exitGrantOption(self, ctx:MySQLParser.GrantOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#setRole.
    def enterSetRole(self, ctx:MySQLParser.SetRoleContext):
        pass

    # Exit a parse tree produced by MySQLParser#setRole.
    def exitSetRole(self, ctx:MySQLParser.SetRoleContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleList.
    def enterRoleList(self, ctx:MySQLParser.RoleListContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleList.
    def exitRoleList(self, ctx:MySQLParser.RoleListContext):
        pass


    # Enter a parse tree produced by MySQLParser#role.
    def enterRole(self, ctx:MySQLParser.RoleContext):
        pass

    # Exit a parse tree produced by MySQLParser#role.
    def exitRole(self, ctx:MySQLParser.RoleContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableAdministrationStatement.
    def enterTableAdministrationStatement(self, ctx:MySQLParser.TableAdministrationStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableAdministrationStatement.
    def exitTableAdministrationStatement(self, ctx:MySQLParser.TableAdministrationStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#histogram.
    def enterHistogram(self, ctx:MySQLParser.HistogramContext):
        pass

    # Exit a parse tree produced by MySQLParser#histogram.
    def exitHistogram(self, ctx:MySQLParser.HistogramContext):
        pass


    # Enter a parse tree produced by MySQLParser#checkOption.
    def enterCheckOption(self, ctx:MySQLParser.CheckOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#checkOption.
    def exitCheckOption(self, ctx:MySQLParser.CheckOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#repairType.
    def enterRepairType(self, ctx:MySQLParser.RepairTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#repairType.
    def exitRepairType(self, ctx:MySQLParser.RepairTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#installUninstallStatment.
    def enterInstallUninstallStatment(self, ctx:MySQLParser.InstallUninstallStatmentContext):
        pass

    # Exit a parse tree produced by MySQLParser#installUninstallStatment.
    def exitInstallUninstallStatment(self, ctx:MySQLParser.InstallUninstallStatmentContext):
        pass


    # Enter a parse tree produced by MySQLParser#setStatement.
    def enterSetStatement(self, ctx:MySQLParser.SetStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#setStatement.
    def exitSetStatement(self, ctx:MySQLParser.SetStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#startOptionValueList.
    def enterStartOptionValueList(self, ctx:MySQLParser.StartOptionValueListContext):
        pass

    # Exit a parse tree produced by MySQLParser#startOptionValueList.
    def exitStartOptionValueList(self, ctx:MySQLParser.StartOptionValueListContext):
        pass


    # Enter a parse tree produced by MySQLParser#transactionCharacteristics.
    def enterTransactionCharacteristics(self, ctx:MySQLParser.TransactionCharacteristicsContext):
        pass

    # Exit a parse tree produced by MySQLParser#transactionCharacteristics.
    def exitTransactionCharacteristics(self, ctx:MySQLParser.TransactionCharacteristicsContext):
        pass


    # Enter a parse tree produced by MySQLParser#transactionAccessMode.
    def enterTransactionAccessMode(self, ctx:MySQLParser.TransactionAccessModeContext):
        pass

    # Exit a parse tree produced by MySQLParser#transactionAccessMode.
    def exitTransactionAccessMode(self, ctx:MySQLParser.TransactionAccessModeContext):
        pass


    # Enter a parse tree produced by MySQLParser#isolationLevel.
    def enterIsolationLevel(self, ctx:MySQLParser.IsolationLevelContext):
        pass

    # Exit a parse tree produced by MySQLParser#isolationLevel.
    def exitIsolationLevel(self, ctx:MySQLParser.IsolationLevelContext):
        pass


    # Enter a parse tree produced by MySQLParser#optionValueListContinued.
    def enterOptionValueListContinued(self, ctx:MySQLParser.OptionValueListContinuedContext):
        pass

    # Exit a parse tree produced by MySQLParser#optionValueListContinued.
    def exitOptionValueListContinued(self, ctx:MySQLParser.OptionValueListContinuedContext):
        pass


    # Enter a parse tree produced by MySQLParser#optionValueNoOptionType.
    def enterOptionValueNoOptionType(self, ctx:MySQLParser.OptionValueNoOptionTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#optionValueNoOptionType.
    def exitOptionValueNoOptionType(self, ctx:MySQLParser.OptionValueNoOptionTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#optionValue.
    def enterOptionValue(self, ctx:MySQLParser.OptionValueContext):
        pass

    # Exit a parse tree produced by MySQLParser#optionValue.
    def exitOptionValue(self, ctx:MySQLParser.OptionValueContext):
        pass


    # Enter a parse tree produced by MySQLParser#setSystemVariable.
    def enterSetSystemVariable(self, ctx:MySQLParser.SetSystemVariableContext):
        pass

    # Exit a parse tree produced by MySQLParser#setSystemVariable.
    def exitSetSystemVariable(self, ctx:MySQLParser.SetSystemVariableContext):
        pass


    # Enter a parse tree produced by MySQLParser#startOptionValueListFollowingOptionType.
    def enterStartOptionValueListFollowingOptionType(self, ctx:MySQLParser.StartOptionValueListFollowingOptionTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#startOptionValueListFollowingOptionType.
    def exitStartOptionValueListFollowingOptionType(self, ctx:MySQLParser.StartOptionValueListFollowingOptionTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#optionValueFollowingOptionType.
    def enterOptionValueFollowingOptionType(self, ctx:MySQLParser.OptionValueFollowingOptionTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#optionValueFollowingOptionType.
    def exitOptionValueFollowingOptionType(self, ctx:MySQLParser.OptionValueFollowingOptionTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#setExprOrDefault.
    def enterSetExprOrDefault(self, ctx:MySQLParser.SetExprOrDefaultContext):
        pass

    # Exit a parse tree produced by MySQLParser#setExprOrDefault.
    def exitSetExprOrDefault(self, ctx:MySQLParser.SetExprOrDefaultContext):
        pass


    # Enter a parse tree produced by MySQLParser#showStatement.
    def enterShowStatement(self, ctx:MySQLParser.ShowStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#showStatement.
    def exitShowStatement(self, ctx:MySQLParser.ShowStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#showCommandType.
    def enterShowCommandType(self, ctx:MySQLParser.ShowCommandTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#showCommandType.
    def exitShowCommandType(self, ctx:MySQLParser.ShowCommandTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#nonBlocking.
    def enterNonBlocking(self, ctx:MySQLParser.NonBlockingContext):
        pass

    # Exit a parse tree produced by MySQLParser#nonBlocking.
    def exitNonBlocking(self, ctx:MySQLParser.NonBlockingContext):
        pass


    # Enter a parse tree produced by MySQLParser#fromOrIn.
    def enterFromOrIn(self, ctx:MySQLParser.FromOrInContext):
        pass

    # Exit a parse tree produced by MySQLParser#fromOrIn.
    def exitFromOrIn(self, ctx:MySQLParser.FromOrInContext):
        pass


    # Enter a parse tree produced by MySQLParser#inDb.
    def enterInDb(self, ctx:MySQLParser.InDbContext):
        pass

    # Exit a parse tree produced by MySQLParser#inDb.
    def exitInDb(self, ctx:MySQLParser.InDbContext):
        pass


    # Enter a parse tree produced by MySQLParser#profileType.
    def enterProfileType(self, ctx:MySQLParser.ProfileTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#profileType.
    def exitProfileType(self, ctx:MySQLParser.ProfileTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#otherAdministrativeStatement.
    def enterOtherAdministrativeStatement(self, ctx:MySQLParser.OtherAdministrativeStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#otherAdministrativeStatement.
    def exitOtherAdministrativeStatement(self, ctx:MySQLParser.OtherAdministrativeStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyCacheListOrParts.
    def enterKeyCacheListOrParts(self, ctx:MySQLParser.KeyCacheListOrPartsContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyCacheListOrParts.
    def exitKeyCacheListOrParts(self, ctx:MySQLParser.KeyCacheListOrPartsContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyCacheList.
    def enterKeyCacheList(self, ctx:MySQLParser.KeyCacheListContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyCacheList.
    def exitKeyCacheList(self, ctx:MySQLParser.KeyCacheListContext):
        pass


    # Enter a parse tree produced by MySQLParser#assignToKeycache.
    def enterAssignToKeycache(self, ctx:MySQLParser.AssignToKeycacheContext):
        pass

    # Exit a parse tree produced by MySQLParser#assignToKeycache.
    def exitAssignToKeycache(self, ctx:MySQLParser.AssignToKeycacheContext):
        pass


    # Enter a parse tree produced by MySQLParser#assignToKeycachePartition.
    def enterAssignToKeycachePartition(self, ctx:MySQLParser.AssignToKeycachePartitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#assignToKeycachePartition.
    def exitAssignToKeycachePartition(self, ctx:MySQLParser.AssignToKeycachePartitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#cacheKeyList.
    def enterCacheKeyList(self, ctx:MySQLParser.CacheKeyListContext):
        pass

    # Exit a parse tree produced by MySQLParser#cacheKeyList.
    def exitCacheKeyList(self, ctx:MySQLParser.CacheKeyListContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyUsageElement.
    def enterKeyUsageElement(self, ctx:MySQLParser.KeyUsageElementContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyUsageElement.
    def exitKeyUsageElement(self, ctx:MySQLParser.KeyUsageElementContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyUsageList.
    def enterKeyUsageList(self, ctx:MySQLParser.KeyUsageListContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyUsageList.
    def exitKeyUsageList(self, ctx:MySQLParser.KeyUsageListContext):
        pass


    # Enter a parse tree produced by MySQLParser#flushOption.
    def enterFlushOption(self, ctx:MySQLParser.FlushOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#flushOption.
    def exitFlushOption(self, ctx:MySQLParser.FlushOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#logType.
    def enterLogType(self, ctx:MySQLParser.LogTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#logType.
    def exitLogType(self, ctx:MySQLParser.LogTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#flushTables.
    def enterFlushTables(self, ctx:MySQLParser.FlushTablesContext):
        pass

    # Exit a parse tree produced by MySQLParser#flushTables.
    def exitFlushTables(self, ctx:MySQLParser.FlushTablesContext):
        pass


    # Enter a parse tree produced by MySQLParser#flushTablesOptions.
    def enterFlushTablesOptions(self, ctx:MySQLParser.FlushTablesOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#flushTablesOptions.
    def exitFlushTablesOptions(self, ctx:MySQLParser.FlushTablesOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#preloadTail.
    def enterPreloadTail(self, ctx:MySQLParser.PreloadTailContext):
        pass

    # Exit a parse tree produced by MySQLParser#preloadTail.
    def exitPreloadTail(self, ctx:MySQLParser.PreloadTailContext):
        pass


    # Enter a parse tree produced by MySQLParser#preloadList.
    def enterPreloadList(self, ctx:MySQLParser.PreloadListContext):
        pass

    # Exit a parse tree produced by MySQLParser#preloadList.
    def exitPreloadList(self, ctx:MySQLParser.PreloadListContext):
        pass


    # Enter a parse tree produced by MySQLParser#preloadKeys.
    def enterPreloadKeys(self, ctx:MySQLParser.PreloadKeysContext):
        pass

    # Exit a parse tree produced by MySQLParser#preloadKeys.
    def exitPreloadKeys(self, ctx:MySQLParser.PreloadKeysContext):
        pass


    # Enter a parse tree produced by MySQLParser#adminPartition.
    def enterAdminPartition(self, ctx:MySQLParser.AdminPartitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#adminPartition.
    def exitAdminPartition(self, ctx:MySQLParser.AdminPartitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#resourceGroupManagement.
    def enterResourceGroupManagement(self, ctx:MySQLParser.ResourceGroupManagementContext):
        pass

    # Exit a parse tree produced by MySQLParser#resourceGroupManagement.
    def exitResourceGroupManagement(self, ctx:MySQLParser.ResourceGroupManagementContext):
        pass


    # Enter a parse tree produced by MySQLParser#createResourceGroup.
    def enterCreateResourceGroup(self, ctx:MySQLParser.CreateResourceGroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#createResourceGroup.
    def exitCreateResourceGroup(self, ctx:MySQLParser.CreateResourceGroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#resourceGroupVcpuList.
    def enterResourceGroupVcpuList(self, ctx:MySQLParser.ResourceGroupVcpuListContext):
        pass

    # Exit a parse tree produced by MySQLParser#resourceGroupVcpuList.
    def exitResourceGroupVcpuList(self, ctx:MySQLParser.ResourceGroupVcpuListContext):
        pass


    # Enter a parse tree produced by MySQLParser#vcpuNumOrRange.
    def enterVcpuNumOrRange(self, ctx:MySQLParser.VcpuNumOrRangeContext):
        pass

    # Exit a parse tree produced by MySQLParser#vcpuNumOrRange.
    def exitVcpuNumOrRange(self, ctx:MySQLParser.VcpuNumOrRangeContext):
        pass


    # Enter a parse tree produced by MySQLParser#resourceGroupPriority.
    def enterResourceGroupPriority(self, ctx:MySQLParser.ResourceGroupPriorityContext):
        pass

    # Exit a parse tree produced by MySQLParser#resourceGroupPriority.
    def exitResourceGroupPriority(self, ctx:MySQLParser.ResourceGroupPriorityContext):
        pass


    # Enter a parse tree produced by MySQLParser#resourceGroupEnableDisable.
    def enterResourceGroupEnableDisable(self, ctx:MySQLParser.ResourceGroupEnableDisableContext):
        pass

    # Exit a parse tree produced by MySQLParser#resourceGroupEnableDisable.
    def exitResourceGroupEnableDisable(self, ctx:MySQLParser.ResourceGroupEnableDisableContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterResourceGroup.
    def enterAlterResourceGroup(self, ctx:MySQLParser.AlterResourceGroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterResourceGroup.
    def exitAlterResourceGroup(self, ctx:MySQLParser.AlterResourceGroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#setResourceGroup.
    def enterSetResourceGroup(self, ctx:MySQLParser.SetResourceGroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#setResourceGroup.
    def exitSetResourceGroup(self, ctx:MySQLParser.SetResourceGroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#threadIdList.
    def enterThreadIdList(self, ctx:MySQLParser.ThreadIdListContext):
        pass

    # Exit a parse tree produced by MySQLParser#threadIdList.
    def exitThreadIdList(self, ctx:MySQLParser.ThreadIdListContext):
        pass


    # Enter a parse tree produced by MySQLParser#dropResourceGroup.
    def enterDropResourceGroup(self, ctx:MySQLParser.DropResourceGroupContext):
        pass

    # Exit a parse tree produced by MySQLParser#dropResourceGroup.
    def exitDropResourceGroup(self, ctx:MySQLParser.DropResourceGroupContext):
        pass


    # Enter a parse tree produced by MySQLParser#utilityStatement.
    def enterUtilityStatement(self, ctx:MySQLParser.UtilityStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#utilityStatement.
    def exitUtilityStatement(self, ctx:MySQLParser.UtilityStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#describeStatement.
    def enterDescribeStatement(self, ctx:MySQLParser.DescribeStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#describeStatement.
    def exitDescribeStatement(self, ctx:MySQLParser.DescribeStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#explainStatement.
    def enterExplainStatement(self, ctx:MySQLParser.ExplainStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#explainStatement.
    def exitExplainStatement(self, ctx:MySQLParser.ExplainStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#explainableStatement.
    def enterExplainableStatement(self, ctx:MySQLParser.ExplainableStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#explainableStatement.
    def exitExplainableStatement(self, ctx:MySQLParser.ExplainableStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#helpCommand.
    def enterHelpCommand(self, ctx:MySQLParser.HelpCommandContext):
        pass

    # Exit a parse tree produced by MySQLParser#helpCommand.
    def exitHelpCommand(self, ctx:MySQLParser.HelpCommandContext):
        pass


    # Enter a parse tree produced by MySQLParser#useCommand.
    def enterUseCommand(self, ctx:MySQLParser.UseCommandContext):
        pass

    # Exit a parse tree produced by MySQLParser#useCommand.
    def exitUseCommand(self, ctx:MySQLParser.UseCommandContext):
        pass


    # Enter a parse tree produced by MySQLParser#restartServer.
    def enterRestartServer(self, ctx:MySQLParser.RestartServerContext):
        pass

    # Exit a parse tree produced by MySQLParser#restartServer.
    def exitRestartServer(self, ctx:MySQLParser.RestartServerContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprOr.
    def enterExprOr(self, ctx:MySQLParser.ExprOrContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprOr.
    def exitExprOr(self, ctx:MySQLParser.ExprOrContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprNot.
    def enterExprNot(self, ctx:MySQLParser.ExprNotContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprNot.
    def exitExprNot(self, ctx:MySQLParser.ExprNotContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprIs.
    def enterExprIs(self, ctx:MySQLParser.ExprIsContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprIs.
    def exitExprIs(self, ctx:MySQLParser.ExprIsContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprAnd.
    def enterExprAnd(self, ctx:MySQLParser.ExprAndContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprAnd.
    def exitExprAnd(self, ctx:MySQLParser.ExprAndContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprXor.
    def enterExprXor(self, ctx:MySQLParser.ExprXorContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprXor.
    def exitExprXor(self, ctx:MySQLParser.ExprXorContext):
        pass


    # Enter a parse tree produced by MySQLParser#primaryExprPredicate.
    def enterPrimaryExprPredicate(self, ctx:MySQLParser.PrimaryExprPredicateContext):
        pass

    # Exit a parse tree produced by MySQLParser#primaryExprPredicate.
    def exitPrimaryExprPredicate(self, ctx:MySQLParser.PrimaryExprPredicateContext):
        pass


    # Enter a parse tree produced by MySQLParser#primaryExprCompare.
    def enterPrimaryExprCompare(self, ctx:MySQLParser.PrimaryExprCompareContext):
        pass

    # Exit a parse tree produced by MySQLParser#primaryExprCompare.
    def exitPrimaryExprCompare(self, ctx:MySQLParser.PrimaryExprCompareContext):
        pass


    # Enter a parse tree produced by MySQLParser#primaryExprAllAny.
    def enterPrimaryExprAllAny(self, ctx:MySQLParser.PrimaryExprAllAnyContext):
        pass

    # Exit a parse tree produced by MySQLParser#primaryExprAllAny.
    def exitPrimaryExprAllAny(self, ctx:MySQLParser.PrimaryExprAllAnyContext):
        pass


    # Enter a parse tree produced by MySQLParser#primaryExprIsNull.
    def enterPrimaryExprIsNull(self, ctx:MySQLParser.PrimaryExprIsNullContext):
        pass

    # Exit a parse tree produced by MySQLParser#primaryExprIsNull.
    def exitPrimaryExprIsNull(self, ctx:MySQLParser.PrimaryExprIsNullContext):
        pass


    # Enter a parse tree produced by MySQLParser#compOp.
    def enterCompOp(self, ctx:MySQLParser.CompOpContext):
        pass

    # Exit a parse tree produced by MySQLParser#compOp.
    def exitCompOp(self, ctx:MySQLParser.CompOpContext):
        pass


    # Enter a parse tree produced by MySQLParser#predicate.
    def enterPredicate(self, ctx:MySQLParser.PredicateContext):
        pass

    # Exit a parse tree produced by MySQLParser#predicate.
    def exitPredicate(self, ctx:MySQLParser.PredicateContext):
        pass


    # Enter a parse tree produced by MySQLParser#predicateExprIn.
    def enterPredicateExprIn(self, ctx:MySQLParser.PredicateExprInContext):
        pass

    # Exit a parse tree produced by MySQLParser#predicateExprIn.
    def exitPredicateExprIn(self, ctx:MySQLParser.PredicateExprInContext):
        pass


    # Enter a parse tree produced by MySQLParser#predicateExprBetween.
    def enterPredicateExprBetween(self, ctx:MySQLParser.PredicateExprBetweenContext):
        pass

    # Exit a parse tree produced by MySQLParser#predicateExprBetween.
    def exitPredicateExprBetween(self, ctx:MySQLParser.PredicateExprBetweenContext):
        pass


    # Enter a parse tree produced by MySQLParser#predicateExprLike.
    def enterPredicateExprLike(self, ctx:MySQLParser.PredicateExprLikeContext):
        pass

    # Exit a parse tree produced by MySQLParser#predicateExprLike.
    def exitPredicateExprLike(self, ctx:MySQLParser.PredicateExprLikeContext):
        pass


    # Enter a parse tree produced by MySQLParser#predicateExprRegex.
    def enterPredicateExprRegex(self, ctx:MySQLParser.PredicateExprRegexContext):
        pass

    # Exit a parse tree produced by MySQLParser#predicateExprRegex.
    def exitPredicateExprRegex(self, ctx:MySQLParser.PredicateExprRegexContext):
        pass


    # Enter a parse tree produced by MySQLParser#bitExpr.
    def enterBitExpr(self, ctx:MySQLParser.BitExprContext):
        pass

    # Exit a parse tree produced by MySQLParser#bitExpr.
    def exitBitExpr(self, ctx:MySQLParser.BitExprContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprConvert.
    def enterSimpleExprConvert(self, ctx:MySQLParser.SimpleExprConvertContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprConvert.
    def exitSimpleExprConvert(self, ctx:MySQLParser.SimpleExprConvertContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprVariable.
    def enterSimpleExprVariable(self, ctx:MySQLParser.SimpleExprVariableContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprVariable.
    def exitSimpleExprVariable(self, ctx:MySQLParser.SimpleExprVariableContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprCast.
    def enterSimpleExprCast(self, ctx:MySQLParser.SimpleExprCastContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprCast.
    def exitSimpleExprCast(self, ctx:MySQLParser.SimpleExprCastContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprUnary.
    def enterSimpleExprUnary(self, ctx:MySQLParser.SimpleExprUnaryContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprUnary.
    def exitSimpleExprUnary(self, ctx:MySQLParser.SimpleExprUnaryContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprOdbc.
    def enterSimpleExprOdbc(self, ctx:MySQLParser.SimpleExprOdbcContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprOdbc.
    def exitSimpleExprOdbc(self, ctx:MySQLParser.SimpleExprOdbcContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprRuntimeFunction.
    def enterSimpleExprRuntimeFunction(self, ctx:MySQLParser.SimpleExprRuntimeFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprRuntimeFunction.
    def exitSimpleExprRuntimeFunction(self, ctx:MySQLParser.SimpleExprRuntimeFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprFunction.
    def enterSimpleExprFunction(self, ctx:MySQLParser.SimpleExprFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprFunction.
    def exitSimpleExprFunction(self, ctx:MySQLParser.SimpleExprFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprCollate.
    def enterSimpleExprCollate(self, ctx:MySQLParser.SimpleExprCollateContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprCollate.
    def exitSimpleExprCollate(self, ctx:MySQLParser.SimpleExprCollateContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprMatch.
    def enterSimpleExprMatch(self, ctx:MySQLParser.SimpleExprMatchContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprMatch.
    def exitSimpleExprMatch(self, ctx:MySQLParser.SimpleExprMatchContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprWindowingFunction.
    def enterSimpleExprWindowingFunction(self, ctx:MySQLParser.SimpleExprWindowingFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprWindowingFunction.
    def exitSimpleExprWindowingFunction(self, ctx:MySQLParser.SimpleExprWindowingFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprBinary.
    def enterSimpleExprBinary(self, ctx:MySQLParser.SimpleExprBinaryContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprBinary.
    def exitSimpleExprBinary(self, ctx:MySQLParser.SimpleExprBinaryContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprColumnRef.
    def enterSimpleExprColumnRef(self, ctx:MySQLParser.SimpleExprColumnRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprColumnRef.
    def exitSimpleExprColumnRef(self, ctx:MySQLParser.SimpleExprColumnRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprParamMarker.
    def enterSimpleExprParamMarker(self, ctx:MySQLParser.SimpleExprParamMarkerContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprParamMarker.
    def exitSimpleExprParamMarker(self, ctx:MySQLParser.SimpleExprParamMarkerContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprSum.
    def enterSimpleExprSum(self, ctx:MySQLParser.SimpleExprSumContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprSum.
    def exitSimpleExprSum(self, ctx:MySQLParser.SimpleExprSumContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprConvertUsing.
    def enterSimpleExprConvertUsing(self, ctx:MySQLParser.SimpleExprConvertUsingContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprConvertUsing.
    def exitSimpleExprConvertUsing(self, ctx:MySQLParser.SimpleExprConvertUsingContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprSubQuery.
    def enterSimpleExprSubQuery(self, ctx:MySQLParser.SimpleExprSubQueryContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprSubQuery.
    def exitSimpleExprSubQuery(self, ctx:MySQLParser.SimpleExprSubQueryContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprGroupingOperation.
    def enterSimpleExprGroupingOperation(self, ctx:MySQLParser.SimpleExprGroupingOperationContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprGroupingOperation.
    def exitSimpleExprGroupingOperation(self, ctx:MySQLParser.SimpleExprGroupingOperationContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprNot.
    def enterSimpleExprNot(self, ctx:MySQLParser.SimpleExprNotContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprNot.
    def exitSimpleExprNot(self, ctx:MySQLParser.SimpleExprNotContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprValues.
    def enterSimpleExprValues(self, ctx:MySQLParser.SimpleExprValuesContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprValues.
    def exitSimpleExprValues(self, ctx:MySQLParser.SimpleExprValuesContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprDefault.
    def enterSimpleExprDefault(self, ctx:MySQLParser.SimpleExprDefaultContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprDefault.
    def exitSimpleExprDefault(self, ctx:MySQLParser.SimpleExprDefaultContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprList.
    def enterSimpleExprList(self, ctx:MySQLParser.SimpleExprListContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprList.
    def exitSimpleExprList(self, ctx:MySQLParser.SimpleExprListContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprInterval.
    def enterSimpleExprInterval(self, ctx:MySQLParser.SimpleExprIntervalContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprInterval.
    def exitSimpleExprInterval(self, ctx:MySQLParser.SimpleExprIntervalContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprCase.
    def enterSimpleExprCase(self, ctx:MySQLParser.SimpleExprCaseContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprCase.
    def exitSimpleExprCase(self, ctx:MySQLParser.SimpleExprCaseContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprConcat.
    def enterSimpleExprConcat(self, ctx:MySQLParser.SimpleExprConcatContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprConcat.
    def exitSimpleExprConcat(self, ctx:MySQLParser.SimpleExprConcatContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprLiteral.
    def enterSimpleExprLiteral(self, ctx:MySQLParser.SimpleExprLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprLiteral.
    def exitSimpleExprLiteral(self, ctx:MySQLParser.SimpleExprLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#arrayCast.
    def enterArrayCast(self, ctx:MySQLParser.ArrayCastContext):
        pass

    # Exit a parse tree produced by MySQLParser#arrayCast.
    def exitArrayCast(self, ctx:MySQLParser.ArrayCastContext):
        pass


    # Enter a parse tree produced by MySQLParser#jsonOperator.
    def enterJsonOperator(self, ctx:MySQLParser.JsonOperatorContext):
        pass

    # Exit a parse tree produced by MySQLParser#jsonOperator.
    def exitJsonOperator(self, ctx:MySQLParser.JsonOperatorContext):
        pass


    # Enter a parse tree produced by MySQLParser#sumExpr.
    def enterSumExpr(self, ctx:MySQLParser.SumExprContext):
        pass

    # Exit a parse tree produced by MySQLParser#sumExpr.
    def exitSumExpr(self, ctx:MySQLParser.SumExprContext):
        pass


    # Enter a parse tree produced by MySQLParser#groupingOperation.
    def enterGroupingOperation(self, ctx:MySQLParser.GroupingOperationContext):
        pass

    # Exit a parse tree produced by MySQLParser#groupingOperation.
    def exitGroupingOperation(self, ctx:MySQLParser.GroupingOperationContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowFunctionCall.
    def enterWindowFunctionCall(self, ctx:MySQLParser.WindowFunctionCallContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowFunctionCall.
    def exitWindowFunctionCall(self, ctx:MySQLParser.WindowFunctionCallContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowingClause.
    def enterWindowingClause(self, ctx:MySQLParser.WindowingClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowingClause.
    def exitWindowingClause(self, ctx:MySQLParser.WindowingClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#leadLagInfo.
    def enterLeadLagInfo(self, ctx:MySQLParser.LeadLagInfoContext):
        pass

    # Exit a parse tree produced by MySQLParser#leadLagInfo.
    def exitLeadLagInfo(self, ctx:MySQLParser.LeadLagInfoContext):
        pass


    # Enter a parse tree produced by MySQLParser#nullTreatment.
    def enterNullTreatment(self, ctx:MySQLParser.NullTreatmentContext):
        pass

    # Exit a parse tree produced by MySQLParser#nullTreatment.
    def exitNullTreatment(self, ctx:MySQLParser.NullTreatmentContext):
        pass


    # Enter a parse tree produced by MySQLParser#jsonFunction.
    def enterJsonFunction(self, ctx:MySQLParser.JsonFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#jsonFunction.
    def exitJsonFunction(self, ctx:MySQLParser.JsonFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#inSumExpr.
    def enterInSumExpr(self, ctx:MySQLParser.InSumExprContext):
        pass

    # Exit a parse tree produced by MySQLParser#inSumExpr.
    def exitInSumExpr(self, ctx:MySQLParser.InSumExprContext):
        pass


    # Enter a parse tree produced by MySQLParser#identListArg.
    def enterIdentListArg(self, ctx:MySQLParser.IdentListArgContext):
        pass

    # Exit a parse tree produced by MySQLParser#identListArg.
    def exitIdentListArg(self, ctx:MySQLParser.IdentListArgContext):
        pass


    # Enter a parse tree produced by MySQLParser#identList.
    def enterIdentList(self, ctx:MySQLParser.IdentListContext):
        pass

    # Exit a parse tree produced by MySQLParser#identList.
    def exitIdentList(self, ctx:MySQLParser.IdentListContext):
        pass


    # Enter a parse tree produced by MySQLParser#fulltextOptions.
    def enterFulltextOptions(self, ctx:MySQLParser.FulltextOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#fulltextOptions.
    def exitFulltextOptions(self, ctx:MySQLParser.FulltextOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#runtimeFunctionCall.
    def enterRuntimeFunctionCall(self, ctx:MySQLParser.RuntimeFunctionCallContext):
        pass

    # Exit a parse tree produced by MySQLParser#runtimeFunctionCall.
    def exitRuntimeFunctionCall(self, ctx:MySQLParser.RuntimeFunctionCallContext):
        pass


    # Enter a parse tree produced by MySQLParser#geometryFunction.
    def enterGeometryFunction(self, ctx:MySQLParser.GeometryFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#geometryFunction.
    def exitGeometryFunction(self, ctx:MySQLParser.GeometryFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#timeFunctionParameters.
    def enterTimeFunctionParameters(self, ctx:MySQLParser.TimeFunctionParametersContext):
        pass

    # Exit a parse tree produced by MySQLParser#timeFunctionParameters.
    def exitTimeFunctionParameters(self, ctx:MySQLParser.TimeFunctionParametersContext):
        pass


    # Enter a parse tree produced by MySQLParser#fractionalPrecision.
    def enterFractionalPrecision(self, ctx:MySQLParser.FractionalPrecisionContext):
        pass

    # Exit a parse tree produced by MySQLParser#fractionalPrecision.
    def exitFractionalPrecision(self, ctx:MySQLParser.FractionalPrecisionContext):
        pass


    # Enter a parse tree produced by MySQLParser#weightStringLevels.
    def enterWeightStringLevels(self, ctx:MySQLParser.WeightStringLevelsContext):
        pass

    # Exit a parse tree produced by MySQLParser#weightStringLevels.
    def exitWeightStringLevels(self, ctx:MySQLParser.WeightStringLevelsContext):
        pass


    # Enter a parse tree produced by MySQLParser#weightStringLevelListItem.
    def enterWeightStringLevelListItem(self, ctx:MySQLParser.WeightStringLevelListItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#weightStringLevelListItem.
    def exitWeightStringLevelListItem(self, ctx:MySQLParser.WeightStringLevelListItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#dateTimeTtype.
    def enterDateTimeTtype(self, ctx:MySQLParser.DateTimeTtypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#dateTimeTtype.
    def exitDateTimeTtype(self, ctx:MySQLParser.DateTimeTtypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#trimFunction.
    def enterTrimFunction(self, ctx:MySQLParser.TrimFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#trimFunction.
    def exitTrimFunction(self, ctx:MySQLParser.TrimFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#substringFunction.
    def enterSubstringFunction(self, ctx:MySQLParser.SubstringFunctionContext):
        pass

    # Exit a parse tree produced by MySQLParser#substringFunction.
    def exitSubstringFunction(self, ctx:MySQLParser.SubstringFunctionContext):
        pass


    # Enter a parse tree produced by MySQLParser#functionCall.
    def enterFunctionCall(self, ctx:MySQLParser.FunctionCallContext):
        pass

    # Exit a parse tree produced by MySQLParser#functionCall.
    def exitFunctionCall(self, ctx:MySQLParser.FunctionCallContext):
        pass


    # Enter a parse tree produced by MySQLParser#udfExprList.
    def enterUdfExprList(self, ctx:MySQLParser.UdfExprListContext):
        pass

    # Exit a parse tree produced by MySQLParser#udfExprList.
    def exitUdfExprList(self, ctx:MySQLParser.UdfExprListContext):
        pass


    # Enter a parse tree produced by MySQLParser#udfExpr.
    def enterUdfExpr(self, ctx:MySQLParser.UdfExprContext):
        pass

    # Exit a parse tree produced by MySQLParser#udfExpr.
    def exitUdfExpr(self, ctx:MySQLParser.UdfExprContext):
        pass


    # Enter a parse tree produced by MySQLParser#variable.
    def enterVariable(self, ctx:MySQLParser.VariableContext):
        pass

    # Exit a parse tree produced by MySQLParser#variable.
    def exitVariable(self, ctx:MySQLParser.VariableContext):
        pass


    # Enter a parse tree produced by MySQLParser#userVariable.
    def enterUserVariable(self, ctx:MySQLParser.UserVariableContext):
        pass

    # Exit a parse tree produced by MySQLParser#userVariable.
    def exitUserVariable(self, ctx:MySQLParser.UserVariableContext):
        pass


    # Enter a parse tree produced by MySQLParser#systemVariable.
    def enterSystemVariable(self, ctx:MySQLParser.SystemVariableContext):
        pass

    # Exit a parse tree produced by MySQLParser#systemVariable.
    def exitSystemVariable(self, ctx:MySQLParser.SystemVariableContext):
        pass


    # Enter a parse tree produced by MySQLParser#internalVariableName.
    def enterInternalVariableName(self, ctx:MySQLParser.InternalVariableNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#internalVariableName.
    def exitInternalVariableName(self, ctx:MySQLParser.InternalVariableNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#whenExpression.
    def enterWhenExpression(self, ctx:MySQLParser.WhenExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#whenExpression.
    def exitWhenExpression(self, ctx:MySQLParser.WhenExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#thenExpression.
    def enterThenExpression(self, ctx:MySQLParser.ThenExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#thenExpression.
    def exitThenExpression(self, ctx:MySQLParser.ThenExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#elseExpression.
    def enterElseExpression(self, ctx:MySQLParser.ElseExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#elseExpression.
    def exitElseExpression(self, ctx:MySQLParser.ElseExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#castType.
    def enterCastType(self, ctx:MySQLParser.CastTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#castType.
    def exitCastType(self, ctx:MySQLParser.CastTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprList.
    def enterExprList(self, ctx:MySQLParser.ExprListContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprList.
    def exitExprList(self, ctx:MySQLParser.ExprListContext):
        pass


    # Enter a parse tree produced by MySQLParser#charset.
    def enterCharset(self, ctx:MySQLParser.CharsetContext):
        pass

    # Exit a parse tree produced by MySQLParser#charset.
    def exitCharset(self, ctx:MySQLParser.CharsetContext):
        pass


    # Enter a parse tree produced by MySQLParser#notRule.
    def enterNotRule(self, ctx:MySQLParser.NotRuleContext):
        pass

    # Exit a parse tree produced by MySQLParser#notRule.
    def exitNotRule(self, ctx:MySQLParser.NotRuleContext):
        pass


    # Enter a parse tree produced by MySQLParser#not2Rule.
    def enterNot2Rule(self, ctx:MySQLParser.Not2RuleContext):
        pass

    # Exit a parse tree produced by MySQLParser#not2Rule.
    def exitNot2Rule(self, ctx:MySQLParser.Not2RuleContext):
        pass


    # Enter a parse tree produced by MySQLParser#interval.
    def enterInterval(self, ctx:MySQLParser.IntervalContext):
        pass

    # Exit a parse tree produced by MySQLParser#interval.
    def exitInterval(self, ctx:MySQLParser.IntervalContext):
        pass


    # Enter a parse tree produced by MySQLParser#intervalTimeStamp.
    def enterIntervalTimeStamp(self, ctx:MySQLParser.IntervalTimeStampContext):
        pass

    # Exit a parse tree produced by MySQLParser#intervalTimeStamp.
    def exitIntervalTimeStamp(self, ctx:MySQLParser.IntervalTimeStampContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprListWithParentheses.
    def enterExprListWithParentheses(self, ctx:MySQLParser.ExprListWithParenthesesContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprListWithParentheses.
    def exitExprListWithParentheses(self, ctx:MySQLParser.ExprListWithParenthesesContext):
        pass


    # Enter a parse tree produced by MySQLParser#exprWithParentheses.
    def enterExprWithParentheses(self, ctx:MySQLParser.ExprWithParenthesesContext):
        pass

    # Exit a parse tree produced by MySQLParser#exprWithParentheses.
    def exitExprWithParentheses(self, ctx:MySQLParser.ExprWithParenthesesContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleExprWithParentheses.
    def enterSimpleExprWithParentheses(self, ctx:MySQLParser.SimpleExprWithParenthesesContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleExprWithParentheses.
    def exitSimpleExprWithParentheses(self, ctx:MySQLParser.SimpleExprWithParenthesesContext):
        pass


    # Enter a parse tree produced by MySQLParser#orderList.
    def enterOrderList(self, ctx:MySQLParser.OrderListContext):
        pass

    # Exit a parse tree produced by MySQLParser#orderList.
    def exitOrderList(self, ctx:MySQLParser.OrderListContext):
        pass


    # Enter a parse tree produced by MySQLParser#orderExpression.
    def enterOrderExpression(self, ctx:MySQLParser.OrderExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#orderExpression.
    def exitOrderExpression(self, ctx:MySQLParser.OrderExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#groupList.
    def enterGroupList(self, ctx:MySQLParser.GroupListContext):
        pass

    # Exit a parse tree produced by MySQLParser#groupList.
    def exitGroupList(self, ctx:MySQLParser.GroupListContext):
        pass


    # Enter a parse tree produced by MySQLParser#groupingExpression.
    def enterGroupingExpression(self, ctx:MySQLParser.GroupingExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#groupingExpression.
    def exitGroupingExpression(self, ctx:MySQLParser.GroupingExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#channel.
    def enterChannel(self, ctx:MySQLParser.ChannelContext):
        pass

    # Exit a parse tree produced by MySQLParser#channel.
    def exitChannel(self, ctx:MySQLParser.ChannelContext):
        pass


    # Enter a parse tree produced by MySQLParser#compoundStatement.
    def enterCompoundStatement(self, ctx:MySQLParser.CompoundStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#compoundStatement.
    def exitCompoundStatement(self, ctx:MySQLParser.CompoundStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#returnStatement.
    def enterReturnStatement(self, ctx:MySQLParser.ReturnStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#returnStatement.
    def exitReturnStatement(self, ctx:MySQLParser.ReturnStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#ifStatement.
    def enterIfStatement(self, ctx:MySQLParser.IfStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#ifStatement.
    def exitIfStatement(self, ctx:MySQLParser.IfStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#ifBody.
    def enterIfBody(self, ctx:MySQLParser.IfBodyContext):
        pass

    # Exit a parse tree produced by MySQLParser#ifBody.
    def exitIfBody(self, ctx:MySQLParser.IfBodyContext):
        pass


    # Enter a parse tree produced by MySQLParser#thenStatement.
    def enterThenStatement(self, ctx:MySQLParser.ThenStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#thenStatement.
    def exitThenStatement(self, ctx:MySQLParser.ThenStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#compoundStatementList.
    def enterCompoundStatementList(self, ctx:MySQLParser.CompoundStatementListContext):
        pass

    # Exit a parse tree produced by MySQLParser#compoundStatementList.
    def exitCompoundStatementList(self, ctx:MySQLParser.CompoundStatementListContext):
        pass


    # Enter a parse tree produced by MySQLParser#caseStatement.
    def enterCaseStatement(self, ctx:MySQLParser.CaseStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#caseStatement.
    def exitCaseStatement(self, ctx:MySQLParser.CaseStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#elseStatement.
    def enterElseStatement(self, ctx:MySQLParser.ElseStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#elseStatement.
    def exitElseStatement(self, ctx:MySQLParser.ElseStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#labeledBlock.
    def enterLabeledBlock(self, ctx:MySQLParser.LabeledBlockContext):
        pass

    # Exit a parse tree produced by MySQLParser#labeledBlock.
    def exitLabeledBlock(self, ctx:MySQLParser.LabeledBlockContext):
        pass


    # Enter a parse tree produced by MySQLParser#unlabeledBlock.
    def enterUnlabeledBlock(self, ctx:MySQLParser.UnlabeledBlockContext):
        pass

    # Exit a parse tree produced by MySQLParser#unlabeledBlock.
    def exitUnlabeledBlock(self, ctx:MySQLParser.UnlabeledBlockContext):
        pass


    # Enter a parse tree produced by MySQLParser#label.
    def enterLabel(self, ctx:MySQLParser.LabelContext):
        pass

    # Exit a parse tree produced by MySQLParser#label.
    def exitLabel(self, ctx:MySQLParser.LabelContext):
        pass


    # Enter a parse tree produced by MySQLParser#beginEndBlock.
    def enterBeginEndBlock(self, ctx:MySQLParser.BeginEndBlockContext):
        pass

    # Exit a parse tree produced by MySQLParser#beginEndBlock.
    def exitBeginEndBlock(self, ctx:MySQLParser.BeginEndBlockContext):
        pass


    # Enter a parse tree produced by MySQLParser#labeledControl.
    def enterLabeledControl(self, ctx:MySQLParser.LabeledControlContext):
        pass

    # Exit a parse tree produced by MySQLParser#labeledControl.
    def exitLabeledControl(self, ctx:MySQLParser.LabeledControlContext):
        pass


    # Enter a parse tree produced by MySQLParser#unlabeledControl.
    def enterUnlabeledControl(self, ctx:MySQLParser.UnlabeledControlContext):
        pass

    # Exit a parse tree produced by MySQLParser#unlabeledControl.
    def exitUnlabeledControl(self, ctx:MySQLParser.UnlabeledControlContext):
        pass


    # Enter a parse tree produced by MySQLParser#loopBlock.
    def enterLoopBlock(self, ctx:MySQLParser.LoopBlockContext):
        pass

    # Exit a parse tree produced by MySQLParser#loopBlock.
    def exitLoopBlock(self, ctx:MySQLParser.LoopBlockContext):
        pass


    # Enter a parse tree produced by MySQLParser#whileDoBlock.
    def enterWhileDoBlock(self, ctx:MySQLParser.WhileDoBlockContext):
        pass

    # Exit a parse tree produced by MySQLParser#whileDoBlock.
    def exitWhileDoBlock(self, ctx:MySQLParser.WhileDoBlockContext):
        pass


    # Enter a parse tree produced by MySQLParser#repeatUntilBlock.
    def enterRepeatUntilBlock(self, ctx:MySQLParser.RepeatUntilBlockContext):
        pass

    # Exit a parse tree produced by MySQLParser#repeatUntilBlock.
    def exitRepeatUntilBlock(self, ctx:MySQLParser.RepeatUntilBlockContext):
        pass


    # Enter a parse tree produced by MySQLParser#spDeclarations.
    def enterSpDeclarations(self, ctx:MySQLParser.SpDeclarationsContext):
        pass

    # Exit a parse tree produced by MySQLParser#spDeclarations.
    def exitSpDeclarations(self, ctx:MySQLParser.SpDeclarationsContext):
        pass


    # Enter a parse tree produced by MySQLParser#spDeclaration.
    def enterSpDeclaration(self, ctx:MySQLParser.SpDeclarationContext):
        pass

    # Exit a parse tree produced by MySQLParser#spDeclaration.
    def exitSpDeclaration(self, ctx:MySQLParser.SpDeclarationContext):
        pass


    # Enter a parse tree produced by MySQLParser#variableDeclaration.
    def enterVariableDeclaration(self, ctx:MySQLParser.VariableDeclarationContext):
        pass

    # Exit a parse tree produced by MySQLParser#variableDeclaration.
    def exitVariableDeclaration(self, ctx:MySQLParser.VariableDeclarationContext):
        pass


    # Enter a parse tree produced by MySQLParser#conditionDeclaration.
    def enterConditionDeclaration(self, ctx:MySQLParser.ConditionDeclarationContext):
        pass

    # Exit a parse tree produced by MySQLParser#conditionDeclaration.
    def exitConditionDeclaration(self, ctx:MySQLParser.ConditionDeclarationContext):
        pass


    # Enter a parse tree produced by MySQLParser#spCondition.
    def enterSpCondition(self, ctx:MySQLParser.SpConditionContext):
        pass

    # Exit a parse tree produced by MySQLParser#spCondition.
    def exitSpCondition(self, ctx:MySQLParser.SpConditionContext):
        pass


    # Enter a parse tree produced by MySQLParser#sqlstate.
    def enterSqlstate(self, ctx:MySQLParser.SqlstateContext):
        pass

    # Exit a parse tree produced by MySQLParser#sqlstate.
    def exitSqlstate(self, ctx:MySQLParser.SqlstateContext):
        pass


    # Enter a parse tree produced by MySQLParser#handlerDeclaration.
    def enterHandlerDeclaration(self, ctx:MySQLParser.HandlerDeclarationContext):
        pass

    # Exit a parse tree produced by MySQLParser#handlerDeclaration.
    def exitHandlerDeclaration(self, ctx:MySQLParser.HandlerDeclarationContext):
        pass


    # Enter a parse tree produced by MySQLParser#handlerCondition.
    def enterHandlerCondition(self, ctx:MySQLParser.HandlerConditionContext):
        pass

    # Exit a parse tree produced by MySQLParser#handlerCondition.
    def exitHandlerCondition(self, ctx:MySQLParser.HandlerConditionContext):
        pass


    # Enter a parse tree produced by MySQLParser#cursorDeclaration.
    def enterCursorDeclaration(self, ctx:MySQLParser.CursorDeclarationContext):
        pass

    # Exit a parse tree produced by MySQLParser#cursorDeclaration.
    def exitCursorDeclaration(self, ctx:MySQLParser.CursorDeclarationContext):
        pass


    # Enter a parse tree produced by MySQLParser#iterateStatement.
    def enterIterateStatement(self, ctx:MySQLParser.IterateStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#iterateStatement.
    def exitIterateStatement(self, ctx:MySQLParser.IterateStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#leaveStatement.
    def enterLeaveStatement(self, ctx:MySQLParser.LeaveStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#leaveStatement.
    def exitLeaveStatement(self, ctx:MySQLParser.LeaveStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#getDiagnostics.
    def enterGetDiagnostics(self, ctx:MySQLParser.GetDiagnosticsContext):
        pass

    # Exit a parse tree produced by MySQLParser#getDiagnostics.
    def exitGetDiagnostics(self, ctx:MySQLParser.GetDiagnosticsContext):
        pass


    # Enter a parse tree produced by MySQLParser#signalAllowedExpr.
    def enterSignalAllowedExpr(self, ctx:MySQLParser.SignalAllowedExprContext):
        pass

    # Exit a parse tree produced by MySQLParser#signalAllowedExpr.
    def exitSignalAllowedExpr(self, ctx:MySQLParser.SignalAllowedExprContext):
        pass


    # Enter a parse tree produced by MySQLParser#statementInformationItem.
    def enterStatementInformationItem(self, ctx:MySQLParser.StatementInformationItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#statementInformationItem.
    def exitStatementInformationItem(self, ctx:MySQLParser.StatementInformationItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#conditionInformationItem.
    def enterConditionInformationItem(self, ctx:MySQLParser.ConditionInformationItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#conditionInformationItem.
    def exitConditionInformationItem(self, ctx:MySQLParser.ConditionInformationItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#signalInformationItemName.
    def enterSignalInformationItemName(self, ctx:MySQLParser.SignalInformationItemNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#signalInformationItemName.
    def exitSignalInformationItemName(self, ctx:MySQLParser.SignalInformationItemNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#signalStatement.
    def enterSignalStatement(self, ctx:MySQLParser.SignalStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#signalStatement.
    def exitSignalStatement(self, ctx:MySQLParser.SignalStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#resignalStatement.
    def enterResignalStatement(self, ctx:MySQLParser.ResignalStatementContext):
        pass

    # Exit a parse tree produced by MySQLParser#resignalStatement.
    def exitResignalStatement(self, ctx:MySQLParser.ResignalStatementContext):
        pass


    # Enter a parse tree produced by MySQLParser#signalInformationItem.
    def enterSignalInformationItem(self, ctx:MySQLParser.SignalInformationItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#signalInformationItem.
    def exitSignalInformationItem(self, ctx:MySQLParser.SignalInformationItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#cursorOpen.
    def enterCursorOpen(self, ctx:MySQLParser.CursorOpenContext):
        pass

    # Exit a parse tree produced by MySQLParser#cursorOpen.
    def exitCursorOpen(self, ctx:MySQLParser.CursorOpenContext):
        pass


    # Enter a parse tree produced by MySQLParser#cursorClose.
    def enterCursorClose(self, ctx:MySQLParser.CursorCloseContext):
        pass

    # Exit a parse tree produced by MySQLParser#cursorClose.
    def exitCursorClose(self, ctx:MySQLParser.CursorCloseContext):
        pass


    # Enter a parse tree produced by MySQLParser#cursorFetch.
    def enterCursorFetch(self, ctx:MySQLParser.CursorFetchContext):
        pass

    # Exit a parse tree produced by MySQLParser#cursorFetch.
    def exitCursorFetch(self, ctx:MySQLParser.CursorFetchContext):
        pass


    # Enter a parse tree produced by MySQLParser#schedule.
    def enterSchedule(self, ctx:MySQLParser.ScheduleContext):
        pass

    # Exit a parse tree produced by MySQLParser#schedule.
    def exitSchedule(self, ctx:MySQLParser.ScheduleContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnDefinition.
    def enterColumnDefinition(self, ctx:MySQLParser.ColumnDefinitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnDefinition.
    def exitColumnDefinition(self, ctx:MySQLParser.ColumnDefinitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#checkOrReferences.
    def enterCheckOrReferences(self, ctx:MySQLParser.CheckOrReferencesContext):
        pass

    # Exit a parse tree produced by MySQLParser#checkOrReferences.
    def exitCheckOrReferences(self, ctx:MySQLParser.CheckOrReferencesContext):
        pass


    # Enter a parse tree produced by MySQLParser#checkConstraint.
    def enterCheckConstraint(self, ctx:MySQLParser.CheckConstraintContext):
        pass

    # Exit a parse tree produced by MySQLParser#checkConstraint.
    def exitCheckConstraint(self, ctx:MySQLParser.CheckConstraintContext):
        pass


    # Enter a parse tree produced by MySQLParser#constraintEnforcement.
    def enterConstraintEnforcement(self, ctx:MySQLParser.ConstraintEnforcementContext):
        pass

    # Exit a parse tree produced by MySQLParser#constraintEnforcement.
    def exitConstraintEnforcement(self, ctx:MySQLParser.ConstraintEnforcementContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableConstraintDef.
    def enterTableConstraintDef(self, ctx:MySQLParser.TableConstraintDefContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableConstraintDef.
    def exitTableConstraintDef(self, ctx:MySQLParser.TableConstraintDefContext):
        pass


    # Enter a parse tree produced by MySQLParser#constraintName.
    def enterConstraintName(self, ctx:MySQLParser.ConstraintNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#constraintName.
    def exitConstraintName(self, ctx:MySQLParser.ConstraintNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#fieldDefinition.
    def enterFieldDefinition(self, ctx:MySQLParser.FieldDefinitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#fieldDefinition.
    def exitFieldDefinition(self, ctx:MySQLParser.FieldDefinitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnAttribute.
    def enterColumnAttribute(self, ctx:MySQLParser.ColumnAttributeContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnAttribute.
    def exitColumnAttribute(self, ctx:MySQLParser.ColumnAttributeContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnFormat.
    def enterColumnFormat(self, ctx:MySQLParser.ColumnFormatContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnFormat.
    def exitColumnFormat(self, ctx:MySQLParser.ColumnFormatContext):
        pass


    # Enter a parse tree produced by MySQLParser#storageMedia.
    def enterStorageMedia(self, ctx:MySQLParser.StorageMediaContext):
        pass

    # Exit a parse tree produced by MySQLParser#storageMedia.
    def exitStorageMedia(self, ctx:MySQLParser.StorageMediaContext):
        pass


    # Enter a parse tree produced by MySQLParser#gcolAttribute.
    def enterGcolAttribute(self, ctx:MySQLParser.GcolAttributeContext):
        pass

    # Exit a parse tree produced by MySQLParser#gcolAttribute.
    def exitGcolAttribute(self, ctx:MySQLParser.GcolAttributeContext):
        pass


    # Enter a parse tree produced by MySQLParser#references.
    def enterReferences(self, ctx:MySQLParser.ReferencesContext):
        pass

    # Exit a parse tree produced by MySQLParser#references.
    def exitReferences(self, ctx:MySQLParser.ReferencesContext):
        pass


    # Enter a parse tree produced by MySQLParser#deleteOption.
    def enterDeleteOption(self, ctx:MySQLParser.DeleteOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#deleteOption.
    def exitDeleteOption(self, ctx:MySQLParser.DeleteOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyList.
    def enterKeyList(self, ctx:MySQLParser.KeyListContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyList.
    def exitKeyList(self, ctx:MySQLParser.KeyListContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyPart.
    def enterKeyPart(self, ctx:MySQLParser.KeyPartContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyPart.
    def exitKeyPart(self, ctx:MySQLParser.KeyPartContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyListWithExpression.
    def enterKeyListWithExpression(self, ctx:MySQLParser.KeyListWithExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyListWithExpression.
    def exitKeyListWithExpression(self, ctx:MySQLParser.KeyListWithExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyPartOrExpression.
    def enterKeyPartOrExpression(self, ctx:MySQLParser.KeyPartOrExpressionContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyPartOrExpression.
    def exitKeyPartOrExpression(self, ctx:MySQLParser.KeyPartOrExpressionContext):
        pass


    # Enter a parse tree produced by MySQLParser#keyListVariants.
    def enterKeyListVariants(self, ctx:MySQLParser.KeyListVariantsContext):
        pass

    # Exit a parse tree produced by MySQLParser#keyListVariants.
    def exitKeyListVariants(self, ctx:MySQLParser.KeyListVariantsContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexType.
    def enterIndexType(self, ctx:MySQLParser.IndexTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexType.
    def exitIndexType(self, ctx:MySQLParser.IndexTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexOption.
    def enterIndexOption(self, ctx:MySQLParser.IndexOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexOption.
    def exitIndexOption(self, ctx:MySQLParser.IndexOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#commonIndexOption.
    def enterCommonIndexOption(self, ctx:MySQLParser.CommonIndexOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#commonIndexOption.
    def exitCommonIndexOption(self, ctx:MySQLParser.CommonIndexOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#visibility.
    def enterVisibility(self, ctx:MySQLParser.VisibilityContext):
        pass

    # Exit a parse tree produced by MySQLParser#visibility.
    def exitVisibility(self, ctx:MySQLParser.VisibilityContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexTypeClause.
    def enterIndexTypeClause(self, ctx:MySQLParser.IndexTypeClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexTypeClause.
    def exitIndexTypeClause(self, ctx:MySQLParser.IndexTypeClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#fulltextIndexOption.
    def enterFulltextIndexOption(self, ctx:MySQLParser.FulltextIndexOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#fulltextIndexOption.
    def exitFulltextIndexOption(self, ctx:MySQLParser.FulltextIndexOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#spatialIndexOption.
    def enterSpatialIndexOption(self, ctx:MySQLParser.SpatialIndexOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#spatialIndexOption.
    def exitSpatialIndexOption(self, ctx:MySQLParser.SpatialIndexOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#dataTypeDefinition.
    def enterDataTypeDefinition(self, ctx:MySQLParser.DataTypeDefinitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#dataTypeDefinition.
    def exitDataTypeDefinition(self, ctx:MySQLParser.DataTypeDefinitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#dataType.
    def enterDataType(self, ctx:MySQLParser.DataTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#dataType.
    def exitDataType(self, ctx:MySQLParser.DataTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#nchar.
    def enterNchar(self, ctx:MySQLParser.NcharContext):
        pass

    # Exit a parse tree produced by MySQLParser#nchar.
    def exitNchar(self, ctx:MySQLParser.NcharContext):
        pass


    # Enter a parse tree produced by MySQLParser#realType.
    def enterRealType(self, ctx:MySQLParser.RealTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#realType.
    def exitRealType(self, ctx:MySQLParser.RealTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#fieldLength.
    def enterFieldLength(self, ctx:MySQLParser.FieldLengthContext):
        pass

    # Exit a parse tree produced by MySQLParser#fieldLength.
    def exitFieldLength(self, ctx:MySQLParser.FieldLengthContext):
        pass


    # Enter a parse tree produced by MySQLParser#fieldOptions.
    def enterFieldOptions(self, ctx:MySQLParser.FieldOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#fieldOptions.
    def exitFieldOptions(self, ctx:MySQLParser.FieldOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#charsetWithOptBinary.
    def enterCharsetWithOptBinary(self, ctx:MySQLParser.CharsetWithOptBinaryContext):
        pass

    # Exit a parse tree produced by MySQLParser#charsetWithOptBinary.
    def exitCharsetWithOptBinary(self, ctx:MySQLParser.CharsetWithOptBinaryContext):
        pass


    # Enter a parse tree produced by MySQLParser#ascii.
    def enterAscii(self, ctx:MySQLParser.AsciiContext):
        pass

    # Exit a parse tree produced by MySQLParser#ascii.
    def exitAscii(self, ctx:MySQLParser.AsciiContext):
        pass


    # Enter a parse tree produced by MySQLParser#unicode.
    def enterUnicode(self, ctx:MySQLParser.UnicodeContext):
        pass

    # Exit a parse tree produced by MySQLParser#unicode.
    def exitUnicode(self, ctx:MySQLParser.UnicodeContext):
        pass


    # Enter a parse tree produced by MySQLParser#wsNumCodepoints.
    def enterWsNumCodepoints(self, ctx:MySQLParser.WsNumCodepointsContext):
        pass

    # Exit a parse tree produced by MySQLParser#wsNumCodepoints.
    def exitWsNumCodepoints(self, ctx:MySQLParser.WsNumCodepointsContext):
        pass


    # Enter a parse tree produced by MySQLParser#typeDatetimePrecision.
    def enterTypeDatetimePrecision(self, ctx:MySQLParser.TypeDatetimePrecisionContext):
        pass

    # Exit a parse tree produced by MySQLParser#typeDatetimePrecision.
    def exitTypeDatetimePrecision(self, ctx:MySQLParser.TypeDatetimePrecisionContext):
        pass


    # Enter a parse tree produced by MySQLParser#charsetName.
    def enterCharsetName(self, ctx:MySQLParser.CharsetNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#charsetName.
    def exitCharsetName(self, ctx:MySQLParser.CharsetNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#collationName.
    def enterCollationName(self, ctx:MySQLParser.CollationNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#collationName.
    def exitCollationName(self, ctx:MySQLParser.CollationNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#createTableOptions.
    def enterCreateTableOptions(self, ctx:MySQLParser.CreateTableOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#createTableOptions.
    def exitCreateTableOptions(self, ctx:MySQLParser.CreateTableOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#createTableOptionsSpaceSeparated.
    def enterCreateTableOptionsSpaceSeparated(self, ctx:MySQLParser.CreateTableOptionsSpaceSeparatedContext):
        pass

    # Exit a parse tree produced by MySQLParser#createTableOptionsSpaceSeparated.
    def exitCreateTableOptionsSpaceSeparated(self, ctx:MySQLParser.CreateTableOptionsSpaceSeparatedContext):
        pass


    # Enter a parse tree produced by MySQLParser#createTableOption.
    def enterCreateTableOption(self, ctx:MySQLParser.CreateTableOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#createTableOption.
    def exitCreateTableOption(self, ctx:MySQLParser.CreateTableOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#ternaryOption.
    def enterTernaryOption(self, ctx:MySQLParser.TernaryOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#ternaryOption.
    def exitTernaryOption(self, ctx:MySQLParser.TernaryOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#defaultCollation.
    def enterDefaultCollation(self, ctx:MySQLParser.DefaultCollationContext):
        pass

    # Exit a parse tree produced by MySQLParser#defaultCollation.
    def exitDefaultCollation(self, ctx:MySQLParser.DefaultCollationContext):
        pass


    # Enter a parse tree produced by MySQLParser#defaultEncryption.
    def enterDefaultEncryption(self, ctx:MySQLParser.DefaultEncryptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#defaultEncryption.
    def exitDefaultEncryption(self, ctx:MySQLParser.DefaultEncryptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#defaultCharset.
    def enterDefaultCharset(self, ctx:MySQLParser.DefaultCharsetContext):
        pass

    # Exit a parse tree produced by MySQLParser#defaultCharset.
    def exitDefaultCharset(self, ctx:MySQLParser.DefaultCharsetContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionClause.
    def enterPartitionClause(self, ctx:MySQLParser.PartitionClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionClause.
    def exitPartitionClause(self, ctx:MySQLParser.PartitionClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionDefKey.
    def enterPartitionDefKey(self, ctx:MySQLParser.PartitionDefKeyContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionDefKey.
    def exitPartitionDefKey(self, ctx:MySQLParser.PartitionDefKeyContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionDefHash.
    def enterPartitionDefHash(self, ctx:MySQLParser.PartitionDefHashContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionDefHash.
    def exitPartitionDefHash(self, ctx:MySQLParser.PartitionDefHashContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionDefRangeList.
    def enterPartitionDefRangeList(self, ctx:MySQLParser.PartitionDefRangeListContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionDefRangeList.
    def exitPartitionDefRangeList(self, ctx:MySQLParser.PartitionDefRangeListContext):
        pass


    # Enter a parse tree produced by MySQLParser#subPartitions.
    def enterSubPartitions(self, ctx:MySQLParser.SubPartitionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#subPartitions.
    def exitSubPartitions(self, ctx:MySQLParser.SubPartitionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionKeyAlgorithm.
    def enterPartitionKeyAlgorithm(self, ctx:MySQLParser.PartitionKeyAlgorithmContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionKeyAlgorithm.
    def exitPartitionKeyAlgorithm(self, ctx:MySQLParser.PartitionKeyAlgorithmContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionDefinitions.
    def enterPartitionDefinitions(self, ctx:MySQLParser.PartitionDefinitionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionDefinitions.
    def exitPartitionDefinitions(self, ctx:MySQLParser.PartitionDefinitionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionDefinition.
    def enterPartitionDefinition(self, ctx:MySQLParser.PartitionDefinitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionDefinition.
    def exitPartitionDefinition(self, ctx:MySQLParser.PartitionDefinitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionValuesIn.
    def enterPartitionValuesIn(self, ctx:MySQLParser.PartitionValuesInContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionValuesIn.
    def exitPartitionValuesIn(self, ctx:MySQLParser.PartitionValuesInContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionOption.
    def enterPartitionOption(self, ctx:MySQLParser.PartitionOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionOption.
    def exitPartitionOption(self, ctx:MySQLParser.PartitionOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#subpartitionDefinition.
    def enterSubpartitionDefinition(self, ctx:MySQLParser.SubpartitionDefinitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#subpartitionDefinition.
    def exitSubpartitionDefinition(self, ctx:MySQLParser.SubpartitionDefinitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionValueItemListParen.
    def enterPartitionValueItemListParen(self, ctx:MySQLParser.PartitionValueItemListParenContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionValueItemListParen.
    def exitPartitionValueItemListParen(self, ctx:MySQLParser.PartitionValueItemListParenContext):
        pass


    # Enter a parse tree produced by MySQLParser#partitionValueItem.
    def enterPartitionValueItem(self, ctx:MySQLParser.PartitionValueItemContext):
        pass

    # Exit a parse tree produced by MySQLParser#partitionValueItem.
    def exitPartitionValueItem(self, ctx:MySQLParser.PartitionValueItemContext):
        pass


    # Enter a parse tree produced by MySQLParser#definerClause.
    def enterDefinerClause(self, ctx:MySQLParser.DefinerClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#definerClause.
    def exitDefinerClause(self, ctx:MySQLParser.DefinerClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#ifExists.
    def enterIfExists(self, ctx:MySQLParser.IfExistsContext):
        pass

    # Exit a parse tree produced by MySQLParser#ifExists.
    def exitIfExists(self, ctx:MySQLParser.IfExistsContext):
        pass


    # Enter a parse tree produced by MySQLParser#ifNotExists.
    def enterIfNotExists(self, ctx:MySQLParser.IfNotExistsContext):
        pass

    # Exit a parse tree produced by MySQLParser#ifNotExists.
    def exitIfNotExists(self, ctx:MySQLParser.IfNotExistsContext):
        pass


    # Enter a parse tree produced by MySQLParser#procedureParameter.
    def enterProcedureParameter(self, ctx:MySQLParser.ProcedureParameterContext):
        pass

    # Exit a parse tree produced by MySQLParser#procedureParameter.
    def exitProcedureParameter(self, ctx:MySQLParser.ProcedureParameterContext):
        pass


    # Enter a parse tree produced by MySQLParser#functionParameter.
    def enterFunctionParameter(self, ctx:MySQLParser.FunctionParameterContext):
        pass

    # Exit a parse tree produced by MySQLParser#functionParameter.
    def exitFunctionParameter(self, ctx:MySQLParser.FunctionParameterContext):
        pass


    # Enter a parse tree produced by MySQLParser#collate.
    def enterCollate(self, ctx:MySQLParser.CollateContext):
        pass

    # Exit a parse tree produced by MySQLParser#collate.
    def exitCollate(self, ctx:MySQLParser.CollateContext):
        pass


    # Enter a parse tree produced by MySQLParser#typeWithOptCollate.
    def enterTypeWithOptCollate(self, ctx:MySQLParser.TypeWithOptCollateContext):
        pass

    # Exit a parse tree produced by MySQLParser#typeWithOptCollate.
    def exitTypeWithOptCollate(self, ctx:MySQLParser.TypeWithOptCollateContext):
        pass


    # Enter a parse tree produced by MySQLParser#schemaIdentifierPair.
    def enterSchemaIdentifierPair(self, ctx:MySQLParser.SchemaIdentifierPairContext):
        pass

    # Exit a parse tree produced by MySQLParser#schemaIdentifierPair.
    def exitSchemaIdentifierPair(self, ctx:MySQLParser.SchemaIdentifierPairContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewRefList.
    def enterViewRefList(self, ctx:MySQLParser.ViewRefListContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewRefList.
    def exitViewRefList(self, ctx:MySQLParser.ViewRefListContext):
        pass


    # Enter a parse tree produced by MySQLParser#updateList.
    def enterUpdateList(self, ctx:MySQLParser.UpdateListContext):
        pass

    # Exit a parse tree produced by MySQLParser#updateList.
    def exitUpdateList(self, ctx:MySQLParser.UpdateListContext):
        pass


    # Enter a parse tree produced by MySQLParser#updateElement.
    def enterUpdateElement(self, ctx:MySQLParser.UpdateElementContext):
        pass

    # Exit a parse tree produced by MySQLParser#updateElement.
    def exitUpdateElement(self, ctx:MySQLParser.UpdateElementContext):
        pass


    # Enter a parse tree produced by MySQLParser#charsetClause.
    def enterCharsetClause(self, ctx:MySQLParser.CharsetClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#charsetClause.
    def exitCharsetClause(self, ctx:MySQLParser.CharsetClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#fieldsClause.
    def enterFieldsClause(self, ctx:MySQLParser.FieldsClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#fieldsClause.
    def exitFieldsClause(self, ctx:MySQLParser.FieldsClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#fieldTerm.
    def enterFieldTerm(self, ctx:MySQLParser.FieldTermContext):
        pass

    # Exit a parse tree produced by MySQLParser#fieldTerm.
    def exitFieldTerm(self, ctx:MySQLParser.FieldTermContext):
        pass


    # Enter a parse tree produced by MySQLParser#linesClause.
    def enterLinesClause(self, ctx:MySQLParser.LinesClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#linesClause.
    def exitLinesClause(self, ctx:MySQLParser.LinesClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#lineTerm.
    def enterLineTerm(self, ctx:MySQLParser.LineTermContext):
        pass

    # Exit a parse tree produced by MySQLParser#lineTerm.
    def exitLineTerm(self, ctx:MySQLParser.LineTermContext):
        pass


    # Enter a parse tree produced by MySQLParser#userList.
    def enterUserList(self, ctx:MySQLParser.UserListContext):
        pass

    # Exit a parse tree produced by MySQLParser#userList.
    def exitUserList(self, ctx:MySQLParser.UserListContext):
        pass


    # Enter a parse tree produced by MySQLParser#createUserList.
    def enterCreateUserList(self, ctx:MySQLParser.CreateUserListContext):
        pass

    # Exit a parse tree produced by MySQLParser#createUserList.
    def exitCreateUserList(self, ctx:MySQLParser.CreateUserListContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterUserList.
    def enterAlterUserList(self, ctx:MySQLParser.AlterUserListContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterUserList.
    def exitAlterUserList(self, ctx:MySQLParser.AlterUserListContext):
        pass


    # Enter a parse tree produced by MySQLParser#createUserEntry.
    def enterCreateUserEntry(self, ctx:MySQLParser.CreateUserEntryContext):
        pass

    # Exit a parse tree produced by MySQLParser#createUserEntry.
    def exitCreateUserEntry(self, ctx:MySQLParser.CreateUserEntryContext):
        pass


    # Enter a parse tree produced by MySQLParser#alterUserEntry.
    def enterAlterUserEntry(self, ctx:MySQLParser.AlterUserEntryContext):
        pass

    # Exit a parse tree produced by MySQLParser#alterUserEntry.
    def exitAlterUserEntry(self, ctx:MySQLParser.AlterUserEntryContext):
        pass


    # Enter a parse tree produced by MySQLParser#retainCurrentPassword.
    def enterRetainCurrentPassword(self, ctx:MySQLParser.RetainCurrentPasswordContext):
        pass

    # Exit a parse tree produced by MySQLParser#retainCurrentPassword.
    def exitRetainCurrentPassword(self, ctx:MySQLParser.RetainCurrentPasswordContext):
        pass


    # Enter a parse tree produced by MySQLParser#discardOldPassword.
    def enterDiscardOldPassword(self, ctx:MySQLParser.DiscardOldPasswordContext):
        pass

    # Exit a parse tree produced by MySQLParser#discardOldPassword.
    def exitDiscardOldPassword(self, ctx:MySQLParser.DiscardOldPasswordContext):
        pass


    # Enter a parse tree produced by MySQLParser#replacePassword.
    def enterReplacePassword(self, ctx:MySQLParser.ReplacePasswordContext):
        pass

    # Exit a parse tree produced by MySQLParser#replacePassword.
    def exitReplacePassword(self, ctx:MySQLParser.ReplacePasswordContext):
        pass


    # Enter a parse tree produced by MySQLParser#userIdentifierOrText.
    def enterUserIdentifierOrText(self, ctx:MySQLParser.UserIdentifierOrTextContext):
        pass

    # Exit a parse tree produced by MySQLParser#userIdentifierOrText.
    def exitUserIdentifierOrText(self, ctx:MySQLParser.UserIdentifierOrTextContext):
        pass


    # Enter a parse tree produced by MySQLParser#user.
    def enterUser(self, ctx:MySQLParser.UserContext):
        pass

    # Exit a parse tree produced by MySQLParser#user.
    def exitUser(self, ctx:MySQLParser.UserContext):
        pass


    # Enter a parse tree produced by MySQLParser#likeClause.
    def enterLikeClause(self, ctx:MySQLParser.LikeClauseContext):
        pass

    # Exit a parse tree produced by MySQLParser#likeClause.
    def exitLikeClause(self, ctx:MySQLParser.LikeClauseContext):
        pass


    # Enter a parse tree produced by MySQLParser#likeOrWhere.
    def enterLikeOrWhere(self, ctx:MySQLParser.LikeOrWhereContext):
        pass

    # Exit a parse tree produced by MySQLParser#likeOrWhere.
    def exitLikeOrWhere(self, ctx:MySQLParser.LikeOrWhereContext):
        pass


    # Enter a parse tree produced by MySQLParser#onlineOption.
    def enterOnlineOption(self, ctx:MySQLParser.OnlineOptionContext):
        pass

    # Exit a parse tree produced by MySQLParser#onlineOption.
    def exitOnlineOption(self, ctx:MySQLParser.OnlineOptionContext):
        pass


    # Enter a parse tree produced by MySQLParser#noWriteToBinLog.
    def enterNoWriteToBinLog(self, ctx:MySQLParser.NoWriteToBinLogContext):
        pass

    # Exit a parse tree produced by MySQLParser#noWriteToBinLog.
    def exitNoWriteToBinLog(self, ctx:MySQLParser.NoWriteToBinLogContext):
        pass


    # Enter a parse tree produced by MySQLParser#usePartition.
    def enterUsePartition(self, ctx:MySQLParser.UsePartitionContext):
        pass

    # Exit a parse tree produced by MySQLParser#usePartition.
    def exitUsePartition(self, ctx:MySQLParser.UsePartitionContext):
        pass


    # Enter a parse tree produced by MySQLParser#fieldIdentifier.
    def enterFieldIdentifier(self, ctx:MySQLParser.FieldIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#fieldIdentifier.
    def exitFieldIdentifier(self, ctx:MySQLParser.FieldIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnName.
    def enterColumnName(self, ctx:MySQLParser.ColumnNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnName.
    def exitColumnName(self, ctx:MySQLParser.ColumnNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnInternalRef.
    def enterColumnInternalRef(self, ctx:MySQLParser.ColumnInternalRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnInternalRef.
    def exitColumnInternalRef(self, ctx:MySQLParser.ColumnInternalRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnInternalRefList.
    def enterColumnInternalRefList(self, ctx:MySQLParser.ColumnInternalRefListContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnInternalRefList.
    def exitColumnInternalRefList(self, ctx:MySQLParser.ColumnInternalRefListContext):
        pass


    # Enter a parse tree produced by MySQLParser#columnRef.
    def enterColumnRef(self, ctx:MySQLParser.ColumnRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#columnRef.
    def exitColumnRef(self, ctx:MySQLParser.ColumnRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#insertIdentifier.
    def enterInsertIdentifier(self, ctx:MySQLParser.InsertIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#insertIdentifier.
    def exitInsertIdentifier(self, ctx:MySQLParser.InsertIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexName.
    def enterIndexName(self, ctx:MySQLParser.IndexNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexName.
    def exitIndexName(self, ctx:MySQLParser.IndexNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#indexRef.
    def enterIndexRef(self, ctx:MySQLParser.IndexRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#indexRef.
    def exitIndexRef(self, ctx:MySQLParser.IndexRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableWild.
    def enterTableWild(self, ctx:MySQLParser.TableWildContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableWild.
    def exitTableWild(self, ctx:MySQLParser.TableWildContext):
        pass


    # Enter a parse tree produced by MySQLParser#schemaName.
    def enterSchemaName(self, ctx:MySQLParser.SchemaNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#schemaName.
    def exitSchemaName(self, ctx:MySQLParser.SchemaNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#schemaRef.
    def enterSchemaRef(self, ctx:MySQLParser.SchemaRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#schemaRef.
    def exitSchemaRef(self, ctx:MySQLParser.SchemaRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#procedureName.
    def enterProcedureName(self, ctx:MySQLParser.ProcedureNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#procedureName.
    def exitProcedureName(self, ctx:MySQLParser.ProcedureNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#procedureRef.
    def enterProcedureRef(self, ctx:MySQLParser.ProcedureRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#procedureRef.
    def exitProcedureRef(self, ctx:MySQLParser.ProcedureRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#functionName.
    def enterFunctionName(self, ctx:MySQLParser.FunctionNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#functionName.
    def exitFunctionName(self, ctx:MySQLParser.FunctionNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#functionRef.
    def enterFunctionRef(self, ctx:MySQLParser.FunctionRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#functionRef.
    def exitFunctionRef(self, ctx:MySQLParser.FunctionRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#triggerName.
    def enterTriggerName(self, ctx:MySQLParser.TriggerNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#triggerName.
    def exitTriggerName(self, ctx:MySQLParser.TriggerNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#triggerRef.
    def enterTriggerRef(self, ctx:MySQLParser.TriggerRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#triggerRef.
    def exitTriggerRef(self, ctx:MySQLParser.TriggerRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewName.
    def enterViewName(self, ctx:MySQLParser.ViewNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewName.
    def exitViewName(self, ctx:MySQLParser.ViewNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#viewRef.
    def enterViewRef(self, ctx:MySQLParser.ViewRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#viewRef.
    def exitViewRef(self, ctx:MySQLParser.ViewRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#tablespaceName.
    def enterTablespaceName(self, ctx:MySQLParser.TablespaceNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#tablespaceName.
    def exitTablespaceName(self, ctx:MySQLParser.TablespaceNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#tablespaceRef.
    def enterTablespaceRef(self, ctx:MySQLParser.TablespaceRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#tablespaceRef.
    def exitTablespaceRef(self, ctx:MySQLParser.TablespaceRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#logfileGroupName.
    def enterLogfileGroupName(self, ctx:MySQLParser.LogfileGroupNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#logfileGroupName.
    def exitLogfileGroupName(self, ctx:MySQLParser.LogfileGroupNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#logfileGroupRef.
    def enterLogfileGroupRef(self, ctx:MySQLParser.LogfileGroupRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#logfileGroupRef.
    def exitLogfileGroupRef(self, ctx:MySQLParser.LogfileGroupRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#eventName.
    def enterEventName(self, ctx:MySQLParser.EventNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#eventName.
    def exitEventName(self, ctx:MySQLParser.EventNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#eventRef.
    def enterEventRef(self, ctx:MySQLParser.EventRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#eventRef.
    def exitEventRef(self, ctx:MySQLParser.EventRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#udfName.
    def enterUdfName(self, ctx:MySQLParser.UdfNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#udfName.
    def exitUdfName(self, ctx:MySQLParser.UdfNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#serverName.
    def enterServerName(self, ctx:MySQLParser.ServerNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#serverName.
    def exitServerName(self, ctx:MySQLParser.ServerNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#serverRef.
    def enterServerRef(self, ctx:MySQLParser.ServerRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#serverRef.
    def exitServerRef(self, ctx:MySQLParser.ServerRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#engineRef.
    def enterEngineRef(self, ctx:MySQLParser.EngineRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#engineRef.
    def exitEngineRef(self, ctx:MySQLParser.EngineRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableName.
    def enterTableName(self, ctx:MySQLParser.TableNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableName.
    def exitTableName(self, ctx:MySQLParser.TableNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#filterTableRef.
    def enterFilterTableRef(self, ctx:MySQLParser.FilterTableRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#filterTableRef.
    def exitFilterTableRef(self, ctx:MySQLParser.FilterTableRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableRefWithWildcard.
    def enterTableRefWithWildcard(self, ctx:MySQLParser.TableRefWithWildcardContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableRefWithWildcard.
    def exitTableRefWithWildcard(self, ctx:MySQLParser.TableRefWithWildcardContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableRef.
    def enterTableRef(self, ctx:MySQLParser.TableRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableRef.
    def exitTableRef(self, ctx:MySQLParser.TableRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableRefList.
    def enterTableRefList(self, ctx:MySQLParser.TableRefListContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableRefList.
    def exitTableRefList(self, ctx:MySQLParser.TableRefListContext):
        pass


    # Enter a parse tree produced by MySQLParser#tableAliasRefList.
    def enterTableAliasRefList(self, ctx:MySQLParser.TableAliasRefListContext):
        pass

    # Exit a parse tree produced by MySQLParser#tableAliasRefList.
    def exitTableAliasRefList(self, ctx:MySQLParser.TableAliasRefListContext):
        pass


    # Enter a parse tree produced by MySQLParser#parameterName.
    def enterParameterName(self, ctx:MySQLParser.ParameterNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#parameterName.
    def exitParameterName(self, ctx:MySQLParser.ParameterNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#labelIdentifier.
    def enterLabelIdentifier(self, ctx:MySQLParser.LabelIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#labelIdentifier.
    def exitLabelIdentifier(self, ctx:MySQLParser.LabelIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#labelRef.
    def enterLabelRef(self, ctx:MySQLParser.LabelRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#labelRef.
    def exitLabelRef(self, ctx:MySQLParser.LabelRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleIdentifier.
    def enterRoleIdentifier(self, ctx:MySQLParser.RoleIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleIdentifier.
    def exitRoleIdentifier(self, ctx:MySQLParser.RoleIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleRef.
    def enterRoleRef(self, ctx:MySQLParser.RoleRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleRef.
    def exitRoleRef(self, ctx:MySQLParser.RoleRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#pluginRef.
    def enterPluginRef(self, ctx:MySQLParser.PluginRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#pluginRef.
    def exitPluginRef(self, ctx:MySQLParser.PluginRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#componentRef.
    def enterComponentRef(self, ctx:MySQLParser.ComponentRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#componentRef.
    def exitComponentRef(self, ctx:MySQLParser.ComponentRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#resourceGroupRef.
    def enterResourceGroupRef(self, ctx:MySQLParser.ResourceGroupRefContext):
        pass

    # Exit a parse tree produced by MySQLParser#resourceGroupRef.
    def exitResourceGroupRef(self, ctx:MySQLParser.ResourceGroupRefContext):
        pass


    # Enter a parse tree produced by MySQLParser#windowName.
    def enterWindowName(self, ctx:MySQLParser.WindowNameContext):
        pass

    # Exit a parse tree produced by MySQLParser#windowName.
    def exitWindowName(self, ctx:MySQLParser.WindowNameContext):
        pass


    # Enter a parse tree produced by MySQLParser#pureIdentifier.
    def enterPureIdentifier(self, ctx:MySQLParser.PureIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#pureIdentifier.
    def exitPureIdentifier(self, ctx:MySQLParser.PureIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifier.
    def enterIdentifier(self, ctx:MySQLParser.IdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifier.
    def exitIdentifier(self, ctx:MySQLParser.IdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierList.
    def enterIdentifierList(self, ctx:MySQLParser.IdentifierListContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierList.
    def exitIdentifierList(self, ctx:MySQLParser.IdentifierListContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierListWithParentheses.
    def enterIdentifierListWithParentheses(self, ctx:MySQLParser.IdentifierListWithParenthesesContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierListWithParentheses.
    def exitIdentifierListWithParentheses(self, ctx:MySQLParser.IdentifierListWithParenthesesContext):
        pass


    # Enter a parse tree produced by MySQLParser#qualifiedIdentifier.
    def enterQualifiedIdentifier(self, ctx:MySQLParser.QualifiedIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#qualifiedIdentifier.
    def exitQualifiedIdentifier(self, ctx:MySQLParser.QualifiedIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#simpleIdentifier.
    def enterSimpleIdentifier(self, ctx:MySQLParser.SimpleIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#simpleIdentifier.
    def exitSimpleIdentifier(self, ctx:MySQLParser.SimpleIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#dotIdentifier.
    def enterDotIdentifier(self, ctx:MySQLParser.DotIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#dotIdentifier.
    def exitDotIdentifier(self, ctx:MySQLParser.DotIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#ulong_number.
    def enterUlong_number(self, ctx:MySQLParser.Ulong_numberContext):
        pass

    # Exit a parse tree produced by MySQLParser#ulong_number.
    def exitUlong_number(self, ctx:MySQLParser.Ulong_numberContext):
        pass


    # Enter a parse tree produced by MySQLParser#real_ulong_number.
    def enterReal_ulong_number(self, ctx:MySQLParser.Real_ulong_numberContext):
        pass

    # Exit a parse tree produced by MySQLParser#real_ulong_number.
    def exitReal_ulong_number(self, ctx:MySQLParser.Real_ulong_numberContext):
        pass


    # Enter a parse tree produced by MySQLParser#ulonglong_number.
    def enterUlonglong_number(self, ctx:MySQLParser.Ulonglong_numberContext):
        pass

    # Exit a parse tree produced by MySQLParser#ulonglong_number.
    def exitUlonglong_number(self, ctx:MySQLParser.Ulonglong_numberContext):
        pass


    # Enter a parse tree produced by MySQLParser#real_ulonglong_number.
    def enterReal_ulonglong_number(self, ctx:MySQLParser.Real_ulonglong_numberContext):
        pass

    # Exit a parse tree produced by MySQLParser#real_ulonglong_number.
    def exitReal_ulonglong_number(self, ctx:MySQLParser.Real_ulonglong_numberContext):
        pass


    # Enter a parse tree produced by MySQLParser#literal.
    def enterLiteral(self, ctx:MySQLParser.LiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#literal.
    def exitLiteral(self, ctx:MySQLParser.LiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#signedLiteral.
    def enterSignedLiteral(self, ctx:MySQLParser.SignedLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#signedLiteral.
    def exitSignedLiteral(self, ctx:MySQLParser.SignedLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#stringList.
    def enterStringList(self, ctx:MySQLParser.StringListContext):
        pass

    # Exit a parse tree produced by MySQLParser#stringList.
    def exitStringList(self, ctx:MySQLParser.StringListContext):
        pass


    # Enter a parse tree produced by MySQLParser#textStringLiteral.
    def enterTextStringLiteral(self, ctx:MySQLParser.TextStringLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#textStringLiteral.
    def exitTextStringLiteral(self, ctx:MySQLParser.TextStringLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#textString.
    def enterTextString(self, ctx:MySQLParser.TextStringContext):
        pass

    # Exit a parse tree produced by MySQLParser#textString.
    def exitTextString(self, ctx:MySQLParser.TextStringContext):
        pass


    # Enter a parse tree produced by MySQLParser#textStringHash.
    def enterTextStringHash(self, ctx:MySQLParser.TextStringHashContext):
        pass

    # Exit a parse tree produced by MySQLParser#textStringHash.
    def exitTextStringHash(self, ctx:MySQLParser.TextStringHashContext):
        pass


    # Enter a parse tree produced by MySQLParser#textLiteral.
    def enterTextLiteral(self, ctx:MySQLParser.TextLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#textLiteral.
    def exitTextLiteral(self, ctx:MySQLParser.TextLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#textStringNoLinebreak.
    def enterTextStringNoLinebreak(self, ctx:MySQLParser.TextStringNoLinebreakContext):
        pass

    # Exit a parse tree produced by MySQLParser#textStringNoLinebreak.
    def exitTextStringNoLinebreak(self, ctx:MySQLParser.TextStringNoLinebreakContext):
        pass


    # Enter a parse tree produced by MySQLParser#textStringLiteralList.
    def enterTextStringLiteralList(self, ctx:MySQLParser.TextStringLiteralListContext):
        pass

    # Exit a parse tree produced by MySQLParser#textStringLiteralList.
    def exitTextStringLiteralList(self, ctx:MySQLParser.TextStringLiteralListContext):
        pass


    # Enter a parse tree produced by MySQLParser#numLiteral.
    def enterNumLiteral(self, ctx:MySQLParser.NumLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#numLiteral.
    def exitNumLiteral(self, ctx:MySQLParser.NumLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#boolLiteral.
    def enterBoolLiteral(self, ctx:MySQLParser.BoolLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#boolLiteral.
    def exitBoolLiteral(self, ctx:MySQLParser.BoolLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#nullLiteral.
    def enterNullLiteral(self, ctx:MySQLParser.NullLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#nullLiteral.
    def exitNullLiteral(self, ctx:MySQLParser.NullLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#temporalLiteral.
    def enterTemporalLiteral(self, ctx:MySQLParser.TemporalLiteralContext):
        pass

    # Exit a parse tree produced by MySQLParser#temporalLiteral.
    def exitTemporalLiteral(self, ctx:MySQLParser.TemporalLiteralContext):
        pass


    # Enter a parse tree produced by MySQLParser#floatOptions.
    def enterFloatOptions(self, ctx:MySQLParser.FloatOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#floatOptions.
    def exitFloatOptions(self, ctx:MySQLParser.FloatOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#standardFloatOptions.
    def enterStandardFloatOptions(self, ctx:MySQLParser.StandardFloatOptionsContext):
        pass

    # Exit a parse tree produced by MySQLParser#standardFloatOptions.
    def exitStandardFloatOptions(self, ctx:MySQLParser.StandardFloatOptionsContext):
        pass


    # Enter a parse tree produced by MySQLParser#precision.
    def enterPrecision(self, ctx:MySQLParser.PrecisionContext):
        pass

    # Exit a parse tree produced by MySQLParser#precision.
    def exitPrecision(self, ctx:MySQLParser.PrecisionContext):
        pass


    # Enter a parse tree produced by MySQLParser#textOrIdentifier.
    def enterTextOrIdentifier(self, ctx:MySQLParser.TextOrIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#textOrIdentifier.
    def exitTextOrIdentifier(self, ctx:MySQLParser.TextOrIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#lValueIdentifier.
    def enterLValueIdentifier(self, ctx:MySQLParser.LValueIdentifierContext):
        pass

    # Exit a parse tree produced by MySQLParser#lValueIdentifier.
    def exitLValueIdentifier(self, ctx:MySQLParser.LValueIdentifierContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleIdentifierOrText.
    def enterRoleIdentifierOrText(self, ctx:MySQLParser.RoleIdentifierOrTextContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleIdentifierOrText.
    def exitRoleIdentifierOrText(self, ctx:MySQLParser.RoleIdentifierOrTextContext):
        pass


    # Enter a parse tree produced by MySQLParser#sizeNumber.
    def enterSizeNumber(self, ctx:MySQLParser.SizeNumberContext):
        pass

    # Exit a parse tree produced by MySQLParser#sizeNumber.
    def exitSizeNumber(self, ctx:MySQLParser.SizeNumberContext):
        pass


    # Enter a parse tree produced by MySQLParser#parentheses.
    def enterParentheses(self, ctx:MySQLParser.ParenthesesContext):
        pass

    # Exit a parse tree produced by MySQLParser#parentheses.
    def exitParentheses(self, ctx:MySQLParser.ParenthesesContext):
        pass


    # Enter a parse tree produced by MySQLParser#equal.
    def enterEqual(self, ctx:MySQLParser.EqualContext):
        pass

    # Exit a parse tree produced by MySQLParser#equal.
    def exitEqual(self, ctx:MySQLParser.EqualContext):
        pass


    # Enter a parse tree produced by MySQLParser#optionType.
    def enterOptionType(self, ctx:MySQLParser.OptionTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#optionType.
    def exitOptionType(self, ctx:MySQLParser.OptionTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#varIdentType.
    def enterVarIdentType(self, ctx:MySQLParser.VarIdentTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#varIdentType.
    def exitVarIdentType(self, ctx:MySQLParser.VarIdentTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#setVarIdentType.
    def enterSetVarIdentType(self, ctx:MySQLParser.SetVarIdentTypeContext):
        pass

    # Exit a parse tree produced by MySQLParser#setVarIdentType.
    def exitSetVarIdentType(self, ctx:MySQLParser.SetVarIdentTypeContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierKeyword.
    def enterIdentifierKeyword(self, ctx:MySQLParser.IdentifierKeywordContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierKeyword.
    def exitIdentifierKeyword(self, ctx:MySQLParser.IdentifierKeywordContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierKeywordsAmbiguous1RolesAndLabels.
    def enterIdentifierKeywordsAmbiguous1RolesAndLabels(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous1RolesAndLabelsContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierKeywordsAmbiguous1RolesAndLabels.
    def exitIdentifierKeywordsAmbiguous1RolesAndLabels(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous1RolesAndLabelsContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierKeywordsAmbiguous2Labels.
    def enterIdentifierKeywordsAmbiguous2Labels(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous2LabelsContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierKeywordsAmbiguous2Labels.
    def exitIdentifierKeywordsAmbiguous2Labels(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous2LabelsContext):
        pass


    # Enter a parse tree produced by MySQLParser#labelKeyword.
    def enterLabelKeyword(self, ctx:MySQLParser.LabelKeywordContext):
        pass

    # Exit a parse tree produced by MySQLParser#labelKeyword.
    def exitLabelKeyword(self, ctx:MySQLParser.LabelKeywordContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierKeywordsAmbiguous3Roles.
    def enterIdentifierKeywordsAmbiguous3Roles(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous3RolesContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierKeywordsAmbiguous3Roles.
    def exitIdentifierKeywordsAmbiguous3Roles(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous3RolesContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierKeywordsUnambiguous.
    def enterIdentifierKeywordsUnambiguous(self, ctx:MySQLParser.IdentifierKeywordsUnambiguousContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierKeywordsUnambiguous.
    def exitIdentifierKeywordsUnambiguous(self, ctx:MySQLParser.IdentifierKeywordsUnambiguousContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleKeyword.
    def enterRoleKeyword(self, ctx:MySQLParser.RoleKeywordContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleKeyword.
    def exitRoleKeyword(self, ctx:MySQLParser.RoleKeywordContext):
        pass


    # Enter a parse tree produced by MySQLParser#lValueKeyword.
    def enterLValueKeyword(self, ctx:MySQLParser.LValueKeywordContext):
        pass

    # Exit a parse tree produced by MySQLParser#lValueKeyword.
    def exitLValueKeyword(self, ctx:MySQLParser.LValueKeywordContext):
        pass


    # Enter a parse tree produced by MySQLParser#identifierKeywordsAmbiguous4SystemVariables.
    def enterIdentifierKeywordsAmbiguous4SystemVariables(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous4SystemVariablesContext):
        pass

    # Exit a parse tree produced by MySQLParser#identifierKeywordsAmbiguous4SystemVariables.
    def exitIdentifierKeywordsAmbiguous4SystemVariables(self, ctx:MySQLParser.IdentifierKeywordsAmbiguous4SystemVariablesContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleOrIdentifierKeyword.
    def enterRoleOrIdentifierKeyword(self, ctx:MySQLParser.RoleOrIdentifierKeywordContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleOrIdentifierKeyword.
    def exitRoleOrIdentifierKeyword(self, ctx:MySQLParser.RoleOrIdentifierKeywordContext):
        pass


    # Enter a parse tree produced by MySQLParser#roleOrLabelKeyword.
    def enterRoleOrLabelKeyword(self, ctx:MySQLParser.RoleOrLabelKeywordContext):
        pass

    # Exit a parse tree produced by MySQLParser#roleOrLabelKeyword.
    def exitRoleOrLabelKeyword(self, ctx:MySQLParser.RoleOrLabelKeywordContext):
        pass



del MySQLParser
