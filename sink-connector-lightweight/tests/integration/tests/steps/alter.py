from integration.requirements.requirements import *

from integration.helpers.common import *


@TestStep(When)
def add_column(self, table_name, column_name="new_col", column_type="varchar(255)", node=None):
    if node is None:
        node = self.context.cluster.node("mysql-master")

    node.query(f"ALTER TABLE {table_name} ADD COLUMN {column_name} {column_type};")
