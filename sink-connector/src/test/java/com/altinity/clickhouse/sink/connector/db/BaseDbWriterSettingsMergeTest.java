package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.01 section 3.5: {@code clickhouse.jdbc.settings} is MERGED with the connector's default session settings
 * (a user entry overrides that one key and never drops the others), and {@code clickhouse.jdbc.params} entries are
 * split on the first {@code =} only, so a value may itself contain {@code =}.
 */
public class BaseDbWriterSettingsMergeTest {

    private static Map<String, String> asMap(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String entry : csv.split(",")) {
            String[] kv = entry.split("=", 2);
            out.put(kv[0], kv.length > 1 ? kv[1] : null);
        }
        return out;
    }

    @Test
    @DisplayName("an unrelated user setting does not drop insert_allow_materialized_columns or allow_experimental_object_type")
    void userSettingDoesNotClobberDefaults() {
        Map<String, String> effective = asMap(BaseDbWriter.customSettings("async_insert=1"));

        assertEquals("1", effective.get("async_insert"));
        assertEquals("1", effective.get("insert_allow_materialized_columns"), effective.toString());
        assertEquals("1", effective.get("allow_experimental_object_type"), effective.toString());
        assertEquals("0", effective.get("input_format_null_as_default"), effective.toString());
    }

    @Test
    @DisplayName("a user value overrides exactly its own default key and every other default is kept")
    void userOverridesIndividualDefaultKey() {
        Map<String, String> effective = asMap(
                BaseDbWriter.customSettings("allow_experimental_object_type=0"));

        assertEquals("0", effective.get("allow_experimental_object_type"), effective.toString());
        assertEquals("1", effective.get("insert_allow_materialized_columns"), effective.toString());
        assertEquals("0", effective.get("input_format_null_as_default"), effective.toString());
        assertEquals(3, effective.size(), "no key is duplicated: " + effective);
    }

    @Test
    @DisplayName("a setting value that contains '=' survives the merge intact, whitespace and empty entries are dropped")
    void equalsInsideValueAndWhitespaceAreHandled() {
        String merged = BaseDbWriter.customSettings(" log_comment = a=b ,, , async_insert=1 ");
        Map<String, String> effective = asMap(merged);

        assertEquals("a=b", effective.get("log_comment"), merged);
        assertEquals("1", effective.get("async_insert"), merged);
        assertFalse(merged.contains(",,"), "no empty entry is emitted: " + merged);
        assertFalse(merged.startsWith(",") || merged.endsWith(","), merged);
    }

    @Test
    @DisplayName("a blank user list is the default list")
    void blankUserListIsTheDefault() {
        assertEquals(BaseDbWriter.DEFAULT_CUSTOM_SETTINGS, BaseDbWriter.customSettings("   "));
        assertEquals(BaseDbWriter.DEFAULT_CUSTOM_SETTINGS, BaseDbWriter.customSettings(null));
    }

    @Test
    @DisplayName("splitJdbcProperties splits on the first '=' only, so base64 padding and nested settings survive")
    void splitKeepsEqualsInValue() {
        Properties p = BaseDbWriter.splitJdbcProperties(
                "custom_settings=a=1,token=YWJjZA==,socket_timeout=30000");

        assertEquals("a=1", p.getProperty("custom_settings"));
        assertEquals("YWJjZA==", p.getProperty("token"));
        assertEquals("30000", p.getProperty("socket_timeout"));
    }

    @Test
    @DisplayName("splitJdbcProperties trims key and value and skips empty entries")
    void splitTrimsAndSkipsEmpty() {
        Properties p = BaseDbWriter.splitJdbcProperties(" ssl = true , , sslmode=none,");

        assertEquals("true", p.getProperty("ssl"));
        assertEquals("none", p.getProperty("sslmode"));
        assertEquals(2, p.size(), p.toString());
        assertTrue(BaseDbWriter.splitJdbcProperties("").isEmpty());
        assertTrue(BaseDbWriter.splitJdbcProperties(null).isEmpty());
    }

    @Test
    @DisplayName("splitJdbcProperties refuses an entry with no '=' or no key, naming it, instead of an index error")
    void splitRefusesMalformedEntry() {
        IllegalArgumentException noEquals = assertThrows(IllegalArgumentException.class,
                () -> BaseDbWriter.splitJdbcProperties("ssl=true,oops"));
        assertTrue(noEquals.getMessage().contains("oops"), noEquals.getMessage());

        assertThrows(IllegalArgumentException.class, () -> BaseDbWriter.splitJdbcProperties("=value"));
    }

    @Test
    @DisplayName("getConnection keeps returning the (null) held handle when the pool cannot supply one, and does not throw")
    void getConnectionKeepsNullContractWhenAcquisitionFails() {
        // No host/port: jdbcUrl() is null and no pool is registered for the database, so re-acquisition
        // fails. The caller contract (return the held handle, here null) is unchanged; the failure is
        // logged with the database name and the cause rather than swallowed without context.
        BaseDbWriter writer = new BaseDbWriter(null, null, "no_such_db_for_test", "default", "", null, null);
        Connection conn = assertDoesNotThrow(writer::getConnection,
                "getConnection must not throw for a failed re-acquisition");
        assertNull(conn);
    }
}
