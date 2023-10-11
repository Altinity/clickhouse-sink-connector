#!/bin/bash


#https://severalnines.com/database-blog/using-sysbench-generate-test-data-sharded-table-mysql
#
# --rate	Average transactions rate. The number specifies how many events (transactions) per seconds should be executed by all threads on average. 0 (default) means unlimited rate, i.e. events are executed as fast as possible

sysbench \
/usr/share/sysbench/bulk_insert.lua \
--report-interval=2 \
--threads=16 \
--rate=0 \
--time=60 \
--db-driver=mysql \
--mysql-host=mysql-master \
--mysql-port=3306 \
--mysql-user=root \
--mysql-db=sbtest \
--mysql-password=root \
--tables=1 \
--table-size=100 \
cleanup

sysbench \
/usr/share/sysbench/bulk_insert.lua \
--report-interval=2 \
--threads=16 \
--rate=0 \
--time=60 \
--db-driver=mysql \
--mysql-host=mysql-master \
--mysql-port=3306 \
--mysql-user=root \
--mysql-db=sbtest \
--mysql-password=root \
--tables=1 \
--table-size=100 \
prepare

sysbench \
/usr/share/sysbench/bulk_insert.lua \
--report-interval=2 \
--threads=16 \
--rate=0 \
--time=60 \
--db-driver=mysql \
--mysql-host=mysql-master \
--mysql-port=3306 \
--mysql-user=root \
--mysql-db=sbtest \
--mysql-password=root \
--tables=1 \
--table-size=100 \
run