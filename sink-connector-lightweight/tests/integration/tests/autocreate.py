from integration.tests.steps.mysql import *
from integration.tests.steps.clickhouse import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.datatypes import *


@TestOutline
def create_all_data_types(
    self, mysql_columns, clickhouse_columns, clickhouse_table_engine
):
    """Check auto-creation of replicated MySQL table
    which contains all supported data types.
    """
    table_name = f"autocreate_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(
        f"I create MySQL to CH replicated table with all supported NOT NULL data types",
        description=table_name,
    ):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
        )

    with When(f"I check MySQL table {table_name} was created"):
        mysql.query(f"SHOW CREATE TABLE {table_name};", message=f"{table_name}")

    with Then(f"I make insert to create ClickHouse table"):
        mysql.query(
            f"INSERT INTO {table_name} VALUES (1,2/3,1.23,999.00009,'2012-12-12','2018-09-08 17:51:04.777','17:51:04.777','17:51:04.777',0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,'x','some_text','IVAN','some_blob','x_Mediumblob','some_Longblobblobblob','a','IVAN')"
        )

    with Then(
        f"I check that corresponding ClickHouse table was created and data was inserted"
    ):
        verify_table_creation_in_clickhouse(
            table_name=table_name,
            clickhouse_table_engine=clickhouse_table_engine,
            statement="count(*)",
            with_final=True,
        )


@TestFeature
def create_all_data_types_null_table(
    self,
    mysql_columns=all_mysql_datatypes,
    clickhouse_columns=all_ch_datatypes,
):
    """Check all available methods and tables creation of replicated MySQL to Ch table that
    contains all supported "NULL" data types.
    """

    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            create_all_data_types(
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestFeature
def create_all_data_types_not_null_table_manual(
    self,
    mysql_columns=all_nullable_mysql_datatypes,
    clickhouse_columns=all_nullable_ch_datatypes,
):
    """Check all available methods and tables creation of replicated MySQL to CH table
    which contains all supported "NOT NULL" data types.
    """
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            create_all_data_types(
                mysql_columns=mysql_columns,
                clickhouse_columns=clickhouse_columns,
                clickhouse_table_engine=clickhouse_table_engine,
            )


@TestModule
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoCreate(
        "1.0"
    )
)
@Name("autocreate")
def module(self):
    """Verify correct replication of all supported MySQL data types."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
