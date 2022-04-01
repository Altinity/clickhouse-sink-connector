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
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Properties;

/**
 * Class that abstracts all functionality
 * related to interacting with Clickhouse DB.
 */
public class DbWriter {
    //ClickHouseNode server;
    ClickHouseConnection conn;

    /**
     * Constructor to create Clickhouse DB connection.
     */
    public DbWriter() {
        //ToDo: Read from Config
        String url = "jdbc:ch://localhost/test";
        String clientName = "Agent_1";
        String userName = "admin";
        String password = "root";

        this.createConnection(url, clientName, userName, password);
    }

    /**
     * Function to create Connection using the JDBC Driver
     * @param url url with the JDBC format jdbc:ch://localhost/test
     * @param clientName Client Name
     * @param userName UserName
     * @param password Password
     */
    public void createConnection(String url, String clientName, String userName, String password) {
        try {
            Properties properties = new Properties();
            properties.setProperty("client_name", clientName);
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);

            this.conn = dataSource.getConnection(userName, password);
        } catch(Exception e) {
            System.out.println("Error creating SQL connection" + e);
        }
    }

    /**
     * Function to retrieve Clickhouse http client Connection
     * @return
     */
    private ClickHouseNode getHttpConnection() {
        ClickHouseCredentials credentials = ClickHouseCredentials.fromUserAndPassword("admin", "root");
        return ClickHouseNode.builder().credentials(credentials).database("test").port(8123).host("localhost").build();

    }

    /**
     * Formatter for Raw Insert SQL query with placeholders for values
     * with this format insert into <tablename> values(?, ?, ?, )
     * @param tableName Table Name
     * @param numFields Number of fields with placeholders
     * @return
     */
    public String getInsertQuery(String tableName, int numFields) {
        StringBuffer insertQuery = new StringBuffer().append("insert into ")
                .append(tableName).append(" values(");
        for(int i = 0; i < numFields; i++) {
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
     * Function where the Kafka connect data types
     * are mapped to Clickhouse data types and a batch insert is performed.
     * @param table Table Name
     * @param afterValue after Value (With Insert: before is always empty_
     * @param fields Kafka connect fields
     */
    public void insert(String table, Struct afterValue, List<Field> fields){

        table = "test";
        String insertQueryTemplate = this.getInsertQuery(table, fields.size());
        try (PreparedStatement ps = this.conn.prepareStatement(insertQueryTemplate)) {

            int index = 1;
            for(Field f: fields) {
                Schema.Type fieldType = f.schema().type();
                Object value = afterValue.get(f);

                // Text columns
                if(fieldType == Schema.Type.STRING) {

                    ps.setString(index, (String) value);
                }
                else if(fieldType == Schema.INT8_SCHEMA.type() ||
                        fieldType == Schema.INT16_SCHEMA.type() ||
                        fieldType == Schema.INT32_SCHEMA.type()) {
                    if(f.schema().name() == Date.SCHEMA_NAME) {
                        // Date field arrives as INT32 with schema name set to io.debezium.time.Date
                        ps.setDate(index, (java.sql.Date) value);
                    } else {
                        ps.setInt(index, (Integer) value);
                    }
                } else if(fieldType == Schema.FLOAT32_SCHEMA.type() ||
                        fieldType == Schema.FLOAT64_SCHEMA.type()) {
                    ps.setFloat(index, (Float) value);
                } else if(fieldType == Schema.BOOLEAN_SCHEMA.type()) {
                    ps.setBoolean(index, (Boolean) value);
                }
                index++;

            }
            ps.addBatch(); // append parameters to the query

            ps.executeBatch(); // issue the composed query: insert into mytable values(...)(...)...(...)
        } catch(Exception e) {
            System.out.println("insert Batch exception" + e);
        }
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
