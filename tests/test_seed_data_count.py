import os
import time
import unittest
from datetime import date

from tests.clickhouse_connection import ClickHouseConnection
from tests.mysql_connection import MySqlConnection
from fake_data import FakeData

class MyTestCase(unittest.TestCase):

    conn = None

    @classmethod
    def setUpClass(cls):
        print("Setup class")


    @classmethod
    def tearDownClass(cls):
        print("Teardown class")


    def test_seed_data_count(self):
        mysql_conn = MySqlConnection()
        mysql_conn.create_connection()

        clickhouse_conn = ClickHouseConnection('localhost', 'root', 'root', 'test')
        clickhouse_conn.create_connection()

        ch_employees_count = clickhouse_conn.execute_sql("select count(*) from employees")
        mysql_employees_count = mysql_conn.execute_sql("select count(*) from employees_predated")

        ch_customers_count = clickhouse_conn.execute_sql("select count(*) from customers")
        mysql_customers_count = mysql_conn.execute_sql("select count(*) from customers")

        ch_products_count = clickhouse_conn.execute_sql("select count(*) from products")
        mysql_products_count = mysql_conn.execute_sql("select count(*) from products")

        mysql_conn.close()
        clickhouse_conn.close()

        self.assertEqual(ch_employees_count[0], mysql_employees_count)
        self.assertEqual(ch_customers_count[0], mysql_customers_count)
        self.assertEqual(ch_products_count[0], mysql_products_count)


    def test_sysbench_test_data_count(self):

        os.environ['DB_NAME'] = 'sbtest'
        mysql_conn = MySqlConnection()
        mysql_conn.create_connection()

        clickhouse_conn = ClickHouseConnection('localhost', 'root', 'root', 'test')
        clickhouse_conn.create_connection()

        clickhouse_conn.execute_sql('optimize table sbtest1 final')
        ch_count = clickhouse_conn.execute_sql("select count(*) from sbtest1")
        mysql_count = mysql_conn.execute_sql("select count(*) from sbtest1")

        mysql_conn.close()
        clickhouse_conn.close()

        self.assertEqual(ch_count[0], mysql_count)

    def test_sakila_test_data_count(self):

        os.environ['DB_NAME'] = 'sakila'
        mysql_conn = MySqlConnection()
        mysql_conn.create_connection()

        clickhouse_conn = ClickHouseConnection('localhost', 'root', 'root', 'test')
        clickhouse_conn.create_connection()

        tables = ['actor', 'address', 'category', 'city', 'country', 'customer', 'customer',
                   'film', 'film_actor', 'film_category',
                  'film_text', 'inventory', 'language', 'payment',
                  'rental','staff','store']
        for table in tables:
            if table != 'address':
                clickhouse_conn.execute_sql(f'optimize table {table} final')
                ch_count = clickhouse_conn.execute_sql(f"select count(*) from {table}")
                mysql_count = mysql_conn.execute_sql(f"select count(*) from {table}")
                print(f"table Name: {table}, MySQL Count: {mysql_count}, CH Count: {ch_count}")
                self.assertEqual(ch_count[0], mysql_count)

        mysql_conn.close()
        clickhouse_conn.close()

