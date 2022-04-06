#!/bin/sh

echo "Deleting Source Connector"
curl -X DELETE -H "Accept:application/json" localhost:8083/connectors/test-connector 2>/dev/null | jq .