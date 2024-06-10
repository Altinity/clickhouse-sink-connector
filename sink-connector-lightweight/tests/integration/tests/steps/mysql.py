import random

from integration.helpers.common import *
from datetime import datetime, timedelta
from testflows.core import *


def generate_sample_mysql_value(data_type):
    """Generate a sample MySQL value for the provided datatype."""
    if data_type.startswith("DECIMAL"):
        precision, scale = map(
            int, data_type[data_type.index("(") + 1 : data_type.index(")")].split(",")
        )
        number = round(
            random.uniform(-(10 ** (precision - scale)), 10 ** (precision - scale)),
            scale,
        )
        return str(number)
    elif data_type.startswith("DOUBLE"):
        # Adjusting the range to avoid overflow, staying within a reasonable limit
        return str(random.uniform(-1.7e308, 1.7e308))
    elif data_type == "DATE NOT NULL":
        return (datetime.today() - timedelta(days=random.randint(1, 365))).strftime(
            "%Y-%m-%d"
        )
    elif data_type.startswith("DATETIME"):
        return (datetime.now() - timedelta(days=random.randint(1, 365))).strftime(
            "%Y-%m-%d %H:%M:%S.%f"
        )[:19]
    elif data_type.startswith("TIME"):
        if "6" in data_type:
            return (datetime.now()).strftime("%H:%M:%S.%f")[
                : 8 + 3
            ]  # Including microseconds
        else:
            return (datetime.now()).strftime("%H:%M:%S")
    elif "INT" in data_type:
        if "TINYINT" in data_type:
            return str(
                random.randint(0, 255)
                if "UNSIGNED" in data_type
                else random.randint(-128, 127)
            )
        elif "SMALLINT" in data_type:
            return str(
                random.randint(0, 65535)
                if "UNSIGNED" in data_type
                else random.randint(-32768, 32767)
            )
        elif "MEDIUMINT" in data_type:
            return str(
                random.randint(0, 16777215)
                if "UNSIGNED" in data_type
                else random.randint(-8388608, 8388607)
            )
        elif "BIGINT" in data_type:
            return str(
                random.randint(0, 2**63 - 1)
                if "UNSIGNED" in data_type
                else random.randint(-(2**63), 2**63 - 1)
            )
        else:  # INT
            return str(
                random.randint(0, 4294967295)
                if "UNSIGNED" in data_type
                else random.randint(-2147483648, 2147483647)
            )
    elif (
        data_type.startswith("CHAR")
        or data_type.startswith("VARCHAR")
        or data_type == "TEXT NOT NULL"
    ):
        return "'SampleText'"
    elif data_type.endswith("BLOB NOT NULL"):
        return "'SampleBinaryData'"
    elif data_type.startswith("BINARY") or data_type.startswith("VARBINARY"):
        return "'BinaryData'"
    else:
        return "UnknownType"


