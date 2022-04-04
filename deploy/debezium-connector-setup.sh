#!/bin/bash

# Check
# https://debezium.io/documentation/reference/stable/connectors/mysql.html#_required_debezium_mysql_connector_configuration_properties
# for full list of properties

CONNECT_URL="http://127.0.0.1:8083/connectors"
CONNECTOR_NAME="test-connector"

MYSQL_HOST="mysql"
MYSQL_PORT="3306"
MYSQL_USER="root"
MYSQL_PASSWORD="root"
# Comma-separated list of regular expressions that match the databases for which to capture changes
MYSQL_DBS="test"
# Comma-separated list of regular expressions that match fully-qualified table identifiers of tables
MYSQL_TABLES=""
#KAFKA_BOOTSTRAP_SERVERS="one-node-cluster-0.one-node-cluster.redpanda.svc.cluster.local:9092"
KAFKA_BOOTSTRAP_SERVERS="kafka:9092"
KAFKA_TOPIC="schema-changes.test_db"

# Connector joins the MySQL database cluster as another server (with this unique ID) so it can read the binlog.
# By default, a random number between 5400 and 6400 is generated, though the recommendation is to explicitly set a value.
DATABASE_SERVER_ID="5432"
# Unique across all other connectors, used as a prefix for Kafka topic names for events emitted by this connector.
# Alphanumeric characters, hyphens, dots and underscores only.
DATABASE_SERVER_NAME="SERVER5432"

cat <<EOF | curl --request POST --url "${CONNECT_URL}" --header 'Content-Type: application/json' --data @-
{
  "name": "${CONNECTOR_NAME}",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",

    "database.hostname": "${MYSQL_HOST}",
    "database.port": "${MYSQL_PORT}",
    "database.user": "${MYSQL_USER}",
    "database.password": "${MYSQL_PASSWORD}",
    "database.server.id": "${DATABASE_SERVER_ID}",
    "database.server.name": "${DATABASE_SERVER_NAME}",
    "database.include.list": "${MYSQL_DBS}",
    "table.include.list": "${MYSQL_TABLES}",
    "database.history.kafka.bootstrap.servers": "${KAFKA_BOOTSTRAP_SERVERS}",
    "database.history.kafka.topic": "${KAFKA_TOPIC}"
  }
}
EOF

echo