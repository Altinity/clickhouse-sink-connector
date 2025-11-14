package com.altinity.clickhouse.sink.connector.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * ClickHouse Table Checksum Calculator
 * Calculates a checksum for ClickHouse tables using MD5 algorithm compatible with pt-checksum technique.
 * 
 * Credits: https://www.sisense.com/blog/hashing-tables-to-ensure-consistency-in-postgres-redshift-and-mysql/
 */
public class ClickHouseTableChecksum {
    
    private static final Logger log = LogManager.getLogger(ClickHouseTableChecksum.class);
    private static Args args;
    
    private static final String CREATE_FUNCTION_FORMAT_DECIMAL = 
        "CREATE OR REPLACE FUNCTION format_decimal AS (x, scale) -> " +
        "if(locate(toString(x),'.')>0,concat(toString(x),repeat('0',toUInt8(scale-(length(toString(x))-locate(toString(x),'.'))))),"+
        "concat(toString(x),if(scale=0,'','.'),repeat('0',toUInt8(scale))))";
    
    public static class Args {
        String clickhouseHost;
        String clickhouseUser;
        String clickhousePassword;
        String clickhouseConfigFile = "./clickhouse-client.xml";
        String clickhouseDatabase;
        int clickhousePort = 9000;
        boolean secure = false;
        String signColumn = "_sign";
        String tablesRegex;
        String where;
        String orderBy;
        String ignoreTablesRegex;
        boolean noWc = false;
        boolean debugOutput = false;
        String debugLimit;
        List<String> hexColumns = new ArrayList<>();
        boolean debug = false;
        List<String> excludeColumns = new ArrayList<>(Arrays.asList("_sign", "_version", "is_deleted", "_is_deleted"));
        int threads = 1;
        String minDatetimeValue = "1900-01-01 00:00:00";
        String maxDatetimeValue = "2299-12-31 23:59:59.000000";
        String maxMemoryUsage;
    }
    
    private static class ColumnMetadata {
        String name;
        String type;
        boolean isNullable;
        int numericScale;
        
        ColumnMetadata(String name, String type, boolean isNullable, int numericScale) {
            this.name = name;
            this.type = type;
            this.isNullable = isNullable;
            this.numericScale = numericScale;
        }
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
            ClickHouseTableChecksum.args = parseArguments(args);
            
            // Setup logging
            if (ClickHouseTableChecksum.args.debug) {
                System.setProperty("log4j.logger.com.altinity.clickhouse.sink.connector.tools", "DEBUG");
            }
            
            // Validate and resolve credentials
            String clickhouseUser = ClickHouseTableChecksum.args.clickhouseUser;
            String clickhousePassword = ClickHouseTableChecksum.args.clickhousePassword;
            
            if (ClickHouseTableChecksum.args.clickhousePassword != null) {
                log.warn("Using password on the command line is not secure, please specify a config file");
                if (ClickHouseTableChecksum.args.clickhouseUser == null) {
                    throw new IllegalArgumentException("--clickhouse_user must be specified");
                }
            } else {
                String[] credentials = resolveCredentialsFromConfig(ClickHouseTableChecksum.args.clickhouseConfigFile);
                clickhouseUser = credentials[0];
                clickhousePassword = credentials[1];
            }
            
            // Get connection and create custom function
            Connection conn = getConnection(clickhouseUser, clickhousePassword);
            executeSql(conn, CREATE_FUNCTION_FORMAT_DECIMAL);
            
            // Get list of tables
            List<String> tables = getTablesFromRegex(conn);
            conn.close();
            
            // Process tables in parallel
            ExecutorService executor = Executors.newFixedThreadPool(ClickHouseTableChecksum.args.threads);
            List<Future<?>> futures = new ArrayList<>();
            
            for (String table : tables) {
                final String finalClickhouseUser = clickhouseUser;
                final String finalClickhousePassword = clickhousePassword;
                Future<?> future = executor.submit(() -> 
                    calculateChecksum(table, finalClickhouseUser, finalClickhousePassword, 
                                    ClickHouseTableChecksum.args.where)
                );
                futures.add(future);
            }
            
