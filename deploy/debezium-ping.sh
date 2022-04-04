#!/bin/bash

echo "Version:"
curl -H "Accept:application/json" 127.0.0.1:8083 2>/dev/null | jq .

echo  "Connectors:"
curl -H "Accept:application/json" 127.0.0.1:8083/connectors/ 2>/dev/null | jq .

echo "Test connector status:"
curl -X GET -H "Accept:application/json" localhost:8083/connectors/test-connector 2>/dev/null | jq .
