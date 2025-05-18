package com.altinity.clickhouse.debezium.embedded.api;

import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumJdbcStorageOperations;
import com.altinity.clickhouse.debezium.embedded.cdc.ReplicationStatusSingleton;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Injector;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.HashMap;
import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage.*;
import static com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage.LSN;
import static com.altinity.clickhouse.sink.connector.db.BaseDbWriter.SYSTEM_DB;

/**
 * DebeziumEmbeddedRestApi provides a REST API for managing
 * the Debezium change event capture and related functionalities.
 * <p>
 * It uses the Javalin framework to expose endpoints for starting,
 * stopping, and querying the status of the replication process,
 * as well as updating binlog, offsets, and schema history.
 * </p>
 */
public class DebeziumEmbeddedRestApi {

    private static final Logger log = LogManager.getLogger(
            DebeziumEmbeddedRestApi.class);

    static Javalin app;

    /**
     * Starts the REST API server with the given properties and injector,
     * and sets up various endpoints for controlling replication.
     *
     * @param props                   The connector properties.
     * @param injector                The Guice injector.
     * @param debeziumChangeEventCapture The Debezium event capture instance.
     * @param userProperties          User-specified properties.
     */
    public static void startRestApi(Properties props, Injector injector,
                                    DebeziumChangeEventCapture debeziumChangeEventCapture,
                                    Properties userProperties) {
        String cliPort = props.getProperty(
                SinkConnectorLightWeightConfig.CLI_PORT);
        MySQLDDLParserService sqlddlParserService = new MySQLDDLParserService();
        sqlddlParserService = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()),
                "employees");
        if (cliPort == null || cliPort.isEmpty()) {
            cliPort = "7000";
        }

        app = Javalin.create().start(Integer.parseInt(cliPort));
        app.get("/", ctx -> {
            ctx.result("Hello World");
        });
        app.get("/stop", ctx -> {
            ClickHouseDebeziumEmbeddedApplication.stop();
        });
        Properties finalProps1 = props;
        app.get("/status", ctx -> {
            ClickHouseSinkConnectorConfig config =
                    new ClickHouseSinkConnectorConfig(
                            PropertiesHelper.toMap(finalProps1));
            String response = "";

            try {
                DebeziumJdbcStorageOperations debeziumJdbcStorageOperations =
                        new DebeziumJdbcStorageOperations();
                HikariDataSource ds = HikariDbSource.getInstance(SYSTEM_DB);
                Connection connection = ds.getConnection();
                response = debeziumJdbcStorageOperations.getDebeziumStorageStatus(
                        connection, config, finalProps1);
                connection.close();

            } catch (Exception e) {
                log.error("Client - Error getting status", e);
                // Create JSON response
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("error", e.toString());
                response = jsonObject.toJSONString();
                ctx.result(response);
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }
            ctx.result(response);
        });

        // Delete offsets
        app.delete("/offsets", ctx -> {
            ClickHouseSinkConnectorConfig config =
                    new ClickHouseSinkConnectorConfig(
                            PropertiesHelper.toMap(finalProps1));
            String response = "";

            try {
                DebeziumJdbcStorageOperations debeziumJdbcStorageOperations =
                        new DebeziumJdbcStorageOperations();
                HikariDataSource ds = HikariDbSource.getInstance(SYSTEM_DB);
                Connection connection = ds.getConnection();
                debeziumJdbcStorageOperations.deleteOffsets(connection, finalProps1);
                connection.close();
            } catch (Exception e) {
                log.error("Client - Error deleting offsets", e);
                ctx.result(e.toString());
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }
            ctx.result(response);
        });

        app.post("/binlog", ctx -> {
            if (ReplicationStatusSingleton.getInstance().isReplicationRunning()) {
                ctx.status(HttpStatus.BAD_REQUEST);
                return;
            }
            String body = ctx.body();
            JSONObject jsonObject = (JSONObject) new JSONParser().parse(body);
            String binlogFile = (String) jsonObject.get(BINLOG_FILE);
            String binlogPosition = (String) jsonObject.get(BINLOG_POS);
            String gtid = (String) jsonObject.get(GTID);

            String sourceHost = (String) jsonObject.get(SOURCE_HOST);
            String sourcePort = (String) jsonObject.get(SOURCE_PORT);
            String sourceUser = (String) jsonObject.get(SOURCE_USER);
            String sourcePassword = (String) jsonObject.get(SOURCE_PASSWORD);

            ClickHouseSinkConnectorConfig config =
                    new ClickHouseSinkConnectorConfig(
                            PropertiesHelper.toMap(finalProps1));

            if (sourceHost != null && !sourceHost.isEmpty()) {
                userProperties.setProperty("database.hostname", sourceHost);
            }
            if (sourcePort != null && !sourcePort.isEmpty()) {
                userProperties.setProperty("database.port", sourcePort);
            }
            if (sourceUser != null && !sourceUser.isEmpty()) {
                userProperties.setProperty("database.user", sourceUser);
            }
            if (sourcePassword != null && !sourcePassword.isEmpty()) {
                userProperties.setProperty("database.password", sourcePassword);
            }
            if (userProperties.size() > 0) {
                log.info("User Overridden properties: " + userProperties);
            }

            DebeziumJdbcStorageOperations debeziumJdbcStorageOperations =
                    new DebeziumJdbcStorageOperations();
            HikariDataSource ds = HikariDbSource.getInstance(SYSTEM_DB);
            Connection connection = ds.getConnection();
            debeziumJdbcStorageOperations.updateDebeziumStorageStatus(connection, config,
                    finalProps1, binlogFile, binlogPosition, gtid);
            connection.close();
            log.info("Received update-binlog request: " + body);
        });

        // Delete offsets
        app.delete("/schema-history", ctx -> {
            ClickHouseSinkConnectorConfig config =
                    new ClickHouseSinkConnectorConfig(
                            PropertiesHelper.toMap(finalProps1));
            String response = "";

            try {
                DebeziumJdbcStorageOperations debeziumJdbcStorageOperations =
                        new DebeziumJdbcStorageOperations();
                HikariDataSource ds = HikariDbSource.getInstance(SYSTEM_DB);
                Connection connection = ds.getConnection();
                debeziumJdbcStorageOperations.deleteSchemaHistory(connection, config, finalProps1);
                connection.close();
            } catch (Exception e) {
                log.error("Client - Error deleting schema history", e);
                ctx.result(e.toString());
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }
            ctx.result(response);
        });

        app.get("/show-slave-status", ctx -> {
            ClickHouseSinkConnectorConfig config =
                    new ClickHouseSinkConnectorConfig(
                            PropertiesHelper.toMap(finalProps1));
            DebeziumJdbcStorageOperations debeziumJdbcStorageOperations =
                    new DebeziumJdbcStorageOperations();
                HikariDataSource ds = HikariDbSource.getInstance(SYSTEM_DB);
                Connection connection = ds.getConnection();
                debeziumJdbcStorageOperations.getErrorTableStatus(connection, finalProps1);
                connection.close();
            } catch (Exception e) {
                log.error("Client - Error getting error table status", e);      
                ctx.result(e.toString());
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                return;
            }
            ctx.result(response);
        });
                    
        app.post("/lsn", ctx -> {
            String body = ctx.body();
            JSONObject jsonObject = (JSONObject) new JSONParser().parse(body);
            String lsn = (String) jsonObject.get(LSN);

            ClickHouseSinkConnectorConfig config =
                    new ClickHouseSinkConnectorConfig(
                            PropertiesHelper.toMap(finalProps1));

            DebeziumJdbcStorageOperations debeziumJdbcStorageOperations =
                    new DebeziumJdbcStorageOperations();
            HikariDataSource ds = HikariDbSource.getInstance(SYSTEM_DB);
            Connection connection = ds.getConnection();
            debeziumJdbcStorageOperations.updateDebeziumStorageStatus(connection, config,
                    finalProps1, lsn);
            connection.close();
            log.info("Received update-binlog request: " + body);
        });

        Properties finalProps = props;
        app.get("/start", ctx -> {
            finalProps.putAll(userProperties);
            CompletableFuture<String> cf =
                    ClickHouseDebeziumEmbeddedApplication.startDebeziumEventLoop(injector, finalProps);
            ctx.result("Started Replication...., this might take 60 seconds....");
        });

        app.get("/restart", ctx -> {
            log.info("Restarting sink connector");
            ClickHouseDebeziumEmbeddedApplication.stop();

            finalProps.putAll(userProperties);
            CompletableFuture<String> cf =
                    ClickHouseDebeziumEmbeddedApplication.startDebeziumEventLoop(injector, finalProps);
            ctx.result("Started Replication....");
        });

        MySQLDDLParserService finalSqlddlParserService = sqlddlParserService;
        app.post("/ddl-translate", ctx -> {
            String ddl = ctx.body();
            log.info(String.format("Received DDL for translation %s", ddl));
            // get the ddl value from JSON.
            JSONObject jsonObject = (JSONObject) new JSONParser().parse(ddl);
            String ddlValue = (String) jsonObject.get("ddl");
            StringBuffer clickHouseQuery = new StringBuffer();
            finalSqlddlParserService.parseSql(ddlValue, "employees", clickHouseQuery);
            ctx.result(clickHouseQuery.toString());
        });
    }

    /**
     * Stops the Javalin REST API server.
     */
    public static void stop() {
        if (app != null)
            app.stop();
    }

    /**
     * Returns the Javalin app instance.
     *
     * @return the Javalin application.
     */
    public static Javalin app() {
        return app;
    }
}
