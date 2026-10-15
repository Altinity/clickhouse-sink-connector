package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

/**
 * Every translated CREATE TABLE must be idempotent.
 *
 * <p>The ClickHouse side is a replica of the source table, so a CREATE for a
 * table that already exists is a no-op by definition. The translator used to
 * emit a bare {@code CREATE TABLE} and only added the guard when the source
 * MySQL DDL itself carried IF NOT EXISTS -- which a snapshot or binlog CREATE
 * generally does not.</p>
 *
 * <p>Replaying such a CREATE against an existing target returns
 * {@code Code: 57 TABLE_ALREADY_EXISTS}. That error was classified as
 * retryable, so the CDC/DDL thread slept through the whole
 * {@code errors.max.retries} budget with a linear backoff, and every change
 * event that arrived during the window was lost. On a production
 * history-mode connector this produced ~95% row loss on the affected table
 * while the heartbeat kept reporting 1:1 and lag stayed under 20 seconds.</p>
 */
public class CreateTableIdempotentTest {

    private MySQLDDLParserService parserService(boolean replicationHistory) {
        HashMap<String, String> config = new HashMap<>();
        if (replicationHistory) {
            config.put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(), "true");
        }
        config.put(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString(), "America/Chicago");
        return new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(config), "employees");
    }

    @Test
    @DisplayName("A plain CREATE TABLE is translated with the IF NOT EXISTS guard")
    public void testPlainCreateTableIsGuarded() {
        String sql = "CREATE TABLE `heartbeat` (`id` INT PRIMARY KEY, `ts` DATETIME NOT NULL) ENGINE=InnoDB;";
        StringBuffer out = new StringBuffer();
        parserService(false).parseSql(sql, "test_db", out);

        String q = out.toString();
        Assertions.assertTrue(q.toLowerCase().startsWith("create table if not exists "),
                "a replayed CREATE must not fail with TABLE_ALREADY_EXISTS; got: " + q);
    }

    @Test
    @DisplayName("History mode is guarded too -- this is the path that lost events in production")
    public void testHistoryModeCreateTableIsGuarded() {
        String sql = "CREATE TABLE `process` (`id` INT PRIMARY KEY, `appkey` VARCHAR(64) NOT NULL) ENGINE=InnoDB;";
        StringBuffer out = new StringBuffer();
        parserService(true).parseSql(sql, "test_db", out);

        String q = out.toString();
        Assertions.assertTrue(q.toLowerCase().startsWith("create table if not exists "),
                "history-mode CREATE must be idempotent; got: " + q);
        // The bitemporal shape must be unchanged by the guard.
        Assertions.assertTrue(q.contains("`_valid_from`"), q);
        Assertions.assertTrue(q.contains("`_valid_to`"), q);
        Assertions.assertTrue(q.contains("PARTITION BY toDate(`_valid_to`)"), q);
    }

    @Test
    @DisplayName("A source CREATE that already says IF NOT EXISTS does not emit it twice")
    public void testSourceIfNotExistsIsNotDuplicated() {
        String sql = "CREATE TABLE IF NOT EXISTS `process` (`id` INT PRIMARY KEY, `v` VARCHAR(32)) ENGINE=InnoDB;";
        StringBuffer out = new StringBuffer();
        parserService(false).parseSql(sql, "test_db", out);

        String q = out.toString().toLowerCase();
        int first = q.indexOf("if not exists");
        Assertions.assertTrue(first >= 0, "guard missing: " + out);
        Assertions.assertEquals(-1, q.indexOf("if not exists", first + 1),
                "the guard must appear exactly once, not duplicated: " + out);
    }

    @Test
    @DisplayName("The guard precedes the table name and does not corrupt the identifier")
    public void testGuardPlacement() {
        String sql = "CREATE TABLE `process` (`id` INT PRIMARY KEY) ENGINE=InnoDB;";
        StringBuffer out = new StringBuffer();
        parserService(false).parseSql(sql, "test_db", out);

        String q = out.toString();
        int guard = q.toLowerCase().indexOf("if not exists");
        int table = q.indexOf("`employees`.`process`");
        Assertions.assertTrue(guard >= 0 && table >= 0, q);
        Assertions.assertTrue(guard < table, "IF NOT EXISTS must precede the table name: " + q);
    }
}
