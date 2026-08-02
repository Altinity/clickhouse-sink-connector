package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Tests for BaseDbWriter — Phase 9 edge case coverage.
 * <p>
 * Focuses on splitJdbcProperties() which previously crashed with
 * ArrayIndexOutOfBoundsException on malformed input.
 * </p>
 */
public class BaseDbWriterTest {

    @Nested
    @DisplayName("splitJdbcProperties edge cases")
    class SplitJdbcPropertiesTests {

        @Test
        @DisplayName("Normal key=value pairs should be parsed correctly")
        public void testNormalProperties() {
            Properties props = BaseDbWriter.splitJdbcProperties("key1=value1,key2=value2");
            Assert.assertEquals("value1", props.getProperty("key1"));
            Assert.assertEquals("value2", props.getProperty("key2"));
            Assert.assertEquals(2, props.size());
        }

        @Test
        @DisplayName("Single property should be parsed correctly")
        public void testSingleProperty() {
            Properties props = BaseDbWriter.splitJdbcProperties("key1=value1");
            Assert.assertEquals("value1", props.getProperty("key1"));
            Assert.assertEquals(1, props.size());
        }

        @Test
        @DisplayName("Null input should return empty Properties (not NPE)")
        public void testNullInput() {
            Properties props = BaseDbWriter.splitJdbcProperties(null);
            Assert.assertNotNull("Null input should return empty Properties", props);
            Assert.assertEquals(0, props.size());
        }

        @Test
        @DisplayName("Empty string should return empty Properties")
        public void testEmptyString() {
            Properties props = BaseDbWriter.splitJdbcProperties("");
            Assert.assertNotNull(props);
            Assert.assertEquals(0, props.size());
        }

        @Test
        @DisplayName("Whitespace-only string should return empty Properties")
        public void testWhitespaceOnly() {
            Properties props = BaseDbWriter.splitJdbcProperties("   ");
            Assert.assertNotNull(props);
            Assert.assertEquals(0, props.size());
        }

        @Test
        @DisplayName("Trailing comma should not cause ArrayIndexOutOfBoundsException")
        public void testTrailingComma() {
            Properties props = BaseDbWriter.splitJdbcProperties("key1=value1,");
            Assert.assertEquals("value1", props.getProperty("key1"));
            Assert.assertEquals(1, props.size());
        }

        @Test
        @DisplayName("Leading comma should not cause ArrayIndexOutOfBoundsException")
        public void testLeadingComma() {
            Properties props = BaseDbWriter.splitJdbcProperties(",key1=value1");
            Assert.assertEquals("value1", props.getProperty("key1"));
            Assert.assertEquals(1, props.size());
        }

        @Test
        @DisplayName("Property without value should be skipped (not crash)")
        public void testPropertyWithoutValue() {
            Properties props = BaseDbWriter.splitJdbcProperties("key1=value1,key2,key3=value3");
            Assert.assertEquals("value1", props.getProperty("key1"));
            Assert.assertEquals("value3", props.getProperty("key3"));
            Assert.assertNull("key2 with no value should be skipped", props.getProperty("key2"));
        }

        @Test
        @DisplayName("Value containing equals sign should be preserved")
        public void testValueWithEqualsSign() {
            // This is important for base64-encoded values or connection strings
            Properties props = BaseDbWriter.splitJdbcProperties("key1=value=with=equals");
            Assert.assertEquals("value=with=equals", props.getProperty("key1"));
        }

        @Test
        @DisplayName("Multiple commas in a row should be handled")
        public void testMultipleCommas() {
            Properties props = BaseDbWriter.splitJdbcProperties("key1=value1,,key2=value2");
            Assert.assertEquals("value1", props.getProperty("key1"));
            Assert.assertEquals("value2", props.getProperty("key2"));
            Assert.assertEquals(2, props.size());
        }

        @Test
        @DisplayName("Properties with whitespace around keys and values should be trimmed")
        public void testWhitespaceAroundKeysAndValues() {
            Properties props = BaseDbWriter.splitJdbcProperties(" key1 = value1 , key2 = value2 ");
            Assert.assertEquals("value1", props.getProperty("key1"));
            Assert.assertEquals("value2", props.getProperty("key2"));
        }
    }

    @Nested
    @DisplayName("getConnectionString")
    class ConnectionStringTests {

        @Test
        @DisplayName("Should format JDBC URL correctly")
        public void testGetConnectionString() {
            String result = BaseDbWriter.getConnectionString("localhost", 8123, "mydb");
            Assert.assertEquals("jdbc:clickhouse://localhost:8123/mydb", result);
        }

        @Test
        @DisplayName("Should handle IPv6 address")
        public void testGetConnectionStringIPv6() {
            String result = BaseDbWriter.getConnectionString("::1", 8123, "mydb");
            Assert.assertEquals("jdbc:clickhouse://::1:8123/mydb", result);
        }
    }

    @Test
    public void testSplitJdbcPropertiesNullInput() {
        Properties props = BaseDbWriter.splitJdbcProperties(null);
        Assert.assertNotNull(props);
        Assert.assertTrue(props.isEmpty());
    }

    @Test
    public void testSplitJdbcPropertiesEmptyInput() {
        Properties props = BaseDbWriter.splitJdbcProperties("");
        Assert.assertNotNull(props);
        Assert.assertTrue(props.isEmpty());
    }

    @Test
    public void testSplitJdbcPropertiesWithEqualsInValue() {
        // Values containing '=' should be preserved (split limit=2)
        String jdbcProperties = "custom_settings=allow_experimental_object_type=1";
        Properties props = BaseDbWriter.splitJdbcProperties(jdbcProperties);
        Assert.assertEquals("allow_experimental_object_type=1",
                props.getProperty("custom_settings"));
    }

    @Test
    public void testSplitJdbcPropertiesMalformedEntry() {
        // Malformed entries (no '=') should be skipped without exception
        String jdbcProperties = "good_key=good_value,malformed_no_equals,another_good=value";
        Properties props = BaseDbWriter.splitJdbcProperties(jdbcProperties);
        Assert.assertEquals("good_value", props.getProperty("good_key"));
        Assert.assertEquals("value", props.getProperty("another_good"));
        Assert.assertNull(props.getProperty("malformed_no_equals"));
    }

    @Test
    public void testSplitJdbcPropertiesWithSsl() {
        // Kept from upstream 2.10.0, but rewritten to call the STATIC
        // splitJdbcProperties directly. The upstream version constructed a
        // BaseDbWriter via createConnection("localhost", ...), which needs a
        // live ClickHouse and so fails in any environment without one — the
        // parsing behaviour under test needs no database at all.
        String jdbcProperties = "ssl=true,sslmode=none";
        Properties properties = BaseDbWriter.splitJdbcProperties(jdbcProperties);
        Assert.assertEquals("true", properties.getProperty("ssl"));
        Assert.assertEquals("none", properties.getProperty("sslmode"));
    }

    // NOTE: the merge re-introduced upstream 2.10.0's duplicate
    // testSplitJdbcPropertiesWithSsl(), which builds a BaseDbWriter through
    // createConnection("localhost", ...) and therefore needs a live ClickHouse.
    // It is dropped in favour of the version above, which exercises exactly the
    // same parsing behaviour with no database. Two methods of the same name in
    // one class also does not compile.
}
