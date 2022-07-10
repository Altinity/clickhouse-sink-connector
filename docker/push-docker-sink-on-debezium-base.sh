#!/bin/bash

# Production docker image builder
set -e

docker tag altinity/clickhouse-kafka-sink-connector-on-debezium-base:latest altinity/clickhouse-sink-connector:latest
docker push altinity/clickhouse-sink-connector:latest



#Kafka(Partitions - 10) -> SinkTask(10) -> topics, partitions -> Thread Pool(10) -> DB Connection ->

#put() -> Shared Queue(1000) -> Thread1- 200 -> CH , THread 2 -200 -> CH