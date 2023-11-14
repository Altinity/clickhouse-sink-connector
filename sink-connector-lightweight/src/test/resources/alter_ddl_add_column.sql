create table ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int, `min value` int, `max value` int, `null value` int);
create table add_test(col1 varchar(255), col2 int, col3 int);

insert into ship_class values(1, "test_class", 20.2, 20.2, 1997, 1997, 1998, 2, 3, 1);
insert into ship_class values(2, "test_class", 20.2, 20.2, 1997, 1997, 1998, 2, 3, 1);

CREATE TABLE `test_ent` (
  `e_id` int NOT NULL AUTO_INCREMENT,
  `u_id` int unsigned NOT NULL,
  `date` date NOT NULL,
  `t_id` int unsigned NOT NULL,
  `tspan` int unsigned NOT NULL,
  `lock_status` smallint DEFAULT '0',
  `modified_ts` timestamp NULL DEFAULT NULL,
  `description` varchar(1024) CHARACTER SET utf8mb3 DEFAULT NULL,
  `start_time_hour` time DEFAULT NULL,
  `end_time_hour` time DEFAULT NULL,
  `invoiceId` int DEFAULT '0',
  `billable` binary(1) DEFAULT '1',
PRIMARY KEY (`e_id`),
KEY `user_id` (`u_id`,`date`),
KEY `test_ent_task_id_date` (`t_id`,`date`),
KEY `date_u` (`date`,`u_id`),
KEY `test_ent_entries_task_id_index` (`t_id`),
KEY `user_t_date` (`u_id`,`t_id`,`date`)
) ENGINE=InnoDB AUTO_INCREMENT=191099272 DEFAULT CHARSET=latin1;


insert into test_ent values(111, 222, '2023-01-01', 222, 222, 1, '2008-01-01 00:00:01', 'Test', '22:10', '23:10', 2, '');

CREATE TABLE dt
(
`timestamp` timestamp,
`json` varchar(100),
`event_id` int unsigned,
`sign` smallint
);

insert into dt values('2008-01-01 00:00:01', 'this is a test', 11, 2);