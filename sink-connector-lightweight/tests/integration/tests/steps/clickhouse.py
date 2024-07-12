from integration.helpers.common import *


@TestStep(Then)
def drop_database(self, database_name=None, node=None):
    """Drop ClickHouse database."""
    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")
    with By("executing drop database query"):
        node.query(
            rf"DROP DATABASE IF EXISTS {database_name} ON CLUSTER replicated_cluster;"
        )


@TestStep(Then)
def check_column(
    self, table_name, column_name, node=None, column_type=None, database=None
):
    """Check if column exists in ClickHouse table."""

    if database is None:
        database = "test"

    if column_type is not None:
        if "varchar" in column_type:
            column_type = "String"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    if column_type is None:
        select = "name"
    else:
        select = "name, type"

    with By(f"checking if {column_name} exists in {table_name}"):
        for retry in retries(timeout=25, delay=1):
            with retry:
                column = node.query(
                    f"SELECT {select} FROM system.columns WHERE table = '{table_name}' AND name = '{column_name}' AND database = '{database}' FORMAT TabSeparated"
                )

                expected_output = (
                    column_name
                    if column_type is None
                    else f"{column_name}	{column_type}"
                )

                assert column.output.strip() == expected_output, error()


@TestStep(Given)
def create_clickhouse_database(self, name=None, node=None):
    """Create ClickHouse database."""
    if name is None:
        name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    try:
        with By(f"adding {name} database if not exists"):
            drop_database(database_name=name)

            node.query(
                rf"CREATE DATABASE IF NOT EXISTS {name} ON CLUSTER replicated_cluster"
            )
        yield
    finally:
        with Finally(f"I delete {name} database if exists"):
            drop_database(database_name=name)


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
def check_if_table_was_created(
    self, table_name, database_name=None, node=None, timeout=40, message=1
):
    """Check if table was created in ClickHouse."""
    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    if self.context.clickhouse_table_engine == "ReplicatedReplacingMergeTree":
        for node in self.context.cluster.nodes["clickhouse"]:
            retry(self.context.cluster.node(node).query, timeout=timeout, delay=3)(
                f"EXISTS {database_name}.{table_name}", message=f"{message}"
            )
    elif self.context.clickhouse_table_engine == "ReplacingMergeTree":
        retry(node.query, timeout=timeout, delay=3)(
            f"EXISTS {database_name}.{table_name}", message=f"{message}"
        )
    else:
        raise Exception("Unknown ClickHouse table engine")


@TestStep(Then)
def validate_data_in_clickhouse_table(
    self,
    table_name,
    expected_output,
    statement="*",
    node=None,
    database_name=None,
    timeout=40,
):
    """Validate data in ClickHouse table."""

    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    if self.context.clickhouse_table_engine == "ReplicatedReplacingMergeTree":
        for node in self.context.cluster.nodes["clickhouse"]:
            for retry in retries(timeout=timeout, delay=1):
                with retry:
                    data = (
                        self.context.cluster.node(node)
                        .query(
                            f"SELECT {statement} FROM {database_name}.{table_name} ORDER BY tuple(*) FORMAT CSV"
                        )
                        .output.strip()
                        .replace('"', "")
                    )

                    assert (
                        data == expected_output
                    ), f"Expected: {expected_output}, Actual: {data}"
    elif self.context.clickhouse_table_engine == "ReplacingMergeTree":
        for retry in retries(timeout=timeout, delay=1):
            with retry:
                data = (
                    node.query(
                        f"SELECT {statement} FROM {database_name}.{table_name} ORDER BY tuple(*) FORMAT CSV"
                    )
                    .output.strip()
                    .replace('"', "")
                )

                assert (
                    data == expected_output
                ), f"Expected: {expected_output}, Actual: {data}"

    else:
        raise Exception("Unknown ClickHouse table engine")


@TestStep(Then)
def validate_rows_number(
    self, table_name, expected_rows, node=None, database_name=None
):
    """Validate number of rows in ClickHouse table."""

    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    for retry in retries(timeout=40):
        with retry:
            data = node.query(
                f"SELECT count(*) FROM {database_name}.{table_name} ORDER BY tuple(*) FORMAT CSV"
            )
            assert data.output.strip().replace('"', "") == expected_rows, error()


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
