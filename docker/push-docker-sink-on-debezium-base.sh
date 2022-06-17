#!/bin/bash

# Production docker image builder
set -e

docker tag altinity/clickhouse-kafka-sink-connector-on-debezium-base:latest altinity/clickhouse-sink-connector:latest
docker push altinity/clickhouse-sink-connector:latest