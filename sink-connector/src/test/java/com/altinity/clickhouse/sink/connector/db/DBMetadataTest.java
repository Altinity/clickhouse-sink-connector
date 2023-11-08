package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.commons.lang3.tuple.MutablePair;
import java.sql.SQLException;
import java.util.HashMap;

@Testcontainers

public class DBMetadataTest {

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("./init_clickhouse.sql");
    @Test
    public void testGetSignColumnForCollapsingMergeTree() {

        DBMetadata metadata = new DBMetadata();

        String createTableDML = "CollapsingMergeTree(signNumberCol) PRIMARY KEY productCode ORDER BY productCode SETTINGS index_granularity = 8192";
        String signColumn = metadata.getSignColumnForCollapsingMergeTree(createTableDML);

        Assert.assertTrue(signColumn.equalsIgnoreCase("signNumberCol"));
    }

    @Test
    public void testDefaultGetSignColumnForCollapsingMergeTree() {

        DBMetadata metadata = new DBMetadata();

        String createTableDML = "ReplacingMergeTree() PRIMARY KEY productCode ORDER BY productCode SETTINGS index_granularity = 8192";
        String signColumn = metadata.getSignColumnForCollapsingMergeTree(createTableDML);

        Assert.assertTrue(signColumn.equalsIgnoreCase("sign"));
    }

    @Test
    public void testGetVersionColumnForReplacingMergeTree() {
        DBMetadata metadata = new DBMetadata();

        String createTableDML = "ReplacingMergeTree(versionNo) PRIMARY KEY productCode ORDER BY productCode SETTINGS index_granularity = 8192";
        String signColumn = metadata.getVersionColumnForReplacingMergeTree(createTableDML);

        Assert.assertTrue(signColumn.equalsIgnoreCase("versionNo"));

    }

    @Test
    @Tag("IntegrationTest")
    public void testCheckIfDatabaseExists() throws SQLException {

        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "default";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);

        // Default database exists.
        boolean result = new DBMetadata().checkIfDatabaseExists(writer.getConnection(), "default");
        Assert.assertTrue(result);

        boolean result2 = new DBMetadata().checkIfDatabaseExists(writer.getConnection(), "newdb");
        Assert.assertFalse(result2);

    }

    @Test
    public void testGetEngineFromResponse() {

        String replacingMergeTree = "ReplacingMergeTree(ver) PRIMARY KEY dept_no ORDER BY dept_no SETTINGS index_granularity = 8192";
        MutablePair<DBMetadata.TABLE_ENGINE, String> replacingMergeTreeResult = new DBMetadata().getEngineFromResponse(replacingMergeTree);

        Assert.assertTrue(replacingMergeTreeResult.getRight().equalsIgnoreCase("ver"));
        Assert.assertTrue(replacingMergeTreeResult.getLeft().getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine()));


        String replicatedReplacingMergeTree = "ReplicatedReplacingMergeTree('/clickhouse/{cluster}/tables/dashboard_mysql_replication/favourite_products', '{replica}', ver) ORDER BY id SETTINGS allow_nullable_key = 1, index_granularity = 8192";

        MutablePair<DBMetadata.TABLE_ENGINE, String> replicatedReplacingMergeTreeResult = new DBMetadata().getEngineFromResponse(replicatedReplacingMergeTree);

        Assert.assertTrue(replicatedReplacingMergeTreeResult.getRight().equalsIgnoreCase("ver"));
        Assert.assertTrue(replicatedReplacingMergeTreeResult.getLeft().getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine()));


    }

    @ParameterizedTest
    @CsvSource({
            "23.2, true",
            "23.1, false",
            "23.2.1, true",
            "23.1.1, false",
            "23.0.1, false",
            "23.3, true",
            "33.1, true",
            "23.10.1, true"
    })
    public void testIsRMTVersionSupported(String clickhouseVersion, boolean result) throws SQLException {
        Assert.assertTrue(new DBMetadata().checkIfNewReplacingMergeTree(clickhouseVersion) == result);
    }

}
