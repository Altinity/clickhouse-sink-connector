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
        statement = (
            f"CREATE TABLE IF NOT EXISTS {name}"
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


@TestStep(Given)
def create_clickhouse_table_engine(
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
    name,
    mysql_columns,
    clickhouse_table_engine,
    clickhouse_columns=None,
    mysql_node=None,
    clickhouse_node=None,
    version_column="_version",
    sign_column="_sign",
    primary_key="id",
    partition_by=None,
    engine=True,
):
    """Create MySQL-to-ClickHouse replicated table.

    :param self:
    :param table_name: replicated table name
    :param mysql_columns: MySQL table columns
    :param clickhouse_columns: coresponding ClickHouse columns
    :param clickhouse_table_engine: use 'auto' for auto create, 'ReplicatedReplacingMergeTree' or 'ReplacingMergeTree'
    :param mysql_node: MySql docker compose node
    :param clickhouse_node: CH docker compose node
    :return:
    """
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
                        f"{f'PRIMARY KEY ({primary_key}) ORDER BY ({primary_key})'if primary_key is not None else 'ORDER BY tuple()'}"
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
            mysql_node.query(
                f"CREATE TABLE IF NOT EXISTS {name} "
                f"(id INT NOT NULL,"
                f"{mysql_columns}"
                f"{f', PRIMARY KEY ({primary_key})'if primary_key is not None else ''}) "
                f"{' ENGINE = InnoDB;' if engine else ''}",
            )

        yield
    finally:
        with Finally(
            "I clean up by deleting MySql to CH replicated table", description={name}
        ):
            mysql_node.query(f"DROP TABLE IF EXISTS {name};")
            clickhouse_node.query(
                f"DROP TABLE IF EXISTS test.{name} ON CLUSTER sharded_replicated_cluster;"
            )
            time.sleep(5)


@TestStep
def create_tables(self, table_name, clickhouse_table_engine):
    """Create different types of replicated tables."""

    tables_list = [
        f"{table_name}",
        f"{table_name}_no_primary_key",
        f"{table_name}_no_engine",
        f"{table_name}_no_engine_no_primary_key",
    ]

    with Given(
        "I create MySQL to ClickHouse replicated table with primary key and with engine"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
        )

    with And(
        "I create MySQL to ClickHouse replicated table with complex primary key and with engine"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_primary_key_complex",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            primary_key="id,x",
        )

    with And(
        "I create MySQL to ClickHouse replicated table with complex primary key and without engine"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_no_engine_complex",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            primary_key="id,x",
            engine=False,
        )

    with And(
        "I create MySQL to ClickHouse replicated table without primary key and with engine"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_no_primary_key",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            primary_key=None,
        )

    with And(
        "I create MySQL to ClickHouse replicated table with primary key and without engine"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_no_engine",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            engine=False,
        )

    with And(
        "I create MySQL to ClickHouse replicated table without primary key and without engine"
    ):
        create_mysql_to_clickhouse_replicated_table(
            name=f"{table_name}_no_engine_no_primary_key",
            mysql_columns="x INT NOT NULL",
            clickhouse_columns="x Int32",
            clickhouse_table_engine=clickhouse_table_engine,
            primary_key=None,
            engine=False,
        )

    return tables_list


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


@TestStep(Then)
def select(
    self,
    manual_output=None,
    table_name=None,
    statement=None,
    node=None,
    with_final=False,
    with_optimize=False,
    sign_column="_sign",
    timeout=300,
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

    if manual_output is None:
        manual_output = mysql_output

    if with_final:
        retry(
            node.query,
            timeout=timeout,
            delay=10,
        )(
            # f"SELECT {statement} FROM test.{table_name}  FINAL WHERE {sign_column} != -1 FORMAT CSV",
            f"SELECT {statement} FROM test.{table_name} FINAL",
            # f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
            message=f"{manual_output}",
        )
    elif with_optimize:
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                node.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                node.query(
                    f"SELECT {statement} FROM test.{table_name} where {sign_column} !=-1 FORMAT CSV",
                    # f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
                    message=f"{manual_output}",
                )

    else:
        retry(
            node.query,
            timeout=timeout,
            delay=10,
        )(
            # f"SELECT {statement} FROM test.{table_name} where {sign_column} !=-1 FORMAT CSV",
            f"SELECT {statement} FROM test.{table_name} FORMAT CSV",
            message=f"{manual_output}",
        )


@TestStep(Then)
def complex_check_creation_and_select(
    self,
    table_name,
    statement,
    clickhouse_table_engine=(""),
    timeout=300,
    manual_output=None,
    with_final=False,
    with_optimize=False,
):
    """
    Check for table creation on all clickhouse nodes where it is expected and select data consistency with MySql
    :param self:
    :param table_name:
    :param auto_create_tables:
    :param replicated:
    :param statement:
    :param with_final:
    :param with_optimize:
    :return:
    """
    clickhouse = self.context.cluster.node("clickhouse")
    clickhouse1 = self.context.cluster.node("clickhouse1")
    clickhouse2 = self.context.cluster.node("clickhouse2")
    clickhouse3 = self.context.cluster.node("clickhouse3")
    mysql = self.context.cluster.node("mysql-master")

    if clickhouse_table_engine.startswith("Replicated"):
        with Then("I check table creation on few nodes"):
            retry(clickhouse.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            retry(clickhouse1.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            retry(clickhouse2.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            retry(clickhouse3.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
    else:
        with Then("I check table creation"):
            retry(clickhouse.query, timeout=100, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(
            table_name=table_name,
            manual_output=manual_output,
            statement=statement,
            with_final=with_final,
            with_optimize=with_optimize,
            timeout=timeout,
        )
        if clickhouse_table_engine.startswith("Replicated"):
            with Then(
                "I check that ClickHouse table has same number of rows as MySQL table on the replica node if it is "
                "replicted table"
            ):
                select(
                    table_name=table_name,
                    manual_output=manual_output,
                    statement=statement,
                    node=self.context.cluster.node("clickhouse1"),
                    with_final=with_final,
                    with_optimize=with_optimize,
                    timeout=timeout,
                )


@TestStep(When)
def delete(
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
    Delete query step
    :param self:
    :param first_delete_id:
    :param last_delete_id:
    :param table_name:
    :return:
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
            f"I delete {last_delete_id - first_delete_id} rows of data in MySql table"
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
    """
    Update query step
    :param self:
    :param first_update_id:
    :param last_update_id:
    :param table_name:
    :return:
    """
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
        By(
            "inserting data in MySql table",
            test=insert,
            parallel=True,
        )(
            first_insert_id=first_insert_id,
            last_insert_id=last_insert_id,
            table_name=table_name,
        )
        By(
            "deleting data in MySql table",
            test=delete,
            parallel=True,
        )(
            first_delete_id=first_delete_id,
            last_delete_id=last_delete_id,
            table_name=table_name,
        )
        By(
            "updating data in MySql table",
            test=update,
            parallel=True,
        )(
            first_update_id=first_update_id,
            last_update_id=last_update_id,
            table_name=table_name,
        )
