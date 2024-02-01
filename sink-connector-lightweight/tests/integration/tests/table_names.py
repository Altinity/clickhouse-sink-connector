from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *
import string
import random


def generate_table_names(size=64, count=10):
    special_chars = "$%&*()-+={}|;:'\",<>./?"
    ascii_chars = string.ascii_letters + string.digits + special_chars
    reserved_keywords = [
        "Select",
        "Insert",
        "Update",
        "Delete",
        "Group",
        "Where",
        "Transaction",
    ]

    names = set()

    names.update(reserved_keywords)

    # Generate names with special characters
    for _ in range(count // 3):
        name = (
            "/`"
            + "".join(
                random.choice(ascii_chars) for _ in range(random.randint(1, size - 2))
            )
            + "/`"
        )
        names.add(name)

    # Include reserved keywords with a twist
    for keyword in random.sample(
        reserved_keywords, min(len(reserved_keywords), count // 3)
    ):
        name = (
            "/`"
            + keyword
            + "".join(random.choice(string.digits) for _ in range(random.randint(1, 3)))
            + "/`"
        )
        names.add(name)

    # Generate names starting with numbers and containing special characters
    while len(names) < count:
        name = (
            "/`"
            + random.choice(string.digits)
            + "".join(
                random.choice(ascii_chars) for _ in range(random.randint(1, size - 3))
            )
            + "/`"
        )
        names.add(name)

    return names


@TestCheck
def check_table_names(self, table_name):
    """Check that the table with the given name is replicated in ClickHouse."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node

    with Given(f"I create the {table_name} table"):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns="x INT",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=self.context.clickhouse_table_engines[0],
        )

        with And("I insert data into the table"):
            mysql_node.query(f"INSERT INTO {table_name} VALUES (1);")

        with Check(f"I check that the {table_name} was created in the ClickHouse side"):
            for retry in retries(timeout=20):
                with retry:
                    clickhouse_node.query(f"EXISTS {table_name}", message="1")


@TestSketch(Scenario)
@Flags(TE)
def table_names(self):
    table_names = generate_table_names(
        size=self.context.table_name_max_length, count=self.context.table_names_count
    )

    for table_name in table_names:
        check_table_names(table_name=table_name)


@TestModule
@Name("table names")
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
    table_names_count=100,
    table_name_max_length=64,
):
    """Check tables with PARTITION BY for MySql to ClickHouse replication."""
    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)
    self.context.table_names_count = table_names_count
    self.context.table_name_max_length = table_name_max_length

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Scenario):
                Scenario(test=feature, parallel=True, executor=executor)()
        finally:
            join()
