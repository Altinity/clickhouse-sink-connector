cd ../python

DATABASE=datatypes

python3 db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database $DATABASE --tables_regex "^*" --debug_output
python3 db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database $DATABASE --tables_regex "^*" --debug_output

table_names=$(docker exec -it mysql-master mysql -sN -uroot -proot -e "select TABLE_NAME from information_schema.tables where TABLE_SCHEMA='datatypes'")

## Remove the mysql warning
#table_names_formatted=$(echo ${table_names//mysql: [Warning]/})

for table_name_unformatted in $table_names
do
  table_name=$(echo $table_name_unformatted|tr -d '\r\n')
  mysql_file_name="$(pwd)/out.${table_name}.mysql.txt"
  ch_file_name="$(pwd)/out.${table_name}.ch.txt"

  #echo $mysql_file_name
  #echo $ch_file_name
  if test -f ${mysql_file_name}; then
    if test -f ${ch_file_name}; then
        echo " DIFF TABLE ****${table_name} **** "
        diff $(pwd)/out.${table_name}.mysql.txt $(pwd)/out.${table_name}.ch.txt
        if [[ $? != 0 ]]; then
          echo "string types diff failed"
          #exit 1
        fi
    #else
      #echo "ch_file_name file does not exist"
    fi
  #else
    #echo "$mysql_file_name file does not exist"
  fi

done
rm -fr out*.ch.txt
rm -fr out*.mysql.txt
#
#### string types
#for tableName in string_types_CHAR20_gbk string_types_CHAR20_latin1 string_types_CHAR20_utf8mb4 string_types_LONGTEXT_gbk string_types_LONGTEXT_latin1 string_types_LONGTEXT_utf8mb4 string_types_MEDIUMTEXT_gbk string_types_MEDIUMTEXT_latin1 string_types_MEDIUMTEXT_utf8mb string_types_TEXT_gbk string_types_TEXT_latin1 string_types_TEXT_utf8mb4 string_types_TINYTEXT
###numeric_types_FLOAT
#do
#  echo " DIFF TABLE ****${tableName} **** "
#  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
#  if [[ $? != 0 ]]; then
#    echo "string types diff failed"
#    exit 1
#  fi
#done
#
#### numeric types
#for tableName in numeric_types_DECIMAL_10_0 numeric_types_DECIMAL_40_20 numeric_types_DECIMAL_65_0 numeric_types_DECIMAL_65_30 numeric_types_DOUBLE
##numeric_types_FLOAT
#do
#  echo " DIFF TABLE ****${tableName} **** "
#  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
#  if [[ $? != 0 ]]; then
#    echo "numeric types diff failed"
#    exit 1
#  fi
#done
#
### BINARY TYPES
#for tableName in binary_types_BINARY binary_types_BLOB binary_types_LONGBLOB binary_types_MEDIUMBLOB binary_types_TINYBLOB binary_types_VARBINARY5
#do
#  echo " DIFF TABLE ****${tableName} **** "
#  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
#  if [[ $? != 0 ]]; then
#    echo "binary types diff failed"
#    exit 1
#  fi
#done
#
### DATE and DATETIME
#for tableName in temporal_types_DATE temporal_types_DATETIME temporal_types_DATETIME1 temporal_types_DATETIME2 temporal_types_DATETIME3 temporal_types_DATETIME4 temporal_types_DATETIME5 temporal_types_DATETIME6
#do
#  echo " DIFF TABLE ****${tableName} **** "
#  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
#  if [[ $? != 0 ]]; then
#    echo "Date/DateTime diff failed"
#    exit 1
#  fi
#done
#
## TIME and TIMESTAMP
#for tableName in temporal_types_TIME temporal_types_TIME1 temporal_types_TIME2 temporal_types_TIME3 temporal_types_TIME4 temporal_types_TIME5 temporal_types_TIME6
#do
#  echo " DIFF TABLE ****${tableName} **** "
#  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
#  if [[ $? != 0 ]]; then
#    echo "Time diff failed"
#    exit 1
#  fi
#done
#
## TIMESTAMP
#for tableName in temporal_types_TIMESTAMP temporal_types_TIMESTAMP1 temporal_types_TIMESTAMP2 temporal_types_TIMESTAMP3 temporal_types_TIMESTAMP4 temporal_types_TIMESTAMP5 temporal_types_TIMESTAMP6
#do
#  echo " DIFF TABLE ****${tableName} **** "
#  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
#  if [[ $? != 0 ]]; then
#      echo "**********Timestamp diff failed **************"
#      exit 1
#  fi
#done
#
## YEAR
#for tableName in temporal_types_YEAR temporal_types_YEAR4
#do
#  echo " DIFF TABLE ****${tableName} **** "
#  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
#  if [[ $? != 0 ]]; then
#      echo "**********YEAR diff failed **************"
#      exit 1
#  fi
#done
#

#diff ../python/

