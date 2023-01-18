from integration.tests.steps import *


@TestOutline
def partition_limits(self, input):
    """"""
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

    with When("I insert data in MySql table wtih more than 100 partitions per insert block"):
        # mysql.query(f"INSERT INTO {table_name} (col1,col2,col3) VALUES {input};")
        complex_insert(node=mysql, table_name=table_name, values=input,
                       range_value=3)
        pause()

    with Then("I check data inserted correct"):
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")
                clickhouse.query(
                    f"SELECT col1,col2,col3 FROM test.{table_name} FINAL FORMAT CSV"
                )


@TestScenario
def exceed_partition_limit(self):
    """Test to check partition correct insert of data with partition limits option."""
    partition_limits(
        input=["({x},{y},DEFAULT)", "({x},{y},DEFAULT)"]
    )


@TestFeature
@Requirements()
@Name("partition limits")
def feature(self):
    """Tests for cases when the partitioning limit is exceeded."""
    xfail("doesn't ready")

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
