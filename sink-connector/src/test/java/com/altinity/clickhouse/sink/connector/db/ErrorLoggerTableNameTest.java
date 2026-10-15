package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the "Table system.`null` does not exist" failure: the
 * DDL path passed a null error-table name straight into the INSERT, so logging
 * one failure raised a second one and the real error was never recorded.
 */
public class ErrorLoggerTableNameTest {

    @Test
    public void nullNameFallsBackToDefault() {
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName(null));
    }

    @Test
    public void emptyNameFallsBackToDefault() {
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName(""));
    }

    @Test
    public void literalNullStringFallsBackToDefault() {
        // props.getProperty stringification can surface the literal "null".
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName("null"));
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName("NULL"));
    }

    @Test
    public void configuredNameIsHonored() {
        Assert.assertEquals("my_error_table",
                ErrorLogger.resolveErrorTableName("my_error_table"));
    }
}
