from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def create_replicated_tables(
    self,
    name,
    clickhouse_table_engine,
    node=None,
):
    """Outline to create MySQL to CLickHouse replicated table."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = name

    with Given(
        f"I create different MySQL to ClickHouse replicated tables with "
        f"ClickHouse table creation method {self.context.env} "
        f"and ClickHouse table engine {clickhouse_table_engine}"
    ):
        tables_list = define(
            "List of replicated tables for test",
            create_tables(table_name=name, clickhouse_table_engine=clickhouse_table_engine),
        )

        for table_name in tables_list:
            with When("I insert some data into MySQL table"):
                node.query(f"INSERT INTO {table_name} values (1,1);")

            with And("I check that ClickHouse replicated table was created"):
                retry(
                    self.context.cluster.node("clickhouse").query, timeout=100, delay=5
                )("SHOW TABLES FROM test", message=f"{table_name}")

    return tables_list


@TestFeature
def add_change_column(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN, CHANGE COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table_engine=clickhouse_table_engine),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                if not table_name.endswith("_complex"):
                    with When(
                        f"I perform `ALTER TABLE ADD COLUMN, CHANGE COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) AFTER id, CHANGE COLUMN x x2 varchar(255) NULL"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has the new column and changed column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","String","","","","",""\n"x2","Nullable(String)"',
                        )


@TestFeature
def change_add_column(self, node=None):
    """Check that after `ALTER TABLE CHANGE COLUMN, ADD COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table_engine=clickhouse_table_engine),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                if not table_name.endswith("_complex"):
                    with When(
                        f"I perform `ALTER TABLE CHANGE COLUMN, ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} CHANGE COLUMN x x2 varchar(255) NULL, ADD COLUMN new_col varchar(255) AFTER id"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has the new column and changed "
                        f"column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","String","","","","",""\n"x2","Nullable(String)"',
                        )


@TestFeature
def add_modify_column(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN, MODIFY COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table_engine=clickhouse_table_engine),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                if not table_name.endswith("_complex"):
                    with When(
                        f"I perform `ALTER TABLE ADD COLUMN, MODIFY COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) AFTER id, MODIFY COLUMN x varchar(255) NULL"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has the new column and modified column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","String","","","","",""\n"x","Nullable(String)"',
                        )


@TestFeature
def modify_add_column(self, node=None):
    """Check that after `ALTER TABLE MODIFY COLUMN, ADD COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table_engine=clickhouse_table_engine),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                if not table_name.endswith("_complex"):
                    with When(
                        f"I perform `ALTER TABLE MODIFY COLUMN, ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} MODIFY COLUMN x varchar(255) NULL, ADD COLUMN new_col varchar(255) AFTER id"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has the new column and modified "
                        f"column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","String","","","","",""\n"x","Nullable(String)"',
                        )


@TestFeature
def add_rename_column(self, node=None):
    """Check that after `ALTER TABLE ADD COLUMN, RENAME COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table_engine=clickhouse_table_engine),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                if not table_name.endswith("_complex"):
                    with When(
                        f"I perform `ALTER TABLE ADD COLUMN, RENAME COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} ADD COLUMN new_col varchar(255) AFTER id, RENAME COLUMN x to x2"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has the new column and renamed column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","String","","","","",""\n"x2"',
                        )


@TestFeature
def rename_add_column(self, node=None):
    """Check that after `ALTER TABLE RENAME COLUMN, ADD COLUMN` query MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_list = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(name=name, clickhouse_table_engine=clickhouse_table_engine),
            )

        for table_name in tables_list:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                if not table_name.endswith("_complex"):
                    with When(
                        f"I perform `ALTER TABLE RENAME COLUMN, ADD COLUMN` on replicated table {table_name}"
                    ):
                        node.query(
                            f"ALTER TABLE {table_name} RENAME COLUMN x to x2, ADD COLUMN new_col varchar(255) AFTER id"
                        )

                    with Then(
                        f"I check that Clickhouse replicated table test.{table_name} has the new column and renamed "
                        f"column"
                    ):
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message='"new_col","String","","","","",""\n"x2"',
                        )


@TestModule
@Name("serial connection of alters")
def module(self):
    """Check serial connection of `ALTER` queries for MySql to ClickHouse replication."""
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
