#!/bin/bash
set -x


docker exec -it mysql-master mysql -uroot -proot -e "CREATE SCHEMA sbtest;"
docker exec -it mysql-master mysql -uroot -proot -e "CREATE USER 'sbtest'@'%' IDENTIFIED BY 'passw0rd';"
docker exec -it mysql-master mysql -uroot -proot -e "GRANT ALL PRIVILEGES ON sbtest.* TO 'sbtest'@'%';"

####
# Runs all the sysbench tests
# and compares the MySQL and CH results
####

#for sysbench_test in bulk_insert oltp_insert oltp_delete oltp_update_index oltp_update_non_index
for sysbench_test in oltp_update_index
#for sysbench_test in oltp_insert_truncate
do
  echo "*** Setup connectors"
  deploy/./configure_sysbench.sh
  echo "*** Running Sysbench tests ****"
  ./run_sysbench_tests.sh -t $sysbench_test
  result=$(./compare_mysql_ch.sh $sysbench_test)
  if [ -z "$result" ]
  then
    echo "**** Sysbench successful -****" $sysbench_test
  else
    echo "**** Sysbench failed *****"  $sysbench_test
  fi
done
