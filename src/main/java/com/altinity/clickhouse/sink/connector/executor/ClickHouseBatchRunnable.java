package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.Metrics;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.codahale.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runnable object that will be called on
 * a schedule to perform the batch insert of
 * records to Clickhouse.
 */
public class ClickHouseBatchRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseBatchRunnable.class);
    private final ConcurrentLinkedQueue<ClickHouseStruct> records;

    private final ClickHouseSinkConnectorConfig config;

    private final Map<String, String> topic2TableMap;

    public ClickHouseBatchRunnable(ConcurrentLinkedQueue<ClickHouseStruct> records,
                                   ClickHouseSinkConnectorConfig config,
                                   Map<String, String> topic2TableMap) {
        this.records = records;
        this.config = config;
        this.topic2TableMap = topic2TableMap;
    }

    @Override
    public void run() {
        log.info("*************** BULK INSERT TO CLICKHOUSE **************");
        log.info("*************** RECORDS: {}", records.size());
        String dbHostName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL);
        String database = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE);
        Integer port = config.getInt(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT);
        String userName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER);
        String password = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS);
        //String tableName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE);

        UUID blockUuid = UUID.randomUUID();

        // ToDo: Every task should only have one topic.
        // peeking in the buffer might be a good logic?
        ClickHouseStruct peekedRecord = this.records.peek();
        if(peekedRecord != null) {

            String tableName = this.topic2TableMap.get(peekedRecord.getTopic());
            // Initialize Timer to track time taken to transform and insert to Clickhouse.
            Timer timer = Metrics.timer("Bulk Insert: " + blockUuid + " Size:" + records.size());
            Timer.Context context = timer.time();

            DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password, this.config);
            writer.insert(this.records);
            context.stop();

        }

    }
}
