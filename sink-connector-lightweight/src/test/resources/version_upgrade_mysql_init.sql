-- MySQL init script for the version-upgrade CSV comparison test.
-- Creates test.customers with 5 initial rows representing the "pre-upgrade" state.
-- The connector will snapshot these rows, then the test will INSERT new rows and
-- verify that the old rows are NOT overwritten when querying with FINAL.
CREATE DATABASE IF NOT EXISTS test;

USE test;

CREATE TABLE customers (
  customerNumber int(11) NOT NULL,
  customerName varchar(50) NOT NULL,
  contactLastName varchar(50) NOT NULL,
  contactFirstName varchar(50) NOT NULL,
  phone varchar(50) NOT NULL,
  addressLine1 varchar(50) NOT NULL,
  addressLine2 varchar(50) DEFAULT NULL,
  city varchar(50) NOT NULL,
  state varchar(50) DEFAULT NULL,
  postalCode varchar(15) DEFAULT NULL,
  country varchar(50) NOT NULL,
  salesRepEmployeeNumber int(11) DEFAULT NULL,
  creditLimit decimal(10,2) DEFAULT NULL,
  PRIMARY KEY (customerNumber)
) ENGINE=InnoDB;

INSERT INTO customers VALUES
(103,'Atelier graphique','Schmitt','Carine','40.32.2555','54, rue Royale',NULL,'Nantes',NULL,'44000','France',1370,21000.00),
(112,'Signal Gift Stores','King','Jean','7025551838','8489 Strong St.',NULL,'Las Vegas','NV','83030','USA',1166,71800.00),
(114,'Australian Collectors, Co.','Ferguson','Peter','03 9520 4555','636 St Kilda Road','Level 3','Melbourne','Victoria','3004','Australia',1611,117300.00),
(119,'La Rochelle Gifts','Labrune','Janine','40.67.8555','67, rue des Cinquante Otages',NULL,'Nantes',NULL,'44000','France',1370,118200.00),
(121,'Baane Mini Imports','Bergulfsen','Jonas','07-98 9555','Erling Skakkes gate 78',NULL,'Stavern',NULL,'4110','Norway',1504,81700.00);
