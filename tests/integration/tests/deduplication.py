from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def deduplication(self, inserts=False, big_insert=False, insert_number=1000):
    """Check MySQL to Clickhouse connection for non-duplication data"""

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
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT AUTO_INCREMENT,age INT, PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )

    with And(
        f"I create ClickHouse replica test.{table_name} to MySQL table with auto remove in the end of the test"
    ):
        create_clickhouse_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
            f"(id Int32, age Int32) "
            f"ENGINE = ReplacingMergeTree "
            f"PRIMARY KEY id ORDER BY id SETTINGS "
            f"index_granularity = 8192;",
        )

    if inserts:
        with When(f"I insert {insert_number} rows of data in MySql table"):
            for i in range(0, insert_number + 1):
                mysql.query(f"insert into {table_name} values ({i},777)")
            metric(name="map insert time", value=current_time(), units="sec")
    elif big_insert:
        with When(f"I make one insert on {insert_number} rows data in MySql table"):
            mysql.query(
                f"insert into {table_name} "
                f"values {','.join([f'({i},777)' for i in range(1, insert_number + 1)])}"
            )

    with Then(f"I wait unique values from CLickHouse table equal to MySQL table"):
        select(
            insert=insert_number,
            table_name=table_name,
            statement="count()",
            with_final=True,
            timeout=50,
        )


@TestScenario
def deduplication_on_big_insert(self):
    """Check MySQL to Clickhouse connection for non-duplication data on 10 000 inserts."""
    deduplication(big_insert=True, insert_number=100000)


@TestScenario
def deduplication_on_many_inserts(self):
    """Check MySQL to Clickhouse connection for non-duplication data on big inserts."""
    deduplication(inserts=True, insert_number=10000)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication("1.0")
)
@Name("deduplication")
def feature(self):
    """MySql to ClickHouse replication tests to check
    for non-duplication data on big inserts."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
