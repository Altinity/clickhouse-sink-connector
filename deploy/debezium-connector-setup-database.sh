#!/bin/bash

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/debezium-connector-config.sh"

CONNECTOR_NAME="debezium-connector-$1"

echo "*********** ${CONNECTOR_NAME} **************"
# Debezium parameters. Check
# https://debezium.io/documentation/reference/stable/connectors/mysql.html#_required_debezium_mysql_connector_configuration_properties
# for the full list of available properties

MYSQL_HOST="mysql-master"
MYSQL_PORT="3306"
MYSQL_USER="root"
MYSQL_PASSWORD="root"
# Comma-separated list of regular expressions that match the databases for which to capture changes
DATABASE=$1
MYSQL_DBS="${DATABASE}"
# Comma-separated list of regular expressions that match fully-qualified table identifiers of tables
MYSQL_TABLES=""
#KAFKA_BOOTSTRAP_SERVERS="one-node-cluster-0.one-node-cluster.redpanda.svc.cluster.local:9092"
KAFKA_BOOTSTRAP_SERVERS="kafka:9092"
KAFKA_TOPIC="schema-changes.${DATABASE}"

# Connector joins the MySQL database cluster as another server (with this unique ID) so it can read the binlog.
# By default, a random number between 5400 and 6400 is generated, though the recommendation is to explicitly set a value.
DATABASE_SERVER_ID="5432"
# Unique across all other connectors, used as a prefix for Kafka topic names for events emitted by this connector.
# Alphanumeric characters, hyphens, dots and underscores only.
DATABASE_SERVER_NAME="SERVER5432-${DATABASE}"

if [[ $2 == "apicurio" ]]; then
  echo "APICURIO SCHEMA REGISTRY"
  A
  ######       Connector  registration ######
  cat <<EOF | curl --request POST --url "${CONNECTORS_MANAGEMENT_URL}" --header 'Content-Type: application/json' --data @-
  {
    "name": "${CONNECTOR_NAME}",
    "config": {
      "connector.class": "io.debezium.connector.mysql.MySqlConnector",
      "tasks.max": "1",
      "snapshot.mode": "initial",
      "snapshot.locking.mode": "minimal",
      "snapshot.delay.ms": 10000,
      "include.schema.changes":"true",
      "database.hostname": "${MYSQL_HOST}",
      "database.port": "${MYSQL_PORT}",
      "database.user": "${MYSQL_USER}",
      "database.password": "${MYSQL_PASSWORD}",
      "database.server.id": "${DATABASE_SERVER_ID}",
      "database.server.name": "${DATABASE_SERVER_NAME}",
      "database.whitelist": "${MYSQL_DBS}",
      "database.allowPublicKeyRetrieval":"true",

      "database.history.kafka.bootstrap.servers": "${KAFKA_BOOTSTRAP_SERVERS}",
      "database.history.kafka.topic": "${KAFKA_TOPIC}",

      "key.converter": "io.apicurio.registry.utils.converter.AvroConverter",
      "value.converter": "io.apicurio.registry.utils.converter.AvroConverter",

      "key.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
      "key.converter.apicurio.registry.auto-register": "true",
      "key.converter.apicurio.registry.find-latest": "true",

      "value.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
      "value.converter.apicurio.registry.auto-register": "true",
      "value.converter.apicurio.registry.find-latest": "true",

      "topic.creation.$alias.partitions": 1,
      "topic.creation.default.replication.factor": 1,
      "topic.creation.default.partitions": 1,

      "provide.transaction.metadata": "true"
    }
  }
EOF
else
 echo "Using confluent schema registry"
#https://debezium.io/documentation/reference/stable/configuration/avro.html
      cat <<EOF | curl --request POST --url "${CONNECTORS_MANAGEMENT_URL}" --header 'Content-Type: application/json' --data @-
      {
        "name": "${CONNECTOR_NAME}",
        "config": {
          "connector.class": "io.debezium.connector.mysql.MySqlConnector",
          "tasks.max": "1",
          "snapshot.mode": "initial",
          "snapshot.locking.mode": "minimal",
          "snapshot.delay.ms": 1,
          "include.schema.changes":"true",
          "include.schema.comments": "true",
          "database.hostname": "${MYSQL_HOST}",
          "database.port": "${MYSQL_PORT}",
          "database.user": "${MYSQL_USER}",
          "database.password": "${MYSQL_PASSWORD}",
          "database.server.name": "${DATABASE_SERVER_NAME}",
          "database.whitelist": "${MYSQL_DBS}",
          "database.allowPublicKeyRetrieval":"true",
          "database.history.kafka.bootstrap.servers": "${KAFKA_BOOTSTRAP_SERVERS}",
          "database.history.kafka.topic": "${KAFKA_TOPIC}",

          "key.converter": "io.confluent.connect.avro.AvroConverter",
          "value.converter": "io.confluent.connect.avro.AvroConverter",

          "key.converter.schema.registry.url": "http://schemaregistry:8081",
          "value.converter.schema.registry.url":"http://schemaregistry:8081",

          "topic.creation.$alias.partitions": 6,
          "topic.creation.default.replication.factor": 1,
          "topic.creation.default.partitions": 6,

          "provide.transaction.metadata": "true"
        }
      }
EOF
fi
#binary.handling.mode
