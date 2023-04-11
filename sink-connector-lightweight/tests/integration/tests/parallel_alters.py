from integration.tests.steps.sql import *
from integration.tests.steps.alter import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def create_replicated_tables(
    self,
    name,
    clickhouse_table,
    node=None,
):
    """Outline to create MySQL to CLickHouse replicated table."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = name

    with Given("I create MySQL to ClickHouse replicated tables"):
        tables_list = define(
            "List of tables for test",
            create_tables(table_name=name, clickhouse_table=clickhouse_table),
        )

    for table_name in tables_list:
        with When("I insert some data into MySQL table"):
            node.query(f"INSERT INTO {table_name} values (1,1);")

        with And("I check that ClickHouse replicated table was created"):
            retry(self.context.cluster.node("clickhouse").query, timeout=100, delay=5)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )

    return tables_list


@TestFeature
def multiple_parallel_add_column(self, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    for clickhouse_table in self.context.available_clickhouse_tables:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_list = define(
                "List of tables for test",
                create_replicated_tables(name=name, clickhouse_table=clickhouse_table),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table}", flags=TE):
                with When(
                    "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    By(f"add columt new_col1", test=add_column, parallel=True)(
                        node=node,
                        table_name=name,
                        column_name="new_col1",

                    )
                    By(f"add columt new_col2", test=add_column, parallel=True)(
                        node=node,
                        table_name=name,
                        column_name="new_col2",

                    )
                    By(f"add columt new_col3", test=add_column, parallel=True)(
                        node=node,
                        table_name=name,
                        column_name="new_col3",

                    )

                    join()

                with Then("I check that Clickhouse replicated table has all the new columns"):
                    retry(self.context.cluster.node("clickhouse").query, timeout=100, delay=5)(
                        f"DESC test.{table_name} FORMAT CSV", message='"new_col1"'
                    )
                    retry(self.context.cluster.node("clickhouse").query, timeout=100, delay=5)(
                        f"DESC test.{table_name} FORMAT CSV", message='"new_col2"'
                    )
                    retry(self.context.cluster.node("clickhouse").query, timeout=100, delay=5)(
                        f"DESC test.{table_name} FORMAT CSV", message='"new_col3"'
                    )


@TestModule
@Name("parallel alters")
def module(self):
    """Check parallel `ALTER` queries for MySql to ClickHouse replication."""
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()