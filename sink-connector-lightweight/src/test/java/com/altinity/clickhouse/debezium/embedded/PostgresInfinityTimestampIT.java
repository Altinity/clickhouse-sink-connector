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
 * End-to-end regression coverage for issue #1231: PostgreSQL {@code timestamptz}
 * columns holding the special values {@code infinity} and {@code -infinity}
 * replicated as empty timestamps.
 *
 * <p>PostgreSQL accepts {@code infinity} and {@code -infinity} in any
 * {@code timestamp}/{@code timestamptz} column. Debezium delivers them verbatim
 * as those literal strings, which match none of the timestamp patterns in
 * {@code DebeziumConverter.ZonedTimestampConverter.convert}, so {@code result}
 * was returned as the empty string it was initialised to. ClickHouse then
 * stored the epoch (or rejected the row) instead of a value ordered correctly
 * against the rest of the column.
 *
 * <p>ClickHouse DateTime64 cannot represent an actual infinity, so the fix
 * saturates to the bounds of the target type -- the same clamping already
 * applied to out-of-range timestamps -- which preserves the PostgreSQL ordering
 * semantics that infinity sorts after, and -infinity before, every other
 * timestamp.
 *
 * <p>This test drives the real Debezium pipeline against a live PostgreSQL
 * source and asserts on the values read back from a live ClickHouse server,
 * because the defect is in what the destination ends up holding. It follows the
 * pgoutput harness of
 * {@link ClickHouseDebeziumEmbeddedPostgresPgoutputDockerIT}: pgoutput is built
 * into stock PostgreSQL, so no output-plugin image is required.
 */
public class PostgresInfinityTimestampIT {

    /** DateTime64 upper bound, the saturation target for {@code infinity}. */
    private static final String SATURATED_MAX = "2299-12-31 23:59:59";

    /** DateTime64 lower bound, the saturation target for {@code -infinity}. */
    private static final String SATURATED_MIN = "1900-01-01 00:00:00";

    /** A finite timestamp, to prove ordinary values still replicate correctly. */
    private static final String FINITE = "2024-07-24 12:34:56";

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
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        properties.put("topic.prefix", "test-server");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("table.include.list", "public.infinity_ts");
        properties.put("auto.create.tables", "true");
        return properties;
    }

    @Test
    @DisplayName("Integration Test - Validates that PostgreSQL infinity timestamptz values saturate to the DateTime64 bounds")
    public void testInfinityTimestamptzSaturates() throws Exception {
        Network network = Network.newNetwork();

        postgreSQLContainer.withNetwork(network).start();
        clickHouseContainer.withNetwork(network).start();
        Thread.sleep(10000);

        // The rows are seeded BEFORE the connector starts so they arrive through
        // the initial snapshot, which is the path the reporter hit.
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement("CREATE TABLE public.infinity_ts ("
                + "id int PRIMARY KEY, label text, ts timestamptz NOT NULL)").execute();
        pgConn.prepareStatement("INSERT INTO public.infinity_ts VALUES "
                + "(1, 'positive', 'infinity'), "
                + "(2, 'negative', '-infinity'), "
                + "(3, 'finite', '" + FINITE + "+00')").execute();

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

        Thread.sleep(50000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        String positive = selectTimestamp(writer.getConnection(), 1);
        String negative = selectTimestamp(writer.getConnection(), 2);
        String finite = selectTimestamp(writer.getConnection(), 3);

        // The reported symptom: the converter returned "" for these literals, so
        // nothing meaningful reached the column.
        Assert.assertNotNull("no row replicated for the 'infinity' timestamptz", positive);
        Assert.assertFalse("the 'infinity' timestamptz replicated as an empty value; "
                + "the converter matched none of its patterns and returned the empty "
                + "string it was initialised to", positive.trim().isEmpty());

        Assert.assertTrue("PostgreSQL 'infinity' must saturate to the DateTime64 upper bound "
                        + SATURATED_MAX + " so it keeps sorting after every other timestamp; got: "
                        + positive,
                positive.startsWith(SATURATED_MAX));

        Assert.assertNotNull("no row replicated for the '-infinity' timestamptz", negative);
        Assert.assertFalse("the '-infinity' timestamptz replicated as an empty value",
                negative.trim().isEmpty());
        Assert.assertTrue("PostgreSQL '-infinity' must saturate to the DateTime64 lower bound "
                        + SATURATED_MIN + " so it keeps sorting before every other timestamp; got: "
                        + negative,
                negative.startsWith(SATURATED_MIN));

        // 1970-01-01 is what an empty/unparsed value collapses to. Naming it
        // explicitly keeps the failure message honest about the old behaviour.
        Assert.assertFalse("'infinity' must not collapse to the epoch; got: " + positive,
                positive.startsWith("1970-01-01"));
        Assert.assertFalse("'-infinity' must not collapse to the epoch; got: " + negative,
                negative.startsWith("1970-01-01"));

        // Ordinary timestamps must be untouched by the saturation branch.
        Assert.assertNotNull("no row replicated for the finite timestamptz", finite);
        Assert.assertTrue("a finite timestamptz must replicate unchanged; got: " + finite,
                finite.startsWith(FINITE));

        // The whole point of saturating rather than nulling: ordering survives.
        Assert.assertTrue("the saturated bounds must bracket the finite value, otherwise "
                        + "ORDER BY on the column no longer matches PostgreSQL",
                negative.compareTo(finite) < 0 && finite.compareTo(positive) < 0);

        if (engine.get() != null) {
            engine.get().stop();
        }
        pgConn.close();
        executorService.shutdown();

        HikariDbSource.close();
    }

    /** Reads one replicated timestamp back from ClickHouse as a string. */
    private static String selectTimestamp(Connection chConn, int id) throws Exception {
        String value = null;
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                "select toString(ts) as ts from public.infinity_ts final where id = " + id,
                chConn);
        while (rs.next()) {
            value = rs.getString("ts");
        }
        return value;
    }
}
