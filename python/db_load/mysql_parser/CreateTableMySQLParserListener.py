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

    def extract_original_text(self, ctx):
        token_source = ctx.start.getTokenSource()
        input_stream = token_source.inputStream
        start, stop = ctx.start.start, ctx.stop.stop
        return input_stream.getText(start, stop)

    def convertDataType(self, dataType):
        dataTypeText = self.extract_original_text(dataType)
        dataTpeText = re.sub("CHARACTER SET.*", '',
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

    def exitColumnDefinition(self, ctx):
        column_text = self.extract_original_text(ctx)

        column_buffer = ""
        column_name = ctx.columnName().getText()

        column_buffer += column_name

        # columns have an identifier and a column definition
        fieldDefinition = ctx.fieldDefinition()

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

        self.columns.append(column_buffer)
        self.columns_map.append(
            {'column_name': column_name, 'datatype': dataType})

    def enterTableName(self, ctx):
      self.buffer += self.extract_original_text(ctx)+" (\n"
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
        self.buffer = "CREATE TABLE " + self.buffer
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
