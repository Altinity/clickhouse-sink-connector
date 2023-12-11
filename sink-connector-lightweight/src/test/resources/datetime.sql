
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