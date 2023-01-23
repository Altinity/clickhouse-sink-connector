from integration.requirements.requirements import *

from integration.helpers.common import *


@TestStep(Given)
def init_debezium_connector(self, node=None):
    """
    Initialize debezium connectors.
    """
    if node is None:
        node = self.context.cluster.node("bash-tools")

    debezium_settings_transfer_command_apicurio = """cat <<EOF | curl --request POST --url "http://debezium:8083/connectors" --header 'Content-Type: application/json' --data @-
{
  "name": "test-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",
    "snapshot.mode": "initial",
    "snapshot.locking.mode": "minimal",
    "snapshot.delay.ms": 10000,
    "include.schema.changes":"true",
    "include.schema.comments": "true",
    "database.hostname": "mysql-master",
    "database.port": "3306",
    "database.user": "root",
    "database.password": "root",
    "database.server.id": "5432",
    "database.server.name": "SERVER5432",
    "database.whitelist": "test",
    "database.allowPublicKeyRetrieval":"true",
    "database.history.kafka.bootstrap.servers": "kafka:9092",
    "database.history.kafka.topic": "schema-changes.test_db",



    "key.converter": "io.apicurio.registry.utils.converter.AvroConverter",
    "value.converter": "io.apicurio.registry.utils.converter.AvroConverter",

    "key.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
    "key.converter.apicurio.registry.auto-register": "true",
    "key.converter.apicurio.registry.find-latest": "true",

    "value.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
    "value.converter.apicurio.registry.auto-register": "true",
    "value.converter.apicurio.registry.find-latest": "true",

    "topic.creation.$alias.partitions": 3,
    "topic.creation.default.replication.factor": 1,
    "topic.creation.default.partitions": 6,

    "provide.transaction.metadata": "true",
  }
}
EOF"""

    debezium_settings_transfer_command_confluent = """cat <<EOF | curl --request POST --url "http://debezium:8083/connectors" --header 'Content-Type: application/json' --data @-
      {
        "name": "test-connector",
        "config": {
          "connector.class": "io.debezium.connector.mysql.MySqlConnector",
          "tasks.max": "1",
          "snapshot.mode": "initial",
          "snapshot.locking.mode": "minimal",
          "snapshot.delay.ms": 1,
          "include.schema.changes":"true",
          "include.schema.comments": "true",
          "database.hostname": "mysql-master",
          "database.port": "3306",
          "database.user": "root",
          "database.password": "root",
          "database.server.id": "5432",
          "database.server.name": "SERVER5432",
          "database.whitelist": "test",
          "database.allowPublicKeyRetrieval":"true",

          "database.history.kafka.bootstrap.servers": "kafka:9092",
          "database.history.kafka.topic": "schema-changes.test",

          "key.converter": "io.confluent.connect.avro.AvroConverter",
          "value.converter": "io.confluent.connect.avro.AvroConverter",

          "key.converter.schema.registry.url": "http://schemaregistry:8081",
          "value.converter.schema.registry.url":"http://schemaregistry:8081",

          "topic.creation.$alias.partitions": 6,
          "topic.creation.default.replication.factor": 1,
          "topic.creation.default.partitions": 6,

          "provide.transaction.metadata": "true",
          "topic.prefix" : "SERVER5432",
          "database.server.id": "5432",
          
          "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
          "schema.history.internal.kafka.topic": "schemahistory.test",
          "skipped.operations": "none"   
        }
      }
EOF"""

    try:
        time.sleep(10)
        with Given(
            "I start debezium connector",
            description="""Sending debezium settings push command on bash_tools
                    and wait message that they applied correct""",
        ):
            retry(node.cmd, timeout=100, delay=3)(
                f"{debezium_settings_transfer_command_confluent}",
                message='{"error_code":409,"message":"Connector '
                'test-connector already exists"}',
            )

        yield
    finally:
        time.sleep(10)
        with Finally("I delete sink and debezium connections"):
            with By("deleting debezium connector", flags=TE):
                node.cmd(
                    'curl -X DELETE -H "Accept:application/json" "http://debezium:8083/connectors/test-connector" '
                    "2>/dev/null | jq ."
                )


