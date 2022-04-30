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

    def get_insert_sql_query(self, table_name, col_names, column_length):

        values_template = ''
        for i in range(1, column_length + 1):
            values_template += f" %s, "

        return f"insert into {table_name} ({col_names}) values({values_template.rstrip(', ')})"

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
        sql_query = self.get_insert_sql_query(table_name,','.join(col_names), len(col_names))

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
        sql_query = self.get_insert_sql_query(table_name,','.join(col_names), len(col_names))

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
        sql_query = self.get_insert_sql_query(table_name,','.join(col_names), len(col_names))

        x = range(1, 1000000)
        for n in x:
            fake_row = FakeData.get_fake_products_row()
            print(fake_row)
            conn.execute_sql(sql_query, fake_row)

        conn.close()

    def test_multiple_tables(self):
        #self.generate_employees_records_with_datetime()
        #self.generate_employees_fake_records()
        #self.generate_products_fake_records()
        self.generate_products_fake_records()



if __name__ == '__main__':
    unittest.main()
