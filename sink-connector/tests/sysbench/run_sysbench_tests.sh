#!/bin/bash

set +x

SHORT=t:,h
LONG=test-name:,help
OPTS=$(getopt -a -n run_sysbench_tests --options $SHORT --longoptions $LONG -- "$@")

eval set -- "$OPTS"

help() {
  echo "./run_sysbench_tests.sh -t <test_name>, test_name should be one of the following
              bulk_insert, oltp_insert, oltp_delete, oltp_update_index, oltp_update_non_index oltp_insert_truncate"
}

### Supported Sysbench tests
#declare -a supported_test_names=("bulk_insert" "oltp_insert", "oltp_update_index", "oltp_delete", "oltp_update_index", "oltp_update_non_index")
supported_test_names=()
supported_test_names+=('bulk_insert')
supported_test_names+=('oltp_insert')
supported_test_names+=('oltp_delete')
supported_test_names+=('oltp_update_index')
supported_test_names+=('oltp_update_non_index')
supported_test_names+=('oltp_insert_truncate')

### Sysbench configuration
num_threads=1000
time=500 # IN Seconds

mysql_host=127.0.0.1
mysql_port=3306
mysql_username=root
mysql_password=root
mysql_db=sbtest

sysbench_command() {
sysbench \
/usr/share/sysbench/${1}.lua \
--report-interval=2 \
--threads=${num_threads} \
--rate=0 \
--time=${time} \
--db-driver=mysql \
--mysql-host=${mysql_host} \
--mysql-port=${mysql_port} \
--mysql-user=${mysql_username} \
--mysql-db=${mysql_db} \
--mysql-password=${mysql_password} \
--tables=1 \
--table-size=100 \
$2
}

while :
do
  case "$1" in
    -t | --test-name )
      test_name="$2"
      if grep -q ${test_name} <<< "${supported_test_names[@]}"; then
          echo "Supported test name"
          echo "Running Sysbench cleanup ...."
          eval sysbench_command ${test_name} "cleanup"
          sleep 2
          echo "Running Sysbench prepare ...."
          eval sysbench_command ${test_name} "prepare"
          sleep 2
          echo "Running Sysbench run ...."
          eval sysbench_command ${test_name} "run"

      else
          echo "Not supported test_name"
          help
      fi
      shift 2
      ;;
    -h | --help)
      help
      exit 2
      ;;
    --)
      help
      shift;
      break
      ;;
    *)
      echo "Unexpected option: $1"
      help
      exit 2
      ;;
  esac
done

