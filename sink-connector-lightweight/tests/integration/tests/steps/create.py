from testflows.core import *
from integration.tests.steps.sql import *


@TestStep(Given)
def insert(self, table_name, values, mysql_node=None):
    """Insert values into the mysql table."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    with By(f"executing the INSERT command on the {table_name} table"):
        mysql_node.query(f"INSERT INTO {table_name} VALUES {values}")


@TestStep(Given)
def create_table_with_decimal_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with decimal column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} DECIMAL(2,1) NOT NULL",
        clickhouse_columns=f"{column_name} Nullable(DECIMAL(2,1))",
    )


@TestStep(Given)
def create_table_with_double_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with double column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} DOUBLE NOT NULL",
        clickhouse_columns=f"{column_name} Float64",
    )


@TestStep(Given)
def create_table_with_date_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with date column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} DATE NOT NULL",
        clickhouse_columns=f"{column_name} Date",
    )


@TestStep(Given)
def create_table_with_datetime_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with datetime column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} DATETIME(6) NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_time_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with time column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} TIME(6) NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_int_min_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with int column that has minimal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    # TODO ADD MAX VALUES INSTEAD OF RANDOM
    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} INT NOT NULL",
        clickhouse_columns=f"{column_name} Int32",
    )


@TestStep(Given)
def create_table_with_int_max_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with int column that has maximal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} INT NOT NULL",
        clickhouse_columns=f"{column_name} Int32",
    )


@TestStep(Given)
def create_table_with_unsigned_int_min_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with unsigned int column that has minimal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} INT UNSIGNED NOT NULL",
        clickhouse_columns=f"{column_name} UInt32",
    )


@TestStep(Given)
def create_table_with_unsigned_int_max_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with unsigned int column that has maximal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} INT UNSIGNED NOT NULL",
        clickhouse_columns=f"{column_name} UInt32",
    )


@TestStep(Given)
def create_table_with_bigint_min_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with bigint column that has minimal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} BIGINT NOT NULL",
        clickhouse_columns=f"{column_name} UInt64",
    )


@TestStep(Given)
def create_table_with_bigint_max_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with bigint column that has maximal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} BIGINT NOT NULL",
        clickhouse_columns=f"{column_name} UInt64",
    )


@TestStep(Given)
def create_table_with_unsigned_bigint_min_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with unsigned bigint column that has minimal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} BIGINT UNSIGNED NOT NULL",
        clickhouse_columns=f"{column_name} UInt64",
    )


@TestStep(Given)
def create_table_with_unsigned_bigint_max_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with unsigned bigint column that has maximal value."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} BIGINT UNSIGNED NOT NULL",
        clickhouse_columns=f"{column_name} UInt64",
    )


@TestStep(Given)
def create_table_with_char_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with char column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} CHAR NOT NULL",
        clickhouse_columns=f"{column_name} LowCardinality(String)",
    )


@TestStep(Given)
def create_table_with_text_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with text column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} TEXT NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_varchar_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with varchar column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} VARCHAR(4) NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_blob_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with blob column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} BLOB NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_medium_blob_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with medium blob column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} MEDIUMBLOB NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_long_blob_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with long blob column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} MEDIUMBLOB NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_long_binary_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with binary column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} BINARY NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )


@TestStep(Given)
def create_table_with_long_varbinary_column(
    self, table_name, column_name, mysql_node=None, clickhouse_node=None
):
    """Create a mysql to clickhouse replicated table with varbinary column."""
    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    create_mysql_to_clickhouse_replicated_table(
        mysql_node=mysql_node,
        clickhouse_node=clickhouse_node,
        name=table_name,
        mysql_columns=f"{column_name} VARBINARY(4) NOT NULL",
        clickhouse_columns=f"{column_name} String",
    )
