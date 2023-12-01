from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def partition_limits(
    self,
    input,
    max_insert_block_size,
    partitions,
    parts_per_partition,
    block_size,
    mysql_columns,
    clickhouse_table_engine,
    clickhouse_columns=None,
):
    """Creating table and append it with partition limits setting"""
    table_name = f"partition_limits_{getuid()}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
            clickhouse_table_engine=clickhouse_table_engine,
            clickhouse_columns=clickhouse_columns,
            partition_by="id",
        )

    with When(
        "I insert data in MySql table wtih more than 100 partitions per insert block"
    ):
        complex_insert(
            node=mysql,
            table_name=table_name,
            values=input,
            partitions=partitions,
            parts_per_partition=parts_per_partition,
            block_size=block_size,
            max_insert_block_size=max_insert_block_size,
        )

    with Then("I wait unique values from CLickHouse table equal to MySQL table"):
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")
                mysql_count = mysql.query(
                    f"SELECT count(*) FROM {table_name}"
                ).output.strip()[90:]

                retry(
                    clickhouse.query,
                    timeout=50,
                    delay=1,
                )(
                    f"SELECT count() FROM test.{table_name}  FINAL where _sign !=-1  FORMAT CSV",
                    message=mysql_count,
                )


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts_PartitionLimits(
        "1.0"
    )
)
def exceed_partition_limit(self):
    """Test to check partition correct insert of data with partition limits option."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            partition_limits(
                input=["({x},{y},DEFAULT)", "({x},{y},DEFAULT)"],
                clickhouse_table_engine=clickhouse_table_engine,
                max_insert_block_size=1,
                partitions=10001,
                parts_per_partition=1,
                block_size=1,
                mysql_columns="col1 INT, col2 INT NOT NULL, col3 INT default 777",
                clickhouse_columns="col1 Int32, col2 Int32, col3 Int32",
            )


@TestModule
@Requirements()
@Name("partition limits")
def module(self):
    """Tests for cases when the partitioning limit is exceeded."""
    xfail("")

    for scenario in loads(current_module(), Scenario):
        scenario()
