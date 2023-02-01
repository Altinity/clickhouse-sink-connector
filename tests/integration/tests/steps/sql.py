from integration.requirements.requirements import *

from integration.helpers.common import *


@TestStep(Given)
def create_mysql_table(self, name=None, statement=None, node=None):
    """
    Creation of default MySQL table for tests
    :param self:
    :param name:
    :param statement:
    :param node:
    :return:
    """
    if node is None:
        node = self.context.cluster.node("mysql-master")
    if name is None:
        name = "users"
    if statement is None:
        statement = f"CREATE TABLE IF NOT EXISTS {name} "
        f"(id INT AUTO_INCREMENT,age INT, PRIMARY KEY (id))"
        f" ENGINE = InnoDB;"

    try:
        with Given(f"I create MySQL table {name}"):
            node.query(statement)
        yield
    finally:
        with Finally("I clean up by deleting table in MySQL"):
            node.query(f"DROP TABLE IF EXISTS {name};")
            self.context.cluster.node("clickhouse").query(
                f"DROP TABLE IF EXISTS test.{name} ON CLUSTER sharded_replicated_cluster;;"
            )
            time.sleep(5)


@TestStep(Given)
def create_clickhouse_table(
    self, name=None, statement=None, node=None, force_select_final=False
):
    """
    Creation of default ClickHouse table for tests
    :param self:
    :param name:
    :param statement:
    :param node:
    :return:
    """
    if node is None:
        node = self.context.cluster.node("clickhouse")
    if name is None:
        name = "users"
    if statement is None:
        statement = f"CREATE TABLE IF NOT EXISTS test.{name} "
        f"(id Int32, age Int32) "
        f"ENGINE = MergeTree "
        f"PRIMARY KEY id ORDER BY id SETTINGS {' ignore_force_select_final=1' if force_select_final else ''}"
        f"index_granularity = 8192;"

    try:
        with Given(f"I create ClickHouse table {name}"):
            node.query(statement)
        yield
    finally:
        with Finally("I clean up by deleting table in ClickHouse"):
            node.query(
                f"DROP TABLE IF EXISTS test.{name} ON CLUSTER sharded_replicated_cluster;"
            )


@TestStep
def create_mysql_to_clickhouse_replicated_table(
    self,
    table_name,
    mysql_columns,
    clickhouse_columns,
    clickhouse_table,
):
    """Create MySQL-to-ClickHouse replicated table.

    :param self:
    :param table_name: replicated table name
    :param mysql_columns: MySQL table columns
    :param clickhouse_columns: coresponding ClickHouse columns
    :param clickhouse_table: use 'auto' for auto create, 'ReplicatedReplacingMergeTree' or 'ReplacingMergeTree'
    :return:
    """

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT AUTO_INCREMENT,"
            f"{mysql_columns},"
            f" PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )

    if ch_table == "auto":
        pass
    
    elif ch_table == "ReplicatedReplacingMergeTree:
        with And(
            f"I create ReplicatedReplacingMergeTree as a replication table", description= f"{table_name}"
        ):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} ON CLUSTER sharded_replicated_cluster"
                f"(id Int32,{clickhouse_columns}, _sign "
                f"Int8, _version UInt64) "
                f"ENGINE = ReplicatedReplacingMergeTree("
                "'/clickhouse/tables/{shard}"
                f"/{table_name}',"
                " '{replica}',"
                f" _version) "
                f"PRIMARY KEY id ORDER BY id SETTINGS "
                f"index_granularity = 8192;",
            )
         
    elif ch_table == "ReplacingMergeTree:
        with And(
            f"I create ClickHouse table as replication table to MySQL test.{table_name}"
        ):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
                f"(id Int32,{clickhouse_columns}, _sign "
                f"Int8, _version UInt64) "
                f"ENGINE = ReplacingMergeTree(_version) "
                f"PRIMARY KEY id ORDER BY id SETTINGS "
                f"index_granularity = 8192;",
            )
    
    else:
        raise NotImplementedError(f"table '{clickhouse_table}' not supported")


@TestStep(Given)
def insert(
    self,
    first_insert_id,
    last_insert_id,
    table_name,
    insert_values="({x},2,'a','b')",
    node=None,
):
    """
    Insert some controlled interval of id's
    :param self:
    :param node:
    :param first_insert_id:
    :param last_insert_id:
    :param table_name:
    :return:
    """
    if node is None:
        node = self.context.cluster.node("mysql-master")

    with Given(
        f"I insert {first_insert_id - last_insert_id} rows of data in MySql table"
    ):
        for i in range(first_insert_id, last_insert_id + 1):
            node.query(f"INSERT INTO {table_name} VALUES {insert_values}".format(x=i))


@TestStep(When)
def complex_insert(
    self,
    table_name,
    values,
    node=None,
    partitions=101,
    parts_per_partition=1,
    block_size=1,
):
    """Insert data having specified number of partitions and parts."""
    if node is None:
        node = self.context.cluster.node("mysql-master")

    insert_values_1 = ",".join(
        f"{values[0]}".format(x=x, y=y)
        for x in range(partitions)
        for y in range(block_size * parts_per_partition)
    )
    insert_values_2 = ",".join(
        f"{values[1]}".format(x=x, y=y)
        for x in range(partitions)
        for y in range(block_size * parts_per_partition)
    )
    node.query("system stop merges")
    node.query(f"INSERT INTO {table_name} VALUES {insert_values_1}")
    node.query(f"INSERT INTO {table_name} VALUES {insert_values_2}")


