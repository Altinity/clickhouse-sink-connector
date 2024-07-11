from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *


@TestOutline
@Name("simple update")
def simple_update(
    self,
    mysql_columns,
    clickhouse_columns,
    clickhouse_table_engine,
    primary_key,
    engine,
):
    """Check that simple updates to MySQL is properly propagated to the replicated ClickHouse table."""

    table_name = f"update_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(
        f"I create MySQL to ClickHouse replicated table", description=table_name
    ):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
            primary_key=primary_key,
        )

    with When(f"I insert data in MySQL table"):
        mysql.query(f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');")
    with Then(f"I update data in MySQL table"):
        mysql.query(f"UPDATE {table_name} SET k=k+5 WHERE id=1;")

    with And("I check that ClickHouse has updated data as MySQL"):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            manual_output='1,7,"a","b"',
            clickhouse_table_engine=clickhouse_table_engine,
            statement="id,k,c,pad",
            with_final=True,
        )


@TestFeature
def simple_primary_key(self):
    """Check replication for `UPDATE` with simple primary key and without table engine."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            simple_update(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=False,
            )


@TestFeature
def simple_primary_key_innodb(self):
    """Check replication for `UPDATE` with simple primary key and with table engine InnoDB."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            simple_update(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=True,
            )


@TestOutline
def update_zero_rows(self, table_name, node=None):
    """Check that `UPDATE` can change zero rows."""
    with When(f"I update zero rows in MySQL table {table_name}"):
        update(row_update=True, table_name=table_name, condition="id < -1")

    with Then(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            statement="count(*)",
            with_final=True,
        )


@TestOutline
def update_all_rows(self, table_name, node=None):
    """Check that `UPDATE` can change all rows."""
    if node is None:
        node = self.context.node

    with When(f"I update all rows from MySQL table {table_name}"):
        update(row_update=True, table_name=table_name, condition="id > -1")

    with And(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            statement="count(*)",
            with_final=True,
        )


@TestOutline
def update_small_subset(self, table_name):
    """Check that `UPDATE` can change a small subset of rows."""

    with When(f"I update a small subset of rows from MySQL table {table_name}"):
        update(row_update=True, table_name=table_name, condition="x < 10")

    with Then(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            statement="count(*)",
            with_final=True,
        )


@TestOutline
def update_large_subset(self, table_name):
    """Check that `UPDATE` can change a large subset of rows."""

    with When(f"I update a large subset of rows from MySQL table {table_name}"):
        update(row_update=True, table_name=table_name, condition="x > 10")

    with Then(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            statement="count(*)",
            with_final=True,
        )


@TestOutline
def update_all_rows_from_half_of_parts(self, table_name, node=None):
    """Check that `UPDATE` can change all rows from half of the parts."""

    with When(
        f"I update all rows from half of the parts from MySQL table {table_name}"
    ):
        update(row_update=True, table_name=table_name, condition="id < 5")

    with Then(
        "I check that MySQL tables and Clickhouse replication tables have the same data"
    ):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
@Name("one partition one part")
def one_partition_one_part(self):
    """Check `UPDATE` with a table that has one partition and one part."""

    for outline in loads(current_module(), Outline):
        if outline.name != "simple update":
            with Scenario(test=outline):
                name = f"{getuid()}"

                for clickhouse_table_engine in self.context.clickhouse_table_engines:
                    with Given("I create MySQL to ClickHouse replicated tables"):
                        tables_names = define(
                            "List of tables for test",
                            create_tables(
                                table_name=name,
                                clickhouse_table_engine=clickhouse_table_engine,
                            ),
                        )

                    for table_name in tables_names:
                        if table_name.endswith("complex") or table_name.endswith(
                            "no_primary_key"
                        ):
                            if table_name.endswith("_no_primary_key"):
                                skip(
                                    "doesn't work without primary key as only last row of insert is replicated"
                                )
                            with Example(f"{table_name}", flags=TE):
                                with When(
                                    "I perform insert in MySQL to create one partition and one part in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=1,
                                        parts_per_partition=1,
                                        block_size=100,
                                    )

                                with And(
                                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                                ):
                                    verify_table_creation_in_clickhouse(
                                        table_name=table_name,
                                        statement="count(*)",
                                        with_final=True,
                                    )

                                outline(table_name=table_name)


