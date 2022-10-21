
from requirements import *
from flow_tests.steps import *


@TestOutline
def check_datatype_replication(
        self,
        mysql_type,
        ch_type,
        values,
        ch_values,
        nullable=False,
        hex_type=False,
        auto_create_tables=True,
):
    """Check replication of a given MySQL data type."""
    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=auto_create_tables, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
                      f"(id INT AUTO_INCREMENT,"
                      f"MyData {mysql_type}{' NOT NULL' if not nullable else ''},"
                      f" PRIMARY KEY (id))"
                      f" ENGINE = InnoDB;",
        )
        pause()

    if not auto_create_tables:
        with And(f"I create ClickHouse replica test.{table_name}"):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
                          f"(id Int32,{f'MyData Nullable({ch_type})' if nullable else f'MyData {ch_type}'}, sign "
                          f"Int8, ver UInt64) "
                          f"ENGINE = ReplacingMergeTree(ver) "
                          f"PRIMARY KEY id ORDER BY id SETTINGS "
                          f"index_granularity = 8192;",
            )

    with When(f"I insert data in MySql table {table_name}"):
        for i, value in enumerate(values, 1):
            mysql.query(f"INSERT INTO {table_name} VALUES ({i}, {value})")
            with Then(f"I make check that ClickHouse table has same dataset"):
                retry(clickhouse.query, timeout=50, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FINAL FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values  nullable",
    [
        ("BIT(64)", "String", ["b'101'"], ['"0500000000000000"'], False),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime("1.0")
)
def bytes(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'DATE' and 'TIME' data type."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
        auto_create_tables=True,
    )

"ci2"
@TestFeature
@Name("manual section")
def feature(self):
    """MySql to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
