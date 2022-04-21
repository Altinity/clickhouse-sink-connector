import mysql.connector
import os

"""
Class related to operation in MySQL
"""


class MySqlConnection:

    def __init__(self):
        self.db_host = os.environ.get('DB_HOST', 'localhost')
        self.db_name = os.environ.get('DB_NAME', 'test')
        self.db_user = os.environ.get('DB_USER_NAME', 'root')
        self.db_pass = os.environ.get('DB_USER_PASSWORD', 'root')
        self.conn = None
        self.cursor = None

    def create_connection(self):

        try:
            self.conn = mysql.connector.connect(host=self.db_host, database=self.db_name,
                                   user=self.db_user, password=self.db_pass)

        except Exception as e:
            print("Error creating connection", e)

        return self.conn

    def execute_sql(self, sql):

        if self.conn.is_connected():
            self.cursor = self.conn.cursor()

            try:
                if self.cursor:
                    self.cursor.execute(sql)
                    for result in self.cursor:
                        print(result)

                if (self.conn and self.conn.is_connected()):
                    self.conn.commit()
            except Exception as e:
                print("Error executing SQL", e)

    def close(self):
        # closing database connection.
            if self.cursor:
                self.cursor.close()
            if self.conn:
                self.conn.close()
