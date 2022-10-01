
from mysql_to_clickhouse_replication.requirements import *
from mysql_to_clickhouse_replication.tests.steps import *
from helpers.common import *


@TestOutline
def check_different_primary_keys(self, insert_values, output_values, mysql_primary_key, ch_primary_key,
                                 auto_create_table=True,
                                 timeout=70):
    """Check replicating MySQl table with different primary keys."""
    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL table {table_name} with some primary key"):
        init_sink_connector(auto_create_tables=auto_create_table, topics=f"SERVER5432.test.{table_name}")
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT NOT NULL,Name VARCHAR(14) NOT NULL {mysql_primary_key})"
            f" ENGINE = InnoDB;",
        )

    if not auto_create_table:
        with And(f"I create ClickHouse replica test.{table_name} to MySQL table"):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
                          f"(id Int32, Name String) "
                          f"ENGINE = ReplacingMergeTree "
                          f"{ch_primary_key}"
                          f"index_granularity = 8192;",
            )

    with When(f"I insert data in MySql table {table_name}"):
        mysql.query(
            f"INSERT INTO {table_name} VALUES {insert_values}"
        )

    with Then(f"I check that ClickHouse table has same data as MySQL table"):
        select(insert=output_values, table_name=table_name, statement="id, Name", with_final=True, timeout=50)


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple("1.0")
)
def simple_primary_key(self):
    """Check replicating MySQl table with simple primary key."""
    check_different_primary_keys(
        insert_values="(1, 'Ivan'),(3,'Sergio'),(4,'Alex'),(2,'Alex'),(5,'Andre')",
        output_values='1,"Ivan"\n2,"Alex"\n3,"Sergio"\n4,"Alex"\n5,"Andre"',
        mysql_primary_key=", PRIMARY KEY (id)",
        ch_primary_key="PRIMARY KEY id ORDER BY id SETTINGS "
    )


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Composite("1.0")
)
def composite_primary_key(self):
    """Check replicating MySQl table with composite key."""
    check_different_primary_keys(
        insert_values="(1, 'Ivan'),(1,'Sergio'),(1,'Alex'),(2,'Alex'),(2,'Andre')",
        output_values='1,"Alex"\n1,"Ivan"\n1,"Sergio"\n2,"Alex"\n2,"Andre"',
        mysql_primary_key=", PRIMARY KEY (id, Name)",
        ch_primary_key="PRIMARY KEY (id,Name) ORDER BY (id,Name) SETTINGS "
    )


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_NoPrimaryKey("1.0")
)
def no_primary_key(self):
    """Check replicating MySQl table without any primary key."""

    xfail("https://github.com/Altinity/clickhouse-sink-connector/issues/39")
    check_different_primary_keys(
        insert_values="(1, 'Ivan'),(1,'Sergio'),(1,'Alex'),(2,'Alex'),(2,'Andre')",
        output_values='1,"Ivan"\n1,"Sergio"\n1,"Alex"\n2,"Alex"\n2,"Andre"',
        mysql_primary_key="",
        ch_primary_key="PRIMARY KEY tuple() ORDER BY tuple() SETTINGS "
    )


@TestFeature
@Name("primary keys")
def feature(self):
    """MySql to ClickHouse replication simple and composite primary keys tests."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
