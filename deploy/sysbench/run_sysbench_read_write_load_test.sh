#!/bin/bash

sysbench \
/usr/share/sysbench/oltp_read_write.lua \
--report-interval=2 \
--threads=500 \
--rate=0 \
--time=0 \
--db-driver=mysql \
--mysql-host=127.0.0.1 \
--mysql-port=3306 \
--mysql-user=root \
--mysql-db=sbtest \
--mysql-password=root \
--tables=1 \
--table-size=10000 \
run