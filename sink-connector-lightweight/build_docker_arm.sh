#!/bin/bash

docker login registry.gitlab.com
docker buildx create --name graviton --platform linux/arm64
docker buildx build --platform linux/arm64 . --tag registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:v1 --push --builder graviton