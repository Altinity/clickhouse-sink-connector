use test;

alter table employees
add column `salary` UInt64,
add column num_years UInt8,
add column bonus UInt32,
add column small_value UInt16,
add column int_value UInt32,

add column discount Int64,
add column num_years_signed Int8,
add column bonus_signed Int32,
add column small_value_signed Int16,
add column int_value_signed Int32;

alter table employees add column last_modified_date_time String;
alter table employees add column last_access_time String;

alter table employees  add column `married_status` String;
alter table employees add column `perDiemRate` Decimal(30, 12);

alter table employees  add column `hourlyRate` Float64;
alter table employees add column `jobDescription` String;

alter table employees add column `updated_time` DateTime;