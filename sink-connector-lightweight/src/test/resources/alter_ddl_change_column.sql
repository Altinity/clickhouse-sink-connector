create table ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int);
create table add_test(col1 varchar(255), col2 int, col3 int);

insert into ship_class values(1, "test_class", 20.2, 20.2, 1997, 1997, 1998);
insert into ship_class values(2, "test_class", 20.2, 20.2, 1997, 1997, 1998);

truncate table ship_class;
--drop table ship_class;

--alter table ship_class change column class_name class_name_new int;
--alter table ship_class change column tonange tonange_new decimal(10,10);
--alter table add_test change column col1 col1_new int, modify column col2 varchar(255);
--alter table add_test change column col1 int default 0;
--alter table add_test change column col3 int first;
--alter table add_test change column col2 int after col3;