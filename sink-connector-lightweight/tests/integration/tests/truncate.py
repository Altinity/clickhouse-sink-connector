from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *


@TestOutline
def truncate(
    self,
    mysql_columns,
    clickhouse_columns,
    clickhouse_table_engine,
    primary_key,
    engine,
):
    """
    Just simple 'TRUNCATE' query check
    """
    table_name = f"truncate_{getuid()}"
    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
            primary_key=primary_key,
        )

    with When(f"I insert data in MySQL table"):
        mysql.query(f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');")

    with Then("I check that clickhouse table received data"):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="count(*)",
            with_final=True,
        )

    with And("I truncate MySQL table"):
        mysql.query(f"TRUNCATE TABLE {table_name}")

    with And("I check that clickhouse table empty"):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
def no_primary_key(self):
    """Check for `DELETE` with no primary key without InnoDB engine."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            truncate(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key=None,
                engine=False,
            )


@TestFeature
def no_primary_key_innodb(self):
    """Check for `DELETE` with no primary key with InnoDB engine."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            truncate(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key=None,
                engine=True,
            )


@TestFeature
def simple_primary_key(self):
    """Check for `DELETE` with simple primary key without InnoDB engine."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            truncate(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=False,
            )


@TestFeature
def simple_primary_key_innodb(self):
    """Check for `DELETE` with simple primary key with InnoDB engine."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            truncate(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=True,
            )


@TestFeature
def complex_primary_key(self):
    """Check for `DELETE` with complex primary key without engine InnoDB."""
    xfail("complex keys need to be fixed")
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            truncate(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id,k",
                engine=True,
            )


@TestFeature
def complex_primary_key_innodb(self):
    """Check for `DELETE` with complex primary key with engine InnoDB."""
    xfail("complex keys need to be fixed")
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            truncate(
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id,k",
                engine=False,
            )


@TestModule
@Name("truncate")
def module(self):
    """'ALTER TRUNCATE' query tests."""

    for feature in loads(current_module(), Feature):
        Feature(test=feature)()
