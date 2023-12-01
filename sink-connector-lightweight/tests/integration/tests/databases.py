from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *
from integration.tests.steps.steps_global import *


@TestOutline
@Requirements()
def databases_tables(
    self,
    clickhouse_table_engine,
    version_column="_version",
    clickhouse_columns=None,
    mysql_columns=" MyData DATETIME",
):
    """Check correctness of virtual column names."""

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    table_name = f"databases_{getuid()}"

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_to_clickhouse_replicated_table(
            version_column=version_column,
            name=table_name,
            clickhouse_columns=clickhouse_columns,
            mysql_columns=mysql_columns,
            clickhouse_table_engine=clickhouse_table_engine,
        )

    with And(
        f"I create another database in Clickhouse and table with the same name {table_name} in it"
    ):
        clickhouse_node = self.context.cluster.node("clickhouse")

        create_database(name="test2")

        clickhouse_node.query(
            f"CREATE TABLE IF NOT EXISTS test2.{table_name} "
            f"(id Int32, MyData Nullable(DateTime64(3)), _sign "
            f"Int8, {version_column} UInt64) "
            f"ENGINE = ReplacingMergeTree({version_column}) "
            f" PRIMARY KEY (id ) ORDER BY (id)"
            f" SETTINGS "
            f"index_granularity = 8192;"
        )

    with When(f"I insert data in MySql table {table_name}"):
        mysql.query(f"INSERT INTO {table_name} VALUES (1, '2018-09-08 17:51:05.777')")
        mysql.query(f"INSERT INTO {table_name} VALUES (2, '2018-09-08 17:51:05.777')")
        mysql.query(f"INSERT INTO {table_name} VALUES (3, '2018-09-08 17:51:05.777')")

    with Then(f"I check that data is replicated to the correct table"):
        complex_check_creation_and_select(
            table_name=table_name,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
def tables_in_different_databases(self):
    """Check correctness replication when we have two tables with the same name in different databases."""
    # xfail("https://github.com/Altinity/clickhouse-sink-connector/issues/247")

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        if self.context.env.endswith("auto"):
            with Example({clickhouse_table_engine}, flags=TE):
                databases_tables(clickhouse_table_engine=clickhouse_table_engine)


@TestModule
@Name("databases")
def module(self):
    """Section to check behavior replication in different databases."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
