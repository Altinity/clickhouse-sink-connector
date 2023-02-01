from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *
from integration.tests.steps.statements import *


@TestOutline
def create_all_data_types(self, mysql_type, ch_type, replicated, auto_create_tables):
    """Check auto-creation of replicated MySQL table
    which contains all supported "NOT NULL" data types.
    """
    table_name = f"test{getuid()}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(
        f"I create MySQL table {table_name} with all supported NOT NULL data types"
    ):
        create_tables(
            table_name=table_name,
            mysql_type=mysql_type,
            ch_type=ch_type,
            manual_columns=True,
            replicated=replicated,
            auto_create_tables=auto_create_tables,
        )

    with When(f"I check MySql table {table_name} was created"):
        mysql.query(f"SHOW CREATE TABLE {table_name};", message=f"{table_name}")

    with Then(f"I make insert to create ClickHouse table"):
        mysql.query(
            f"INSERT INTO {table_name} VALUES (1,2/3,1.23,999.00009,'2012-12-12','2018-09-08 17:51:04.777','17:51:04.777','17:51:04.777',0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,'x','some_text','IVAN','some_blob','x_Mediumblob','some_Longblobblobblob','a','IVAN')"
        )

    with Then(f"I check that corresponding ClickHouse table was created"):
        retry(clickhouse.query, timeout=30, delay=5)(
            f"SHOW CREATE TABLE test.{table_name};", message=f"{table_name}"
        )


@TestScenario
def create_all_data_types_not_null_table_auto(
    self,
    mysql_type=all_nullable_mysql_datatypes,
    ch_type=all_nullable_ch_datatypes,
    replicated=False,
    auto_create_tables=True,
):
    """Check auto-creation of replicated MySQL table
    which contains all supported "NOT NULL" data types.
    """
    create_all_data_types(
        mysql_type=mysql_type,
        ch_type=ch_type,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def create_all_data_types_null_table_auto(
    self,
    mysql_type=all_mysql_datatypes,
    ch_type=all_ch_datatypes,
    replicated=False,
    auto_create_tables=True,
):
    """Check auto-creation of replicated MySQL table that
    contains all supported "NULL" data types.
    """
    create_all_data_types(
        mysql_type=mysql_type,
        ch_type=ch_type,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )

@TestScenario
def create_all_data_types_not_null_table_auto(
    self,
    mysql_type=all_nullable_mysql_datatypes,
    ch_type=all_nullable_ch_datatypes,
    replicated=False,
    auto_create_tables=False,
):
    """Check auto-creation of replicated MySQL table
    which contains all supported "NOT NULL" data types.
    """
    create_all_data_types(
        mysql_type=mysql_type,
        ch_type=ch_type,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def create_all_data_types_null_table_auto(
    self,
    mysql_type=all_mysql_datatypes,
    ch_type=all_ch_datatypes,
    replicated=False,
    auto_create_tables=False,
):
    """Check auto-creation of replicated MySQL table that
    contains all supported "NULL" data types.
    """
    create_all_data_types(
        mysql_type=mysql_type,
        ch_type=ch_type,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def create_all_data_types_not_null_table_auto(
    self,
    mysql_type=all_nullable_mysql_datatypes,
    ch_type=all_nullable_ch_datatypes,
    replicated=True,
    auto_create_tables=False,
):
    """Check auto-creation of replicated MySQL table
    which contains all supported "NOT NULL" data types.
    """
    create_all_data_types(
        mysql_type=mysql_type,
        ch_type=ch_type,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestScenario
def create_all_data_types_null_table_auto(
    self,
    mysql_type=all_mysql_datatypes,
    ch_type=all_ch_datatypes,
    replicated=True,
    auto_create_tables=False,
):
    """Check auto-creation of replicated MySQL table that
    contains all supported "NULL" data types.
    """
    create_all_data_types(
        mysql_type=mysql_type,
        ch_type=ch_type,
        replicated=replicated,
        auto_create_tables=auto_create_tables,
    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoCreate(
        "1.0"
    )
)
@Name("autocreate")
def feature(self):
    """Verify correct replication of all supported MySQL data types."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
