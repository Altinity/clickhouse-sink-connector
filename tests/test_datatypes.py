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



    def generate_employees_fake_records(self):
        '''
        Generate fake records for employees table.
        :return:
        '''
        conn = MySqlConnection()
        conn.create_connection()

        table_name = 'employees_predated'
        # Start with empty table
        conn.execute_sql(f'truncate table {table_name}')
        conn.execute_sql(f'select * from {table_name} limit 1')

        col_names = conn.get_column_names(f'select * from {table_name} limit 1')
        sql_query = conn.get_insert_sql_query(table_name,','.join(col_names), len(col_names))

        x = range(1, 1000000)
        for n in x:
            fake_row = FakeData.get_fake_row(n)
            print(fake_row)
            conn.execute_sql(sql_query, fake_row)

        conn.close()

    def generate_employees_records_with_datetime(self):
        conn = MySqlConnection()
        conn.create_connection()

        table_name = 'employees_predated'
        # Start with empty table
        conn.execute_sql(f'truncate table {table_name}')
        conn.execute_sql(f'select * from {table_name} limit 1')
        col_names = conn.get_column_names(f'select * from {table_name} limit 1')
        sql_query = conn.get_insert_sql_query(table_name,','.join(col_names), len(col_names))

        #9999-12-31 or 1900-01-01
        fake_row_ch_invalid_date_range = FakeData.get_fake_employees_row_with_out_of_range_datetime(122323, date(1900,2,2), date(1910, 1, 1))
        conn.execute_sql(sql_query, fake_row_ch_invalid_date_range)

        fake_row_ch_invalid_date_range = FakeData.get_fake_employees_row_with_out_of_range_datetime(122324, date(9999, 12, 30), date(9999, 12, 31))
        conn.execute_sql(sql_query, fake_row_ch_invalid_date_range)

        clickhouse_conn = ClickHouseConnection(host_name='localhost', username='root', password='root', database='test')
        clickhouse_conn.create_connection()
        result = clickhouse_conn.execute_sql('select * from products')

        print(result)
        conn.close()


    def test_duplicate_inserts(self):
        '''
        Test case to make sure duplicate records
        are not inserted
        :return:
        '''

        clickhouse_conn = ClickHouseConnection(host_name='localhost', username='root', password='root', database='test')
        clickhouse_conn.create_connection()
        clickhouse_conn.execute_sql('truncate table products')

        time.sleep(20)

        conn = MySqlConnection()
        conn.create_connection()

        table_name = 'products'
        # Start with empty table
        conn.execute_sql(f"truncate table {table_name}")
        conn.execute_sql(f"select * from {table_name} limit 1")

        col_names = conn.get_column_names(f'select * from {table_name} limit 1')
        sql_query_1 = conn.get_insert_sql_query(table_name,','.join(col_names), len(col_names))
        fake_row_1 = FakeData.get_fake_products_row()
        conn.execute_sql(sql_query_1, fake_row_1)

        conn.execute_sql(f"truncate table {table_name}")
        col_names = conn.get_column_names(f'select * from {table_name} limit 1')
        sql_query_2 = conn.get_insert_sql_query(table_name,','.join(col_names), len(col_names))
        conn.execute_sql(sql_query_2, fake_row_1)


        result = clickhouse_conn.execute_sql('select count(*) from products')

        print(result)
        conn.close()

    def generate_products_fake_records(self):
        '''
        Generate fake records for products table.
        :return:
        '''
        conn = MySqlConnection()
        conn.create_connection()

        table_name = 'products'
        # Start with empty table
        conn.execute_sql(f"truncate table {table_name}")
        conn.execute_sql(f"select * from {table_name} limit 1")

        col_names = conn.get_column_names(f'select * from {table_name} limit 1')
        sql_query = conn.get_insert_sql_query(table_name,','.join(col_names), len(col_names))

        x = range(1, 1000000)
        for n in x:
            fake_row = FakeData.get_fake_products_row()
            print(fake_row)
            conn.execute_sql(sql_query, fake_row)

        conn.close()

    def generate_update_records(self):

        conn = MySqlConnection()
        conn.create_connection()

        table_name = 'products'
        conn.execute_sql("update products set buyPrice=900 where productCode='S10_1678'");
        conn.execute_sql("update employees_predated set salary=900 where emp_no='10001'");
        conn.close()


    def generate_delete_records(self):

        conn = MySqlConnection()
        conn.create_connection()

        table_name = 'products'

        conn.execute_sql("delete from products where productCode='S10_1949'");
        conn.execute_sql("delete from employees_predated where emp_no =9999");

        conn.close()

    def test_json_data_type(self):
        conn = MySqlConnection()
        conn.create_connection()

        #conn.execute_sql("alter table products add column source json");

        conn.execute_sql("insert into products(source, productCode, productName, productLine, productScale,productVendor, "
                         "productDescription, quantityInStock, buyPrice, MSRP) "
                         "values('{\"key1\": \"value1\", \"key2\": \"value2\"}', 'S10_122222', 'TEST', 'TEST', '1:10', 'TEST', 'TEST', 100, 1.0, 1.50)")
        conn.close()

        # clickhouse_conn = ClickHouseConnection(host_name='localhost', username='root', password='root', database='test')
        # clickhouse_conn.create_connection()
        # clickhouse_conn.execute_sql('alter table products add column source String')
        # #conn.execute_sql("")

    def test_multiple_tables(self):
        #self.generate_employees_records_with_datetime()
        #self.generate_employees_fake_records()
        #self.generate_products_fake_records()
        #self.generate_products_fake_records()
        self.generate_update_records()
        self.test_json_data_type()
        #self.generate_delete_records()
        #self.test_duplicate_inserts()



if __name__ == '__main__':
    unittest.main()
