from integration.requirements.requirements import *
from integration.tests.steps.configurations import *
from integration.tests.steps.sql import *
from integration.tests.steps.datatypes import *


@TestOutline
def update(
    self, mysql_columns, clickhouse_columns, clickhouse_table, primary_key, engine
):
    """Check `UPDATE` query replicating from MySQl table to CH with different primary keys."""

    table_name = f"update_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=clickhouse_table[0], topics=f"SERVER5432.test.{table_name}"
    )

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
    with Then(f"I update data in MySql table"):
        mysql.query(f"UPDATE {table_name} SET k=k+5 WHERE id=1;")

    with And("I check that ClickHouse has updated data as MySQL"):
        complex_check_creation_and_select(
            table_name=table_name,
            manual_output='1,7,"a","b"',
            clickhouse_table=clickhouse_table,
            statement="id,k,c,pad",
            with_final=True,
        )


@TestFeature
def no_primary_key(self):
    """Check for `UPDATE` with no primary key without table engine."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            update(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key=None,
                engine=False,
            )


@TestFeature
def no_primary_key_innodb(self):
    """Check for `UPDATE` with no primary key with table engine InnoDB."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            update(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key=None,
                engine=True,
            )


@TestFeature
def simple_primary_key(self):
    """Check for `UPDATE` with simple primary key without table engine."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            update(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=False,
            )


@TestFeature
def simple_primary_key_innodb(self):
    """Check for `UPDATE` with simple primary key with table engine InnoDB."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            update(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id",
                engine=True,
            )


@TestFeature
def complex_primary_key(self):
    """Check for `UPDATE` with complex primary key without table engine."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            update(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id,k",
                engine=False,
            )


@TestFeature
def complex_primary_key_innodb(self):
    """Check for `UPDATE` with complex primary key with table engine InnoDB."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            update(
                clickhouse_table=clickhouse_table,
                mysql_columns=" k INT,c CHAR, pad CHAR",
                clickhouse_columns=" k Int32,c String, pad String",
                primary_key="id,k",
                engine=True,
            )


@TestModule
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Updates("1.0"))
@Name("update")
def module(self):
    """MySql to ClickHouse replication update tests to test `UPDATE` queries."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
