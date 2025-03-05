from integration.helpers.common import *


@TestStep(Given)
def delete(self, table, condition=None, database=None, node=None):
    """Execute the DELETE query in MySQL to deleted existing records in a table."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    query = rf"DELETE FROM {database}.{table}{condition};"

    if condition is not None:
        query += f" WHERE {condition}"

    with By("executing DELETE query"):
        node.query(query)


@TestStep
def delete_all_records(self, table_name, database=None, node=None):
    """Delete all records from a MySQL table."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    with By("executing DELETE query"):
        delete(node=node, table=table_name, database=database, condition=None)
