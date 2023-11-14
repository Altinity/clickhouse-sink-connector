cd ../python

DATABASE=sbtest

python3 db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database $DATABASE --tables_regex "^*" --debug_output
python3 db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database test --tables_regex "^*" --debug_output --sign_column=is_deleted --new_rmt=True --exclude-columns=['_version', 'is_deleted']

mysql_information_schema_query="select TABLE_NAME from information_schema.tables where TABLE_SCHEMA='$DATABASE'"
echo $mysql_information_schema_query

table_names=$(docker exec -it mysql-master mysql -sN -uroot -proot -e "select TABLE_NAME from information_schema.tables where TABLE_SCHEMA='sbtest'")

## Remove the mysql warning
#table_names_formatted=$(echo ${table_names//mysql: [Warning]/})

for table_name_unformatted in $table_names
do
  table_name=$(echo $table_name_unformatted|tr -d '\r\n')
  mysql_file_name="$(pwd)/out.${table_name}.mysql.txt"
  ch_file_name="$(pwd)/out.${table_name}.ch.txt"


  echo $mysql_file_name
  echo $ch_file_name
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
#rm -fr out*.ch.txt
#rm -fr out*.mysql.txt

