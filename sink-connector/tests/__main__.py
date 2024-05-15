import time
import os

import mysql.connector
from mysql.connector import Error
from faker import Faker

Faker.seed(33422)

fake = Faker()

create_table_sql = """
CREATE TABLE `people` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `first_name` varchar(50) COLLATE utf8_unicode_ci NOT NULL,
  `last_name` varchar(50) COLLATE utf8_unicode_ci NOT NULL,
  `email` varchar(100) COLLATE utf8_unicode_ci NOT NULL,
  `zipcode` int(5) NOT NULL,
  `city` varchar(100) COLLATE utf8_unicode_ci NOT NULL,
  `country` varchar(100) COLLATE utf8_unicode_ci NOT NULL,
  `birthdate` date NOT NULL,
  `added` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
"""

db_host = os.environ.get("DB_HOST", "localhost")
db_name = os.environ.get("DB_NAME")
db_user = os.environ.get("DB_USER_NAME")
db_pass = os.environ.get("DB_USER_PASSWORD")

try:
    conn = mysql.connector.connect(
        host=db_host, database=db_name, user=db_user, password=db_pass
    )

    if conn.is_connected():
        cursor = conn.cursor()

        try:
            cursor.execute(create_table_sql)
            print("Table created")
        except Exception as e:
            print("Error creating table", e)
        row = {}
        n = 0

        while True:
            n += 1
            row = [
                fake.first_name(),
                fake.last_name(),
                fake.email(),
                fake.postcode(),
                fake.city(),
                fake.country(),
                fake.date_of_birth(),
            ]

            cursor.execute(
                ' \
                INSERT INTO `people` (first_name, last_name, email, zipcode, city, country, birthdate) \
                VALUES ("%s", "%s", "%s", %s, "%s", "%s", "%s"); \
                '
                % (row[0], row[1], row[2], row[3], row[4], row[5], row[6])
            )

            if n % 100 == 0:
                print("iteration %s" % n)
                time.sleep(0.5)
                conn.commit()
except Error as e:
    print("error", e)
    pass
except Exception as e:
    print("Unknown error %s", e)
finally:
    # closing database connection.
    if conn and conn.is_connected():
        conn.commit()
        cursor.close()
        conn.close()
