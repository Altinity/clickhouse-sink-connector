class Schema:

    def __init__(self):
        pass

    def create_schema(self) -> str:
        return """
    -- test.employees definition

    CREATE TABLE `employees` (
  `emp_no` int NOT NULL,
  `birth_date` date NOT NULL,
  `first_name` varchar(14) NOT NULL,
  `last_name` varchar(16) NOT NULL,
  `gender` enum('M','F') NOT NULL,
  `hire_date` date NOT NULL,
  `salary` bigint unsigned DEFAULT NULL,
  `num_years` tinyint unsigned DEFAULT NULL,
  `bonus` mediumint unsigned DEFAULT NULL,
  `small_value` smallint unsigned DEFAULT NULL,
  `int_value` int unsigned DEFAULT NULL,
  `discount` bigint DEFAULT NULL,
  `num_years_signed` tinyint DEFAULT NULL,
  `bonus_signed` mediumint DEFAULT NULL,
  `small_value_signed` smallint DEFAULT NULL,
  `int_value_signed` int DEFAULT NULL,
  `last_modified_date_time` datetime DEFAULT NULL,
  `last_access_time` time DEFAULT NULL,
  `married_status` char(1) DEFAULT NULL,
  `perDiemRate` decimal(30,12) DEFAULT NULL,
  `hourlyRate` double DEFAULT NULL,
  `jobDescription` text,
  `updated_time` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`emp_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
"""
