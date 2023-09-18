#!/bin/bash

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/sink-connector-config.sh"

CONNECTOR_NAME="sink-connector-$1"

echo "******** ${CONNECTOR_NAME} *******"
# clickhouse-sink-connector params

CLICKHOUSE_HOST="clickhouse"
CLICKHOUSE_PORT=8123
CLICKHOUSE_USER="root"
CLICKHOUSE_PASSWORD="root"
CLICKHOUSE_TABLE="dummy"
DATABASE=$1
CLICKHOUSE_DATABASE="${DATABASE}"
TOPICS_TABLE_MAP="SERVER5432.test.employees_predated:employees"
BUFFER_COUNT=10000

if [[ $1 == "apicurio" ]]; then
      echo "APICURIO SCHEMA REGISTRY"
    cat <<EOF | curl --request POST --url "${CONNECTORS_MANAGEMENT_URL}" --header 'Content-Type: application/json' --data @-
    {
      "name": "${CONNECTOR_NAME}",
      "config": {
        "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
        "tasks.max": "10",
        "topics.regex": "SERVER5432.${DATABASE}.(.*)", 
        "clickhouse.topic2table.map": "${TOPICS_TABLE_MAP}",
        "clickhouse.server.url": "${CLICKHOUSE_HOST}",
        "clickhouse.server.user": "${CLICKHOUSE_USER}",
        "clickhouse.server.password": "${CLICKHOUSE_PASSWORD}",
        "clickhouse.server.database": "${CLICKHOUSE_DATABASE}",
        "clickhouse.server.port": ${CLICKHOUSE_PORT},
#        "clickhouse.table.name": "${CLICKHOUSE_TABLE}",
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
        "buffer.flush.time.ms": 500,
        "thread.pool.size": 1,
        "fetch.min.bytes": 52428800,

        "enable.kafka.offset": false,

        "replacingmergetree.delete.column": "_sign",

        "auto.create.tables": true,
        "schema.evolution": false,

        "deduplication.policy": "off"
        }
    }
EOF
else
 echo "Using confluent schema registry"
  cat <<EOF | curl --request POST --url "${CONNECTORS_MANAGEMENT_URL}" --header 'Content-Type: application/json' --data @-
  {
    "name": "${CONNECTOR_NAME}",
    "config": {
      "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
      "tasks.max": "10",
      "topics.regex": "SERVER5432.${DATABASE}.(.*)", 
      "clickhouse.topic2table.map": "${TOPICS_TABLE_MAP}",
      "clickhouse.server.url": "${CLICKHOUSE_HOST}",
      "clickhouse.server.user": "${CLICKHOUSE_USER}",
      "clickhouse.server.password": "${CLICKHOUSE_PASSWORD}",
      "clickhouse.server.database": "${CLICKHOUSE_DATABASE}",
      "clickhouse.server.port": ${CLICKHOUSE_PORT},
      "clickhouse.table.name": "${CLICKHOUSE_TABLE}",
      "key.converter": "io.confluent.connect.avro.AvroConverter",
      "value.converter": "io.confluent.connect.avro.AvroConverter",
      "key.converter.schema.registry.url": "http://schemaregistry:8081",
      "value.converter.schema.registry.url":"http://schemaregistry:8081",

      "store.kafka.metadata": true,
      "topic.creation.default.partitions": 1,

      "store.raw.data": false,
      "store.raw.data.column": "raw_data",

      "metrics.enable": true,
      "metrics.port": 8084,
      "buffer.flush.time.ms": 500,
      "thread.pool.size": 1,
      "fetch.min.bytes": 52428800,

      "enable.kafka.offset": false,

      "replacingmergetree.delete.column": "_sign",

      "auto.create.tables": true,
      "schema.evolution": false,

      "deduplication.policy": "off",

      "metadata.max.age.ms" : 10000
      }
  }
EOF

fi
# "replacingmergetree.delete.column": "sign_delete"
