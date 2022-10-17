package com.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class DBMetadataTest {

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
}
