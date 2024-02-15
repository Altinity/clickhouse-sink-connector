from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime,
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes,
)
from integration.tests.steps.alter import drop_column
from integration.tests.steps.common import generate_sample_mysql_value
from integration.tests.steps.service_settings_steps import *
from integration.tests.steps.sql import *
from integration.tests.steps.statements import all_mysql_datatypes_dict


@TestStep(Given)
def create_table_with_datetime_column(self, table_name, data, precision):
    """Create MySQL table that contains the datetime column."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node

    with By(f"creating a {table_name} table with datetime column"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"\`{table_name}\`",
            mysql_columns=f"date DATETIME({precision})",
            clickhouse_table_engine=self.context.clickhouse_table_engines[0],
        )

    with And(f"inserting data to MySQL {table_name} table"):
        mysql_node.query(f"INSERT INTO {table_name} VALUES (1, '{data}');")


@TestCheck
def check_datetime_column(self, precision, data):
    table_name = "table_" + getuid()
    clickhouse_node = self.context.clickhouse_node

    with Given(
        "I create a table with datetime column",
        description=f"""
    precision: {precision},
    values: {data}
    """,
    ):
        create_table_with_datetime_column(
            table_name=table_name, precision=precision, data=data
        )

    with Then(f"I check that the data is replicated to ClickHouse and is not lost"):
        for retry in retries(timeout=30):
            with retry:
                if data == "1000-01-01 00:00:00" and data == "1900-01-01 00:00:00":
                    clickhouse_values = clickhouse_node.query(
                        f"SELECT date FROM {self.context.database}.{table_name} FORMAT CSV"
                    )
                    assert clickhouse_values.output.strip() == "1900-01-01", error()
                elif data == "9999-12-31 23:59:59" and data == "2299-12-31 23:59:59":
                    clickhouse_values = clickhouse_node.query(
                        f"SELECT date FROM {self.context.database}.{table_name} FORMAT CSV"
                    )
                    assert (
                        clickhouse_values.output.strip() == "2299-12-31 23:59:59"
                    ), error()
                else:
                    clickhouse_values = clickhouse_node.query(
                        f"SELECT date FROM {self.context.database}.{table_name} FORMAT CSV"
                    )
                    assert clickhouse_values.output.strip() == data, error()


@TestSketch(Scenario)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime("1.0")
)
@Flags(TE)
def datetime(self):
    """Validate that the table in MySQL is replicated to ClickHouse when it contains datetime columns with different
    value and precision combinations."""
    precision_values = ["0", "1", "2", "3", "4", "5", "6"]
    data = [
        "1000-01-01 00:00:00",
        "9999-12-31 23:59:59",
        "1900-01-01 00:00:00",
        "2299-12-31 23:59:59",
        "NOW()",
        "2024-02-29 00:00:00",
        "2023-02-28 23:59:59",
    ]

    check_datetime_column(
        precision=either(*precision_values, i="precision values"),
        data=either(*data, i="data values"),
    )


@TestModule
@Name("datatypes")
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes("1.0"))
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that the table is replicated with all MySQL DataTypes.

    Combinations used are related to:

    - DATETIME precision values in range of 1-6
    - Different DATETIME values like:
        - min/max MySQL values,
        - min/max ClickHouse DateTime64 values,
        - NOW() function,
        - Leap year like 2024
        - Non leap year like 2023
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)
    self.context.database = "test"

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)
