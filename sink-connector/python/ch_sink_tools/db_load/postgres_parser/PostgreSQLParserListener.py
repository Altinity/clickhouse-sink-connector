# Generated from PostgreSQLParser.g4 by ANTLR 4.11.1
from antlr4 import *
if __name__ is not None and "." in __name__:
    from .PostgreSQLParser import PostgreSQLParser
else:
    from PostgreSQLParser import PostgreSQLParser

# This class defines a complete listener for a parse tree produced by PostgreSQLParser.
class PostgreSQLParserListener(ParseTreeListener):

    # Enter a parse tree produced by PostgreSQLParser#root.
    def enterRoot(self, ctx:PostgreSQLParser.RootContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#root.
    def exitRoot(self, ctx:PostgreSQLParser.RootContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#stmtblock.
    def enterStmtblock(self, ctx:PostgreSQLParser.StmtblockContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#stmtblock.
    def exitStmtblock(self, ctx:PostgreSQLParser.StmtblockContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#stmtmulti.
    def enterStmtmulti(self, ctx:PostgreSQLParser.StmtmultiContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#stmtmulti.
    def exitStmtmulti(self, ctx:PostgreSQLParser.StmtmultiContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#stmt.
    def enterStmt(self, ctx:PostgreSQLParser.StmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#stmt.
    def exitStmt(self, ctx:PostgreSQLParser.StmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#callstmt.
    def enterCallstmt(self, ctx:PostgreSQLParser.CallstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#callstmt.
    def exitCallstmt(self, ctx:PostgreSQLParser.CallstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createrolestmt.
    def enterCreaterolestmt(self, ctx:PostgreSQLParser.CreaterolestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createrolestmt.
    def exitCreaterolestmt(self, ctx:PostgreSQLParser.CreaterolestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#with_.
    def enterWith_(self, ctx:PostgreSQLParser.With_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#with_.
    def exitWith_(self, ctx:PostgreSQLParser.With_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optrolelist.
    def enterOptrolelist(self, ctx:PostgreSQLParser.OptrolelistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optrolelist.
    def exitOptrolelist(self, ctx:PostgreSQLParser.OptrolelistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alteroptrolelist.
    def enterAlteroptrolelist(self, ctx:PostgreSQLParser.AlteroptrolelistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alteroptrolelist.
    def exitAlteroptrolelist(self, ctx:PostgreSQLParser.AlteroptrolelistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alteroptroleelem.
    def enterAlteroptroleelem(self, ctx:PostgreSQLParser.AlteroptroleelemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alteroptroleelem.
    def exitAlteroptroleelem(self, ctx:PostgreSQLParser.AlteroptroleelemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createoptroleelem.
    def enterCreateoptroleelem(self, ctx:PostgreSQLParser.CreateoptroleelemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createoptroleelem.
    def exitCreateoptroleelem(self, ctx:PostgreSQLParser.CreateoptroleelemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createuserstmt.
    def enterCreateuserstmt(self, ctx:PostgreSQLParser.CreateuserstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createuserstmt.
    def exitCreateuserstmt(self, ctx:PostgreSQLParser.CreateuserstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterrolestmt.
    def enterAlterrolestmt(self, ctx:PostgreSQLParser.AlterrolestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterrolestmt.
    def exitAlterrolestmt(self, ctx:PostgreSQLParser.AlterrolestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#in_database_.
    def enterIn_database_(self, ctx:PostgreSQLParser.In_database_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#in_database_.
    def exitIn_database_(self, ctx:PostgreSQLParser.In_database_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterrolesetstmt.
    def enterAlterrolesetstmt(self, ctx:PostgreSQLParser.AlterrolesetstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterrolesetstmt.
    def exitAlterrolesetstmt(self, ctx:PostgreSQLParser.AlterrolesetstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#droprolestmt.
    def enterDroprolestmt(self, ctx:PostgreSQLParser.DroprolestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#droprolestmt.
    def exitDroprolestmt(self, ctx:PostgreSQLParser.DroprolestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#creategroupstmt.
    def enterCreategroupstmt(self, ctx:PostgreSQLParser.CreategroupstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#creategroupstmt.
    def exitCreategroupstmt(self, ctx:PostgreSQLParser.CreategroupstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altergroupstmt.
    def enterAltergroupstmt(self, ctx:PostgreSQLParser.AltergroupstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altergroupstmt.
    def exitAltergroupstmt(self, ctx:PostgreSQLParser.AltergroupstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#add_drop.
    def enterAdd_drop(self, ctx:PostgreSQLParser.Add_dropContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#add_drop.
    def exitAdd_drop(self, ctx:PostgreSQLParser.Add_dropContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createschemastmt.
    def enterCreateschemastmt(self, ctx:PostgreSQLParser.CreateschemastmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createschemastmt.
    def exitCreateschemastmt(self, ctx:PostgreSQLParser.CreateschemastmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optschemaname.
    def enterOptschemaname(self, ctx:PostgreSQLParser.OptschemanameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optschemaname.
    def exitOptschemaname(self, ctx:PostgreSQLParser.OptschemanameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optschemaeltlist.
    def enterOptschemaeltlist(self, ctx:PostgreSQLParser.OptschemaeltlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optschemaeltlist.
    def exitOptschemaeltlist(self, ctx:PostgreSQLParser.OptschemaeltlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#schema_stmt.
    def enterSchema_stmt(self, ctx:PostgreSQLParser.Schema_stmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#schema_stmt.
    def exitSchema_stmt(self, ctx:PostgreSQLParser.Schema_stmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#variablesetstmt.
    def enterVariablesetstmt(self, ctx:PostgreSQLParser.VariablesetstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#variablesetstmt.
    def exitVariablesetstmt(self, ctx:PostgreSQLParser.VariablesetstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#set_rest.
    def enterSet_rest(self, ctx:PostgreSQLParser.Set_restContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#set_rest.
    def exitSet_rest(self, ctx:PostgreSQLParser.Set_restContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generic_set.
    def enterGeneric_set(self, ctx:PostgreSQLParser.Generic_setContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generic_set.
    def exitGeneric_set(self, ctx:PostgreSQLParser.Generic_setContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#set_rest_more.
    def enterSet_rest_more(self, ctx:PostgreSQLParser.Set_rest_moreContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#set_rest_more.
    def exitSet_rest_more(self, ctx:PostgreSQLParser.Set_rest_moreContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#var_name.
    def enterVar_name(self, ctx:PostgreSQLParser.Var_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#var_name.
    def exitVar_name(self, ctx:PostgreSQLParser.Var_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#var_list.
    def enterVar_list(self, ctx:PostgreSQLParser.Var_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#var_list.
    def exitVar_list(self, ctx:PostgreSQLParser.Var_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#var_value.
    def enterVar_value(self, ctx:PostgreSQLParser.Var_valueContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#var_value.
    def exitVar_value(self, ctx:PostgreSQLParser.Var_valueContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#iso_level.
    def enterIso_level(self, ctx:PostgreSQLParser.Iso_levelContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#iso_level.
    def exitIso_level(self, ctx:PostgreSQLParser.Iso_levelContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#boolean_or_string_.
    def enterBoolean_or_string_(self, ctx:PostgreSQLParser.Boolean_or_string_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#boolean_or_string_.
    def exitBoolean_or_string_(self, ctx:PostgreSQLParser.Boolean_or_string_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#zone_value.
    def enterZone_value(self, ctx:PostgreSQLParser.Zone_valueContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#zone_value.
    def exitZone_value(self, ctx:PostgreSQLParser.Zone_valueContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#encoding_.
    def enterEncoding_(self, ctx:PostgreSQLParser.Encoding_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#encoding_.
    def exitEncoding_(self, ctx:PostgreSQLParser.Encoding_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#nonreservedword_or_sconst.
    def enterNonreservedword_or_sconst(self, ctx:PostgreSQLParser.Nonreservedword_or_sconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#nonreservedword_or_sconst.
    def exitNonreservedword_or_sconst(self, ctx:PostgreSQLParser.Nonreservedword_or_sconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#variableresetstmt.
    def enterVariableresetstmt(self, ctx:PostgreSQLParser.VariableresetstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#variableresetstmt.
    def exitVariableresetstmt(self, ctx:PostgreSQLParser.VariableresetstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reset_rest.
    def enterReset_rest(self, ctx:PostgreSQLParser.Reset_restContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reset_rest.
    def exitReset_rest(self, ctx:PostgreSQLParser.Reset_restContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generic_reset.
    def enterGeneric_reset(self, ctx:PostgreSQLParser.Generic_resetContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generic_reset.
    def exitGeneric_reset(self, ctx:PostgreSQLParser.Generic_resetContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#setresetclause.
    def enterSetresetclause(self, ctx:PostgreSQLParser.SetresetclauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#setresetclause.
    def exitSetresetclause(self, ctx:PostgreSQLParser.SetresetclauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#functionsetresetclause.
    def enterFunctionsetresetclause(self, ctx:PostgreSQLParser.FunctionsetresetclauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#functionsetresetclause.
    def exitFunctionsetresetclause(self, ctx:PostgreSQLParser.FunctionsetresetclauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#variableshowstmt.
    def enterVariableshowstmt(self, ctx:PostgreSQLParser.VariableshowstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#variableshowstmt.
    def exitVariableshowstmt(self, ctx:PostgreSQLParser.VariableshowstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constraintssetstmt.
    def enterConstraintssetstmt(self, ctx:PostgreSQLParser.ConstraintssetstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constraintssetstmt.
    def exitConstraintssetstmt(self, ctx:PostgreSQLParser.ConstraintssetstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constraints_set_list.
    def enterConstraints_set_list(self, ctx:PostgreSQLParser.Constraints_set_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constraints_set_list.
    def exitConstraints_set_list(self, ctx:PostgreSQLParser.Constraints_set_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constraints_set_mode.
    def enterConstraints_set_mode(self, ctx:PostgreSQLParser.Constraints_set_modeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constraints_set_mode.
    def exitConstraints_set_mode(self, ctx:PostgreSQLParser.Constraints_set_modeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#checkpointstmt.
    def enterCheckpointstmt(self, ctx:PostgreSQLParser.CheckpointstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#checkpointstmt.
    def exitCheckpointstmt(self, ctx:PostgreSQLParser.CheckpointstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#discardstmt.
    def enterDiscardstmt(self, ctx:PostgreSQLParser.DiscardstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#discardstmt.
    def exitDiscardstmt(self, ctx:PostgreSQLParser.DiscardstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altertablestmt.
    def enterAltertablestmt(self, ctx:PostgreSQLParser.AltertablestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altertablestmt.
    def exitAltertablestmt(self, ctx:PostgreSQLParser.AltertablestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_table_cmds.
    def enterAlter_table_cmds(self, ctx:PostgreSQLParser.Alter_table_cmdsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_table_cmds.
    def exitAlter_table_cmds(self, ctx:PostgreSQLParser.Alter_table_cmdsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#partition_cmd.
    def enterPartition_cmd(self, ctx:PostgreSQLParser.Partition_cmdContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#partition_cmd.
    def exitPartition_cmd(self, ctx:PostgreSQLParser.Partition_cmdContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#index_partition_cmd.
    def enterIndex_partition_cmd(self, ctx:PostgreSQLParser.Index_partition_cmdContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#index_partition_cmd.
    def exitIndex_partition_cmd(self, ctx:PostgreSQLParser.Index_partition_cmdContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_table_cmd.
    def enterAlter_table_cmd(self, ctx:PostgreSQLParser.Alter_table_cmdContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_table_cmd.
    def exitAlter_table_cmd(self, ctx:PostgreSQLParser.Alter_table_cmdContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_column_default.
    def enterAlter_column_default(self, ctx:PostgreSQLParser.Alter_column_defaultContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_column_default.
    def exitAlter_column_default(self, ctx:PostgreSQLParser.Alter_column_defaultContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#drop_behavior_.
    def enterDrop_behavior_(self, ctx:PostgreSQLParser.Drop_behavior_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#drop_behavior_.
    def exitDrop_behavior_(self, ctx:PostgreSQLParser.Drop_behavior_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#collate_clause_.
    def enterCollate_clause_(self, ctx:PostgreSQLParser.Collate_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#collate_clause_.
    def exitCollate_clause_(self, ctx:PostgreSQLParser.Collate_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_using.
    def enterAlter_using(self, ctx:PostgreSQLParser.Alter_usingContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_using.
    def exitAlter_using(self, ctx:PostgreSQLParser.Alter_usingContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#replica_identity.
    def enterReplica_identity(self, ctx:PostgreSQLParser.Replica_identityContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#replica_identity.
    def exitReplica_identity(self, ctx:PostgreSQLParser.Replica_identityContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reloptions.
    def enterReloptions(self, ctx:PostgreSQLParser.ReloptionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reloptions.
    def exitReloptions(self, ctx:PostgreSQLParser.ReloptionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reloptions_.
    def enterReloptions_(self, ctx:PostgreSQLParser.Reloptions_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reloptions_.
    def exitReloptions_(self, ctx:PostgreSQLParser.Reloptions_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reloption_list.
    def enterReloption_list(self, ctx:PostgreSQLParser.Reloption_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reloption_list.
    def exitReloption_list(self, ctx:PostgreSQLParser.Reloption_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reloption_elem.
    def enterReloption_elem(self, ctx:PostgreSQLParser.Reloption_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reloption_elem.
    def exitReloption_elem(self, ctx:PostgreSQLParser.Reloption_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_identity_column_option_list.
    def enterAlter_identity_column_option_list(self, ctx:PostgreSQLParser.Alter_identity_column_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_identity_column_option_list.
    def exitAlter_identity_column_option_list(self, ctx:PostgreSQLParser.Alter_identity_column_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_identity_column_option.
    def enterAlter_identity_column_option(self, ctx:PostgreSQLParser.Alter_identity_column_optionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_identity_column_option.
    def exitAlter_identity_column_option(self, ctx:PostgreSQLParser.Alter_identity_column_optionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#partitionboundspec.
    def enterPartitionboundspec(self, ctx:PostgreSQLParser.PartitionboundspecContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#partitionboundspec.
    def exitPartitionboundspec(self, ctx:PostgreSQLParser.PartitionboundspecContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#hash_partbound_elem.
    def enterHash_partbound_elem(self, ctx:PostgreSQLParser.Hash_partbound_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#hash_partbound_elem.
    def exitHash_partbound_elem(self, ctx:PostgreSQLParser.Hash_partbound_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#hash_partbound.
    def enterHash_partbound(self, ctx:PostgreSQLParser.Hash_partboundContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#hash_partbound.
    def exitHash_partbound(self, ctx:PostgreSQLParser.Hash_partboundContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altercompositetypestmt.
    def enterAltercompositetypestmt(self, ctx:PostgreSQLParser.AltercompositetypestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altercompositetypestmt.
    def exitAltercompositetypestmt(self, ctx:PostgreSQLParser.AltercompositetypestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_type_cmds.
    def enterAlter_type_cmds(self, ctx:PostgreSQLParser.Alter_type_cmdsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_type_cmds.
    def exitAlter_type_cmds(self, ctx:PostgreSQLParser.Alter_type_cmdsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_type_cmd.
    def enterAlter_type_cmd(self, ctx:PostgreSQLParser.Alter_type_cmdContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_type_cmd.
    def exitAlter_type_cmd(self, ctx:PostgreSQLParser.Alter_type_cmdContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#closeportalstmt.
    def enterCloseportalstmt(self, ctx:PostgreSQLParser.CloseportalstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#closeportalstmt.
    def exitCloseportalstmt(self, ctx:PostgreSQLParser.CloseportalstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copystmt.
    def enterCopystmt(self, ctx:PostgreSQLParser.CopystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copystmt.
    def exitCopystmt(self, ctx:PostgreSQLParser.CopystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_from.
    def enterCopy_from(self, ctx:PostgreSQLParser.Copy_fromContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_from.
    def exitCopy_from(self, ctx:PostgreSQLParser.Copy_fromContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#program_.
    def enterProgram_(self, ctx:PostgreSQLParser.Program_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#program_.
    def exitProgram_(self, ctx:PostgreSQLParser.Program_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_file_name.
    def enterCopy_file_name(self, ctx:PostgreSQLParser.Copy_file_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_file_name.
    def exitCopy_file_name(self, ctx:PostgreSQLParser.Copy_file_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_options.
    def enterCopy_options(self, ctx:PostgreSQLParser.Copy_optionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_options.
    def exitCopy_options(self, ctx:PostgreSQLParser.Copy_optionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_opt_list.
    def enterCopy_opt_list(self, ctx:PostgreSQLParser.Copy_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_opt_list.
    def exitCopy_opt_list(self, ctx:PostgreSQLParser.Copy_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_opt_item.
    def enterCopy_opt_item(self, ctx:PostgreSQLParser.Copy_opt_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_opt_item.
    def exitCopy_opt_item(self, ctx:PostgreSQLParser.Copy_opt_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#binary_.
    def enterBinary_(self, ctx:PostgreSQLParser.Binary_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#binary_.
    def exitBinary_(self, ctx:PostgreSQLParser.Binary_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_delimiter.
    def enterCopy_delimiter(self, ctx:PostgreSQLParser.Copy_delimiterContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_delimiter.
    def exitCopy_delimiter(self, ctx:PostgreSQLParser.Copy_delimiterContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#using_.
    def enterUsing_(self, ctx:PostgreSQLParser.Using_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#using_.
    def exitUsing_(self, ctx:PostgreSQLParser.Using_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_generic_opt_list.
    def enterCopy_generic_opt_list(self, ctx:PostgreSQLParser.Copy_generic_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_generic_opt_list.
    def exitCopy_generic_opt_list(self, ctx:PostgreSQLParser.Copy_generic_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_generic_opt_elem.
    def enterCopy_generic_opt_elem(self, ctx:PostgreSQLParser.Copy_generic_opt_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_generic_opt_elem.
    def exitCopy_generic_opt_elem(self, ctx:PostgreSQLParser.Copy_generic_opt_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_generic_opt_arg.
    def enterCopy_generic_opt_arg(self, ctx:PostgreSQLParser.Copy_generic_opt_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_generic_opt_arg.
    def exitCopy_generic_opt_arg(self, ctx:PostgreSQLParser.Copy_generic_opt_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_generic_opt_arg_list.
    def enterCopy_generic_opt_arg_list(self, ctx:PostgreSQLParser.Copy_generic_opt_arg_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_generic_opt_arg_list.
    def exitCopy_generic_opt_arg_list(self, ctx:PostgreSQLParser.Copy_generic_opt_arg_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#copy_generic_opt_arg_list_item.
    def enterCopy_generic_opt_arg_list_item(self, ctx:PostgreSQLParser.Copy_generic_opt_arg_list_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#copy_generic_opt_arg_list_item.
    def exitCopy_generic_opt_arg_list_item(self, ctx:PostgreSQLParser.Copy_generic_opt_arg_list_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createstmt.
    def enterCreatestmt(self, ctx:PostgreSQLParser.CreatestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createstmt.
    def exitCreatestmt(self, ctx:PostgreSQLParser.CreatestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opttemp.
    def enterOpttemp(self, ctx:PostgreSQLParser.OpttempContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opttemp.
    def exitOpttemp(self, ctx:PostgreSQLParser.OpttempContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opttableelementlist.
    def enterOpttableelementlist(self, ctx:PostgreSQLParser.OpttableelementlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opttableelementlist.
    def exitOpttableelementlist(self, ctx:PostgreSQLParser.OpttableelementlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opttypedtableelementlist.
    def enterOpttypedtableelementlist(self, ctx:PostgreSQLParser.OpttypedtableelementlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opttypedtableelementlist.
    def exitOpttypedtableelementlist(self, ctx:PostgreSQLParser.OpttypedtableelementlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tableelementlist.
    def enterTableelementlist(self, ctx:PostgreSQLParser.TableelementlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tableelementlist.
    def exitTableelementlist(self, ctx:PostgreSQLParser.TableelementlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#typedtableelementlist.
    def enterTypedtableelementlist(self, ctx:PostgreSQLParser.TypedtableelementlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#typedtableelementlist.
    def exitTypedtableelementlist(self, ctx:PostgreSQLParser.TypedtableelementlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tableelement.
    def enterTableelement(self, ctx:PostgreSQLParser.TableelementContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tableelement.
    def exitTableelement(self, ctx:PostgreSQLParser.TableelementContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#typedtableelement.
    def enterTypedtableelement(self, ctx:PostgreSQLParser.TypedtableelementContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#typedtableelement.
    def exitTypedtableelement(self, ctx:PostgreSQLParser.TypedtableelementContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#columnDef.
    def enterColumnDef(self, ctx:PostgreSQLParser.ColumnDefContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#columnDef.
    def exitColumnDef(self, ctx:PostgreSQLParser.ColumnDefContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#columnOptions.
    def enterColumnOptions(self, ctx:PostgreSQLParser.ColumnOptionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#columnOptions.
    def exitColumnOptions(self, ctx:PostgreSQLParser.ColumnOptionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#colquallist.
    def enterColquallist(self, ctx:PostgreSQLParser.ColquallistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#colquallist.
    def exitColquallist(self, ctx:PostgreSQLParser.ColquallistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#colconstraint.
    def enterColconstraint(self, ctx:PostgreSQLParser.ColconstraintContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#colconstraint.
    def exitColconstraint(self, ctx:PostgreSQLParser.ColconstraintContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#colconstraintelem.
    def enterColconstraintelem(self, ctx:PostgreSQLParser.ColconstraintelemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#colconstraintelem.
    def exitColconstraintelem(self, ctx:PostgreSQLParser.ColconstraintelemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generated_when.
    def enterGenerated_when(self, ctx:PostgreSQLParser.Generated_whenContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generated_when.
    def exitGenerated_when(self, ctx:PostgreSQLParser.Generated_whenContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constraintattr.
    def enterConstraintattr(self, ctx:PostgreSQLParser.ConstraintattrContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constraintattr.
    def exitConstraintattr(self, ctx:PostgreSQLParser.ConstraintattrContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tablelikeclause.
    def enterTablelikeclause(self, ctx:PostgreSQLParser.TablelikeclauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tablelikeclause.
    def exitTablelikeclause(self, ctx:PostgreSQLParser.TablelikeclauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tablelikeoptionlist.
    def enterTablelikeoptionlist(self, ctx:PostgreSQLParser.TablelikeoptionlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tablelikeoptionlist.
    def exitTablelikeoptionlist(self, ctx:PostgreSQLParser.TablelikeoptionlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tablelikeoption.
    def enterTablelikeoption(self, ctx:PostgreSQLParser.TablelikeoptionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tablelikeoption.
    def exitTablelikeoption(self, ctx:PostgreSQLParser.TablelikeoptionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tableconstraint.
    def enterTableconstraint(self, ctx:PostgreSQLParser.TableconstraintContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tableconstraint.
    def exitTableconstraint(self, ctx:PostgreSQLParser.TableconstraintContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constraintelem.
    def enterConstraintelem(self, ctx:PostgreSQLParser.ConstraintelemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constraintelem.
    def exitConstraintelem(self, ctx:PostgreSQLParser.ConstraintelemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#no_inherit_.
    def enterNo_inherit_(self, ctx:PostgreSQLParser.No_inherit_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#no_inherit_.
    def exitNo_inherit_(self, ctx:PostgreSQLParser.No_inherit_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#column_list_.
    def enterColumn_list_(self, ctx:PostgreSQLParser.Column_list_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#column_list_.
    def exitColumn_list_(self, ctx:PostgreSQLParser.Column_list_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#columnlist.
    def enterColumnlist(self, ctx:PostgreSQLParser.ColumnlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#columnlist.
    def exitColumnlist(self, ctx:PostgreSQLParser.ColumnlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#columnElem.
    def enterColumnElem(self, ctx:PostgreSQLParser.ColumnElemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#columnElem.
    def exitColumnElem(self, ctx:PostgreSQLParser.ColumnElemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#c_include_.
    def enterC_include_(self, ctx:PostgreSQLParser.C_include_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#c_include_.
    def exitC_include_(self, ctx:PostgreSQLParser.C_include_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#key_match.
    def enterKey_match(self, ctx:PostgreSQLParser.Key_matchContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#key_match.
    def exitKey_match(self, ctx:PostgreSQLParser.Key_matchContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#exclusionconstraintlist.
    def enterExclusionconstraintlist(self, ctx:PostgreSQLParser.ExclusionconstraintlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#exclusionconstraintlist.
    def exitExclusionconstraintlist(self, ctx:PostgreSQLParser.ExclusionconstraintlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#exclusionconstraintelem.
    def enterExclusionconstraintelem(self, ctx:PostgreSQLParser.ExclusionconstraintelemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#exclusionconstraintelem.
    def exitExclusionconstraintelem(self, ctx:PostgreSQLParser.ExclusionconstraintelemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#exclusionwhereclause.
    def enterExclusionwhereclause(self, ctx:PostgreSQLParser.ExclusionwhereclauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#exclusionwhereclause.
    def exitExclusionwhereclause(self, ctx:PostgreSQLParser.ExclusionwhereclauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#key_actions.
    def enterKey_actions(self, ctx:PostgreSQLParser.Key_actionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#key_actions.
    def exitKey_actions(self, ctx:PostgreSQLParser.Key_actionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#key_update.
    def enterKey_update(self, ctx:PostgreSQLParser.Key_updateContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#key_update.
    def exitKey_update(self, ctx:PostgreSQLParser.Key_updateContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#key_delete.
    def enterKey_delete(self, ctx:PostgreSQLParser.Key_deleteContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#key_delete.
    def exitKey_delete(self, ctx:PostgreSQLParser.Key_deleteContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#key_action.
    def enterKey_action(self, ctx:PostgreSQLParser.Key_actionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#key_action.
    def exitKey_action(self, ctx:PostgreSQLParser.Key_actionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optinherit.
    def enterOptinherit(self, ctx:PostgreSQLParser.OptinheritContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optinherit.
    def exitOptinherit(self, ctx:PostgreSQLParser.OptinheritContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optpartitionspec.
    def enterOptpartitionspec(self, ctx:PostgreSQLParser.OptpartitionspecContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optpartitionspec.
    def exitOptpartitionspec(self, ctx:PostgreSQLParser.OptpartitionspecContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#partitionspec.
    def enterPartitionspec(self, ctx:PostgreSQLParser.PartitionspecContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#partitionspec.
    def exitPartitionspec(self, ctx:PostgreSQLParser.PartitionspecContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#part_params.
    def enterPart_params(self, ctx:PostgreSQLParser.Part_paramsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#part_params.
    def exitPart_params(self, ctx:PostgreSQLParser.Part_paramsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#part_elem.
    def enterPart_elem(self, ctx:PostgreSQLParser.Part_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#part_elem.
    def exitPart_elem(self, ctx:PostgreSQLParser.Part_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#table_access_method_clause.
    def enterTable_access_method_clause(self, ctx:PostgreSQLParser.Table_access_method_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#table_access_method_clause.
    def exitTable_access_method_clause(self, ctx:PostgreSQLParser.Table_access_method_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optwith.
    def enterOptwith(self, ctx:PostgreSQLParser.OptwithContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optwith.
    def exitOptwith(self, ctx:PostgreSQLParser.OptwithContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#oncommitoption.
    def enterOncommitoption(self, ctx:PostgreSQLParser.OncommitoptionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#oncommitoption.
    def exitOncommitoption(self, ctx:PostgreSQLParser.OncommitoptionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opttablespace.
    def enterOpttablespace(self, ctx:PostgreSQLParser.OpttablespaceContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opttablespace.
    def exitOpttablespace(self, ctx:PostgreSQLParser.OpttablespaceContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optconstablespace.
    def enterOptconstablespace(self, ctx:PostgreSQLParser.OptconstablespaceContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optconstablespace.
    def exitOptconstablespace(self, ctx:PostgreSQLParser.OptconstablespaceContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#existingindex.
    def enterExistingindex(self, ctx:PostgreSQLParser.ExistingindexContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#existingindex.
    def exitExistingindex(self, ctx:PostgreSQLParser.ExistingindexContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createstatsstmt.
    def enterCreatestatsstmt(self, ctx:PostgreSQLParser.CreatestatsstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createstatsstmt.
    def exitCreatestatsstmt(self, ctx:PostgreSQLParser.CreatestatsstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterstatsstmt.
    def enterAlterstatsstmt(self, ctx:PostgreSQLParser.AlterstatsstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterstatsstmt.
    def exitAlterstatsstmt(self, ctx:PostgreSQLParser.AlterstatsstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createasstmt.
    def enterCreateasstmt(self, ctx:PostgreSQLParser.CreateasstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createasstmt.
    def exitCreateasstmt(self, ctx:PostgreSQLParser.CreateasstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#create_as_target.
    def enterCreate_as_target(self, ctx:PostgreSQLParser.Create_as_targetContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#create_as_target.
    def exitCreate_as_target(self, ctx:PostgreSQLParser.Create_as_targetContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#with_data_.
    def enterWith_data_(self, ctx:PostgreSQLParser.With_data_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#with_data_.
    def exitWith_data_(self, ctx:PostgreSQLParser.With_data_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#creatematviewstmt.
    def enterCreatematviewstmt(self, ctx:PostgreSQLParser.CreatematviewstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#creatematviewstmt.
    def exitCreatematviewstmt(self, ctx:PostgreSQLParser.CreatematviewstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#create_mv_target.
    def enterCreate_mv_target(self, ctx:PostgreSQLParser.Create_mv_targetContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#create_mv_target.
    def exitCreate_mv_target(self, ctx:PostgreSQLParser.Create_mv_targetContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optnolog.
    def enterOptnolog(self, ctx:PostgreSQLParser.OptnologContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optnolog.
    def exitOptnolog(self, ctx:PostgreSQLParser.OptnologContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#refreshmatviewstmt.
    def enterRefreshmatviewstmt(self, ctx:PostgreSQLParser.RefreshmatviewstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#refreshmatviewstmt.
    def exitRefreshmatviewstmt(self, ctx:PostgreSQLParser.RefreshmatviewstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createseqstmt.
    def enterCreateseqstmt(self, ctx:PostgreSQLParser.CreateseqstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createseqstmt.
    def exitCreateseqstmt(self, ctx:PostgreSQLParser.CreateseqstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterseqstmt.
    def enterAlterseqstmt(self, ctx:PostgreSQLParser.AlterseqstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterseqstmt.
    def exitAlterseqstmt(self, ctx:PostgreSQLParser.AlterseqstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optseqoptlist.
    def enterOptseqoptlist(self, ctx:PostgreSQLParser.OptseqoptlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optseqoptlist.
    def exitOptseqoptlist(self, ctx:PostgreSQLParser.OptseqoptlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optparenthesizedseqoptlist.
    def enterOptparenthesizedseqoptlist(self, ctx:PostgreSQLParser.OptparenthesizedseqoptlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optparenthesizedseqoptlist.
    def exitOptparenthesizedseqoptlist(self, ctx:PostgreSQLParser.OptparenthesizedseqoptlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#seqoptlist.
    def enterSeqoptlist(self, ctx:PostgreSQLParser.SeqoptlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#seqoptlist.
    def exitSeqoptlist(self, ctx:PostgreSQLParser.SeqoptlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#seqoptelem.
    def enterSeqoptelem(self, ctx:PostgreSQLParser.SeqoptelemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#seqoptelem.
    def exitSeqoptelem(self, ctx:PostgreSQLParser.SeqoptelemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#by_.
    def enterBy_(self, ctx:PostgreSQLParser.By_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#by_.
    def exitBy_(self, ctx:PostgreSQLParser.By_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#numericonly.
    def enterNumericonly(self, ctx:PostgreSQLParser.NumericonlyContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#numericonly.
    def exitNumericonly(self, ctx:PostgreSQLParser.NumericonlyContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#numericonly_list.
    def enterNumericonly_list(self, ctx:PostgreSQLParser.Numericonly_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#numericonly_list.
    def exitNumericonly_list(self, ctx:PostgreSQLParser.Numericonly_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createplangstmt.
    def enterCreateplangstmt(self, ctx:PostgreSQLParser.CreateplangstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createplangstmt.
    def exitCreateplangstmt(self, ctx:PostgreSQLParser.CreateplangstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#trusted_.
    def enterTrusted_(self, ctx:PostgreSQLParser.Trusted_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#trusted_.
    def exitTrusted_(self, ctx:PostgreSQLParser.Trusted_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#handler_name.
    def enterHandler_name(self, ctx:PostgreSQLParser.Handler_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#handler_name.
    def exitHandler_name(self, ctx:PostgreSQLParser.Handler_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#inline_handler_.
    def enterInline_handler_(self, ctx:PostgreSQLParser.Inline_handler_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#inline_handler_.
    def exitInline_handler_(self, ctx:PostgreSQLParser.Inline_handler_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#validator_clause.
    def enterValidator_clause(self, ctx:PostgreSQLParser.Validator_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#validator_clause.
    def exitValidator_clause(self, ctx:PostgreSQLParser.Validator_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#validator_.
    def enterValidator_(self, ctx:PostgreSQLParser.Validator_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#validator_.
    def exitValidator_(self, ctx:PostgreSQLParser.Validator_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#procedural_.
    def enterProcedural_(self, ctx:PostgreSQLParser.Procedural_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#procedural_.
    def exitProcedural_(self, ctx:PostgreSQLParser.Procedural_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createtablespacestmt.
    def enterCreatetablespacestmt(self, ctx:PostgreSQLParser.CreatetablespacestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createtablespacestmt.
    def exitCreatetablespacestmt(self, ctx:PostgreSQLParser.CreatetablespacestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opttablespaceowner.
    def enterOpttablespaceowner(self, ctx:PostgreSQLParser.OpttablespaceownerContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opttablespaceowner.
    def exitOpttablespaceowner(self, ctx:PostgreSQLParser.OpttablespaceownerContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#droptablespacestmt.
    def enterDroptablespacestmt(self, ctx:PostgreSQLParser.DroptablespacestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#droptablespacestmt.
    def exitDroptablespacestmt(self, ctx:PostgreSQLParser.DroptablespacestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createextensionstmt.
    def enterCreateextensionstmt(self, ctx:PostgreSQLParser.CreateextensionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createextensionstmt.
    def exitCreateextensionstmt(self, ctx:PostgreSQLParser.CreateextensionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#create_extension_opt_list.
    def enterCreate_extension_opt_list(self, ctx:PostgreSQLParser.Create_extension_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#create_extension_opt_list.
    def exitCreate_extension_opt_list(self, ctx:PostgreSQLParser.Create_extension_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#create_extension_opt_item.
    def enterCreate_extension_opt_item(self, ctx:PostgreSQLParser.Create_extension_opt_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#create_extension_opt_item.
    def exitCreate_extension_opt_item(self, ctx:PostgreSQLParser.Create_extension_opt_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterextensionstmt.
    def enterAlterextensionstmt(self, ctx:PostgreSQLParser.AlterextensionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterextensionstmt.
    def exitAlterextensionstmt(self, ctx:PostgreSQLParser.AlterextensionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_extension_opt_list.
    def enterAlter_extension_opt_list(self, ctx:PostgreSQLParser.Alter_extension_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_extension_opt_list.
    def exitAlter_extension_opt_list(self, ctx:PostgreSQLParser.Alter_extension_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_extension_opt_item.
    def enterAlter_extension_opt_item(self, ctx:PostgreSQLParser.Alter_extension_opt_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_extension_opt_item.
    def exitAlter_extension_opt_item(self, ctx:PostgreSQLParser.Alter_extension_opt_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterextensioncontentsstmt.
    def enterAlterextensioncontentsstmt(self, ctx:PostgreSQLParser.AlterextensioncontentsstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterextensioncontentsstmt.
    def exitAlterextensioncontentsstmt(self, ctx:PostgreSQLParser.AlterextensioncontentsstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createfdwstmt.
    def enterCreatefdwstmt(self, ctx:PostgreSQLParser.CreatefdwstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createfdwstmt.
    def exitCreatefdwstmt(self, ctx:PostgreSQLParser.CreatefdwstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#fdw_option.
    def enterFdw_option(self, ctx:PostgreSQLParser.Fdw_optionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#fdw_option.
    def exitFdw_option(self, ctx:PostgreSQLParser.Fdw_optionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#fdw_options.
    def enterFdw_options(self, ctx:PostgreSQLParser.Fdw_optionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#fdw_options.
    def exitFdw_options(self, ctx:PostgreSQLParser.Fdw_optionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#fdw_options_.
    def enterFdw_options_(self, ctx:PostgreSQLParser.Fdw_options_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#fdw_options_.
    def exitFdw_options_(self, ctx:PostgreSQLParser.Fdw_options_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterfdwstmt.
    def enterAlterfdwstmt(self, ctx:PostgreSQLParser.AlterfdwstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterfdwstmt.
    def exitAlterfdwstmt(self, ctx:PostgreSQLParser.AlterfdwstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#create_generic_options.
    def enterCreate_generic_options(self, ctx:PostgreSQLParser.Create_generic_optionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#create_generic_options.
    def exitCreate_generic_options(self, ctx:PostgreSQLParser.Create_generic_optionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generic_option_list.
    def enterGeneric_option_list(self, ctx:PostgreSQLParser.Generic_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generic_option_list.
    def exitGeneric_option_list(self, ctx:PostgreSQLParser.Generic_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_generic_options.
    def enterAlter_generic_options(self, ctx:PostgreSQLParser.Alter_generic_optionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_generic_options.
    def exitAlter_generic_options(self, ctx:PostgreSQLParser.Alter_generic_optionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_generic_option_list.
    def enterAlter_generic_option_list(self, ctx:PostgreSQLParser.Alter_generic_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_generic_option_list.
    def exitAlter_generic_option_list(self, ctx:PostgreSQLParser.Alter_generic_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alter_generic_option_elem.
    def enterAlter_generic_option_elem(self, ctx:PostgreSQLParser.Alter_generic_option_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alter_generic_option_elem.
    def exitAlter_generic_option_elem(self, ctx:PostgreSQLParser.Alter_generic_option_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generic_option_elem.
    def enterGeneric_option_elem(self, ctx:PostgreSQLParser.Generic_option_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generic_option_elem.
    def exitGeneric_option_elem(self, ctx:PostgreSQLParser.Generic_option_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generic_option_name.
    def enterGeneric_option_name(self, ctx:PostgreSQLParser.Generic_option_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generic_option_name.
    def exitGeneric_option_name(self, ctx:PostgreSQLParser.Generic_option_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generic_option_arg.
    def enterGeneric_option_arg(self, ctx:PostgreSQLParser.Generic_option_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generic_option_arg.
    def exitGeneric_option_arg(self, ctx:PostgreSQLParser.Generic_option_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createforeignserverstmt.
    def enterCreateforeignserverstmt(self, ctx:PostgreSQLParser.CreateforeignserverstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createforeignserverstmt.
    def exitCreateforeignserverstmt(self, ctx:PostgreSQLParser.CreateforeignserverstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#type_.
    def enterType_(self, ctx:PostgreSQLParser.Type_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#type_.
    def exitType_(self, ctx:PostgreSQLParser.Type_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#foreign_server_version.
    def enterForeign_server_version(self, ctx:PostgreSQLParser.Foreign_server_versionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#foreign_server_version.
    def exitForeign_server_version(self, ctx:PostgreSQLParser.Foreign_server_versionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#foreign_server_version_.
    def enterForeign_server_version_(self, ctx:PostgreSQLParser.Foreign_server_version_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#foreign_server_version_.
    def exitForeign_server_version_(self, ctx:PostgreSQLParser.Foreign_server_version_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterforeignserverstmt.
    def enterAlterforeignserverstmt(self, ctx:PostgreSQLParser.AlterforeignserverstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterforeignserverstmt.
    def exitAlterforeignserverstmt(self, ctx:PostgreSQLParser.AlterforeignserverstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createforeigntablestmt.
    def enterCreateforeigntablestmt(self, ctx:PostgreSQLParser.CreateforeigntablestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createforeigntablestmt.
    def exitCreateforeigntablestmt(self, ctx:PostgreSQLParser.CreateforeigntablestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#importforeignschemastmt.
    def enterImportforeignschemastmt(self, ctx:PostgreSQLParser.ImportforeignschemastmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#importforeignschemastmt.
    def exitImportforeignschemastmt(self, ctx:PostgreSQLParser.ImportforeignschemastmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#import_qualification_type.
    def enterImport_qualification_type(self, ctx:PostgreSQLParser.Import_qualification_typeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#import_qualification_type.
    def exitImport_qualification_type(self, ctx:PostgreSQLParser.Import_qualification_typeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#import_qualification.
    def enterImport_qualification(self, ctx:PostgreSQLParser.Import_qualificationContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#import_qualification.
    def exitImport_qualification(self, ctx:PostgreSQLParser.Import_qualificationContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createusermappingstmt.
    def enterCreateusermappingstmt(self, ctx:PostgreSQLParser.CreateusermappingstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createusermappingstmt.
    def exitCreateusermappingstmt(self, ctx:PostgreSQLParser.CreateusermappingstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#auth_ident.
    def enterAuth_ident(self, ctx:PostgreSQLParser.Auth_identContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#auth_ident.
    def exitAuth_ident(self, ctx:PostgreSQLParser.Auth_identContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropusermappingstmt.
    def enterDropusermappingstmt(self, ctx:PostgreSQLParser.DropusermappingstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropusermappingstmt.
    def exitDropusermappingstmt(self, ctx:PostgreSQLParser.DropusermappingstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterusermappingstmt.
    def enterAlterusermappingstmt(self, ctx:PostgreSQLParser.AlterusermappingstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterusermappingstmt.
    def exitAlterusermappingstmt(self, ctx:PostgreSQLParser.AlterusermappingstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createpolicystmt.
    def enterCreatepolicystmt(self, ctx:PostgreSQLParser.CreatepolicystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createpolicystmt.
    def exitCreatepolicystmt(self, ctx:PostgreSQLParser.CreatepolicystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterpolicystmt.
    def enterAlterpolicystmt(self, ctx:PostgreSQLParser.AlterpolicystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterpolicystmt.
    def exitAlterpolicystmt(self, ctx:PostgreSQLParser.AlterpolicystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsecurityoptionalexpr.
    def enterRowsecurityoptionalexpr(self, ctx:PostgreSQLParser.RowsecurityoptionalexprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsecurityoptionalexpr.
    def exitRowsecurityoptionalexpr(self, ctx:PostgreSQLParser.RowsecurityoptionalexprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsecurityoptionalwithcheck.
    def enterRowsecurityoptionalwithcheck(self, ctx:PostgreSQLParser.RowsecurityoptionalwithcheckContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsecurityoptionalwithcheck.
    def exitRowsecurityoptionalwithcheck(self, ctx:PostgreSQLParser.RowsecurityoptionalwithcheckContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsecuritydefaulttorole.
    def enterRowsecuritydefaulttorole(self, ctx:PostgreSQLParser.RowsecuritydefaulttoroleContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsecuritydefaulttorole.
    def exitRowsecuritydefaulttorole(self, ctx:PostgreSQLParser.RowsecuritydefaulttoroleContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsecurityoptionaltorole.
    def enterRowsecurityoptionaltorole(self, ctx:PostgreSQLParser.RowsecurityoptionaltoroleContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsecurityoptionaltorole.
    def exitRowsecurityoptionaltorole(self, ctx:PostgreSQLParser.RowsecurityoptionaltoroleContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsecuritydefaultpermissive.
    def enterRowsecuritydefaultpermissive(self, ctx:PostgreSQLParser.RowsecuritydefaultpermissiveContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsecuritydefaultpermissive.
    def exitRowsecuritydefaultpermissive(self, ctx:PostgreSQLParser.RowsecuritydefaultpermissiveContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsecuritydefaultforcmd.
    def enterRowsecuritydefaultforcmd(self, ctx:PostgreSQLParser.RowsecuritydefaultforcmdContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsecuritydefaultforcmd.
    def exitRowsecuritydefaultforcmd(self, ctx:PostgreSQLParser.RowsecuritydefaultforcmdContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#row_security_cmd.
    def enterRow_security_cmd(self, ctx:PostgreSQLParser.Row_security_cmdContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#row_security_cmd.
    def exitRow_security_cmd(self, ctx:PostgreSQLParser.Row_security_cmdContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createamstmt.
    def enterCreateamstmt(self, ctx:PostgreSQLParser.CreateamstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createamstmt.
    def exitCreateamstmt(self, ctx:PostgreSQLParser.CreateamstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#am_type.
    def enterAm_type(self, ctx:PostgreSQLParser.Am_typeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#am_type.
    def exitAm_type(self, ctx:PostgreSQLParser.Am_typeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createtrigstmt.
    def enterCreatetrigstmt(self, ctx:PostgreSQLParser.CreatetrigstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createtrigstmt.
    def exitCreatetrigstmt(self, ctx:PostgreSQLParser.CreatetrigstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggeractiontime.
    def enterTriggeractiontime(self, ctx:PostgreSQLParser.TriggeractiontimeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggeractiontime.
    def exitTriggeractiontime(self, ctx:PostgreSQLParser.TriggeractiontimeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerevents.
    def enterTriggerevents(self, ctx:PostgreSQLParser.TriggereventsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerevents.
    def exitTriggerevents(self, ctx:PostgreSQLParser.TriggereventsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggeroneevent.
    def enterTriggeroneevent(self, ctx:PostgreSQLParser.TriggeroneeventContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggeroneevent.
    def exitTriggeroneevent(self, ctx:PostgreSQLParser.TriggeroneeventContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerreferencing.
    def enterTriggerreferencing(self, ctx:PostgreSQLParser.TriggerreferencingContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerreferencing.
    def exitTriggerreferencing(self, ctx:PostgreSQLParser.TriggerreferencingContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggertransitions.
    def enterTriggertransitions(self, ctx:PostgreSQLParser.TriggertransitionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggertransitions.
    def exitTriggertransitions(self, ctx:PostgreSQLParser.TriggertransitionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggertransition.
    def enterTriggertransition(self, ctx:PostgreSQLParser.TriggertransitionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggertransition.
    def exitTriggertransition(self, ctx:PostgreSQLParser.TriggertransitionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transitionoldornew.
    def enterTransitionoldornew(self, ctx:PostgreSQLParser.TransitionoldornewContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transitionoldornew.
    def exitTransitionoldornew(self, ctx:PostgreSQLParser.TransitionoldornewContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transitionrowortable.
    def enterTransitionrowortable(self, ctx:PostgreSQLParser.TransitionrowortableContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transitionrowortable.
    def exitTransitionrowortable(self, ctx:PostgreSQLParser.TransitionrowortableContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transitionrelname.
    def enterTransitionrelname(self, ctx:PostgreSQLParser.TransitionrelnameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transitionrelname.
    def exitTransitionrelname(self, ctx:PostgreSQLParser.TransitionrelnameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerforspec.
    def enterTriggerforspec(self, ctx:PostgreSQLParser.TriggerforspecContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerforspec.
    def exitTriggerforspec(self, ctx:PostgreSQLParser.TriggerforspecContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerforopteach.
    def enterTriggerforopteach(self, ctx:PostgreSQLParser.TriggerforopteachContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerforopteach.
    def exitTriggerforopteach(self, ctx:PostgreSQLParser.TriggerforopteachContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerfortype.
    def enterTriggerfortype(self, ctx:PostgreSQLParser.TriggerfortypeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerfortype.
    def exitTriggerfortype(self, ctx:PostgreSQLParser.TriggerfortypeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerwhen.
    def enterTriggerwhen(self, ctx:PostgreSQLParser.TriggerwhenContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerwhen.
    def exitTriggerwhen(self, ctx:PostgreSQLParser.TriggerwhenContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#function_or_procedure.
    def enterFunction_or_procedure(self, ctx:PostgreSQLParser.Function_or_procedureContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#function_or_procedure.
    def exitFunction_or_procedure(self, ctx:PostgreSQLParser.Function_or_procedureContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerfuncargs.
    def enterTriggerfuncargs(self, ctx:PostgreSQLParser.TriggerfuncargsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerfuncargs.
    def exitTriggerfuncargs(self, ctx:PostgreSQLParser.TriggerfuncargsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#triggerfuncarg.
    def enterTriggerfuncarg(self, ctx:PostgreSQLParser.TriggerfuncargContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#triggerfuncarg.
    def exitTriggerfuncarg(self, ctx:PostgreSQLParser.TriggerfuncargContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#optconstrfromtable.
    def enterOptconstrfromtable(self, ctx:PostgreSQLParser.OptconstrfromtableContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#optconstrfromtable.
    def exitOptconstrfromtable(self, ctx:PostgreSQLParser.OptconstrfromtableContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constraintattributespec.
    def enterConstraintattributespec(self, ctx:PostgreSQLParser.ConstraintattributespecContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constraintattributespec.
    def exitConstraintattributespec(self, ctx:PostgreSQLParser.ConstraintattributespecContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constraintattributeElem.
    def enterConstraintattributeElem(self, ctx:PostgreSQLParser.ConstraintattributeElemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constraintattributeElem.
    def exitConstraintattributeElem(self, ctx:PostgreSQLParser.ConstraintattributeElemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createeventtrigstmt.
    def enterCreateeventtrigstmt(self, ctx:PostgreSQLParser.CreateeventtrigstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createeventtrigstmt.
    def exitCreateeventtrigstmt(self, ctx:PostgreSQLParser.CreateeventtrigstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#event_trigger_when_list.
    def enterEvent_trigger_when_list(self, ctx:PostgreSQLParser.Event_trigger_when_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#event_trigger_when_list.
    def exitEvent_trigger_when_list(self, ctx:PostgreSQLParser.Event_trigger_when_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#event_trigger_when_item.
    def enterEvent_trigger_when_item(self, ctx:PostgreSQLParser.Event_trigger_when_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#event_trigger_when_item.
    def exitEvent_trigger_when_item(self, ctx:PostgreSQLParser.Event_trigger_when_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#event_trigger_value_list.
    def enterEvent_trigger_value_list(self, ctx:PostgreSQLParser.Event_trigger_value_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#event_trigger_value_list.
    def exitEvent_trigger_value_list(self, ctx:PostgreSQLParser.Event_trigger_value_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altereventtrigstmt.
    def enterAltereventtrigstmt(self, ctx:PostgreSQLParser.AltereventtrigstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altereventtrigstmt.
    def exitAltereventtrigstmt(self, ctx:PostgreSQLParser.AltereventtrigstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#enable_trigger.
    def enterEnable_trigger(self, ctx:PostgreSQLParser.Enable_triggerContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#enable_trigger.
    def exitEnable_trigger(self, ctx:PostgreSQLParser.Enable_triggerContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createassertionstmt.
    def enterCreateassertionstmt(self, ctx:PostgreSQLParser.CreateassertionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createassertionstmt.
    def exitCreateassertionstmt(self, ctx:PostgreSQLParser.CreateassertionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#definestmt.
    def enterDefinestmt(self, ctx:PostgreSQLParser.DefinestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#definestmt.
    def exitDefinestmt(self, ctx:PostgreSQLParser.DefinestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#definition.
    def enterDefinition(self, ctx:PostgreSQLParser.DefinitionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#definition.
    def exitDefinition(self, ctx:PostgreSQLParser.DefinitionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#def_list.
    def enterDef_list(self, ctx:PostgreSQLParser.Def_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#def_list.
    def exitDef_list(self, ctx:PostgreSQLParser.Def_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#def_elem.
    def enterDef_elem(self, ctx:PostgreSQLParser.Def_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#def_elem.
    def exitDef_elem(self, ctx:PostgreSQLParser.Def_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#def_arg.
    def enterDef_arg(self, ctx:PostgreSQLParser.Def_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#def_arg.
    def exitDef_arg(self, ctx:PostgreSQLParser.Def_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#old_aggr_definition.
    def enterOld_aggr_definition(self, ctx:PostgreSQLParser.Old_aggr_definitionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#old_aggr_definition.
    def exitOld_aggr_definition(self, ctx:PostgreSQLParser.Old_aggr_definitionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#old_aggr_list.
    def enterOld_aggr_list(self, ctx:PostgreSQLParser.Old_aggr_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#old_aggr_list.
    def exitOld_aggr_list(self, ctx:PostgreSQLParser.Old_aggr_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#old_aggr_elem.
    def enterOld_aggr_elem(self, ctx:PostgreSQLParser.Old_aggr_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#old_aggr_elem.
    def exitOld_aggr_elem(self, ctx:PostgreSQLParser.Old_aggr_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#enum_val_list_.
    def enterEnum_val_list_(self, ctx:PostgreSQLParser.Enum_val_list_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#enum_val_list_.
    def exitEnum_val_list_(self, ctx:PostgreSQLParser.Enum_val_list_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#enum_val_list.
    def enterEnum_val_list(self, ctx:PostgreSQLParser.Enum_val_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#enum_val_list.
    def exitEnum_val_list(self, ctx:PostgreSQLParser.Enum_val_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterenumstmt.
    def enterAlterenumstmt(self, ctx:PostgreSQLParser.AlterenumstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterenumstmt.
    def exitAlterenumstmt(self, ctx:PostgreSQLParser.AlterenumstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#if_not_exists_.
    def enterIf_not_exists_(self, ctx:PostgreSQLParser.If_not_exists_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#if_not_exists_.
    def exitIf_not_exists_(self, ctx:PostgreSQLParser.If_not_exists_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createopclassstmt.
    def enterCreateopclassstmt(self, ctx:PostgreSQLParser.CreateopclassstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createopclassstmt.
    def exitCreateopclassstmt(self, ctx:PostgreSQLParser.CreateopclassstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opclass_item_list.
    def enterOpclass_item_list(self, ctx:PostgreSQLParser.Opclass_item_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opclass_item_list.
    def exitOpclass_item_list(self, ctx:PostgreSQLParser.Opclass_item_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opclass_item.
    def enterOpclass_item(self, ctx:PostgreSQLParser.Opclass_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opclass_item.
    def exitOpclass_item(self, ctx:PostgreSQLParser.Opclass_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#default_.
    def enterDefault_(self, ctx:PostgreSQLParser.Default_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#default_.
    def exitDefault_(self, ctx:PostgreSQLParser.Default_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opfamily_.
    def enterOpfamily_(self, ctx:PostgreSQLParser.Opfamily_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opfamily_.
    def exitOpfamily_(self, ctx:PostgreSQLParser.Opfamily_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opclass_purpose.
    def enterOpclass_purpose(self, ctx:PostgreSQLParser.Opclass_purposeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opclass_purpose.
    def exitOpclass_purpose(self, ctx:PostgreSQLParser.Opclass_purposeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#recheck_.
    def enterRecheck_(self, ctx:PostgreSQLParser.Recheck_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#recheck_.
    def exitRecheck_(self, ctx:PostgreSQLParser.Recheck_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createopfamilystmt.
    def enterCreateopfamilystmt(self, ctx:PostgreSQLParser.CreateopfamilystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createopfamilystmt.
    def exitCreateopfamilystmt(self, ctx:PostgreSQLParser.CreateopfamilystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alteropfamilystmt.
    def enterAlteropfamilystmt(self, ctx:PostgreSQLParser.AlteropfamilystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alteropfamilystmt.
    def exitAlteropfamilystmt(self, ctx:PostgreSQLParser.AlteropfamilystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opclass_drop_list.
    def enterOpclass_drop_list(self, ctx:PostgreSQLParser.Opclass_drop_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opclass_drop_list.
    def exitOpclass_drop_list(self, ctx:PostgreSQLParser.Opclass_drop_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opclass_drop.
    def enterOpclass_drop(self, ctx:PostgreSQLParser.Opclass_dropContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opclass_drop.
    def exitOpclass_drop(self, ctx:PostgreSQLParser.Opclass_dropContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropopclassstmt.
    def enterDropopclassstmt(self, ctx:PostgreSQLParser.DropopclassstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropopclassstmt.
    def exitDropopclassstmt(self, ctx:PostgreSQLParser.DropopclassstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropopfamilystmt.
    def enterDropopfamilystmt(self, ctx:PostgreSQLParser.DropopfamilystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropopfamilystmt.
    def exitDropopfamilystmt(self, ctx:PostgreSQLParser.DropopfamilystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropownedstmt.
    def enterDropownedstmt(self, ctx:PostgreSQLParser.DropownedstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropownedstmt.
    def exitDropownedstmt(self, ctx:PostgreSQLParser.DropownedstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reassignownedstmt.
    def enterReassignownedstmt(self, ctx:PostgreSQLParser.ReassignownedstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reassignownedstmt.
    def exitReassignownedstmt(self, ctx:PostgreSQLParser.ReassignownedstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropstmt.
    def enterDropstmt(self, ctx:PostgreSQLParser.DropstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropstmt.
    def exitDropstmt(self, ctx:PostgreSQLParser.DropstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#object_type_any_name.
    def enterObject_type_any_name(self, ctx:PostgreSQLParser.Object_type_any_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#object_type_any_name.
    def exitObject_type_any_name(self, ctx:PostgreSQLParser.Object_type_any_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#object_type_name.
    def enterObject_type_name(self, ctx:PostgreSQLParser.Object_type_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#object_type_name.
    def exitObject_type_name(self, ctx:PostgreSQLParser.Object_type_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#drop_type_name.
    def enterDrop_type_name(self, ctx:PostgreSQLParser.Drop_type_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#drop_type_name.
    def exitDrop_type_name(self, ctx:PostgreSQLParser.Drop_type_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#object_type_name_on_any_name.
    def enterObject_type_name_on_any_name(self, ctx:PostgreSQLParser.Object_type_name_on_any_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#object_type_name_on_any_name.
    def exitObject_type_name_on_any_name(self, ctx:PostgreSQLParser.Object_type_name_on_any_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#any_name_list_.
    def enterAny_name_list_(self, ctx:PostgreSQLParser.Any_name_list_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#any_name_list_.
    def exitAny_name_list_(self, ctx:PostgreSQLParser.Any_name_list_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#any_name.
    def enterAny_name(self, ctx:PostgreSQLParser.Any_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#any_name.
    def exitAny_name(self, ctx:PostgreSQLParser.Any_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#attrs.
    def enterAttrs(self, ctx:PostgreSQLParser.AttrsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#attrs.
    def exitAttrs(self, ctx:PostgreSQLParser.AttrsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#type_name_list.
    def enterType_name_list(self, ctx:PostgreSQLParser.Type_name_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#type_name_list.
    def exitType_name_list(self, ctx:PostgreSQLParser.Type_name_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#truncatestmt.
    def enterTruncatestmt(self, ctx:PostgreSQLParser.TruncatestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#truncatestmt.
    def exitTruncatestmt(self, ctx:PostgreSQLParser.TruncatestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#restart_seqs_.
    def enterRestart_seqs_(self, ctx:PostgreSQLParser.Restart_seqs_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#restart_seqs_.
    def exitRestart_seqs_(self, ctx:PostgreSQLParser.Restart_seqs_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#commentstmt.
    def enterCommentstmt(self, ctx:PostgreSQLParser.CommentstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#commentstmt.
    def exitCommentstmt(self, ctx:PostgreSQLParser.CommentstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#comment_text.
    def enterComment_text(self, ctx:PostgreSQLParser.Comment_textContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#comment_text.
    def exitComment_text(self, ctx:PostgreSQLParser.Comment_textContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#seclabelstmt.
    def enterSeclabelstmt(self, ctx:PostgreSQLParser.SeclabelstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#seclabelstmt.
    def exitSeclabelstmt(self, ctx:PostgreSQLParser.SeclabelstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#provider_.
    def enterProvider_(self, ctx:PostgreSQLParser.Provider_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#provider_.
    def exitProvider_(self, ctx:PostgreSQLParser.Provider_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#security_label.
    def enterSecurity_label(self, ctx:PostgreSQLParser.Security_labelContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#security_label.
    def exitSecurity_label(self, ctx:PostgreSQLParser.Security_labelContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#fetchstmt.
    def enterFetchstmt(self, ctx:PostgreSQLParser.FetchstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#fetchstmt.
    def exitFetchstmt(self, ctx:PostgreSQLParser.FetchstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#fetch_args.
    def enterFetch_args(self, ctx:PostgreSQLParser.Fetch_argsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#fetch_args.
    def exitFetch_args(self, ctx:PostgreSQLParser.Fetch_argsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#from_in.
    def enterFrom_in(self, ctx:PostgreSQLParser.From_inContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#from_in.
    def exitFrom_in(self, ctx:PostgreSQLParser.From_inContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#from_in_.
    def enterFrom_in_(self, ctx:PostgreSQLParser.From_in_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#from_in_.
    def exitFrom_in_(self, ctx:PostgreSQLParser.From_in_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#grantstmt.
    def enterGrantstmt(self, ctx:PostgreSQLParser.GrantstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#grantstmt.
    def exitGrantstmt(self, ctx:PostgreSQLParser.GrantstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#revokestmt.
    def enterRevokestmt(self, ctx:PostgreSQLParser.RevokestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#revokestmt.
    def exitRevokestmt(self, ctx:PostgreSQLParser.RevokestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#privileges.
    def enterPrivileges(self, ctx:PostgreSQLParser.PrivilegesContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#privileges.
    def exitPrivileges(self, ctx:PostgreSQLParser.PrivilegesContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#privilege_list.
    def enterPrivilege_list(self, ctx:PostgreSQLParser.Privilege_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#privilege_list.
    def exitPrivilege_list(self, ctx:PostgreSQLParser.Privilege_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#privilege.
    def enterPrivilege(self, ctx:PostgreSQLParser.PrivilegeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#privilege.
    def exitPrivilege(self, ctx:PostgreSQLParser.PrivilegeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#privilege_target.
    def enterPrivilege_target(self, ctx:PostgreSQLParser.Privilege_targetContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#privilege_target.
    def exitPrivilege_target(self, ctx:PostgreSQLParser.Privilege_targetContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#grantee_list.
    def enterGrantee_list(self, ctx:PostgreSQLParser.Grantee_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#grantee_list.
    def exitGrantee_list(self, ctx:PostgreSQLParser.Grantee_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#grantee.
    def enterGrantee(self, ctx:PostgreSQLParser.GranteeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#grantee.
    def exitGrantee(self, ctx:PostgreSQLParser.GranteeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#grant_grant_option_.
    def enterGrant_grant_option_(self, ctx:PostgreSQLParser.Grant_grant_option_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#grant_grant_option_.
    def exitGrant_grant_option_(self, ctx:PostgreSQLParser.Grant_grant_option_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#grantrolestmt.
    def enterGrantrolestmt(self, ctx:PostgreSQLParser.GrantrolestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#grantrolestmt.
    def exitGrantrolestmt(self, ctx:PostgreSQLParser.GrantrolestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#revokerolestmt.
    def enterRevokerolestmt(self, ctx:PostgreSQLParser.RevokerolestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#revokerolestmt.
    def exitRevokerolestmt(self, ctx:PostgreSQLParser.RevokerolestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#grant_admin_option_.
    def enterGrant_admin_option_(self, ctx:PostgreSQLParser.Grant_admin_option_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#grant_admin_option_.
    def exitGrant_admin_option_(self, ctx:PostgreSQLParser.Grant_admin_option_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#granted_by_.
    def enterGranted_by_(self, ctx:PostgreSQLParser.Granted_by_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#granted_by_.
    def exitGranted_by_(self, ctx:PostgreSQLParser.Granted_by_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterdefaultprivilegesstmt.
    def enterAlterdefaultprivilegesstmt(self, ctx:PostgreSQLParser.AlterdefaultprivilegesstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterdefaultprivilegesstmt.
    def exitAlterdefaultprivilegesstmt(self, ctx:PostgreSQLParser.AlterdefaultprivilegesstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#defacloptionlist.
    def enterDefacloptionlist(self, ctx:PostgreSQLParser.DefacloptionlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#defacloptionlist.
    def exitDefacloptionlist(self, ctx:PostgreSQLParser.DefacloptionlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#defacloption.
    def enterDefacloption(self, ctx:PostgreSQLParser.DefacloptionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#defacloption.
    def exitDefacloption(self, ctx:PostgreSQLParser.DefacloptionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#defaclaction.
    def enterDefaclaction(self, ctx:PostgreSQLParser.DefaclactionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#defaclaction.
    def exitDefaclaction(self, ctx:PostgreSQLParser.DefaclactionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#defacl_privilege_target.
    def enterDefacl_privilege_target(self, ctx:PostgreSQLParser.Defacl_privilege_targetContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#defacl_privilege_target.
    def exitDefacl_privilege_target(self, ctx:PostgreSQLParser.Defacl_privilege_targetContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#indexstmt.
    def enterIndexstmt(self, ctx:PostgreSQLParser.IndexstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#indexstmt.
    def exitIndexstmt(self, ctx:PostgreSQLParser.IndexstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#unique_.
    def enterUnique_(self, ctx:PostgreSQLParser.Unique_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#unique_.
    def exitUnique_(self, ctx:PostgreSQLParser.Unique_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#nulls_distinct.
    def enterNulls_distinct(self, ctx:PostgreSQLParser.Nulls_distinctContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#nulls_distinct.
    def exitNulls_distinct(self, ctx:PostgreSQLParser.Nulls_distinctContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#single_name_.
    def enterSingle_name_(self, ctx:PostgreSQLParser.Single_name_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#single_name_.
    def exitSingle_name_(self, ctx:PostgreSQLParser.Single_name_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#concurrently_.
    def enterConcurrently_(self, ctx:PostgreSQLParser.Concurrently_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#concurrently_.
    def exitConcurrently_(self, ctx:PostgreSQLParser.Concurrently_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#index_name_.
    def enterIndex_name_(self, ctx:PostgreSQLParser.Index_name_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#index_name_.
    def exitIndex_name_(self, ctx:PostgreSQLParser.Index_name_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#access_method_clause.
    def enterAccess_method_clause(self, ctx:PostgreSQLParser.Access_method_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#access_method_clause.
    def exitAccess_method_clause(self, ctx:PostgreSQLParser.Access_method_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#index_params.
    def enterIndex_params(self, ctx:PostgreSQLParser.Index_paramsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#index_params.
    def exitIndex_params(self, ctx:PostgreSQLParser.Index_paramsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#index_elem_options.
    def enterIndex_elem_options(self, ctx:PostgreSQLParser.Index_elem_optionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#index_elem_options.
    def exitIndex_elem_options(self, ctx:PostgreSQLParser.Index_elem_optionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#index_elem.
    def enterIndex_elem(self, ctx:PostgreSQLParser.Index_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#index_elem.
    def exitIndex_elem(self, ctx:PostgreSQLParser.Index_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#include_.
    def enterInclude_(self, ctx:PostgreSQLParser.Include_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#include_.
    def exitInclude_(self, ctx:PostgreSQLParser.Include_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#index_including_params.
    def enterIndex_including_params(self, ctx:PostgreSQLParser.Index_including_paramsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#index_including_params.
    def exitIndex_including_params(self, ctx:PostgreSQLParser.Index_including_paramsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#collate_.
    def enterCollate_(self, ctx:PostgreSQLParser.Collate_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#collate_.
    def exitCollate_(self, ctx:PostgreSQLParser.Collate_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#class_.
    def enterClass_(self, ctx:PostgreSQLParser.Class_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#class_.
    def exitClass_(self, ctx:PostgreSQLParser.Class_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#asc_desc_.
    def enterAsc_desc_(self, ctx:PostgreSQLParser.Asc_desc_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#asc_desc_.
    def exitAsc_desc_(self, ctx:PostgreSQLParser.Asc_desc_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#nulls_order_.
    def enterNulls_order_(self, ctx:PostgreSQLParser.Nulls_order_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#nulls_order_.
    def exitNulls_order_(self, ctx:PostgreSQLParser.Nulls_order_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createfunctionstmt.
    def enterCreatefunctionstmt(self, ctx:PostgreSQLParser.CreatefunctionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createfunctionstmt.
    def exitCreatefunctionstmt(self, ctx:PostgreSQLParser.CreatefunctionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#or_replace_.
    def enterOr_replace_(self, ctx:PostgreSQLParser.Or_replace_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#or_replace_.
    def exitOr_replace_(self, ctx:PostgreSQLParser.Or_replace_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_args.
    def enterFunc_args(self, ctx:PostgreSQLParser.Func_argsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_args.
    def exitFunc_args(self, ctx:PostgreSQLParser.Func_argsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_args_list.
    def enterFunc_args_list(self, ctx:PostgreSQLParser.Func_args_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_args_list.
    def exitFunc_args_list(self, ctx:PostgreSQLParser.Func_args_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#function_with_argtypes_list.
    def enterFunction_with_argtypes_list(self, ctx:PostgreSQLParser.Function_with_argtypes_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#function_with_argtypes_list.
    def exitFunction_with_argtypes_list(self, ctx:PostgreSQLParser.Function_with_argtypes_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#function_with_argtypes.
    def enterFunction_with_argtypes(self, ctx:PostgreSQLParser.Function_with_argtypesContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#function_with_argtypes.
    def exitFunction_with_argtypes(self, ctx:PostgreSQLParser.Function_with_argtypesContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_args_with_defaults.
    def enterFunc_args_with_defaults(self, ctx:PostgreSQLParser.Func_args_with_defaultsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_args_with_defaults.
    def exitFunc_args_with_defaults(self, ctx:PostgreSQLParser.Func_args_with_defaultsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_args_with_defaults_list.
    def enterFunc_args_with_defaults_list(self, ctx:PostgreSQLParser.Func_args_with_defaults_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_args_with_defaults_list.
    def exitFunc_args_with_defaults_list(self, ctx:PostgreSQLParser.Func_args_with_defaults_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_arg.
    def enterFunc_arg(self, ctx:PostgreSQLParser.Func_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_arg.
    def exitFunc_arg(self, ctx:PostgreSQLParser.Func_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#arg_class.
    def enterArg_class(self, ctx:PostgreSQLParser.Arg_classContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#arg_class.
    def exitArg_class(self, ctx:PostgreSQLParser.Arg_classContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#param_name.
    def enterParam_name(self, ctx:PostgreSQLParser.Param_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#param_name.
    def exitParam_name(self, ctx:PostgreSQLParser.Param_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_return.
    def enterFunc_return(self, ctx:PostgreSQLParser.Func_returnContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_return.
    def exitFunc_return(self, ctx:PostgreSQLParser.Func_returnContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_type.
    def enterFunc_type(self, ctx:PostgreSQLParser.Func_typeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_type.
    def exitFunc_type(self, ctx:PostgreSQLParser.Func_typeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_arg_with_default.
    def enterFunc_arg_with_default(self, ctx:PostgreSQLParser.Func_arg_with_defaultContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_arg_with_default.
    def exitFunc_arg_with_default(self, ctx:PostgreSQLParser.Func_arg_with_defaultContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#aggr_arg.
    def enterAggr_arg(self, ctx:PostgreSQLParser.Aggr_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#aggr_arg.
    def exitAggr_arg(self, ctx:PostgreSQLParser.Aggr_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#aggr_args.
    def enterAggr_args(self, ctx:PostgreSQLParser.Aggr_argsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#aggr_args.
    def exitAggr_args(self, ctx:PostgreSQLParser.Aggr_argsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#aggr_args_list.
    def enterAggr_args_list(self, ctx:PostgreSQLParser.Aggr_args_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#aggr_args_list.
    def exitAggr_args_list(self, ctx:PostgreSQLParser.Aggr_args_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#aggregate_with_argtypes.
    def enterAggregate_with_argtypes(self, ctx:PostgreSQLParser.Aggregate_with_argtypesContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#aggregate_with_argtypes.
    def exitAggregate_with_argtypes(self, ctx:PostgreSQLParser.Aggregate_with_argtypesContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#aggregate_with_argtypes_list.
    def enterAggregate_with_argtypes_list(self, ctx:PostgreSQLParser.Aggregate_with_argtypes_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#aggregate_with_argtypes_list.
    def exitAggregate_with_argtypes_list(self, ctx:PostgreSQLParser.Aggregate_with_argtypes_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createfunc_opt_list.
    def enterCreatefunc_opt_list(self, ctx:PostgreSQLParser.Createfunc_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createfunc_opt_list.
    def exitCreatefunc_opt_list(self, ctx:PostgreSQLParser.Createfunc_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#common_func_opt_item.
    def enterCommon_func_opt_item(self, ctx:PostgreSQLParser.Common_func_opt_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#common_func_opt_item.
    def exitCommon_func_opt_item(self, ctx:PostgreSQLParser.Common_func_opt_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createfunc_opt_item.
    def enterCreatefunc_opt_item(self, ctx:PostgreSQLParser.Createfunc_opt_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createfunc_opt_item.
    def exitCreatefunc_opt_item(self, ctx:PostgreSQLParser.Createfunc_opt_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_as.
    def enterFunc_as(self, ctx:PostgreSQLParser.Func_asContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_as.
    def exitFunc_as(self, ctx:PostgreSQLParser.Func_asContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transform_type_list.
    def enterTransform_type_list(self, ctx:PostgreSQLParser.Transform_type_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transform_type_list.
    def exitTransform_type_list(self, ctx:PostgreSQLParser.Transform_type_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#definition_.
    def enterDefinition_(self, ctx:PostgreSQLParser.Definition_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#definition_.
    def exitDefinition_(self, ctx:PostgreSQLParser.Definition_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#table_func_column.
    def enterTable_func_column(self, ctx:PostgreSQLParser.Table_func_columnContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#table_func_column.
    def exitTable_func_column(self, ctx:PostgreSQLParser.Table_func_columnContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#table_func_column_list.
    def enterTable_func_column_list(self, ctx:PostgreSQLParser.Table_func_column_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#table_func_column_list.
    def exitTable_func_column_list(self, ctx:PostgreSQLParser.Table_func_column_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterfunctionstmt.
    def enterAlterfunctionstmt(self, ctx:PostgreSQLParser.AlterfunctionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterfunctionstmt.
    def exitAlterfunctionstmt(self, ctx:PostgreSQLParser.AlterfunctionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterfunc_opt_list.
    def enterAlterfunc_opt_list(self, ctx:PostgreSQLParser.Alterfunc_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterfunc_opt_list.
    def exitAlterfunc_opt_list(self, ctx:PostgreSQLParser.Alterfunc_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#restrict_.
    def enterRestrict_(self, ctx:PostgreSQLParser.Restrict_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#restrict_.
    def exitRestrict_(self, ctx:PostgreSQLParser.Restrict_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#removefuncstmt.
    def enterRemovefuncstmt(self, ctx:PostgreSQLParser.RemovefuncstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#removefuncstmt.
    def exitRemovefuncstmt(self, ctx:PostgreSQLParser.RemovefuncstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#removeaggrstmt.
    def enterRemoveaggrstmt(self, ctx:PostgreSQLParser.RemoveaggrstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#removeaggrstmt.
    def exitRemoveaggrstmt(self, ctx:PostgreSQLParser.RemoveaggrstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#removeoperstmt.
    def enterRemoveoperstmt(self, ctx:PostgreSQLParser.RemoveoperstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#removeoperstmt.
    def exitRemoveoperstmt(self, ctx:PostgreSQLParser.RemoveoperstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#oper_argtypes.
    def enterOper_argtypes(self, ctx:PostgreSQLParser.Oper_argtypesContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#oper_argtypes.
    def exitOper_argtypes(self, ctx:PostgreSQLParser.Oper_argtypesContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#any_operator.
    def enterAny_operator(self, ctx:PostgreSQLParser.Any_operatorContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#any_operator.
    def exitAny_operator(self, ctx:PostgreSQLParser.Any_operatorContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#operator_with_argtypes_list.
    def enterOperator_with_argtypes_list(self, ctx:PostgreSQLParser.Operator_with_argtypes_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#operator_with_argtypes_list.
    def exitOperator_with_argtypes_list(self, ctx:PostgreSQLParser.Operator_with_argtypes_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#operator_with_argtypes.
    def enterOperator_with_argtypes(self, ctx:PostgreSQLParser.Operator_with_argtypesContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#operator_with_argtypes.
    def exitOperator_with_argtypes(self, ctx:PostgreSQLParser.Operator_with_argtypesContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dostmt.
    def enterDostmt(self, ctx:PostgreSQLParser.DostmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dostmt.
    def exitDostmt(self, ctx:PostgreSQLParser.DostmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dostmt_opt_list.
    def enterDostmt_opt_list(self, ctx:PostgreSQLParser.Dostmt_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dostmt_opt_list.
    def exitDostmt_opt_list(self, ctx:PostgreSQLParser.Dostmt_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dostmt_opt_item.
    def enterDostmt_opt_item(self, ctx:PostgreSQLParser.Dostmt_opt_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dostmt_opt_item.
    def exitDostmt_opt_item(self, ctx:PostgreSQLParser.Dostmt_opt_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createcaststmt.
    def enterCreatecaststmt(self, ctx:PostgreSQLParser.CreatecaststmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createcaststmt.
    def exitCreatecaststmt(self, ctx:PostgreSQLParser.CreatecaststmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#cast_context.
    def enterCast_context(self, ctx:PostgreSQLParser.Cast_contextContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#cast_context.
    def exitCast_context(self, ctx:PostgreSQLParser.Cast_contextContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropcaststmt.
    def enterDropcaststmt(self, ctx:PostgreSQLParser.DropcaststmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropcaststmt.
    def exitDropcaststmt(self, ctx:PostgreSQLParser.DropcaststmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#if_exists_.
    def enterIf_exists_(self, ctx:PostgreSQLParser.If_exists_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#if_exists_.
    def exitIf_exists_(self, ctx:PostgreSQLParser.If_exists_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createtransformstmt.
    def enterCreatetransformstmt(self, ctx:PostgreSQLParser.CreatetransformstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createtransformstmt.
    def exitCreatetransformstmt(self, ctx:PostgreSQLParser.CreatetransformstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transform_element_list.
    def enterTransform_element_list(self, ctx:PostgreSQLParser.Transform_element_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transform_element_list.
    def exitTransform_element_list(self, ctx:PostgreSQLParser.Transform_element_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#droptransformstmt.
    def enterDroptransformstmt(self, ctx:PostgreSQLParser.DroptransformstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#droptransformstmt.
    def exitDroptransformstmt(self, ctx:PostgreSQLParser.DroptransformstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reindexstmt.
    def enterReindexstmt(self, ctx:PostgreSQLParser.ReindexstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reindexstmt.
    def exitReindexstmt(self, ctx:PostgreSQLParser.ReindexstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reindex_target_relation.
    def enterReindex_target_relation(self, ctx:PostgreSQLParser.Reindex_target_relationContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reindex_target_relation.
    def exitReindex_target_relation(self, ctx:PostgreSQLParser.Reindex_target_relationContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reindex_target_all.
    def enterReindex_target_all(self, ctx:PostgreSQLParser.Reindex_target_allContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reindex_target_all.
    def exitReindex_target_all(self, ctx:PostgreSQLParser.Reindex_target_allContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reindex_option_list.
    def enterReindex_option_list(self, ctx:PostgreSQLParser.Reindex_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reindex_option_list.
    def exitReindex_option_list(self, ctx:PostgreSQLParser.Reindex_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altertblspcstmt.
    def enterAltertblspcstmt(self, ctx:PostgreSQLParser.AltertblspcstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altertblspcstmt.
    def exitAltertblspcstmt(self, ctx:PostgreSQLParser.AltertblspcstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#renamestmt.
    def enterRenamestmt(self, ctx:PostgreSQLParser.RenamestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#renamestmt.
    def exitRenamestmt(self, ctx:PostgreSQLParser.RenamestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#column_.
    def enterColumn_(self, ctx:PostgreSQLParser.Column_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#column_.
    def exitColumn_(self, ctx:PostgreSQLParser.Column_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#set_data_.
    def enterSet_data_(self, ctx:PostgreSQLParser.Set_data_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#set_data_.
    def exitSet_data_(self, ctx:PostgreSQLParser.Set_data_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterobjectdependsstmt.
    def enterAlterobjectdependsstmt(self, ctx:PostgreSQLParser.AlterobjectdependsstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterobjectdependsstmt.
    def exitAlterobjectdependsstmt(self, ctx:PostgreSQLParser.AlterobjectdependsstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#no_.
    def enterNo_(self, ctx:PostgreSQLParser.No_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#no_.
    def exitNo_(self, ctx:PostgreSQLParser.No_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterobjectschemastmt.
    def enterAlterobjectschemastmt(self, ctx:PostgreSQLParser.AlterobjectschemastmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterobjectschemastmt.
    def exitAlterobjectschemastmt(self, ctx:PostgreSQLParser.AlterobjectschemastmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alteroperatorstmt.
    def enterAlteroperatorstmt(self, ctx:PostgreSQLParser.AlteroperatorstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alteroperatorstmt.
    def exitAlteroperatorstmt(self, ctx:PostgreSQLParser.AlteroperatorstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#operator_def_list.
    def enterOperator_def_list(self, ctx:PostgreSQLParser.Operator_def_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#operator_def_list.
    def exitOperator_def_list(self, ctx:PostgreSQLParser.Operator_def_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#operator_def_elem.
    def enterOperator_def_elem(self, ctx:PostgreSQLParser.Operator_def_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#operator_def_elem.
    def exitOperator_def_elem(self, ctx:PostgreSQLParser.Operator_def_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#operator_def_arg.
    def enterOperator_def_arg(self, ctx:PostgreSQLParser.Operator_def_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#operator_def_arg.
    def exitOperator_def_arg(self, ctx:PostgreSQLParser.Operator_def_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altertypestmt.
    def enterAltertypestmt(self, ctx:PostgreSQLParser.AltertypestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altertypestmt.
    def exitAltertypestmt(self, ctx:PostgreSQLParser.AltertypestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterownerstmt.
    def enterAlterownerstmt(self, ctx:PostgreSQLParser.AlterownerstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterownerstmt.
    def exitAlterownerstmt(self, ctx:PostgreSQLParser.AlterownerstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createpublicationstmt.
    def enterCreatepublicationstmt(self, ctx:PostgreSQLParser.CreatepublicationstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createpublicationstmt.
    def exitCreatepublicationstmt(self, ctx:PostgreSQLParser.CreatepublicationstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#publication_for_tables_.
    def enterPublication_for_tables_(self, ctx:PostgreSQLParser.Publication_for_tables_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#publication_for_tables_.
    def exitPublication_for_tables_(self, ctx:PostgreSQLParser.Publication_for_tables_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#publication_for_tables.
    def enterPublication_for_tables(self, ctx:PostgreSQLParser.Publication_for_tablesContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#publication_for_tables.
    def exitPublication_for_tables(self, ctx:PostgreSQLParser.Publication_for_tablesContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterpublicationstmt.
    def enterAlterpublicationstmt(self, ctx:PostgreSQLParser.AlterpublicationstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterpublicationstmt.
    def exitAlterpublicationstmt(self, ctx:PostgreSQLParser.AlterpublicationstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createsubscriptionstmt.
    def enterCreatesubscriptionstmt(self, ctx:PostgreSQLParser.CreatesubscriptionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createsubscriptionstmt.
    def exitCreatesubscriptionstmt(self, ctx:PostgreSQLParser.CreatesubscriptionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#publication_name_list.
    def enterPublication_name_list(self, ctx:PostgreSQLParser.Publication_name_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#publication_name_list.
    def exitPublication_name_list(self, ctx:PostgreSQLParser.Publication_name_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#publication_name_item.
    def enterPublication_name_item(self, ctx:PostgreSQLParser.Publication_name_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#publication_name_item.
    def exitPublication_name_item(self, ctx:PostgreSQLParser.Publication_name_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altersubscriptionstmt.
    def enterAltersubscriptionstmt(self, ctx:PostgreSQLParser.AltersubscriptionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altersubscriptionstmt.
    def exitAltersubscriptionstmt(self, ctx:PostgreSQLParser.AltersubscriptionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropsubscriptionstmt.
    def enterDropsubscriptionstmt(self, ctx:PostgreSQLParser.DropsubscriptionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropsubscriptionstmt.
    def exitDropsubscriptionstmt(self, ctx:PostgreSQLParser.DropsubscriptionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rulestmt.
    def enterRulestmt(self, ctx:PostgreSQLParser.RulestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rulestmt.
    def exitRulestmt(self, ctx:PostgreSQLParser.RulestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#ruleactionlist.
    def enterRuleactionlist(self, ctx:PostgreSQLParser.RuleactionlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#ruleactionlist.
    def exitRuleactionlist(self, ctx:PostgreSQLParser.RuleactionlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#ruleactionmulti.
    def enterRuleactionmulti(self, ctx:PostgreSQLParser.RuleactionmultiContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#ruleactionmulti.
    def exitRuleactionmulti(self, ctx:PostgreSQLParser.RuleactionmultiContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#ruleactionstmt.
    def enterRuleactionstmt(self, ctx:PostgreSQLParser.RuleactionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#ruleactionstmt.
    def exitRuleactionstmt(self, ctx:PostgreSQLParser.RuleactionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#ruleactionstmtOrEmpty.
    def enterRuleactionstmtOrEmpty(self, ctx:PostgreSQLParser.RuleactionstmtOrEmptyContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#ruleactionstmtOrEmpty.
    def exitRuleactionstmtOrEmpty(self, ctx:PostgreSQLParser.RuleactionstmtOrEmptyContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#event.
    def enterEvent(self, ctx:PostgreSQLParser.EventContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#event.
    def exitEvent(self, ctx:PostgreSQLParser.EventContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#instead_.
    def enterInstead_(self, ctx:PostgreSQLParser.Instead_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#instead_.
    def exitInstead_(self, ctx:PostgreSQLParser.Instead_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#notifystmt.
    def enterNotifystmt(self, ctx:PostgreSQLParser.NotifystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#notifystmt.
    def exitNotifystmt(self, ctx:PostgreSQLParser.NotifystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#notify_payload.
    def enterNotify_payload(self, ctx:PostgreSQLParser.Notify_payloadContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#notify_payload.
    def exitNotify_payload(self, ctx:PostgreSQLParser.Notify_payloadContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#listenstmt.
    def enterListenstmt(self, ctx:PostgreSQLParser.ListenstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#listenstmt.
    def exitListenstmt(self, ctx:PostgreSQLParser.ListenstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#unlistenstmt.
    def enterUnlistenstmt(self, ctx:PostgreSQLParser.UnlistenstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#unlistenstmt.
    def exitUnlistenstmt(self, ctx:PostgreSQLParser.UnlistenstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transactionstmt.
    def enterTransactionstmt(self, ctx:PostgreSQLParser.TransactionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transactionstmt.
    def exitTransactionstmt(self, ctx:PostgreSQLParser.TransactionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transaction_.
    def enterTransaction_(self, ctx:PostgreSQLParser.Transaction_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transaction_.
    def exitTransaction_(self, ctx:PostgreSQLParser.Transaction_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transaction_mode_item.
    def enterTransaction_mode_item(self, ctx:PostgreSQLParser.Transaction_mode_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transaction_mode_item.
    def exitTransaction_mode_item(self, ctx:PostgreSQLParser.Transaction_mode_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transaction_mode_list.
    def enterTransaction_mode_list(self, ctx:PostgreSQLParser.Transaction_mode_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transaction_mode_list.
    def exitTransaction_mode_list(self, ctx:PostgreSQLParser.Transaction_mode_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transaction_mode_list_or_empty.
    def enterTransaction_mode_list_or_empty(self, ctx:PostgreSQLParser.Transaction_mode_list_or_emptyContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transaction_mode_list_or_empty.
    def exitTransaction_mode_list_or_empty(self, ctx:PostgreSQLParser.Transaction_mode_list_or_emptyContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#transaction_chain_.
    def enterTransaction_chain_(self, ctx:PostgreSQLParser.Transaction_chain_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#transaction_chain_.
    def exitTransaction_chain_(self, ctx:PostgreSQLParser.Transaction_chain_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#viewstmt.
    def enterViewstmt(self, ctx:PostgreSQLParser.ViewstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#viewstmt.
    def exitViewstmt(self, ctx:PostgreSQLParser.ViewstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#check_option_.
    def enterCheck_option_(self, ctx:PostgreSQLParser.Check_option_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#check_option_.
    def exitCheck_option_(self, ctx:PostgreSQLParser.Check_option_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#loadstmt.
    def enterLoadstmt(self, ctx:PostgreSQLParser.LoadstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#loadstmt.
    def exitLoadstmt(self, ctx:PostgreSQLParser.LoadstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createdbstmt.
    def enterCreatedbstmt(self, ctx:PostgreSQLParser.CreatedbstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createdbstmt.
    def exitCreatedbstmt(self, ctx:PostgreSQLParser.CreatedbstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createdb_opt_list.
    def enterCreatedb_opt_list(self, ctx:PostgreSQLParser.Createdb_opt_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createdb_opt_list.
    def exitCreatedb_opt_list(self, ctx:PostgreSQLParser.Createdb_opt_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createdb_opt_items.
    def enterCreatedb_opt_items(self, ctx:PostgreSQLParser.Createdb_opt_itemsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createdb_opt_items.
    def exitCreatedb_opt_items(self, ctx:PostgreSQLParser.Createdb_opt_itemsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createdb_opt_item.
    def enterCreatedb_opt_item(self, ctx:PostgreSQLParser.Createdb_opt_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createdb_opt_item.
    def exitCreatedb_opt_item(self, ctx:PostgreSQLParser.Createdb_opt_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createdb_opt_name.
    def enterCreatedb_opt_name(self, ctx:PostgreSQLParser.Createdb_opt_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createdb_opt_name.
    def exitCreatedb_opt_name(self, ctx:PostgreSQLParser.Createdb_opt_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#equal_.
    def enterEqual_(self, ctx:PostgreSQLParser.Equal_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#equal_.
    def exitEqual_(self, ctx:PostgreSQLParser.Equal_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterdatabasestmt.
    def enterAlterdatabasestmt(self, ctx:PostgreSQLParser.AlterdatabasestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterdatabasestmt.
    def exitAlterdatabasestmt(self, ctx:PostgreSQLParser.AlterdatabasestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterdatabasesetstmt.
    def enterAlterdatabasesetstmt(self, ctx:PostgreSQLParser.AlterdatabasesetstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterdatabasesetstmt.
    def exitAlterdatabasesetstmt(self, ctx:PostgreSQLParser.AlterdatabasesetstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#dropdbstmt.
    def enterDropdbstmt(self, ctx:PostgreSQLParser.DropdbstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#dropdbstmt.
    def exitDropdbstmt(self, ctx:PostgreSQLParser.DropdbstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#drop_option_list.
    def enterDrop_option_list(self, ctx:PostgreSQLParser.Drop_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#drop_option_list.
    def exitDrop_option_list(self, ctx:PostgreSQLParser.Drop_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#drop_option.
    def enterDrop_option(self, ctx:PostgreSQLParser.Drop_optionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#drop_option.
    def exitDrop_option(self, ctx:PostgreSQLParser.Drop_optionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altercollationstmt.
    def enterAltercollationstmt(self, ctx:PostgreSQLParser.AltercollationstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altercollationstmt.
    def exitAltercollationstmt(self, ctx:PostgreSQLParser.AltercollationstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altersystemstmt.
    def enterAltersystemstmt(self, ctx:PostgreSQLParser.AltersystemstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altersystemstmt.
    def exitAltersystemstmt(self, ctx:PostgreSQLParser.AltersystemstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createdomainstmt.
    def enterCreatedomainstmt(self, ctx:PostgreSQLParser.CreatedomainstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createdomainstmt.
    def exitCreatedomainstmt(self, ctx:PostgreSQLParser.CreatedomainstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alterdomainstmt.
    def enterAlterdomainstmt(self, ctx:PostgreSQLParser.AlterdomainstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alterdomainstmt.
    def exitAlterdomainstmt(self, ctx:PostgreSQLParser.AlterdomainstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#as_.
    def enterAs_(self, ctx:PostgreSQLParser.As_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#as_.
    def exitAs_(self, ctx:PostgreSQLParser.As_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altertsdictionarystmt.
    def enterAltertsdictionarystmt(self, ctx:PostgreSQLParser.AltertsdictionarystmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altertsdictionarystmt.
    def exitAltertsdictionarystmt(self, ctx:PostgreSQLParser.AltertsdictionarystmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#altertsconfigurationstmt.
    def enterAltertsconfigurationstmt(self, ctx:PostgreSQLParser.AltertsconfigurationstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#altertsconfigurationstmt.
    def exitAltertsconfigurationstmt(self, ctx:PostgreSQLParser.AltertsconfigurationstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#any_with.
    def enterAny_with(self, ctx:PostgreSQLParser.Any_withContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#any_with.
    def exitAny_with(self, ctx:PostgreSQLParser.Any_withContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#createconversionstmt.
    def enterCreateconversionstmt(self, ctx:PostgreSQLParser.CreateconversionstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#createconversionstmt.
    def exitCreateconversionstmt(self, ctx:PostgreSQLParser.CreateconversionstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#clusterstmt.
    def enterClusterstmt(self, ctx:PostgreSQLParser.ClusterstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#clusterstmt.
    def exitClusterstmt(self, ctx:PostgreSQLParser.ClusterstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#cluster_index_specification.
    def enterCluster_index_specification(self, ctx:PostgreSQLParser.Cluster_index_specificationContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#cluster_index_specification.
    def exitCluster_index_specification(self, ctx:PostgreSQLParser.Cluster_index_specificationContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vacuumstmt.
    def enterVacuumstmt(self, ctx:PostgreSQLParser.VacuumstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vacuumstmt.
    def exitVacuumstmt(self, ctx:PostgreSQLParser.VacuumstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#analyzestmt.
    def enterAnalyzestmt(self, ctx:PostgreSQLParser.AnalyzestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#analyzestmt.
    def exitAnalyzestmt(self, ctx:PostgreSQLParser.AnalyzestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#utility_option_list.
    def enterUtility_option_list(self, ctx:PostgreSQLParser.Utility_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#utility_option_list.
    def exitUtility_option_list(self, ctx:PostgreSQLParser.Utility_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vac_analyze_option_list.
    def enterVac_analyze_option_list(self, ctx:PostgreSQLParser.Vac_analyze_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vac_analyze_option_list.
    def exitVac_analyze_option_list(self, ctx:PostgreSQLParser.Vac_analyze_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#analyze_keyword.
    def enterAnalyze_keyword(self, ctx:PostgreSQLParser.Analyze_keywordContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#analyze_keyword.
    def exitAnalyze_keyword(self, ctx:PostgreSQLParser.Analyze_keywordContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#utility_option_elem.
    def enterUtility_option_elem(self, ctx:PostgreSQLParser.Utility_option_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#utility_option_elem.
    def exitUtility_option_elem(self, ctx:PostgreSQLParser.Utility_option_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#utility_option_name.
    def enterUtility_option_name(self, ctx:PostgreSQLParser.Utility_option_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#utility_option_name.
    def exitUtility_option_name(self, ctx:PostgreSQLParser.Utility_option_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#utility_option_arg.
    def enterUtility_option_arg(self, ctx:PostgreSQLParser.Utility_option_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#utility_option_arg.
    def exitUtility_option_arg(self, ctx:PostgreSQLParser.Utility_option_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vac_analyze_option_elem.
    def enterVac_analyze_option_elem(self, ctx:PostgreSQLParser.Vac_analyze_option_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vac_analyze_option_elem.
    def exitVac_analyze_option_elem(self, ctx:PostgreSQLParser.Vac_analyze_option_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vac_analyze_option_name.
    def enterVac_analyze_option_name(self, ctx:PostgreSQLParser.Vac_analyze_option_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vac_analyze_option_name.
    def exitVac_analyze_option_name(self, ctx:PostgreSQLParser.Vac_analyze_option_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vac_analyze_option_arg.
    def enterVac_analyze_option_arg(self, ctx:PostgreSQLParser.Vac_analyze_option_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vac_analyze_option_arg.
    def exitVac_analyze_option_arg(self, ctx:PostgreSQLParser.Vac_analyze_option_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#analyze_.
    def enterAnalyze_(self, ctx:PostgreSQLParser.Analyze_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#analyze_.
    def exitAnalyze_(self, ctx:PostgreSQLParser.Analyze_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#verbose_.
    def enterVerbose_(self, ctx:PostgreSQLParser.Verbose_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#verbose_.
    def exitVerbose_(self, ctx:PostgreSQLParser.Verbose_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#full_.
    def enterFull_(self, ctx:PostgreSQLParser.Full_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#full_.
    def exitFull_(self, ctx:PostgreSQLParser.Full_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#freeze_.
    def enterFreeze_(self, ctx:PostgreSQLParser.Freeze_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#freeze_.
    def exitFreeze_(self, ctx:PostgreSQLParser.Freeze_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#name_list_.
    def enterName_list_(self, ctx:PostgreSQLParser.Name_list_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#name_list_.
    def exitName_list_(self, ctx:PostgreSQLParser.Name_list_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vacuum_relation.
    def enterVacuum_relation(self, ctx:PostgreSQLParser.Vacuum_relationContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vacuum_relation.
    def exitVacuum_relation(self, ctx:PostgreSQLParser.Vacuum_relationContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vacuum_relation_list.
    def enterVacuum_relation_list(self, ctx:PostgreSQLParser.Vacuum_relation_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vacuum_relation_list.
    def exitVacuum_relation_list(self, ctx:PostgreSQLParser.Vacuum_relation_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#vacuum_relation_list_.
    def enterVacuum_relation_list_(self, ctx:PostgreSQLParser.Vacuum_relation_list_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#vacuum_relation_list_.
    def exitVacuum_relation_list_(self, ctx:PostgreSQLParser.Vacuum_relation_list_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#explainstmt.
    def enterExplainstmt(self, ctx:PostgreSQLParser.ExplainstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#explainstmt.
    def exitExplainstmt(self, ctx:PostgreSQLParser.ExplainstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#explainablestmt.
    def enterExplainablestmt(self, ctx:PostgreSQLParser.ExplainablestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#explainablestmt.
    def exitExplainablestmt(self, ctx:PostgreSQLParser.ExplainablestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#explain_option_list.
    def enterExplain_option_list(self, ctx:PostgreSQLParser.Explain_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#explain_option_list.
    def exitExplain_option_list(self, ctx:PostgreSQLParser.Explain_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#explain_option_elem.
    def enterExplain_option_elem(self, ctx:PostgreSQLParser.Explain_option_elemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#explain_option_elem.
    def exitExplain_option_elem(self, ctx:PostgreSQLParser.Explain_option_elemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#explain_option_name.
    def enterExplain_option_name(self, ctx:PostgreSQLParser.Explain_option_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#explain_option_name.
    def exitExplain_option_name(self, ctx:PostgreSQLParser.Explain_option_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#explain_option_arg.
    def enterExplain_option_arg(self, ctx:PostgreSQLParser.Explain_option_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#explain_option_arg.
    def exitExplain_option_arg(self, ctx:PostgreSQLParser.Explain_option_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#preparestmt.
    def enterPreparestmt(self, ctx:PostgreSQLParser.PreparestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#preparestmt.
    def exitPreparestmt(self, ctx:PostgreSQLParser.PreparestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#prep_type_clause.
    def enterPrep_type_clause(self, ctx:PostgreSQLParser.Prep_type_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#prep_type_clause.
    def exitPrep_type_clause(self, ctx:PostgreSQLParser.Prep_type_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#preparablestmt.
    def enterPreparablestmt(self, ctx:PostgreSQLParser.PreparablestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#preparablestmt.
    def exitPreparablestmt(self, ctx:PostgreSQLParser.PreparablestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#executestmt.
    def enterExecutestmt(self, ctx:PostgreSQLParser.ExecutestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#executestmt.
    def exitExecutestmt(self, ctx:PostgreSQLParser.ExecutestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#execute_param_clause.
    def enterExecute_param_clause(self, ctx:PostgreSQLParser.Execute_param_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#execute_param_clause.
    def exitExecute_param_clause(self, ctx:PostgreSQLParser.Execute_param_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#deallocatestmt.
    def enterDeallocatestmt(self, ctx:PostgreSQLParser.DeallocatestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#deallocatestmt.
    def exitDeallocatestmt(self, ctx:PostgreSQLParser.DeallocatestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#insertstmt.
    def enterInsertstmt(self, ctx:PostgreSQLParser.InsertstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#insertstmt.
    def exitInsertstmt(self, ctx:PostgreSQLParser.InsertstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#insert_target.
    def enterInsert_target(self, ctx:PostgreSQLParser.Insert_targetContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#insert_target.
    def exitInsert_target(self, ctx:PostgreSQLParser.Insert_targetContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#insert_rest.
    def enterInsert_rest(self, ctx:PostgreSQLParser.Insert_restContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#insert_rest.
    def exitInsert_rest(self, ctx:PostgreSQLParser.Insert_restContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#override_kind.
    def enterOverride_kind(self, ctx:PostgreSQLParser.Override_kindContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#override_kind.
    def exitOverride_kind(self, ctx:PostgreSQLParser.Override_kindContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#insert_column_list.
    def enterInsert_column_list(self, ctx:PostgreSQLParser.Insert_column_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#insert_column_list.
    def exitInsert_column_list(self, ctx:PostgreSQLParser.Insert_column_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#insert_column_item.
    def enterInsert_column_item(self, ctx:PostgreSQLParser.Insert_column_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#insert_column_item.
    def exitInsert_column_item(self, ctx:PostgreSQLParser.Insert_column_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#on_conflict_.
    def enterOn_conflict_(self, ctx:PostgreSQLParser.On_conflict_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#on_conflict_.
    def exitOn_conflict_(self, ctx:PostgreSQLParser.On_conflict_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#conf_expr_.
    def enterConf_expr_(self, ctx:PostgreSQLParser.Conf_expr_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#conf_expr_.
    def exitConf_expr_(self, ctx:PostgreSQLParser.Conf_expr_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#returning_clause.
    def enterReturning_clause(self, ctx:PostgreSQLParser.Returning_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#returning_clause.
    def exitReturning_clause(self, ctx:PostgreSQLParser.Returning_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#mergestmt.
    def enterMergestmt(self, ctx:PostgreSQLParser.MergestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#mergestmt.
    def exitMergestmt(self, ctx:PostgreSQLParser.MergestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#merge_insert_clause.
    def enterMerge_insert_clause(self, ctx:PostgreSQLParser.Merge_insert_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#merge_insert_clause.
    def exitMerge_insert_clause(self, ctx:PostgreSQLParser.Merge_insert_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#merge_update_clause.
    def enterMerge_update_clause(self, ctx:PostgreSQLParser.Merge_update_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#merge_update_clause.
    def exitMerge_update_clause(self, ctx:PostgreSQLParser.Merge_update_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#merge_delete_clause.
    def enterMerge_delete_clause(self, ctx:PostgreSQLParser.Merge_delete_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#merge_delete_clause.
    def exitMerge_delete_clause(self, ctx:PostgreSQLParser.Merge_delete_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#deletestmt.
    def enterDeletestmt(self, ctx:PostgreSQLParser.DeletestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#deletestmt.
    def exitDeletestmt(self, ctx:PostgreSQLParser.DeletestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#using_clause.
    def enterUsing_clause(self, ctx:PostgreSQLParser.Using_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#using_clause.
    def exitUsing_clause(self, ctx:PostgreSQLParser.Using_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#lockstmt.
    def enterLockstmt(self, ctx:PostgreSQLParser.LockstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#lockstmt.
    def exitLockstmt(self, ctx:PostgreSQLParser.LockstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#lock_.
    def enterLock_(self, ctx:PostgreSQLParser.Lock_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#lock_.
    def exitLock_(self, ctx:PostgreSQLParser.Lock_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#lock_type.
    def enterLock_type(self, ctx:PostgreSQLParser.Lock_typeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#lock_type.
    def exitLock_type(self, ctx:PostgreSQLParser.Lock_typeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#nowait_.
    def enterNowait_(self, ctx:PostgreSQLParser.Nowait_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#nowait_.
    def exitNowait_(self, ctx:PostgreSQLParser.Nowait_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#nowait_or_skip_.
    def enterNowait_or_skip_(self, ctx:PostgreSQLParser.Nowait_or_skip_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#nowait_or_skip_.
    def exitNowait_or_skip_(self, ctx:PostgreSQLParser.Nowait_or_skip_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#updatestmt.
    def enterUpdatestmt(self, ctx:PostgreSQLParser.UpdatestmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#updatestmt.
    def exitUpdatestmt(self, ctx:PostgreSQLParser.UpdatestmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#set_clause_list.
    def enterSet_clause_list(self, ctx:PostgreSQLParser.Set_clause_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#set_clause_list.
    def exitSet_clause_list(self, ctx:PostgreSQLParser.Set_clause_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#set_clause.
    def enterSet_clause(self, ctx:PostgreSQLParser.Set_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#set_clause.
    def exitSet_clause(self, ctx:PostgreSQLParser.Set_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#set_target.
    def enterSet_target(self, ctx:PostgreSQLParser.Set_targetContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#set_target.
    def exitSet_target(self, ctx:PostgreSQLParser.Set_targetContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#set_target_list.
    def enterSet_target_list(self, ctx:PostgreSQLParser.Set_target_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#set_target_list.
    def exitSet_target_list(self, ctx:PostgreSQLParser.Set_target_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#declarecursorstmt.
    def enterDeclarecursorstmt(self, ctx:PostgreSQLParser.DeclarecursorstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#declarecursorstmt.
    def exitDeclarecursorstmt(self, ctx:PostgreSQLParser.DeclarecursorstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#cursor_name.
    def enterCursor_name(self, ctx:PostgreSQLParser.Cursor_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#cursor_name.
    def exitCursor_name(self, ctx:PostgreSQLParser.Cursor_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#cursor_options.
    def enterCursor_options(self, ctx:PostgreSQLParser.Cursor_optionsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#cursor_options.
    def exitCursor_options(self, ctx:PostgreSQLParser.Cursor_optionsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#hold_.
    def enterHold_(self, ctx:PostgreSQLParser.Hold_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#hold_.
    def exitHold_(self, ctx:PostgreSQLParser.Hold_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#selectstmt.
    def enterSelectstmt(self, ctx:PostgreSQLParser.SelectstmtContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#selectstmt.
    def exitSelectstmt(self, ctx:PostgreSQLParser.SelectstmtContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_with_parens.
    def enterSelect_with_parens(self, ctx:PostgreSQLParser.Select_with_parensContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_with_parens.
    def exitSelect_with_parens(self, ctx:PostgreSQLParser.Select_with_parensContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_no_parens.
    def enterSelect_no_parens(self, ctx:PostgreSQLParser.Select_no_parensContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_no_parens.
    def exitSelect_no_parens(self, ctx:PostgreSQLParser.Select_no_parensContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_clause.
    def enterSelect_clause(self, ctx:PostgreSQLParser.Select_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_clause.
    def exitSelect_clause(self, ctx:PostgreSQLParser.Select_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#simple_select_intersect.
    def enterSimple_select_intersect(self, ctx:PostgreSQLParser.Simple_select_intersectContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#simple_select_intersect.
    def exitSimple_select_intersect(self, ctx:PostgreSQLParser.Simple_select_intersectContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#simple_select_pramary.
    def enterSimple_select_pramary(self, ctx:PostgreSQLParser.Simple_select_pramaryContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#simple_select_pramary.
    def exitSimple_select_pramary(self, ctx:PostgreSQLParser.Simple_select_pramaryContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#with_clause.
    def enterWith_clause(self, ctx:PostgreSQLParser.With_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#with_clause.
    def exitWith_clause(self, ctx:PostgreSQLParser.With_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#cte_list.
    def enterCte_list(self, ctx:PostgreSQLParser.Cte_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#cte_list.
    def exitCte_list(self, ctx:PostgreSQLParser.Cte_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#common_table_expr.
    def enterCommon_table_expr(self, ctx:PostgreSQLParser.Common_table_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#common_table_expr.
    def exitCommon_table_expr(self, ctx:PostgreSQLParser.Common_table_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#materialized_.
    def enterMaterialized_(self, ctx:PostgreSQLParser.Materialized_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#materialized_.
    def exitMaterialized_(self, ctx:PostgreSQLParser.Materialized_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#with_clause_.
    def enterWith_clause_(self, ctx:PostgreSQLParser.With_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#with_clause_.
    def exitWith_clause_(self, ctx:PostgreSQLParser.With_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#into_clause.
    def enterInto_clause(self, ctx:PostgreSQLParser.Into_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#into_clause.
    def exitInto_clause(self, ctx:PostgreSQLParser.Into_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#strict_.
    def enterStrict_(self, ctx:PostgreSQLParser.Strict_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#strict_.
    def exitStrict_(self, ctx:PostgreSQLParser.Strict_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opttempTableName.
    def enterOpttempTableName(self, ctx:PostgreSQLParser.OpttempTableNameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opttempTableName.
    def exitOpttempTableName(self, ctx:PostgreSQLParser.OpttempTableNameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#table_.
    def enterTable_(self, ctx:PostgreSQLParser.Table_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#table_.
    def exitTable_(self, ctx:PostgreSQLParser.Table_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#all_or_distinct.
    def enterAll_or_distinct(self, ctx:PostgreSQLParser.All_or_distinctContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#all_or_distinct.
    def exitAll_or_distinct(self, ctx:PostgreSQLParser.All_or_distinctContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#distinct_clause.
    def enterDistinct_clause(self, ctx:PostgreSQLParser.Distinct_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#distinct_clause.
    def exitDistinct_clause(self, ctx:PostgreSQLParser.Distinct_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#all_clause_.
    def enterAll_clause_(self, ctx:PostgreSQLParser.All_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#all_clause_.
    def exitAll_clause_(self, ctx:PostgreSQLParser.All_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#sort_clause_.
    def enterSort_clause_(self, ctx:PostgreSQLParser.Sort_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#sort_clause_.
    def exitSort_clause_(self, ctx:PostgreSQLParser.Sort_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#sort_clause.
    def enterSort_clause(self, ctx:PostgreSQLParser.Sort_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#sort_clause.
    def exitSort_clause(self, ctx:PostgreSQLParser.Sort_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#sortby_list.
    def enterSortby_list(self, ctx:PostgreSQLParser.Sortby_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#sortby_list.
    def exitSortby_list(self, ctx:PostgreSQLParser.Sortby_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#sortby.
    def enterSortby(self, ctx:PostgreSQLParser.SortbyContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#sortby.
    def exitSortby(self, ctx:PostgreSQLParser.SortbyContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_limit.
    def enterSelect_limit(self, ctx:PostgreSQLParser.Select_limitContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_limit.
    def exitSelect_limit(self, ctx:PostgreSQLParser.Select_limitContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_limit_.
    def enterSelect_limit_(self, ctx:PostgreSQLParser.Select_limit_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_limit_.
    def exitSelect_limit_(self, ctx:PostgreSQLParser.Select_limit_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#limit_clause.
    def enterLimit_clause(self, ctx:PostgreSQLParser.Limit_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#limit_clause.
    def exitLimit_clause(self, ctx:PostgreSQLParser.Limit_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#offset_clause.
    def enterOffset_clause(self, ctx:PostgreSQLParser.Offset_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#offset_clause.
    def exitOffset_clause(self, ctx:PostgreSQLParser.Offset_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_limit_value.
    def enterSelect_limit_value(self, ctx:PostgreSQLParser.Select_limit_valueContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_limit_value.
    def exitSelect_limit_value(self, ctx:PostgreSQLParser.Select_limit_valueContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_offset_value.
    def enterSelect_offset_value(self, ctx:PostgreSQLParser.Select_offset_valueContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_offset_value.
    def exitSelect_offset_value(self, ctx:PostgreSQLParser.Select_offset_valueContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#select_fetch_first_value.
    def enterSelect_fetch_first_value(self, ctx:PostgreSQLParser.Select_fetch_first_valueContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#select_fetch_first_value.
    def exitSelect_fetch_first_value(self, ctx:PostgreSQLParser.Select_fetch_first_valueContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#i_or_f_const.
    def enterI_or_f_const(self, ctx:PostgreSQLParser.I_or_f_constContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#i_or_f_const.
    def exitI_or_f_const(self, ctx:PostgreSQLParser.I_or_f_constContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#row_or_rows.
    def enterRow_or_rows(self, ctx:PostgreSQLParser.Row_or_rowsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#row_or_rows.
    def exitRow_or_rows(self, ctx:PostgreSQLParser.Row_or_rowsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#first_or_next.
    def enterFirst_or_next(self, ctx:PostgreSQLParser.First_or_nextContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#first_or_next.
    def exitFirst_or_next(self, ctx:PostgreSQLParser.First_or_nextContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#group_clause.
    def enterGroup_clause(self, ctx:PostgreSQLParser.Group_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#group_clause.
    def exitGroup_clause(self, ctx:PostgreSQLParser.Group_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#group_by_list.
    def enterGroup_by_list(self, ctx:PostgreSQLParser.Group_by_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#group_by_list.
    def exitGroup_by_list(self, ctx:PostgreSQLParser.Group_by_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#group_by_item.
    def enterGroup_by_item(self, ctx:PostgreSQLParser.Group_by_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#group_by_item.
    def exitGroup_by_item(self, ctx:PostgreSQLParser.Group_by_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#empty_grouping_set.
    def enterEmpty_grouping_set(self, ctx:PostgreSQLParser.Empty_grouping_setContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#empty_grouping_set.
    def exitEmpty_grouping_set(self, ctx:PostgreSQLParser.Empty_grouping_setContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rollup_clause.
    def enterRollup_clause(self, ctx:PostgreSQLParser.Rollup_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rollup_clause.
    def exitRollup_clause(self, ctx:PostgreSQLParser.Rollup_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#cube_clause.
    def enterCube_clause(self, ctx:PostgreSQLParser.Cube_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#cube_clause.
    def exitCube_clause(self, ctx:PostgreSQLParser.Cube_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#grouping_sets_clause.
    def enterGrouping_sets_clause(self, ctx:PostgreSQLParser.Grouping_sets_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#grouping_sets_clause.
    def exitGrouping_sets_clause(self, ctx:PostgreSQLParser.Grouping_sets_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#having_clause.
    def enterHaving_clause(self, ctx:PostgreSQLParser.Having_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#having_clause.
    def exitHaving_clause(self, ctx:PostgreSQLParser.Having_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#for_locking_clause.
    def enterFor_locking_clause(self, ctx:PostgreSQLParser.For_locking_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#for_locking_clause.
    def exitFor_locking_clause(self, ctx:PostgreSQLParser.For_locking_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#for_locking_clause_.
    def enterFor_locking_clause_(self, ctx:PostgreSQLParser.For_locking_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#for_locking_clause_.
    def exitFor_locking_clause_(self, ctx:PostgreSQLParser.For_locking_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#for_locking_items.
    def enterFor_locking_items(self, ctx:PostgreSQLParser.For_locking_itemsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#for_locking_items.
    def exitFor_locking_items(self, ctx:PostgreSQLParser.For_locking_itemsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#for_locking_item.
    def enterFor_locking_item(self, ctx:PostgreSQLParser.For_locking_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#for_locking_item.
    def exitFor_locking_item(self, ctx:PostgreSQLParser.For_locking_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#for_locking_strength.
    def enterFor_locking_strength(self, ctx:PostgreSQLParser.For_locking_strengthContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#for_locking_strength.
    def exitFor_locking_strength(self, ctx:PostgreSQLParser.For_locking_strengthContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#locked_rels_list.
    def enterLocked_rels_list(self, ctx:PostgreSQLParser.Locked_rels_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#locked_rels_list.
    def exitLocked_rels_list(self, ctx:PostgreSQLParser.Locked_rels_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#values_clause.
    def enterValues_clause(self, ctx:PostgreSQLParser.Values_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#values_clause.
    def exitValues_clause(self, ctx:PostgreSQLParser.Values_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#from_clause.
    def enterFrom_clause(self, ctx:PostgreSQLParser.From_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#from_clause.
    def exitFrom_clause(self, ctx:PostgreSQLParser.From_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#from_list.
    def enterFrom_list(self, ctx:PostgreSQLParser.From_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#from_list.
    def exitFrom_list(self, ctx:PostgreSQLParser.From_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#table_ref.
    def enterTable_ref(self, ctx:PostgreSQLParser.Table_refContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#table_ref.
    def exitTable_ref(self, ctx:PostgreSQLParser.Table_refContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#alias_clause.
    def enterAlias_clause(self, ctx:PostgreSQLParser.Alias_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#alias_clause.
    def exitAlias_clause(self, ctx:PostgreSQLParser.Alias_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_alias_clause.
    def enterFunc_alias_clause(self, ctx:PostgreSQLParser.Func_alias_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_alias_clause.
    def exitFunc_alias_clause(self, ctx:PostgreSQLParser.Func_alias_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#join_type.
    def enterJoin_type(self, ctx:PostgreSQLParser.Join_typeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#join_type.
    def exitJoin_type(self, ctx:PostgreSQLParser.Join_typeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#join_qual.
    def enterJoin_qual(self, ctx:PostgreSQLParser.Join_qualContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#join_qual.
    def exitJoin_qual(self, ctx:PostgreSQLParser.Join_qualContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#relation_expr.
    def enterRelation_expr(self, ctx:PostgreSQLParser.Relation_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#relation_expr.
    def exitRelation_expr(self, ctx:PostgreSQLParser.Relation_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#relation_expr_list.
    def enterRelation_expr_list(self, ctx:PostgreSQLParser.Relation_expr_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#relation_expr_list.
    def exitRelation_expr_list(self, ctx:PostgreSQLParser.Relation_expr_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#relation_expr_opt_alias.
    def enterRelation_expr_opt_alias(self, ctx:PostgreSQLParser.Relation_expr_opt_aliasContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#relation_expr_opt_alias.
    def exitRelation_expr_opt_alias(self, ctx:PostgreSQLParser.Relation_expr_opt_aliasContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tablesample_clause.
    def enterTablesample_clause(self, ctx:PostgreSQLParser.Tablesample_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tablesample_clause.
    def exitTablesample_clause(self, ctx:PostgreSQLParser.Tablesample_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#repeatable_clause_.
    def enterRepeatable_clause_(self, ctx:PostgreSQLParser.Repeatable_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#repeatable_clause_.
    def exitRepeatable_clause_(self, ctx:PostgreSQLParser.Repeatable_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_table.
    def enterFunc_table(self, ctx:PostgreSQLParser.Func_tableContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_table.
    def exitFunc_table(self, ctx:PostgreSQLParser.Func_tableContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsfrom_item.
    def enterRowsfrom_item(self, ctx:PostgreSQLParser.Rowsfrom_itemContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsfrom_item.
    def exitRowsfrom_item(self, ctx:PostgreSQLParser.Rowsfrom_itemContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rowsfrom_list.
    def enterRowsfrom_list(self, ctx:PostgreSQLParser.Rowsfrom_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rowsfrom_list.
    def exitRowsfrom_list(self, ctx:PostgreSQLParser.Rowsfrom_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#col_def_list_.
    def enterCol_def_list_(self, ctx:PostgreSQLParser.Col_def_list_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#col_def_list_.
    def exitCol_def_list_(self, ctx:PostgreSQLParser.Col_def_list_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#ordinality_.
    def enterOrdinality_(self, ctx:PostgreSQLParser.Ordinality_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#ordinality_.
    def exitOrdinality_(self, ctx:PostgreSQLParser.Ordinality_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#where_clause.
    def enterWhere_clause(self, ctx:PostgreSQLParser.Where_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#where_clause.
    def exitWhere_clause(self, ctx:PostgreSQLParser.Where_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#where_or_current_clause.
    def enterWhere_or_current_clause(self, ctx:PostgreSQLParser.Where_or_current_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#where_or_current_clause.
    def exitWhere_or_current_clause(self, ctx:PostgreSQLParser.Where_or_current_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opttablefuncelementlist.
    def enterOpttablefuncelementlist(self, ctx:PostgreSQLParser.OpttablefuncelementlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opttablefuncelementlist.
    def exitOpttablefuncelementlist(self, ctx:PostgreSQLParser.OpttablefuncelementlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tablefuncelementlist.
    def enterTablefuncelementlist(self, ctx:PostgreSQLParser.TablefuncelementlistContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tablefuncelementlist.
    def exitTablefuncelementlist(self, ctx:PostgreSQLParser.TablefuncelementlistContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#tablefuncelement.
    def enterTablefuncelement(self, ctx:PostgreSQLParser.TablefuncelementContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#tablefuncelement.
    def exitTablefuncelement(self, ctx:PostgreSQLParser.TablefuncelementContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xmltable.
    def enterXmltable(self, ctx:PostgreSQLParser.XmltableContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xmltable.
    def exitXmltable(self, ctx:PostgreSQLParser.XmltableContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xmltable_column_list.
    def enterXmltable_column_list(self, ctx:PostgreSQLParser.Xmltable_column_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xmltable_column_list.
    def exitXmltable_column_list(self, ctx:PostgreSQLParser.Xmltable_column_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xmltable_column_el.
    def enterXmltable_column_el(self, ctx:PostgreSQLParser.Xmltable_column_elContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xmltable_column_el.
    def exitXmltable_column_el(self, ctx:PostgreSQLParser.Xmltable_column_elContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xmltable_column_option_list.
    def enterXmltable_column_option_list(self, ctx:PostgreSQLParser.Xmltable_column_option_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xmltable_column_option_list.
    def exitXmltable_column_option_list(self, ctx:PostgreSQLParser.Xmltable_column_option_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xmltable_column_option_el.
    def enterXmltable_column_option_el(self, ctx:PostgreSQLParser.Xmltable_column_option_elContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xmltable_column_option_el.
    def exitXmltable_column_option_el(self, ctx:PostgreSQLParser.Xmltable_column_option_elContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_namespace_list.
    def enterXml_namespace_list(self, ctx:PostgreSQLParser.Xml_namespace_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_namespace_list.
    def exitXml_namespace_list(self, ctx:PostgreSQLParser.Xml_namespace_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_namespace_el.
    def enterXml_namespace_el(self, ctx:PostgreSQLParser.Xml_namespace_elContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_namespace_el.
    def exitXml_namespace_el(self, ctx:PostgreSQLParser.Xml_namespace_elContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#typename.
    def enterTypename(self, ctx:PostgreSQLParser.TypenameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#typename.
    def exitTypename(self, ctx:PostgreSQLParser.TypenameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opt_array_bounds.
    def enterOpt_array_bounds(self, ctx:PostgreSQLParser.Opt_array_boundsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opt_array_bounds.
    def exitOpt_array_bounds(self, ctx:PostgreSQLParser.Opt_array_boundsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#simpletypename.
    def enterSimpletypename(self, ctx:PostgreSQLParser.SimpletypenameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#simpletypename.
    def exitSimpletypename(self, ctx:PostgreSQLParser.SimpletypenameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#consttypename.
    def enterConsttypename(self, ctx:PostgreSQLParser.ConsttypenameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#consttypename.
    def exitConsttypename(self, ctx:PostgreSQLParser.ConsttypenameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#generictype.
    def enterGenerictype(self, ctx:PostgreSQLParser.GenerictypeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#generictype.
    def exitGenerictype(self, ctx:PostgreSQLParser.GenerictypeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#type_modifiers_.
    def enterType_modifiers_(self, ctx:PostgreSQLParser.Type_modifiers_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#type_modifiers_.
    def exitType_modifiers_(self, ctx:PostgreSQLParser.Type_modifiers_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#numeric.
    def enterNumeric(self, ctx:PostgreSQLParser.NumericContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#numeric.
    def exitNumeric(self, ctx:PostgreSQLParser.NumericContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#float_.
    def enterFloat_(self, ctx:PostgreSQLParser.Float_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#float_.
    def exitFloat_(self, ctx:PostgreSQLParser.Float_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#bit.
    def enterBit(self, ctx:PostgreSQLParser.BitContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#bit.
    def exitBit(self, ctx:PostgreSQLParser.BitContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constbit.
    def enterConstbit(self, ctx:PostgreSQLParser.ConstbitContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constbit.
    def exitConstbit(self, ctx:PostgreSQLParser.ConstbitContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#bitwithlength.
    def enterBitwithlength(self, ctx:PostgreSQLParser.BitwithlengthContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#bitwithlength.
    def exitBitwithlength(self, ctx:PostgreSQLParser.BitwithlengthContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#bitwithoutlength.
    def enterBitwithoutlength(self, ctx:PostgreSQLParser.BitwithoutlengthContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#bitwithoutlength.
    def exitBitwithoutlength(self, ctx:PostgreSQLParser.BitwithoutlengthContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#character.
    def enterCharacter(self, ctx:PostgreSQLParser.CharacterContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#character.
    def exitCharacter(self, ctx:PostgreSQLParser.CharacterContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constcharacter.
    def enterConstcharacter(self, ctx:PostgreSQLParser.ConstcharacterContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constcharacter.
    def exitConstcharacter(self, ctx:PostgreSQLParser.ConstcharacterContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#character_c.
    def enterCharacter_c(self, ctx:PostgreSQLParser.Character_cContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#character_c.
    def exitCharacter_c(self, ctx:PostgreSQLParser.Character_cContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#varying_.
    def enterVarying_(self, ctx:PostgreSQLParser.Varying_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#varying_.
    def exitVarying_(self, ctx:PostgreSQLParser.Varying_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constdatetime.
    def enterConstdatetime(self, ctx:PostgreSQLParser.ConstdatetimeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constdatetime.
    def exitConstdatetime(self, ctx:PostgreSQLParser.ConstdatetimeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#constinterval.
    def enterConstinterval(self, ctx:PostgreSQLParser.ConstintervalContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#constinterval.
    def exitConstinterval(self, ctx:PostgreSQLParser.ConstintervalContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#timezone_.
    def enterTimezone_(self, ctx:PostgreSQLParser.Timezone_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#timezone_.
    def exitTimezone_(self, ctx:PostgreSQLParser.Timezone_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#interval_.
    def enterInterval_(self, ctx:PostgreSQLParser.Interval_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#interval_.
    def exitInterval_(self, ctx:PostgreSQLParser.Interval_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#interval_second.
    def enterInterval_second(self, ctx:PostgreSQLParser.Interval_secondContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#interval_second.
    def exitInterval_second(self, ctx:PostgreSQLParser.Interval_secondContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#jsonType.
    def enterJsonType(self, ctx:PostgreSQLParser.JsonTypeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#jsonType.
    def exitJsonType(self, ctx:PostgreSQLParser.JsonTypeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#escape_.
    def enterEscape_(self, ctx:PostgreSQLParser.Escape_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#escape_.
    def exitEscape_(self, ctx:PostgreSQLParser.Escape_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr.
    def enterA_expr(self, ctx:PostgreSQLParser.A_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr.
    def exitA_expr(self, ctx:PostgreSQLParser.A_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_qual.
    def enterA_expr_qual(self, ctx:PostgreSQLParser.A_expr_qualContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_qual.
    def exitA_expr_qual(self, ctx:PostgreSQLParser.A_expr_qualContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_lessless.
    def enterA_expr_lessless(self, ctx:PostgreSQLParser.A_expr_lesslessContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_lessless.
    def exitA_expr_lessless(self, ctx:PostgreSQLParser.A_expr_lesslessContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_or.
    def enterA_expr_or(self, ctx:PostgreSQLParser.A_expr_orContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_or.
    def exitA_expr_or(self, ctx:PostgreSQLParser.A_expr_orContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_and.
    def enterA_expr_and(self, ctx:PostgreSQLParser.A_expr_andContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_and.
    def exitA_expr_and(self, ctx:PostgreSQLParser.A_expr_andContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_between.
    def enterA_expr_between(self, ctx:PostgreSQLParser.A_expr_betweenContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_between.
    def exitA_expr_between(self, ctx:PostgreSQLParser.A_expr_betweenContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_in.
    def enterA_expr_in(self, ctx:PostgreSQLParser.A_expr_inContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_in.
    def exitA_expr_in(self, ctx:PostgreSQLParser.A_expr_inContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_unary_not.
    def enterA_expr_unary_not(self, ctx:PostgreSQLParser.A_expr_unary_notContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_unary_not.
    def exitA_expr_unary_not(self, ctx:PostgreSQLParser.A_expr_unary_notContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_isnull.
    def enterA_expr_isnull(self, ctx:PostgreSQLParser.A_expr_isnullContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_isnull.
    def exitA_expr_isnull(self, ctx:PostgreSQLParser.A_expr_isnullContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_is_not.
    def enterA_expr_is_not(self, ctx:PostgreSQLParser.A_expr_is_notContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_is_not.
    def exitA_expr_is_not(self, ctx:PostgreSQLParser.A_expr_is_notContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_compare.
    def enterA_expr_compare(self, ctx:PostgreSQLParser.A_expr_compareContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_compare.
    def exitA_expr_compare(self, ctx:PostgreSQLParser.A_expr_compareContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_like.
    def enterA_expr_like(self, ctx:PostgreSQLParser.A_expr_likeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_like.
    def exitA_expr_like(self, ctx:PostgreSQLParser.A_expr_likeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_qual_op.
    def enterA_expr_qual_op(self, ctx:PostgreSQLParser.A_expr_qual_opContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_qual_op.
    def exitA_expr_qual_op(self, ctx:PostgreSQLParser.A_expr_qual_opContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_unary_qualop.
    def enterA_expr_unary_qualop(self, ctx:PostgreSQLParser.A_expr_unary_qualopContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_unary_qualop.
    def exitA_expr_unary_qualop(self, ctx:PostgreSQLParser.A_expr_unary_qualopContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_add.
    def enterA_expr_add(self, ctx:PostgreSQLParser.A_expr_addContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_add.
    def exitA_expr_add(self, ctx:PostgreSQLParser.A_expr_addContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_mul.
    def enterA_expr_mul(self, ctx:PostgreSQLParser.A_expr_mulContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_mul.
    def exitA_expr_mul(self, ctx:PostgreSQLParser.A_expr_mulContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_caret.
    def enterA_expr_caret(self, ctx:PostgreSQLParser.A_expr_caretContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_caret.
    def exitA_expr_caret(self, ctx:PostgreSQLParser.A_expr_caretContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_unary_sign.
    def enterA_expr_unary_sign(self, ctx:PostgreSQLParser.A_expr_unary_signContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_unary_sign.
    def exitA_expr_unary_sign(self, ctx:PostgreSQLParser.A_expr_unary_signContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_at_time_zone.
    def enterA_expr_at_time_zone(self, ctx:PostgreSQLParser.A_expr_at_time_zoneContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_at_time_zone.
    def exitA_expr_at_time_zone(self, ctx:PostgreSQLParser.A_expr_at_time_zoneContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_collate.
    def enterA_expr_collate(self, ctx:PostgreSQLParser.A_expr_collateContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_collate.
    def exitA_expr_collate(self, ctx:PostgreSQLParser.A_expr_collateContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#a_expr_typecast.
    def enterA_expr_typecast(self, ctx:PostgreSQLParser.A_expr_typecastContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#a_expr_typecast.
    def exitA_expr_typecast(self, ctx:PostgreSQLParser.A_expr_typecastContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#b_expr.
    def enterB_expr(self, ctx:PostgreSQLParser.B_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#b_expr.
    def exitB_expr(self, ctx:PostgreSQLParser.B_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#c_expr_exists.
    def enterC_expr_exists(self, ctx:PostgreSQLParser.C_expr_existsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#c_expr_exists.
    def exitC_expr_exists(self, ctx:PostgreSQLParser.C_expr_existsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#c_expr_expr.
    def enterC_expr_expr(self, ctx:PostgreSQLParser.C_expr_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#c_expr_expr.
    def exitC_expr_expr(self, ctx:PostgreSQLParser.C_expr_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#c_expr_case.
    def enterC_expr_case(self, ctx:PostgreSQLParser.C_expr_caseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#c_expr_case.
    def exitC_expr_case(self, ctx:PostgreSQLParser.C_expr_caseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#plsqlvariablename.
    def enterPlsqlvariablename(self, ctx:PostgreSQLParser.PlsqlvariablenameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#plsqlvariablename.
    def exitPlsqlvariablename(self, ctx:PostgreSQLParser.PlsqlvariablenameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_application.
    def enterFunc_application(self, ctx:PostgreSQLParser.Func_applicationContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_application.
    def exitFunc_application(self, ctx:PostgreSQLParser.Func_applicationContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_expr.
    def enterFunc_expr(self, ctx:PostgreSQLParser.Func_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_expr.
    def exitFunc_expr(self, ctx:PostgreSQLParser.Func_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_expr_windowless.
    def enterFunc_expr_windowless(self, ctx:PostgreSQLParser.Func_expr_windowlessContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_expr_windowless.
    def exitFunc_expr_windowless(self, ctx:PostgreSQLParser.Func_expr_windowlessContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_expr_common_subexpr.
    def enterFunc_expr_common_subexpr(self, ctx:PostgreSQLParser.Func_expr_common_subexprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_expr_common_subexpr.
    def exitFunc_expr_common_subexpr(self, ctx:PostgreSQLParser.Func_expr_common_subexprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_root_version.
    def enterXml_root_version(self, ctx:PostgreSQLParser.Xml_root_versionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_root_version.
    def exitXml_root_version(self, ctx:PostgreSQLParser.Xml_root_versionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_root_standalone_.
    def enterXml_root_standalone_(self, ctx:PostgreSQLParser.Xml_root_standalone_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_root_standalone_.
    def exitXml_root_standalone_(self, ctx:PostgreSQLParser.Xml_root_standalone_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_attributes.
    def enterXml_attributes(self, ctx:PostgreSQLParser.Xml_attributesContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_attributes.
    def exitXml_attributes(self, ctx:PostgreSQLParser.Xml_attributesContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_attribute_list.
    def enterXml_attribute_list(self, ctx:PostgreSQLParser.Xml_attribute_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_attribute_list.
    def exitXml_attribute_list(self, ctx:PostgreSQLParser.Xml_attribute_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_attribute_el.
    def enterXml_attribute_el(self, ctx:PostgreSQLParser.Xml_attribute_elContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_attribute_el.
    def exitXml_attribute_el(self, ctx:PostgreSQLParser.Xml_attribute_elContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#document_or_content.
    def enterDocument_or_content(self, ctx:PostgreSQLParser.Document_or_contentContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#document_or_content.
    def exitDocument_or_content(self, ctx:PostgreSQLParser.Document_or_contentContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_whitespace_option.
    def enterXml_whitespace_option(self, ctx:PostgreSQLParser.Xml_whitespace_optionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_whitespace_option.
    def exitXml_whitespace_option(self, ctx:PostgreSQLParser.Xml_whitespace_optionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xmlexists_argument.
    def enterXmlexists_argument(self, ctx:PostgreSQLParser.Xmlexists_argumentContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xmlexists_argument.
    def exitXmlexists_argument(self, ctx:PostgreSQLParser.Xmlexists_argumentContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xml_passing_mech.
    def enterXml_passing_mech(self, ctx:PostgreSQLParser.Xml_passing_mechContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xml_passing_mech.
    def exitXml_passing_mech(self, ctx:PostgreSQLParser.Xml_passing_mechContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#within_group_clause.
    def enterWithin_group_clause(self, ctx:PostgreSQLParser.Within_group_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#within_group_clause.
    def exitWithin_group_clause(self, ctx:PostgreSQLParser.Within_group_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#filter_clause.
    def enterFilter_clause(self, ctx:PostgreSQLParser.Filter_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#filter_clause.
    def exitFilter_clause(self, ctx:PostgreSQLParser.Filter_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#window_clause.
    def enterWindow_clause(self, ctx:PostgreSQLParser.Window_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#window_clause.
    def exitWindow_clause(self, ctx:PostgreSQLParser.Window_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#window_definition_list.
    def enterWindow_definition_list(self, ctx:PostgreSQLParser.Window_definition_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#window_definition_list.
    def exitWindow_definition_list(self, ctx:PostgreSQLParser.Window_definition_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#window_definition.
    def enterWindow_definition(self, ctx:PostgreSQLParser.Window_definitionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#window_definition.
    def exitWindow_definition(self, ctx:PostgreSQLParser.Window_definitionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#over_clause.
    def enterOver_clause(self, ctx:PostgreSQLParser.Over_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#over_clause.
    def exitOver_clause(self, ctx:PostgreSQLParser.Over_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#window_specification.
    def enterWindow_specification(self, ctx:PostgreSQLParser.Window_specificationContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#window_specification.
    def exitWindow_specification(self, ctx:PostgreSQLParser.Window_specificationContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#existing_window_name_.
    def enterExisting_window_name_(self, ctx:PostgreSQLParser.Existing_window_name_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#existing_window_name_.
    def exitExisting_window_name_(self, ctx:PostgreSQLParser.Existing_window_name_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#partition_clause_.
    def enterPartition_clause_(self, ctx:PostgreSQLParser.Partition_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#partition_clause_.
    def exitPartition_clause_(self, ctx:PostgreSQLParser.Partition_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#frame_clause_.
    def enterFrame_clause_(self, ctx:PostgreSQLParser.Frame_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#frame_clause_.
    def exitFrame_clause_(self, ctx:PostgreSQLParser.Frame_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#frame_extent.
    def enterFrame_extent(self, ctx:PostgreSQLParser.Frame_extentContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#frame_extent.
    def exitFrame_extent(self, ctx:PostgreSQLParser.Frame_extentContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#frame_bound.
    def enterFrame_bound(self, ctx:PostgreSQLParser.Frame_boundContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#frame_bound.
    def exitFrame_bound(self, ctx:PostgreSQLParser.Frame_boundContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#window_exclusion_clause_.
    def enterWindow_exclusion_clause_(self, ctx:PostgreSQLParser.Window_exclusion_clause_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#window_exclusion_clause_.
    def exitWindow_exclusion_clause_(self, ctx:PostgreSQLParser.Window_exclusion_clause_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#row.
    def enterRow(self, ctx:PostgreSQLParser.RowContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#row.
    def exitRow(self, ctx:PostgreSQLParser.RowContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#explicit_row.
    def enterExplicit_row(self, ctx:PostgreSQLParser.Explicit_rowContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#explicit_row.
    def exitExplicit_row(self, ctx:PostgreSQLParser.Explicit_rowContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#implicit_row.
    def enterImplicit_row(self, ctx:PostgreSQLParser.Implicit_rowContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#implicit_row.
    def exitImplicit_row(self, ctx:PostgreSQLParser.Implicit_rowContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#sub_type.
    def enterSub_type(self, ctx:PostgreSQLParser.Sub_typeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#sub_type.
    def exitSub_type(self, ctx:PostgreSQLParser.Sub_typeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#all_op.
    def enterAll_op(self, ctx:PostgreSQLParser.All_opContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#all_op.
    def exitAll_op(self, ctx:PostgreSQLParser.All_opContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#mathop.
    def enterMathop(self, ctx:PostgreSQLParser.MathopContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#mathop.
    def exitMathop(self, ctx:PostgreSQLParser.MathopContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#qual_op.
    def enterQual_op(self, ctx:PostgreSQLParser.Qual_opContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#qual_op.
    def exitQual_op(self, ctx:PostgreSQLParser.Qual_opContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#qual_all_op.
    def enterQual_all_op(self, ctx:PostgreSQLParser.Qual_all_opContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#qual_all_op.
    def exitQual_all_op(self, ctx:PostgreSQLParser.Qual_all_opContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#subquery_Op.
    def enterSubquery_Op(self, ctx:PostgreSQLParser.Subquery_OpContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#subquery_Op.
    def exitSubquery_Op(self, ctx:PostgreSQLParser.Subquery_OpContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#expr_list.
    def enterExpr_list(self, ctx:PostgreSQLParser.Expr_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#expr_list.
    def exitExpr_list(self, ctx:PostgreSQLParser.Expr_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_arg_list.
    def enterFunc_arg_list(self, ctx:PostgreSQLParser.Func_arg_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_arg_list.
    def exitFunc_arg_list(self, ctx:PostgreSQLParser.Func_arg_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_arg_expr.
    def enterFunc_arg_expr(self, ctx:PostgreSQLParser.Func_arg_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_arg_expr.
    def exitFunc_arg_expr(self, ctx:PostgreSQLParser.Func_arg_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#type_list.
    def enterType_list(self, ctx:PostgreSQLParser.Type_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#type_list.
    def exitType_list(self, ctx:PostgreSQLParser.Type_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#array_expr.
    def enterArray_expr(self, ctx:PostgreSQLParser.Array_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#array_expr.
    def exitArray_expr(self, ctx:PostgreSQLParser.Array_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#array_expr_list.
    def enterArray_expr_list(self, ctx:PostgreSQLParser.Array_expr_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#array_expr_list.
    def exitArray_expr_list(self, ctx:PostgreSQLParser.Array_expr_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#extract_list.
    def enterExtract_list(self, ctx:PostgreSQLParser.Extract_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#extract_list.
    def exitExtract_list(self, ctx:PostgreSQLParser.Extract_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#extract_arg.
    def enterExtract_arg(self, ctx:PostgreSQLParser.Extract_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#extract_arg.
    def exitExtract_arg(self, ctx:PostgreSQLParser.Extract_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#unicode_normal_form.
    def enterUnicode_normal_form(self, ctx:PostgreSQLParser.Unicode_normal_formContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#unicode_normal_form.
    def exitUnicode_normal_form(self, ctx:PostgreSQLParser.Unicode_normal_formContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#overlay_list.
    def enterOverlay_list(self, ctx:PostgreSQLParser.Overlay_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#overlay_list.
    def exitOverlay_list(self, ctx:PostgreSQLParser.Overlay_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#position_list.
    def enterPosition_list(self, ctx:PostgreSQLParser.Position_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#position_list.
    def exitPosition_list(self, ctx:PostgreSQLParser.Position_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#substr_list.
    def enterSubstr_list(self, ctx:PostgreSQLParser.Substr_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#substr_list.
    def exitSubstr_list(self, ctx:PostgreSQLParser.Substr_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#trim_list.
    def enterTrim_list(self, ctx:PostgreSQLParser.Trim_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#trim_list.
    def exitTrim_list(self, ctx:PostgreSQLParser.Trim_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#in_expr_select.
    def enterIn_expr_select(self, ctx:PostgreSQLParser.In_expr_selectContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#in_expr_select.
    def exitIn_expr_select(self, ctx:PostgreSQLParser.In_expr_selectContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#in_expr_list.
    def enterIn_expr_list(self, ctx:PostgreSQLParser.In_expr_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#in_expr_list.
    def exitIn_expr_list(self, ctx:PostgreSQLParser.In_expr_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#case_expr.
    def enterCase_expr(self, ctx:PostgreSQLParser.Case_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#case_expr.
    def exitCase_expr(self, ctx:PostgreSQLParser.Case_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#when_clause_list.
    def enterWhen_clause_list(self, ctx:PostgreSQLParser.When_clause_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#when_clause_list.
    def exitWhen_clause_list(self, ctx:PostgreSQLParser.When_clause_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#when_clause.
    def enterWhen_clause(self, ctx:PostgreSQLParser.When_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#when_clause.
    def exitWhen_clause(self, ctx:PostgreSQLParser.When_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#case_default.
    def enterCase_default(self, ctx:PostgreSQLParser.Case_defaultContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#case_default.
    def exitCase_default(self, ctx:PostgreSQLParser.Case_defaultContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#case_arg.
    def enterCase_arg(self, ctx:PostgreSQLParser.Case_argContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#case_arg.
    def exitCase_arg(self, ctx:PostgreSQLParser.Case_argContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#columnref.
    def enterColumnref(self, ctx:PostgreSQLParser.ColumnrefContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#columnref.
    def exitColumnref(self, ctx:PostgreSQLParser.ColumnrefContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#indirection_el.
    def enterIndirection_el(self, ctx:PostgreSQLParser.Indirection_elContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#indirection_el.
    def exitIndirection_el(self, ctx:PostgreSQLParser.Indirection_elContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#slice_bound_.
    def enterSlice_bound_(self, ctx:PostgreSQLParser.Slice_bound_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#slice_bound_.
    def exitSlice_bound_(self, ctx:PostgreSQLParser.Slice_bound_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#indirection.
    def enterIndirection(self, ctx:PostgreSQLParser.IndirectionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#indirection.
    def exitIndirection(self, ctx:PostgreSQLParser.IndirectionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#opt_indirection.
    def enterOpt_indirection(self, ctx:PostgreSQLParser.Opt_indirectionContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#opt_indirection.
    def exitOpt_indirection(self, ctx:PostgreSQLParser.Opt_indirectionContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_passing_clause.
    def enterJson_passing_clause(self, ctx:PostgreSQLParser.Json_passing_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_passing_clause.
    def exitJson_passing_clause(self, ctx:PostgreSQLParser.Json_passing_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_arguments.
    def enterJson_arguments(self, ctx:PostgreSQLParser.Json_argumentsContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_arguments.
    def exitJson_arguments(self, ctx:PostgreSQLParser.Json_argumentsContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_argument.
    def enterJson_argument(self, ctx:PostgreSQLParser.Json_argumentContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_argument.
    def exitJson_argument(self, ctx:PostgreSQLParser.Json_argumentContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_wrapper_behavior.
    def enterJson_wrapper_behavior(self, ctx:PostgreSQLParser.Json_wrapper_behaviorContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_wrapper_behavior.
    def exitJson_wrapper_behavior(self, ctx:PostgreSQLParser.Json_wrapper_behaviorContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_behavior.
    def enterJson_behavior(self, ctx:PostgreSQLParser.Json_behaviorContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_behavior.
    def exitJson_behavior(self, ctx:PostgreSQLParser.Json_behaviorContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_behavior_type.
    def enterJson_behavior_type(self, ctx:PostgreSQLParser.Json_behavior_typeContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_behavior_type.
    def exitJson_behavior_type(self, ctx:PostgreSQLParser.Json_behavior_typeContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_behavior_clause.
    def enterJson_behavior_clause(self, ctx:PostgreSQLParser.Json_behavior_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_behavior_clause.
    def exitJson_behavior_clause(self, ctx:PostgreSQLParser.Json_behavior_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_on_error_clause.
    def enterJson_on_error_clause(self, ctx:PostgreSQLParser.Json_on_error_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_on_error_clause.
    def exitJson_on_error_clause(self, ctx:PostgreSQLParser.Json_on_error_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_value_expr.
    def enterJson_value_expr(self, ctx:PostgreSQLParser.Json_value_exprContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_value_expr.
    def exitJson_value_expr(self, ctx:PostgreSQLParser.Json_value_exprContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_format_clause.
    def enterJson_format_clause(self, ctx:PostgreSQLParser.Json_format_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_format_clause.
    def exitJson_format_clause(self, ctx:PostgreSQLParser.Json_format_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_quotes_clause.
    def enterJson_quotes_clause(self, ctx:PostgreSQLParser.Json_quotes_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_quotes_clause.
    def exitJson_quotes_clause(self, ctx:PostgreSQLParser.Json_quotes_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_returning_clause.
    def enterJson_returning_clause(self, ctx:PostgreSQLParser.Json_returning_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_returning_clause.
    def exitJson_returning_clause(self, ctx:PostgreSQLParser.Json_returning_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_predicate_type_constraint.
    def enterJson_predicate_type_constraint(self, ctx:PostgreSQLParser.Json_predicate_type_constraintContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_predicate_type_constraint.
    def exitJson_predicate_type_constraint(self, ctx:PostgreSQLParser.Json_predicate_type_constraintContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_key_uniqueness_constraint.
    def enterJson_key_uniqueness_constraint(self, ctx:PostgreSQLParser.Json_key_uniqueness_constraintContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_key_uniqueness_constraint.
    def exitJson_key_uniqueness_constraint(self, ctx:PostgreSQLParser.Json_key_uniqueness_constraintContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_name_and_value_list.
    def enterJson_name_and_value_list(self, ctx:PostgreSQLParser.Json_name_and_value_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_name_and_value_list.
    def exitJson_name_and_value_list(self, ctx:PostgreSQLParser.Json_name_and_value_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_name_and_value.
    def enterJson_name_and_value(self, ctx:PostgreSQLParser.Json_name_and_valueContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_name_and_value.
    def exitJson_name_and_value(self, ctx:PostgreSQLParser.Json_name_and_valueContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_object_constructor_null_clause.
    def enterJson_object_constructor_null_clause(self, ctx:PostgreSQLParser.Json_object_constructor_null_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_object_constructor_null_clause.
    def exitJson_object_constructor_null_clause(self, ctx:PostgreSQLParser.Json_object_constructor_null_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_array_constructor_null_clause.
    def enterJson_array_constructor_null_clause(self, ctx:PostgreSQLParser.Json_array_constructor_null_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_array_constructor_null_clause.
    def exitJson_array_constructor_null_clause(self, ctx:PostgreSQLParser.Json_array_constructor_null_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_value_expr_list.
    def enterJson_value_expr_list(self, ctx:PostgreSQLParser.Json_value_expr_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_value_expr_list.
    def exitJson_value_expr_list(self, ctx:PostgreSQLParser.Json_value_expr_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_aggregate_func.
    def enterJson_aggregate_func(self, ctx:PostgreSQLParser.Json_aggregate_funcContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_aggregate_func.
    def exitJson_aggregate_func(self, ctx:PostgreSQLParser.Json_aggregate_funcContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#json_array_aggregate_order_by_clause.
    def enterJson_array_aggregate_order_by_clause(self, ctx:PostgreSQLParser.Json_array_aggregate_order_by_clauseContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#json_array_aggregate_order_by_clause.
    def exitJson_array_aggregate_order_by_clause(self, ctx:PostgreSQLParser.Json_array_aggregate_order_by_clauseContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#target_list_.
    def enterTarget_list_(self, ctx:PostgreSQLParser.Target_list_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#target_list_.
    def exitTarget_list_(self, ctx:PostgreSQLParser.Target_list_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#target_list.
    def enterTarget_list(self, ctx:PostgreSQLParser.Target_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#target_list.
    def exitTarget_list(self, ctx:PostgreSQLParser.Target_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#target_label.
    def enterTarget_label(self, ctx:PostgreSQLParser.Target_labelContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#target_label.
    def exitTarget_label(self, ctx:PostgreSQLParser.Target_labelContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#target_star.
    def enterTarget_star(self, ctx:PostgreSQLParser.Target_starContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#target_star.
    def exitTarget_star(self, ctx:PostgreSQLParser.Target_starContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#qualified_name_list.
    def enterQualified_name_list(self, ctx:PostgreSQLParser.Qualified_name_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#qualified_name_list.
    def exitQualified_name_list(self, ctx:PostgreSQLParser.Qualified_name_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#qualified_name.
    def enterQualified_name(self, ctx:PostgreSQLParser.Qualified_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#qualified_name.
    def exitQualified_name(self, ctx:PostgreSQLParser.Qualified_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#name_list.
    def enterName_list(self, ctx:PostgreSQLParser.Name_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#name_list.
    def exitName_list(self, ctx:PostgreSQLParser.Name_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#name.
    def enterName(self, ctx:PostgreSQLParser.NameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#name.
    def exitName(self, ctx:PostgreSQLParser.NameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#attr_name.
    def enterAttr_name(self, ctx:PostgreSQLParser.Attr_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#attr_name.
    def exitAttr_name(self, ctx:PostgreSQLParser.Attr_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#file_name.
    def enterFile_name(self, ctx:PostgreSQLParser.File_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#file_name.
    def exitFile_name(self, ctx:PostgreSQLParser.File_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#func_name.
    def enterFunc_name(self, ctx:PostgreSQLParser.Func_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#func_name.
    def exitFunc_name(self, ctx:PostgreSQLParser.Func_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#aexprconst.
    def enterAexprconst(self, ctx:PostgreSQLParser.AexprconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#aexprconst.
    def exitAexprconst(self, ctx:PostgreSQLParser.AexprconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#xconst.
    def enterXconst(self, ctx:PostgreSQLParser.XconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#xconst.
    def exitXconst(self, ctx:PostgreSQLParser.XconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#bconst.
    def enterBconst(self, ctx:PostgreSQLParser.BconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#bconst.
    def exitBconst(self, ctx:PostgreSQLParser.BconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#fconst.
    def enterFconst(self, ctx:PostgreSQLParser.FconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#fconst.
    def exitFconst(self, ctx:PostgreSQLParser.FconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#iconst.
    def enterIconst(self, ctx:PostgreSQLParser.IconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#iconst.
    def exitIconst(self, ctx:PostgreSQLParser.IconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#sconst.
    def enterSconst(self, ctx:PostgreSQLParser.SconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#sconst.
    def exitSconst(self, ctx:PostgreSQLParser.SconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#anysconst.
    def enterAnysconst(self, ctx:PostgreSQLParser.AnysconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#anysconst.
    def exitAnysconst(self, ctx:PostgreSQLParser.AnysconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#uescape_.
    def enterUescape_(self, ctx:PostgreSQLParser.Uescape_Context):
        pass

    # Exit a parse tree produced by PostgreSQLParser#uescape_.
    def exitUescape_(self, ctx:PostgreSQLParser.Uescape_Context):
        pass


    # Enter a parse tree produced by PostgreSQLParser#signediconst.
    def enterSignediconst(self, ctx:PostgreSQLParser.SignediconstContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#signediconst.
    def exitSignediconst(self, ctx:PostgreSQLParser.SignediconstContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#roleid.
    def enterRoleid(self, ctx:PostgreSQLParser.RoleidContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#roleid.
    def exitRoleid(self, ctx:PostgreSQLParser.RoleidContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#rolespec.
    def enterRolespec(self, ctx:PostgreSQLParser.RolespecContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#rolespec.
    def exitRolespec(self, ctx:PostgreSQLParser.RolespecContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#role_list.
    def enterRole_list(self, ctx:PostgreSQLParser.Role_listContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#role_list.
    def exitRole_list(self, ctx:PostgreSQLParser.Role_listContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#colid.
    def enterColid(self, ctx:PostgreSQLParser.ColidContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#colid.
    def exitColid(self, ctx:PostgreSQLParser.ColidContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#type_function_name.
    def enterType_function_name(self, ctx:PostgreSQLParser.Type_function_nameContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#type_function_name.
    def exitType_function_name(self, ctx:PostgreSQLParser.Type_function_nameContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#nonreservedword.
    def enterNonreservedword(self, ctx:PostgreSQLParser.NonreservedwordContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#nonreservedword.
    def exitNonreservedword(self, ctx:PostgreSQLParser.NonreservedwordContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#colLabel.
    def enterColLabel(self, ctx:PostgreSQLParser.ColLabelContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#colLabel.
    def exitColLabel(self, ctx:PostgreSQLParser.ColLabelContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#bareColLabel.
    def enterBareColLabel(self, ctx:PostgreSQLParser.BareColLabelContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#bareColLabel.
    def exitBareColLabel(self, ctx:PostgreSQLParser.BareColLabelContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#unreserved_keyword.
    def enterUnreserved_keyword(self, ctx:PostgreSQLParser.Unreserved_keywordContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#unreserved_keyword.
    def exitUnreserved_keyword(self, ctx:PostgreSQLParser.Unreserved_keywordContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#col_name_keyword.
    def enterCol_name_keyword(self, ctx:PostgreSQLParser.Col_name_keywordContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#col_name_keyword.
    def exitCol_name_keyword(self, ctx:PostgreSQLParser.Col_name_keywordContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#type_func_name_keyword.
    def enterType_func_name_keyword(self, ctx:PostgreSQLParser.Type_func_name_keywordContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#type_func_name_keyword.
    def exitType_func_name_keyword(self, ctx:PostgreSQLParser.Type_func_name_keywordContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#reserved_keyword.
    def enterReserved_keyword(self, ctx:PostgreSQLParser.Reserved_keywordContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#reserved_keyword.
    def exitReserved_keyword(self, ctx:PostgreSQLParser.Reserved_keywordContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#bare_label_keyword.
    def enterBare_label_keyword(self, ctx:PostgreSQLParser.Bare_label_keywordContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#bare_label_keyword.
    def exitBare_label_keyword(self, ctx:PostgreSQLParser.Bare_label_keywordContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#any_identifier.
    def enterAny_identifier(self, ctx:PostgreSQLParser.Any_identifierContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#any_identifier.
    def exitAny_identifier(self, ctx:PostgreSQLParser.Any_identifierContext):
        pass


    # Enter a parse tree produced by PostgreSQLParser#identifier.
    def enterIdentifier(self, ctx:PostgreSQLParser.IdentifierContext):
        pass

    # Exit a parse tree produced by PostgreSQLParser#identifier.
    def exitIdentifier(self, ctx:PostgreSQLParser.IdentifierContext):
        pass



del PostgreSQLParser