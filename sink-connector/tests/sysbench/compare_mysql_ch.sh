#!/bin/bash

set +x

rm -fr MySQL.tsv
rm -fr CH.tsv

if [[ $1 == "bulk_insert" || $1 == "oltp_insert" ]]; then
  docker exec -it clickhouse clickhouse-client --multiquery -uroot --password root --query "use sbtest; select id ,k from sbtest.sbtest1 where _sign !=-1 order by id format TSV" | grep -v "<jemalloc>" >CH.tsv
else
  docker exec -it clickhouse clickhouse-client --multiquery -uroot --password root --query "use sbtest; select id  ,k, c, pad  from sbtest.sbtest1 final where _sign !=-1 order by id format TSV" | grep -v "<jemalloc>" >CH.tsv
fi
  docker exec -it mysql-master mysql -uroot -proot -B -N -e "select * from sbtest.sbtest1 order by id" | grep -v "Using a password on the command line interface" >MySQL.tsv

diff --strip-trailing-cr MySQL.tsv CH.tsv

#
#rm -fr MySQL.tsv
#rm -fr CH.tsv