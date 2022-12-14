package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class BaseDbWriter {

    protected ClickHouseConnection conn;

    private static final Logger log = LoggerFactory.getLogger(BaseDbWriter.class);

    public BaseDbWriter(
            String hostName,
            Integer port,
            String database,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config
    ) {

        String connectionUrl = getConnectionString(hostName, port, database);
        this.createConnection(connectionUrl, "Agent_1", userName, password);
    }

    public ClickHouseConnection getConnection() {
        return this.conn;
    }
    public String getConnectionString(String hostName, Integer port, String database) {
        return String.format("jdbc:clickhouse://%s:%s/%s", hostName, port, database);
    }

    /**
     * Function to create Connection using the JDBC Driver
     *
     * @param url        url with the JDBC format jdbc:ch://localhost/test
     * @param clientName Client Name
     * @param userName   UserName
     * @param password   Password
     */
    protected void createConnection(String url, String clientName, String userName, String password) {
        try {
            Properties properties = new Properties();
            properties.setProperty("client_name", clientName);
            properties.setProperty("custom_settings", "allow_experimental_object_type=1");
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);

            this.conn = dataSource.getConnection(userName, password);
        } catch (Exception e) {
            log.error("Error creating ClickHouse connection" + e);
        }
    }

    /**
     * Function that uses the DatabaseMetaData JDBC functionality
     * to get the column name and column data type as key/value pair.
     */
    public Map<String, String> getColumnsDataTypesForTable(String tableName) {

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try {
            if (this.conn == null) {
                log.error("Error with DB connection");
                return result;
            }
            // TODO : add check for DB name 
            ResultSet columns = this.conn.getMetaData().getColumns(null, null,
                    tableName, null);
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");

//                Object dataType = columns.getString("DATA_TYPE");
//                String columnSize = columns.getString("COLUMN_SIZE");
//                String isNullable = columns.getString("IS_NULLABLE");
//                String isAutoIncrement = columns.getString("IS_AUTOINCREMENT");

                result.put(columnName, typeName);
            }
        } catch (SQLException sq) {
            log.error("Exception retrieving Column Metadata", sq);
        }
        return result;
    }
}

