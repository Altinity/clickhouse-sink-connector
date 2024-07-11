from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
import string
import random
from keyword import iskeyword
from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableNames_Valid,
)


def generate_table_names(num_names, max_length=64):
    """Generate a set of unique MySQL table names."""
    reserved_keywords = [
        "Select",
        "Insert",
        "Update",
        "Delete",
        "Group",
        "Where",
        "Transaction",
    ]

    def generate_table_name(length):
        """Generate a random table name of a given length."""
        # Characters allowed in table names (excluding characters that require escaping)
        allowed_chars = string.ascii_letters + string.digits
        return "".join(random.choice(allowed_chars) for _ in range(length))

    table_names = set()

    table_names.update(reserved_keywords)

    while len(table_names) < num_names:
        length = random.randint(1, max_length)

        name = generate_table_name(length)

        if iskeyword(name) or name[0] in string.digits:
            name = f"{name}"

        table_names.add(f"{name}")

    return table_names


@TestScenario
def check_table_names(self, table_name):
    """Check that the table with the given name is replicated in ClickHouse."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node

    with Given(f"I create the {table_name} table"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns="x INT",
        )

    with And("I insert data into the table"):
        mysql_node.query(rf"INSERT INTO \`{table_name}\` VALUES (1, 1);")

    with Then(f"I check that the {table_name} was created in the ClickHouse side"):
        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_node.query(rf"EXISTS test.\`{table_name}\`", message="1")

    with And("I check that the data was inserted correctly into the ClickHouse table"):
        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_data = clickhouse_node.query(
                    rf"SELECT id,x FROM test.\`{table_name}\` FORMAT CSV"
                )
                assert clickhouse_data.output.strip() == "1,1", error()


@TestModule
@Name("table names")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableNames_Valid("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
    table_names_count=50,
    table_name_max_length=64,
):
    """
    Check replication of tables with different name combinations.
        - Names starting with a number
        - Names containing spaces
        - Names using reserved keywords
        - Names with mixed alphanumeric characters and safe symbols
    """
    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    table_names = generate_table_names(
        num_names=table_names_count,
        max_length=table_name_max_length,
    )

    for table_name in table_names:
        Scenario(name=f"check table with {table_name} name", test=check_table_names)(
            table_name=table_name
        )
