package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.config.ConfigurationService;
import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.google.inject.AbstractModule;

/**
 * Google guice injection module.
 */
public class AppInjector extends AbstractModule {

    @Override
    protected void configure() {
        bind(ConfigurationService.class).to(EnvironmentConfigurationService.class);
        bind(DebeziumRecordParserService.class).to(SourceRecordParserService.class);
        bind(DDLParserService.class).to(MySQLDDLParserService.class);
    }
}
