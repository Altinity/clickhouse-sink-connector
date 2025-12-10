#!/bin/bash



docker exec -it clickhouse clickhouse-client --query "DROP DATABASE IF EXISTS altinity_sink_connector"