@TestStep(Given)
def init_sink_connector(
    self,
    node=None,
    auto_create_tables=False,
    topics="SERVER5432.sbtest.sbtest1,SERVER5432.test.users1,SERVER5432.test.users2,SERVER5432.test.users3, SERVER5432.test.users",
):
    """
    Initialize sink connector.
    """
    if node is None:
        node = self.context.cluster.node("bash-tools")

    if auto_create_tables:
        auto_create_tables_local = "true"
    else:
        auto_create_tables_local = "false"

    sink_settings_transfer_command_apicurio = (
        """cat <<EOF | curl --request POST --url "http://sink:8083/connectors" --header 'Content-Type: application/json' --data @-
{
  "name": "sink-connector",
  "config": {
    "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
    "tasks.max": "10",
    "topics.regex": "SERVER5432.test.users, SERVER5432.sbtest.sbtest1",
    "clickhouse.topic2table.map": "SERVER5432.test.users:users",
    "clickhouse.server.url": "clickhouse",
    "clickhouse.server.user": "root",
    "clickhouse.server.pass": "root",
    "clickhouse.server.database": "test",
    "clickhouse.server.port": 8123,
    "clickhouse.table.name": "users",
    "key.converter": "io.apicurio.registry.utils.converter.AvroConverter",
    "value.converter": "io.apicurio.registry.utils.converter.AvroConverter",

    "key.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
    "key.converter.apicurio.registry.auto-register": "true",
    "key.converter.apicurio.registry.find-latest": "true",

    "value.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
    "value.converter.apicurio.registry.auto-register": "true",
    "value.converter.apicurio.registry.find-latest": "true",
    "store.kafka.metadata": true,
    "topic.creation.default.partitions": 6,

    "store.raw.data": false,
    "store.raw.data.column": "raw_data",

    "metrics.enable": true,
    "metrics.port": 8084,
    "buffer.flush.time": 3500,
    "thread.pool.size": 1,
    "fetch.min.bytes": 52428800,

    "enable.kafka.offset": false,

    "replacingmergetree.delete.column": "_sign","""
        + f'"auto.create.tables": {auto_create_tables_local},'
        """
    "schema.evolution": false,

    "deduplication.policy": "off"
  }
}
EOF"""
    )
    # "topics": "SERVER5432.test.users",
    sink_settings_transfer_command_confluent = (
        """cat <<EOF | curl --request POST --url "http://sink:8083/connectors" --header 'Content-Type: application/json' --data @-
  {
    "name": "sink-connector",
    "config": {
      "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
      "tasks.max": "100","""
        + f'"topics": "{topics}",'
        + """
      "clickhouse.topic2table.map": "",
      "clickhouse.server.url": "clickhouse",
      "clickhouse.server.user": "root",
      "clickhouse.server.pass": "root",
      "clickhouse.server.database": "test",
      "clickhouse.server.port": 8123,
      "clickhouse.table.name": "users",
      "key.converter": "io.confluent.connect.avro.AvroConverter",
      "value.converter": "io.confluent.connect.avro.AvroConverter",
      "key.converter.schema.registry.url": "http://schemaregistry:8081",
      "value.converter.schema.registry.url":"http://schemaregistry:8081",

      "store.kafka.metadata": true,
      "topic.creation.default.partitions": 6,

      "store.raw.data": false,
      "store.raw.data.column": "raw_data",

      "metrics.enable": true,
      "metrics.port": 8084,
      "buffer.flush.time.ms": 500,
      "thread.pool.size": 1,
      "fetch.min.bytes": 52428800,

    "enable.kafka.offset": false,

    "replacingmergetree.delete.column": "_sign","""
        + f'"auto.create.tables": {auto_create_tables_local},'
        """
    "schema.evolution": false,

    "deduplication.policy": "off",
    
    "metadata.max.age.ms" : 10000
  }
}
EOF"""
    )

    try:
        with Given(
            "I start sink connector",
            description="""Sending sink settings push command on bash_tools""",
        ):
            node.cmd(f"{sink_settings_transfer_command_confluent}")

        yield
    finally:
        with Finally("I delete sink and debezium connections"):
            with By("deleteing sink connector", flags=TE):
                node.cmd(
                    'curl -X DELETE -H "Accept:application/json" "http://sink:8083/connectors/sink-connector" '
                    "2>/dev/null | jq ."
                )


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
                f"DROP TABLE IF EXISTS test.{name};"
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
            node.query(f"DROP TABLE IF EXISTS test.{name};")


