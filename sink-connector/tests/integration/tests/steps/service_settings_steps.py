from integration.requirements.requirements import *

from integration.helpers.common import *


@TestStep(Given)
def init_sink_connector(
    self,
    node=None,
    auto_create_tables="auto",
    auto_create_replicated_tables=False,
    topics="SERVER5432.sbtest.sbtest1,SERVER5432.test.users1,SERVER5432.test.users2,SERVER5432.test.users3, SERVER5432.test.users",
):
    """
    Initialize sink connector.
    """
    if node is None:
        node = self.context.cluster.node("bash-tools")

    if auto_create_replicated_tables:
        auto_create_replicated_tables = "true"
    else:
        auto_create_replicated_tables = "false"

    if auto_create_tables == "auto":
        auto_create_tables_local = "true"
    else:
        auto_create_tables_local = "false"

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
      "clickhouse.server.password": "root",
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
        + f'"auto.create.tables": {auto_create_tables_local},"auto.create.tables.replicated": {auto_create_replicated_tables},'
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
            command = node.cmd(f"{sink_settings_transfer_command_confluent}")
            assert command.output.strip().startswith(
                '{"name":"sink-connector"'
            ) or command.output.strip().startswith(
                '{"error_code":409,"message":"Connector sink-connector already exists"}'
            ), error()
        yield
    finally:
        with Finally("I delete sink and debezium connections"):
            with By("deleteing sink connector", flags=TE):
                node.cmd(
                    'curl -X DELETE -H "Accept:application/json" "http://sink:8083/connectors/sink-connector" '
                    "2>/dev/null | jq ."
                )


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
