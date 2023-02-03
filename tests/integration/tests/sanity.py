from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def mysql_to_clickhouse_connection(
    self,
    mysql_columns,
    replicated,
    auto_create_tables,
    clickhouse_table,
    clickhouse_columns=None,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert."""

    table_name = f"sanity_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=auto_create_tables, topics=f"SERVER5432.test.{table_name}"
    )

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
            partitions=1,
            parts_per_partition=1,
            block_size=10,
        )

    with Then(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        complex_select(
            table_name=table_name,
            auto_create_tables=auto_create_tables,
            replicated=replicated,
            statement="count(*)",
            with_final=True,
        )


@TestScenario
def mysql_to_clickhouse_auto(
    self,
    mysql_columns="MyData INT",
    replicated=False,
    auto_create_tables=True,
    clickhouse_table="auto",
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with auto table creation."""
    mysql_to_clickhouse_connection(
        mysql_columns=mysql_columns,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
        clickhouse_table=clickhouse_table,
    )


@TestScenario
def mysql_to_clickhouse_manual(
    self,
    mysql_columns="MyData INT",
    clickhouse_columns="MyData Int32",
    replicated=False,
    auto_create_tables=False,
    clickhouse_table="ReplacingMergeTree",
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with manual table creation."""
    mysql_to_clickhouse_connection(
        mysql_columns=mysql_columns,
        clickhouse_columns=clickhouse_columns,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
        clickhouse_table=clickhouse_table,
    )


@TestScenario
def mysql_to_clickhouse_replicated(
    self,
    mysql_columns="MyData INT",
    clickhouse_columns="MyData Int32",
    replicated=True,
    auto_create_tables=False,
    clickhouse_table="ReplicatedReplacingMergeTree",
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with manual replicated table
    creation."""
    mysql_to_clickhouse_connection(
        mysql_columns=mysql_columns,
        clickhouse_columns=clickhouse_columns,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
        clickhouse_table=clickhouse_table,
    )


@TestFeature
@Name("sanity")
def feature(self):
    """MySql to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
