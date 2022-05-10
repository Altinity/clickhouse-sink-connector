import mysql.connector
import os

from mysql.connector import MySQLConnection

"""
Class related to operation in MySQL
"""


class MySqlConnection:

    def __init__(self):
        self.db_host = os.environ.get('DB_HOST', 'localhost')
        self.db_name = os.environ.get('DB_NAME', 'test')
        self.db_user = os.environ.get('DB_USER_NAME', 'root')
        self.db_pass = os.environ.get('DB_USER_PASSWORD', 'root')
        self.conn:MySQLConnection = None
        self.cursor = None

    def create_connection(self, auto_commit=True):

        try:
            self.conn = mysql.connector.connect(host=self.db_host, database=self.db_name,
                                   user=self.db_user, password=self.db_pass, autocommit=auto_commit)

        except Exception as e:
            print("Error creating connection", e)

        return self.conn

    def get_column_names(self, sql):
        column_names = ''

        if self.conn.is_connected:
            self.cursor = self.conn.cursor()

            self.cursor.execute(sql)
            for result in self.cursor:
                print(result)

            column_names = self.cursor.column_names


            if (self.conn and self.conn.is_connected()):
                self.conn.commit()

        return column_names

    def execute_sql(self, sql, data=None):

        if self.conn.is_connected():
            self.cursor = self.conn.cursor()

            try:
                if self.cursor:
                    if data:
                        self.cursor.execute(sql, data)
                    else:
                        self.cursor.execute(sql)

                    for result in self.cursor:
                        print(result)

                if (self.conn and self.conn.is_connected()):
                    self.conn.commit()
            except Exception as e:
                print("Error executing SQL", e)

    def get_connection(self) -> MySQLConnection:
        return self.conn

    def get_insert_sql_query(self, table_name, col_names, column_length):

        values_template = ''
        for i in range(1, column_length + 1):
            values_template += f" %s, "

        return f"insert into {table_name} ({col_names}) values({values_template.rstrip(', ')})"

    def close(self):
        # closing database connection.
            if self.cursor:
                self.cursor.close()
            if self.conn:
                self.conn.close()
