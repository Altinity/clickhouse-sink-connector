-- Init SYSBENCH tables ---
CREATE SCHEMA sbtest;

CREATE USER 'sbtest'@'%' IDENTIFIED BY 'passw0rd';
GRANT ALL PRIVILEGES ON sbtest.* TO 'sbtest'@'%';

use sbtest;
CREATE TABLE `sbtest1` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `k` int(11) NOT NULL DEFAULT '0',
  `c` char(120) NOT NULL DEFAULT '',
  `pad` char(60) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`,`k`)
  )
  PARTITION BY RANGE (k) (
    PARTITION p1 VALUES LESS THAN (499999),
    PARTITION p2 VALUES LESS THAN MAXVALUE
  );

  SET global general_log = 1;
  SET global log_output = 'table';
