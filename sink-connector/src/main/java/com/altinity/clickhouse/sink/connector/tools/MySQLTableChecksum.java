package com.altinity.clickhouse.sink.connector.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * MySQL Table Checksum Calculator
 * Calculates a checksum for MySQL tables compatible with ClickHouse comparison.
 * 
 * Credits: https://www.sisense.com/blog/hashing-tables-to-ensure-consistency-in-postgres-redshift-and-mysql/
 */
public class MySQLTableChecksum {
    
    private static final Logger log = LogManager.getLogger(MySQLTableChecksum.class);
    private static Args args;
    
    private static final String[] BINARY_DATATYPES = {
        "blob", "varbinary", "point", "geometry", "bit", "binary", 
        "linestring", "geomcollection", "multilinestring", "multipolygon", 
        "multipoint", "polygon"
    };
    
    public static class Args {
        String mysqlHost;
        String mysqlUser;
        String mysqlPassword;
        String defaultsFile = "~/.my.cnf";
        String mysqlDatabase;
        int mysqlPort = 3306;
        String tablesRegex;
        String where;
        String orderBy;
        String ignoreTablesRegex;
        boolean noWc = false;
        boolean debugOutput = false;
        String debugLimit;
        String binaryEncoding = "hex";
        String minDateValue = "1900-01-01";
        String maxDateValue = "2299-12-31";
        String minDatetimeValue = "1970-01-01 00:00:00";
        String maxDatetimeValue = "2299-12-31 23:59:59";
        boolean debug = false;
        List<String> excludeColumns = new ArrayList<>();
        int threadsPerTable = 1;
        int chunkSize = 10000;
        int threads = 1;
    }
    
    private static class ChecksumResult {
        long cnt;
        long a;
        long b;
        long c;
        long d;
        
        ChecksumResult(long cnt, long a, long b, long c, long d) {
            this.cnt = cnt;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }
        
        void add(ChecksumResult other) {
            this.cnt += other.cnt;
            this.a += other.a;
            this.b += other.b;
            this.c += other.c;
            this.d += other.d;
        }
    }
    
    private static class Chunk {
        long minPk;
        long maxPk;
    }
    
    private static class TableChecksumQuery {
        String query;
        String selectQuery;
        String orderByColumns;
        String externalColumnTypes;
        
        TableChecksumQuery(String query, String selectQuery, String orderByColumns, String externalColumnTypes) {
            this.query = query;
            this.selectQuery = selectQuery;
            this.orderByColumns = orderByColumns;
            this.externalColumnTypes = externalColumnTypes;
        }
    }
    
    public static void main(String[] args) {
        try {
            MySQLTableChecksum.args = parseArguments(args);
            
            // Setup logging
            if (MySQLTableChecksum.args.debug) {
                // Enable debug logging
                System.setProperty("log4j.logger.com.altinity.clickhouse.sink.connector.tools", "DEBUG");
            }
            
            // Validate and resolve credentials
            String mysqlUser = MySQLTableChecksum.args.mysqlUser;
            String mysqlPassword = MySQLTableChecksum.args.mysqlPassword;
            
            if (MySQLTableChecksum.args.mysqlPassword != null) {
                log.warn("Using password on the command line is not secure, please specify a config file");
                if (MySQLTableChecksum.args.mysqlUser == null) {
                    throw new IllegalArgumentException("--mysql_user must be specified");
                }
            } else {
                String[] credentials = resolveCredentialsFromConfig(MySQLTableChecksum.args.defaultsFile);
                mysqlUser = credentials[0];
                mysqlPassword = credentials[1];
            }
            
            // Get list of tables
            List<String> tables = getTablesFromRegexp(mysqlUser, mysqlPassword, MySQLTableChecksum.args.tablesRegex);
            
            // Process tables in parallel
            ExecutorService executor = Executors.newFixedThreadPool(MySQLTableChecksum.args.threads);
            List<Future<?>> futures = new ArrayList<>();
            Map<Future<?>, String> futureToTable = new HashMap<>();
            
            for (String table : tables) {
                final String finalMysqlUser = mysqlUser;
                final String finalMysqlPassword = mysqlPassword;
                Future<?> future = executor.submit(() -> 
                    calculateChecksum(table, finalMysqlUser, finalMysqlPassword, MySQLTableChecksum.args.excludeColumns)
                );
                futures.add(future);
                futureToTable.put(future, table);
            }
            
            // Wait for all tasks to complete
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    log.error("Exception in table " + futureToTable.get(future), e.getCause());
                    throw e.getCause();
                }
            }
            
