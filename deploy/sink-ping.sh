#!/bin/bash

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/sink-connector-config.sh"

echo "Version:"
curl -H "Accept:application/json" "${CONNECT_URL}" 2>/dev/null | jq .

echo  "Connectors:"
curl -H "Accept:application/json" "${CONNECTORS_MANAGEMENT_URL}" 2>/dev/null | jq .

echo "Test connector status:"
curl -X GET -H "Accept:application/json" "${CONNECTOR_URL}" 2>/dev/null | jq .
