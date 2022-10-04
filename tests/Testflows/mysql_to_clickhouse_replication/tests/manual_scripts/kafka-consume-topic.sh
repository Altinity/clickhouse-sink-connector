#!/bin/bash

kafkacat -b 127.0.0.1:19092 -C -t SERVER5432.test.employees

kafkacat -b 127.0.0.1:19092 -C -e -q -t offset-storage-topic
kafkacat -b 127.0.0.1:19092 -C -e -q -t status-storage-topic
kafkacat -b 127.0.0.1:19092 -C -e -q -t config-storage-topic
kafkacat -b 127.0.0.1:19092 -C -e -q -t schema-changes.test_db
kafkacat -b 127.0.0.1:19092 -C -e -q -t SERVER5432
