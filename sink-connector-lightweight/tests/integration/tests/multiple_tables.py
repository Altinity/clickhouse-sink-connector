from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *


@TestOutline
def multiple_table_auto_creation(
    self,
    number_of_tables,
    mysql_columns,
    clickhouse_table_engine,
    clickhouse_columns=None,
):
    """
    Multiple tables auto creation
    """
    mysql = self.context.cluster.node("mysql-master")
    clickhouse = self.context.cluster.node("clickhouse")
    clickhouse1 = self.context.cluster.node("clickhouse1")

    for i in range(number_of_tables):
        table_name = f"users{i}"
        with Given(f"I create MySQL table {table_name}"):
            create_mysql_table(
                table_name=table_name,
                olumns=mysql_columns,
            )

        with When(f"I insert data in MySQL table"):
            mysql.query(f"insert into {table_name} values (1,777)")

        with Then("I count created tables"):
            retry(clickhouse.query, timeout=50, delay=1)(
                "SELECT count() FROM system.tables WHERE name ilike 'users%'",
                message=f"{i+1}",
            )
            if clickhouse_table_engine.startswith("Replicated"):
                retry(clickhouse1.query, timeout=50, delay=1)(
                    "SELECT count() FROM system.tables WHERE name ilike 'users%'",
                    message=f"{i + 1}",
                )


@TestFeature
def tables_100(
    self,
    mysql_columns=" MyData INT",
):
    """
    Creation of 10 tables (if --stress enabled 100 tables creation).
    """
    if self.context.cluster.stress:
        for clickhouse_table_engine in self.context.clickhouse_table_engines:
            if self.context.env.endswith("auto"):
                with Example({clickhouse_table_engine}, flags=TE):
                    multiple_table_auto_creation(
                        number_of_tables=100,
                        mysql_columns=mysql_columns,
                        clickhouse_table_engine=clickhouse_table_engine,
                    )

    else:
        for clickhouse_table_engine in self.context.clickhouse_table_engines:
            if self.context.env.endswith("auto"):
                with Example({clickhouse_table_engine}, flags=TE):
                    multiple_table_auto_creation(
                        number_of_tables=10,
                        mysql_columns=mysql_columns,
                        clickhouse_table_engine=clickhouse_table_engine,
                    )


@TestModule
@Name("multiple tables")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_MultipleAutoCreate(
        "1.0"
    )
)
def module(self):
    """
    Multiple tables creation.
    """

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
