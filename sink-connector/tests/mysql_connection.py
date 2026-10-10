import pymysql
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
        self.conn: pymysql.connections.Connection = None
        self.cursor = None

    def create_connection(self, auto_commit=True):

        try:
            # pymysql auto-negotiates the auth plugin, so there is no
            # equivalent of mysql.connector's auth_plugin='mysql_native_password'
            # kwarg to pass here.
            self.conn = pymysql.connect(host=self.db_host, database=self.db_name,
                                   user=self.db_user, password=self.db_pass, autocommit=auto_commit)

        except Exception as e:
             print("Error creating connection", e)

        return self.conn

    def get_column_names(self, sql):
        column_names = ''

        if self.conn.open:
            self.cursor = self.conn.cursor()

            self.cursor.execute(sql)
            for result in self.cursor:
                print(result)

            # pymysql has no cursor.column_names helper (mysql.connector-only);
            # build the same tuple of names from cursor.description instead.
            column_names = tuple(col[0] for col in self.cursor.description)


            if (self.conn and self.conn.open):
                self.conn.commit()

        return column_names

    def execute_sql(self, sql, data=None):

        result = None
        if self.conn.open:
            self.cursor = self.conn.cursor()

            try:
                if self.cursor:
                    if data:
                        self.cursor.execute(sql, data)
                    else:
                        self.cursor.execute(sql)

                    for result in self.cursor:
                        print(result)

                if (self.conn and self.conn.open):
                    self.conn.commit()
            except Exception as e:
                print("Error executing SQL", e)

        return result

    def get_connection(self) -> pymysql.connections.Connection:
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
