from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def check_different_primary_keys(
    self,
    insert_values,
    output_values,
    mysql_columns,
    clickhouse_columns,
    clickhouse_table,
    primary_key,
    engine,
):
    """Check replicating MySQl table with different primary keys."""
    table_name = f"primary_keys_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=clickhouse_table[0],
        topics=f"SERVER5432.test.{table_name}",
    )

    with Given(
        f"I create MySql to CH replicated table with some primary key",
        description=table_name,
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
            clickhouse_columns=clickhouse_columns,
            clickhouse_table=clickhouse_table,
            primary_key=primary_key,
            engine=engine,
        )

    with When(f"I insert data in MySql table {table_name}"):
        mysql.query(f"INSERT INTO {table_name} VALUES {insert_values}")

    with Then(f"I check that ClickHouse table has same data as MySQL table"):
        complex_check_creation_and_select(
            manual_output=output_values,
            table_name=table_name,
            clickhouse_table=clickhouse_table,
            statement="id, Name",
            with_final=True,
            order_by="id",
        )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple("1.0")
)
def simple_primary_key(self):
    """Check replicating MySQl table with simple primary key."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_different_primary_keys(
                insert_values="(1, 'Ivan'),(3,'Sergio'),(4,'Alex'),(2,'Alex'),(5,'Andre')",
                output_values='1,"Ivan"\n2,"Alex"\n3,"Sergio"\n4,"Alex"\n5,"Andre"',
                clickhouse_table=clickhouse_table,
                mysql_columns=" Name VARCHAR(14)",
                clickhouse_columns=" Name String",
                primary_key="id",
                engine=True,
            )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Composite("1.0")
)
def composite_primary_key(self):
    """Check replicating MySQl table with composite key."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_different_primary_keys(
                insert_values="(1, 'Ivan'),(1,'Sergio'),(1,'Alex'),(2,'Alex'),(2,'Andre')",
                output_values='1,"Alex"\n1,"Ivan"\n1,"Sergio"\n2,"Alex"\n2,"Andre"',
                clickhouse_table=clickhouse_table,
                mysql_columns=" Name VARCHAR(14)",
                clickhouse_columns=" Name String",
                primary_key="id,Name",
                engine=True,
            )


@TestFeature
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_No("1.0"))
def no_primary_key(self):
    """Check replicating MySQl table without any primary key."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_different_primary_keys(
                insert_values="(1, 'Ivan'),(1,'Sergio'),(1,'Alex'),(2,'Alex'),(2,'Andre')",
                output_values='1,"Ivan"\n1,"Sergio"\n1,"Alex"\n2,"Alex"\n2,"Andre"',
                clickhouse_table=clickhouse_table,
                mysql_columns=" Name VARCHAR(14)",
                clickhouse_columns=" Name String",
                primary_key="",
                engine=True,
                # ch_primary_key="PRIMARY KEY tuple() ORDER BY tuple() SETTINGS ",
            )


@TestModule
@Name("primary keys")
def module(self):
    """MySql to ClickHouse replication simple and composite primary keys tests."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