            // Wait for all tasks to complete
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    log.error("Exception in task", e.getCause());
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
                case "--clickhouse_host":
                    result.clickhouseHost = args[++i];
                    break;
                case "--clickhouse_user":
                    result.clickhouseUser = args[++i];
                    break;
                case "--clickhouse_password":
                    result.clickhousePassword = args[++i];
                    break;
                case "--clickhouse_config_file":
                    result.clickhouseConfigFile = args[++i];
                    break;
                case "--clickhouse_database":
                    result.clickhouseDatabase = args[++i];
                    break;
                case "--clickhouse_port":
                    result.clickhousePort = Integer.parseInt(args[++i]);
                    break;
                case "--secure":
                    result.secure = Boolean.parseBoolean(args[++i]);
                    break;
                case "--sign_column":
                    result.signColumn = args[++i];
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
                case "--hex_columns":
                    result.hexColumns.clear();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        result.hexColumns.add(args[++i]);
                    }
                    break;
                case "--debug":
                    result.debug = true;
                    break;
                case "--exclude_columns":
                    result.excludeColumns.clear();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        String col = args[++i];
                        // Handle comma-separated values
                        if (col.contains(",")) {
                            result.excludeColumns.addAll(Arrays.asList(col.split(",")));
                        } else {
                            result.excludeColumns.add(col);
                        }
                    }
                    break;
                case "--threads":
                    result.threads = Integer.parseInt(args[++i]);
                    break;
                case "--min_datetime_value":
                    result.minDatetimeValue = args[++i];
                    break;
                case "--max_datetime_value":
                    result.maxDatetimeValue = args[++i];
                    break;
                case "--max_memory_usage":
                    result.maxMemoryUsage = args[++i];
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
        if (result.clickhouseHost == null || result.clickhouseDatabase == null || result.tablesRegex == null) {
            System.err.println("Required arguments missing!");
            printUsage();
            System.exit(1);
        }
        
        return result;
    }
    
    private static void printUsage() {
        System.out.println("ClickHouse Table Checksum Calculator");
        System.out.println("\nCompute the table checksum using the same technique as pt-checksum, md5 algorithm.");
        System.out.println("\nRequired arguments:");
        System.out.println("  --clickhouse_host HOST           ClickHouse host");
        System.out.println("  --clickhouse_database DATABASE   ClickHouse database");
        System.out.println("  --tables_regex REGEX             Table regular expression");
        System.out.println("\nOptional arguments:");
        System.out.println("  --clickhouse_user USER           ClickHouse user");
        System.out.println("  --clickhouse_password PASS       ClickHouse password (discouraged, use config file)");
        System.out.println("  --clickhouse_config_file FILE    Config file (xml/yaml) (default: ./clickhouse-client.xml)");
        System.out.println("  --clickhouse_port PORT           ClickHouse port (default: 9000)");
        System.out.println("  --secure BOOL                    Use secure connection (default: false)");
        System.out.println("  --sign_column COLUMN             Sign column name (default: _sign)");
        System.out.println("  --where CLAUSE                   WHERE clause");
        System.out.println("  --order_by CLAUSE                ORDER BY clause");
        System.out.println("  --ignore_tables_regex REGEX      Ignore table regex");
        System.out.println("  --no_wc                          Use --tables_regex as exact table name");
        System.out.println("  --debug_output                   Output raw format to file");
        System.out.println("  --debug_limit N                  Limit debug output lines");
        System.out.println("  --hex_columns COL...             Columns to convert from hex");
        System.out.println("  --debug                          Enable debug logging");
        System.out.println("  --exclude_columns COL...         Columns to exclude (default: _sign,_version,is_deleted,_is_deleted)");
        System.out.println("  --threads N                      Number of parallel threads (default: 1)");
        System.out.println("  --min_datetime_value DT          Min DateTime64 (default: 1900-01-01 00:00:00)");
        System.out.println("  --max_datetime_value DT          Max DateTime64 (default: 2299-12-31 23:59:59.000000)");
        System.out.println("  --max_memory_usage BYTES         Increase max_memory_usage setting");
    }
    
    private static Connection getConnection(String user, String password) throws SQLException {
        String protocol = args.secure ? "https" : "http";
        String url = String.format("jdbc:clickhouse://%s:%d/%s?ssl=%s", 
                                   args.clickhouseHost, args.clickhousePort, 
                                   args.clickhouseDatabase, args.secure);
        
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("connect_timeout", "20000");
        
        return DriverManager.getConnection(url, props);
    }
    
    private static String[] resolveCredentialsFromConfig(String configFile) throws Exception {
        if (!Files.exists(Paths.get(configFile))) {
            throw new IllegalArgumentException("Config file does not exist: " + configFile);
        }
        
        String clickhouseUser = null;
        String clickhousePassword = null;
        
        if (configFile.endsWith(".xml")) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new File(configFile));
            doc.getDocumentElement().normalize();
            
            Element root = doc.getDocumentElement();
            clickhouseUser = getTextContent(root, "user");
            clickhousePassword = getTextContent(root, "password");
            
        } else if (configFile.endsWith(".yml") || configFile.endsWith(".yaml")) {
            Yaml yaml = new Yaml();
            try (InputStream in = new FileInputStream(configFile)) {
                Map<String, Object> data = yaml.load(in);
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) data.get("config");
                if (config != null) {
                    clickhouseUser = (String) config.get("user");
                    clickhousePassword = (String) config.get("password");
                }
            }
        } else {
            throw new IllegalArgumentException("Supported configuration extensions: .xml, .yaml, .yml");
        }
        
        if (clickhouseUser == null || clickhousePassword == null) {
            throw new IllegalArgumentException("Could not find user and password in config file: " + configFile);
        }
        
        log.debug("clickhouse_user " + clickhouseUser + " clickhouse_password ****");
        return new String[]{clickhouseUser, clickhousePassword};
    }
    
    private static String getTextContent(Element parent, String tagName) {
        try {
            return parent.getElementsByTagName(tagName).item(0).getTextContent();
        } catch (Exception e) {
            return null;
        }
    }
    
    private static Object[] executeSql(Connection conn, String sql) throws SQLException {
        log.debug("SQL=" + sql);
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            List<Object[]> rows = new ArrayList<>();
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            while (rs.next()) {
                Object[] row = new Object[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                rows.add(row);
            }
            
            return new Object[]{rows, rows.size()};
        }
    }
    
    private static List<String> getTablesFromRegex(Connection conn) throws SQLException {
        if (args.noWc) {
            return Collections.singletonList(args.tablesRegex);
        }
        
        String sql = String.format(
            "SELECT name FROM system.tables WHERE database = '%s' AND match(name,'%s') ORDER BY 1",
            args.clickhouseDatabase, args.tablesRegex
        );
        
        log.info("REGEX QUERY: " + sql);
        
        Object[] result = executeSql(conn, sql);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) result[0];
        
        List<String> tables = new ArrayList<>();
        for (Object[] row : rows) {
            tables.add((String) row[0]);
        }
        
        return tables;
    }
    
    private static List<String> getPrimaryKeyColumns(Connection conn, String tableSchema, String tableName) 
            throws SQLException {
        String sql = String.format(
            "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s' " +
            "AND is_in_primary_key = 1 ORDER BY position ASC",
            tableSchema, tableName
        );
        
        Object[] result = executeSql(conn, sql);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) result[0];
        
        List<String> pkColumns = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                pkColumns.add((String) row[0]);
            }
        }
        
        return pkColumns;
    }
    
    private static String getTablePartitionKey(Connection conn, String database, String table) 
            throws SQLException {
        String sql = String.format(
            "SELECT partition_key FROM system.tables WHERE name = '%s' AND database = '%s' FORMAT TabSeparated",
            table, database
        );
        
        Object[] result = executeSql(conn, sql);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) result[0];
        
        if (!rows.isEmpty() && rows.get(0)[0] != null) {
            return (String) rows.get(0)[0];
        }
        return "";
    }
    
    private static TableChecksumQuery getTableChecksumQuery(Connection conn, String table) throws SQLException {
        // Process excluded columns
        List<String> excludedColumns = new ArrayList<>();
        for (String col : args.excludeColumns) {
            if (col.contains(",")) {
                excludedColumns.addAll(Arrays.asList(col.split(",")));
            } else {
                excludedColumns.add(col);
            }
        }
        
        log.info("Excluded columns: " + excludedColumns);
        
        String sql = String.format(
            "SELECT name, type, if(match(type,'Nullable'),1,0) is_nullable, numeric_scale " +
            "FROM system.columns WHERE database='%s' AND table='%s' ORDER BY position",
            args.clickhouseDatabase, table
        );
        
        Object[] result = executeSql(conn, sql);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = (List<Object[]>) result[0];
        
        // Build column metadata
        List<ColumnMetadata> columnsMetadata = new ArrayList<>();
        Map<String, ColumnMetadata> metadataMap = new HashMap<>();
        
        for (Object[] row : rows) {
            String name = (String) row[0];
            String type = (String) row[1];
            boolean isNullable = ((Number) row[2]).intValue() == 1;
            int numericScale = row[3] != null ? ((Number) row[3]).intValue() : 0;
            
            ColumnMetadata metadata = new ColumnMetadata(name, type, isNullable, numericScale);
            columnsMetadata.add(metadata);
            metadataMap.put(name, metadata);
        }
        
        // Filter excluded columns (handle prefixed columns)
        List<ColumnMetadata> filteredMetadata = new ArrayList<>();
        for (ColumnMetadata metadata : columnsMetadata) {
            String prefixedColumn = "_" + metadata.name;
            
            if (excludedColumns.contains(metadata.name) && metadataMap.containsKey(prefixedColumn)) {
                log.info("Not excluding column " + metadata.name + " as " + prefixedColumn + " is also excluded");
                filteredMetadata.add(metadata);
            } else if (excludedColumns.contains(metadata.name)) {
                log.info("Excluding column " + metadata.name);
            } else {
                filteredMetadata.add(metadata);
            }
        }
        
        // Build SELECT query
        StringBuilder select = new StringBuilder();
        List<String> nullables = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        Map<String, String> dataTypes = new HashMap<>();
        
        for (int i = 0; i < filteredMetadata.size(); i++) {
            ColumnMetadata metadata = filteredMetadata.get(i);
            String columnName = "\"" + metadata.name + "\"";
            boolean unhex = args.hexColumns.contains(metadata.name);
            
            columns.add(metadata.name);
            dataTypes.put(metadata.name, metadata.type);
            
            if (i > 0) {
                select.append("||");
            }
            
            if (metadata.isNullable) {
                nullables.add(columnName);
                select.append(" CASE WHEN ").append(columnName).append(" IS NULL THEN '' ELSE ");
            }
            
            select.append(buildSelectColumn(columnName, metadata.type, metadata.numericScale, unhex));
            
            if (metadata.isNullable) {
                select.append(" END");
            }
            
            if (i < filteredMetadata.size() - 1) {
                select.append("||'#'");
            }
        }
        
        log.debug("Nullables: " + nullables);
        
        if (!nullables.isEmpty()) {
            select.append("||'#'");
            for (String nullable : nullables) {
                select.append("|| CASE WHEN ").append(nullable).append(" IS NULL THEN '1' ELSE '0' END ");
            }
        }
        
        // Get primary key columns
        List<String> primaryKeyColumns = getPrimaryKeyColumns(conn, args.clickhouseDatabase, table);
        log.debug("Primary key columns: " + primaryKeyColumns);
        
        String orderByColumns = "";
        if (!primaryKeyColumns.isEmpty()) {
            orderByColumns = String.join(",", primaryKeyColumns);
        } else {
            orderByColumns = String.join(",", columns);
        }
        
        String query = String.format("SELECT %s||',' AS query FROM %s.%s", 
                                     select, args.clickhouseDatabase, table);
        
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
    
    private static String buildSelectColumn(String columnName, String dataType, int numericScale, boolean unhex) {
        StringBuilder selectColumn = new StringBuilder();
        
        if (dataType.contains("timestamp")) {
            selectColumn.append("replace(to_char(").append(columnName)
                       .append(",'YYYY-MM-DD HH24:MI:SS.US'),'1900-01-01 ','')");
        } else if (dataType.equals("Bool")) {
            selectColumn.append("toString(toUInt8(").append(columnName).append("))");
        } else if (dataType.equals("date")) {
            selectColumn.append("to_char(").append(columnName).append(",'YYYY-MM-DD')");
        } else if (dataType.contains("Decimal")) {
            selectColumn.append("toDecimalString(").append(columnName).append(",").append(numericScale).append(")");
        } else if (dataType.contains("DateTime64(0")) {
            selectColumn.append(String.format(
                "if(toString(%s) >= '%s', '%s', if(toString(%s) < '%s', '%s', " +
                "trim(TRAILING '.' from (trim(TRAILING '0' FROM toString(%s))))))",
                columnName, args.maxDatetimeValue, args.maxDatetimeValue,
                columnName, args.minDatetimeValue, args.minDatetimeValue,
                columnName
            ));
        } else if (dataType.contains("DateTime64(6")) {
            selectColumn.append(String.format(
                "if(toString(%s) >= '%s', '%s', if(toString(%s) < '%s', '%s', " +
                "trim(TRAILING '.' from (trim(TRAILING '0' FROM toString(%s))))))",
                columnName, args.maxDatetimeValue, args.maxDatetimeValue,
                columnName, args.minDatetimeValue, args.minDatetimeValue,
                columnName
            ));
        } else if (dataType.contains("DateTime")) {
            selectColumn.append("trim(TRAILING '.' from (trim(TRAILING '0' FROM toString(")
                       .append(columnName).append("))))");
        } else if (dataType.equals("time without time zone")) {
            selectColumn.append("replace(to_char(").append(columnName)
                       .append(",'HH24:MI:SS.US'),'1900-01-01 ','')");
        } else if (unhex) {
            selectColumn.append("toString(unhex(").append(columnName).append("))");
        } else {
            selectColumn.append("toString(").append(columnName).append(")");
        }
        
        return selectColumn.toString();
    }
    
    private static List<String> selectTableStatements(String table, String query, String selectQuery,
                                                     String orderBy, String externalColumnTypes, String where) {
        List<String> statements = new ArrayList<>();
        
        String limit = "";
        if (args.debugLimit != null) {
            limit = " LIMIT " + args.debugLimit;
        }
        
        String whereClause = where != null ? where : "1=1";
        
        // Skip deleted rows
        if (args.signColumn != null && !args.signColumn.isEmpty()) {
            whereClause += " AND " + args.signColumn + " > 0";
        }
        
        String memorySetting = "";
        if (args.maxMemoryUsage != null) {
            memorySetting = ", max_memory_usage = " + args.maxMemoryUsage;
        }
        
        String sql;
        if (args.debugOutput) {
            sql = String.format(
                "SELECT %s AS \"hash\" FROM %s.%s FINAL WHERE %s %s " +
                "SETTINGS do_not_merge_across_partitions_select_final=1",
                selectQuery, args.clickhouseDatabase, table, whereClause, limit
            );
        } else {
            sql = String.format(
                "SELECT " +
                "  count(*) AS cnt, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 1, 8))))),0) AS a, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 9, 8))))),0) AS b, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 17, 8))))),0) AS c, " +
                "  coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 25, 8))))),0) AS d " +
                "FROM ( " +
                "  SELECT hex(MD5(%s)) AS \"hash\" " +
                "  FROM %s.%s FINAL WHERE %s %s " +
                ") AS t SETTINGS do_not_merge_across_partitions_select_final=1%s",
                selectQuery, args.clickhouseDatabase, table, whereClause, limit, memorySetting
            );
        }
        
        statements.add(sql);
        return statements;
    }
    
    private static void computeChecksum(String table, String clickhouseUser, String clickhousePassword,
                                       List<String> statements) throws Exception {
        Connection conn = getConnection(clickhouseUser, clickhousePassword);
        PrintWriter debugOut = null;
        
        if (args.debugOutput) {
            String outFile = "out." + table + ".ch.txt";
            debugOut = new PrintWriter(new FileWriter(outFile));
        } else {
            log.info("Skipping writing to file");
        }
        
        try {
            for (String sql : statements) {
                Object[] result = executeSql(conn, sql);
                @SuppressWarnings("unchecked")
                List<Object[]> rows = (List<Object[]>) result[0];
                int rowcount = (Integer) result[1];
                
                log.debug("Rows affected " + rowcount);
                
                if (rowcount > 0) {
                    List<Object> values = new ArrayList<>();
                    for (Object[] row : rows) {
                        for (Object val : row) {
                            values.add(val);
                        }
                    }
                    
                    if (args.debugOutput) {
                        for (Object value : values) {
                            if (value instanceof byte[]) {
                                debugOut.write(new String((byte[]) value, StandardCharsets.UTF_8));
                            } else {
                                debugOut.write(String.valueOf(value));
                            }
                            debugOut.write('\n');
                        }
                    } else {
                        StringBuilder md5Sum = new StringBuilder();
                        String cnt = "-1";
                        
                        for (Object value : values) {
                            log.debug(String.valueOf(value));
                            md5Sum.append(value).append('#');
                            if (cnt.equals("-1")) {
                                cnt = String.valueOf(value);
                            }
                        }
                        
                        log.debug(md5Sum.toString());
                        
                        MessageDigest md5 = MessageDigest.getInstance("MD5");
                        byte[] digest = md5.digest(md5Sum.toString().getBytes(StandardCharsets.UTF_8));
                        String checksum = bytesToHex(digest);
                        
                        log.info(String.format("Checksum for table %s.%s = %s count %s",
                                             args.clickhouseDatabase, table, checksum, cnt));
                    }
                }
            }
        } finally {
            if (debugOut != null) {
                debugOut.close();
            }
            conn.close();
        }
    }
    
    private static void calculateChecksum(String table, String clickhouseUser, String clickhousePassword,
                                         String where) {
        try {
            if (args.ignoreTablesRegex != null) {
                Pattern ignorePattern = Pattern.compile(args.ignoreTablesRegex, Pattern.CASE_INSENSITIVE);
                if (ignorePattern.matcher(table).matches()) {
                    log.info("Ignoring " + table + " due to ignore_regex_tables");
                    return;
                }
            }
            
            Connection conn = getConnection(clickhouseUser, clickhousePassword);
            
            // Count rows first
            String countSql = "SELECT count(*) cnt FROM " + args.clickhouseDatabase + "." + table;
            String whereClause = where;
            
            if (where != null && where.contains("{partition_expression}")) {
                String partitionKey = getTablePartitionKey(conn, args.clickhouseDatabase, table);
                log.info("Partition key: " + partitionKey);
                if (!partitionKey.isEmpty()) {
                    whereClause = where.replace("{partition_expression}", partitionKey);
                }
            }
            
            if (whereClause != null && !whereClause.isEmpty()) {
                countSql += " WHERE " + whereClause;
            }
            
            Object[] countResult = executeSql(conn, countSql);
            @SuppressWarnings("unchecked")
            List<Object[]> countRows = (List<Object[]>) countResult[0];
            int rowcount = (Integer) countResult[1];
            
            if (rowcount == 0) {
                log.info("No rows in ClickHouse. Nothing to sync.");
                log.info(String.format("Checksum for table %s.%s = d41d8cd98f00b204e9800998ecf8427e count 0",
                                      args.clickhouseDatabase, table));
                conn.close();
                return;
            }
            
            // Generate checksum query
            TableChecksumQuery query = getTableChecksumQuery(conn, table);
            List<String> statements = selectTableStatements(table, query.query, query.selectQuery,
                                                           query.orderByColumns, query.externalColumnTypes, whereClause);
            conn.close();
            
            computeChecksum(table, clickhouseUser, clickhousePassword, statements);
            
        } catch (Exception e) {
            log.error("Error calculating checksum for table " + table, e);
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

