from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *


@TestOutline
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames(
        "1.0"
    )
)
def virtual_column_names(
    self,
    clickhouse_table_engine,
    version_column="_version",
    clickhouse_columns=None,
    mysql_columns=" MyData DATETIME",
):
    """Check correctness of virtual column names."""

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    table_name = f"virtual_columns_{getuid()}"

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
        )

    with Then(f"I make check that ClickHouse table virtual column names are correct"):
        if check_clickhouse_version("<23")(self):
            retry(clickhouse.query, timeout=50, delay=1)(
                f"SHOW CREATE TABLE test.{table_name}",
                message=f"`_sign` Int8,\\n    `{version_column}` UInt64",
            )
        else:
            retry(clickhouse.query, timeout=50, delay=1)(
                f"SHOW CREATE TABLE test.{table_name}",
                message=f"`{version_column}` UInt64,\\n    `is_deleted` UInt8\\n",
            )

    with And(f"I check that data is replicated"):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
def virtual_column_names_default(self):
    """Check correctness of default virtual column names."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        if self.context.env.endswith("auto"):
            with Example({clickhouse_table_engine}, flags=TE):
                virtual_column_names(clickhouse_table_engine=clickhouse_table_engine)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree_DifferentVersionColumnNames(
        "1.0"
    )
)
def virtual_column_names_replicated_random(self):
    """Check replication with some random version column name."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        if clickhouse_table_engine.startswith("Replicated"):
            with Example({clickhouse_table_engine}, flags=TE):
                virtual_column_names(
                    clickhouse_table_engine=clickhouse_table_engine,
                    clickhouse_columns=" MyData String",
                    version_column="some_version_column",
                )


@TestModule
@Name("virtual columns")
def module(self):
    """Section to check behavior of virtual columns."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
