from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *


@TestOutline
def mysql_to_clickhouse_connection(
    self,
    mysql_columns,
    clickhouse_table_engine,
    clickhouse_columns=None,
):
    """Perform a basic check of the MySQL to ClickHouse connection by inserting a small and simple set of data."""

    table_name = f"sanity_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
        )

    with When(f"I insert data in MySQL table"):
        complex_insert(
            node=mysql,
            table_name=table_name,
            values=["({x},{y})", "({x},{y})"],
            partitions=1,
            parts_per_partition=1,
            block_size=1,
        )

    with Then(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            clickhouse_table_engine=clickhouse_table_engine,
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
    """Check the MySQL to ClickHouse connection by inserting small and simple data using all available methods and tables."""

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            mysql_to_clickhouse_connection(
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestModule
@Name("sanity")
def module(self):
    """MySQL to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
