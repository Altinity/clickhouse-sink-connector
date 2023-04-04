#!/bin/bash

#--alter="ADD COLUMN random int(11) null DEFAULT null"
./gh-ost --debug \
--user="root" \
--password="root" \
--host=localhost \
--database="employees" \
--table="orders" \
--verbose \
--alter="ADD COLUMN random int(11)" \
--max-load=Threads_running=25 \
--critical-load=Threads_running=1000 \
--chunk-size=1000 \
--max-lag-millis=150000 \
--allow-master-master \
--cut-over=default \
--initially-drop-ghost-table \
--exact-rowcount \
--concurrent-rowcount \
--default-retries=120 \
--panic-flag-file=/tmp/ghost.panic.flag \
--allow-on-master \
[--execute]
