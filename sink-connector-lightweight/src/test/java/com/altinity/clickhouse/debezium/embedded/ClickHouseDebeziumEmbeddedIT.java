//package com.altinity.clickhouse.debezium.embedded;
//
//import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
//import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
//import com.altinity.clickhouse.debezium.embedded.ddl.parser.MySQLDDLParserService;
//import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
//import org.apache.log4j.BasicConfigurator;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.testcontainers.containers.ClickHouseContainer;
//import org.testcontainers.containers.MySQLContainer;
//import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//import org.testcontainers.utility.DockerImageName;
//
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.Properties;
//
//@Testcontainers
//public class ClickHouseDebeziumEmbeddedIT {
//
//    //  private MySQLContainer mySqlContainer;
//    private MySQLContainer mySqlContainer;
//
//    @Container
//    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
//            .withInitScript("init_clickhouse.sql")
//            // .withPassword("root")
//            .withExposedPorts(8123);
//
////    @Container
////    public static MySQLContainer mySqlContainer = new MySQLContainer<>("mysql:8.0.24")
////            .withDatabaseName("datatypes").withUsername("root").withPassword("root")
////            .withInitScript("data_types.sql").withExposedPorts(3306).withAccessToHost(true);
//    //withAccessToHost(true);
////                        .withExtraHost("localhost", "0.0.0.0");
//
//
//    //public static String mySqlHost = mysqlContainer.getHost();
//
//    @BeforeEach
//    public void startContainers() throws InterruptedException {
//        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
//                .asCompatibleSubstituteFor("mysql"))
//                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
//                //.withFileSystemBind("src/test/resources/", "/docker-entrypoint-initdb.d")
//                .withInitScript("employees.sql")
//                //.withInitScript("data_types.sql").withExposedPorts(3306)
//                .withExtraHost("mysql-server", "0.0.0.0")
//                .waitingFor(new HttpWaitStrategy().forPort(3306));
//
//
////        mySqlContainer = (GenericContainer) new GenericContainer("docker.io/bitnami/mysql:latest")
////                .withEnv("MYSQL_ROOT_PASSWORD", "root")
////                        .withEnv("MYSQL_DATABASE", "sakila")
////                                .withEnv("MYSQL_REPLICATION_MODE", "master")
////                                        .withExposedPorts(3306)
////                .withFileSystemBind("mysqld.cnf", "/opt/bitnami/mysql/conf/my_custom.cnf");
//        //.withFileSystemBind("src/test/resources/", "/docker-entrypoint-initdb.d");
//
//        BasicConfigurator.configure();
////
//        mySqlContainer.start();
//        Thread.sleep(15000);
//    }
//
//
//    @Test
////    @SetEnvironmentVariable(key = "database.hostname", value = "localhost")
////    @SetEnvironmentVariable(key = "database.port", value = "3306")
////    @SetEnvironmentVariable(key = "database.user", value = "root")
////    @SetEnvironmentVariable(key = "database.pass", value = "root")
////    @SetEnvironmentVariable(key="database.include.list", value="datatypes")
////    //@SetEnvironmentVariable(key = "clickhouse.server.url", value = "localhost")
////    //@SetEnvironmentVariable(key = "clickhouse.server.user", value = "root")
////    //@SetEnvironmentVariable(key = "clickhouse.server.pass", value = "root")
////    @SetEnvironmentVariable(key = "clickhouse.server.port", value = "8123")
////    @SetEnvironmentVariable(key="auto.create.tables", value= "true")
////    @SetEnvironmentVariable(key="snapshot.mode", value="schema_only")
////    @SetEnvironmentVariable(key="connector.class", value="io.debezium.connector.mysql.MySqlConnector")
//    public void testDataTypesDB() throws Exception {
//
//        Properties defaultProps = (new EnvironmentConfigurationService()).parse();
//        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
//        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
//
//        defaultProps.setProperty("database.user", "root");
//        defaultProps.setProperty("database.password", "adminpass");
//
//        defaultProps.setProperty("database.include.list", "employees");
//        defaultProps.setProperty("snapshot.mode", "initial");
//        defaultProps.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
//
//        defaultProps.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
//        defaultProps.setProperty("provide.transaction.metadata", "true");
//
//        //String tempOffsetPath = "/tmp/2/offsets" + System.currentTimeMillis() + ".dat";
//        Path tmpFilePath = Files.createTempFile("offsets", ".dat");
//
//        Files.deleteIfExists(tmpFilePath);
//        defaultProps.setProperty("offset.storage.file.filename", tmpFilePath.toString());
//        defaultProps.setProperty("offset.flush.interval.ms", "60000");
//
//
//        defaultProps.setProperty("auto.create.tables", "true");
//        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
//        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
//        defaultProps.setProperty("clickhouse.server.user", "default");
//        defaultProps.setProperty("clickhouse.server.pass", "");
//        defaultProps.setProperty("clickhouse.server.database", "employees");
//        defaultProps.setProperty("replacingmergetree.delete.column", "_sign");
//        defaultProps.setProperty("metrics.port", "8088");
//
//
//        defaultProps.setProperty("database.allowPublicKeyRetrieval", "true");
//
//        new DebeziumChangeEventCapture().setup(defaultProps, new SourceRecordParserService(),
//                new MySQLDDLParserService());
//        //ClickHouseDebeziumEmbeddedApplication.main(new String[] {""});
//
//
////        GenericContainer container = new GenericContainer("registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded")
////                .withEnv("database.hostname", mySqlContainer.getHost())
////                .withEnv("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()))
////                .withEnv("database.user", mySqlContainer.getUsername())
////                .withEnv("database.pass", mySqlContainer.getPassword());
////
////        mySqlContainer.start();
////        clickHouseContainer.start();
////
////        Thread.sleep(10000);
////        container.start();
////
//        // Thread.sleep(10000);
////
////        String dbHostName = clickHouseContainer.getHost();
////        Integer port = clickHouseContainer.getFirstMappedPort();
////        String database = "world";
////        String userName = clickHouseContainer.getUsername();
////        String password = clickHouseContainer.getPassword();
////        String tableName = "employees";
////
////        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
////                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);
//
//        //jdbc:mysql://localhost/TUTORIALSPOINT
//        // Compare counts
//        new BaseIT().runJDBCQuery(String.format("jdbc:mysql://%s:%s/employees", mySqlContainer.getHost(), mySqlContainer.getFirstMappedPort()),
//                mySqlContainer.getUsername(), mySqlContainer.getPassword(), "select count(*) from employees");
//
//        Files.deleteIfExists(tmpFilePath);
//
//
//    }
//
//    @Test
//    public void testSysBench() throws Exception {
//
//        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
//                .asCompatibleSubstituteFor("mysql"))
//                .withDatabaseName("sbtest").withUsername("root").withPassword("adminpass")
//                //.withFileSystemBind("src/test/resources/", "/docker-entrypoint-initdb.d")
//                // .withInitScript("employees.sql")
//                //.withInitScript("data_types.sql").withExposedPorts(3306)
//                .withExtraHost("mysql-server", "0.0.0.0")
//                .waitingFor(new HttpWaitStrategy().forPort(3306));
//
//        //mySqlContainer.start();
//        //Thread.sleep(15000);
//
//        // Start the debezium embedded application.
//
//        Properties defaultProps = (new EnvironmentConfigurationService()).parse();
//        System.out.println("MYSQL HOST" + mySqlContainer.getHost());
//        //System.out.println("JDBC URL" + mySqlContainer.getJdbcUrl());
//        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
//        //defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
//        defaultProps.setProperty("database.port", "3306");
//
////        defaultProps.setProperty("database.user", mySqlContainer.getUsername());
////        defaultProps.setProperty("database.password", mySqlContainer.getPassword());
//
//        defaultProps.setProperty("database.user", "root");
//        defaultProps.setProperty("database.password", "adminpass");
//
//        defaultProps.setProperty("database.include.list", "sbtest");
//        defaultProps.setProperty("snapshot.mode", "initial");
//        defaultProps.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
//
//        defaultProps.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
//
//        Path tmpFilePath = Files.createTempFile("offsets", ".dat");
//
//        Files.deleteIfExists(tmpFilePath);
//        defaultProps.setProperty("offset.storage.file.filename", tmpFilePath.toString());
//        defaultProps.setProperty("offset.flush.interval.ms", "60000");
//
//
//        defaultProps.setProperty("auto.create.tables", "true");
//        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
//        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
//        defaultProps.setProperty("clickhouse.server.user", "default");
//        defaultProps.setProperty("clickhouse.server.pass", "");
//        defaultProps.setProperty("clickhouse.server.database", "sbtest");
//        defaultProps.setProperty("replacingmergetree.delete.column", "_sign");
//        defaultProps.setProperty("metrics.port", "8087");
//        defaultProps.setProperty("thread.pool.size", "10");
//
//
//        defaultProps.setProperty("database.allowPublicKeyRetrieval", "true");
//
//        new DebeziumChangeEventCapture().setup(defaultProps, new SourceRecordParserService(),
//                new MySQLDDLParserService());
//        Thread.sleep(100000);
//        //ClickHouseDebeziumEmbeddedApplication.main(new String[] {""});
//
//
////        GenericContainer container = new GenericContainer("registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded")
////                .withEnv("database.hostname", mySqlContainer.getHost())
////                .withEnv("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()))
////                .withEnv("database.user", mySqlContainer.getUsername())
////                .withEnv("database.pass", mySqlContainer.getPassword());
////
////        mySqlContainer.start();
////        clickHouseContainer.start();
////
////        Thread.sleep(10000);
////        container.start();
////
//        // Thread.sleep(10000);
////
////        String dbHostName = clickHouseContainer.getHost();
////        Integer port = clickHouseContainer.getFirstMappedPort();
////        String database = "world";
////        String userName = clickHouseContainer.getUsername();
////        String password = clickHouseContainer.getPassword();
////        String tableName = "employees";
////
////        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
////                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);
//
//        //jdbc:mysql://localhost/TUTORIALSPOINT
//        // Compare counts
////        new BaseIT().runJDBCQuery(String.format("jdbc:mysql://%s:%s/employees", mySqlContainer.getHost(), mySqlContainer.getFirstMappedPort()),
////                mySqlContainer.getUsername(), mySqlContainer.getPassword(), "select count(*) from employees");
//
//        Files.deleteIfExists(tmpFilePath);
//
//
//    }
//
//}
