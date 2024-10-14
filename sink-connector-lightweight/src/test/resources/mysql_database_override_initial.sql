create table `newtable`(col1 varchar(255) not null, col2 int, col3 int, primary key(col1));
insert into newtable values('a', 1, 1);

create database products;
create table products.prodtable(col1 varchar(255) not null, col2 int, col3 int, primary key(col1));

insert into products.prodtable values('a', 1, 1);
create database customers;
create table customers.custtable(col1 varchar(255) not null, col2 int, col3 int, primary key(col1));
insert into customers.custtable values('a', 1, 1);