import unittest

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

    def get_insert_sql_query(self, col_names, column_length):

        values_template = ''
        for i in range(1, column_length + 1):
            values_template += f" %s, "

        return f"insert into employees ({col_names}) values({values_template.rstrip(', ')})"

    def test_something(self):
        conn = MySqlConnection()
        conn.create_connection()
        conn.execute_sql("select * from employees limit 1")

        col_names = conn.get_column_names('select * from employees limit 1')

     #   conn.close()

        sql_query = self.get_insert_sql_query(','.join(col_names), len(col_names))

        print(sql_query)

        x = range(1, 1000000)
        for n in x:
            fake_row = FakeData.get_fake_row(n)
            print(fake_row)
            conn.execute_sql(sql_query, fake_row)

        conn.close()
        #
        # while True:
        #     n += 1
        #
        #     fake_row = FakeData.get_fake_row(n)
        #     print(fake_row)

    def insert_fake_records(self):
        conn = MySqlConnection()
        conn.create_connection()
        row = {}
        n = 0

        while True:
            n += 1

            fake_row = FakeData.get_fake_row(n)
            print(fake_row)
            conn.execute_sql()
        #
        # cursor.execute(' \
        #         INSERT INTO `people` (first_name, last_name, email, zipcode, city, country, birthdate) \
        #         VALUES ("%s", "%s", "%s", %s, "%s", "%s", "%s"); \
        #         ' % (row[0], row[1], row[2], row[3], row[4], row[5], row[6]))
        #
        # if n % 100 == 0:
        #     print("iteration %s" % n)
        #     time.sleep(0.5)
        #     conn.commit()

if __name__ == '__main__':
    unittest.main()
