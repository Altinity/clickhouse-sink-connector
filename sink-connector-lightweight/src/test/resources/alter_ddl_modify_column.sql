create table ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int);
create table add_test(col1 varchar(255), col2 int, col3 int);

create table office(office_id INT PRIMARY KEY, office_name VARCHAR(50) NOT NULL, office_address VARCHAR(255) NOT NULL, office_code int DEFAULT NULL);

-- Keyless on purpose: testAlterAddPrimaryKeyAndModifyNotNull adds the PRIMARY KEY
-- itself, as the first clause of a multi-clause ALTER. MySQL rejects ADD PRIMARY
-- KEY on a table that already has one ("Multiple primary key defined"), so that
-- step cannot run against `office`.
create table branch(branch_id int, branch_name varchar(50), branch_address varchar(255));


--insert into ship_class values(1, "test_class", 20.2, 20.2, 1997, 1997, 1998);
--insert into ship_class values(2, "test_class", 20.2, 20.2, 1997, 1997, 1998);
--  | MODIFY [COLUMN] col_name column_definition
--[FIRST | AFTER col_name]
--alter table ship_class modify column class_name int;
--alter table ship_class modify column tonange decimal(10,10);
--alter table add_test modify column col1 int, modify column col2 varchar(255);
--alter table add_test modify column col1 int default 0;
--alter table add_test modify column col3 int first;
--alter table add_test modify column col2 int after col3;
