from testflows.core import *

from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def simple_insert(
    self, input, output, mysql_columns, clickhouse_table_engine, clickhouse_columns=None
):
    """Check that simple insert to MySQL is properly propagated to the replicated ClickHouse table."""

    table_name = f"tb_{getuid()}"
    mysql = self.context.cluster.node("mysql-master")

    with Given(
        f"I create MySQL to ClickHouse replicated table", description=table_name
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
            clickhouse_table_engine=clickhouse_table_engine,
            clickhouse_columns=clickhouse_columns,
        )

    with When("I insert data in MySQL table"):
        mysql.query(f"INSERT INTO {table_name} (col1,col2,col3) VALUES {input};")

    with Then("I check data inserted correct"):
        complex_check_creation_and_select(
            table_name=table_name,
            manual_output=output,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="col1,col2,col3",
            with_final=True,
        )


@TestFeature
def default_with_null(
    self,
    input="(DEFAULT,5,333)",
    output="\\N,5,333",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col1 Nullable(Int32), col2 Int32, col3 Int32",
):
    """Check replication of insert that contains only one DEFAULT value which is set to NULL."""
    xfail("doesn't work")
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            simple_insert(
                input=input,
                output=output,
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestFeature
def default_with_null_and_non_null(
    self,
    input="(DEFAULT,5,DEFAULT)",
    output="\\N,5,777",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col1 Nullable(Int32), col2 Int32, col3 Int32",
):
    """Check replication of insert that contains two DEFAULT values one of which is set to NULL value."""
    xfail("doesn't work")
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            simple_insert(
                input=input,
                output=output,
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestFeature
def use_select_constant_as_value(
    self,
    input="((select 2),7,DEFAULT)",
    output="2,7,777",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col1 Int32, col2 Int32, col3 Int32",
):
    """Check insert of a value defined using a SELECT constant query."""
    xfail("doesn't work")
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            simple_insert(
                input=input,
                output=output,
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestFeature
def use_select_from_table_as_value(
    self,
    input="((select id from auxiliary_table),7,DEFAULT)",
    output="2,7,777",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col1 Int32, col2 Int32, col3 Int32",
):
    """Check insert of a value defined using a SELECT from auxiliary table query."""
    xfail("doesn't work")
    auxiliary_table = f"auxiliary_table"
    try:
        with Given(f"I create auxiliary MySQL table", description=auxiliary_table):
            self.context.cluster.node("mysql-master").query(
                f"CREATE TABLE IF NOT EXISTS {auxiliary_table} "
                f"(id INT , PRIMARY KEY (id)) ENGINE = InnoDB;",
            )

            self.context.cluster.node("mysql-master").query(
                f"INSERT INTO {auxiliary_table} VALUES (2)",
            )

        for clickhouse_table_engine in self.context.clickhouse_table_engines:
            with Example({clickhouse_table_engine}, flags=TE):
                simple_insert(
                    input=input,
                    output=output,
                    mysql_columns=mysql_columns,
                    clickhouse_columns=clickhouse_columns,
                    clickhouse_table_engine=clickhouse_table_engine,
                )
    finally:
        with Finally(f"I drop MySQL table", description=auxiliary_table):
            self.context.cluster.node("mysql-master").query(
                f"DROP TABLE IF EXISTS {auxiliary_table}"
            )


@TestFeature
@Name("one partition one part")
def one_partition_one_part(self, node=None):
    """Check `INSERT` that creates one partition and one part."""
    name = f"tb_{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_names = define(
                "List of tables for test",
                create_tables(
                    table_name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
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
                        block_size=1,
                    )

                with Then(
                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                ):
                    complex_check_creation_and_select(
                        table_name=table_name,
                        statement="count(*)",
                        with_final=True,
                    )


@TestFeature
@Name("one partition many parts")
def one_partition_many_parts(self, node=None):
    """Check that `INSERT` that creates one partition with many parts to MySQL is properly propagated to the replicated
    ClickHouse table."""
    name = f"tb_{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_names = define(
                "List of tables for test",
                create_tables(
                    table_name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            if table_name.endswith("_complex"):
                with Example(f"{table_name}", flags=TE):
                    with When(
                        "I perform insert in MySQL to create one partition with many parts in replicated ClickHouse table"
                    ):
                        complex_insert(
                            node=self.context.cluster.node("mysql-master"),
                            table_name=table_name,
                            values=["({x},{y})", "({x},{y})"],
                            partitions=1,
                            parts_per_partition=100,
                            block_size=1000,
                        )

                    with Then(
                        "I check that MySQL tables and Clickhouse replication tables have the same data"
                    ):
                        complex_check_creation_and_select(
                            table_name=table_name,
                            statement="count(*)",
                            with_final=True,
                        )


@TestFeature
@Name("one partition mixed parts")
def one_partition_mixed_parts(self, node=None):
    """Check that `INSERT` that creates one partition with one large part and many small parts to MySQL is properly
    propagated to the replicated ClickHouse table."""
    name = f"tb_{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_names = define(
                "List of tables for test",
                create_tables(
                    table_name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            if table_name.endswith("_complex"):
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
                            block_size=100,
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

                    with Then(
                        "I check that MySQL tables and Clickhouse replication tables have the same data"
                    ):
                        complex_check_creation_and_select(
                            table_name=table_name,
                            statement="count(*)",
                            with_final=True,
                        )


@TestFeature
@Name("many partitions one part")
def many_partitions_one_part(self, node=None):
    """Check that `INSERT` of many partitions and one part to MySQL is properly propagated to the replicated ClickHouse
    table."""
    name = f"tb_{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_names = define(
                "List of tables for test",
                create_tables(
                    table_name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            if table_name.endswith("complex") or table_name.endswith("no_primary_key"):
                if table_name.endswith("_no_primary_key"):
                    xfail(
                        "doesn't work without primary key as only last row of insert is replicated"
                    )
                with Example(f"{table_name}", flags=TE):
                    with When(
                        "I perform insert in MySQL to create many partitions and one part in replicated "
                        "ClickHouse table"
                    ):
                        complex_insert(
                            node=self.context.cluster.node("mysql-master"),
                            table_name=table_name,
                            values=["({x},{y})", "({x},{y})"],
                            partitions=1000,
                            parts_per_partition=1,
                            block_size=1,
                        )

                    with Then(
                        "I check that MySQL tables and Clickhouse replication tables have the same data"
                    ):
                        complex_check_creation_and_select(
                            table_name=table_name,
                            statement="count(*)",
                            with_final=True,
                        )


@TestFeature
@Name("many partitions many parts")
def many_partitions_many_parts(self, node=None):
    """Check that `INSERT` of many partitions and many parts to MySQL is properly propagated to the replicated ClickHouse
    table."""
    name = f"tb_{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_names = define(
                "List of tables for test",
                create_tables(
                    table_name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            if table_name.endswith("complex"):
                with Example(f"{table_name}", flags=TE):
                    with When(
                        "I perform insert in MySQL to create many partitions and many parts in replicated ClickHouse table"
                    ):
                        complex_insert(
                            node=self.context.cluster.node("mysql-master"),
                            table_name=table_name,
                            values=["({x},{y})", "({x},{y})"],
                            partitions=10,
                            parts_per_partition=10,
                            block_size=10,
                        )

                    with Then(
                        "I check that MySQL tables and Clickhouse replication tables have the same data"
                    ):
                        complex_check_creation_and_select(
                            table_name=table_name,
                            statement="count(*)",
                            with_final=True,
                        )


@TestFeature
@Name("many partitions mixed parts")
def many_partitions_mixed_parts(self, node=None):
    """Check that `INSERT` with of many partitions, each with one large part and many small parts to MySQL is properly
    propagated to the replicated ClickHouse table."""
    name = f"tb_{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given("I create MySQL to ClickHouse replicated tables"):
            tables_names = define(
                "List of tables for test",
                create_tables(
                    table_name=name, clickhouse_table_engine=clickhouse_table_engine
                ),
            )

        for table_name in tables_names:
            if table_name.endswith("complex"):
                with Example(f"{table_name}", flags=TE):
                    with When(
                        "I perform insert in MySQL to create one large part in replicated ClickHouse table"
                    ):
                        complex_insert(
                            node=self.context.cluster.node("mysql-master"),
                            table_name=table_name,
                            values=["({x},{y})", "({x},{y})"],
                            partitions=2,
                            parts_per_partition=1,
                            block_size=100,
                        )

                    with Then(
                        "I perform insert in MySQL to create many small parts in replicated ClickHouse table"
                    ):
                        complex_insert(
                            node=self.context.cluster.node("mysql-master"),
                            table_name=table_name,
                            start_id=3,
                            values=["({x},{y})", "({x},{y})"],
                            partitions=10,
                            parts_per_partition=10,
                            block_size=100,
                        )

                    with Then(
                        "I check that MySQL tables and Clickhouse replication tables have the same data"
                    ):
                        complex_check_creation_and_select(
                            table_name=table_name,
                            statement="count(*)",
                            with_final=True,
                        )


@TestFeature
@Name("one million datapoints")
def one_million_datapoints(self, node=None):
    xfail("too big insert")
    """Check that `INSERT` of one million entries to MySQL is properly propagated to the replicated ClickHouse table."""
    name = f"tb_{getuid()}"

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
                xfail(
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
                        partitions=100000,
                        parts_per_partition=1,
                        block_size=1,
                    )

                with Then(
                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                ):
                    complex_check_creation_and_select(
                        table_name=table_name,
                        statement="count(*)",
                        with_final=True,
                    )


@TestFeature
@Name("parallel")
def parallel(self):
    """Check that after different `INSERT` queries in parallel MySQL and Clickhouse has the same data."""
    name = f"tb_{getuid()}"

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
                xfail(
                    "doesn't work without primary key as only last row of insert is replicated"
                )
            with Example(f"{table_name}", flags=TE):
                with When(
                    "I perform insert in MySQL to make parallel inserts in replicated ClickHouse table"
                ):
                    By(f"one raw insert", test=complex_insert, parallel=True)(
                        node=self.context.cluster.node("mysql-master"),
                        table_name=table_name,
                        values=["({x},{y})", "({x},{y})"],
                        partitions=1,
                        parts_per_partition=1,
                        block_size=1,
                        exitcode=False,
                    )
                    By(f"100 rows insert", test=complex_insert, parallel=True)(
                        node=self.context.cluster.node("mysql-master"),
                        table_name=table_name,
                        values=["({x},{y})", "({x},{y})"],
                        partitions=100,
                        parts_per_partition=1,
                        block_size=1,
                        exitcode=False,
                    )
                    By(f"1000 rows insert", test=complex_insert, parallel=True)(
                        node=self.context.cluster.node("mysql-master"),
                        table_name=table_name,
                        values=["({x},{y})", "({x},{y})"],
                        start_id=2,
                        partitions=1000,
                        parts_per_partition=1,
                        block_size=1,
                        exitcode=False,
                    )

                    join()

                with Then(
                    "I check that MySQL tables and Clickhouse replication tables have the same data"
                ):
                    complex_check_creation_and_select(
                        table_name=table_name,
                        statement="count(*)",
                        with_final=True,
                    )


@TestModule
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts("1.0"))
@Name("insert")
def module(self):
    """MySql to ClickHouse replication insert tests to test `INSERT` queries."""
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
