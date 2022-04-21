import unittest

from tests.mysql_connection import MySqlConnection


class MyTestCase(unittest.TestCase):

    conn = None

    @classmethod
    def setUpClass(cls):
        print("Setup class")


    @classmethod
    def tearDownClass(cls):
        print("Teardown class")

    def test_something(self):
        conn = MySqlConnection()
        conn.create_connection()
        conn.execute_sql("select * from employees limit 1")
        conn.close()

if __name__ == '__main__':
    unittest.main()
