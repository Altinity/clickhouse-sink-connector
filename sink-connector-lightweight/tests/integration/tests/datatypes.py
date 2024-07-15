from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime,
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes,
)
from integration.tests.steps.service_settings import *
from integration.tests.steps.mysql import *


def adjust_precision(datetime_str, precision):
    """A function to transform given DATETIME values from MySQL to ClickHouse with given precision."""
    if datetime_str == "NOW()":
        return datetime_str

    parts = datetime_str.split(".")
    main_part = parts[0]

    if precision == "0":
        return main_part

    if len(parts) == 1:
        return f"{main_part}.{'0' * int(precision)}"

    microseconds_part = parts[1]
    adjusted_microseconds = microseconds_part[: int(precision)].ljust(
        int(precision), "0"
    )

    return f"{main_part}.{adjusted_microseconds}"


@TestStep(Given)
def create_table_with_datetime_column(self, table_name, data, precision):
    """Create MySQL table that contains the datetime column."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node

    with By(f"creating a {table_name} table with datetime column"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"date DATETIME({precision})",
        )

    with And(f"inserting data to MySQL {table_name} table"):
        if data != "NOW()":
            mysql_node.query(f"INSERT INTO {table_name} VALUES (1, '{data}');")
        else:
            mysql_node.query(f"INSERT INTO {table_name} VALUES (1, {data});")


@TestCheck
def check_datetime_column(self, precision, data):
    table_name = "table_" + getuid()
    clickhouse_node = self.context.clickhouse_node

    data = adjust_precision(datetime_str=data, precision=precision)

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
                clickhouse_values = clickhouse_node.query(
                    f"SELECT count(date) FROM {self.context.database}.{table_name} FORMAT CSV"
                )

                assert clickhouse_values.output.strip() != "0", error()

        clickhouse_values = clickhouse_node.query(
            f"SELECT date FROM {self.context.database}.{table_name} FORMAT CSV"
        )

        if data == "NOW()":
            clickhouse_values = clickhouse_node.query(
                f"SELECT count(date) FROM {self.context.database}.{table_name} FORMAT CSV"
            )
            assert clickhouse_values.output.strip() != "0", error()

        elif data[:19] == "1000-01-01 00:00:00" or data[:19] == "1900-01-01 00:00:00":
            assert clickhouse_values.output.strip().replace(
                '"', ""
            ) == adjust_precision(
                datetime_str="1900-01-01 00:00:00", precision=precision
            ), error()
        elif data[:19] == "9999-12-31 23:59:59":
            if precision != "0":
                assert (
                    clickhouse_values.output.strip().replace('"', "")
                    == f"2299-12-31 23:59:59.{'9'*int(precision)}"
                ), error()
            else:
                assert (
                    clickhouse_values.output.strip().replace('"', "")
                    == "2299-12-31 23:59:59"
                ), error()
        elif data[:19] == "2299-12-31 23:59:59.9":
            if precision != "0":
                assert (
                    clickhouse_values.output.strip().replace('"', "")
                    == f"2299-12-31 23:59:59.{'9'*int(precision)}"
                ), error()
            else:
                assert (
                    clickhouse_values.output.strip().replace('"', "")
                    == "2299-12-31 23:59:59"
                ), error()
        else:
            assert clickhouse_values.output.strip().replace('"', "") == data, error()


@TestSketch(Scenario)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime("1.0")
)
@Flags(TE)
def datetime(self):
    """Validate that the table in MySQL is replicated to ClickHouse when it contains datetime columns with different
    value and precision combinations.

    Combinations used are related to:

    - DATETIME precision values in range of 1-6
    - Different DATETIME values like:
        - min/max MySQL values,
        - min/max ClickHouse DateTime64 values,
        - NOW() function,
        - Leap year like 2024
        - Non leap year like 2023
    """
    precision_values = ["0", "1", "2", "3", "4", "5", "6"]
    data = [
        "1000-01-01 00:00:00",
        "1000-01-01 00:00:00.000000",
        "9999-12-31 23:59:59",
        "9999-12-31 23:59:59.999999",
        "1900-01-01 00:00:00",
        "1900-01-01 00:00:00.000000",
        "2299-12-31 23:59:59",
        "2299-12-31 23:59:59.999999",
        "NOW()",
        "2024-02-29 00:00:00",
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
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)
    self.context.database = "test"

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)
