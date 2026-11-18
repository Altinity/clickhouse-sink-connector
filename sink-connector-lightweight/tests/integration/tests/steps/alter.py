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


@TestStep(When)
def alter_table(self, table_name, clause, node=None, database=None):
    """Apply one raw ALTER TABLE clause list.

    The MySQL grammar has 48 `alterSpecification` alternatives and the
    connector translates some while deliberately skipping others, so the
    exhaustive-coverage suite states each clause as data and applies it
    through this one step. A clause that is skipped must still leave the
    replication stream alive, which the caller asserts separately.
    """
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(rf"ALTER TABLE {database}.\`{table_name}\` {clause};")


@TestStep(When)
def drop_table(self, table_name, node=None, database=None):
    """DROP TABLE."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(rf"DROP TABLE {database}.\`{table_name}\`;")


@TestStep(When)
def rename_table(self, table_name, new_table_name, node=None, database=None):
    """RENAME TABLE (the standalone statement, not ALTER TABLE ... RENAME TO)."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"RENAME TABLE {database}.\`{table_name}\` TO {database}.\`{new_table_name}\`;"
    )


@TestStep(When)
def create_index(self, table_name, index_name, columns, node=None, database=None):
    """CREATE INDEX (the standalone statement)."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"CREATE INDEX {index_name} ON {database}.\`{table_name}\` ({columns});"
    )


@TestStep(When)
def create_view(self, table_name, view_name, select, node=None, database=None):
    """CREATE VIEW."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(rf"CREATE VIEW {database}.{view_name} AS {select};")


@TestStep(When)
def create_table_like(self, table_name, new_table_name, node=None, database=None):
    """CREATE TABLE ... LIKE (the connector maps it to CREATE TABLE ... AS)."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(
        rf"CREATE TABLE {database}.{new_table_name} LIKE {database}.\`{table_name}\`;"
    )
