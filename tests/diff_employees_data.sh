cd ../python
DATABASE=employees

python3 db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database $DATABASE --tables_regex "^*" --debug_output
python3 db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database $DATABASE --tables_regex "^*" --debug_output

for tableName in departments dept_emp dept_manager employees salaries titles
do
  echo " DIFF TABLE ****${tableName} **** "
  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
done
#diff ../python/

#rm -fr *.ch.txt
#rm -fr *.mysql.txt