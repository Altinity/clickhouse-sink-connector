#!/bin/bash

cat mysql_dump.sql | mysql --host=127.0.0.1 --port=3306 --user=root --password=root --database=test