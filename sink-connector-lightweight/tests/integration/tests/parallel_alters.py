from integration.tests.steps.mysql import *
from integration.tests.steps.alter import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *


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
        tables_names = define(
            "List of replicated tables for test",
            create_tables(
                table_name=name, clickhouse_table_engine=clickhouse_table_engine
            ),
        )

        for table_name in tables_names:
            with When("I insert some data into MySQL table"):
                node.query(f"INSERT INTO {table_name} values (1,1);")

            with And("I check that ClickHouse replicated table was created"):
                retry(
                    self.context.cluster.node("clickhouse").query, timeout=100, delay=5
                )("SHOW TABLES FROM test", message=f"{table_name}")

    return tables_names


@TestFeature
def multiple_parallel_add_column(self, column_number=5, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

    if self.context.stress:
        column_number = 64

    columns = [f"column_{i}" for i in range(column_number)]

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has all the new columns"
                ):
                    for column_name in columns:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(f"DESC test.{table_name} FORMAT CSV", message=column_name)


@TestFeature
def multiple_parallel_add_and_rename_column(self, column_number=5, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries and
    multiple `ALTER TABLE RENAME COLUMN` parallel queries MySQL and Clickhouse has the same columns.
    """
    xfail("doesn't rename column")
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"tb_{getuid()}"

    if self.context.stress:
        column_number = 64

    columns = [f"column_{i}" for i in range(column_number)]

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with And(
                    "I perform multiple `ALTER TABLE RENAME COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(
                            f"rename column {column_name}",
                            test=rename_column,
                            parallel=True,
                        )(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                            new_column_name=f"new_{column_name}",
                        )

                    join()

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has all the new columns"
                ):
                    for column_name in columns:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message="new_" + column_name,
                        )


@TestFeature
def multiple_parallel_add_and_change_column(self, column_number=5, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries and
    multiple `ALTER TABLE CHANGE COLUMN` parallel queries MySQL and Clickhouse has the same columns.
    """
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

    if self.context.stress:
        column_number = 64

    columns = [f"column_{i}" for i in range(column_number)]

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with And(
                    "I perform multiple `ALTER TABLE CHANGE COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(
                            f"change column {column_name}",
                            test=change_column,
                            parallel=True,
                        )(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                            new_column_name=f"new_{column_name}",
                            new_column_type="INT",
                        )

                    join()

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has all the changed columns"
                ):
                    for column_name in columns:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message=f'"new_{column_name}","Int32',
                        )


@TestFeature
def multiple_parallel_add_and_modify_column(self, column_number=5, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN` parallel queries and
    multiple `ALTER TABLE MODIFY COLUMN` parallel queries MySQL and Clickhouse has the same columns.
    """
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

    if self.context.stress:
        column_number = 64

    columns = [f"column_{i}" for i in range(column_number)]

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with And(
                    "I perform multiple `ALTER TABLE MODIFY COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(
                            f"modify column {column_name}",
                            test=modify_column,
                            parallel=True,
                        )(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                            new_column_type="INT",
                        )

                    join()

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has all the modified columns"
                ):
                    for column_name in columns:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(
                            f"DESC test.{table_name} FORMAT CSV",
                            message=f'"{column_name}","Int32',
                        )


@TestFeature
def multiple_parallel_add_and_drop_column(self, column_number=5, node=None):
    """Check that after multiple `ALTER TABLE DROP COLUMN` parallel queries MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"tb_{getuid()}"

    if self.context.stress:
        column_number = 64

    columns = [f"column_{i}" for i in range(column_number)]

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    "I perform multiple `ALTER TABLE ADD COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(f"add column {column_name}", test=add_column, parallel=True)(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has all the new columns"
                ):
                    for column_name in columns:
                        retry(
                            self.context.cluster.node("clickhouse").query,
                            timeout=100,
                            delay=5,
                        )(f"DESC test.{table_name} FORMAT CSV", message=column_name)

                with When(
                    "I perform multiple `ALTER TABLE DROP COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(
                            f"drop column {column_name}",
                            test=drop_column,
                            parallel=True,
                        )(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the same number of columns "
                    f"as in MySQL table {table_name}"
                ):
                    mysql_columns_number = node.query(
                        columns_number.format(table_name=table_name)
                    ).output.strip()

                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        columns_number.format(table_name=table_name),
                        message=mysql_columns_number[
                            len(
                                "mysql: [Warning] Using a password on the command line "
                                "interface can be insecure.\ncount(*)\n"
                            ) :
                        ],
                    )


@TestFeature
def multiple_parallel_add_modify_drop_column(self, column_number=5, node=None):
    """Check that after multiple `ALTER TABLE ADD COLUMN`,`ALTER TABLE MODIFY COLUMN`,`ALTER TABLE DROP COLUMN` parallel
    queries MySQL and Clickhouse has the same columns."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    name = f"f{getuid()}"

    if self.context.stress:
        column_number = 64

    columns = [f"column_{i}" for i in range(column_number)]

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(
            f"I create and insert data in different MySQL to ClickHouse replicated tables with "
            f"ClickHouse table creation method {self.context.env} "
            f"and ClickHouse table engine {clickhouse_table_engine}"
        ):
            tables_names = define(
                "List of different replicated tables with inserted data",
                create_replicated_tables(
                    name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            with Example(f"{table_name} {clickhouse_table_engine}", flags=TE):
                with When(
                    "I perform multiple `ALTER TABLE ADD COLUMN`,`ALTER TABLE MODIFY COLUMN`,"
                    "`ALTER TABLE DROP COLUMN` parallel in MySQL"
                ):
                    for column_name in columns:
                        By(
                            f"add,modify,drop column {column_name},",
                            test=add_modify_drop_column,
                            parallel=True,
                        )(
                            node=node,
                            table_name=table_name,
                            column_name=column_name,
                        )

                    join()

                with Then(
                    f"I check that Clickhouse replicated table {table_name} has the same number of columns "
                    f"as in MySQL table {table_name}"
                ):
                    mysql_columns_number = node.query(
                        columns_number.format(table_name=table_name)
                    ).output.strip()

                    retry(
                        self.context.cluster.node("clickhouse").query,
                        timeout=100,
                        delay=5,
                    )(
                        columns_number.format(table_name=table_name),
                        message=mysql_columns_number[
                            len(
                                "mysql: [Warning] Using a password on the command line "
                                "interface can be insecure.\ncount(*)\n"
                            ) :
                        ],
                    )


@TestModule
@Name("parallel alters")
def module(self):
    """Check parallel `ALTER` queries for MySQL to ClickHouse replication."""
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
