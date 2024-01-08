from antlr4 import *
from db_load.mysql_parser.MySqlParserListener import MySqlParserListener
from db_load.mysql_parser.MySqlParser import MySqlParser
from db.mysql import is_binary_datatype
import re
import logging


class CreateTableMySQLParserListener(MySqlParserListener):
    def __init__(self, rmt_delete_support, partition_options, datetime_timezone=None):
        self.buffer = ""
        self.columns = ""
        self.primary_key = ""
        self.columns_map = {}
        self.alter_list = []
        self.rename_list = []
        self.rmt_delete_support = rmt_delete_support
        self.partition_options = partition_options
        self.datatime_timezone = datetime_timezone

    def extract_original_text(self, ctx):
        token_source = ctx.start.getTokenSource()
        input_stream = token_source.inputStream
        start, stop = ctx.start.start, ctx.stop.stop
        return input_stream.getText(start, stop)

    def add_timezone(self, dataTypeText):
        if self.datatime_timezone is not None:
            dataTypeText = dataTypeText[:-1]+",'"+self.datatime_timezone+"')"
        return dataTypeText

    def convertDataType(self, dataType):
        dataTypeText = self.extract_original_text(dataType)
        dataTypeText = re.sub("CHARACTER SET.*", '',
                              dataTypeText, flags=re.IGNORECASE)
        dataTypeText = re.sub("CHARSET.*", '', dataTypeText, re.IGNORECASE)

        if isinstance(dataType, MySqlParser.SimpleDataTypeContext) and dataType.DATE():
            dataTypeText = 'Date32'
        if isinstance(dataType, MySqlParser.DimensionDataTypeContext):
            if dataType.DATETIME() or dataType.TIMESTAMP():
                dataTypeText = 'DateTime64(0)'
                dataTypeText = self.add_timezone(dataTypeText)
                if dataType.lengthOneDimension():
                    dataTypeText = 'DateTime64'+dataType.lengthOneDimension().getText()
                    dataTypeText = self.add_timezone(dataTypeText)
            elif dataType.TIME():
                dataTypeText = "String"

        if (isinstance(dataType, MySqlParser.SpatialDataTypeContext) and dataType.JSON()) or is_binary_datatype(dataTypeText):
            dataTypeText = 'String'

        return dataTypeText

    def translateColumnDefinition(self, column_name, columnDefinition):
        column_buffer = ''
        dataType = columnDefinition.dataType()
        dataTypeText = self.convertDataType(dataType)
        # data type
        column_buffer += ' ' + dataTypeText

        # data type modifier (NULL / NOT NULL / PRIMARY KEY)
        notNull = False
        notSymbol = True
        nullable = True
        for child in columnDefinition.getChildren():
            if child.getRuleIndex() == MySqlParser.RULE_columnConstraint:

                if isinstance(child, MySqlParser.NullColumnConstraintContext):
                    nullNotNull = child.nullNotnull()
                    if nullNotNull:
                        text = self.extract_original_text(child)
                        column_buffer += " " + text
                        if "NULL" == text:
                            nullable = True
                            notNull = True
                            continue

                        if nullNotNull.NOT():
                            notSymbol = True
                        if (nullNotNull.NULL_LITERAL() or nullNotNull.NULL_SPEC_LITERAL()) and notSymbol:
                            notNull = True
                            nullable = False
                        else:
                            notNull = False
                            nullable = True

                if isinstance(child, MySqlParser.PrimaryKeyColumnConstraintContext) and child.PRIMARY():
                    self.primary_key = column_name
        # column without nullable info are default nullable in MySQL, while they are not null in ClickHouse
        if not notNull:
            column_buffer += " NULL"
            nullable = True

        return (column_buffer, dataType, nullable)

    def exitColumnDeclaration(self, ctx):
        column_text = self.extract_original_text(ctx)

        column_buffer = ""
        column_name = ctx.fullColumnName().getText()

        column_buffer += column_name

        # columns have an identifier and a column definition
        columnDefinition = ctx.columnDefinition()
        dataType = columnDefinition.dataType()
        originalDataTypeText = self.extract_original_text(dataType)

        (columnDefinition_buffer, dataType, nullable) = self.translateColumnDefinition(
            column_name, columnDefinition)

        column_buffer += columnDefinition_buffer

        self.columns.append(column_buffer)
        dataTypeText = self.convertDataType(dataType)
        columnMap = {'column_name': column_name, 'datatype': dataTypeText,
                     'nullable': nullable, 'mysql_datatype': originalDataTypeText}
        logging.info(str(columnMap))
        self.columns_map.append(columnMap)

    def exitPrimaryKeyTableConstraint(self, ctx):

        text = self.extract_original_text(ctx.indexColumnNames())
        self.primary_key = text

    def enterColumnCreateTable(self, ctx):
        self.buffer = ""
        self.columns = []
        self.columns_map = []
        self.primary_key = 'tuple()'
        self.partition_keys = None

    def exitPartitionClause(self, ctx):
        if ctx.partitionTypeDef():
            partitionTypeDef = ctx.partitionTypeDef()
            if partitionTypeDef.RANGE_SYMBOL() and partitionTypeDef.COLUMNS_SYMBOL():
                text = self.extract_original_text(
                    partitionTypeDef.identifierList())
                self.partition_keys = text

    def exitColumnCreateTable(self, ctx):
        tableName = self.extract_original_text(ctx.tableName())
        self.buffer = f"CREATE TABLE {tableName} ("
        self.columns.append("`_version` UInt64 DEFAULT 0")
        # is_deleted and _sign are redundant, so exclusive in the schema
        if self.rmt_delete_support:
            self.columns.append("`is_deleted` UInt8 DEFAULT 0")
        else:
            self.columns.append("`_sign` Int8 DEFAULT 1")

        for column in self.columns:
            self.buffer += column
            if column != self.columns[-1]:
                self.buffer += ','
            self.buffer += '\n'

        partition_by = self.partition_options
        if self.partition_keys:
            partition_by = f"partition by {self.partition_keys}"
        rmt_params = "_version"
        if self.rmt_delete_support:
            rmt_params += ',is_deleted'

        self.buffer += f") engine=ReplacingMergeTree({rmt_params}) {partition_by} order by " + \
            self.primary_key
        logging.info(self.buffer)

    def get_clickhouse_sql(self):
        return (self.buffer, self.columns_map)

    def exitAlterList(self, ctx):
        for child in ctx.getChildren():
            if isinstance(child, MySqlParser.AlterListItemContext):
                alter = self.extract_original_text(child)

                if child.ADD_SYMBOL() and child.fieldDefinition():
                    fieldDefinition = child.fieldDefinition()
                    if child.identifier():
                        identifier = self.extract_original_text(
                            child.identifier())
                        place = self.extract_original_text(
                            child.place()) if child.place() else ''

                        (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(
                            identifier, fieldDefinition)
                        alter = f"add column {identifier} {fieldDefinition_buffer} {place}"
                        self.alter_list.append(alter)

                if child.MODIFY_SYMBOL() and child.fieldDefinition():
                    fieldDefinition = child.fieldDefinition()
                    if child.columnInternalRef():
                        identifier = self.extract_original_text(
                            child.columnInternalRef().identifier())
                        place = self.extract_original_text(
                            child.place()) if child.place() else ''
                        (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(
                            identifier, fieldDefinition)
                        alter = f"modify column {identifier} {fieldDefinition_buffer} {place}"
                        self.alter_list.append(alter)

                if child.CHANGE_SYMBOL() and child.fieldDefinition():
                    fieldDefinition = child.fieldDefinition()
                    if child.columnInternalRef():
                        identifier = self.extract_original_text(
                            child.columnInternalRef().identifier())
                        place = self.extract_original_text(
                            child.place()) if child.place() else ''
                        (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(
                            identifier, fieldDefinition)
                        alter = f"modify column {identifier} {fieldDefinition_buffer} {place}"
                        self.alter_list.append(alter)
                        new_identifier = self.extract_original_text(
                            child.identifier())
                        rename_column = f"rename column {identifier} to {new_identifier}"
                        self.alter_list.append(rename_column)

                if child.DROP_SYMBOL() and child.COLUMN_SYMBOL():
                    self.alter_list.append(alter)

                if child.RENAME_SYMBOL() and child.COLUMN_SYMBOL():
                    self.alter_list.append(alter)

                if child.RENAME_SYMBOL() and child.tableName():
                    to_table = self.extract_original_text(
                        child.tableName().qualifiedIdentifier())
                    rename = f" to {to_table}"
                    self.rename_list.append(rename)

    def exitAlterTable(self, ctx):
        tableName = self.extract_original_text(ctx.tableName())

        for child in ctx.getChildren():
            if isinstance(child, MySqlParser.AlterByRenameContext):
                if child.RENAME():
                    if child.uid():
                        rename = self.extract_original_text(child.uid())
                    if child.fullId():
                        rename = self.extract_original_text(child.fullId())

                    self.buffer += f" rename table {tableName} to {rename}"

        # if len(self.alter_list):
        #  self.buffer = f"ALTER TABLE {tableName}"
        #  for alter in self.alter_list:
        #    self.buffer += ' ' + alter
        #    if self.alter_list[-1] != alter:
        #      self.buffer += ', '
        #  self.buffer += ';'

             # for rename in self.rename_list:
             # self.buffer += f" rename table {tableName} {rename}"
             # self.buffer += ';'

    def exitRenameTable(self, ctx):
        # same syntax as CH
        self.buffer = self.extract_original_text(ctx)

    def exitTruncateTable(self, ctx):
        # same syntax as CH
        self.buffer = self.extract_original_text(ctx)

    def exitDropTable(self, ctx):
        # same syntax as CH
        self.buffer = self.extract_original_text(ctx)
