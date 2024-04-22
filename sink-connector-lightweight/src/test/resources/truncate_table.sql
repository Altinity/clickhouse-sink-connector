create table ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int, `min value` int, `max value` int, `null value` int);

insert into ship_class values(1, "test_class", 20.2, 20.2, 1997, 1997, 1998, 2, 3, 1);
insert into ship_class values(2, "test_class", 20.2, 20.2, 1997, 1997, 1998, 2, 3, 1);

truncate table ship_class;