@TestStep(Given)
def create_mysql_database(self, node=None, database_name=None):
    """Creation of MySQL database."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    if database_name is None:
        database_name = "test"

    try:
        with Given(f"I create MySQL database {database_name}"):
            node.query(f"DROP DATABASE IF EXISTS {database_name};")
            node.query(f"CREATE DATABASE IF NOT EXISTS {database_name};")
        yield
    finally:
        with Finally(f"I delete MySQL database {database_name}"):
            node.query(f"DROP DATABASE IF EXISTS {database_name};")


@TestStep(Given)
def create_mysql_table(self, name=None, statement=None, node=None, database_name=None):
    """Creation of default MySQL table for tests."""

    if database_name is None:
        database_name = "test"
    if node is None:
        node = self.context.cluster.node("mysql-master")
    if name is None:
        name = "users"
    if statement is None:
        statement = (
            f"CREATE TABLE IF NOT EXISTS {database_name}.{name}"
            f" (id INT AUTO_INCREMENT,"
            f" age INT, PRIMARY KEY (id)) ORDER BY tuple() ENGINE = InnoDB;"
        )

    try:
        with Given(f"I create MySQL table {name}"):
            node.query(statement)
        yield
    finally:
        with Finally("I clean up by deleting table in MySQL"):
            node.query(f"DROP TABLE IF EXISTS {name};")
            self.context.cluster.node("clickhouse").query(
                f"DROP TABLE IF EXISTS test.{name} ON CLUSTER sharded_replicated_cluster;"
            )
            time.sleep(5)


@TestStep
def create_mysql_to_clickhouse_replicated_table(
    self,
    name,
    mysql_columns,
    clickhouse_table_engine,
    database_name=None,
    clickhouse_columns=None,
    mysql_node=None,
    clickhouse_node=None,
    version_column="_version",
    sign_column="_sign",
    primary_key="id",
    partition_by=None,
    engine=True,
    partition_by_mysql=False,
):
    """Create MySQL to ClickHouse replicated table."""
    if database_name is None:
        database_name = "test"

    if mysql_node is None:
        mysql_node = self.context.cluster.node("mysql-master")

    if clickhouse_node is None:
        clickhouse_node = self.context.cluster.node("clickhouse")

    try:
        if self.context.env.endswith("auto"):
            if clickhouse_table_engine == "ReplacingMergeTree":
                pass
            else:
                raise NotImplementedError(
                    f"table '{clickhouse_table_engine}' not supported"
                )

        elif self.context.env.endswith("manual"):
            if clickhouse_table_engine == "ReplicatedReplacingMergeTree":
                with Given(
                    f"I create ReplicatedReplacingMergeTree as a replication table",
                    description=name,
                ):
                    clickhouse_node.query(
                        f"CREATE TABLE IF NOT EXISTS test.{name} ON CLUSTER sharded_replicated_cluster"
                        f"(id Int32,{clickhouse_columns}, {sign_column} "
                        f"Int8, {version_column} UInt64) "
                        f"ENGINE = ReplicatedReplacingMergeTree("
                        "'/clickhouse/tables/{shard}"
                        f"/{name}',"
                        " '{replica}',"
                        f" {version_column}) "
                        f"{f'PRIMARY KEY ({primary_key}) ORDER BY ({primary_key})' if primary_key is not None else 'ORDER BY tuple()'}"
                        f"{f'PARTITION BY ({partition_by})' if partition_by is not None else ''}"
                        f" SETTINGS "
                        f"index_granularity = 8192;",
                    )
            elif clickhouse_table_engine == "ReplacingMergeTree":
                with Given(
                    f"I create ClickHouse table as replication table to MySQL test.{name}"
                ):
                    clickhouse_node.query(
                        f"CREATE TABLE IF NOT EXISTS test.{name} "
                        f"(id Int32,{clickhouse_columns}, {sign_column} "
                        f"Int8, {version_column} UInt64) "
                        f"ENGINE = ReplacingMergeTree({version_column}) "
                        f"{f'PRIMARY KEY ({primary_key}) ORDER BY ({primary_key})' if primary_key is not None else 'ORDER BY tuple()'}"
                        f"{f'PARTITION BY ({partition_by})' if partition_by is not None else ''}"
                        f" SETTINGS "
                        f"index_granularity = 8192;",
                    )

            else:
                raise NotImplementedError(
                    f"table '{clickhouse_table_engine}' not supported"
                )

        else:
            raise NotImplementedError(
                f"table creation method '{self.context.env}' not supported"
            )

        with Given(f"I create MySQL table", description=name):
            query = f"CREATE TABLE IF NOT EXISTS {database_name}.{name} (id INT NOT NULL,{mysql_columns}"

            if primary_key is not None:
                query += f", PRIMARY KEY ({primary_key}))"
            else:
                query += ")"

            if engine:
                query += f" ENGINE = InnoDB"

            if partition_by_mysql:
                query += f", PARTITION BY {partition_by_mysql}"

            query += ";"

            mysql_node.query(query)

        yield
    finally:
        with Finally(
            "I clean up by deleting MySQL to CH replicated table", description={name}
        ):
            mysql_node.query(f"DROP TABLE IF EXISTS {database_name}.{name};")
            clickhouse_node.query(
                f"DROP TABLE IF EXISTS {database_name}.{name} ON CLUSTER sharded_replicated_cluster;"
            )
            time.sleep(5)


@TestStep(Given)
def create_table_with_no_primary_key(self, table_name, clickhouse_table_engine):
    """Create MySQL table without primary key."""

    with By(f"creating a {table_name} table without primary key"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_no_primary_key",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            primary_key=None,
        )


@TestStep(Given)
def create_table_with_no_engine(self, table_name, clickhouse_table_engine):
    """Create MySQL table without engine."""

    with By(f"creating a {table_name} table without engine"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_no_engine",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            engine=False,
        )


@TestStep(Given)
def create_table_with_primary_key_and_engine(self, table_name, clickhouse_table_engine):
    """Create MySQL table with primary key and with engine."""

    with By(f"creating a {table_name} table with primary key and with engine"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
        )


@TestStep(Given)
def create_table_with_no_engine_and_no_primary_key(
    self, table_name, clickhouse_table_engine
):
    """Create MySQL table without engine and without primary key."""

    with By(f"creating a {table_name} table without engine and without primary key"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_no_engine_no_primary_key",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            primary_key=None,
            engine=False,
        )


@TestStep(Given)
def create_tables(self, table_name, clickhouse_table_engine="ReplacingMergeTree"):
    """Create different types of replicated tables."""

    with Given("I set the table names"):
        tables_list = [
            f"{table_name}",
            f"{table_name}_no_primary_key",
            f"{table_name}_no_engine",
            f"{table_name}_no_engine_no_primary_key",
        ]

    with And(
        "I create MySQL to ClickHouse replicated table with primary key and with engine"
    ):
        create_table_with_primary_key_and_engine(
            table_name=table_name, clickhouse_table_engine=clickhouse_table_engine
        )

    with And(
        "I create MySQL to ClickHouse replicated table without primary key and with engine"
    ):
        create_table_with_no_primary_key(
            table_name=table_name, clickhouse_table_engine=clickhouse_table_engine
        )

    with And(
        "I create MySQL to ClickHouse replicated table with primary key and without engine"
    ):
        create_table_with_no_engine(
            table_name=table_name, clickhouse_table_engine=clickhouse_table_engine
        )

    with And(
        "I create MySQL to ClickHouse replicated table without primary key and without engine"
    ):
        create_table_with_no_engine_and_no_primary_key(
            table_name=table_name, clickhouse_table_engine=clickhouse_table_engine
        )

    return tables_list


@TestStep(When)
def insert(self, table_name, values, node=None, database_name=None):
    """Insert data into MySQL table."""
    if database_name is None:
        database_name = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    with When("I insert data into MySQL table"):
        node.query(rf"INSERT INTO {database_name}.\`{table_name}\` VALUES ({values});")


@TestStep(Given)
def insert_precondition_rows(
    self,
    first_insert_id,
    last_insert_id,
    table_name,
    insert_values=None,
    node=None,
):
    """Insert some controlled interval of ID's in MySQL table."""
    if insert_values is None:
        insert_values = "({x},2,'a','b')"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    with Given(
        f"I insert {first_insert_id - last_insert_id} rows of data in MySQL table"
    ):
        for i in range(first_insert_id, last_insert_id + 1):
            node.query(f"INSERT INTO `{table_name}` VALUES {insert_values}".format(x=i))