@TestStep(Given)
def create_all_data_types_table(
    self, table_name=None, node=None, manual_ch_table_create=False
):
    """Step to create table with all data types."""
    if node is None:
        node = self.context.cluster.node("clickhouse")
    if table_name is None:
        table_name = "users"

    with Given(f"I create MySQL table {table_name} with all data types)"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT AUTO_INCREMENT,"
            f"D4 DECIMAL(2,1) NOT NULL, D5 DECIMAL(30, 10) NOT NULL,"
            f" Doublex DOUBLE NOT NULL,"
            f" x_date DATE NOT NULL,"
            f"x_datetime6 DATETIME(6) NOT NULL,"
            f"x_time TIME NOT NULL,"
            f"x_time6 TIME(6) NOT NULL,"
            f"Intmin INT NOT NULL, Intmax INT NOT NULL,"
            f"UIntmin INT UNSIGNED NOT NULL, UIntmax INT UNSIGNED NOT NULL,"
            f"BIGIntmin BIGINT NOT NULL,BIGIntmax BIGINT NOT NULL,"
            f"UBIGIntmin BIGINT UNSIGNED NOT NULL,UBIGIntmax BIGINT UNSIGNED NOT NULL,"
            f"TIntmin TINYINT NOT NULL,TIntmax TINYINT NOT NULL,"
            f"UTIntmin TINYINT UNSIGNED NOT NULL,UTIntmax TINYINT UNSIGNED NOT NULL,"
            f"SIntmin SMALLINT NOT NULL,SIntmax SMALLINT NOT NULL,"
            f"USIntmin SMALLINT UNSIGNED NOT NULL,USIntmax SMALLINT UNSIGNED NOT NULL,"
            f"MIntmin MEDIUMINT NOT NULL,MIntmax MEDIUMINT NOT NULL,"
            f"UMIntmin MEDIUMINT UNSIGNED NOT NULL,UMIntmax MEDIUMINT UNSIGNED NOT NULL,"
            f" x_char CHAR NOT NULL,"
            f" x_text TEXT NOT NULL,"
            f" x_varchar VARCHAR(4) NOT NULL,"
            f" x_Blob BLOB NOT NULL,"
            f" x_Mediumblob MEDIUMBLOB NOT NULL,"
            f" x_Longblob LONGBLOB NOT NULL,"
            f" x_binary BINARY NOT NULL,"
            f" x_varbinary VARBINARY(4) NOT NULL,"
            f" PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )
    if manual_ch_table_create:
        with And(f"I create ClickHouse replica test.{table_name}"):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
                f"(id Int32,"
                f" D4 DECIMAL(2,1), D5 DECIMAL(30, 10),"
                f" Doublex Float64,"
                f" x_date Date,"
                f" x_datetime6 String,"
                f" x_time String,"
                f" x_time6 String,"
                f" Intmin Int32, Intmax Int32,"
                f" UIntmin UInt32, UIntmax UInt32,"
                f" BIGIntmin Int64, BIGIntmax Int64,"
                f" UBIGIntmin UInt64, UBIGIntmax UInt64,"
                f" TIntmin Int8, TIntmax Int8,"
                f" UTIntmin UInt8, UTIntmax UInt8,"
                f" SIntmin Int16, SIntmax Int16,"
                f" USIntmin UInt16, USIntmax UInt16,"
                f" MIntmin Int32, MIntmax Int32,"
                f" UMIntmin UInt32, UMIntmax UInt32,"
                f" x_char LowCardinality(String),"
                f" x_text String,"
                f" x_varchar String,"
                f" x_Blob String,"
                f" x_Mediumblob String,"
                f" x_Longblob String,"
                f" x_binary String,"
                f" x_varbinary String)"
                f"ENGINE = MergeTree "
                f"PRIMARY KEY id ORDER BY id SETTINGS "
                f"index_granularity = 8192;",
            )


