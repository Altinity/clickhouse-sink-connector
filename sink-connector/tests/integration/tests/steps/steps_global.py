from integration.helpers.common import *


@TestStep(Given)
def create_database(self, name="test", node=None):
    """Create ClickHouse database."""
    if node is None:
        node = self.context.cluster.node("clickhouse")

    try:
        with By(f"adding {name} database if not exists"):
            node.query(
                f"CREATE DATABASE IF NOT EXISTS {name} ON CLUSTER replicated_cluster"
            )
        yield
    finally:
        with Finally(f"I delete {name} database if exists"):
            node.query(
                f"DROP DATABASE IF EXISTS {name} ON CLUSTER replicated_cluster;"
            )
