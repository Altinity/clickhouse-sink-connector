#!/bin/bash

SCHEMA=public
./debezium-delete.sh public && ./debezium-connector-setup-database.sh public confluent postgres
sleep 10
./sink-delete.sh public && ./sink-connector-setup-database.sh public