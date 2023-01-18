from integration.tests.steps import *


@TestOutline
def check_datatype_replication_on_cluster(
    self,
    mysql_type,
    ch_type,
    values,
    ch_values,
    nullable=False,
    hex_type=False,
    auto_create_tables=False,
):
    """Check replication of a given MySQL data type on cluster."""
    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    clickhouse1 = self.context.cluster.node("clickhouse1")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=auto_create_tables, topics=f"SERVER5432.test.{table_name}"
    )

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
        with And(f"I create ClickHouse replica test.{table_name} on cluster with 2 shards and 2 replicas"):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} ON CLUSTER sharded_replicated_cluster"
                f"(id Int32,{f'MyData Nullable({ch_type})' if nullable else f'MyData {ch_type}'}, _sign "
                f"Int8, _version UInt64) "
                f"ENGINE = ReplicatedReplacingMergeTree("
                          "'/clickhouse/tables/{shard}"
                          f"/{table_name}',"
                          " '{replica}', _version) "
                f"PRIMARY KEY id ORDER BY id SETTINGS "
                f"index_granularity = 8192;",
            )

    with When(f"I insert data in MySql table {table_name}"):
        for i, value in enumerate(values, 1):
            mysql.query(f"INSERT INTO {table_name} VALUES ({i}, {value})")
            with Then(f"I make check that ClickHouse table has same dataset on both replicas of the first shard"):
                retry(clickhouse.query, timeout=50, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FINAL FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )
                retry(clickhouse1.query, timeout=50, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FINAL FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )

    with Then("I drop clickhouse cluster table"):
        self.context.cluster.node("clickhouse").query(
            f"DROP TABLE IF EXISTS test.{table_name} ON CLUSTER sharded_replicated_cluster;"
        )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("BINARY", "String", ["'a'"], ['"a"'], False),
        ("VARBINARY(4)", "String", ["'IVAN'"], ['"IVAN"'], False),
    ],
)
def binary(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'BINARY' data types for
    ReplicatedReplacingMergeTree engine."""
    check_datatype_replication_on_cluster(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
        hex_type=True,
    )


@TestFeature
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree("1.0"))
@Name("replicated engine")
def feature(self):
    """
    Tests for ReplicatedReplacingMergeTree engine
    """
    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()

