from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Special,
)
from integration.tests.steps.alter import drop_column
from integration.tests.steps.common import generate_sample_mysql_value
from integration.tests.steps.service_settings_steps import *
from integration.tests.steps.sql import *
from integration.tests.steps.statements import all_mysql_datatypes_dict


@TestStep(Given)
def create_table_with_is_deleted(
    self, table_name, datatype="int", data="5", column="is_deleted"
):
    """Create mysql table that contains the column with the name 'is_deleted'"""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node

    with By(
        f"creating a {table_name} table with is_deleted column and {datatype} datatype"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=f"\`{table_name}\`",
            mysql_columns=f"col1 varchar(255), col2 int, {column} {datatype}",
            clickhouse_table_engine=self.context.clickhouse_table_engines[0],
        )

    with And(f"inserting data into the {table_name} table"):
        mysql_node.query(f"INSERT INTO {table_name} VALUES (1, 'test', 1, {data})")

    with And("I make sure that the table was replicated on the ClickHouse side"):
        for retry in retries(timeout=40):
            with retry:
                clickhouse_node.query(f"EXISTS test.{table_name}", message="1")


@TestScenario
def check_is_deleted(self):
    """Check that when creating a mysql table with is_deleted column when the ReplacingMergeTree table on the
    ClickHouse side already has that column, the column in ClickHouse is renamed to __is_deleted and new is_deleted
    column is created."""

    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    table_name = "tb_" + getuid()

    with Given(f"I create the {table_name} table and populate it with data"):
        create_table_with_is_deleted(table_name=table_name)

    with Then("I check that the data was inserted correctly into the ClickHouse table"):
        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_data = clickhouse_node.query(
                    f"DESCRIBE TABLE test.{table_name} FORMAT CSV"
                )
                assert (
                    "__is_deleted" and "is_deleted" in clickhouse_data.output.strip()
                ), error()


@TestScenario
def check_is_deleted_with_underscore(self):
    """Check that when creating a mysql table with __is_deleted column it is replicated to the ClickHouse table."""

    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    table_name = "tb_" + getuid()

    with Given(f"I create the {table_name} table and populate it with data"):
        create_table_with_is_deleted(table_name=table_name, column="__is_deleted")

    with Then("I check that the data was inserted correctly into the ClickHouse table"):
        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_data = clickhouse_node.query(
                    f"DESCRIBE TABLE test.{table_name} FORMAT CSV"
                )
                assert (
                    "_is_deleted" and "is_deleted" in clickhouse_data.output.strip()
                ), error()


@TestScenario
def remove_is_deleted_column(self):
    """Check that after removing the is deleted column from the mysql table and creating it again, the schema is preserved on the ClickHouse side."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    table_name = "tb_" + getuid()

    with Given(f"I create the {table_name} table and populate it with data"):
        create_table_with_is_deleted(table_name=table_name)

    with When(
        "I remove column from the source table and makes sure it is remove from ClickHouse"
    ):
        drop_column(table_name=table_name, column_name="is_deleted")

        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_node.query(
                    f"SELECT is_deleted FROM test.{table_name}",
                    message="DB::Exception: Missing columns: 'is_deleted' while processing query",
                )

    with And("I create the is_deleted column on the source table"):
        mysql_node.query(f"ALTER TABLE {table_name} ADD COLUMN is_deleted INT")
        mysql_node.query(f"INSERT INTO {table_name} VALUES (3, 'test2', 1, 6)")

    with Then(
        "I check that the is_deleted column is created again and the __is_deleted column is still present"
    ):
        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_data = clickhouse_node.query(
                    f"SELECT is_deleted FROM test.{table_name} WHERE id == 3 FORMAT CSV"
                )
                assert clickhouse_data.output.strip() == "6", error()


@TestOutline
def check_is_deleted_datatypes(self, datatype):
    """Check that the source table is replicated on ClickHouse side when it contains the is_deleted column with different mysql datatypes."""
    clickhouse_node = self.context.clickhouse_node
    table_name = "tb_" + getuid()

    values = generate_sample_mysql_value(data_type=datatype)

    with Given(
        f"I create a {table_name} with is_deleted column that has {datatype} datatype"
    ):
        create_table_with_is_deleted(
            table_name=table_name, datatype=datatype, data=values
        )

    with Then(
        f"I check the table replication with is_deleted column and {datatype} datatype"
    ):
        for retry in retries(timeout=40, delay=1):
            with retry:
                describe_clickhouse_table = clickhouse_node.query(
                    f"DESCRIBE TABLE test.{table_name} FORMAT CSV"
                )
                assert (
                    "__is_deleted"
                    and "is_deleted" in describe_clickhouse_table.output.strip()
                ), error()


@TestScenario
def is_deleted_different_datatypes(self):
    """Check that the table is replicated when the is_deleted column on the source table was created
    with all possible MySQL datatypes."""
    datatypes = [
        all_mysql_datatypes_dict[datatype] for datatype in all_mysql_datatypes_dict
    ]

    for datatype in datatypes:
        Check(
            name=f"check is_deleted with {datatype}", test=check_is_deleted_datatypes
        )(datatype=datatype)


@TestModule
@Name("is deleted")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Special("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that the table is replicated when teh source table has a column named is_deleted.

    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)
