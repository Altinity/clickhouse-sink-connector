from integration.tests.steps import *


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames(
        "1.0"
    )
)
def virtual_column_names(
    self,
    mysql_type="DATETIME",
    nullable=False,
    auto_create_tables=True,
):
    """Check correctness of virtual column names."""
    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=auto_create_tables, topics=f"SERVER5432.test.{table_name}"
    )

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT AUTO_INCREMENT,"
            f"MyData {mysql_type}{' NOT NULL' if not nullable else ''},"
            f" PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )

    with When(f"I insert data in MySql table {table_name}"):
        mysql.query(f"INSERT INTO {table_name} VALUES (1, '2018-09-08 17:51:05.777')")

    with Then(f"I make check that ClickHouse table virtual column names are correct"):
        retry(clickhouse.query, timeout=50, delay=1)(
            f"SHOW CREATE TABLE test.{table_name}",
            message="`_sign` Int8,\\n    `_version` UInt64\\n",
        )


@TestFeature
@Name("virtual columns")
def feature(self):
    """Section to check behavior of virtual columns."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
