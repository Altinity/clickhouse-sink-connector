from integration.requirements.requirements import *

from integration.helpers.common import *


@TestStep(When)
def add_column(
    self,
    table_name,
    column_name="new_col",
    column_type="varchar(255)",
    node=None,
    database=None,
):
    """ADD COLUMN"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"ALTER TABLE {database}.\`{table_name}\` ADD COLUMN {column_name} {column_type};"
    )


@TestStep(When)
def rename_column(
    self,
    table_name,
    column_name="new_col",
    new_column_name="new_column_name",
    node=None,
    database=None,
):
    """RENAME COLUMN"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"ALTER TABLE {database}.\`{table_name}\` RENAME COLUMN {column_name} to {new_column_name};"
    )


@TestStep(When)
def change_column(
    self,
    table_name,
    column_name="new_col",
    new_column_name="new_column_name",
    new_column_type="varchar(255)",
    node=None,
    database=None,
):
    """CHANGE COLUMN"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"ALTER TABLE {database}.\`{table_name}\` CHANGE COLUMN {column_name} {new_column_name} {new_column_type};"
    )


@TestStep(When)
def modify_column(
    self,
    table_name,
    column_name="new_col",
    new_column_type="varchar(255)",
    node=None,
    database=None,
):
    """MODIFY COLUMN"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"ALTER TABLE {database}.\`{table_name}\` MODIFY COLUMN {column_name} {new_column_type};"
    )


@TestStep(When)
def drop_column(self, table_name, column_name="new_col", node=None, database=None):
    """DROP COLUMN"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(rf"ALTER TABLE {database}.\`{table_name}\` DROP COLUMN {column_name};")


@TestStep(When)
def add_modify_drop_column(
    self, table_name, column_name="new_col", new_column_type="INT", node=None
):
    """ADD, then MODIFY, then DROP one column.

    The three statements depend on each other (MODIFY and DROP need the column
    ADD creates), so they run in order. Running them in parallel let MODIFY or
    DROP reach MySQL before ADD and fail with "Unknown column". Callers get
    their parallelism by running this step for several columns at once.
    """
    if node is None:
        node = self.context.cluster.node("mysql-master")

    By(f"add column {column_name}", test=add_column)(
        node=node,
        table_name=table_name,
        column_name=column_name,
    )

    By(f"modify column {column_name}", test=modify_column)(
        node=node,
        table_name=table_name,
        column_name=column_name,
        new_column_type=new_column_type,
    )

    By(f"drop column {column_name}", test=drop_column)(
        node=node,
        table_name=table_name,
        column_name=column_name,
    )


@TestStep(When)
def add_column_null_not_null(
    self,
    table_name,
    column_name,
    column_type,
    is_null=False,
    node=None,
    database=None,
):
    """ADD COLUMN NULL/NOT NULL"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    null_not_null = "NOT NULL" if not is_null else "NULL"
    node.query(
        rf"ALTER TABLE {database}.\`{table_name}\` ADD COLUMN {column_name} {column_type} {null_not_null};"
    )


@TestStep(When)
def add_column_default(
    self,
    table_name,
    column_name,
    column_type,
    default_value,
    node=None,
    database=None,
):
    """ADD COLUMN DEFAULT"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"ALTER TABLE {database}.\`{table_name}\` ADD COLUMN {column_name} {column_type} DEFAULT {default_value};"
    )


@TestStep(When)
def add_primary_key(self, table_name, column_name, node=None, database=None):
    """ADD PRIMARY KEY"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"ALTER TABLE {database}.\`{table_name}\` ADD PRIMARY KEY ({column_name});"
    )


@TestStep(When)
def drop_primary_key(self, table_name, node=None, database=None):
    """DROP PRIMARY KEY"""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(rf"ALTER TABLE {database}.\`{table_name}\` DROP PRIMARY KEY;")
