from integration.tests.steps.clickhouse import *
from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *


@TestOutline
def deduplication(
    self, clickhouse_table_engine, inserts=False, big_insert=False, insert_number=1000
):
    """Check MySQL to Clickhouse connection for non-duplication data"""

    table_name = f"deduplication_{getuid()}"

    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL to CH replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns="age INT",
        )

    if inserts:
        with When(f"I insert {insert_number} rows of data in MySQL table"):
            for i in range(1, insert_number + 1):
                mysql.query(f"insert into {table_name} values ({i},777)")
            metric(name="map insert time", value=current_time(), units="sec")
    elif big_insert:
        with When(f"I make one insert on {insert_number} rows data in MySQL table"):
            mysql.query(
                f"insert into {table_name} "
                f"values {','.join([f'({i},777)' for i in range(1, insert_number + 1)])}"
            )

    with Then(f"I wait unique values from CLickHouse table equal to MySQL table"):
        verify_table_creation_in_clickhouse(
            manual_output=insert_number,
            table_name=table_name,
            statement="count(*)",
            clickhouse_table_engine=clickhouse_table_engine,
            with_final=True,
            timeout=50,
        )


@TestFeature
def deduplication_on_big_insert(self):
    """Check MySQL to Clickhouse connection for non-duplication data on 10 000 inserts."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            deduplication(
                clickhouse_table_engine=clickhouse_table_engine,
                big_insert=True,
                insert_number=10000,
            )


@TestFeature
def deduplication_on_many_inserts(self):
    """Check MySQL to Clickhouse connection for non-duplication data on big inserts."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        with Example({clickhouse_table_engine}, flags=TE):
            deduplication(
                clickhouse_table_engine=clickhouse_table_engine,
                inserts=True,
                insert_number=1000,
            )


@TestModule
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication("1.0")
)
@Name("deduplication")
def module(self):
    """MySQL to ClickHouse replication tests to check
    for non-duplication data on big inserts."""

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