@TestFeature
@Name("one partition many parts")
def one_partition_many_parts(self):
    """Check `UPDATE` with a table that has one partition and many parts."""

    for outline in loads(current_module(), Outline):
        if outline.name != "simple update":
            with Scenario(test=outline):
                name = f"{getuid()}"

                for clickhouse_table_engine in self.context.clickhouse_table_engines:
                    with Given("I create MySQL to ClickHouse replicated tables"):
                        tables_names = define(
                            "List of tables for test",
                            create_tables(
                                table_name=name,
                                clickhouse_table_engine=clickhouse_table_engine,
                            ),
                        )

                    for table_name in tables_names:
                        if table_name.endswith("complex") or table_name.endswith(
                            "no_primary_key"
                        ):
                            if table_name.endswith("_no_primary_key"):
                                skip(
                                    "doesn't work without primary key as only last row of insert is replicated"
                                )

                            with Example(f"{table_name}", flags=TE):
                                with When(
                                    "I perform insert in MySQL to create one partition with many parts in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=1,
                                        parts_per_partition=10,
                                        block_size=100,
                                    )

                                with And(
                                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                                ):
                                    verify_table_creation_in_clickhouse(
                                        table_name=table_name,
                                        statement="count(*)",
                                        with_final=True,
                                    )

                                outline(table_name=table_name)


@TestFeature
@Name("one partition mixed parts")
def one_partition_mixed_parts(self, node=None):
    """Check `UPDATE` with a table that has one partition, one large part, and many small parts."""

    for outline in loads(current_module(), Outline):
        if outline.name != "simple update":
            with Scenario(test=outline):
                name = f"{getuid()}"

                for clickhouse_table_engine in self.context.clickhouse_table_engines:
                    with Given("I create MySQL to ClickHouse replicated tables"):
                        tables_names = define(
                            "List of tables for test",
                            create_tables(
                                table_name=name,
                                clickhouse_table_engine=clickhouse_table_engine,
                            ),
                        )

                    for table_name in tables_names:
                        if table_name.endswith("complex") or table_name.endswith(
                            "no_primary_key"
                        ):
                            if table_name.endswith("_no_primary_key"):
                                skip(
                                    "doesn't work without primary key as only last row of insert is replicated"
                                )
                            with Example(f"{table_name}", flags=TE):
                                with When(
                                    "I perform insert in MySQL to create one large part in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=1,
                                        parts_per_partition=1,
                                        block_size=1000,
                                    )

                                with And(
                                    "I perform insert in MySQL to create many small parts in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        start_id=2,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=1,
                                        parts_per_partition=100,
                                        block_size=10,
                                    )

                                with And(
                                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                                ):
                                    verify_table_creation_in_clickhouse(
                                        table_name=table_name,
                                        statement="count(*)",
                                        with_final=True,
                                    )

                                outline(table_name=table_name)


@TestFeature
@Name("many partitions one part")
def many_partitions_one_part(self):
    """Check `UPDATE` with a table that has many partitions and one part."""

    for outline in loads(current_module(), Outline):
        if outline.name != "simple update":
            with Scenario(test=outline):
                name = f"{getuid()}"

                for clickhouse_table_engine in self.context.clickhouse_table_engines:
                    with Given("I create MySQL to ClickHouse replicated tables"):
                        tables_names = define(
                            "List of tables for test",
                            create_tables(
                                table_name=name,
                                clickhouse_table_engine=clickhouse_table_engine,
                            ),
                        )

                    for table_name in tables_names:
                        if table_name.endswith("complex") or table_name.endswith(
                            "no_primary_key"
                        ):
                            if table_name.endswith("_no_primary_key"):
                                skip(
                                    "doesn't work without primary key as only last row of insert is replicated"
                                )
                            with Example(f"{table_name}", flags=TE):
                                with When(
                                    "I perform insert in MySQL to create many partition and one part in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=10,
                                        parts_per_partition=1,
                                        block_size=100,
                                    )

                                with And(
                                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                                ):
                                    verify_table_creation_in_clickhouse(
                                        table_name=table_name,
                                        statement="count(*)",
                                        with_final=True,
                                    )

                                outline(table_name=table_name)


@TestFeature
@Name("many partitions many parts")
def many_partitions_many_parts(self):
    """Check `UPDATE` with a table that has many partitions and many parts."""

    for outline in loads(current_module(), Outline):
        if outline.name != "simple update":
            with Scenario(test=outline):
                name = f"{getuid()}"

                for clickhouse_table_engine in self.context.clickhouse_table_engines:
                    with Given("I create MySQL to ClickHouse replicated tables"):
                        tables_names = define(
                            "List of tables for test",
                            create_tables(
                                table_name=name,
                                clickhouse_table_engine=clickhouse_table_engine,
                            ),
                        )

                    for table_name in tables_names:
                        if table_name.endswith("complex") or table_name.endswith(
                            "no_primary_key"
                        ):
                            if table_name.endswith("_no_primary_key"):
                                skip(
                                    "doesn't work without primary key as only last row of insert is replicated"
                                )

                            with Example(f"{table_name}", flags=TE):
                                with When(
                                    "I perform insert in MySQL to create many partition and many parts in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=100,
                                        parts_per_partition=100,
                                        block_size=100,
                                    )

                                with And(
                                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                                ):
                                    verify_table_creation_in_clickhouse(
                                        table_name=table_name,
                                        statement="count(*)",
                                        with_final=True,
                                    )

                                outline(table_name=table_name)


