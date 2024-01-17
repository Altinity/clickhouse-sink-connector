from testflows.core import *
from integration.tests.steps.sql import *

@TestStep(Given)
def create_table_with_decimal_column(self, table_name, column_name):
    create_mysql_to_clickhouse_replicated_table(
        name=table_name,
        mysql_columns=f"{column_name} DECIMAL(2,1) NOT NULL",
        clickhouse_columns=f"{column_name} Nullable(DECIMAL(2,1))",
        clickhouse_table_engine="ReplacingMergeTree",
    )


@TestStep(Given)
def create_table_with_double_column(self, table_name, column_name):
    create_mysql_to_clickhouse_replicated_table(
        name=table_name,
        mysql_columns=f"{column_name} DOUBLE NOT NULL",
        clickhouse_columns=f"{column_name} Float64",
        clickhouse_table_engine="ReplacingMergeTree",
    )

@TestStep(Given)
def create_table_with_date_column(self, table_name, column_name):
    create_mysql_to_clickhouse_replicated_table(
        name=table_name,
        mysql_columns=f"{column_name} DATE NOT NULL",
        clickhouse_columns=f"{column_name} Date",
        clickhouse_table_engine="ReplacingMergeTree",
    )

@TestStep(Given)
def create_table_with_datetime_column(self, table_name, column_name):
    create_mysql_to_clickhouse_replicated_table(
        name=table_name,
        mysql_columns=f"{column_name} DATETIME(6) NOT NULL",
        clickhouse_columns=f"{column_name} String",
        clickhouse_table_engine="ReplacingMergeTree",
    )