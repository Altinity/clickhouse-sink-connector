package com.altinity.clickhouse.debezium.embedded.api;

import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.google.inject.Injector;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage.*;
import static com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage.LSN;

public class DebeziumEmbeddedRestApi {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEmbeddedRestApi.class);

    public static void startRestApi(Properties props, Injector injector,
                             DebeziumChangeEventCapture debeziumChangeEventCapture,
                             Properties userProperties) {
        String cliPort = props.getProperty(SinkConnectorLightWeightConfig.CLI_PORT);
        if(cliPort == null || cliPort.isEmpty()) {
            cliPort = "7000";
        }

        Javalin app = Javalin.create().start(Integer.parseInt(cliPort));
        app.get("/", ctx -> {
            ctx.result("Hello World");
        });
        app.get("/stop", ctx -> {
            ClickHouseDebeziumEmbeddedApplication.stop();
        });
        Properties finalProps1 = props;
        app.get("/status", ctx -> {
            ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(finalProps1));

            ctx.result(debeziumChangeEventCapture.getDebeziumStorageStatus(config, finalProps1));

        });

        app.post("/binlog", ctx -> {
            if(debeziumChangeEventCapture.isReplicationRunning()) {
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

            ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(finalProps1));

            if(sourceHost != null && !sourceHost.isEmpty()) {
                userProperties.setProperty("database.hostname", sourceHost);
            }

            if(sourcePort != null && !sourcePort.isEmpty()) {
                userProperties.setProperty("database.port", sourcePort);
            }

            if(sourceUser != null && !sourceUser.isEmpty()) {
                userProperties.setProperty("database.user", sourceUser);
            }

            if(sourcePassword != null && !sourcePassword.isEmpty()) {
                userProperties.setProperty("database.password", sourcePassword);
            }

            if(userProperties.size() > 0) {
                log.info("User Overridden properties: " + userProperties);

            }

            debeziumChangeEventCapture.updateDebeziumStorageStatus(config, finalProps1, binlogFile, binlogPosition,
                    gtid);
            log.info("Received update-binlog request: " + body);
        });

        app.post("/lsn", ctx -> {
            String body = ctx.body();
            JSONObject jsonObject = (JSONObject) new JSONParser().parse(body);
            String lsn = (String) jsonObject.get(LSN);

            ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(PropertiesHelper.toMap(finalProps1));

            debeziumChangeEventCapture.updateDebeziumStorageStatus(config, finalProps1, lsn);
            log.info("Received update-binlog request: " + body);
        });

        Properties finalProps = props;
        app.get("/start", ctx -> {
            finalProps.putAll(userProperties);
            CompletableFuture<String> cf = ClickHouseDebeziumEmbeddedApplication.startDebeziumEventLoop(injector, finalProps);
            ctx.result("Started Replication....");
        });

    }
}
