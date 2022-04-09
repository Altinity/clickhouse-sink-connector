-- BIGINT --
alter table employees add column salary bigint;

insert into employees values(15, '1980-01-01', 'John', 'Doe', 'M', '2022-01-01', 232323232)

select * from employees where salary is not null;

-- TINYINT --
alter table employees add column num_years tinyint;

-- DATETIME --
alter table employees add column last_modified_date_time datetime;

insert into employees values(1333445, '1980-01-01', 'John', 'Doe', 'M', '2022-01-01', 232323232, 12, '2022-01-01 00:01:00')

-- TIME --
alter table employees add column last_access_time TIME;

insert into employees values(1333445, '1980-01-01', 'John', 'Doe', 'M', '2022-01-01', 232323232, 12, '2022-01-01 00:01:00', '01:02:03" )