@TestStep(Then)
def select(
    self,
    insert=None,
    table_name=None,
    statement=None,
    node=None,
    with_final=False,
    with_optimize=False,
    timeout=100,
):
    """SELECT with an option to either with FINAL or loop SELECT + OPTIMIZE TABLE default simple 'SELECT'
    :param insert: expected insert data if None compare with MySQL table
    :param table_name: table name for select  default "users"
    :param statement: statement for select default "*"
    :param node: node name
    :param with_final: 'SELECT ... FINAL'
    :param with_optimize: loop 'OPTIMIZE TABLE' + 'SELECT'
    :param timeout: retry timeout
    """
    if node is None:
        node = self.context.cluster.node("clickhouse")
    if table_name is None:
        table_name = "users"
    if statement is None:
        statement = "*"

    mysql = self.context.cluster.node("mysql-master")
    mysql_output = mysql.query(f"select {statement} from {table_name}").output.strip()[
        90:
    ]

    if insert is None:
        insert = mysql_output

    if with_final:
        retry(node.query, timeout=timeout, delay=10,)(
            f"SELECT {statement} FROM test.{table_name} FINAL FORMAT CSV",
            message=f"{insert}",
        )
    elif with_optimize:
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                node.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                node.query(
                    f"SELECT {statement} FROM test.{table_name} where _sign !=-1 FORMAT CSV",
                    message=f"{insert}",
                )

    else:
        retry(node.query, timeout=timeout, delay=10,)(
            f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
            message=f"{insert}",
        )


@TestStep(Then)
def complex_select(
    self,
    table_name,
    auto_create_tables,
    replicated,
    statement,
    with_final=False,
    with_optimize=False,
):
    clickhouse = self.context.cluster.node("clickhouse")
    clickhouse1 = self.context.cluster.node("clickhouse1")
    clickhouse2 = self.context.cluster.node("clickhouse2")
    clickhouse3 = self.context.cluster.node("clickhouse3")
    mysql = self.context.cluster.node("mysql-master")

    if auto_create_tables:
        if replicated:
            with Then("I check table creation on few nodes"):
                retry(clickhouse.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )
                retry(clickhouse1.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )
                retry(clickhouse2.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )
                retry(clickhouse3.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )
        else:
            with Then("I check table creation"):
                retry(clickhouse.query, timeout=30, delay=3)(
                    "SHOW TABLES FROM test", message=f"{table_name}"
                )

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(
            table_name=table_name,
            statement=statement,
            with_final=with_final,
            with_optimize=with_optimize,
            timeout=50,
        )
        if replicated:
            with Then(
                "I check that ClickHouse table has same number of rows as MySQL table on the replica node if it is "
                "replicted table"
            ):
                select(
                    table_name=table_name,
                    statement=statement,
                    node=self.context.cluster.node("clickhouse1"),
                    with_final=with_final,
                    with_optimize=with_optimize,
                    timeout=50,
                )


@TestStep(When)
def delete(self, first_delete_id, last_delete_id, table_name):
    """
    Delete query step
    :param self:
    :param first_delete_id:
    :param last_delete_id:
    :param table_name:
    :return:
    """
    mysql = self.context.cluster.node("mysql-master")

    with Given(
        f"I delete {last_delete_id - first_delete_id} rows of data in MySql table"
    ):
        for i in range(first_delete_id, last_delete_id):
            mysql.query(f"DELETE FROM {table_name} WHERE id={i}")


@TestStep(When)
def update(self, first_update_id, last_update_id, table_name):
    """
    Update query step
    :param self:
    :param first_update_id:
    :param last_update_id:
    :param table_name:
    :return:
    """
    mysql = self.context.cluster.node("mysql-master")

    with Given(
        f"I update {last_update_id - first_update_id} rows of data in MySql table"
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
    """
    Insert, update, delete for concurrent queries.
    :param self:
    :param table_name: table name
    :param first_insert_number: first id of precondition insert
    :param last_insert_number:  last id of precondition insert
    :param first_insert_id: first id of concurrent insert
    :param last_insert_id: last id of concurrent insert
    :param first_delete_id: first id of concurrent delete
    :param last_delete_id: last id of concurrent delete
    :param first_update_id: first id of concurrent update
    :param last_update_id: last id of concurrent update
    :return:
    """

    with Given("I insert block of precondition rows"):
        insert(
            table_name=table_name,
            first_insert_id=first_insert_number,
            last_insert_id=last_insert_number,
        )

    with When("I start concurrently insert, update and delete queries in MySql table"):
        By("inserting data in MySql table", test=insert, parallel=True,)(
            first_insert_id=first_insert_id,
            last_insert_id=last_insert_id,
            table_name=table_name,
        )
        By("deleting data in MySql table", test=delete, parallel=True,)(
            first_delete_id=first_delete_id,
            last_delete_id=last_delete_id,
            table_name=table_name,
        )
        By("updating data in MySql table", test=update, parallel=True,)(
            first_update_id=first_update_id,
            last_update_id=last_update_id,
            table_name=table_name,
        )
