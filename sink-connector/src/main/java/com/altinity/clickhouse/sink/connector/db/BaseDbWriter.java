package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.lang3.StringUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class BaseDbWriter {

    protected ClickHouseConnection conn;

    private String hostName;
    private Integer port;
    protected String database;
    private String userName;
    private String password;

    private static final Logger log = LoggerFactory.getLogger(BaseDbWriter.class);

    public BaseDbWriter(
            String hostName,
            Integer port,
            String database,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config
    ) {

        this.hostName = hostName;
        this.port = port;
        this.database = database;
        this.userName = userName;
        this.password = password;

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
    public Map<String, String> getColumnsDataTypesForTable(String tableName, String sourceTimeZone) {

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try {
            if (this.conn == null) {
                log.error("Error with DB connection");
                return result;
            }

            ResultSet columns = this.conn.getMetaData().getColumns(null, this.database,
                     tableName, null);
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");
                if (typeName.contains("DateTime")) {
                    typeName = addTimeZoneToColumnDefinition(typeName, sourceTimeZone);
                } 

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

    /**
     * Function to execute query.
     * @param sql
     * @return
     * @throws SQLException
     */
    public String executeQuery(String sql) throws SQLException {
        String result = null;
        if(this.conn == null) {
            String connectionUrl = getConnectionString(hostName, port, database);
            this.createConnection(connectionUrl, "Agent_1", userName, password);
        }
        ResultSet rs = this.conn.prepareStatement(sql).executeQuery();
        if(rs != null) {
            while(rs.next()) {
                result = rs.getString(1);
            }
        }

        return result;
    }

    /**
     * Function to execute query.
     * @param sql
     * @return
     * @throws SQLException
     */
    public ResultSet executeQueryWithResultSet(String sql) throws SQLException {
        if(this.conn == null) {
            String connectionUrl = getConnectionString(hostName, port, database);
            this.createConnection(connectionUrl, "Agent_1", userName, password);
        }
        ResultSet rs = this.conn.prepareStatement(sql).executeQuery();
        return rs;

    }

    public String addTimeZoneToColumnDefinition(String typeName, String timeZone) {
        Pattern pattern = Pattern.compile("DateTime(64)?(\\([^\\)]*\\))?");
        Matcher matcher = pattern.matcher(typeName);
        StringBuffer result = new StringBuffer("");
        int cursor = 0;
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            result.append(typeName.substring(cursor, start));
            cursor = end;
            String occurrence = typeName.substring(start, end);
            if (occurrence.contains("DateTime64")) {
                String[] params = StringUtils.substringBetween(occurrence, "(", ")").split(",");
                String precision = params[0].trim();
                result.append(String.format("DateTime64(%s,\\'%s\\')", precision, timeZone));
            } else {
                result.append(String.format("DateTime(\\'%s\\')", timeZone));
            }
        }
        result.append(typeName.substring(cursor));
        return result.toString();
    }

    /**
     * Function to get the clickhouse version.
     * @return version as string.
     * @throws SQLException
     */
    public String getClickHouseVersion() throws SQLException {
        return this.executeQuery("SELECT VERSION()");
    }
}

