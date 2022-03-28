package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.ClickHouseProtocol;

import java.text.MessageFormat;
import java.util.List;

public class DbWriter {
    ClickHouseNode server;
    public DbWriter() {
        // Keep a singleton of the connection to clickhouse.
        this.server = ClickHouseNode.of("localhost", ClickHouseProtocol.HTTP, 8123, "my_db");

    }

    public void insert(String table, List<String> rows, List<String> values) {

        String insertQuery = MessageFormat.format("insert into {0} {1} values({2})",
                table, rows.toArray(), values.toArray());
        if(this.server != null) {
            ClickHouseClient.send(this.server, insertQuery);
        } else {
            // Error .
        }

    }
}
