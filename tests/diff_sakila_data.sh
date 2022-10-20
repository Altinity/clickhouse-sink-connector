cd ../python

python3 db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database sakila --tables_regex "^*" --debug_output
python3 db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database sakila --tables_regex "^*" --debug_output


for tableName in actor address category city country customer film film_actor film_category film_text inventory language payment rental staff store
do
  echo " DIFF TABLE ****${tableName} **** "
  diff out.${tableName}.mysql.txt out.${tableName}.ch.txt
done
#diff ../python/

#rm -fr out*.ch.txt
#rm -fr out*.mysql.txt