package com.altinity.clickhouse.sink.connector.validators;

import org.apache.kafka.common.config.ConfigException;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JdbcV1DriverValidator}: clickhouse.jdbc.v1=true is refused
 * at start, false and unset are accepted (spec 03.08).
 */
public class JdbcV1DriverValidatorTest {

    private static final String KEY = "clickhouse.jdbc.v1";

    @Test
    public void testFalseIsAccepted() {
        new JdbcV1DriverValidator().ensureValid(KEY, Boolean.FALSE);
    }

    @Test
    public void testUnsetIsAccepted() {
        new JdbcV1DriverValidator().ensureValid(KEY, null);
    }

    @Test
    public void testTrueIsRefusedWithKeyAndCause() {
        try {
            new JdbcV1DriverValidator().ensureValid(KEY, Boolean.TRUE);
            Assert.fail("clickhouse.jdbc.v1=true must be refused");
        } catch (ConfigException e) {
            Assert.assertTrue(e.getMessage().contains(KEY));
            Assert.assertTrue(e.getMessage().contains("DateTimeParseException"));
            Assert.assertTrue(e.getMessage().contains("Remove clickhouse.jdbc.v1"));
        }
    }
}
