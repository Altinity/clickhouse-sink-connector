from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *


@TestScenario
def simple_scenario(self, node=None):
    """
    Just simple 'TRUNCATE' query check
    """
    if node is None:
        node = self.context.node
    table_name = f"test_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySWL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} "
            f"(id INT AUTO_INCREMENT,col1 int4, col2 int4 NOT NULL,"
            f" col3 int4 default 777, PRIMARY KEY (id)) "
            f"ENGINE = InnoDB;",
        )

    with And("I insert data in MySQL table"):
        mysql.query(f"INSERT INTO {table_name} (col1,col2,col3) VALUES (2,3,777)")

    with And("I check that clickhouse table received data"):
        retry(clickhouse.query, timeout=50, delay=2)(
            f"SELECT count() FROM test.{table_name}", message="1"
        )

    with When("I truncate MySQL table"):
        mysql.query(f"TRUNCATE TABLE {table_name}")

    with Then("I check that clickhouse table empty"):
        retry(clickhouse.query, timeout=50, delay=2)(
            f"SELECT count() FROM test.{table_name}", message="0"
        )


@TestFeature
@Name("truncate")
def feature(self):
    """'ALTER TRUNCATE' query tests."""
    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
