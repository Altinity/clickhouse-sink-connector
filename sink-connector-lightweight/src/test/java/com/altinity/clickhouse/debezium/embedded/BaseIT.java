package com.altinity.clickhouse.debezium.embedded;

import java.sql.*;

public class BaseIT {
    /**
     * Function to run JDBC Query
     */
    public void runJDBCQuery(String dbUrl, String userName, String password, final String query)  {
        // Open a connection
        try (Connection conn = DriverManager.getConnection(dbUrl, userName, password)) {
            Statement stmt = conn.createStatement();
            //stmt.execute(query);
            ResultSet rs = stmt.executeQuery(query);
//            // Extract data from result set
            while (rs.next()) {
                System.out.println("query result" + rs.next());
            }//                // Retrieve by column name
//                System.out.println("COUNT QUERY" + rs.getInt("count"));
            stmt.closeOnCompletion();
//            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
