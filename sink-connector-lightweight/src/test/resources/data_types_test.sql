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