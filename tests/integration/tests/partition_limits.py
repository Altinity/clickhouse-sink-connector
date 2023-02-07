from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def partition_limits(
    self, input, max_insert_block_size, partitions, parts_per_partition, block_size
):
    """Checking different types of insert"""
    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} (id INT AUTO_INCREMENT,col1 int4, col2 int4 NOT NULL,"
            f" col3 int4 default 777, PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )

    with When(
        "I insert data in MySql table wtih more than 100 partitions per insert block"
    ):
        clickhouse.query(f"SET max_insert_block_size={max_insert_block_size};")
        complex_insert(
            node=mysql,
            table_name=table_name,
            values=input,
            partitions=partitions,
            parts_per_partition=parts_per_partition,
            block_size=block_size,
        )

    with Then("I wait unique values from CLickHouse table equal to MySQL table"):
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")
                mysql_count = mysql.query(
                    f"SELECT count(*) FROM {table_name}"
                ).output.strip()[90:]

                retry(clickhouse.query, timeout=50, delay=1,)(
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
    partition_limits(
        input=["({x},{y},DEFAULT)", "({x},{y},DEFAULT)"],
        max_insert_block_size=1,
        partitions=10001,
        parts_per_partition=1,
        block_size=1,
    )


@TestModule
@Requirements()
@Name("partition limits")
def module(self):
    """Tests for cases when the partitioning limit is exceeded."""
    xfail("")
    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
