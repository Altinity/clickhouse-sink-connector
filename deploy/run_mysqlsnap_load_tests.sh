#!/bin/bash

mysql_master_ip_address=`docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' mysql-master`
mysqlslap -h $mysql_master_ip_address -u root -proot --create-schema=test --auto-generate-sql --concurrency=1000 --iterations=1000 --auto-generate-sql-load-type=insert