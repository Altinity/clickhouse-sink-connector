#!/bin/sh

docker network create docker_default
docker-compose create mysql-master
docker-compose create clickhouse
docker-compose start mysql-master
docker-compose start clickhouse
