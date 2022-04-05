#!/bin/sh

echo "Deleting Sink Connector"
curl -X DELETE -H "Accept:application/json" localhost:18083/connectors/sink-connector 2>/dev/null | jq .