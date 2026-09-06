package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
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
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;

/**
 * Reproduction coverage for issue #1379: the initial snapshot of a multi-table
 * PostgreSQL schema never marked itself complete.
 *
 * <p>The reporter's offset row stayed at
 * {@code "snapshot":"INITIAL","snapshot_completed":false} even though
 * Debezium's own {@code SnapshotResult} logged {@code status=COMPLETED} and the
 * connector moved on to streaming. The schema that triggered it is a typical
 * EF Core model: identity primary keys, foreign keys with
 * {@code ON DELETE CASCADE}, a many-to-many junction table, {@code real}
 * columns, {@code time} columns, and {@code timestamptz} columns defaulting to
 * {@code '-infinity'} (which Debezium's {@code PostgresDefaultValueConverter}
 * cannot parse and warns about twice per startup).
 *
 * <p>This test drives the real pgoutput pipeline against a live PostgreSQL
 * source with that schema shape, lets the initial snapshot run, and then
 * asserts on the offset row persisted in ClickHouse -- the exact place the
 * reporter observed the stale flag -- as well as the replicated row counts.
 */
public class PostgresSchemaIT {

    /** DateTime64 lower bound, the saturation target for {@code -infinity}. */
    private static final String SATURATED_MIN = "1900-01-01 00:00:00";

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @Container
    public static PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("public")
            .withUsername("root")
            .withPassword("root")
            .withExposedPorts(5432)
            .withCommand("postgres -c wal_level=logical")
            .withNetworkAliases("postgres").withAccessToHost(true);

    public Properties getProperties() throws Exception {
        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        properties.put("topic.prefix", "schema-it-server");
        properties.put("slot.name", "schema_it_slot");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("snapshot.mode", "initial");
        properties.put("auto.create.tables", "true");
        properties.put("table.include.list",
                "public.TableA,public.TableB,public.TableC,public.TableD,"
                        + "public.TableE,public.TableF,public.TableG,public.TableH,"
                        + "public.TableA_TableB");
        return properties;
    }

