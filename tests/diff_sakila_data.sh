cd ../python
#python db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database sakila --tables_regex "^*" --debug_output
#python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database sakila --tables_regex "^*" --debug_output

diff out.customer.mysql.txt out.customer.ch.txt
diff out.payment.mysql.txt out.payment.ch.txt
diff out.rental.mysql.txt out.rental.ch.txt
#diff ../python/