from integration.requirements.requirements import *
from integration.tests.steps.service_configurations import *
from integration.tests.steps.sql import *
from integration.tests.steps.statements import *


@TestOutline
def delete(
    self, mysql_columns, clickhouse_columns, clickhouse_table, primary_key, engine
):
    """Check `DELETE` query replicating from MySQl table to CH with different primary keys."""

    table_name = f"delete_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=clickhouse_table[0], topics=f"SERVER5432.test.{table_name}"
    )

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySql to CH replicated table", description=table_name):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
            clickhouse_columns=clickhouse_columns,
            clickhouse_table=clickhouse_table,
            primary_key=primary_key,
            engine=engine,
        )

    with When(f"I insert data in MySql table"):
        mysql.query(f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');")
    with Then(f"I delete data in MySql table"):
        mysql.query(f"DELETE FROM {table_name} WHERE id=1;")

    with And("I check that ClickHouse table has same number of rows as MySQL table"):
        complex_check_creation_and_select(
            table_name=table_name,
            clickhouse_table=clickhouse_table,
            statement="count(*)",
            with_final=True,
        )


@TestScenario
def no_primary_key(self):
    """Check for `DELETE` with no primary key without InnoDB engine."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            delete(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key=None,
                engine=False,
            )


@TestScenario
def no_primary_key_innodb(self):
    """Check for `DELETE` with no primary key with InnoDB engine."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            delete(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key=None,
                engine=True,
            )


@TestFeature
def simple_primary_key(self):
    """Check for `DELETE` with simple primary key without InnoDB engine."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            delete(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=False,
            )


@TestScenario
def simple_primary_key_innodb(self):
    """Check for `DELETE` with simple primary key with InnoDB engine."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            delete(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=True,
            )


@TestScenario
def complex_primary_key(self):
    """Check for `DELETE` with complex primary key without engine InnoDB."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            delete(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id,k",
                engine=True,
            )


@TestScenario
def complex_primary_key_innodb(self):
    """Check for `DELETE` with complex primary key with engine InnoDB."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            delete(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id,k",
                engine=False,
            )


@TestFeature
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Deletes("1.0"))
@Name("delete")
def feature(self):
    """MySql to ClickHouse replication delete tests to test `DELETE` queries."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
