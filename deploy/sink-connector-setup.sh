#!/bin/bash


CONNECT_URL="http://127.0.0.1:18083/connectors"
CONNECTOR_NAME="sink-connector"

CLICKHOUSE_HOST="mysql"
CLICKHOUSE_PORT="3306"
CLICKHOUSE_USER="root"
CLICKHOUSE_PASSWORD="root"

TOPICS="SERVER5432.test.employees"

cat <<EOF | curl --request POST --url "${CONNECT_URL}" --header 'Content-Type: application/json' --data @-
{
  "name": "${CONNECTOR_NAME}",
  "config": {
    "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
    "tasks.max": "1",
    "topics": "${TOPICS}",

    "database.hostname": "${CLICKHOUSE_HOST}",
    "database.port": "${CLICKHOUSE_PORT}",
    "database.user": "${CLICKHOUSE_USER}",
    "database.password": "${CLICKHOUSE_PASSWORD}"
  }
}
EOF

echo