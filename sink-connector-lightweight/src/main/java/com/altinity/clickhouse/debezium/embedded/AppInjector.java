package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.config.ConfigurationService;
import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.google.inject.AbstractModule;

/**
 * Google Guice injection module.
 *
 * <p>This module binds service interfaces to their respective
 * implementations:
 * <ul>
 *   <li>{@link ConfigurationService} is bound to
 *       {@link EnvironmentConfigurationService}</li>
 *   <li>{@link DebeziumRecordParserService} is bound to
 *       {@link SourceRecordParserService}</li>
 *   <li>{@link DDLParserService} is bound to
 *       {@link MySQLDDLParserService}</li>
 * </ul>
 */
public class AppInjector extends AbstractModule {

    /**
     * Configures the dependency injection bindings.
     */
    @Override
    protected void configure() {
        bind(ConfigurationService.class)
                .to(EnvironmentConfigurationService.class);
        bind(DebeziumRecordParserService.class)
                .to(SourceRecordParserService.class);
        bind(DDLParserService.class)
                .to(MySQLDDLParserService.class);
    }
}
