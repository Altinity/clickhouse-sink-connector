#!/bin/bash


CONNECT_URL="http://127.0.0.1:18083/connectors"
CONNECTOR_NAME="sink-connector"

CLICKHOUSE_HOST="localhost"
CLICKHOUSE_PORT=8123
CLICKHOUSE_USER="root"
CLICKHOUSE_PASSWORD="root"
CLICKHOUSE_TABLE="employees"
CLICKHOUSE_DATABASE="test"

BUFFER_COUNT=10000

TOPICS="SERVER5432.test.employees"

cat <<EOF | curl --request POST --url "${CONNECT_URL}" --header 'Content-Type: application/json' --data @-
{
  "name": "${CONNECTOR_NAME}",
  "config": {
    "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
    "tasks.max": "1",
    "topics": "${TOPICS}",
    "buffer.count.records": "${BUFFER_COUNT}",
    "clickhouse.server.url": "${CLICKHOUSE_HOST}",
    "clickhouse.server.user": "${CLICKHOUSE_USER}",
    "clickhouse.server.pass": "${CLICKHOUSE_PASSWORD}",
    "clickhouse.server.database": "${CLICKHOUSE_DATABASE}",
    "clickhouse.server.port": ${CLICKHOUSE_PORT},
    "clickhouse.table.name": "${CLICKHOUSE_TABLE}"
  }
}
EOF

echo