#!/bin/bash

if [ -z $1 ]; then
  DATABASE=world
else
  DATABASE=$1
fi

cd ../python
python db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database $DATABASE --tables_regex "^*" --debug_output
python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database $DATABASE --tables_regex "^*" --debug_output


diff out.city.mysql.txt out.city.ch.txt
diff out.country.mysql.txt out.country.ch.txt
diff out.countrylanguage.mysql.txt out.countrylanguage.ch.txt

rm -fr *.ch.txt
rm -fr *.mysql.txt