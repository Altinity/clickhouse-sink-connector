#!/bin/bash

set -x
# Validates the config in payload.json

CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source "${CUR_DIR}/debezium-connector-config.sh"

VALIDATE_URL="${CONNECT_URL}/connector-plugins/test-connector/config/validate"
curl -X PUT "${VALIDATE_URL}" -d@payload.json --header 'Content-Type: application/json'
