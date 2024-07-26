from integration.tests.steps.service_configurations import *
from integration.tests.steps.sql import *
from integration.tests.steps.statements import *


@TestOutline
def check_datatype_replication(
    self,
    mysql_type,
    ch_type,
    values,
    ch_values,
    nullable,
    table_name,
    clickhouse_table,
    hex_type=False,
):
    """Check replication of a given MySQL data type."""
    table_name = table_name
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    mysql_columns = f"MyData {mysql_type}{' NOT NULL' if not nullable else ''}"
    clickhouse_columns = (
        f"{f'MyData Nullable({ch_type})' if nullable else f'MyData {ch_type}'}"
    )

    with Given(f"I create MySql to CH replicated table", description=table_name):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
            clickhouse_columns=clickhouse_columns,
            clickhouse_table=clickhouse_table,
        )

    with When(f"I insert data in MySql table {table_name}"):
        for i, value in enumerate(values, 1):
            mysql.query(f"INSERT INTO {table_name} VALUES ({i}, {value})")
            with Then(f"I make check that ClickHouse table has same dataset"):
                retry(clickhouse.query, timeout=5, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("TEXT", "String", ["'some_text'"], ['"some_text"'], False),
        (
            "INT",
            "Int32",
            ["-2147483648", "0", "2147483647"],
            ["-2147483648", "0", "2147483647"],
            False,
        ),
        ("INT UNSIGNED", "UInt32", ["0", "4294967295"], ["0", "4294967295"], False),
        ("DECIMAL(30, 10)", "DECIMAL(30, 10)", ["1.232323233"], ["1.232323233"], False),
    ],
)
def table_recreation_with_different_datatypes(
    self, mysql_type, ch_type, values, ch_values, nullable
):
    """Check MySQL to CH replicated table auto recreation with the same name but different column data types."""

    for clickhouse_table in available_clickhouse_tables:
        if clickhouse_table[0] == "auto":
            with Example({clickhouse_table}, flags=TE):
                check_datatype_replication(
                    mysql_type=mysql_type,
                    ch_type=ch_type,
                    values=values,
                    ch_values=ch_values,
                    nullable=nullable,
                    clickhouse_table=clickhouse_table,
                    table_name="users1",
                )


@TestModule
@Name("schema changes")
def module(self):
    """Test some table schema changes."""
    xfail("doesn't work, breaks sink and all tests")

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()
        init_sink_connector()

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
