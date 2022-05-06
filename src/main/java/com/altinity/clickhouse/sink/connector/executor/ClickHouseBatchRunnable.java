package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.Metrics;
import com.altinity.clickhouse.sink.connector.Utils;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.codahale.metrics.Timer;
import org.apache.commons.lang3.tuple.MutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runnable object that will be called on
 * a schedule to perform the batch insert of
 * records to Clickhouse.
 */
public class ClickHouseBatchRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseBatchRunnable.class);
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records;

    private final ClickHouseSinkConnectorConfig config;

    private final Map<String, String> topic2TableMap;

    public ClickHouseBatchRunnable(ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records,
                                   ClickHouseSinkConnectorConfig config,
                                   Map<String, String> topic2TableMap) {
        this.records = records;
        this.config = config;
        if(topic2TableMap == null) {
            this.topic2TableMap = new HashMap();
        } else {
            this.topic2TableMap = topic2TableMap;
        }
    }

    @Override
    public void run() {

        String dbHostName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL);
        String database = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE);
        Integer port = config.getInt(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT);
        String userName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER);
        String password = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS);
        //String tableName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE);


        for (Map.Entry<String, ConcurrentLinkedQueue<ClickHouseStruct>> entry : this.records.entrySet()) {

            String topicName = entry.getKey();

            //The user parameter will override the topic mapping to table.
            String tableName;

            if (this.topic2TableMap.containsKey(topicName) == false) {
                tableName = Utils.getTableNameFromTopic(topicName);
                this.topic2TableMap.put(topicName, tableName);
            } else {
                tableName = this.topic2TableMap.get(topicName);
            }

            if (entry.getValue().size() > 0) {
                UUID blockUuid = UUID.randomUUID();

                int numRecords = entry.getValue().size();
                log.info("*************** BULK INSERT TO CLICKHOUSE **************");
                log.info("*************** RECORDS: {}", numRecords);

                // Initialize Timer to track time taken to transform and insert to Clickhouse.
                Timer timer = Metrics.timer("Bulk Insert: " + blockUuid + " Size:" + entry.getValue().size());
                Timer.Context context = timer.time();

                DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password, this.config);
                MutablePair<Long, Long> offsets = writer.insert(entry.getValue());
                context.stop();

                Metrics.getClickHouseSinkRecordsCounter()
                        .tag("UUID", blockUuid.toString())
                        .tag("topic", topicName)
                        .tag("table", tableName)
                        .tag("minOffset", offsets.left.toString())
                        .tag("maxOffset", offsets.right.toString())

                        .register(Metrics.meterRegistry()).increment(numRecords);

            }
        }
    }
}
