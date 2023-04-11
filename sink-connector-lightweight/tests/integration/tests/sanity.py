from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def mysql_to_clickhouse_connection(
    self,
    mysql_columns,
    clickhouse_table,
    clickhouse_columns=None,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert."""

    table_name = f"sanity_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySql to CH replicated table", description=table_name):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
            clickhouse_columns=clickhouse_columns,
            clickhouse_table=clickhouse_table,
        )

    with When(f"I insert data in MySql table"):
        complex_insert(
            node=mysql,
            table_name=table_name,
            values=["({x},{y})", "({x},{y})"],
            partitions=10,
            parts_per_partition=1,
            block_size=1,
        )

    with Then(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        complex_check_creation_and_select(
            table_name=table_name,
            clickhouse_table=clickhouse_table,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
@Name("mysql to clickhouse")
def mysql_to_clickhouse(
    self,
    mysql_columns="MyData INT",
    clickhouse_columns="MyData Int32",
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with all availabe methods and tables."""

    for clickhouse_table in self.context.available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            mysql_to_clickhouse_connection(
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table=clickhouse_table,
            )


@TestModule
@Name("sanity")
def module(self):
    """MySql to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
