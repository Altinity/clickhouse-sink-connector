from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *


@TestOutline
def check_different_primary_keys(
    self,
    insert_values,
    output_values,
    mysql_columns,
    clickhouse_columns,
    clickhouse_table_engine,
    primary_key,
    engine,
):
    """Check replicating MySQL table with different primary keys."""
    table_name = f"primary_keys_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(
        f"I create MySQL to CH replicated table with some primary key",
        description=table_name,
    ):
        create_mysql_table(
            name=table_name,
            columns=mysql_columns,
            primary_key=primary_key,
        )

    with When(f"I insert data in MySQL table {table_name}"):
        mysql.query(f"INSERT INTO {table_name} VALUES {insert_values}")

    with Then(f"I check that ClickHouse table has same data as MySQL table"):
        verify_table_creation_in_clickhouse(
            manual_output=output_values,
            table_name=table_name,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="id, Name",
            with_final=True,
        )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple("1.0")
)
def simple_primary_key(self):
    """Check replicating MySQL table with simple primary key."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_different_primary_keys(
                insert_values="(1, 'Ivan'),(3,'Sergio'),(4,'Alex'),(2,'Alex'),(5,'Andre')",
                output_values='1,"Ivan"\n2,"Alex"\n3,"Sergio"\n4,"Alex"\n5,"Andre"',
                clickhouse_table_engine=clickhouse_table_engine,
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
    """Check replicating MySQL table with composite key."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_different_primary_keys(
                insert_values="(1, 'Ivan'),(1,'Sergio'),(1,'Alex'),(2,'Alex'),(2,'Andre')",
                output_values='1,"Alex"\n1,"Ivan"\n1,"Sergio"\n2,"Alex"\n2,"Andre"',
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" Name VARCHAR(14)",
                clickhouse_columns=" Name String",
                primary_key="id,Name",
                engine=True,
            )


@TestFeature
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_No("1.0"))
def no_primary_key(self):
    """Check replicating MySQL table without any primary key."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            check_different_primary_keys(
                insert_values="(1, 'Ivan'),(1,'Sergio'),(1,'Alex'),(2,'Alex'),(2,'Andre')",
                output_values='1,"Ivan"\n1,"Sergio"\n1,"Alex"\n2,"Alex"\n2,"Andre"',
                clickhouse_table_engine=clickhouse_table_engine,
                mysql_columns=" Name VARCHAR(14)",
                clickhouse_columns=" Name String",
                primary_key="",
                engine=True,
                # ch_primary_key="PRIMARY KEY tuple() ORDER BY tuple() SETTINGS ",
            )


@TestModule
@Name("primary keys")
def module(self):
    """MySQL to ClickHouse replication simple and composite primary keys tests."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
