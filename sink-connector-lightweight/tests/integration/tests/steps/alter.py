from integration.requirements.requirements import *

from integration.helpers.common import *


@TestStep(When)
def add_column(
    self, table_name, column_name="new_col", column_type="varchar(255)", node=None
):
    """ADD COLUMN"""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(f"ALTER TABLE {table_name} ADD COLUMN {column_name} {column_type};")


@TestStep(When)
def rename_column(
    self, table_name, column_name="new_col", new_column_name="new_column_name", node=None
):
    """RENAME COLUMN"""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(f"ALTER TABLE {table_name} RENAME COLUMN {column_name} to {new_column_name};")


@TestStep(When)
def change_column(
    self, table_name, column_name="new_col", new_column_name="new_column_name", new_column_type="varchar(255)",
        node=None
):
    """CHANGE COLUMN"""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(f"ALTER TABLE {table_name} CHANGE COLUMN {column_name} {new_column_name} {new_column_type};")


@TestStep(When)
def modify_column(
    self, table_name, column_name="new_col", new_column_type="varchar(255)",
        node=None
):
    """MODIFY COLUMN"""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(f"ALTER TABLE {table_name} MODIFY COLUMN {column_name} {new_column_type};")