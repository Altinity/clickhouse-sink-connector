from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_configurations import *


@TestOutline
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames(
        "1.0"
    )
)
def virtual_column_names(
    self,
    clickhouse_table,
    version_column="_version",
    clickhouse_columns=None,
    mysql_columns=" MyData DATETIME",
):
    """Check correctness of virtual column names."""

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    table_name = f"virtual_columns_{getuid()}"

    init_sink_connector(
        auto_create_tables=clickhouse_table[0], topics=f"SERVER5432.test.{table_name}"
    )

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_to_clickhouse_replicated_table(
            version_column=version_column,
            name=table_name,
            clickhouse_columns=clickhouse_columns,
            mysql_columns=mysql_columns,
            clickhouse_table=clickhouse_table,
        )

    with When(f"I insert data in MySql table {table_name}"):
        mysql.query(f"INSERT INTO {table_name} VALUES (1, '2018-09-08 17:51:05.777')")

    with Then(f"I make check that ClickHouse table virtual column names are correct"):
        retry(clickhouse.query, timeout=50, delay=1)(
            f"SHOW CREATE TABLE test.{table_name}",
            message=f"`_sign` Int8,\\n    `{version_column}` UInt64\\n",
        )

    with And(f"I check that data is replicated"):
        complex_check_creation_and_select(
            table_name=table_name,
            clickhouse_table=clickhouse_table,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
def virtual_column_names_default(self):
    """Check correctness of default virtual column names."""
    for clickhouse_table in available_clickhouse_tables:
        if clickhouse_table[0] == "auto":
            with Example({clickhouse_table}, flags=TE):
                virtual_column_names(clickhouse_table=clickhouse_table)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree_DifferentVersionColumnNames(
        "1.0"
    )
)
def virtual_column_names_replicated_random(self):
    """Check replication with some random version column name."""
    for clickhouse_table in available_clickhouse_tables:
        if clickhouse_table[1].startswith("Replicated"):
            with Example({clickhouse_table}, flags=TE):
                virtual_column_names(
                    clickhouse_table=clickhouse_table,
                    clickhouse_columns=" MyData String",
                    version_column="some_version_column",
                )


@TestModule
@Name("virtual columns")
def module(self):
    """Section to check behavior of virtual columns."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
