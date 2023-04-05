#!/bin/bash

# Production docker image builder
set -e

today_date=$(date +%F)
docker tag altinity/clickhouse-kafka-sink-connector-on-debezium-base:${today_date} altinity/clickhouse-sink-connector:${today_date}
docker push altinity/clickhouse-sink-connector:${today_date}



#Kafka(Partitions - 10) -> SinkTask(10) -> topics, partitions -> Thread Pool(10) -> DB Connection ->

#put() -> Shared Queue(1000) -> Thread1- 200 -> CH , THread 2 -200 -> CH