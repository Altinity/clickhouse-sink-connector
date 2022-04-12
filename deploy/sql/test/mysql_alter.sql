alter table employees
add column salary bigint unsigned,
add column num_years tinyint unsigned,
add column bonus mediumint unsigned,
add column small_value smallint unsigned,
add column int_value int unsigned,

add column discount bigint,
add column num_years_signed tinyint,
add column bonus_signed mediumint,
add column small_value_signed smallint,
add column int_value_signed int,

add column last_modified_date_time DateTime,
add column last_access_time TIME;


insert into employees values(1333446, '1980-01-01', 'John', 'Doe', 'M', '2022-01-01', 18446744073709551615 , 255, 16777215,
 65535, 4294967295, 9223372036854775807,  127, 8388607, 32767, 2147483647, '2022-01-01 00:01:00', '01:02:03')

