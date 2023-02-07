from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def check_datatype_replication(
    self,
    mysql_type,
    ch_type,
    values,
    ch_values,
    nullable,
    table_name,
    hex_type=False,
    auto_create_tables=True,
):
    """Check replication of a given MySQL data type."""
    table_name = table_name
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT AUTO_INCREMENT,"
            f"MyData {mysql_type}{' NOT NULL' if not nullable else ''},"
            f" PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )

    if not auto_create_tables:
        with And(f"I create ClickHouse replica test.{table_name}"):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
                f"(id Int32,{f'MyData Nullable({ch_type})' if nullable else f'MyData {ch_type}'}, _sign "
                f"Int8, _version UInt64) "
                f"ENGINE = ReplacingMergeTree(ver) "
                f"PRIMARY KEY id ORDER BY id SETTINGS "
                f"index_granularity = 8192;",
            )

    with When(f"I insert data in MySql table {table_name}"):
        for i, value in enumerate(values, 1):
            mysql.query(f"INSERT INTO {table_name} VALUES ({i}, {value})")
            with Then(f"I make check that ClickHouse table has same dataset"):
                retry(clickhouse.query, timeout=50, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )


@TestOutline(Scenario)
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
    """Check MySQL table recreation with the same name but different column data types."""
    xfail("")
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
        table_name="users1",
    )


@TestModule
@Name("schema changes")
def module(self):
    """Test some table schema changes."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()
        init_sink_connector(auto_create_tables=True)

    for scenario in loads(current_module(), Scenario):
        scenario()
