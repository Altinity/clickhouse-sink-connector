#!/bin/bash

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/debezium-connector-config.sh"

CONNECTOR_URL="${CONNECTORS_MANAGEMENT_URL}/${CONNECTOR_NAME}-$1"

echo "Deleting Source Connector"
curl -X DELETE -H "Accept:application/json" "${CONNECTOR_URL}" 2>/dev/null | jq .