@TestFeature
@Name("many partitions mixed parts")
def many_partitions_mixed_parts(self):
    """Check `UPDATE` with a table that has many partitions, each with one large part and many small parts."""

    for outline in loads(current_module(), Outline):
        if outline.name != "simple update":
            with Scenario(test=outline):
                name = f"{getuid()}"

                for clickhouse_table_engine in self.context.clickhouse_table_engines:
                    with Given("I create MySQL to ClickHouse replicated tables"):
                        tables_names = define(
                            "List of tables for test",
                            create_tables(
                                table_name=name,
                                clickhouse_table_engine=clickhouse_table_engine,
                            ),
                        )

                    for table_name in tables_names:
                        if table_name.endswith("complex") or table_name.endswith(
                            "no_primary_key"
                        ):
                            if table_name.endswith("_no_primary_key"):
                                skip(
                                    "doesn't work without primary key as only last row of insert is replicated"
                                )
                            with Example(f"{table_name}", flags=TE):
                                with When(
                                    "I perform insert in MySQL to create one large part in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=1,
                                        parts_per_partition=1,
                                        block_size=1000,
                                    )

                                with And(
                                    "I perform insert in MySQL to create many small parts in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        start_id=2,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=10,
                                        parts_per_partition=10,
                                        block_size=10,
                                    )

                                with And(
                                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                                ):
                                    verify_table_creation_in_clickhouse(
                                        table_name=table_name,
                                        statement="count(*)",
                                        with_final=True,
                                    )

                                outline(table_name=table_name)


@TestFeature
@Name("one million datapoints")
def one_million_datapoints(self):
    """Check `UPDATE` statement with a table that has one million entries."""

    for outline in loads(current_module(), Outline):
        if outline.name != "simple update":
            with Scenario(test=outline):
                name = f"{getuid()}"

                for clickhouse_table_engine in self.context.clickhouse_table_engines:
                    with Given("I create MySQL to ClickHouse replicated tables"):
                        tables_names = define(
                            "List of tables for test",
                            create_tables(
                                table_name=name,
                                clickhouse_table_engine=clickhouse_table_engine,
                            ),
                        )

                    for table_name in tables_names:
                        if table_name.endswith("complex") or table_name.endswith(
                            "no_primary_key"
                        ):
                            if table_name.endswith("_no_primary_key"):
                                skip(
                                    "doesn't work without primary key as only last row of insert is replicated"
                                )

                            with Example(f"{table_name}", flags=TE):
                                with When(
                                    "I perform insert in MySQL to create one million entries in replicated ClickHouse table"
                                ):
                                    complex_insert(
                                        node=self.context.cluster.node("mysql-master"),
                                        table_name=table_name,
                                        values=["({x},{y})", "({x},{y})"],
                                        partitions=10,
                                        parts_per_partition=10,
                                        block_size=10,
                                    )

                                with And(
                                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                                ):
                                    verify_table_creation_in_clickhouse(
                                        table_name=table_name,
                                        statement="count(*)",
                                        with_final=True,
                                    )

                                outline(table_name=table_name)


@TestFeature
@Name("parallel")
def parallel(self):
    """Check that after different parallel `UPDATE` queries MySQL and Clickhouse has the same data."""

    name = f"{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_names = define(
                "List of tables for test",
                create_tables(
                    table_name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            if table_name.endswith("_no_primary_key"):
                skip(
                    "doesn't work without primary key as only last row of insert is replicated"
                )

            with Example(f"{table_name}", flags=TE):
                with When(
                    "I perform insert in MySQL to create one partition and one part in replicated ClickHouse table"
                ):
                    complex_insert(
                        node=self.context.cluster.node("mysql-master"),
                        table_name=table_name,
                        values=["({x},{y})", "({x},{y})"],
                        partitions=1000,
                        parts_per_partition=1,
                        block_size=1,
                    )

                with And(
                    "I perform updates in MySQL to make parallel updates in replicated ClickHouse table"
                ):
                    By(f"update zero rows", test=update, parallel=True)(
                        row_update=True, table_name=table_name, condition="id < -1"
                    )

                    By(
                        f"update rows with `x` column value less then 10",
                        test=update,
                        parallel=True,
                    )(row_update=True, table_name=table_name, condition="x < 10")

                    By(
                        f"update rows with `x` column value more then 20",
                        test=update,
                        parallel=True,
                    )(row_update=True, table_name=table_name, condition="x > 20")

                    join()

                with Then(
                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                ):
                    verify_table_creation_in_clickhouse(
                        table_name=table_name,
                        statement="count(*)",
                        with_final=True,
                    )


@TestModule
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Updates("1.0"))
@Name("update")
def module(self):
    """MySQL to ClickHouse replication update tests to test `UPDATE` queries."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
