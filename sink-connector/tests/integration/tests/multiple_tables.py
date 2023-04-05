from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def multiple_table_auto_creation(
    self, number_of_tables, mysql_columns, clickhouse_table, clickhouse_columns=None
):
    """
    Multiple tables auto creation
    """
    mysql = self.context.cluster.node("mysql-master")
    clickhouse = self.context.cluster.node("clickhouse")
    clickhouse1 = self.context.cluster.node("clickhouse1")

    with Given("I create unique topics"):
        table_name = f"{','.join([f'SERVER5432.test.users{i}' for i in range(1, number_of_tables + 1)])}"

    init_sink_connector(auto_create_tables=clickhouse_table[0], topics=table_name)

    for i in range(number_of_tables):
        table_name = f"users{i}"
        with Given(f"I create MySQL table {table_name}"):
            create_mysql_to_clickhouse_replicated_table(
                name=table_name,
                mysql_columns=mysql_columns,
                clickhouse_table=clickhouse_table,
                clickhouse_columns=clickhouse_columns,
            )

        with When(f"I insert data in MySql table"):
            mysql.query(f"insert into {table_name} values (1,777)")

        with Then("I count created tables"):
            retry(clickhouse.query, timeout=50, delay=1)(
                "SELECT count() FROM system.tables WHERE name ilike 'users%'",
                message=f"{i+1}",
            )
            if clickhouse_table[1].startswith("Replicated"):
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
        for clickhouse_table in available_clickhouse_tables:
            if clickhouse_table[0] == "auto":
                with Example({clickhouse_table}, flags=TE):
                    multiple_table_auto_creation(
                        number_of_tables=100,
                        mysql_columns=mysql_columns,
                        clickhouse_table=clickhouse_table,
                    )

    else:
        for clickhouse_table in available_clickhouse_tables:
            if clickhouse_table[0] == "auto":
                with Example({clickhouse_table}, flags=TE):
                    multiple_table_auto_creation(
                        number_of_tables=10,
                        mysql_columns=mysql_columns,
                        clickhouse_table=clickhouse_table,
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

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
