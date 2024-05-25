from integration.tests.steps.mysql import *
from integration.tests.steps.clickhouse import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *


@TestOutline
def mysql_to_clickhouse_insert(
    self, input, output, mysql_columns, clickhouse_table_engine, clickhouse_columns=None
):
    """Manual creation table section"""

    table_name = f"columns_inconsistency_{getuid()}"
    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
        )

    with When("I insert data in MySQL table"):
        mysql.query(f"INSERT INTO {table_name} (col1,col2,col3) VALUES {input};")
        time.sleep(20)

    with Then("I check data inserted correct"):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            manual_output=output,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
def more_columns(
    self,
    input="(2,7,777)",
    output="0",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col1 Int32, col2 Int32, col3 Int32, col4 Int32",
):
    """Check when manual created table has more columns than MySQL table."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        if self.context.env.endswith("manual"):
            with Example({clickhouse_table_engine}, flags=TE):
                mysql_to_clickhouse_insert(
                    input=input,
                    output=output,
                    mysql_columns=mysql_columns,
                    clickhouse_columns=clickhouse_columns,
                    clickhouse_table_engine=clickhouse_table_engine,
                )


@TestFeature
def less_columns(
    self,
    input="(2,7,777)",
    output="1",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col1 Int32, col2 Int32",
):
    """Check when manual created table has fewer columns than MySQL table."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        if self.context.env.endswith("manual"):
            with Example({clickhouse_table_engine}, flags=TE):
                mysql_to_clickhouse_insert(
                    input=input,
                    output=output,
                    mysql_columns=mysql_columns,
                    clickhouse_columns=clickhouse_columns,
                    clickhouse_table_engine=clickhouse_table_engine,
                )


@TestFeature
def equal_columns_different_names(
    self,
    input="(2,7,777)",
    output="0",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col11 Int32, col22 Int32, col33 Int32",
):
    """Check when manual created table has different named columns than MySQL table."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        if self.context.env.endswith("manual"):
            with Example({clickhouse_table_engine}, flags=TE):
                mysql_to_clickhouse_insert(
                    input=input,
                    output=output,
                    mysql_columns=mysql_columns,
                    clickhouse_columns=clickhouse_columns,
                    clickhouse_table_engine=clickhouse_table_engine,
                )


@TestFeature
def equal_columns_some_different_names(
    self,
    input="(2,7,777)",
    output="0",
    mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
    clickhouse_columns="col1 Int32, col22 Int32, col33 Int32",
):
    """Check when manual created table has some different named columns than MySQL table."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        if self.context.env.endswith("manual"):
            with Example({clickhouse_table_engine}, flags=TE):
                mysql_to_clickhouse_insert(
                    input=input,
                    output=output,
                    mysql_columns=mysql_columns,
                    clickhouse_columns=clickhouse_columns,
                    clickhouse_table_engine=clickhouse_table_engine,
                )


@TestModule
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnsInconsistency("1.0")
)
@Name("columns inconsistency")
def module(self):
    """Check for different columns' inconsistency."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
