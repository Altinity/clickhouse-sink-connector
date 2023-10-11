#!/bin/bash


today_date=$(date +%F)

echo 'Tagging ${today_date} as latest'

docker tag altinity/clickhouse-kafka-sink-connector-on-debezium-base:${today_date} altinity/clickhouse-sink-connector:latest
docker push altinity/clickhouse-sink-connector:latest