#!/bin/bash
set -e
echo "Pre-pull images for docker-compose"
docker pull library/hello-world
docker pull clickhouse/clickhouse-integration-test:28741
docker pull docker.io/bitnami/mysql:8.0
docker pull vectorized/redpanda:v22.1.3
docker pull apicurio/apicurio-registry-mem:2.0.0.Final
docker pull debezium/connect:1.9.2.Final
docker pull altinity/clickhouse-sink-connector:2022-07-26
docker pull registry.gitlab.com/altinity-public/container-images/test/bash-tools:2.0
echo "Save images"
docker save library/hello-world -o hello-world.dockerimage
docker save clickhouse/clickhouse-integration-test:28741 -o clickhouse-integration-test.28741.dockerimage
docker save docker.io/bitnami/mysql:8.0 -o mysql.8.0.dockerimage
docker save vectorized/redpanda:v22.1.3 -o redpanda.v22.1.3.dockerimage
docker save apicurio/apicurio-registry-mem:2.0.0.Final -o apicurio-registry-mem.2.0.0.Final.dockerimage
docker save debezium/connect:1.9.2.Final -o connect.192.Final.dockerimage
docker save altinity/clickhouse-sink-connector:2022-07-26 -o clickhouse-sink-connector.2022-07-26.dockerimage
docker save registry.gitlab.com/altinity-public/container-images/test/bash-tools:2.0 -o bash-tools.2.0.dockerimage
echo "Build image"
docker build -t registry.gitlab.com/altinity-qa/clickhouse/cicd/mysql-to-clickhouse-replication .
