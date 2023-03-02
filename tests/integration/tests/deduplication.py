from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def deduplication(
    self, clickhouse_table, inserts=False, big_insert=False, insert_number=1000
):
    """Check MySQL to Clickhouse connection for non-duplication data"""

    table_name = f"deduplication_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(
        auto_create_tables=clickhouse_table[0], topics=f"SERVER5432.test.{table_name}"
    )

    with Given(f"I create MySql to CH replicated table", description=table_name):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns="age INT",
            clickhouse_columns="age Int32",
            clickhouse_table=clickhouse_table,
        )

    if inserts:
        with When(f"I insert {insert_number} rows of data in MySql table"):
            for i in range(1, insert_number + 1):
                mysql.query(f"insert into {table_name} values ({i},777)")
            metric(name="map insert time", value=current_time(), units="sec")
    elif big_insert:
        with When(f"I make one insert on {insert_number} rows data in MySql table"):
            mysql.query(
                f"insert into {table_name} "
                f"values {','.join([f'({i},777)' for i in range(1, insert_number + 1)])}"
            )

    with Then(f"I wait unique values from CLickHouse table equal to MySQL table"):
        complex_check_creation_and_select(
            manual_output=insert_number,
            table_name=table_name,
            statement="count(*)",
            clickhouse_table=clickhouse_table,
            with_final=True,
            timeout=50,
        )


@TestFeature
def deduplication_on_big_insert(self):
    """Check MySQL to Clickhouse connection for non-duplication data on 10 000 inserts."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            deduplication(
                clickhouse_table=clickhouse_table, big_insert=True, insert_number=10000
            )


@TestFeature
def deduplication_on_many_inserts(self):
    """Check MySQL to Clickhouse connection for non-duplication data on big inserts."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            deduplication(
                clickhouse_table=clickhouse_table, inserts=True, insert_number=1000
            )


@TestModule
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication("1.0")
)
@Name("deduplication")
def module(self):
    """MySql to ClickHouse replication tests to check
    for non-duplication data on big inserts."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
