from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def mysql_to_clickhouse_connection(
    self, mysql_type, ch_type, nullable, replicated, auto_create_tables
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert."""

    table_name = f"test{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=auto_create_tables, topics=f"SERVER5432.test.{table_name}"
    )

    with Given(f"I create tables for current test"):
        create_tables(
            table_name=table_name,
            mysql_type=mysql_type,
            ch_type=ch_type,
            nullable=nullable,
            replicated=replicated,
            auto_create_tables=auto_create_tables,
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
    mysql_type="INT",
    ch_type="Int32",
    nullable=True,
    replicated=False,
    auto_create_tables=True,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with auto table creation."""
    mysql_to_clickhouse_connection(
        mysql_type=mysql_type,
        ch_type=ch_type,
        nullable=nullable,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def mysql_to_clickhouse_manual(
    self,
    mysql_type="INT",
    ch_type="Int32",
    nullable=True,
    replicated=False,
    auto_create_tables=False,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with manual table creation."""
    mysql_to_clickhouse_connection(
        mysql_type=mysql_type,
        ch_type=ch_type,
        nullable=nullable,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def mysql_to_clickhouse_replicated(
    self,
    mysql_type="INT",
    ch_type="Int32",
    nullable=True,
    replicated=True,
    auto_create_tables=False,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with manual replicated table
    creation."""
    mysql_to_clickhouse_connection(
        mysql_type=mysql_type,
        ch_type=ch_type,
        nullable=nullable,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
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
