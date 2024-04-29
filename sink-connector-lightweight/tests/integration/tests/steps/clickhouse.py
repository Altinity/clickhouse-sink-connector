from integration.helpers.common import *


@TestStep(Given)
def create_database(self, name="test", node=None):
    """Create ClickHouse database."""
    if node is None:
        node = self.context.cluster.node("clickhouse")

    try:
        with By(f"adding {name} database if not exists"):
            node.query(
                f"DROP DATABASE IF EXISTS {name} ON CLUSTER sharded_cluster;"
            )
            node.query(
                f"CREATE DATABASE IF NOT EXISTS {name} ON CLUSTER sharded_cluster"
            )
        yield
    finally:
        with Finally(f"I delete {name} database if exists"):
            node.query(
                f"DROP DATABASE IF EXISTS {name} ON CLUSTER sharded_cluster;"
            )


@TestStep(Then)
def select(
    self,
    manual_output=None,
    table_name=None,
    statement=None,
    node=None,
    with_final=False,
    with_optimize=False,
    sign_column="_sign",
    timeout=300,
):
    """SELECT with an option to either with FINAL or loop SELECT + OPTIMIZE TABLE default simple 'SELECT'"""
    if node is None:
        node = self.context.cluster.node("clickhouse")
    if table_name is None:
        table_name = "users"
    if statement is None:
        statement = "*"

    mysql = self.context.cluster.node("mysql-master")
    mysql_output = mysql.query(f"select {statement} from {table_name}").output.strip()[
        90:
    ]

    if manual_output is None:
        manual_output = mysql_output

    if with_final:
        retry(
            node.query,
            timeout=timeout,
            delay=10,
        )(
            f"SELECT {statement} FROM test.{table_name} FINAL",
            message=f"{manual_output}",
        )
    elif with_optimize:
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                node.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                node.query(
                    f"SELECT {statement} FROM test.{table_name} where {sign_column} !=-1 FORMAT CSV",
                    message=f"{manual_output}",
                )

    else:
        retry(
            node.query,
            timeout=timeout,
            delay=10,
        )(
            f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
            message=f"{manual_output}",
        )


@TestStep(Then)
def check_if_table_was_created(self, table_name, node=None, timeout=40, message=1):
    """Check if table was created in ClickHouse."""
    if node is None:
        node = self.context.cluster.node("clickhouse")

    retry(node.query, timeout=timeout, delay=3)(
        f"EXISTS {self.context.database}.{table_name}", message=f"{message}"
    )


@TestStep(Then)
def validate_data_in_clickhouse_table(
    self, table_name, expected_output, statement="*", node=None
):
    """Validate data in ClickHouse table."""
    if node is None:
        node = self.context.cluster.node("clickhouse")

    for retry in retries(timeout=40):
        with retry:
            data = node.query(
                f"SELECT {statement} FROM {self.context.database}.{table_name} ORDER BY tuple(*) FORMAT CSV"
            )
            assert data.output.strip().replace('"', "") == expected_output, error()


@TestStep(Then)
def verify_table_creation_in_clickhouse(
    self,
    table_name,
    statement,
    clickhouse_table_engine=(""),
    timeout=300,
    manual_output=None,
    with_final=False,
    with_optimize=False,
):
    """
    Verify the creation of tables on all ClickHouse nodes where they are expected, and ensure data consistency with
    MySQL.
    """
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    if clickhouse_table_engine.startswith("Replicated"):
        with Then("I check table creation on all ClickHouse nodes"):
            for clickhouse_node in self.context.nodes["clickhouse"]:
                retry(
                    self.context.cluster.node(clickhouse_node).query,
                    timeout=100,
                    delay=3,
                )("SHOW TABLES FROM test", message=f"{table_name}")

    else:
        with Then("I check table creation"):
            retry(clickhouse.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(
            table_name=table_name,
            manual_output=manual_output,
            statement=statement,
            with_final=with_final,
            with_optimize=with_optimize,
            timeout=timeout,
        )
        if clickhouse_table_engine.startswith("Replicated"):
            with Then(
                "I check that ClickHouse table has same number of rows as MySQL table on the replica node if it is "
                "replicted table"
            ):
                select(
                    table_name=table_name,
                    manual_output=manual_output,
                    statement=statement,
                    node=self.context.cluster.node("clickhouse1"),
                    with_final=with_final,
                    with_optimize=with_optimize,
                    timeout=timeout,
                )
