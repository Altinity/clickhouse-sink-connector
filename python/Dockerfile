FROM ubuntu 

RUN apt-get update && apt-get upgrade

RUN apt-get install -y apt-utils

RUN apt-get install -y apt-transport-https ca-certificates dirmngr
RUN apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 8919F6BD2B48D754

RUN echo "deb https://packages.clickhouse.com/deb stable main" | tee \
    /etc/apt/sources.list.d/clickhouse.list

RUN apt-get install -y wget && apt-get install -y clickhouse-client

RUN wget https://dev.mysql.com/get/Downloads/MySQL-Shell/mysql-shell-8.0.32-linux-glibc2.12-x86-64bit.tar.gz
RUN tar xvfz mysql-shell-8.0.32-linux-glibc2.12-x86-64bit.tar.gz
