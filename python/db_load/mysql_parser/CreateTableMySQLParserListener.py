from antlr4 import *
from db_load.mysql_parser.MySQLParserListener import MySQLParserListener
from db_load.mysql_parser.MySQLParser import MySQLParser
from db_compare.mysql import is_binary_datatype
import re
import logging


class CreateTableMySQLParserListener(MySQLParserListener):
    def __init__(self):
      self.buffer = ""
      self.columns = ""
      self.primary_key = ""
      self.columns_map = {}
      self.alter_list = []
      self.rename_list = []


    def extract_original_text(self, ctx):
        token_source = ctx.start.getTokenSource()
        input_stream = token_source.inputStream
        start, stop = ctx.start.start, ctx.stop.stop
        return input_stream.getText(start, stop)


    def convertDataType(self, dataType):
        dataTypeText = self.extract_original_text(dataType)
        dataTypeText = re.sub("CHARACTER SET.*", '',
                             dataTypeText, flags=re.IGNORECASE)
        dataTypeText = re.sub("CHARSET.*", '', dataTypeText, re.IGNORECASE)

        if dataType.DATE_SYMBOL():
          dataTypeText = 'Date32'
        if dataType.DATETIME_SYMBOL() or dataType.TIMESTAMP_SYMBOL():
          dataTypeText = 'DateTime64(0)'
          if dataType.typeDatetimePrecision():
            dataTypeText = 'DateTime64'+dataType.typeDatetimePrecision().getText()

        if dataType.JSON_SYMBOL() or is_binary_datatype(dataTypeText):
           dataTypeText = 'String'

        return dataTypeText


    def translateFieldDefinition(self, column_name, fieldDefinition):
        column_buffer =''
        dataType = fieldDefinition.dataType()
        dataTypeText = self.convertDataType(dataType)
        # data type
        column_buffer += ' ' + dataTypeText

        # data type modifier (NULL / NOT NULL / PRIMARY KEY)
        notNull = False
        notSymbol = True
        for child in fieldDefinition.getChildren():
            if child.getRuleIndex() == MySQLParser.RULE_columnAttribute:
              if child.NOT_SYMBOL() or child.nullLiteral():
                text = self.extract_original_text(child)
                column_buffer += " " + text
                if child.NOT_SYMBOL():
                  notSymbol = True
                if child.nullLiteral() and notSymbol:
                  notNull = True

              if child.PRIMARY_SYMBOL():
                self.primary_key = column_name
        # column without nullable info are default nullable in MySQL, while they are not null in ClickHouse
        if not notNull:
            column_buffer += " NULL"

        return (column_buffer, dataType)


    def exitColumnDefinition(self, ctx):
        column_text = self.extract_original_text(ctx)

        column_buffer = ""
        column_name = ctx.columnName().getText()

        column_buffer += column_name

        # columns have an identifier and a column definition
        fieldDefinition = ctx.fieldDefinition()

        (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(column_name, fieldDefinition)

        column_buffer += fieldDefinition_buffer

        self.columns.append(column_buffer)

        self.columns_map.append({'column_name': column_name, 'datatype': dataType})
    

    def enterTableName(self, ctx): 
      self.columns = []
      self.columns_map = []
      self.primary_key = 'tuple()'
      self.partition_keys = None


    def exitTableConstraintDef(self, ctx):
       if ctx.PRIMARY_SYMBOL():
           text = self.extract_original_text(ctx.keyListVariants())
           self.primary_key = text


    def enterCreateTable(self, ctx):
      self.buffer = ""


    def exitPartitionClause(self, ctx):
      if ctx.partitionTypeDef():
        partitionTypeDef = ctx.partitionTypeDef()
        if partitionTypeDef.RANGE_SYMBOL() and partitionTypeDef.COLUMNS_SYMBOL():
          text = self.extract_original_text(partitionTypeDef.identifierList())
          self.partition_keys = text


    def exitCreateTable(self, ctx):
        tableName = self.extract_original_text(ctx.tableName())
        self.buffer = f"CREATE TABLE {tableName} ("
        self.columns.append("`_sign` Int8 DEFAULT 1")
        self.columns.append("`_version` UInt64 DEFAULT 0")
        for column in self.columns:
          self.buffer += column
          if column != self.columns[-1]:
            self.buffer += ','
          self.buffer += '\n'

        partition_by = ""
        if self.partition_keys:
          partition_by = f"partition by {self.partition_keys}"
        self.buffer += f") engine=ReplacingMergeTree(_version) {partition_by} order by " + \
            self.primary_key
        logging.info(self.buffer)


    def get_clickhouse_sql(self):
      return (self.buffer, self.columns_map)


    def exitAlterList(self, ctx):
      for child in ctx.getChildren():
        if isinstance(child,MySQLParser.AlterListItemContext) :
          alter = self.extract_original_text(child)
          
          if child.ADD_SYMBOL() and child.fieldDefinition():
            fieldDefinition = child.fieldDefinition()
            if child.identifier():
              identifier = self.extract_original_text(child.identifier())
              place =  self.extract_original_text(child.place()) if child.place() else ''

              (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(identifier, fieldDefinition)
              alter = f"add column {identifier} {fieldDefinition_buffer} {place}"
              self.alter_list.append(alter)
          
          if child.MODIFY_SYMBOL() and child.fieldDefinition():
            fieldDefinition = child.fieldDefinition()
            if child.columnInternalRef():
              identifier = self.extract_original_text(child.columnInternalRef().identifier())
              place =  self.extract_original_text(child.place()) if child.place() else ''
              (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(identifier, fieldDefinition)
              alter = f"modify column {identifier} {fieldDefinition_buffer} {place}"
              self.alter_list.append(alter)
          
          if child.CHANGE_SYMBOL() and child.fieldDefinition():
            fieldDefinition = child.fieldDefinition()
            if child.columnInternalRef():
              identifier = self.extract_original_text(child.columnInternalRef().identifier())
              place =  self.extract_original_text(child.place()) if child.place() else ''
              (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(identifier, fieldDefinition)
              alter = f"modify column {identifier} {fieldDefinition_buffer} {place}"
              self.alter_list.append(alter)
              new_identifier = self.extract_original_text(child.identifier())
              rename_column = f"rename column {identifier} to {new_identifier}"
              self.alter_list.append(rename_column)

          if child.DROP_SYMBOL() and child.COLUMN_SYMBOL():
             self.alter_list.append(alter)

          if child.RENAME_SYMBOL() and child.COLUMN_SYMBOL():
             self.alter_list.append(alter)

          if child.RENAME_SYMBOL() and child.tableName():
             to_table = self.extract_original_text(child.tableName().qualifiedIdentifier())
             rename = f" to {to_table}"
             self.rename_list.append(rename)


    def exitAlterTable(self, ctx):
       tableName = self.extract_original_text(ctx.tableRef())
       if len(self.alter_list):
        self.buffer = f"ALTER TABLE {tableName}" 
        for alter in self.alter_list:
          self.buffer += ' ' + alter
          if self.alter_list[-1] != alter:
            self.buffer += ', '
        self.buffer += ';' 
        
       for rename in self.rename_list:
        self.buffer += f" rename table {tableName} {rename}"
        self.buffer += ';'


    def exitRenameTableStatement(self, ctx):
         # same syntax as CH
         self.buffer = self.extract_original_text(ctx)


    def exitTruncateTableStatement(self, ctx):
         # same syntax as CH
         self.buffer = self.extract_original_text(ctx)


    def exitDropStatement(self, ctx):
          # same syntax as CH
         self.buffer = self.extract_original_text(ctx)
