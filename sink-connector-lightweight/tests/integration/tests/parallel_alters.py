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

    with Given(
            f"I create different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {clickhouse_table[0]} "
            f"and ClickHouse table engine {clickhouse_table[1]}"
    ):
        tables_list = define(
            "List of replicated tables for test",
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

    input = ["new_col1", "new_col2", "new_col3"]

    output = ['"new_col1"', '"new_col2"', '"new_col3"']

    for clickhouse_table in self.context.available_clickhouse_tables:
        with Given(
                f"I create and insert data in different MySQL to ClickHouse replicated tables with "
                f"ClickHouse table creation method {clickhouse_table[0]} "
                f"and ClickHouse table engine {clickhouse_table[1]}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table=clickhouse_table),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table}", flags=TE):
                with When(
                        "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in input:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has all the new columns"
                ):
                    for message in output:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(f"DESC test.{table_name} FORMAT CSV", message=message)


@TestFeature
def multiple_parallel_add_and_rename_column(self, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries and
     multiple `ALTER TABLE RENAME COLUMN` parallel queries MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    input = ["new_col1", "new_col2", "new_col3"]

    output = ["new_col111", "new_col222", "new_col333"]

    for clickhouse_table in self.context.available_clickhouse_tables:
        with Given(
                f"I create and insert data in different MySQL to ClickHouse replicated tables with "
                f"ClickHouse table creation method {clickhouse_table[0]} "
                f"and ClickHouse table engine {clickhouse_table[1]}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table=clickhouse_table),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table}", flags=TE):
                with When(
                        "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in input:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with And(
                        "I perform multiple `ALTER TABLE RENAME COLUMN` parallel in MySQL"
                ):
                    By(f"rename column new_col1", test=rename_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col1",
                        new_column_name="new_col111"
                    )
                    By(f"rename column new_col2", test=rename_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col2",
                        new_column_name="new_col222"
                    )
                    By(f"rename column new_col3", test=rename_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col3",
                        new_column_name="new_col333"
                    )

                    join()

                with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has all the renamed columns"
                ):
                    for message in output:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(f"DESC test.{table_name} FORMAT CSV", message=message)


@TestFeature
def multiple_parallel_add_and_change_column(self, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries and
     multiple `ALTER TABLE CHANGE COLUMN` parallel queries MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    input = ["new_col1", "new_col2", "new_col3"]

    output = ['"new_col111","Int32"', '"new_col222","Int32"', '"new_col333","Int32"']

    for clickhouse_table in self.context.available_clickhouse_tables:
        with Given(
                f"I create and insert data in different MySQL to ClickHouse replicated tables with "
                f"ClickHouse table creation method {clickhouse_table[0]} "
                f"and ClickHouse table engine {clickhouse_table[1]}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table=clickhouse_table),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table}", flags=TE):
                with When(
                        "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in input:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with And(
                        "I perform multiple `ALTER TABLE CHANGE COLUMN` parallel in MySQL"
                ):
                    By(f"change column new_col1", test=change_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col1",
                        new_column_name="new_col111",
                        new_column_type="INT"
                    )
                    By(f"change column new_col2", test=change_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col2",
                        new_column_name="new_col222",
                        new_column_type="INT"
                    )
                    By(f"change column new_col3", test=change_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col3",
                        new_column_name="new_col333",
                        new_column_type="INT"
                    )

                    join()

                with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has all the changed columns"
                ):
                    for message in output:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(f"DESC test.{table_name} FORMAT CSV", message=message)


@TestFeature
def multiple_parallel_add_and_modify_column(self, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries and
     multiple `ALTER TABLE MODIFY COLUMN` parallel queries MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    input = ["new_col1", "new_col2", "new_col3"]

    output = ['"new_col1","Int32"', '"new_col2","Int32"', '"new_col3","Int32"']

    for clickhouse_table in self.context.available_clickhouse_tables:
        with Given(
                f"I create and insert data in different MySQL to ClickHouse replicated tables with "
                f"ClickHouse table creation method {clickhouse_table[0]} "
                f"and ClickHouse table engine {clickhouse_table[1]}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table=clickhouse_table),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table}", flags=TE):
                with When(
                        "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in input:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with And(
                        "I perform multiple `ALTER TABLE MODIFY COLUMN` parallel in MySQL"
                ):
                    By(f"modify column new_col1", test=modify_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col1",
                        new_column_type="INT"
                    )
                    By(f"modify column new_col2", test=modify_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col2",
                        new_column_type="INT"
                    )
                    By(f"modify column new_col3", test=modify_column, parallel=True)(
                        node=node,
                        table_name=table_name,
                        column_name="new_col3",
                        new_column_type="INT"
                    )

                    join()

                with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has all the modified columns"
                ):
                    for message in output:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(f"DESC test.{table_name} FORMAT CSV", message=message)


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
