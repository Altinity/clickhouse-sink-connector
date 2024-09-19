from integration.helpers.common import *


@TestStep(Given)
def update(self, table_name, database=None, node=None, condition=None, set=None):
    """Update records in MySQL table."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    query = rf"UPDATE {database}.{table_name} SET {set} WHERE {condition};"

    with By("executing UPDATE query"):
        node.query(query)
