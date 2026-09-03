package com.altinity.clickhouse.debezium.embedded.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The offset table must deduplicate on the OFFSET KEY, never on {@code id}.
 *
 * <p>Debezium's {@code JdbcOffsetBackingStore.save()} performs a
 * delete-then-insert, and the value it binds to {@code id} is
 * {@code UUID.randomUUID().toString()} -- a fresh value on every flush. On
 * ClickHouse the configured "delete" is {@code select * from %s}, a SELECT
 * that removes nothing, because ClickHouse has no cheap row DELETE. Those two
 * facts together mean the offset table only ever accumulates rows.</p>
 *
 * <p>So the sort key is the ONLY thing that can collapse an old offset into
 * the new one. With {@code ORDER BY id} every row is unique by construction,
 * ReplacingMergeTree can never merge them, and {@code FINAL} is a no-op --
 * every offset ever written stays queryable forever. The read path then
 * orders by {@code record_insert_ts, record_insert_seq}, but the timestamp is
 * only second-resolution and the sequence restarts at 1 on every connector
 * start, so after a restart a STALE row can sort last and win. The connector
 * resumes from an old position, and a snapshot that has already finished
 * reads back as still in progress -- the residual half of issue #1379.</p>
 *
 * <p>{@code ORDER BY offset_key} makes the newest write for a given connector
 * replace the previous one, which is what the Kubernetes and docker-compose
 * samples in this repository already used.</p>
 *
 * <p>This asserts over EVERY {@code config.properties} visible on the
 * classpath rather than the first match, because the test resources shadow
 * the shipped defaults: checking only the resolved one would silently stop
 * covering {@code src/main/resources}, which is the file every deployment
 * inherits ({@code ClickHouseDebeziumEmbeddedApplication#loadPropertiesFile}
 * and {@code EnvironmentConfigurationService#parse} both load it as the base
 * layer). The DDL is matched as raw text, not via {@code Properties}, so a
 * value written across multiple unescaped lines is still checked in full.</p>
 */
public class OffsetTableDdlSortKeyTest {

    /** Resource name of the shipped defaults. */
    private static final String DEFAULTS = "config.properties";

    /**
     * A ReplacingMergeTree offset-table DDL and its sort key. DOTALL so the
     * DDL may span lines, which it does in several shipped files.
     */
    private static final Pattern OFFSET_DDL = Pattern.compile(
            "offset\\.storage\\.jdbc\\.(?:offset\\.)?table\\.ddl.{0,900}?"
                    + "ENGINE\\s*=\\s*ReplacingMergeTree\\([^)]*\\)\\s*ORDER BY\\s+`?([A-Za-z_]+)`?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static String read(URL url) throws Exception {
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Test
    @DisplayName("Every shipped offset table deduplicates on offset_key, not on the random id (#1379)")
    public void everyOffsetTableDdlSortsByOffsetKey() throws Exception {
        Enumeration<URL> found =
                getClass().getClassLoader().getResources(DEFAULTS);

        List<String> checked = new ArrayList<>();
        while (found.hasMoreElements()) {
            URL url = found.nextElement();
            Matcher m = OFFSET_DDL.matcher(read(url));
            while (m.find()) {
                checked.add(url.toString());
                // Before the fix this was "id", bound to UUID.randomUUID()
                // on every flush, so no two rows ever collapsed.
                assertEquals("the offset table must deduplicate on offset_key; sorting by the "
                                + "random id makes ReplacingMergeTree unable to ever collapse a "
                                + "stale offset, so a finished snapshot reads back as still in "
                                + "progress (#1379). Offending resource: " + url,
                        "offset_key", m.group(1));
            }
        }

        // Guards the assertion above against passing vacuously: if the
        // resource is renamed or the DDL stops being declared there, this
        // test would otherwise assert nothing at all.
        assertTrue("no offset-table DDL was found in any " + DEFAULTS + " on the classpath, so "
                + "this test verified nothing", !checked.isEmpty());
    }

    @Test
    @DisplayName("The offset DDL keeps the columns the sort key and versioning depend on")
    public void offsetDdlKeepsRequiredColumns() throws Exception {
        Enumeration<URL> found =
                getClass().getClassLoader().getResources(DEFAULTS);

        boolean sawOne = false;
        while (found.hasMoreElements()) {
            URL url = found.nextElement();
            String body = read(url);
            if (!OFFSET_DDL.matcher(body).find()) {
                continue;
            }
            sawOne = true;
            // offset_key is only a valid sort key if it is a real column and
            // the payload travels alongside it.
            assertTrue("offset_key must be a column of the offset table in " + url,
                    body.contains("offset_key"));
            assertTrue("offset_val must be a column of the offset table in " + url,
                    body.contains("offset_val"));
            // _version decides which duplicate wins once offset_key collapses them.
            assertTrue("the offset DDL must keep a _version column for ReplacingMergeTree to "
                    + "rank rows in " + url, body.contains("_version"));
        }
        assertTrue("no offset-table DDL was found, so this test verified nothing", sawOne);
    }
}