@TestStep(Given)
def create_all_data_types_table_nullable(
    self, table_name=None, node=None, manual_ch_table_create=False
):
    """Step to create table with all data types."""
    if node is None:
        node = self.context.cluster.node("clickhouse")
    if table_name is None:
        table_name = "users"
    with Given(f"I create MySQL table {table_name} with all data types)"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT AUTO_INCREMENT,"
            f"D4 DECIMAL(2,1), D5 DECIMAL(30, 10),"
            f" Doublex DOUBLE,"
            f" x_date DATE,"
            f"x_datetime6 DATETIME(6),"
            f"x_time TIME,"
            f"x_time6 TIME(6),"
            f"Intmin INT, Intmax INT,"
            f"UIntmin INT UNSIGNED, UIntmax INT UNSIGNED,"
            f"BIGIntmin BIGINT,BIGIntmax BIGINT,"
            f"UBIGIntmin BIGINT UNSIGNED,UBIGIntmax BIGINT UNSIGNED,"
            f"TIntmin TINYINT,TIntmax TINYINT,"
            f"UTIntmin TINYINT UNSIGNED,UTIntmax TINYINT UNSIGNED,"
            f"SIntmin SMALLINT,SIntmax SMALLINT,"
            f"USIntmin SMALLINT UNSIGNED,USIntmax SMALLINT UNSIGNED,"
            f"MIntmin MEDIUMINT,MIntmax MEDIUMINT,"
            f"UMIntmin MEDIUMINT UNSIGNED,UMIntmax MEDIUMINT UNSIGNED,"
            f" x_char CHAR,"
            f" x_text TEXT,"
            f" x_varchar VARCHAR(4),"
            f" x_Blob BLOB,"
            f" x_Mediumblob MEDIUMBLOB,"
            f" x_Longblob LONGBLOB,"
            f" x_binary BINARY,"
            f" x_varbinary VARBINARY(4),"
            f" PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )
    if manual_ch_table_create:
        with And(f"I create ClickHouse replica test.{table_name}"):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
                f"(id Int32,"
                f" D4 Nullable(DECIMAL(2,1)), D5 Nullable(DECIMAL(30, 10)),"
                f" Doublex Float64,"
                f" x_date Date,"
                f" x_datetime6 String,"
                f" x_time String,"
                f" x_time6 String,"
                f" Intmin Int32, Intmax Int32,"
                f" UIntmin UInt32, UIntmax UInt32,"
                f" BIGIntmin Int64, BIGIntmax Int64,"
                f" UBIGIntmin UInt64, UBIGIntmax UInt64,"
                f" TIntmin Int8, TIntmax Int8,"
                f" UTIntmin UInt8, UTIntmax UInt8,"
                f" SIntmin Int16, SIntmax Int16,"
                f" USIntmin UInt16, USIntmax UInt16,"
                f" MIntmin Int32, MIntmax Int32,"
                f" UMIntmin UInt32, UMIntmax UInt32,"
                f" x_char LowCardinality(String),"
                f" x_text String,"
                f" x_varchar String,"
                f" x_Blob String,"
                f" x_Mediumblob String,"
                f" x_Longblob String,"
                f" x_binary String,"
                f" x_varbinary Nullable(String))"
                f"ENGINE = MergeTree "
                f"PRIMARY KEY id ORDER BY id SETTINGS "
                f"index_granularity = 8192;",
            )


@TestStep(Given)
def sb_debizium_script_connector(self):
    """
    Sysbench debezium script start up
    :param self:
    :return:
    """
    try:
        time.sleep(10)
        with Given(
            "I start debezium connector",
            description="""Sending debezium settings push command on bash_tools
                    and wait message that they applied correct""",
        ):
            retry(self.context.cluster.node("bash-tools").cmd, timeout=100, delay=3)(
                f"./../manual_scripts/debezium-connector-setup-database.sh",
                message='{"error_code":409,"message":"Connector '
                'debezium-connector-sbtest already exists"}',
            )
        yield
    finally:
        time.sleep(5)
        with Finally("I delete debezium sysbench connections"):
            with By("deleting debezium connector", flags=TE):
                self.context.cluster.node("bash-tools").cmd(
                    'curl -X DELETE -H "Accept:application/json" "http://debezium:8083/connectors/'
                    'debezium-connector-sbtest" '
                    "2>/dev/null | jq ."
                )
            with And("Drop CH table"):
                self.context.cluster.node("clickhouse").query(
                    "DROP TABLE IF EXISTS test.sbtest1;"
                )


@TestStep(Given)
def insert(self, first_insert_id, last_insert_id, table_name):
    """
    Insert query step
    :param self:
    :param first_insert_id:
    :param last_insert_id:
    :param table_name:
    :return:
    """
    mysql = self.context.cluster.node("mysql-master")
    with Given(
        f"I insert {first_insert_id-last_insert_id} rows of data in MySql table"
    ):
        for i in range(first_insert_id, last_insert_id + 1):
            mysql.query(f"insert into {table_name} values ({i},2,'a','b')")


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
        node = current().context.node

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
    node.query(f"INSERT INTO {table_name} (col1,col2,col3) VALUES {insert_values_1}")
    node.query(f"INSERT INTO {table_name} (col1,col2,col3) VALUES {insert_values_2}")


@TestStep(Given)
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
        f"I delete {last_delete_id-first_delete_id} rows of data in MySql table"
    ):
        for i in range(first_delete_id, last_delete_id):
            mysql.query(f"DELETE FROM {table_name} WHERE id={i}")


@TestStep(Given)
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
        f"I update {last_update_id-first_update_id} rows of data in MySql table"
    ):
        for i in range(first_update_id, last_update_id):
            mysql.query(f"UPDATE {table_name} SET k=k+5 WHERE id={i};")


@TestStep(Given)
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


@TestStep(Given)
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
