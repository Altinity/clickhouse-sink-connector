#!/bin/bash


rm -fr MySQL.tsv
rm -fr CH.tsv

docker exec -it mysql_to_clickhouse_replication_env_clickhouse_1 clickhouse client -uroot --password root --query "select id  ,k    ,c , pad from sbtest.sbtest1 where sign !=-1 order by id format TSV" | grep -v "<jemalloc>" >CH.tsv
docker exec -it mysql-master mysql -uroot -proot -B -N -e "select * from sbtest.sbtest1 order by id" | grep -v "Using a password on the command line interface" >MySQL.tsv

diff MySQL.tsv CH.tsv > diff_file.txt