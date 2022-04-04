package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.client.ClickHouseCredentials;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import io.debezium.time.Time;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

import io.debezium.time.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Class that abstracts all functionality
 * related to interacting with Clickhouse DB.
 */
public class DbWriter {
    //ClickHouseNode server;
    ClickHouseConnection conn;
    private static final Logger log = LoggerFactory.getLogger(DbWriter.class);

    /**
     * Constructor to create Clickhouse DB connection.
     */
    public DbWriter() {
        log.info("DbWriter()");
        //ToDo: Read from Config
        String url = "jdbc:ch://localhost/default";
        String clientName = "Agent_1";
        String userName = "admin";
        String password = "root";

        this.createConnection(url, clientName, userName, password);
    }

    /**
     * Function to create Connection using the JDBC Driver
     *
     * @param url        url with the JDBC format jdbc:ch://localhost/test
     * @param clientName Client Name
     * @param userName   UserName
     * @param password   Password
     */
    public void createConnection(String url, String clientName, String userName, String password) {
        try {
            Properties properties = new Properties();
            properties.setProperty("client_name", clientName);
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);

            this.conn = dataSource.getConnection(userName, password);
        } catch (Exception e) {
            log.warn("Error creating SQL connection" + e);
        }
    }

    /**
     * Formatter for SQL 'Insert' query with placeholders for values
     * insert into <table name> values(?, ?, ?)
     *
     * @param tableName Table Name
     * @param numFields Number of fields with placeholders
     * @return
     */
    public String getInsertQuery(String tableName, int numFields) {
        StringBuffer insertQuery = new StringBuffer()
                .append("insert into ")
                .append(tableName)
                .append(" values(");
        for (int i = 0; i < numFields; i++) {
            insertQuery.append("?");
            if (i == numFields - 1) {
                insertQuery.append(")");
            } else {
                insertQuery.append(",");
            }
        }
        return insertQuery.toString();
    }

    /**
     * Creates INSERT statement and runs it over connection
     * @param records
     */
    public void insert(ConcurrentLinkedQueue<Struct> records) {

        String table = "employees";

        if (records.isEmpty()) {
            log.info("No Records to process");
            return;
        }

        // Get the first record to get length of columns
        Struct peekRecord = records.peek();
        String insertQueryTemplate = this.getInsertQuery(table, peekRecord.schema().fields().size());

        try (PreparedStatement ps = this.conn.prepareStatement(insertQueryTemplate)) {

            Iterator iterator = records.iterator();
            while (iterator.hasNext()) {
                Struct record = (Struct) iterator.next();
                List<Field> fields = record.schema().fields();

                int index = 1;
                for (Field field : fields) {
                    Schema.Type type = field.schema().type();
                    String schemaName = field.schema().name();
                    Object value = record.get(field);

                    boolean isFieldTypeInt = (type == Schema.INT8_SCHEMA.type()) ||
                            (type == Schema.INT16_SCHEMA.type()) ||
                            (type == Schema.INT32_SCHEMA.type());

                    boolean isFieldTypeFloat = (type == Schema.FLOAT32_SCHEMA.type()) ||
                            (type == Schema.FLOAT64_SCHEMA.type());

                    // Text columns
                    if (type == Schema.Type.STRING) {
                        ps.setString(index, (String) value);
                    } else if (isFieldTypeInt) {
                        if (schemaName != null && schemaName.equalsIgnoreCase(Date.SCHEMA_NAME)) {
                            // Date field arrives as INT32 with schema name set to io.debezium.time.Date
                            long msSinceEpoch = TimeUnit.DAYS.toMillis((Integer) value);
                            java.util.Date date = new java.util.Date(msSinceEpoch);
                            java.sql.Date sqlDate = new java.sql.Date(date.getTime());
                            ps.setDate(index, sqlDate);
                        } else {
                            ps.setInt(index, (Integer) value);
                        }
                    } else if (isFieldTypeFloat) {
                        ps.setFloat(index, (Float) value);
                    } else if (type == Schema.BOOLEAN_SCHEMA.type()) {
                        ps.setBoolean(index, (Boolean) value);
                    }
                    index++;
                }
                // Append parameters to the query
                ps.addBatch();
            }

            // Issue the composed query: insert into mytable values(...)(...)...(...)
            ps.executeBatch();
        } catch (Exception e) {
            log.warn("insert Batch exception" + e);
        }
    }

    /**
     * Function to retrieve Clickhouse http client Connection
     *
     * @return
     */
    private ClickHouseNode getHttpConnection() {
        ClickHouseCredentials credentials = ClickHouseCredentials.fromUserAndPassword("admin", "root");
        return ClickHouseNode.builder().credentials(credentials).database("test").port(8123).host("localhost").build();

    }

    /**
     * Function to insert records using Http Connection.
     */
    public void insertUsingHttpConnection() {

//        table = "test_hello2";
//        String insertQuery = MessageFormat.format("insert into {0} {1} values({2})",
//                table, "(id, message)", "1, 'hello'");
////        if(this.server != null) {
////            CompletableFuture<List<ClickHouseResponseSummary>> future = ClickHouseClient.send(this.server, insertQuery);
////            try {
////                future.get();
////            } catch (InterruptedException e) {
////                e.printStackTrace();
////            } catch (ExecutionException e) {
////                e.printStackTrace();
////            }
////        } else {
////            // Error .
////        }
    }
}
