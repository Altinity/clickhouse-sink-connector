
from clickhouse_driver import Client

class ClickHouseConnection:

    def __init__(self, host_name, username, password, database):
        self.host_name = host_name
        self.username = username
        self.password = password
        self.database = database

    def create_connection(self):
        self.client = Client(self.host_name,
                        user=self.username,
                        password=self.password,
                        database=self.database)

    def execute_sql(self, query):
        result = self.client.execute(query)
        return result