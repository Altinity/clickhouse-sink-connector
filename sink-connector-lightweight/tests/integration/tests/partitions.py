from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestScenario
def create_table_partitioned_by_range(self):
    """Check that the table partitioned by range created in mysql is correctly replicated on the ClickHouse side."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    table_name = f"tb_{getuid()}"

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Given(f"I create the table {table_name} partitioned by range"):
            create_mysql_to_clickhouse_replicated_table(
                name=f"{table_name}",
                mysql_columns="order_id INT AUTO_INCREMENT, order_date DATE, total_amount DECIMAL(10, 2)",
                primary_key="order_id, order_date",
                clickhouse_table_engine=clickhouse_table_engine,
                partition_by_mysql="RANGE (YEAR(order_date)) (PARTITION p1 VALUES LESS THAN (2020))",
            )

            with And("I check that the table was replicated"):
                for retry in retries(timeout=40):
                    with retry:
                        clickhouse_node.query(
                            f"SELECT * FROM test.{table_name} ORDER BY tuple(*)"
                        )


@TestModule
@Name("partitions")
def module(self, clickhouse_node="clickhouse", mysql_node="mysql-master"):
    """Check tables with PARTITION BY for MySql to ClickHouse replication."""
    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Scenario):
                Scenario(test=feature, parallel=True, executor=executor)()
        finally:
            join()