    @Test
    @DisplayName("Integration Test - issue #1379: initial snapshot of a multi-table schema must mark snapshot_completed=true")
    public void testSnapshotCompletes() throws Exception {
        Network network = Network.newNetwork();

        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        Thread.sleep(10000);

        // Schema and rows are seeded BEFORE the connector starts so they are
        // captured by the initial snapshot -- the path the reporter hit.
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        createSchema(pgConn);
        seedData(pgConn);

        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(60000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // 1. Every table replicated its rows.
        assertRowCount(writer, "TableA", 2);
        assertRowCount(writer, "TableB", 2);
        assertRowCount(writer, "TableA_TableB", 2);
        assertRowCount(writer, "TableC", 2);
        assertRowCount(writer, "TableD", 2);
        assertRowCount(writer, "TableE", 2);
        assertRowCount(writer, "TableF", 2);
        assertRowCount(writer, "TableG", 2);
        assertRowCount(writer, "TableH", 2);

        // 2. The '-infinity' default on TableB."EndDate"/"StartDate" must
        // saturate to the DateTime64 lower bound, not collapse to the epoch or
        // arrive empty (the converter warning in the issue logs is benign).
        String endDate = selectValue(writer, "TableB", "EndDate", 1);
        Assert.assertNotNull("no row replicated for TableB id=1", endDate);
        Assert.assertTrue("'-infinity' must saturate to " + SATURATED_MIN
                        + "; got: " + endDate,
                endDate.startsWith(SATURATED_MIN));

        // 3. Issue #1379: the persisted offset must record the snapshot as
        // complete. The reporter's offset stayed at
        // "snapshot":"INITIAL","snapshot_completed":false forever, so this is
        // the assertion that fails against the buggy behaviour. Query the
        // latest offset row directly rather than by offset_key, whose server
        // component is the topic prefix and is easy to get wrong in a test.
        //
        // STRICT form: only an explicit "snapshot_completed":true passes. A
        // stuck "snapshot_completed":false fails (the bug), and so does an
        // offset with the field absent -- a fully-streaming offset can
        // legitimately drop the snapshot fields once WAL records flow, but
        // this test issues no post-snapshot DML and configures no heartbeat,
        // so the last committed offset should be the snapshot-completion one.
        // Either way the failure message prints the actual offset_val.
        String offsetValue = latestOffsetValue(writer.getConnection());
        Assert.assertNotNull("no offset row was persisted to ClickHouse", offsetValue);
        Assert.assertFalse("offset row must not be empty", offsetValue.trim().isEmpty());
        Assert.assertTrue("offset must record snapshot_completed=true after the "
                        + "initial snapshot (#1379); got: " + offsetValue,
                offsetValue.contains("\"snapshot_completed\":true"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        pgConn.close();
        executorService.shutdown();

        HikariDbSource.close();
    }

    /** Creates the 9-table EF Core-style schema from the issue. */
    private static void createSchema(Connection pgConn) throws Exception {
        pgConn.prepareStatement(
                "create table \"TableA\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableA\" primary key,"
                        + "\"Name\" varchar(255) not null,"
                        + "\"Code\" text not null,"
                        + "\"CreatedAt\" timestamp with time zone,"
                        + "\"UpdatedAt\" timestamp with time zone)").execute();

        pgConn.prepareStatement(
                "create table \"TableB\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableB\" primary key,"
                        + "\"Description\" varchar(255) not null,"
                        + "\"Target\" integer not null,"
                        + "\"CreatedAt\" timestamp with time zone,"
                        + "\"UpdatedAt\" timestamp with time zone,"
                        + "\"FieldA\" real,"
                        + "\"FieldB\" real,"
                        + "\"Alias\" varchar(255) default ''::character varying not null,"
                        + "\"Category\" text,"
                        + "\"EndDate\" timestamp with time zone default '-infinity'::timestamp with time zone,"
                        + "\"Fee\" real,"
                        + "\"StartDate\" timestamp with time zone default '-infinity'::timestamp with time zone)").execute();

        pgConn.prepareStatement(
                "create table \"TableA_TableB\" ("
                        + "\"TableAId\" bigint not null"
                        + " constraint \"FK_TableA_TableB_TableA_TableAId\" references \"TableA\" on delete cascade,"
                        + "\"TableBId\" bigint not null"
                        + " constraint \"FK_TableA_TableB_TableB_TableBId\" references \"TableB\" on delete cascade,"
                        + "constraint \"PK_TableA_TableB\" primary key (\"TableAId\", \"TableBId\"))").execute();

        pgConn.prepareStatement(
                "create table \"TableC\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableC\" primary key,"
                        + "\"TableBId\" bigint constraint \"FK_TableC_TableB_TableBId\" references \"TableB\","
                        + "\"Type\" integer not null,"
                        + "\"Scope\" integer not null,"
                        + "\"Value\" real,"
                        + "\"MinimalCover\" real,"
                        + "\"CreatedAt\" timestamp with time zone,"
                        + "\"UpdatedAt\" timestamp with time zone)").execute();

        pgConn.prepareStatement(
                "create table \"TableD\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableD\" primary key,"
                        + "\"TableBId\" bigint constraint \"FK_TableD_TableB_TableBId\" references \"TableB\","
                        + "\"StartDate\" timestamp with time zone not null,"
                        + "\"EndDate\" timestamp with time zone not null,"
                        + "\"CreatedAt\" timestamp with time zone,"
                        + "\"UpdatedAt\" timestamp with time zone,"
                        + "\"ExternalId\" bigint)").execute();

        pgConn.prepareStatement(
                "create table \"TableE\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableE\" primary key,"
                        + "\"TableCId\" bigint default 0 not null"
                        + " constraint \"FK_TableE_TableC_TableCId\" references \"TableC\" on delete cascade,"
                        + "\"Cap\" real not null,"
                        + "\"Floor\" real not null,"
                        + "\"FieldC\" integer,"
                        + "\"CreatedAt\" timestamp with time zone,"
                        + "\"UpdatedAt\" timestamp with time zone)").execute();

        pgConn.prepareStatement(
                "create table \"TableF\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableF\" primary key,"
                        + "\"TableCId\" bigint default 0 not null"
                        + " constraint \"FK_TableF_TableC_TableCId\" references \"TableC\" on delete cascade,"
                        + "\"StartHour\" time not null,"
                        + "\"EndHour\" time not null,"
                        + "\"Value\" real not null,"
                        + "\"CreatedAt\" timestamp with time zone,"
                        + "\"UpdatedAt\" timestamp with time zone)").execute();

        pgConn.prepareStatement(
                "create table \"TableG\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableG\" primary key,"
                        + "\"TableBWinterId\" bigint constraint \"FK_TableG_TableB_TableBWinterId\" references \"TableB\","
                        + "\"TableBSummerId\" bigint constraint \"FK_TableG_TableB_TableBSummerId\" references \"TableB\","
                        + "\"ExternalId\" bigint,"
                        + "\"CreatedAt\" timestamp with time zone,"
                        + "\"UpdatedAt\" timestamp with time zone)").execute();

        pgConn.prepareStatement(
                "create table \"TableH\" ("
                        + "\"Id\" bigint generated by default as identity"
                        + " constraint \"PK_TableH\" primary key,"
                        + "\"DateTime\" timestamp with time zone not null,"
                        + "\"Value\" real not null,"
                        + "\"TableAId\" bigint default 0 not null"
                        + " constraint \"FK_TableH_TableA_TableAId\" references \"TableA\" on delete cascade,"
                        + "\"CreatedAt\" timestamp with time zone not null,"
                        + "\"UpdatedAt\" timestamp with time zone,"
                        + "\"Discriminator\" varchar(34) default ''::character varying not null)").execute();
    }

    /** Two rows per table, honouring the FK graph. Ids are identity-generated. */
    private static void seedData(Connection pgConn) throws Exception {
        pgConn.prepareStatement(
                "insert into \"TableA\" (\"Name\", \"Code\", \"CreatedAt\", \"UpdatedAt\") values "
                        + "('alpha', 'A-1', '2024-07-24 12:34:56+00', '2024-07-24 12:34:56+00'),"
                        + "('beta', 'B-1', '2024-07-25 12:34:56+00', null)").execute();

        // Row 1 takes the '-infinity' defaults; row 2 sets explicit dates.
        pgConn.prepareStatement(
                "insert into \"TableB\" (\"Description\", \"Target\", \"CreatedAt\", \"FieldA\", \"Fee\", \"Category\") values "
                        + "('tariff-one', 10, '2024-07-24 12:34:56+00', 1.5, 2.5, 'cat-a'),"
                        + "('tariff-two', 20, '2024-07-25 12:34:56+00', 3.5, 4.5, 'cat-b')").execute();
        pgConn.prepareStatement(
                "update \"TableB\" set \"StartDate\" = '2024-01-01 00:00:00+00',"
                        + " \"EndDate\" = '2024-12-31 23:59:59+00' where \"Description\" = 'tariff-two'").execute();

        pgConn.prepareStatement(
                "insert into \"TableA_TableB\" (\"TableAId\", \"TableBId\") values (1, 1), (2, 2)").execute();

        pgConn.prepareStatement(
                "insert into \"TableC\" (\"TableBId\", \"Type\", \"Scope\", \"Value\", \"MinimalCover\", \"CreatedAt\") values "
                        + "(1, 1, 1, 10.5, 1.0, '2024-07-24 12:34:56+00'),"
                        + "(2, 2, 2, 20.5, 2.0, '2024-07-25 12:34:56+00')").execute();

        pgConn.prepareStatement(
                "insert into \"TableD\" (\"TableBId\", \"StartDate\", \"EndDate\", \"CreatedAt\", \"ExternalId\") values "
                        + "(1, '2024-01-01 00:00:00+00', '2024-06-30 23:59:59+00', '2024-07-24 12:34:56+00', 1001),"
                        + "(2, '2024-07-01 00:00:00+00', '2024-12-31 23:59:59+00', '2024-07-25 12:34:56+00', 1002)").execute();

        pgConn.prepareStatement(
                "insert into \"TableE\" (\"TableCId\", \"Cap\", \"Floor\", \"FieldC\", \"CreatedAt\") values "
                        + "(1, 100.0, 0.0, 5, '2024-07-24 12:34:56+00'),"
                        + "(2, 200.0, 10.0, 6, '2024-07-25 12:34:56+00')").execute();

        pgConn.prepareStatement(
                "insert into \"TableF\" (\"TableCId\", \"StartHour\", \"EndHour\", \"Value\", \"CreatedAt\") values "
                        + "(1, '08:00:00', '12:00:00', 1.25, '2024-07-24 12:34:56+00'),"
                        + "(2, '13:00:00', '18:00:00', 2.75, '2024-07-25 12:34:56+00')").execute();

        pgConn.prepareStatement(
                "insert into \"TableG\" (\"TableBWinterId\", \"TableBSummerId\", \"ExternalId\", \"CreatedAt\") values "
                        + "(1, 2, 5001, '2024-07-24 12:34:56+00'),"
                        + "(2, 1, 5002, '2024-07-25 12:34:56+00')").execute();

        pgConn.prepareStatement(
                "insert into \"TableH\" (\"DateTime\", \"Value\", \"TableAId\", \"CreatedAt\") values "
                        + "('2024-07-24 00:00:00+00', 42.5, 1, '2024-07-24 12:34:56+00'),"
                        + "('2024-07-25 00:00:00+00', 43.5, 2, '2024-07-25 12:34:56+00')").execute();
    }

    /** Asserts the FINAL row count of one replicated table. */
    private static void assertRowCount(BaseDbWriter writer, String table, int expected) throws Exception {
        int count = 0;
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                "select count(*) from public.\"" + table + "\" final", writer.getConnection());
        while (rs.next()) {
            count = rs.getInt(1);
        }
        Assert.assertEquals("row count mismatch for table " + table, expected, count);
    }

    /** Reads one column of one replicated row back from ClickHouse as a string. */
    private static String selectValue(BaseDbWriter writer, String table, String column, long id) throws Exception {
        String value = null;
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                "select toString(\"" + column + "\") as v from public.\"" + table
                        + "\" final where \"Id\" = " + id,
                writer.getConnection());
        while (rs.next()) {
            value = rs.getString("v");
        }
        return value;
    }

    /**
     * Returns the most recently written offset payload from the Debezium
     * offset table, or null when no offset has been flushed yet.
     */
    private static String latestOffsetValue(Connection chConn) throws Exception {
        String value = null;
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                "select offset_val from altinity_sink_connector.replica_source_info final"
                        + " order by record_insert_ts desc, record_insert_seq desc limit 1",
                chConn);
        while (rs.next()) {
            value = rs.getString("offset_val");
        }
        return value;
    }
}