            executor.shutdown();
            log.debug("Exiting Main Thread");
            System.exit(0);
            
        } catch (InterruptedException e) {
            log.info("Received interrupt");
            System.exit(1);
        } catch (Throwable e) {
            log.error("Exception in main thread: " + e.getMessage(), e);
            System.exit(1);
        }
    }
    
    private static Args parseArguments(String[] args) {
        Args result = new Args();
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            switch (arg) {
                case "--mysql_host":
                    result.mysqlHost = args[++i];
                    break;
                case "--mysql_user":
                    result.mysqlUser = args[++i];
                    break;
                case "--mysql_password":
                    result.mysqlPassword = args[++i];
                    break;
                case "--defaults_file":
                    result.defaultsFile = args[++i];
                    break;
                case "--mysql_database":
                    result.mysqlDatabase = args[++i];
                    break;
                case "--mysql_port":
                    result.mysqlPort = Integer.parseInt(args[++i]);
                    break;
                case "--tables_regex":
                    result.tablesRegex = args[++i];
                    break;
                case "--where":
                    result.where = args[++i];
                    break;
                case "--order_by":
                    result.orderBy = args[++i];
                    break;
                case "--ignore_tables_regex":
                    result.ignoreTablesRegex = args[++i];
                    break;
                case "--no_wc":
                    result.noWc = true;
                    break;
                case "--debug_output":
                    result.debugOutput = true;
                    break;
                case "--debug_limit":
                    result.debugLimit = args[++i];
                    break;
                case "--binary_encoding":
                    result.binaryEncoding = args[++i];
                    break;
                case "--min_date_value":
                    result.minDateValue = args[++i];
                    break;
                case "--max_date_value":
                    result.maxDateValue = args[++i];
                    break;
                case "--min_datetime_value":
                    result.minDatetimeValue = args[++i];
                    break;
                case "--max_datetime_value":
                    result.maxDatetimeValue = args[++i];
                    break;
                case "--debug":
                    result.debug = true;
                    break;
                case "--exclude_columns":
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        result.excludeColumns.add(args[++i]);
                    }
                    break;
                case "--threads_per_table":
                    result.threadsPerTable = Integer.parseInt(args[++i]);
                    break;
                case "--chunk_size":
                    result.chunkSize = Integer.parseInt(args[++i]);
                    break;
                case "--threads":
                    result.threads = Integer.parseInt(args[++i]);
                    break;
                case "--help":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown argument: " + arg);
                    printUsage();
                    System.exit(1);
            }
        }
        
        // Validate required arguments
        if (result.mysqlHost == null || result.mysqlDatabase == null || result.tablesRegex == null) {
            System.err.println("Required arguments missing!");
            printUsage();
            System.exit(1);
        }
        
        return result;
    }
    
    private static void printUsage() {
        System.out.println("MySQL Table Checksum Calculator");
        System.out.println("\nRequired arguments:");
        System.out.println("  --mysql_host HOST          MySQL host");
        System.out.println("  --mysql_database DATABASE  MySQL database");
        System.out.println("  --tables_regex REGEX       Table regular expression");
        System.out.println("\nOptional arguments:");
        System.out.println("  --mysql_user USER          MySQL user");
        System.out.println("  --mysql_password PASS      MySQL password (discouraged, use config file)");
        System.out.println("  --defaults_file FILE       MySQL config file (default: ~/.my.cnf)");
        System.out.println("  --mysql_port PORT          MySQL port (default: 3306)");
        System.out.println("  --where CLAUSE             WHERE clause");
        System.out.println("  --order_by CLAUSE          ORDER BY clause");
        System.out.println("  --ignore_tables_regex RX   Ignore table regex");
        System.out.println("  --no_wc                    Use --tables_regex as exact table name");
        System.out.println("  --debug_output             Output raw format to file");
        System.out.println("  --debug_limit N            Limit debug output lines");
        System.out.println("  --binary_encoding ENC      hex or base64 (default: hex)");
        System.out.println("  --min_date_value DATE      Minimum Date32/DateTime64 (default: 1900-01-01)");
        System.out.println("  --max_date_value DATE      Maximum Date32/DateTime64 (default: 2299-12-31)");
        System.out.println("  --min_datetime_value DT    Min Datetime64 (default: 1970-01-01 00:00:00)");
        System.out.println("  --max_datetime_value DT    Max Datetime64 (default: 2299-12-31 23:59:59)");
        System.out.println("  --debug                    Enable debug logging");
        System.out.println("  --exclude_columns COL...   Columns to exclude");
        System.out.println("  --threads_per_table N      Parallel threads per table (default: 1)");
        System.out.println("  --chunk_size N             Chunk size (default: 10000)");
        System.out.println("  --threads N                Number of tables to process in parallel (default: 1)");
    }
    
    private static Connection getMySQLConnection(String host, String user, String password, int port, String database) 
            throws SQLException {
        String url = String.format("jdbc:mysql://%s:%d/%s?charset=utf8mb4", host, port, database);
        return DriverManager.getConnection(url, user, password);
    }
    
    private static String[] resolveCredentialsFromConfig(String configFile) throws IOException {
        configFile = configFile.replace("~", System.getProperty("user.home"));
        
        if (!Files.exists(Paths.get(configFile))) {
            throw new IllegalArgumentException("Config file does not exist: " + configFile);
        }
        
        if (!configFile.endsWith(".cnf")) {
            throw new IllegalArgumentException("Supported configuration extensions: .cnf");
        }
        
        Properties config = new Properties();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            boolean inClientSection = false;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.equals("[client]")) {
                    inClientSection = true;
                    continue;
                } else if (line.startsWith("[")) {
                    inClientSection = false;
                    continue;
                }
                
                if (inClientSection && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    config.setProperty(parts[0].trim(), parts[1].trim());
                }
            }
        }
        
        String user = config.getProperty("user");
        String password = config.getProperty("password");
        
        if (user == null || password == null) {
            throw new IllegalArgumentException("Expected [client] section with 'user' and 'password' in " + configFile);
        }
        
        log.debug("mysql_user " + user + " mysql_password ****");
        return new String[]{user, password};
    }
    
    private static boolean isBinaryDatatype(String dataType) {
        String lowerDataType = dataType.toLowerCase();
        for (String binaryType : BINARY_DATATYPES) {
            if (lowerDataType.contains(binaryType)) {
                return true;
            }
        }
        return false;
    }
    
    private static List<String> getTablesFromRegexp(String user, String password, String tablesRegex) 
            throws SQLException {
        if (args.noWc) {
            return Collections.singletonList(tablesRegex);
        }
        
        try (Connection conn = getMySQLConnection(args.mysqlHost, user, password, args.mysqlPort, args.mysqlDatabase)) {
            String sql = String.format(
                "SELECT TABLE_NAME as table_name FROM information_schema.tables " +
                "WHERE table_type='BASE TABLE' AND table_schema='%s' AND TABLE_NAME RLIKE '%s' ORDER BY 1",
                args.mysqlDatabase, tablesRegex
            );
            
            List<String> tables = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
            return tables;
        }
    }
    
    private static List<String> getMySQLPkColumns(Connection conn, String database, String table, boolean isInteger) 
            throws SQLException {
        String whereInteger = isInteger ? " AND data_type LIKE '%int%'" : "";
        String sql = String.format(
            "SELECT column_name as COLUMN_NAME FROM information_schema.columns " +
            "WHERE table_schema='%s' AND table_name='%s' AND column_key='PRI' %s ORDER BY ORDINAL_POSITION",
            database, table, whereInteger
        );
        
        List<String> pkColumns = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                pkColumns.add(rs.getString("COLUMN_NAME"));
            }
        }
        
        log.debug("PK columns: " + pkColumns);
        return pkColumns;
    }
    
    private static long[] getMinMaxPkValue(Connection conn, String table, String pk, String where) 
            throws SQLException {
        String sql = String.format("SELECT MIN(%s) as min_pk, MAX(%s) as max_pk FROM `%s` WHERE %s", 
                                   pk, pk, table, where);
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                Object minPk = rs.getObject("min_pk");
                Object maxPk = rs.getObject("max_pk");
                
                if (minPk == null || maxPk == null) {
                    return null;
                }
                
                return new long[]{((Number) minPk).longValue(), ((Number) maxPk).longValue()};
            }
        }
        return null;
    }
    
    private static long estimateTableCount(Connection conn, String table, String where, String pk, 
                                          long minPk, long maxPk) throws SQLException {
        String sql = String.format("EXPLAIN SELECT * FROM `%s` WHERE %s AND %s BETWEEN %d AND %d", 
                                   table, where, pk, minPk, maxPk);
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong("rows");
            }
        }
        return 0;
    }
    
    private static List<Chunk> divideTableIntoEvenChunks(Connection conn, String table, int chunkSize, 
                                                         String pk, String where) throws SQLException {
        List<Chunk> chunks = new ArrayList<>();
        
        if (pk == null) {
            chunks.add(new Chunk());
            return chunks;
        }
        
        long[] minMax = getMinMaxPkValue(conn, table, pk, where);
        if (minMax == null) {
            log.debug("No data in " + table);
            return chunks;
        }
        
        long minPkValue = minMax[0];
        long maxPkValue = minMax[1];
        long tableRowcount = estimateTableCount(conn, table, where, pk, minPkValue, maxPkValue);
        
        long nbChunks = (tableRowcount / chunkSize) + 1;
        log.debug("nb_chunks = " + nbChunks);
        log.debug("lower_bound " + minPkValue + " max_pk_value " + maxPkValue);
        
        long chunkStep = ((maxPkValue - minPkValue) / nbChunks) + 1;
        long lowerBound = minPkValue;
        long upperBound = lowerBound - 1;
        
        log.debug("lower_bound " + lowerBound + " max_pk_value " + maxPkValue + " chunk_step " + chunkStep);
        
        while (lowerBound <= maxPkValue) {
            lowerBound = upperBound + 1;
            upperBound = lowerBound + chunkStep;
            
            // Verify there's data in the chunk
            String sql = String.format("SELECT %s FROM `%s` WHERE %s AND %s BETWEEN %d AND %d ORDER BY %s LIMIT 1",
                                       pk, table, where, pk, lowerBound, upperBound, pk);
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    Chunk chunk = new Chunk();
                    chunk.minPk = rs.getLong(pk);
                    chunk.maxPk = upperBound;
                    chunks.add(chunk);
                }
            }
        }
        
        log.debug("Estimated table row count " + tableRowcount);
        return chunks;
    }
    
    private static TableChecksumQuery getTableChecksumQuery(String table, Connection conn, 
                                                            String binaryEncoding, String where, 
                                                            List<String> excludedColumns) throws SQLException {
        String sql = String.format(
            "SELECT COLUMN_NAME as column_name, column_type as data_type, IS_NULLABLE as is_nullable " +
            "FROM information_schema.columns WHERE table_schema='%s' AND table_name='%s' ORDER BY ordinal_position",
            args.mysqlDatabase, table
        );
        
        StringBuilder select = new StringBuilder();
        List<String> nullables = new ArrayList<>();
        Map<String, String> dataTypes = new HashMap<>();
        boolean firstColumn = true;
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String columnName = "`" + rs.getString("column_name") + "`";
                String dataType = rs.getString("data_type");
                String isNullable = rs.getString("is_nullable");
                
                if (excludedColumns.contains(rs.getString("column_name"))) {
                    log.info("Excluding column " + rs.getString("column_name"));
                    continue;
                }
                
                if (!firstColumn) {
                    select.append(",");
                }
                
                if ("YES".equals(isNullable)) {
                    nullables.add(columnName);
                }
                
                String selectColumn = buildSelectColumn(columnName, dataType, isNullable, binaryEncoding);
                
                if ("YES".equals(isNullable)) {
                    selectColumn = String.format("IFNULL(%s,'')", selectColumn);
                }
                
                select.append(selectColumn);
                firstColumn = false;
                dataTypes.put(rs.getString("column_name"), dataType);
            }
        }
        
        log.debug("Nullables: " + nullables);
        
        if (!nullables.isEmpty()) {
            select.append(", CONCAT(");
            boolean first = true;
            for (String nullable : nullables) {
                if (!first) {
                    select.append(',');
                } else {
                    first = false;
                }
                select.append("ISNULL(").append(nullable).append(")");
            }
            select.append(")");
        }
        
        List<String> primaryKeyColumns = new ArrayList<>();
        String orderByColumns = "";
        
        if (!primaryKeyColumns.isEmpty()) {
            orderByColumns = String.join(",", primaryKeyColumns);
        }
        
        String query = String.format("SELECT %s as query FROM `%s`.`%s`", 
                                     select, args.mysqlDatabase, table);
        if (where != null && !where.isEmpty()) {
            query += " WHERE " + where;
        }
        
        if (!primaryKeyColumns.isEmpty()) {
            query += " ORDER BY " + orderByColumns;
        }
        
        StringBuilder externalColumnTypes = new StringBuilder();
        for (String column : primaryKeyColumns) {
            externalColumnTypes.append(",").append(column).append(" ").append(dataTypes.get(column));
        }
        
        log.debug("order by columns " + orderByColumns);
        return new TableChecksumQuery(query, select.toString(), orderByColumns, externalColumnTypes.toString());
    }
    
    private static String buildSelectColumn(String columnName, String dataType, String isNullable, 
                                           String binaryEncoding) {
        String selectColumn = "";
        
        if (dataType.contains("json")) {
            // Complex JSON handling - simplified version
            selectColumn = String.format(
                "REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(" +
                "REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(" +
                "CONVERT(JSON_PRETTY(%s) USING utf8mb4),'\":  \"','\": \"')," +
                "'\":\\\\s(-*\\\\d|\\\\[|\\\\{|true|false)','\":$1')," +
                "'\\\\.0\\\\b',''),'\\\\s+(\".*?)\\\\s*','$1'),'\\\\s*\\\\n\\\\s*',''), " +
                "'\\\\\\\\u([0-9A-F]{3})a', '\\\\\\\\u$1A'), '\\\\\\\\u([0-9A-F]{3})b', '\\\\\\\\u$1B'), " +
                "'\\\\\\\\u([0-9A-F]{3})c', '\\\\\\\\u$1C'), '\\\\\\\\u([0-9A-F]{3})d', '\\\\\\\\u$1D'), " +
                "'\\\\\\\\u([0-9A-F]{3})e', '\\\\\\\\u$1E'), '\\\\\\\\u([0-9A-F]{3})f', '\\\\\\\\u$1F')",
                columnName
            );
        } else if (dataType.matches("datetime(\\([0-3]\\))?")) {
            selectColumn = String.format(
                "CASE WHEN %s >= SUBSTR('%s', 1, LENGTH(%s)) THEN " +
                "SUBSTR(TRIM(TRAILING '0' FROM CAST('%s' AS datetime(3))),1,LENGTH(%s)) " +
                "ELSE CASE WHEN %s <= '%s' THEN TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('%s' AS datetime(3)))) " +
                "ELSE SUBSTR(TRIM(TRAILING '.' FROM (TRIM(TRAILING '0' FROM CAST(%s AS CHAR)))),1,LENGTH(%s)) END END",
                columnName, args.maxDatetimeValue, columnName,
                args.maxDatetimeValue, columnName,
                columnName, args.minDatetimeValue, args.minDatetimeValue,
                columnName, columnName
            );
        } else if (dataType.matches("datetime\\([4-6]\\)")) {
            selectColumn = String.format(
                "CASE WHEN %s >= SUBSTR('%s', 1, LENGTH(%s)) THEN " +
                "SUBSTR(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('%s' AS datetime(6)))),1,LENGTH(%s)) " +
                "ELSE CASE WHEN %s <= '%s' THEN TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('%s' AS datetime(6)))) " +
                "ELSE SUBSTR(TRIM(TRAILING '.' FROM (TRIM(TRAILING '0' FROM CAST(%s AS CHAR)))),1,LENGTH(%s)) END END",
                columnName, args.maxDatetimeValue, columnName,
                args.maxDatetimeValue, columnName,
                columnName, args.minDatetimeValue, args.minDatetimeValue,
                columnName, columnName
            );
        } else if (dataType.matches("time(\\([0-6]\\))?")) {
            selectColumn = String.format("SUBSTR(CAST(%s AS time(6)),1,LENGTH(%s))", columnName, columnName);
        } else if (dataType.matches("timestamp(\\([0-6]\\))?")) {
            selectColumn = String.format(
                "SUBSTR(TRIM(TRAILING '.' FROM (TRIM(TRAILING '0' FROM CAST(%s AS CHAR)))),1,LENGTH(%s))",
                columnName, columnName
            );
        } else if ("date".equals(dataType)) {
            selectColumn = String.format(
                "CASE WHEN %s >='%s' THEN CAST('%s' AS %s) " +
                "ELSE CASE WHEN %s <= '%s' THEN CAST('%s' AS %s) ELSE %s END END",
                columnName, args.maxDateValue, args.maxDateValue, dataType,
                columnName, args.minDateValue, args.minDateValue, dataType, columnName
            );
        } else if (isBinaryDatatype(dataType)) {
            if ("base64".equals(binaryEncoding)) {
                selectColumn = String.format("REPLACE(TO_BASE64(CAST(%s AS BINARY)),'\\n','')", columnName);
            } else {
                selectColumn = String.format("LOWER(HEX(CAST(%s AS BINARY)))", columnName);
            }
        } else {
            selectColumn = columnName;
        }
        
        return selectColumn;
    }
    
    private static List<String> selectTableStatements(String table, String query, String selectQuery, 
                                                      String orderBy, String externalColumnTypes, String where) {
        List<String> statements = new ArrayList<>();
        statements.add("SET NAMES utf8mb4");
        
        String limit = "";
        if (args.debugLimit != null) {
            limit = " LIMIT " + args.debugLimit;
        }
        
        String whereClause = where != null ? where : "1=1";
        
        statements.add(
            "SET @md5sum := '', @a := CAST(0 AS SIGNED), @b:= CAST(0 AS SIGNED), " +
            "@c:= CAST(0 AS SIGNED), @d:=CAST(0 AS SIGNED)"
        );
        
        String sql;
        if (args.debugOutput) {
            sql = String.format(
                "SELECT CONCAT_WS('#',%s) AS `hash` FROM %s.%s WHERE %s %s",
                selectQuery, args.mysqlDatabase, table, whereClause, limit
            );
        } else {
            sql = String.format(
                "SELECT COUNT(*) AS cnt, " +
                "COALESCE(MAX(a),0) AS a, " +
                "COALESCE(MAX(b),0) AS b, " +
                "COALESCE(MAX(c),0) AS c, " +
                "COALESCE(MAX(d),0) AS d " +
                "FROM (" +
                "  SELECT @md5sum :=MD5(CONVERT(CONCAT_WS('#',%s) USING utf8mb4)) AS `hash`, " +
                "  @a:=@a+CAST(CONV(SUBSTRING(@md5sum, 1, 8), -16, 10) AS SIGNED) AS a, " +
                "  @b:=@b+CAST(CONV(SUBSTRING(@md5sum, 9, 8), -16, 10) AS SIGNED) AS b, " +
                "  @c:=@c+CAST(CONV(SUBSTRING(@md5sum, 17, 8), -16, 10) AS SIGNED) AS c, " +
                "  @d:=@d+CAST(CONV(SUBSTRING(@md5sum, 25, 8), -16, 10) AS SIGNED) AS d " +
                "  FROM %s.%s WHERE %s" +
                ") AS t",
                selectQuery, args.mysqlDatabase, table, whereClause
            );
        }
        
        statements.add(sql);
        return statements;
    }
    
    private static ChecksumResult computeChecksum(String table, List<String> statements, Connection conn) 
            throws SQLException, IOException {
        ChecksumResult result = null;
        PrintWriter debugOut = null;
        
        if (args.debugOutput) {
            String outFile = "out." + table + ".mysql.txt";
            debugOut = new PrintWriter(new FileWriter(outFile, true));
        }
        
        try {
            for (String sql : statements) {
                log.debug("SQL: " + sql);
                
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    
                    if (!args.debugOutput) {
                        if (rs.next()) {
                            result = new ChecksumResult(
                                rs.getLong("cnt"),
                                rs.getLong("a"),
                                rs.getLong("b"),
                                rs.getLong("c"),
                                rs.getLong("d")
                            );
                        }
                    } else {
                        while (rs.next()) {
                            debugOut.println(rs.getString(1));
                        }
                    }
                }
            }
        } finally {
            if (debugOut != null) {
                debugOut.close();
            }
        }
        
        return result;
    }
    
    private static ChecksumResult calculateChecksumSingleThread(String mysqlTable, String mysqlUser, 
                                                                String mysqlPassword, Chunk chunk, 
                                                                String pk, String where, 
                                                                List<String> excludedColumns) 
            throws SQLException, IOException {
        try (Connection conn = getMySQLConnection(args.mysqlHost, mysqlUser, mysqlPassword, 
                                                  args.mysqlPort, args.mysqlDatabase)) {
            String whereClause = "1=1";
            if (where != null) {
                whereClause += " AND " + where;
            }
            if (pk != null) {
                whereClause = String.format("%s AND %s BETWEEN %d AND %d", 
                                          whereClause, pk, chunk.minPk, chunk.maxPk);
            }
            
            return calculateSQLChecksum(conn, mysqlTable, whereClause, excludedColumns);
        }
    }
    
    private static ChecksumResult calculateSQLChecksum(Connection conn, String table, String where, 
                                                       List<String> excludedColumns) 
            throws SQLException, IOException {
        if (args.ignoreTablesRegex != null) {
            Pattern ignorePattern = Pattern.compile(args.ignoreTablesRegex, Pattern.CASE_INSENSITIVE);
            if (ignorePattern.matcher(table).matches()) {
                log.info("Ignoring " + table + " due to ignore_regex_tables");
                return null;
            }
        }
        
        TableChecksumQuery query = getTableChecksumQuery(table, conn, args.binaryEncoding, where, excludedColumns);
        List<String> statements = selectTableStatements(table, query.query, query.selectQuery, 
                                                        query.orderByColumns, query.externalColumnTypes, where);
        return computeChecksum(table, statements, conn);
    }
    
    private static void calculateChecksum(String mysqlTable, String mysqlUser, String mysqlPassword, 
                                         List<String> excludedColumns) {
        try {
            if (args.ignoreTablesRegex != null) {
                Pattern ignorePattern = Pattern.compile(args.ignoreTablesRegex, Pattern.CASE_INSENSITIVE);
                if (ignorePattern.matcher(mysqlTable).matches()) {
                    log.info("Ignoring " + mysqlTable + " due to ignore_regex_tables");
                    return;
                }
            }
            
            Connection conn = getMySQLConnection(args.mysqlHost, mysqlUser, mysqlPassword, 
                                                args.mysqlPort, args.mysqlDatabase);
            List<String> pk = getMySQLPkColumns(conn, args.mysqlDatabase, mysqlTable, true);
            
            int threadsPerTable = 1;
            String pkColumn = null;
            
            if (!pk.isEmpty() && args.threadsPerTable > 1) {
                pkColumn = pk.get(0);
                threadsPerTable = args.threadsPerTable;
            }
            
            String where = args.where != null ? args.where : "1=1";
            
            // Initialize debug output
            if (args.debugOutput) {
                String outFile = "out." + mysqlTable + ".mysql.txt";
                new PrintWriter(new FileWriter(outFile, false)).close();
            }
            
            List<ChecksumResult> results = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(threadsPerTable);
            List<Future<ChecksumResult>> futures = new ArrayList<>();
            
            List<Chunk> chunks = divideTableIntoEvenChunks(conn, mysqlTable, args.chunkSize, pkColumn, where);
            conn.close();
            
            for (Chunk chunk : chunks) {
                final String finalPkColumn = pkColumn;
                final String finalWhere = where;
                Future<ChecksumResult> future = executor.submit(() -> 
                    calculateChecksumSingleThread(mysqlTable, mysqlUser, mysqlPassword, chunk, 
                                                 finalPkColumn, finalWhere, excludedColumns)
                );
                futures.add(future);
            }
            
            for (Future<ChecksumResult> future : futures) {
                ChecksumResult result = future.get();
                if (result != null) {
                    results.add(result);
                }
            }
            
            executor.shutdown();
            
            if (args.debugOutput) {
                // Checksum is not output in debug_output mode
                return;
            }
            
            log.debug("Results: " + results);
            
            ChecksumResult totalResult = new ChecksumResult(0, 0, 0, 0, 0);
            for (ChecksumResult r : results) {
                totalResult.add(r);
            }
            
            // Calculate final MD5
            String md5Input = totalResult.cnt + "#" + totalResult.a + "#" + totalResult.b + "#" + 
                            totalResult.c + "#" + totalResult.d + "#";
            
            log.debug("MD5 input: " + md5Input);
            
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(md5Input.getBytes(StandardCharsets.UTF_8));
            String checksum = bytesToHex(digest);
            
            log.info(String.format("Checksum for table %s.%s = %s count %d", 
                                  args.mysqlDatabase, mysqlTable, checksum, totalResult.cnt));
            
        } catch (Exception e) {
            log.error("Error calculating checksum for table " + mysqlTable, e);
            throw new RuntimeException(e);
        }
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

