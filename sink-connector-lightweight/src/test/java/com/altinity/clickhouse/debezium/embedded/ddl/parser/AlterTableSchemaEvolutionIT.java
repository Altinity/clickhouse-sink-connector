package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.junit.jupiter.api.DisplayName;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("Integration Test that validates DDL replication of ALTER table column, first, after and MODIFY column")
public class AlterTableSchemaEvolutionIT extends DDLBaseIT {

}
