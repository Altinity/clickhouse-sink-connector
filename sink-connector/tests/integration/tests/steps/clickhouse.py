from integration.helpers.common import *
from integration.tests.steps.datatypes import mysql_to_clickhouse_datatypes_mapping


@TestStep(Then)
def drop_database(self, database_name=None, node=None, cluster=None):
    """Drop ClickHouse database."""

    if cluster is None:
        cluster = "replicated_cluster"

    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")
    with By("executing drop database query"):
        node.query(
            rf"DROP DATABASE IF EXISTS \`{database_name}\` ON CLUSTER {cluster};"
        )


@TestStep
def select_column_type(self, node, database, table_name, column_name):
    """Check column type in ClickHouse table."""
    with By(f"checking column type for {column_name}"):
        node.query(
            f"SELECT type FROM system.columns WHERE table = '{table_name}' AND name = '{column_name}' AND database = '{database}' FORMAT TabSeparated"
        ).output.strip()


@TestStep
def validate_column_type(self, mysq_column_type):
    """Validate column type in ClickHouse table."""
    with By(f"validating column type"):
        assert (
            mysql_to_clickhouse_datatypes_mapping[mysq_column_type]["mysql"]
            == mysql_to_clickhouse_datatypes_mapping[mysq_column_type]["clickhouse"]
        ), f"expected {mysql_to_clickhouse_datatypes_mapping[mysq_column_type]['mysql']} but got {mysql_to_clickhouse_datatypes_mapping[mysq_column_type]['clickhouse']}"


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

                assert (
                    column.output.strip() == expected_output
                ), f"expected {expected_output} but got {column.output.strip()}"


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
                rf"CREATE DATABASE IF NOT EXISTS \`{name}\` ON CLUSTER replicated_cluster"
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
    database=None,
    node=None,
    with_final=False,
    with_optimize=False,
    sign_column="_sign",
    timeout=300,
    where=None,
):
    """SELECT statement in ClickHouse with an option to use FINAL or loop SELECT + OPTIMIZE TABLE default simple 'SELECT'"""
    if node is None:
        node = self.context.cluster.node("clickhouse")
    if table_name is None:
        table_name = "users"
    if statement is None:
        statement = "*"
    if database is None:
        database = "test"

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
            f"SELECT {statement} FROM {database}.{table_name} FINAL",
            message=f"{manual_output}",
        )
    elif with_optimize:
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                node.query(f"OPTIMIZE TABLE {database}.{table_name} FINAL DEDUPLICATE")

                node.query(
                    f"SELECT {statement} FROM {database}.{table_name} where {sign_column} !=-1 FORMAT CSV",
                    message=f"{manual_output}",
                )
    else:
        r = "SELECT {statement} FROM {database}.{table_name}"

        if where:
            r += f" WHERE {where}"

        r += " FORMAT CSV"

        retry(
            node.query,
            timeout=timeout,
            delay=10,
        )(
            r,
            message=f"{manual_output}",
        )


@TestStep(Then)
def check_if_table_was_created(
    self,
    table_name,
    database_name=None,
    node=None,
    timeout=40,
    message=1,
    replicated=False,
):
    """Check if table was created in ClickHouse."""
    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    if replicated:
        for node in self.context.cluster.nodes["clickhouse"]:
            retry(self.context.cluster.node(node).query, timeout=timeout, delay=3)(
                rf"EXISTS \`{database_name}\`.\`{table_name}\`", message=f"{message}"
            )
    else:
        retry(node.query, timeout=timeout, delay=3)(
            rf"EXISTS \`{database_name}\`.\`{table_name}\`", message=f"{message}"
        )


@TestStep(Then)
def validate_data_in_clickhouse_table(
    self,
    table_name,
    expected_output,
    statement="*",
    node=None,
    database_name=None,
    timeout=40,
    replicated=False,
):
    """Validate data in ClickHouse table."""

    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    if replicated:
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
    else:
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
    clickhouse_node=None,
    database_name=None,
    manual_output=None,
    with_final=False,
    with_optimize=False,
    replicated=False,
):
    """
    Verify the creation of tables on all ClickHouse nodes where they are expected, and ensure data consistency with MySQL.
    """

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    if database_name is None:
        database_name = "test"

    if replicated:
        with Then("I check table creation on few nodes"):
            for node in self.context.cluster.nodes["clickhouse"]:
                retry(self.context.cluster.node(node).query, timeout=timeout, delay=3)(
                    f"EXISTS {database_name}.{table_name}", message=f"{message}"
                )
    else:
        with Then("I check table creation"):
            retry(clickhouse_node.query, timeout=100, delay=3)(
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


@TestStep(When)
def get_random_value_from_column(
    self, table_name, column_name, database=None, node=None
):
    """Get random value from ClickHouse table column."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("clickhouse")

    with By(f"getting random value from {column_name}"):
        random_value = node.query(
            rf"SELECT {column_name} FROM {database}.\`{table_name}\` ORDER BY rand() LIMIT 1"
        )

    return random_value
