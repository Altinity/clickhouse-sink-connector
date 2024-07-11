#!/bin/bash

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/sink-connector-config.sh"

# clickhouse-sink-connector params

CLICKHOUSE_HOST="clickhouse"
CLICKHOUSE_PORT=8123
CLICKHOUSE_USER="root"
CLICKHOUSE_PASSWORD="root"
CLICKHOUSE_TABLE="employees"
CLICKHOUSE_DATABASE="test"

BUFFER_COUNT=10000
TOPICS_TABLE_MAP="SERVER5432.test.employees_predated:employees"
#SERVER5432.transaction
if [[ $1 == "postgres" ]]; then
  TOPICS="SERVER5432.public.Employee"
else
  TOPICS="SERVER5432.test.employees_predated, SERVER5432.test.customers, SERVER5432.test.products"
  #TOPICS="SERVER5432.test.ontime"
  TOPICS_TABLE_MAP="SERVER5432.test.employees_predated:employees, SERVER5432.test.products:products"
fi
#TOPICS="SERVER5432"

#"topics.regex": "SERVER5432.sbtest.(.*), SERVER5432.test.(.*)",

#"topics": "${TOPICS}",

if [[ $2 == "apicurio" ]]; then
      echo "APICURIO SCHEMA REGISTRY"
    cat <<EOF | curl --request POST --url "${CONNECTORS_MANAGEMENT_URL}" --header 'Content-Type: application/json' --data @-
    {
      "name": "${CONNECTOR_NAME}",
      "config": {
        "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
        "tasks.max": "10",

        "topics": "${TOPICS}",
        "clickhouse.topic2table.map": "${TOPICS_TABLE_MAP}",
        "clickhouse.server.url": "${CLICKHOUSE_HOST}",
        "clickhouse.server.user": "${CLICKHOUSE_USER}",
        "clickhouse.server.password": "${CLICKHOUSE_PASSWORD}",
        "clickhouse.server.port": ${CLICKHOUSE_PORT},
        "clickhouse.table.name": "${CLICKHOUSE_TABLE}",
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

        "auto.create.tables": false,
        "schema.evolution": false
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
      "tasks.max": "20",
      "topics": "${TOPICS}",
      "clickhouse.topic2table.map": "${TOPICS_TABLE_MAP}",
      "clickhouse.server.url": "${CLICKHOUSE_HOST}",
      "clickhouse.server.user": "${CLICKHOUSE_USER}",
      "clickhouse.server.password": "${CLICKHOUSE_PASSWORD}",
      "clickhouse.server.port": ${CLICKHOUSE_PORT},
      "clickhouse.table.name": "${CLICKHOUSE_TABLE}",
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

      "replacingmergetree.delete.column": "_sign",

      "auto.create.tables": true,
      "schema.evolution": false
      }
  }
EOF

fi
# "replacingmergetree.delete.column": "sign_delete"
