#!/bin/bash


docker exec -it clickhouse clickhouse-client -uroot --password root --query "select id  ,k    ,c , pad from test.sbtest1 where sign !=-1 order by id format TSV" | grep -v "<jemalloc>" >CH.tsv
docker exec -it mysql-master mysql -uroot -proot -B -N -e "select * from sbtest.sbtest1 order by id" | grep -v "Using a password on the command line interface" >MySQL.tsv

diff MySQL.tsv CH.tsv