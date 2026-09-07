package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;


/**
 * This is a test for  "plugin.name", "pgoutput"
 */

public class PostgresPgoutputMultipleSchemaIT {

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @Container
    public static PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withInitScript("init_postgres.sql")
            .withDatabaseName("public")
            .withUsername("root")
            .withPassword("root")
            .withExposedPorts(5432)
            .withCommand("postgres -c wal_level=logical")
            .withNetworkAliases("postgres").withAccessToHost(true);


    public Properties getProperties() throws Exception {


        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput" );
        properties.put("plugin.path", "/" );
        properties.put("table.include.list", "public.tm" );
        properties.put("topic.prefix", "test-server" );
        properties.put("slot.max.retries", "6" );
        properties.put("slot.retry.delay.ms", "5000" );
        properties.put("database.allowPublicKeyRetrieval", "true" );
        properties.put("schema.include.list", "public,public2");
        properties.put("table.include.list", "public.tm,public2.tm2,public.people,public2.table_time_with_timezone" );
        properties.put("column.exclude.list", "public.people.full_name_mat");
        return properties;
    }

    @Test
    @DisplayName("Integration Test - Validates postgresql replication works with multiple schemas and ignoring ALIAS columns in ClickHouse")
    public void testMultipleSchemaReplication() throws Exception {
        Network network = Network.newNetwork();

        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        Thread.sleep(10000);

        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(),  false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Poll until initial snapshot completes — 'tm' table has 23 columns and 2 rows
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        Assert.assertTrue("Timed out waiting for 'tm' table columns",
                ITCommon.waitForTableColumns(writer.getConnection(), "public", "tm", 23, 180_000));

        long tmCount = ITCommon.waitForRowCount(writer.getConnection(),
                "select count(*) from public.tm final", 2, 180_000, 5_000);
        Assert.assertEquals("Expected 2 rows in public.tm", 2, tmCount);

        // Create a postgres connection and insert new records
        // to public2.tm CREATE TABLE "public2.tm" (id uuid DEFAULT gen_random_uuid() NOT NULL PRIMARY KEY, secid uuid, acc_id uuid);
        Connection postgresConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        postgresConn.createStatement().execute("insert into public2.tm2 (id, secid, acc_id) values (gen_random_uuid(), gen_random_uuid(), gen_random_uuid())");
        postgresConn.close();

        // Poll for the inserted row in public.tm2 (up to 60s)
        long tm2Count = ITCommon.waitForRowCount(writer.getConnection(),
                "select count(*) from public.tm2 final", 1, 60_000, 5_000);
        Assert.assertEquals("Expected 1 row in public.tm2", 1, tm2Count);

        DBMetadata dbMetadata = new DBMetadata(getProperties());
        Map<String, String> tmColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "tm", "public");
        Assert.assertTrue(tmColumns.size() == 23);

        Assert.assertTrue(tmColumns.get("id").equalsIgnoreCase("UUID"));
        Assert.assertTrue(tmColumns.get("secid").equalsIgnoreCase("Nullable(UUID)"));
        //Assert.assertTrue(tmColumns.get("am").equalsIgnoreCase("Nullable(Decimal(21,5))"));
        // Debezium timestamps are UTC by definition, so the record-schema
        // auto-create path tags the column with the zone. Verified live on
        // this branch, PostgreSQL 15 -> ClickHouse 24.8:
        //   CREATE TABLE `public`.`tm`(... `created`
        //       Nullable(DateTime64(6, 'UTC')) ...)
        // The bare-precision expectation predates that change and never
        // matched what the connector emits.
        Assert.assertTrue(tmColumns.get("created").equalsIgnoreCase("Nullable(DateTime64(6, 'UTC'))"));

        // valdate table_with_timezone
//        int tableWithTimezoneCount = 0;
//        ResultSet chRsTz = writer.getConnection().prepareStatement("select count(*) from table_time_with_timezone final").executeQuery();
//        while(chRsTz.next()) {
//            tableWithTimezoneCount =  chRsTz.getInt(1);
//        }
//        Assert.assertTrue(tableWithTimezoneCount == 1);

        // Create a connection to postgresql and create a new table.
        Connection postgresConn2 = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        postgresConn2.createStatement().execute("CREATE TABLE public.people( height_cm numeric PRIMARY KEY, height_in numeric GENERATED ALWAYS AS (height_cm / 2.54) STORED)");

        // insert new records into the new table.
        postgresConn2.createStatement().execute("insert into public.people (height_cm) values (180)");

        // Poll until the first people row appears in ClickHouse (up to 60s)
        long firstPeopleCount = ITCommon.waitForRowCount(writer.getConnection(),
                "select count(*) from public.people", 1, 60_000, 5_000);
        Assert.assertTrue("Expected at least 1 row in public.people", firstPeopleCount >= 1);

        // ClickHouse, add ALIAS column to public.people
        writer.getConnection().createStatement().execute("ALTER TABLE public.people ADD COLUMN full_name String ALIAS concat('John', ' ', 'Doe');");

        // Add MATERIALIZED column to public.people
        writer.getConnection().createStatement().execute("ALTER TABLE public.people ADD COLUMN full_name_mat String MATERIALIZED toString(height_cm)");
        postgresConn2.createStatement().execute("insert into public.people (height_cm) values (200)");

        // Poll until public.people has 2 records (up to 60s)
        long peopleCount = ITCommon.waitForRowCount(writer.getConnection(),
                "select count(*) from public.people", 2, 60_000, 5_000);
        Assert.assertEquals("Expected 2 rows in public.people", 2, peopleCount);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();
        HikariDbSource.close();
    }
}
