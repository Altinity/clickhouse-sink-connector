import mysql.connector
from mysql.connector import Error

def create_connection(host_name, user_name, user_password, db_name):
    connection = None
    try:
        connection = mysql.connector.connect(
            host=host_name,
            user=user_name,
            passwd=user_password,
            database=db_name
        )
        print("Connection to MySQL DB successful")
    except Error as e:
        print(f"The error '{e}' occurred")
    return connection

def create_table(connection, query):
    cursor = connection.cursor()
    try:
        cursor.execute(query)
        connection.commit()
    except Error as e:
        print(f"The error '{e}' occurred")

# Connection details - replace with your actual details
host = "your_host"
user = "your_username"
password = "your_password"
database = "your_database"

connection = create_connection(host, user, password, database)

# Loop to create 15,000 tables
for i in range(1, 15001):
    create_table_query = f"""
    CREATE TABLE IF NOT EXISTS table{i} (
        id INT AUTO_INCREMENT, 
        data VARCHAR(255) NOT NULL, 
        PRIMARY KEY (id)
    ) ENGINE = InnoDB;
    """
    create_table(connection, create_table_query)
    print(f"Table {i} created")

if connection:
    connection.close()
    print("Connection closed")

