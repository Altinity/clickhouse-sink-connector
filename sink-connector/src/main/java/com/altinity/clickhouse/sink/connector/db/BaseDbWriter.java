package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BaseDbWriter {

    protected ClickHouseConnection conn;

    private String hostName;
    private Integer port;
    protected String database;
    private String userName;
    private String password;

    private ZoneId serverTimeZone;

    private ClickHouseSinkConnectorConfig config;

    private static final Logger log = LogManager.getLogger(BaseDbWriter.class);

    public BaseDbWriter(
            String hostName,
            Integer port,
            String database,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config,
            ClickHouseConnection conn
    ) {

        this.hostName = hostName;
        this.port = port;
        this.database = database;
        this.userName = userName;
        this.password = password;

        this.config = config;
        this.conn = conn;
        //this.createConnection(connectionUrl, "Agent_1", userName, password);
        this.serverTimeZone = new DBMetadata().getServerTimeZone(this.conn);
    }

    /**
     * Function to split JDBC properties string into Properties object.
     * @param jdbcProperties
     * @return
     */
    public static Properties splitJdbcProperties(String jdbcProperties) {
        // Split JDBC properties(delimited by equal sign) string delimited by comma.
        String[] splitProperties = jdbcProperties.split(",");

        // Iterate through splitProperties and convert to Properties.
        Properties properties = new Properties();
        Arrays.stream(splitProperties).forEach(property -> {
            String[] keyValue = property.split("=");
            properties.setProperty(keyValue[0], keyValue[1]);
        });

        return properties;
    }

    public ClickHouseConnection getConnection() {
        return this.conn;
    }
    public static String getConnectionString(String hostName, Integer port, String database) {
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
    public static ClickHouseConnection createConnection(String url, String clientName, String userName, String password,
                                 ClickHouseSinkConnectorConfig config) {

        String jdbcParams = "";
        ClickHouseConnection conn = null;
        if(config != null) {
            config.getString(ClickHouseSinkConnectorConfigVariables.JDBC_PARAMETERS.toString());
        }

        try {
            Properties properties = new Properties();
            properties.setProperty("client_name", clientName);
            properties.setProperty("custom_settings", "allow_experimental_object_type=1,insert_allow_materialized_columns=1");

            if(!jdbcParams.isEmpty()) {
                log.info("**** JDBC PARAMS from configuration:" + jdbcParams);
                Properties userProps = splitJdbcProperties(jdbcParams);
                properties.putAll(userProps);
            }
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);
            conn = dataSource.getConnection(userName, password);
        } catch (Exception e) {
            log.error("Error creating ClickHouse connection" + e);
        }

        return conn;
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
            String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port,
                    database);
            conn = BaseDbWriter.createConnection(jdbcUrl, "Client_1", userName, password, config);
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
        if(this.conn == null || this.conn.isClosed()) {
            String connectionUrl = getConnectionString(hostName, port, database);
            //this.createConnection(connectionUrl, "Agent_1", userName, password);
        }
        ResultSet rs = this.conn.prepareStatement(sql).executeQuery();
        return rs;

    }

    /**
     * Function to get the clickhouse version.
     * @return version as string.
     * @throws SQLException
     */
    public String getClickHouseVersion() throws SQLException {
        return this.executeQuery("SELECT VERSION()");
    }


    public Map<String, String> getColumnsDataTypesForTable(String tableName ) {

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try {
            if (conn == null) {
                log.error("Error with DB connection");
                return result;
            }

            ResultSet columns = conn.getMetaData().getColumns(null, database,
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

