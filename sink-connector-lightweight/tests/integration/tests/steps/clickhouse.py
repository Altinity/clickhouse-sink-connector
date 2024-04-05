from integration.helpers.common import *


@TestStep(Given)
def create_database(self, name="test", node=None):
    """Create ClickHouse database."""
    if node is None:
        node = self.context.cluster.node("clickhouse")

    try:
        with By(f"adding {name} database if not exists"):
            node.query(
                f"DROP DATABASE IF EXISTS {name} ON CLUSTER sharded_replicated_cluster;"
            )
            node.query(
                f"CREATE DATABASE IF NOT EXISTS {name} ON CLUSTER sharded_replicated_cluster"
            )
        yield
    finally:
        with Finally(f"I delete {name} database if exists"):
            node.query(
                f"DROP DATABASE IF EXISTS {name} ON CLUSTER sharded_replicated_cluster;"
            )


@TestStep(Given)
def create_clickhouse_table_engine(
    self, name=None, statement=None, node=None, force_select_final=False
):
    """Creation of default ClickHouse table for tests"""
    if node is None:
        node = self.context.cluster.node("clickhouse")
    if name is None:
        name = "users"
    if statement is None:
        statement = f"CREATE TABLE IF NOT EXISTS test.{name} "
        f"(id Int32, age Int32) "
        f"ENGINE = MergeTree "
        f"PRIMARY KEY id ORDER BY id SETTINGS {' ignore_force_select_final=1' if force_select_final else ''}"
        f"index_granularity = 8192;"

    try:
        with Given(f"I create ClickHouse table {name}"):
            node.query(statement)
        yield
    finally:
        with Finally("I clean up by deleting table in ClickHouse"):
            node.query(
                f"DROP TABLE IF EXISTS test.{name} ON CLUSTER sharded_replicated_cluster;"
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
            # f"SELECT {statement} FROM test.{table_name}  FINAL WHERE {sign_column} != -1 FORMAT CSV",
            f"SELECT {statement} FROM test.{table_name} FINAL",
            # f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
            message=f"{manual_output}",
        )
    elif with_optimize:
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                node.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                node.query(
                    f"SELECT {statement} FROM test.{table_name} where {sign_column} !=-1 FORMAT CSV",
                    # f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
                    message=f"{manual_output}",
                )

    else:
        retry(
            node.query,
            timeout=timeout,
            delay=10,
        )(
            # f"SELECT {statement} FROM test.{table_name} where {sign_column} !=-1 FORMAT CSV",
            f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
            message=f"{manual_output}",
        )


@TestStep(Then)
def complex_check_creation_and_select(
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
    clickhouse1 = self.context.cluster.node("clickhouse1")
    clickhouse2 = self.context.cluster.node("clickhouse2")
    clickhouse3 = self.context.cluster.node("clickhouse3")
    mysql = self.context.cluster.node("mysql-master")

    if clickhouse_table_engine.startswith("Replicated"):
        with Then("I check table creation on few nodes"):
            retry(clickhouse.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            retry(clickhouse1.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            retry(clickhouse2.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            retry(clickhouse3.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
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
