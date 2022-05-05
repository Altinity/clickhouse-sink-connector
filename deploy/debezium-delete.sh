#!/bin/bash

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/debezium-connector-config.sh"

echo "Deleting Source Connector"
curl -X DELETE -H "Accept:application/json" "${CONNECTOR_URL}" 2>/dev/null | jq .
