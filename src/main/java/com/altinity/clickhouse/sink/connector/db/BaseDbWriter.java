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

            // select null as TABLE_CAT, database as TABLE_SCHEM, table as TABLE_NAME,
            // name as COLUMN_NAME, toInt32(1111) as DATA_TYPE, type as TYPE_NAME,
            // toInt32(0) as COLUMN_SIZE, 0 as BUFFER_LENGTH, cast(null as Nullable(Int32)) as DECIMAL_DIGITS,
            // 10 as NUM_PREC_RADIX, toInt32(position(type, 'Nullable(') >= 1 ? 1 : 0) as NULLABLE, comment as REMARKS,
            // default_expression as COLUMN_DEF, 0 as SQL_DATA_TYPE, 0 as SQL_DATETIME_SUB, cast(null as Nullable(Int32))
            // as CHAR_OCTET_LENGTH, position as ORDINAL_POSITION, position(type, 'Nullable(') >= 1 ? 'YES' : 'NO' as IS_NULLABLE,
            // null as SCOPE_CATALOG, null as SCOPE_SCHEMA, null as SCOPE_TABLE, null as SOURCE_DATA_TYPE, 'NO' as IS_AUTOINCREMENT,
            // 'NO' as IS_GENERATEDCOLUMN from system.columns where database like '%' and table like 'departments' and name like '%'
            // getMetaData runs the above query to get metadata about the table.
            ResultSet columns = this.conn.getMetaData().getColumns(null, null,
                    tableName, null);

            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");
                log.info("*** METADATA column count, DATABASE:" + this.conn.getCurrentDatabase() + " Table: " + tableName
                        + " COLUMN NAME:" + columnName + " TYPE NAME:" + typeName);

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

