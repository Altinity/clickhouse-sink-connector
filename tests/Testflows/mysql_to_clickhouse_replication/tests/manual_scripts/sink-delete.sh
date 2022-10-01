#!/bin/bash

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/sink-connector-config.sh"

echo "Deleting Sink Connector"
curl -X DELETE -H "Accept:application/json" "${CONNECTOR_URL}" 2>/dev/null | jq .
