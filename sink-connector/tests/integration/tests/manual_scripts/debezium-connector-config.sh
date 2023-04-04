#!/bin/bash

# What Kafka-connect instance to setup Debezium into
CONNECT_HOST="debezium"
CONNECT_PORT="8083"
# Name of this connector instance
CONNECTOR_NAME="test-connector"

# URL of the connect instance
CONNECT_URL="http://${CONNECT_HOST}:${CONNECT_PORT}"
# URL of the connectors management
CONNECTORS_MANAGEMENT_URL="http://${CONNECT_HOST}:${CONNECT_PORT}/connectors"
# URL of this particular conneector instance
CONNECTOR_URL="${CONNECTORS_MANAGEMENT_URL}/${CONNECTOR_NAME}"
