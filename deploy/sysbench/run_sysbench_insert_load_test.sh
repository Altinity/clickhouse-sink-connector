#!/bin/bash


#https://severalnines.com/database-blog/using-sysbench-generate-test-data-sharded-table-mysql
#
# --rate	Average transactions rate. The number specifies how many events (transactions) per seconds should be executed by all threads on average. 0 (default) means unlimited rate, i.e. events are executed as fast as possible

sysbench \
/usr/share/sysbench/oltp_insert.lua \
--report-interval=2 \
--threads=5000 \
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

### TRY READ/WRITE LOADS ####
###oltp_read_write.lua