from integration.tests.steps import *


@TestOutline
def mysql_to_clickhouse_connection(self, auto_create_tables):
    """Basic check MySQL to Clickhouse connection by small and simple data insert."""

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

    if not auto_create_tables:
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

    with When(f"I insert data in MySql table"):
        mysql.query(
            f"insert into {table_name} values (1,777),(2,777),(3,777),(4,777),(5,777),(6,777),(7,777),"
            f"(8,777),(9,777)"
        )

    if auto_create_tables:
        with And("I check table creation"):
            retry(clickhouse.query, timeout=30, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )

    with And(f"I check that ClickHouse table has same number of rows as MySQL table"):
        select(
            insert="8",
            table_name=table_name,
            statement="count()",
            with_final=True,
            timeout=50,
        )


@TestScenario
def mysql_to_clickhouse_auto(self, auto_create_tables=True):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with auto table creation."""
    mysql_to_clickhouse_connection(auto_create_tables=auto_create_tables)
    # with Given("I collect Sink logs"):
    #     with Shell() as bash:
    #         cmd = bash("docker-compose logs sink > sink.log")


@TestScenario
def mysql_to_clickhouse_manual(self, auto_create_tables=False):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with manual table creation."""
    mysql_to_clickhouse_connection(auto_create_tables=auto_create_tables)


@TestFeature
@Name("sanity")
def feature(self):
    """MySql to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