@TestStep(When)
def complex_insert(
    self,
    table_name,
    values,
    start_id=1,
    start_value=1,
    node=None,
    partitions=101,
    parts_per_partition=1,
    block_size=1,
    exitcode=True,
):
    """Insert data having specified number of partitions and parts."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    x = start_id
    y = start_value

    insert_values_1 = ",".join(
        f"{values[0]}".format(x=x, y=y)
        for x in range(start_id, partitions + start_id)
        for y in range(start_value, block_size * parts_per_partition + start_value)
    )

    if exitcode:
        retry(
            node.query,
            timeout=300,
            delay=10,
        )(f"INSERT INTO {table_name} VALUES {insert_values_1}", exitcode=0)
    else:
        retry(
            node.query,
            timeout=300,
            delay=10,
        )(f"INSERT INTO {table_name} VALUES {insert_values_1}")


@TestStep(When)
def delete_rows(
    self,
    table_name,
    first_delete_id=None,
    last_delete_id=None,
    condition=None,
    row_delete=False,
    multiple=False,
    no_checks=False,
    check=False,
    delay=False,
):
    """
    Test step to delete rows from MySQL table.
    """
    mysql = self.context.cluster.node("mysql-master")

    if row_delete:
        if multiple:
            command = ";".join(
                [f"DELETE FROM {table_name} WHERE {i}" for i in condition]
            )
        else:
            command = f"DELETE FROM {table_name} WHERE {condition}"
        r = mysql.query(command, no_checks=no_checks)
        if check:
            for attempt in retries(delay=0.1, timeout=30):
                with attempt:
                    with Then("I check rows are deleted"):
                        check_result = mysql.query(
                            f"SELECT count() FROM {table_name} WHERE {condition}"
                        )
                        assert check_result.output == "0", error()
        if delay:
            time.sleep(delay)
        return r
    else:
        with Given(
            f"I delete {last_delete_id - first_delete_id} rows of data in MySQL table"
        ):
            for i in range(first_delete_id, last_delete_id):
                mysql.query(f"DELETE FROM {table_name} WHERE id={i}")


@TestStep(When)
def update(
    self,
    table_name,
    first_update_id=None,
    last_update_id=None,
    condition=None,
    row_update=False,
    multiple=False,
    no_checks=False,
    check=False,
    delay=False,
):
    """Update query step for MySQL table."""
    mysql = self.context.cluster.node("mysql-master")

    if row_update:
        if multiple:
            command = ";".join(
                [f"UPDATE {table_name} SET x=x+10000 WHERE {i}" for i in condition]
            )
        else:
            command = f"UPDATE {table_name} SET x=x+10000 WHERE {condition}"
        r = mysql.query(command, no_checks=no_checks)
        if check:
            for attempt in retries(delay=0.1, timeout=30):
                with attempt:
                    with Then("I check rows are deleted"):
                        check_result = mysql.query(
                            f"SELECT count() FROM {table_name} WHERE {condition}"
                        )
                        assert check_result.output == "0", error()
        if delay:
            time.sleep(delay)
        return r
    else:
        with Given(
            f"I update {last_update_id - first_update_id} rows of data in MySQL table"
        ):
            for i in range(first_update_id, last_update_id):
                mysql.query(f"UPDATE {table_name} SET k=k+5 WHERE id={i};")


@TestStep(When)
def concurrent_queries(
    self,
    table_name,
    first_insert_number,
    last_insert_number,
    first_insert_id,
    last_insert_id,
    first_delete_id,
    last_delete_id,
    first_update_id,
    last_update_id,
):
    """Insert, update, delete for concurrent queries."""

    with Given("I insert block of precondition rows"):
        insert_precondition_rows(
            table_name=table_name,
            first_insert_id=first_insert_number,
            last_insert_id=last_insert_number,
        )

    with When("I start concurrently insert, update and delete queries in MySQL table"):
        By(
            "inserting data in MySQL table",
            test=insert_precondition_rows,
            parallel=True,
        )(
            first_insert_id=first_insert_id,
            last_insert_id=last_insert_id,
            table_name=table_name,
        )
        By(
            "deleting data in MySQL table",
            test=delete_rows,
            parallel=True,
        )(
            first_delete_id=first_delete_id,
            last_delete_id=last_delete_id,
            table_name=table_name,
        )
        By(
            "updating data in MySQL table",
            test=update,
            parallel=True,
        )(
            first_update_id=first_update_id,
            last_update_id=last_update_id,
            table_name=table_name,
        )
