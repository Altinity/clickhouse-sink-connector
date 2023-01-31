from integration.tests.steps import *


@TestOutline
def mysql_to_clickhouse_connection(
    self, mysql_type, ch_type, nullable, replicated, auto_create_tables
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert."""

    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    clickhouse1 = self.context.cluster.node("clickhouse1")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create tables for current test"):
        create_tables(
            table_name=table_name,
            mysql_type=mysql_type,
            ch_type=ch_type,
            nullable=nullable,
            replicated=replicated,
            auto_create_tables=auto_create_tables,
        )

    with When(f"I insert data in MySql table"):
        mysql.query(
            f"INSERT INTO {table_name} VALUES (1,777),(2,777),(3,777),(4,777),(5,777),(6,777),(7,777),"
            f"(8,777),(9,777)"
        )

    if auto_create_tables:
        if replicated:
            with And("I check table creation on all nodes"):
                retry(clickhouse.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )
                retry(clickhouse1.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )
        else:
            with And("I check table creation"):
                retry(clickhouse.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )

    with And(f"I check that ClickHouse table has same number of rows as MySQL table"):
        select(
            table_name=table_name,
            statement="count(*)",
            with_final=True,
            timeout=50,
        )
        if replicated:
            select(
                table_name=table_name,
                statement="count(*)",
                node=self.context.cluster.node("clickhouse1"),
                with_final=True,
                timeout=50,
            )


@TestScenario
def mysql_to_clickhouse_auto(
    self,
    mysql_type="INT",
    ch_type="Int32",
    nullable=True,
    replicated=False,
    auto_create_tables=True,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with auto table creation."""
    mysql_to_clickhouse_connection(
        mysql_type=mysql_type,
        ch_type=ch_type,
        nullable=nullable,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def mysql_to_clickhouse_manual(
    self,
    mysql_type="INT",
    ch_type="Int32",
    nullable=True,
    replicated=False,
    auto_create_tables=False,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with manual table creation."""
    mysql_to_clickhouse_connection(
        mysql_type=mysql_type,
        ch_type=ch_type,
        nullable=nullable,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def mysql_to_clickhouse_replicated(
    self,
    mysql_type="INT",
    ch_type="Int32",
    nullable=True,
    replicated=True,
    auto_create_tables=False,
):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with manual replicated table creation."""
    mysql_to_clickhouse_connection(
        mysql_type=mysql_type,
        ch_type=ch_type,
        nullable=nullable,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestFeature
@Name("sanity")
def feature(self):
    """MySql to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
