from integration.tests.steps.mysql import *
from integration.tests.steps.clickhouse import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
import random


@TestOutline
def mysql_to_clickhouse_snowflake(
    self,
    mysql_columns,
    clickhouse_table_engine,
    clickhouse_columns=None,
):
    """Basic check MySQL to Clickhouse replicated table `_version` column receives snowflake data."""

    table_name = f"sanity_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
        )

    with When(f"I insert data in MySQL table"):
        self.context.cluster.node("mysql-master").query(
            f"INSERT INTO {table_name} values (1,1)"
        )
        time.sleep(random.randint(0, 10))
        self.context.cluster.node("mysql-master").query(
            f"INSERT INTO {table_name} values (2,2)"
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

    with And("I check that Clickhouse replication tables have correct snowflake data"):
        row_one_version = (
            self.context.cluster.node("clickhouse")
            .query(f"SELECT _version FROM test.{table_name} FINAL WHERE id == 1")
            .output.strip()
        )
        row_two_version = (
            self.context.cluster.node("clickhouse")
            .query(f"SELECT _version FROM test.{table_name} FINAL WHERE id == 2")
            .output.strip()
        )

        if int(row_one_version) > 0 and int(row_two_version) > 0:
            assert int(row_one_version) <= int(row_two_version)
        else:
            raise Error("wrong data in version column")


@TestFeature
@Name("snowflake id simple")
def snowflake_id_simple(
    self,
    mysql_columns="MyData INT",
    clickhouse_columns="MyData Int32",
):
    """Check MySQL to Clickhouse version column receives snowflake id for all available methods."""

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            mysql_to_clickhouse_snowflake(
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestOutline
def mysql_to_clickhouse_snowflake_with_mysql_restart(
    self,
    mysql_columns,
    clickhouse_table_engine,
    clickhouse_columns=None,
    mysql_restarts_number=3,
):
    """Check MySQL to Clickhouse replicated table `_version` column receives increased snowflake values after MySQL restart."""

    table_name = f"sanity_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
        )

    with When(f"I insert data in MySQL table"):
        self.context.cluster.node("mysql-master").query(
            f"INSERT INTO {table_name} values (1,777)"
        )

    for i in range(mysql_restarts_number):
        with And(
            f"I make {mysql_restarts_number} MySQL node restarts and insert data in MySQL table"
        ):
            self.context.cluster.node("mysql-master").restart()
            retry(
                self.context.cluster.node("mysql-master").query,
                timeout=100,
                delay=3,
            )(f"SHOW TABLES", message=f"{table_name}")

            self.context.cluster.node(f"mysql-master").query(
                f"INSERT INTO {table_name} values ({i+2},2)"
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

        with And(
            "I check that Clickhouse replication tables have correct snowflake data"
        ):
            row_one_version = (
                self.context.cluster.node("clickhouse")
                .query(
                    f"SELECT _version FROM test.{table_name} FINAL WHERE id == {i}+1"
                )
                .output.strip()
            )
            row_two_version = (
                self.context.cluster.node("clickhouse")
                .query(
                    f"SELECT _version FROM test.{table_name} FINAL WHERE id == {i}+2"
                )
                .output.strip()
            )

            if int(row_one_version) > 0 and int(row_two_version) > 0:
                assert int(row_one_version) <= int(row_two_version)
            else:
                raise Error("wrong data in version column")


@TestFeature
@Name("snowflake id simple restart")
def snowflake_id_simple_restart(
    self,
    mysql_columns="MyData INT",
    clickhouse_columns="MyData Int32",
):
    """Check MySQL to Clickhouse version column receives increased snowflake id after MySQL restart
    for all available methods."""

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            mysql_to_clickhouse_snowflake_with_mysql_restart(
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestModule
@Name("snowflake id")
def module(self):
    """Check MySQL to ClickHouse replication 64-bit snowflake id for the version column"""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
