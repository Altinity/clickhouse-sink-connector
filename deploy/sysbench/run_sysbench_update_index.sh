#!/bin/bash

sysbench \
/usr/share/sysbench/oltp_update_index.lua \
--report-interval=2 \
--threads=16 \
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
--debug \
--verbosity=5 \
cleanup

sysbench \
/usr/share/sysbench/oltp_update_index.lua \
--report-interval=2 \
--threads=16 \
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
--debug \
--verbosity=5 \
prepare

sysbench \
/usr/share/sysbench/oltp_update_index.lua \
--report-interval=2 \
--threads=16 \
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
--debug \
--verbosity=5 \
run