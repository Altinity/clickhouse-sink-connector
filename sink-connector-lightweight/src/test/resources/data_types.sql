-- MySQL dump 10.13  Distrib 8.0.30, for Linux (x86_64)
--
-- Host: Database: data_types
-- ------------------------------------------------------
-- Server version	8.0.25-15

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
SET @MYSQLDUMP_TEMP_LOG_BIN = @@SESSION.SQL_LOG_BIN;
SET @@SESSION.SQL_LOG_BIN= 0;

--
-- Table structure for table `binary_types_BINARY`
--

DROP TABLE IF EXISTS `binary_types_BINARY`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `binary_types_BINARY` (
  `Type` varchar(50) NOT NULL,
  `Value` binary(1) NOT NULL,
  `Null_Value` binary(1) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `binary_types_BINARY`
--

LOCK TABLES `binary_types_BINARY` WRITE;
/*!40000 ALTER TABLE `binary_types_BINARY` DISABLE KEYS */;
INSERT INTO `binary_types_BINARY` VALUES ('BINARY',0xFF,NULL);
/*!40000 ALTER TABLE `binary_types_BINARY` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `binary_types_BLOB`
--

DROP TABLE IF EXISTS `binary_types_BLOB`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `binary_types_BLOB` (
  `Type` varchar(50) NOT NULL,
  `Value` blob NOT NULL,
  `Null_Value` blob,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `binary_types_BLOB`
--

LOCK TABLES `binary_types_BLOB` WRITE;
/*!40000 ALTER TABLE `binary_types_BLOB` DISABLE KEYS */;
INSERT INTO `binary_types_BLOB` VALUES ('BLOB',0xD3D530AC844A2F7347B20DFFEAAC218026E01F838380DE086BB0BA6105E73382DC8346A210D7968DFFFD5438D4B7D3415B422464815F5A129BD6D86DA649200FD6FD2BDB4A072561934EF681BE5D5AF07DDCDF89B3B8352DBFDF5EF7846FA3232F5AF99D2D964955B4384B757B6D444C9CA85BE8E3424AA454CA3728BF7405B4DB62ABDBFE4EC4BD6CF5CD575D251D6035E93113B1F0A89CB1DDE1652D55714E34F3E5C728C8D95A0DF7830DD4E3428875E4DC84B0A3E007E4E3D3E822F072BF12DD56EF756F46CF780107FF06EDB31D19FE9C523437BE917C3199F24F32F3460188ED9AA6CF416B44360CEC6151183B95E2DF536DE7E18B558BBAA90404B493,NULL);
/*!40000 ALTER TABLE `binary_types_BLOB` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `binary_types_LONGBLOB`
--

DROP TABLE IF EXISTS `binary_types_LONGBLOB`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `binary_types_LONGBLOB` (
  `Type` varchar(50) NOT NULL,
  `Value` longblob NOT NULL,
  `Null_Value` longblob,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `binary_types_LONGBLOB`
--

LOCK TABLES `binary_types_LONGBLOB` WRITE;
/*!40000 ALTER TABLE `binary_types_LONGBLOB` DISABLE KEYS */;
INSERT INTO `binary_types_LONGBLOB` VALUES ('LONGBLOB',0xD3D530AC844A2F7347B20DFFEAAC218026E01F838380DE086BB0BA6105E73382DC8346A210D7968DFFFD5438D4B7D3415B422464815F5A129BD6D86DA649200FD6FD2BDB4A072561934EF681BE5D5AF07DDCDF89B3B8352DBFDF5EF7846FA3232F5AF99D2D964955B4384B757B6D444C9CA85BE8E3424AA454CA3728BF7405B4DB62ABDBFE4EC4BD6CF5CD575D251D6035E93113B1F0A89CB1DDE1652D55714E34F3E5C728C8D95A0DF7830DD4E3428875E4DC84B0A3E007E4E3D3E822F072BF12DD56EF756F46CF780107FF06EDB31D19FE9C523437BE917C3199F24F32F3460188ED9AA6CF416B44360CEC6151183B95E2DF536DE7E18B558BBAA90404B493,NULL);
/*!40000 ALTER TABLE `binary_types_LONGBLOB` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `binary_types_MEDIUMBLOB`
--

DROP TABLE IF EXISTS `binary_types_MEDIUMBLOB`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `binary_types_MEDIUMBLOB` (
  `Type` varchar(50) NOT NULL,
  `Value` mediumblob NOT NULL,
  `Null_Value` mediumblob,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `binary_types_MEDIUMBLOB`
--

LOCK TABLES `binary_types_MEDIUMBLOB` WRITE;
/*!40000 ALTER TABLE `binary_types_MEDIUMBLOB` DISABLE KEYS */;
INSERT INTO `binary_types_MEDIUMBLOB` VALUES ('MEDIUMBLOB',0xD3D530AC844A2F7347B20DFFEAAC218026E01F838380DE086BB0BA6105E73382DC8346A210D7968DFFFD5438D4B7D3415B422464815F5A129BD6D86DA649200FD6FD2BDB4A072561934EF681BE5D5AF07DDCDF89B3B8352DBFDF5EF7846FA3232F5AF99D2D964955B4384B757B6D444C9CA85BE8E3424AA454CA3728BF7405B4DB62ABDBFE4EC4BD6CF5CD575D251D6035E93113B1F0A89CB1DDE1652D55714E34F3E5C728C8D95A0DF7830DD4E3428875E4DC84B0A3E007E4E3D3E822F072BF12DD56EF756F46CF780107FF06EDB31D19FE9C523437BE917C3199F24F32F3460188ED9AA6CF416B44360CEC6151183B95E2DF536DE7E18B558BBAA90404B493,NULL);
/*!40000 ALTER TABLE `binary_types_MEDIUMBLOB` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `binary_types_TINYBLOB`
--

DROP TABLE IF EXISTS `binary_types_TINYBLOB`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `binary_types_TINYBLOB` (
  `Type` varchar(50) NOT NULL,
  `Value` tinyblob NOT NULL,
  `Null_Value` tinyblob,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `binary_types_TINYBLOB`
--

LOCK TABLES `binary_types_TINYBLOB` WRITE;
/*!40000 ALTER TABLE `binary_types_TINYBLOB` DISABLE KEYS */;
INSERT INTO `binary_types_TINYBLOB` VALUES ('TINYBLOB',0xD3D530AC844A2F7347B20DFFEAAC218026E01F838380DE086BB0BA6105E73382DC8346A210D7968DFFFD5438D4B7D3415B422464815F5A129BD6D86DA649200FD6FD2BDB4A072561934EF681BE5D5AF07DDCDF89B3B8352DBFDF5EF7846FA3232F5AF99D2D964955B4384B757B6D444C9CA85B,NULL);
/*!40000 ALTER TABLE `binary_types_TINYBLOB` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `binary_types_VARBINARY5`
--

DROP TABLE IF EXISTS `binary_types_VARBINARY5`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `binary_types_VARBINARY5` (
  `Type` varchar(50) NOT NULL,
  `Value` varbinary(5) NOT NULL,
  `Null_Value` varbinary(5) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `binary_types_VARBINARY5`
--

LOCK TABLES `binary_types_VARBINARY5` WRITE;
/*!40000 ALTER TABLE `binary_types_VARBINARY5` DISABLE KEYS */;
INSERT INTO `binary_types_VARBINARY5` VALUES ('VARBINARy(5)',0xD3D530AC84,NULL);
/*!40000 ALTER TABLE `binary_types_VARBINARY5` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_BIGINT`
--

DROP TABLE IF EXISTS `integer_types_BIGINT`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_BIGINT` (
  `Type` varchar(50) NOT NULL,
  `Storage_Bytes` int NOT NULL,
  `Minimum_Value_Signed` bigint NOT NULL,
  `Minimum_Value_Unsigned` bigint unsigned NOT NULL,
  `Maximum_Value_Signed` bigint NOT NULL,
  `Maximum_Value_Unsigned` bigint unsigned NOT NULL,
  `Null_Value_Signed` bigint DEFAULT NULL,
  `Null_Value_Unsigned` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_BIGINT`
--

LOCK TABLES `integer_types_BIGINT` WRITE;
/*!40000 ALTER TABLE `integer_types_BIGINT` DISABLE KEYS */;
INSERT INTO `integer_types_BIGINT` VALUES ('BIGINT',8,-9223372036854775807,0,9223372036854775807,18446744073709551615,NULL,NULL);
/*!40000 ALTER TABLE `integer_types_BIGINT` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_BIT`
--

DROP TABLE IF EXISTS `integer_types_BIT`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_BIT` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` bit(1) NOT NULL,
  `Maximum_Value` bit(1) NOT NULL,
  `Null_Value` bit(1) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_BIT`
--

LOCK TABLES `integer_types_BIT` WRITE;
/*!40000 ALTER TABLE `integer_types_BIT` DISABLE KEYS */;
INSERT INTO `integer_types_BIT` VALUES ('BIT',0x00,0x01,NULL);
/*!40000 ALTER TABLE `integer_types_BIT` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_BIT2`
--

--DROP TABLE IF EXISTS `integer_types_BIT2`;
--/*!40101 SET @saved_cs_client     = @@character_set_client */;
--/*!50503 SET character_set_client = utf8mb4 */;
--CREATE TABLE `integer_types_BIT2` (
--  `Type` varchar(50) NOT NULL,
--  `Minimum_Value` bit(2) NOT NULL,
--  `Maximum_Value` bit(2) NOT NULL,
--  `Null_Value` bit(2) DEFAULT NULL,
--  PRIMARY KEY (`Type`)
--) ENGINE=InnoDB DEFAULT CHARSET=latin1;
--/*!40101 SET character_set_client = @saved_cs_client */;
--
----
---- Dumping data for table `integer_types_BIT2`
----
--
--LOCK TABLES `integer_types_BIT2` WRITE;
--/*!40000 ALTER TABLE `integer_types_BIT2` DISABLE KEYS */;
--INSERT INTO `integer_types_BIT2` VALUES ('BIT(2)',0x00,0x03,NULL);
--/*!40000 ALTER TABLE `integer_types_BIT2` ENABLE KEYS */;
--UNLOCK TABLES;
--
----
---- Table structure for table `integer_types_BIT4`
----
--
--DROP TABLE IF EXISTS `integer_types_BIT4`;
--/*!40101 SET @saved_cs_client     = @@character_set_client */;
--/*!50503 SET character_set_client = utf8mb4 */;
--CREATE TABLE `integer_types_BIT4` (
--  `Type` varchar(50) NOT NULL,
--  `Minimum_Value` bit(4) NOT NULL,
--  `Maximum_Value` bit(4) NOT NULL,
--  `Null_Value` bit(4) DEFAULT NULL,
--  PRIMARY KEY (`Type`)
--) ENGINE=InnoDB DEFAULT CHARSET=latin1;
--/*!40101 SET character_set_client = @saved_cs_client */;
--
----
---- Dumping data for table `integer_types_BIT4`
----
--
--LOCK TABLES `integer_types_BIT4` WRITE;
--/*!40000 ALTER TABLE `integer_types_BIT4` DISABLE KEYS */;
--INSERT INTO `integer_types_BIT4` VALUES ('BIT(4)',0x00,0x0F,NULL);
--/*!40000 ALTER TABLE `integer_types_BIT4` ENABLE KEYS */;
--UNLOCK TABLES;
--
----
-- Table structure for table `integer_types_BIT64`
--

--DROP TABLE IF EXISTS `integer_types_BIT64`;
--/*!40101 SET @saved_cs_client     = @@character_set_client */;
--/*!50503 SET character_set_client = utf8mb4 */;
--CREATE TABLE `integer_types_BIT64` (
--  `Type` varchar(50) NOT NULL,
--  `Minimum_Value` bit(64) NOT NULL,
--  `Maximum_Value` bit(64) NOT NULL,
--  `Null_Value` bit(64) DEFAULT NULL,
--  PRIMARY KEY (`Type`)
--) ENGINE=InnoDB DEFAULT CHARSET=latin1;
--/*!40101 SET character_set_client = @saved_cs_client */;
--
----
---- Dumping data for table `integer_types_BIT64`
----
--
--LOCK TABLES `integer_types_BIT64` WRITE;
--/*!40000 ALTER TABLE `integer_types_BIT64` DISABLE KEYS */;
--INSERT INTO `integer_types_BIT64` VALUES ('BIT(64)',0x0000000000000000,0x00000000FFFFFFFF,NULL);
--/*!40000 ALTER TABLE `integer_types_BIT64` ENABLE KEYS */;
--UNLOCK TABLES;

--
-- Table structure for table `integer_types_BOOL`
--

DROP TABLE IF EXISTS `integer_types_BOOL`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_BOOL` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` tinyint(1) NOT NULL,
  `Maximum_Value` tinyint(1) NOT NULL,
  `Null_Value` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_BOOL`
--

LOCK TABLES `integer_types_BOOL` WRITE;
/*!40000 ALTER TABLE `integer_types_BOOL` DISABLE KEYS */;
INSERT INTO `integer_types_BOOL` VALUES ('BOOL',0,1,NULL);
/*!40000 ALTER TABLE `integer_types_BOOL` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_BOOLEAN`
--

DROP TABLE IF EXISTS `integer_types_BOOLEAN`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_BOOLEAN` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` tinyint(1) NOT NULL,
  `Maximum_Value` tinyint(1) NOT NULL,
  `Null_Value` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_BOOLEAN`
--

LOCK TABLES `integer_types_BOOLEAN` WRITE;
/*!40000 ALTER TABLE `integer_types_BOOLEAN` DISABLE KEYS */;
INSERT INTO `integer_types_BOOLEAN` VALUES ('BOOLEAN',0,1,NULL);
/*!40000 ALTER TABLE `integer_types_BOOLEAN` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_INT`
--

DROP TABLE IF EXISTS `integer_types_INT`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_INT` (
  `Type` varchar(50) NOT NULL,
  `Storage_Bytes` int NOT NULL,
  `Minimum_Value_Signed` int NOT NULL,
  `Minimum_Value_Unsigned` int unsigned NOT NULL,
  `Maximum_Value_Signed` int NOT NULL,
  `Maximum_Value_Unsigned` int unsigned NOT NULL,
  `Null_Value_Signed` int DEFAULT NULL,
  `Null_Value_Unsigned` int unsigned DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_INT`
--

LOCK TABLES `integer_types_INT` WRITE;
/*!40000 ALTER TABLE `integer_types_INT` DISABLE KEYS */;
INSERT INTO `integer_types_INT` VALUES ('INT',4,-2147483648,0,2147483647,4294967295,NULL,NULL);
/*!40000 ALTER TABLE `integer_types_INT` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_MEDIUMINT`
--

DROP TABLE IF EXISTS `integer_types_MEDIUMINT`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_MEDIUMINT` (
  `Type` varchar(50) NOT NULL,
  `Storage_Bytes` int NOT NULL,
  `Minimum_Value_Signed` mediumint NOT NULL,
  `Minimum_Value_Unsigned` mediumint unsigned NOT NULL,
  `Maximum_Value_Signed` mediumint NOT NULL,
  `Maximum_Value_Unsigned` mediumint unsigned NOT NULL,
  `Null_Value_Signed` mediumint DEFAULT NULL,
  `Null_Value_Unsigned` mediumint unsigned DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_MEDIUMINT`
--

LOCK TABLES `integer_types_MEDIUMINT` WRITE;
/*!40000 ALTER TABLE `integer_types_MEDIUMINT` DISABLE KEYS */;
INSERT INTO `integer_types_MEDIUMINT` VALUES ('MEDIUMINT',3,-8388608,0,8388607,16777215,NULL,NULL);
/*!40000 ALTER TABLE `integer_types_MEDIUMINT` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_SMALLINT`
--

DROP TABLE IF EXISTS `integer_types_SMALLINT`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_SMALLINT` (
  `Type` varchar(50) NOT NULL,
  `Storage_Bytes` int NOT NULL,
  `Minimum_Value_Signed` smallint NOT NULL,
  `Minimum_Value_Unsigned` smallint unsigned NOT NULL,
  `Maximum_Value_Signed` smallint NOT NULL,
  `Maximum_Value_Unsigned` smallint unsigned NOT NULL,
  `Null_Value_Signed` smallint DEFAULT NULL,
  `Null_Value_Unsigned` smallint unsigned DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_SMALLINT`
--

LOCK TABLES `integer_types_SMALLINT` WRITE;
/*!40000 ALTER TABLE `integer_types_SMALLINT` DISABLE KEYS */;
INSERT INTO `integer_types_SMALLINT` VALUES ('SMALLINT',2,-32768,0,32767,65535,NULL,NULL);
/*!40000 ALTER TABLE `integer_types_SMALLINT` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `integer_types_TINYINT`
--

DROP TABLE IF EXISTS `integer_types_TINYINT`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `integer_types_TINYINT` (
  `Type` varchar(50) NOT NULL,
  `Storage_Bytes` int NOT NULL,
  `Minimum_Value_Signed` tinyint NOT NULL,
  `Minimum_Value_Unsigned` tinyint unsigned NOT NULL,
  `Maximum_Value_Signed` tinyint NOT NULL,
  `Maximum_Value_Unsigned` tinyint unsigned NOT NULL,
  `Null_Value_Signed` tinyint DEFAULT NULL,
  `Null_Value_Unsigned` tinyint unsigned DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `integer_types_TINYINT`
--

LOCK TABLES `integer_types_TINYINT` WRITE;
/*!40000 ALTER TABLE `integer_types_TINYINT` DISABLE KEYS */;
INSERT INTO `integer_types_TINYINT` VALUES ('TINYINT',1,-128,0,127,255,NULL,NULL);
/*!40000 ALTER TABLE `integer_types_TINYINT` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `json_types_JSON`
--

DROP TABLE IF EXISTS `json_types_JSON`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `json_types_JSON` (
  `Type` varchar(50) NOT NULL,
  `Value` json NOT NULL,
  `Null_Value` json DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `json_types_JSON`
--

LOCK TABLES `json_types_JSON` WRITE;
/*!40000 ALTER TABLE `json_types_JSON` DISABLE KEYS */;
INSERT INTO `json_types_JSON` VALUES ('JSON','{\"key1\": \"value1\", \"key2\": \"value2\"}',NULL);
/*!40000 ALTER TABLE `json_types_JSON` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `numeric_types_DECIMAL_10_0`
--

DROP TABLE IF EXISTS `numeric_types_DECIMAL_10_0`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `numeric_types_DECIMAL_10_0` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` decimal(10,0) NOT NULL,
  `Zero_Value` decimal(10,0) NOT NULL,
  `Maximum_Value` decimal(10,0) NOT NULL,
  `Null_Value` decimal(10,0) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `numeric_types_DECIMAL_10_0`
--

LOCK TABLES `numeric_types_DECIMAL_10_0` WRITE;
/*!40000 ALTER TABLE `numeric_types_DECIMAL_10_0` DISABLE KEYS */;
INSERT INTO `numeric_types_DECIMAL_10_0` VALUES ('DECIMAL(1,0)',-9,0,9,NULL),('DECIMAL(10,0)',-9999999999,0,9999999999,NULL);
/*!40000 ALTER TABLE `numeric_types_DECIMAL_10_0` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `numeric_types_DECIMAL_1_0`
--

DROP TABLE IF EXISTS `numeric_types_DECIMAL_1_0`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `numeric_types_DECIMAL_1_0` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` decimal(1,0) NOT NULL,
  `Zero_Value` decimal(1,0) NOT NULL,
  `Maximum_Value` decimal(1,0) NOT NULL,
  `Null_Value` decimal(1,0) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `numeric_types_DECIMAL_1_0`
--

LOCK TABLES `numeric_types_DECIMAL_1_0` WRITE;
/*!40000 ALTER TABLE `numeric_types_DECIMAL_1_0` DISABLE KEYS */;
/*!40000 ALTER TABLE `numeric_types_DECIMAL_1_0` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `numeric_types_DECIMAL_40_20`
--

DROP TABLE IF EXISTS `numeric_types_DECIMAL_40_20`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `numeric_types_DECIMAL_40_20` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` decimal(40,20) NOT NULL,
  `Zero_Value` decimal(40,20) NOT NULL,
  `Maximum_Value` decimal(40,20) NOT NULL,
  `Null_Value` decimal(40,20) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `numeric_types_DECIMAL_40_20`
--

LOCK TABLES `numeric_types_DECIMAL_40_20` WRITE;
/*!40000 ALTER TABLE `numeric_types_DECIMAL_40_20` DISABLE KEYS */;
INSERT INTO `numeric_types_DECIMAL_40_20` VALUES ('DECIMAL(40,20)',-9999999999999999999.99999999999999999999,0.00000000000000000000,9999999999999999999.99999999999999999999,NULL);
/*!40000 ALTER TABLE `numeric_types_DECIMAL_40_20` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `numeric_types_DECIMAL_65_0`
--

DROP TABLE IF EXISTS `numeric_types_DECIMAL_65_0`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `numeric_types_DECIMAL_65_0` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` decimal(65,0) NOT NULL,
  `Zero_Value` decimal(65,0) NOT NULL,
  `Maximum_Value` decimal(65,0) NOT NULL,
  `Null_Value` decimal(65,0) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `numeric_types_DECIMAL_65_0`
--

LOCK TABLES `numeric_types_DECIMAL_65_0` WRITE;
/*!40000 ALTER TABLE `numeric_types_DECIMAL_65_0` DISABLE KEYS */;
INSERT INTO `numeric_types_DECIMAL_65_0` VALUES ('DECIMAL(65,0)',-99999999999999999999999999999999999999999999999999999999999999999,0,99999999999999999999999999999999999999999999999999999999999999999,NULL);
/*!40000 ALTER TABLE `numeric_types_DECIMAL_65_0` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `numeric_types_DECIMAL_65_30`
--

DROP TABLE IF EXISTS `numeric_types_DECIMAL_65_30`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `numeric_types_DECIMAL_65_30` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` decimal(65,30) NOT NULL,
  `Zero_Value` decimal(65,30) NOT NULL,
  `Maximum_Value` decimal(65,30) NOT NULL,
  `Null_Value` decimal(65,30) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `numeric_types_DECIMAL_65_30`
--

LOCK TABLES `numeric_types_DECIMAL_65_30` WRITE;
/*!40000 ALTER TABLE `numeric_types_DECIMAL_65_30` DISABLE KEYS */;
INSERT INTO `numeric_types_DECIMAL_65_30` VALUES ('DECIMAL(65,30)',-99999999999999999999999999999999999.999999999999999999999999999999,0.000000000000000000000000000000,99999999999999999999999999999999999.999999999999999999999999999999,NULL);
/*!40000 ALTER TABLE `numeric_types_DECIMAL_65_30` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `numeric_types_DOUBLE`
--

DROP TABLE IF EXISTS `numeric_types_DOUBLE`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `numeric_types_DOUBLE` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` double NOT NULL,
  `Zero_Value` double NOT NULL,
  `Maximum_Value` double NOT NULL,
  `Null_Value` double DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `numeric_types_DOUBLE`
--

LOCK TABLES `numeric_types_DOUBLE` WRITE;
/*!40000 ALTER TABLE `numeric_types_DOUBLE` DISABLE KEYS */;
INSERT INTO `numeric_types_DOUBLE` VALUES ('DOUBLE',-1e60,0,1e60,NULL),('DOUBLE 2',-1e150,0,1e150,NULL),('DOUBLE 3',-1e250,0,1e250,NULL),('DOUBLE 4',-1e300,0,1e300,NULL),('DOUBLE 5',-1e308,0,1e308,NULL);
/*!40000 ALTER TABLE `numeric_types_DOUBLE` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `numeric_types_FLOAT`
--

DROP TABLE IF EXISTS `numeric_types_FLOAT`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `numeric_types_FLOAT` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` float NOT NULL,
  `Zero_Value` float NOT NULL,
  `Maximum_Value` float NOT NULL,
  `Null_Value` float DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `numeric_types_FLOAT`
--

LOCK TABLES `numeric_types_FLOAT` WRITE;
/*!40000 ALTER TABLE `numeric_types_FLOAT` DISABLE KEYS */;
INSERT INTO `numeric_types_FLOAT` VALUES ('FLOAT',-0,0,1e30,NULL);
/*!40000 ALTER TABLE `numeric_types_FLOAT` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_CHAR20_gbk`
--

DROP TABLE IF EXISTS `string_types_CHAR20_gbk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_CHAR20_gbk` (
  `Type` varchar(50) NOT NULL,
  `Value` char(20) NOT NULL,
  `Empty_Value` char(20) NOT NULL,
  `Space_Value` char(20) NOT NULL,
  `Null_Value` char(20) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=gbk;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_CHAR20_gbk`
--

LOCK TABLES `string_types_CHAR20_gbk` WRITE;
/*!40000 ALTER TABLE `string_types_CHAR20_gbk` DISABLE KEYS */;
INSERT INTO `string_types_CHAR20_gbk` VALUES ('CHAR(20)','公','','',NULL);
/*!40000 ALTER TABLE `string_types_CHAR20_gbk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_CHAR20_latin1`
--

DROP TABLE IF EXISTS `string_types_CHAR20_latin1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_CHAR20_latin1` (
  `Type` varchar(50) NOT NULL,
  `Value` char(20) NOT NULL,
  `Empty_Value` char(20) NOT NULL,
  `Space_Value` char(20) NOT NULL,
  `Null_Value` char(20) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_CHAR20_latin1`
--

LOCK TABLES `string_types_CHAR20_latin1` WRITE;
/*!40000 ALTER TABLE `string_types_CHAR20_latin1` DISABLE KEYS */;
INSERT INTO `string_types_CHAR20_latin1` VALUES ('CHAR(20)','CÃ´te d\'Ivoire','','',NULL);
/*!40000 ALTER TABLE `string_types_CHAR20_latin1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_CHAR20_utf8mb4`
--

DROP TABLE IF EXISTS `string_types_CHAR20_utf8mb4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_CHAR20_utf8mb4` (
  `Type` varchar(50) NOT NULL,
  `Value` char(20) NOT NULL,
  `Empty_Value` char(20) NOT NULL,
  `Space_Value` char(20) NOT NULL,
  `Null_Value` char(20) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_CHAR20_utf8mb4`
--

LOCK TABLES `string_types_CHAR20_utf8mb4` WRITE;
/*!40000 ALTER TABLE `string_types_CHAR20_utf8mb4` DISABLE KEYS */;
INSERT INTO `string_types_CHAR20_utf8mb4` VALUES ('CHAR(20)','????','','',NULL);
/*!40000 ALTER TABLE `string_types_CHAR20_utf8mb4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_LONGTEXT_gbk`
--

DROP TABLE IF EXISTS `string_types_LONGTEXT_gbk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_LONGTEXT_gbk` (
  `Type` varchar(50) NOT NULL,
  `Value` longtext NOT NULL,
  `Empty_Value` longtext NOT NULL,
  `Space_Value` longtext NOT NULL,
  `Null_Value` longtext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=gbk;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_LONGTEXT_gbk`
--

LOCK TABLES `string_types_LONGTEXT_gbk` WRITE;
/*!40000 ALTER TABLE `string_types_LONGTEXT_gbk` DISABLE KEYS */;
INSERT INTO `string_types_LONGTEXT_gbk` VALUES ('LONGTEXT','公','','    ',NULL);
/*!40000 ALTER TABLE `string_types_LONGTEXT_gbk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_LONGTEXT_latin1`
--

DROP TABLE IF EXISTS `string_types_LONGTEXT_latin1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_LONGTEXT_latin1` (
  `Type` varchar(50) NOT NULL,
  `Value` longtext NOT NULL,
  `Empty_Value` longtext NOT NULL,
  `Space_Value` longtext NOT NULL,
  `Null_Value` longtext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_LONGTEXT_latin1`
--

LOCK TABLES `string_types_LONGTEXT_latin1` WRITE;
/*!40000 ALTER TABLE `string_types_LONGTEXT_latin1` DISABLE KEYS */;
INSERT INTO `string_types_LONGTEXT_latin1` VALUES ('LONGTEXT','CÃ´te d\'Ivoire','','    ',NULL);
/*!40000 ALTER TABLE `string_types_LONGTEXT_latin1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_LONGTEXT_utf8mb4`
--

DROP TABLE IF EXISTS `string_types_LONGTEXT_utf8mb4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_LONGTEXT_utf8mb4` (
  `Type` varchar(50) NOT NULL,
  `Value` longtext NOT NULL,
  `Empty_Value` longtext NOT NULL,
  `Space_Value` longtext NOT NULL,
  `Null_Value` longtext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_LONGTEXT_utf8mb4`
--

LOCK TABLES `string_types_LONGTEXT_utf8mb4` WRITE;
/*!40000 ALTER TABLE `string_types_LONGTEXT_utf8mb4` DISABLE KEYS */;
INSERT INTO `string_types_LONGTEXT_utf8mb4` VALUES ('LONGTEXT','????','','    ',NULL);
/*!40000 ALTER TABLE `string_types_LONGTEXT_utf8mb4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_MEDIUMTEXT_gbk`
--

DROP TABLE IF EXISTS `string_types_MEDIUMTEXT_gbk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_MEDIUMTEXT_gbk` (
  `Type` varchar(50) NOT NULL,
  `Value` mediumtext NOT NULL,
  `Empty_Value` mediumtext NOT NULL,
  `Space_Value` mediumtext NOT NULL,
  `Null_Value` mediumtext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=gbk;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_MEDIUMTEXT_gbk`
--

LOCK TABLES `string_types_MEDIUMTEXT_gbk` WRITE;
/*!40000 ALTER TABLE `string_types_MEDIUMTEXT_gbk` DISABLE KEYS */;
INSERT INTO `string_types_MEDIUMTEXT_gbk` VALUES ('MEDIUMTEXT','公','','    ',NULL);
/*!40000 ALTER TABLE `string_types_MEDIUMTEXT_gbk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_MEDIUMTEXT_latin1`
--

DROP TABLE IF EXISTS `string_types_MEDIUMTEXT_latin1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_MEDIUMTEXT_latin1` (
  `Type` varchar(50) NOT NULL,
  `Value` mediumtext NOT NULL,
  `Empty_Value` mediumtext NOT NULL,
  `Space_Value` mediumtext NOT NULL,
  `Null_Value` mediumtext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_MEDIUMTEXT_latin1`
--

LOCK TABLES `string_types_MEDIUMTEXT_latin1` WRITE;
/*!40000 ALTER TABLE `string_types_MEDIUMTEXT_latin1` DISABLE KEYS */;
INSERT INTO `string_types_MEDIUMTEXT_latin1` VALUES ('MEDIUMTEXT','CÃ´te d\'Ivoire','','    ',NULL);
/*!40000 ALTER TABLE `string_types_MEDIUMTEXT_latin1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_MEDIUMTEXT_utf8mb4`
--

DROP TABLE IF EXISTS `string_types_MEDIUMTEXT_utf8mb4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_MEDIUMTEXT_utf8mb4` (
  `Type` varchar(50) NOT NULL,
  `Value` mediumtext NOT NULL,
  `Empty_Value` mediumtext NOT NULL,
  `Space_Value` mediumtext NOT NULL,
  `Null_Value` mediumtext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_MEDIUMTEXT_utf8mb4`
--

LOCK TABLES `string_types_MEDIUMTEXT_utf8mb4` WRITE;
/*!40000 ALTER TABLE `string_types_MEDIUMTEXT_utf8mb4` DISABLE KEYS */;
INSERT INTO `string_types_MEDIUMTEXT_utf8mb4` VALUES ('MEDIUMTEXT','????','','    ',NULL);
/*!40000 ALTER TABLE `string_types_MEDIUMTEXT_utf8mb4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_TEXT_gbk`
--

DROP TABLE IF EXISTS `string_types_TEXT_gbk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_TEXT_gbk` (
  `Type` varchar(50) NOT NULL,
  `Value` text NOT NULL,
  `Empty_Value` text NOT NULL,
  `Space_Value` text NOT NULL,
  `Null_Value` text,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=gbk;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_TEXT_gbk`
--

LOCK TABLES `string_types_TEXT_gbk` WRITE;
/*!40000 ALTER TABLE `string_types_TEXT_gbk` DISABLE KEYS */;
INSERT INTO `string_types_TEXT_gbk` VALUES ('TEXT','公','','    ',NULL);
/*!40000 ALTER TABLE `string_types_TEXT_gbk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_TEXT_latin1`
--

DROP TABLE IF EXISTS `string_types_TEXT_latin1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_TEXT_latin1` (
  `Type` varchar(50) NOT NULL,
  `Value` text NOT NULL,
  `Empty_Value` text NOT NULL,
  `Space_Value` text NOT NULL,
  `Null_Value` text,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_TEXT_latin1`
--

LOCK TABLES `string_types_TEXT_latin1` WRITE;
/*!40000 ALTER TABLE `string_types_TEXT_latin1` DISABLE KEYS */;
INSERT INTO `string_types_TEXT_latin1` VALUES ('TEXT','CÃ´te d\'Ivoire','','    ',NULL);
/*!40000 ALTER TABLE `string_types_TEXT_latin1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_TEXT_utf8mb4`
--

DROP TABLE IF EXISTS `string_types_TEXT_utf8mb4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_TEXT_utf8mb4` (
  `Type` varchar(50) NOT NULL,
  `Value` text NOT NULL,
  `Empty_Value` text NOT NULL,
  `Space_Value` text NOT NULL,
  `Null_Value` text,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_TEXT_utf8mb4`
--

LOCK TABLES `string_types_TEXT_utf8mb4` WRITE;
/*!40000 ALTER TABLE `string_types_TEXT_utf8mb4` DISABLE KEYS */;
INSERT INTO `string_types_TEXT_utf8mb4` VALUES ('TEXT','????','','    ',NULL);
/*!40000 ALTER TABLE `string_types_TEXT_utf8mb4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_TINYTEXT_gbk`
--

DROP TABLE IF EXISTS `string_types_TINYTEXT_gbk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_TINYTEXT_gbk` (
  `Type` varchar(50) NOT NULL,
  `Value` tinytext NOT NULL,
  `Empty_Value` tinytext NOT NULL,
  `Space_Value` tinytext NOT NULL,
  `Null_Value` tinytext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=gbk;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_TINYTEXT_gbk`
--

LOCK TABLES `string_types_TINYTEXT_gbk` WRITE;
/*!40000 ALTER TABLE `string_types_TINYTEXT_gbk` DISABLE KEYS */;
INSERT INTO `string_types_TINYTEXT_gbk` VALUES ('TINYTEXT','公','','    ',NULL);
/*!40000 ALTER TABLE `string_types_TINYTEXT_gbk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_TINYTEXT_latin1`
--

DROP TABLE IF EXISTS `string_types_TINYTEXT_latin1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_TINYTEXT_latin1` (
  `Type` varchar(50) NOT NULL,
  `Value` tinytext NOT NULL,
  `Empty_Value` tinytext NOT NULL,
  `Space_Value` tinytext NOT NULL,
  `Null_Value` tinytext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_TINYTEXT_latin1`
--

LOCK TABLES `string_types_TINYTEXT_latin1` WRITE;
/*!40000 ALTER TABLE `string_types_TINYTEXT_latin1` DISABLE KEYS */;
INSERT INTO `string_types_TINYTEXT_latin1` VALUES ('TINYTEXT','CÃ´te d\'Ivoire','','    ',NULL);
/*!40000 ALTER TABLE `string_types_TINYTEXT_latin1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_TINYTEXT_utf8mb4`
--

DROP TABLE IF EXISTS `string_types_TINYTEXT_utf8mb4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_TINYTEXT_utf8mb4` (
  `Type` varchar(50) NOT NULL,
  `Value` tinytext NOT NULL,
  `Empty_Value` tinytext NOT NULL,
  `Space_Value` tinytext NOT NULL,
  `Null_Value` tinytext,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_TINYTEXT_utf8mb4`
--

LOCK TABLES `string_types_TINYTEXT_utf8mb4` WRITE;
/*!40000 ALTER TABLE `string_types_TINYTEXT_utf8mb4` DISABLE KEYS */;
INSERT INTO `string_types_TINYTEXT_utf8mb4` VALUES ('TINYTEXT','????','','    ',NULL);
/*!40000 ALTER TABLE `string_types_TINYTEXT_utf8mb4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_VARCHAR20_gbk`
--

DROP TABLE IF EXISTS `string_types_VARCHAR20_gbk`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_VARCHAR20_gbk` (
  `Type` varchar(50) NOT NULL,
  `Value` varchar(20) NOT NULL,
  `Empty_Value` varchar(20) NOT NULL,
  `Space_Value` varchar(20) NOT NULL,
  `Null_Value` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=gbk;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_VARCHAR20_gbk`
--

LOCK TABLES `string_types_VARCHAR20_gbk` WRITE;
/*!40000 ALTER TABLE `string_types_VARCHAR20_gbk` DISABLE KEYS */;
INSERT INTO `string_types_VARCHAR20_gbk` VALUES ('VARCHAR(20)','公','','    ',NULL);
/*!40000 ALTER TABLE `string_types_VARCHAR20_gbk` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_VARCHAR20_latin1`
--

DROP TABLE IF EXISTS `string_types_VARCHAR20_latin1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_VARCHAR20_latin1` (
  `Type` varchar(50) NOT NULL,
  `Value` varchar(20) NOT NULL,
  `Empty_Value` varchar(20) NOT NULL,
  `Space_Value` varchar(20) NOT NULL,
  `Null_Value` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_VARCHAR20_latin1`
--

LOCK TABLES `string_types_VARCHAR20_latin1` WRITE;
/*!40000 ALTER TABLE `string_types_VARCHAR20_latin1` DISABLE KEYS */;
INSERT INTO `string_types_VARCHAR20_latin1` VALUES ('VARCHAR(20)','CÃ´te d\'Ivoire','','    ',NULL);
/*!40000 ALTER TABLE `string_types_VARCHAR20_latin1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `string_types_VARCHAR20_utf8mb4`
--

DROP TABLE IF EXISTS `string_types_VARCHAR20_utf8mb4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `string_types_VARCHAR20_utf8mb4` (
  `Type` varchar(50) NOT NULL,
  `Value` varchar(20) NOT NULL,
  `Empty_Value` varchar(20) NOT NULL,
  `Space_Value` varchar(20) NOT NULL,
  `Null_Value` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `string_types_VARCHAR20_utf8mb4`
--

LOCK TABLES `string_types_VARCHAR20_utf8mb4` WRITE;
/*!40000 ALTER TABLE `string_types_VARCHAR20_utf8mb4` DISABLE KEYS */;
INSERT INTO `string_types_VARCHAR20_utf8mb4` VALUES ('VARCHAR(20)','????','','    ',NULL);
/*!40000 ALTER TABLE `string_types_VARCHAR20_utf8mb4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATE`
--

DROP TABLE IF EXISTS `temporal_types_DATE`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATE` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` date NOT NULL,
  `Mid_Value` date NOT NULL,
  `Maximum_Value` date NOT NULL,
  `Null_Value` date DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATE`
--

LOCK TABLES `temporal_types_DATE` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATE` DISABLE KEYS */;
INSERT INTO `temporal_types_DATE` VALUES ('DATE','1000-01-01','2022-09-29','9999-12-31',NULL);
/*!40000 ALTER TABLE `temporal_types_DATE` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATETIME`
--

DROP TABLE IF EXISTS `temporal_types_DATETIME`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATETIME` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` datetime NOT NULL,
  `Mid_Value` datetime NOT NULL,
  `Maximum_Value` datetime NOT NULL,
  `Null_Value` datetime DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATETIME`
--

LOCK TABLES `temporal_types_DATETIME` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATETIME` DISABLE KEYS */;
INSERT INTO `temporal_types_DATETIME` VALUES ('DATETIME','1000-01-01 00:00:00','2022-09-29 01:47:46','9999-12-31 23:59:59',NULL);
/*!40000 ALTER TABLE `temporal_types_DATETIME` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATETIME1`
--

DROP TABLE IF EXISTS `temporal_types_DATETIME1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATETIME1` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` datetime(1) NOT NULL,
  `Mid_Value` datetime(1) NOT NULL,
  `Maximum_Value` datetime(1) NOT NULL,
  `Null_Value` datetime(1) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATETIME1`
--

LOCK TABLES `temporal_types_DATETIME1` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATETIME1` DISABLE KEYS */;
INSERT INTO `temporal_types_DATETIME1` VALUES ('DATETIME(1)','1000-01-01 00:00:00.0','2022-09-29 01:48:25.1','9999-12-31 23:59:59.9',NULL);
/*!40000 ALTER TABLE `temporal_types_DATETIME1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATETIME2`
--

DROP TABLE IF EXISTS `temporal_types_DATETIME2`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATETIME2` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` datetime(2) NOT NULL,
  `Mid_Value` datetime(2) NOT NULL,
  `Maximum_Value` datetime(2) NOT NULL,
  `Null_Value` datetime(2) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATETIME2`
--

LOCK TABLES `temporal_types_DATETIME2` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATETIME2` DISABLE KEYS */;
INSERT INTO `temporal_types_DATETIME2` VALUES ('DATETIME(2)','1000-01-01 00:00:00.00','2022-09-29 01:49:05.12','9999-12-31 23:59:59.99',NULL);
/*!40000 ALTER TABLE `temporal_types_DATETIME2` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATETIME3`
--

DROP TABLE IF EXISTS `temporal_types_DATETIME3`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATETIME3` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` datetime(3) NOT NULL,
  `Mid_Value` datetime(3) NOT NULL,
  `Maximum_Value` datetime(3) NOT NULL,
  `Null_Value` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATETIME3`
--

LOCK TABLES `temporal_types_DATETIME3` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATETIME3` DISABLE KEYS */;
INSERT INTO `temporal_types_DATETIME3` VALUES ('DATETIME(3)','1000-01-01 00:00:00.000','2022-09-29 01:49:22.123','9999-12-31 23:59:59.999',NULL);
/*!40000 ALTER TABLE `temporal_types_DATETIME3` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATETIME4`
--

DROP TABLE IF EXISTS `temporal_types_DATETIME4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATETIME4` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` datetime(4) NOT NULL,
  `Mid_Value` datetime(4) NOT NULL,
  `Maximum_Value` datetime(4) NOT NULL,
  `Null_Value` datetime(4) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATETIME4`
--

LOCK TABLES `temporal_types_DATETIME4` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATETIME4` DISABLE KEYS */;
INSERT INTO `temporal_types_DATETIME4` VALUES ('DATETIME(4)','1000-01-01 00:00:00.0000','2022-09-29 01:50:12.1234','9999-12-31 23:59:59.9999',NULL);
/*!40000 ALTER TABLE `temporal_types_DATETIME4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATETIME5`
--

DROP TABLE IF EXISTS `temporal_types_DATETIME5`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATETIME5` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` datetime(5) NOT NULL,
  `Mid_Value` datetime(5) NOT NULL,
  `Maximum_Value` datetime(5) NOT NULL,
  `Null_Value` datetime(5) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATETIME5`
--

LOCK TABLES `temporal_types_DATETIME5` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATETIME5` DISABLE KEYS */;
INSERT INTO `temporal_types_DATETIME5` VALUES ('DATETIME(5)','1000-01-01 00:00:00.00000','2022-09-29 01:50:28.12345','9999-12-31 23:59:59.99999',NULL);
/*!40000 ALTER TABLE `temporal_types_DATETIME5` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_DATETIME6`
--

DROP TABLE IF EXISTS `temporal_types_DATETIME6`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_DATETIME6` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` datetime(6) NOT NULL,
  `Mid_Value` datetime(6) NOT NULL,
  `Maximum_Value` datetime(6) NOT NULL,
  `Null_Value` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_DATETIME6`
--

LOCK TABLES `temporal_types_DATETIME6` WRITE;
/*!40000 ALTER TABLE `temporal_types_DATETIME6` DISABLE KEYS */;
INSERT INTO `temporal_types_DATETIME6` VALUES ('DATETIME(6)','1000-01-01 00:00:00.000000','2022-09-29 01:50:56.123456','9999-12-31 23:59:59.999999',NULL);
INSERT INTO `temporal_types_DATETIME6` VALUES ('DATETIME(6_1)','1000-01-01 00:00:00.000000','2022-09-29 01:50:56.100000','9999-12-31 23:59:59.999999',NULL);
/*!40000 ALTER TABLE `temporal_types_DATETIME6` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIME`
--

DROP TABLE IF EXISTS `temporal_types_TIME`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIME` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` time NOT NULL,
  `Mid_Value` time NOT NULL,
  `Maximum_Value` time NOT NULL,
  `Null_Value` time DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIME`
--

LOCK TABLES `temporal_types_TIME` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIME` DISABLE KEYS */;
INSERT INTO `temporal_types_TIME` VALUES ('TIME','00:00:00','01:52:24','23:59:59',NULL);
/*!40000 ALTER TABLE `temporal_types_TIME` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIME1`
--

DROP TABLE IF EXISTS `temporal_types_TIME1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIME1` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` time(1) NOT NULL,
  `Mid_Value` time(1) NOT NULL,
  `Maximum_Value` time(1) NOT NULL,
  `Null_Value` time(1) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIME1`
--

LOCK TABLES `temporal_types_TIME1` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIME1` DISABLE KEYS */;
INSERT INTO `temporal_types_TIME1` VALUES ('TIME(1)','00:00:00.0','01:53:27.1','23:59:59.9',NULL);
/*!40000 ALTER TABLE `temporal_types_TIME1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIME2`
--

DROP TABLE IF EXISTS `temporal_types_TIME2`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIME2` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` time(2) NOT NULL,
  `Mid_Value` time(2) NOT NULL,
  `Maximum_Value` time(2) NOT NULL,
  `Null_Value` time(2) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIME2`
--

LOCK TABLES `temporal_types_TIME2` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIME2` DISABLE KEYS */;
INSERT INTO `temporal_types_TIME2` VALUES ('TIME(2)','00:00:00.00','01:53:09.13','23:59:59.99',NULL);
/*!40000 ALTER TABLE `temporal_types_TIME2` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIME3`
--

DROP TABLE IF EXISTS `temporal_types_TIME3`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIME3` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` time(3) NOT NULL,
  `Mid_Value` time(3) NOT NULL,
  `Maximum_Value` time(3) NOT NULL,
  `Null_Value` time(3) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIME3`
--

LOCK TABLES `temporal_types_TIME3` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIME3` DISABLE KEYS */;
INSERT INTO `temporal_types_TIME3` VALUES ('TIME(3)','00:00:00.000','01:53:45.858','23:59:59.999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIME3` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIME4`
--

DROP TABLE IF EXISTS `temporal_types_TIME4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIME4` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` time(4) NOT NULL,
  `Mid_Value` time(4) NOT NULL,
  `Maximum_Value` time(4) NOT NULL,
  `Null_Value` time(4) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIME4`
--

LOCK TABLES `temporal_types_TIME4` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIME4` DISABLE KEYS */;
INSERT INTO `temporal_types_TIME4` VALUES ('TIME(4)','00:00:00.0000','01:54:11.3376','23:59:59.9999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIME4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIME5`
--

DROP TABLE IF EXISTS `temporal_types_TIME5`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIME5` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` time(5) NOT NULL,
  `Mid_Value` time(5) NOT NULL,
  `Maximum_Value` time(5) NOT NULL,
  `Null_Value` time(5) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIME5`
--

LOCK TABLES `temporal_types_TIME5` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIME5` DISABLE KEYS */;
INSERT INTO `temporal_types_TIME5` VALUES ('TIME(5)','00:00:00.00000','01:54:23.08209','23:59:59.99999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIME5` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIME6`
--

DROP TABLE IF EXISTS `temporal_types_TIME6`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIME6` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` time(6) NOT NULL,
  `Mid_Value` time(6) NOT NULL,
  `Maximum_Value` time(6) NOT NULL,
  `Null_Value` time(6) DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIME6`
--

LOCK TABLES `temporal_types_TIME6` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIME6` DISABLE KEYS */;
INSERT INTO `temporal_types_TIME6` VALUES ('TIME(6)','00:00:00.000000','01:54:34.374946','23:59:59.999999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIME6` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIMESTAMP`
--

DROP TABLE IF EXISTS `temporal_types_TIMESTAMP`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIMESTAMP` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` timestamp NOT NULL,
  `Mid_Value` timestamp NOT NULL,
  `Maximum_Value` timestamp NOT NULL,
  `Null_Value` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIMESTAMP`
--

LOCK TABLES `temporal_types_TIMESTAMP` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP` DISABLE KEYS */;
INSERT INTO `temporal_types_TIMESTAMP` VALUES ('TIMESTAMP','1970-01-01 00:00:01','2022-09-29 06:57:12','2038-01-19 03:14:07',NULL);
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIMESTAMP1`
--

DROP TABLE IF EXISTS `temporal_types_TIMESTAMP1`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIMESTAMP1` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` timestamp(1) NOT NULL,
  `Mid_Value` timestamp(1) NOT NULL,
  `Maximum_Value` timestamp(1) NOT NULL,
  `Null_Value` timestamp(1) NULL DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIMESTAMP1`
--

LOCK TABLES `temporal_types_TIMESTAMP1` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP1` DISABLE KEYS */;
INSERT INTO `temporal_types_TIMESTAMP1` VALUES ('TIMESTAMP(1)','1970-01-01 00:00:01.0','2022-09-29 06:57:39.1','2038-01-19 03:14:07.9',NULL);
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP1` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIMESTAMP2`
--

DROP TABLE IF EXISTS `temporal_types_TIMESTAMP2`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIMESTAMP2` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` timestamp(2) NOT NULL,
  `Mid_Value` timestamp(2) NOT NULL,
  `Maximum_Value` timestamp(2) NOT NULL,
  `Null_Value` timestamp(2) NULL DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIMESTAMP2`
--

LOCK TABLES `temporal_types_TIMESTAMP2` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP2` DISABLE KEYS */;
INSERT INTO `temporal_types_TIMESTAMP2` VALUES ('TIMESTAMP(2)','1970-01-01 00:00:01.00','2022-09-29 06:57:53.12','2038-01-19 03:14:07.99',NULL);
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP2` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIMESTAMP3`
--

DROP TABLE IF EXISTS `temporal_types_TIMESTAMP3`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIMESTAMP3` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` timestamp(3) NOT NULL,
  `Mid_Value` timestamp(3) NOT NULL,
  `Maximum_Value` timestamp(3) NOT NULL,
  `Null_Value` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIMESTAMP3`
--

LOCK TABLES `temporal_types_TIMESTAMP3` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP3` DISABLE KEYS */;
INSERT INTO `temporal_types_TIMESTAMP3` VALUES ('TIMESTAMP(3)','1970-01-01 00:00:01.000','2022-09-29 06:58:05.123','2038-01-19 03:14:07.999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP3` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIMESTAMP4`
--

DROP TABLE IF EXISTS `temporal_types_TIMESTAMP4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIMESTAMP4` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` timestamp(4) NOT NULL,
  `Mid_Value` timestamp(4) NOT NULL,
  `Maximum_Value` timestamp(4) NOT NULL,
  `Null_Value` timestamp(4) NULL DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIMESTAMP4`
--

LOCK TABLES `temporal_types_TIMESTAMP4` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP4` DISABLE KEYS */;
INSERT INTO `temporal_types_TIMESTAMP4` VALUES ('TIMESTAMP(4)','1970-01-01 00:00:01.0000','2022-09-29 06:58:16.1234','2038-01-19 03:14:07.9999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP4` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIMESTAMP5`
--

DROP TABLE IF EXISTS `temporal_types_TIMESTAMP5`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIMESTAMP5` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` timestamp(5) NOT NULL,
  `Mid_Value` timestamp(5) NOT NULL,
  `Maximum_Value` timestamp(5) NOT NULL,
  `Null_Value` timestamp(5) NULL DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIMESTAMP5`
--

LOCK TABLES `temporal_types_TIMESTAMP5` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP5` DISABLE KEYS */;
INSERT INTO `temporal_types_TIMESTAMP5` VALUES ('TIMESTAMP(5)','1970-01-01 00:00:01.00000','2022-09-29 06:58:26.12345','2038-01-19 03:14:07.99999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP5` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_TIMESTAMP6`
--

DROP TABLE IF EXISTS `temporal_types_TIMESTAMP6`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_TIMESTAMP6` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` timestamp(6) NOT NULL,
  `Mid_Value` timestamp(6) NOT NULL,
  `Maximum_Value` timestamp(6) NOT NULL,
  `Null_Value` timestamp(6) NULL DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_TIMESTAMP6`
--

LOCK TABLES `temporal_types_TIMESTAMP6` WRITE;
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP6` DISABLE KEYS */;
INSERT INTO `temporal_types_TIMESTAMP6` VALUES ('TIMESTAMP(6)','1970-01-01 00:00:01.000000','2022-09-29 06:58:42.123456','2038-01-19 03:14:07.999999',NULL);
/*!40000 ALTER TABLE `temporal_types_TIMESTAMP6` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_YEAR`
--

DROP TABLE IF EXISTS `temporal_types_YEAR`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_YEAR` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` year NOT NULL,
  `Mid_Value` year NOT NULL,
  `Maximum_Value` year NOT NULL,
  `Null_Value` year DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_YEAR`
--

LOCK TABLES `temporal_types_YEAR` WRITE;
/*!40000 ALTER TABLE `temporal_types_YEAR` DISABLE KEYS */;
INSERT INTO `temporal_types_YEAR` VALUES ('YEAR',2000,2022,2099,NULL);
/*!40000 ALTER TABLE `temporal_types_YEAR` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `temporal_types_YEAR4`
--

DROP TABLE IF EXISTS `temporal_types_YEAR4`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `temporal_types_YEAR4` (
  `Type` varchar(50) NOT NULL,
  `Minimum_Value` year NOT NULL,
  `Mid_Value` year NOT NULL,
  `Maximum_Value` year NOT NULL,
  `Null_Value` year DEFAULT NULL,
  PRIMARY KEY (`Type`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `temporal_types_YEAR4`
--

LOCK TABLES `temporal_types_YEAR4` WRITE;
/*!40000 ALTER TABLE `temporal_types_YEAR4` DISABLE KEYS */;
INSERT INTO `temporal_types_YEAR4` VALUES ('YEAR(4)',1901,2022,2155,NULL);
/*!40000 ALTER TABLE `temporal_types_YEAR4` ENABLE KEYS */;
UNLOCK TABLES;
SET @@SESSION.SQL_LOG_BIN = @MYSQLDUMP_TEMP_LOG_BIN;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
create table ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int);
create table add_test(col1 varchar(255), col2 int, col3 int);

-- Dump completed on 2022-09-30  1:31:45
CREATE TABLE IF NOT EXISTS 730b595f_d475_11ed_b64a_398b553542b2 (id INT AUTO_INCREMENT,x INT, PRIMARY KEY (id)) ENGINE = InnoDB;

INSERT INTO 730b595f_d475_11ed_b64a_398b553542b2 VALUES (1,1),(2,1),(3,1),(4,1),(5,1),(6,1),(7,1),(8,1),(9,1),(10,1),(11,1),(12,1),(13,1),(14,1),(15,1),(16,1),(17,1),(18,1),(19,1),(20,1),(21,1),(22,1),(23,1),(24,1),(25,1),(26,1),(27,1),(28,1),(29,1),(30,1),(31,1),(32,1),(33,1),(34,1),(35,1),(36,1),(37,1),(38,1),(39,1),(40,1),(41,1),(42,1),(43,1),(44,1),(45,1),(46,1),(47,1),(48,1),(49,1),(50,1),(51,1),(52,1),(53,1),(54,1),(55,1),(56,1),(57,1),(58,1),(59,1),(60,1),(61,1),(62,1),(63,1),(64,1),(65,1),(66,1),(67,1),(68,1),(69,1),(70,1),(71,1),(72,1),(73,1),(74,1),(75,1),(76,1),(77,1),(78,1),(79,1),(80,1),(81,1),(82,1),(83,1),(84,1),(85,1),(86,1),(87,1),(88,1),(89,1),(90,1),(91,1),(92,1),(93,1),(94,1),(95,1),(96,1),(97,1),(98,1),(99,1),(100,1);