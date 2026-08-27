package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;

/**
 * The error table name must be a bare identifier that can actually be used in
 * SQL.
 *
 * <p>Observed on a live connector: the derived error table name had FOUR
 * dotted parts, "system.default.error.table". That is not a valid ClickHouse
 * identifier, so the CREATE and INSERT the error logger issues could never
 * execute -- every DDL/insert failure report was silently discarded and the
 * originating error left no durable trace.
 */
public class ErrorLoggerTableNameTest {

    /**
     * The shipped default must be a usable table name.
     *
     * <p>Root cause: ClickHouseSinkConnectorConfig passed the config KEY
     * ("default.error.table") as the ConfigDef DEFAULT VALUE, so a deployment
     * that never set the property received that string as its table name. The
     * error logger then qualified it with the system database, producing
     * "system" + "." + "default.error.table".
     */
    @Test
    public void defaultErrorTableNameIsABareIdentifier() throws SQLException {
        ClickHouseSinkConnectorConfig config =
                new ClickHouseSinkConnectorConfig(new HashMap<String, String>());

        String configured = config.getString(
                ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());

        Assert.assertFalse(
                "The default error table name must not contain a dot; a dotted "
                        + "value is concatenated into an unusable multi-part "
                        + "identifier such as system." + configured
                        + ". Got: " + configured,
                configured != null && configured.contains("."));

        // And it must survive validation, i.e. be usable as-is.
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName(configured));
    }

    /**
     * An operator-supplied dotted name must fail loudly rather than produce
     * invalid SQL that swallows every error report.
     */
    @Test
    public void dottedNameIsRejectedWithAClearMessage() {
        SQLException thrown = Assertions.assertThrows(SQLException.class,
                () -> ErrorLogger.resolveErrorTableName("default.error.table"));

        String message = thrown.getMessage();
        Assert.assertTrue(
                "The failure must name the offending value. Got: " + message,
                message.contains("default.error.table"));
        Assert.assertTrue(
                "The failure must explain the bare-identifier rule. Got: " + message,
                message.contains("bare"));
    }

    @Test
    public void otherInvalidNamesAreRejected() {
        String[] invalid = {
            "system.replica_source_error",
            "db.schema.table",
            "1_starts_with_digit",
            "has space",
            "has-hyphen",
            "trailing.",
        };
        for (String name : invalid) {
            Assertions.assertThrows(
                    SQLException.class,
                    () -> ErrorLogger.resolveErrorTableName(name),
                    "Expected rejection of: " + name);
        }
    }

    /** Control: valid names, and the unset case, keep working unchanged. */
    @Test
    public void validNamesAreAccepted() throws SQLException {
        Assert.assertEquals("replica_source_error",
                ErrorLogger.resolveErrorTableName("replica_source_error"));
        Assert.assertEquals("error_table",
                ErrorLogger.resolveErrorTableName("error_table"));
        Assert.assertEquals("_leading_underscore",
                ErrorLogger.resolveErrorTableName("_leading_underscore"));
        Assert.assertEquals("Mixed_Case9",
                ErrorLogger.resolveErrorTableName("Mixed_Case9"));
        // Surrounding whitespace is tolerated, not treated as invalid.
        Assert.assertEquals("replica_source_error",
                ErrorLogger.resolveErrorTableName("  replica_source_error  "));
        // Unset / blank falls back to the documented default.
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName(null));
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName(""));
        Assert.assertEquals(ErrorLogger.DEFAULT_ERROR_TABLE,
                ErrorLogger.resolveErrorTableName("   "));
    }
}
