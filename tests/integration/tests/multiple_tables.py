from integration.tests.steps import *


@TestOutline
def multiple_table_creation(self, number_of_tables):
    """
    Multiple tables auto creation
    """
    mysql = self.context.cluster.node("mysql-master")
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create unique topics"):
        table_name = f"{','.join([f'SERVER5432.test.users{i}' for i in range(1, number_of_tables + 1)])}"

    init_sink_connector(auto_create_tables=True, topics=table_name)

    for i in range(number_of_tables):
        table_name = f"users{i}"
        with Given(f"I create MySQL table {table_name}"):
            create_mysql_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
                f"(id INT AUTO_INCREMENT,age INT, PRIMARY KEY (id))"
                f" ENGINE = InnoDB;",
            )

        with When(f"I insert data in MySql table"):
            mysql.query(f"insert into {table_name} values (1,777)")

        with Then("I count created tables"):
            retry(clickhouse.query, timeout=50, delay=1)(
                "SELECT count() FROM system.tables WHERE name ilike 'users%'",
                message=f"{i+1}",
            )


@TestScenario
def tables_100(self):
    """
    Creation of 10 tables (if --stress enabled 100 tables creation).
    """
    if self.context.cluster.stress:
        multiple_table_creation(number_of_tables=100)
    else:
        multiple_table_creation(number_of_tables=10)


@TestFeature
@Name("multiple tables")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_MultipleAutoCreate(
        "1.0"
    )
)
def feature(self):
    """
    Multiple tables creation.
    """

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
