package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.commons.lang3.tuple.MutablePair;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Spec 05.04 section 6, FM-05.04-3: how {@code DBMetadata.getEngineFromResponse}
 * reads the sign column out of {@code system.tables.engine_full}.
 *
 * <p>The engine is matched with {@code response.contains("CollapsingMergeTree")}
 * and the sign column is the text between {@code "CollapsingMergeTree("} and
 * the first {@code ")"}. That is right for a plain
 * {@code CollapsingMergeTree(sign)} and wrong for the two other engines whose
 * name contains the same substring: a {@code ReplicatedCollapsingMergeTree}
 * (the ZooKeeper path and replica name come first) and a
 * {@code VersionedCollapsingMergeTree} (which spec 05.04 says is not a
 * recognised engine on this path, but is matched as COLLAPSING_MERGE_TREE).
 * The resolved "sign column" is then a name no column has, so the real sign
 * column never gets the connector's +1/-1.</p>
 */
public class CollapsingEngineParsingTest {

    private static DBMetadata metadata() {
        return new DBMetadata(new ClickHouseSinkConnectorConfig(new HashMap<>()));
    }

    @Test
    @DisplayName("A plain CollapsingMergeTree(sgn) resolves the sign column sgn")
    public void plainCollapsingMergeTreeResolvesItsSignColumn() {
        MutablePair<DBMetadata.TABLE_ENGINE, String> engine =
                metadata().getEngineFromResponse("CollapsingMergeTree(sgn) ORDER BY id SETTINGS index_granularity = 8192");
        assertEquals(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE, engine.left);
        assertEquals("sgn", engine.right);
    }

    @Test
    @Disabled("DEFECT FM-05.04-3: the sign column of a ReplicatedCollapsingMergeTree is parsed as the whole "
            + "argument list ('<zk path>', '<replica>', sign), so the real sign column is never bound")
    @DisplayName("A ReplicatedCollapsingMergeTree resolves its sign column, not its ZooKeeper arguments")
    public void replicatedCollapsingMergeTreeResolvesItsSignColumn() {
        MutablePair<DBMetadata.TABLE_ENGINE, String> engine = metadata().getEngineFromResponse(
                "ReplicatedCollapsingMergeTree('/clickhouse/tables/{shard}/db/t', '{replica}', sign) ORDER BY id");
        assertEquals("sign", engine.right);
    }

    @Test
    @Disabled("DEFECT FM-05.04-3: a VersionedCollapsingMergeTree target is matched as COLLAPSING_MERGE_TREE by "
            + "substring, with the sign column parsed as 'sign, ver'")
    @DisplayName("A VersionedCollapsingMergeTree is not treated as a CollapsingMergeTree")
    public void versionedCollapsingMergeTreeIsNotTreatedAsCollapsing() {
        MutablePair<DBMetadata.TABLE_ENGINE, String> engine = metadata().getEngineFromResponse(
                "VersionedCollapsingMergeTree(sign, ver) ORDER BY id");
        assertNotEquals(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE, engine.left,
                "spec 05.04: only CollapsingMergeTree triggers the sign binding; got sign column '" + engine.right + "'");
    }
}
