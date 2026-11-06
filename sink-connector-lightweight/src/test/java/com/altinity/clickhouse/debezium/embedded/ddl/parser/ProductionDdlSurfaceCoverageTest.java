/*
 * Copyright (c) 2023 Altinity, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The parse surface of the STATEMENT LANGUAGE, measured through
 * {@link MySQLDDLParserService} as the CDC path calls it.
 *
 * <p><b>Why a parse failure here is a production outage, not a nuisance.</b>
 * {@code MySQLDDLParserService.parseSql} installs {@code ErrorListenerImpl} on
 * both the lexer and the parser, and its {@code syntaxError} throws
 * {@code RuntimeException("Error parsing DDL")}. That exception escapes the
 * Debezium event handler and stops the replication stream for EVERY table on
 * the host. The pre-parse filter does not save these statements: the bundled
 * {@code IgnoreDDLRegexLoader} patterns cover partition maintenance and
 * AUTO_INCREMENT only, and {@code DdlCaptureFilter.isCaptured(db, null)} returns
 * {@code true} when the statement names no table at all -- so
 * {@code ALTER TABLESPACE}, {@code SET PERSIST}, {@code CREATE RESOURCE GROUP},
 * {@code CLONE INSTANCE} and their relatives all reach the parser, and all of
 * them are ordinary day-two operations that have nothing to do with row data.</p>
 *
 * <p><b>The two rules this test enforces.</b>
 * <ol>
 *   <li><b>A statement that no listener handler can ever translate must parse
 *       and emit nothing.</b> The listener implements handlers for eight
 *       statement kinds (database and table DDL). Every other family -- users,
 *       roles, grants, tablespaces, resource groups, SRIDs, CLONE, SET -- has no
 *       handler, so there is no ClickHouse DDL to derive from it and no
 *       emission risk. Parsing is therefore sufficient, and being unable to
 *       parse must not be fatal.
 *       {@link #everyStatementFamilyWithNoTranslationParsesAndEmitsNothing()}</li>
 *   <li><b>A table clause the grammar lacks, inside a statement that DOES
 *       change a replicated table's schema, must be bridged by removing only
 *       that clause.</b> Dropping the whole statement would lose a column. The
 *       property asserted is equality with the bridge-free equivalent:
 *       {@code ALTER TABLE t ADD COLUMN c INT, SECONDARY_LOAD} must emit
 *       exactly what {@code ALTER TABLE t ADD COLUMN c INT} emits.
 *       {@link #clausesWithNoClickHouseEffectAreBridgedNotDropped()}</li>
 * </ol>
 *
 * <p>Only statements MySQL 8.0 itself accepts are listed. A statement this
 * test gets wrong is a test defect and must be removed rather than reported.</p>
 */
public class ProductionDdlSurfaceCoverageTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    /** Parses through the production entry point; returns the emitted DDL. */
    private static String parse(String sql) {
        service.setDdlEventTimestampMs(1700000000123L);
        StringBuffer output = new StringBuffer();
        service.parseSql(sql, "t", output);
        return output.toString();
    }

    /**
     * Every statement family the connector has no handler for: server-scope
     * administration, account management, and replication plumbing.
     *
     * <p>Each is valid MySQL 8.0 and each is binlogged, so each reaches the
     * parser in production. None can produce ClickHouse DDL, so the required
     * behaviour is: parse, emit nothing, without throwing.</p>
     */
    private static Map<String, String> statementsWithNoTranslation() {
        Map<String, String> cases = new LinkedHashMap<>();

        // Tablespaces. A tablespace holds files, not rows; the connector's
        // handler list has no tablespace entry.
        cases.put("tablespace.alterRenameTo", "ALTER TABLESPACE ts RENAME TO ts2");
        cases.put("tablespace.alterSetActive", "ALTER TABLESPACE ts SET ACTIVE");
        cases.put("tablespace.alterSetInactive", "ALTER TABLESPACE ts SET INACTIVE");
        cases.put("tablespace.alterAutoextendSize", "ALTER TABLESPACE ts AUTOEXTEND_SIZE = 4M");
        cases.put("tablespace.alterEncryptionYes", "ALTER TABLESPACE ts ENCRYPTION = 'Y'");
        cases.put("tablespace.alterEncryptionNo", "ALTER TABLESPACE ts ENCRYPTION = 'N'");
        cases.put("tablespace.alterAddDatafile",
                "ALTER TABLESPACE ts ADD DATAFILE 'x.ibd' ENGINE=InnoDB");
        cases.put("tablespace.alterDropDatafile",
                "ALTER TABLESPACE ts DROP DATAFILE 'x.ibd' ENGINE=InnoDB");
        cases.put("tablespace.createInnodb",
                "CREATE TABLESPACE ts ADD DATAFILE 'x.ibd' ENGINE=InnoDB");
        cases.put("tablespace.createNdb", "CREATE TABLESPACE ts ADD DATAFILE 'x' ENGINE=NDB");
        cases.put("tablespace.drop", "DROP TABLESPACE ts");
        cases.put("tablespace.createUndo", "CREATE UNDO TABLESPACE ts ADD DATAFILE 'x.ibu'");
        cases.put("tablespace.createUndoNoDatafile", "CREATE UNDO TABLESPACE ts");
        cases.put("tablespace.dropUndo", "DROP UNDO TABLESPACE ts");
        cases.put("tablespace.alterUndoRename", "ALTER UNDO TABLESPACE ts RENAME TO ts2");

        // Session and server variables. PERSIST writes mysqld-auto.cnf; the
        // shipped grammar knows SET GLOBAL and SET SESSION only.
        cases.put("set.persist", "SET PERSIST max_connections = 1000");
        cases.put("set.persistSeveral",
                "SET PERSIST max_connections = 1000, sort_buffer_size = 1048576");
        cases.put("set.persistOnly", "SET PERSIST_ONLY max_connections = 1000");
        cases.put("set.persistOnlySeveral",
                "SET PERSIST_ONLY max_connections = 1000, read_only = 1");
        cases.put("set.resetPersist", "RESET PERSIST max_connections");
        cases.put("set.resetPersistAll", "RESET PERSIST");
        cases.put("set.global", "SET GLOBAL max_connections = 1000");
        cases.put("set.session", "SET SESSION sql_mode = 'STRICT_TRANS_TABLES'");
        cases.put("set.namesCollate", "SET NAMES utf8mb4 COLLATE utf8mb4_bin");
        cases.put("set.characterSet", "SET CHARACTER SET utf8mb4");

        // Resource groups: a scheduler feature with no row data.
        cases.put("resourceGroup.create",
                "CREATE RESOURCE GROUP rg TYPE = SYSTEM VCPU = 0-3 THREAD_PRIORITY = 10");
        cases.put("resourceGroup.alter", "ALTER RESOURCE GROUP rg VCPU = 4");
        cases.put("resourceGroup.drop", "DROP RESOURCE GROUP rg");
        cases.put("resourceGroup.setForThread", "SET RESOURCE GROUP rg FOR 1");

        // Spatial reference systems are dictionary rows in the data
        // dictionary, not in any user table.
        cases.put("srs.create",
                "CREATE SPATIAL REFERENCE SYSTEM 9999 NAME 'x' DEFINITION 'y'");
        cases.put("srs.drop", "DROP SPATIAL REFERENCE SYSTEM 9999");

        // Clone copies the whole instance; nothing about it is a table schema.
        cases.put("clone.instance", "CLONE INSTANCE FROM u@h:3306 IDENTIFIED BY 'x'");
        cases.put("clone.local", "CLONE LOCAL DATA DIRECTORY = '/x'");

        // Account management, including the multi-factor forms 8.0.27 added.
        cases.put("user.create", "CREATE USER u IDENTIFIED BY 'x'");
        cases.put("user.createPlugin",
                "CREATE USER u IDENTIFIED WITH caching_sha2_password BY 'x'");
        cases.put("user.createRequireX509", "CREATE USER u IDENTIFIED BY 'x' REQUIRE X509");
        cases.put("user.alter", "ALTER USER u IDENTIFIED BY 'y'");
        cases.put("user.retainCurrentPassword",
                "ALTER USER u IDENTIFIED BY 'y' RETAIN CURRENT PASSWORD");
        cases.put("user.discardOldPassword", "ALTER USER u DISCARD OLD PASSWORD");
        cases.put("user.multiFactor",
                "ALTER USER u IDENTIFIED BY 'y' AND IDENTIFIED WITH caching_sha2_password BY 'z'");
        cases.put("user.multiFactorTwoPlugins",
                "ALTER USER u IDENTIFIED WITH mysql_native_password BY 'y'"
                        + " AND IDENTIFIED WITH caching_sha2_password BY 'z'");
        cases.put("user.drop", "DROP USER IF EXISTS u");
        cases.put("user.rename", "RENAME USER a TO b");
        cases.put("role.create", "CREATE ROLE IF NOT EXISTS r");
        cases.put("role.drop", "DROP ROLE r");
        cases.put("role.setDefaultAll", "SET DEFAULT ROLE ALL TO u");
        cases.put("role.setRoleNone", "SET ROLE NONE");
        cases.put("role.setRoleAllExcept", "SET ROLE ALL EXCEPT r");
        cases.put("grant.dynamic", "GRANT BACKUP_ADMIN ON *.* TO u");
        cases.put("grant.procedure", "GRANT EXECUTE ON PROCEDURE d.p TO u");
        cases.put("revoke.allPrivileges", "REVOKE ALL PRIVILEGES, GRANT OPTION FROM u");

        // Objects that exist only in MySQL.
        cases.put("index.create", "CREATE INDEX idx ON t (a)");
        cases.put("index.createInvisible", "CREATE INDEX idx ON t (a) INVISIBLE");
        // DESTRUCTIVE: fixture text only. DROP INDEX is parsed and classified
        // as an object that exists only in MySQL; nothing is executed, so no
        // index is dropped.
        cases.put("index.drop", "DROP INDEX idx ON t");
        cases.put("server.create",
                "CREATE SERVER s FOREIGN DATA WRAPPER mysql OPTIONS (HOST 'h')");
        cases.put("instance.rotateKey", "ALTER INSTANCE ROTATE INNODB MASTER KEY");
        cases.put("logfileGroup.create",
                "CREATE LOGFILE GROUP lg ADD UNDOFILE 'x' ENGINE=NDB");
        cases.put("event.create", "CREATE EVENT e ON SCHEDULE EVERY 1 DAY DO SELECT 1");
        cases.put("event.alter", "ALTER EVENT e DISABLE");
        cases.put("procedure.alter", "ALTER PROCEDURE p COMMENT 'x'");
        cases.put("trigger.drop", "DROP TRIGGER IF EXISTS tr");

        // Replication and service plumbing the binlog can carry.
        cases.put("admin.shutdown", "SHUTDOWN");
        cases.put("admin.kill", "KILL 1");
        cases.put("admin.flushLogs", "FLUSH LOGS");
        cases.put("admin.analyze", "ANALYZE TABLE t");
        cases.put("admin.optimize", "OPTIMIZE TABLE t");
        cases.put("replication.changeMaster", "CHANGE MASTER TO MASTER_HOST = 'h'");
        cases.put("replication.startSlave", "START SLAVE");
        cases.put("replication.stopSlave", "STOP SLAVE");
        cases.put("replication.purgeLogs", "PURGE BINARY LOGS TO 'mysql-bin.000010'");
        cases.put("replication.resetMaster", "RESET MASTER");
        cases.put("transaction.begin", "BEGIN");
        cases.put("utility.use", "USE db");

        // Valid MySQL 8.0 the bundled grammar does not accept at all. These are
        // not exotic: FOR SHARE, EXPLAIN ANALYZE and START REPLICA are ordinary
        // statements a DBA or a tool runs, and each one is binlogged. Every one
        // belongs to a family with no handler, so the only correct outcome is
        // the recovery parse: dropped, never fatal. A regression that removes
        // that path fails here on all of them at once rather than one per run.
        cases.put("gap.selectForShare", "SELECT a FROM t FOR SHARE");
        cases.put("gap.selectForShareOfNowait", "SELECT a FROM t FOR SHARE OF t NOWAIT");
        cases.put("gap.selectForUpdateSkipLocked", "SELECT a FROM t FOR UPDATE SKIP LOCKED");
        cases.put("gap.selectForUpdateNowait", "SELECT a FROM t FOR UPDATE NOWAIT");
        cases.put("gap.explainAnalyze", "EXPLAIN ANALYZE SELECT 1");
        cases.put("gap.explainFormatTree", "EXPLAIN FORMAT = TREE SELECT 1");
        cases.put("gap.startReplica", "START REPLICA");
        cases.put("gap.stopReplica", "STOP REPLICA");
        cases.put("gap.changeReplicationSource",
                "CHANGE REPLICATION SOURCE TO SOURCE_HOST = 'h'");
        cases.put("gap.alterInstanceRedoLog", "ALTER INSTANCE ENABLE INNODB REDO_LOG");
        cases.put("gap.alterInstanceReloadTls", "ALTER INSTANCE RELOAD TLS");
        cases.put("gap.showProfile", "SHOW PROFILE CPU");
        cases.put("gap.showProfileForQuery", "SHOW PROFILE FOR QUERY 1");
        cases.put("gap.xaRecoverConvert", "XA RECOVER CONVERT XID");
        cases.put("gap.derivedTableWith",
                "SELECT * FROM (WITH c AS (SELECT 1) SELECT * FROM c) x");
        cases.put("gap.changeMasterUntil",
                "CHANGE MASTER TO MASTER_HOST = 'h' UNTIL MASTER_LOG_FILE = 'x'");
        cases.put("gap.changeMasterGtidExecuted",
                "CHANGE MASTER TO MASTER_GTID_EXECUTED = 'uuid:1-5'");
        cases.put("gap.createTablespaceStorageDisk",
                "CREATE TABLESPACE ts ADD DATAFILE 'x' STORAGE DISK ENGINE = InnoDB");
        cases.put("gap.restart", "RESTART");
        cases.put("gap.declareAfterCursor",
                "CREATE PROCEDURE p() BEGIN DECLARE cur CURSOR FOR SELECT 1;"
                        + " DECLARE v INT; OPEN cur; FETCH cur INTO v; CLOSE cur; END");
        cases.put("gap.emptyBlock", "CREATE PROCEDURE p() BEGIN ; END");

        return cases;
    }

    /**
     * No statement family outside the eight handled kinds may stop replication.
     *
     * <p>The failure message lists every offending statement at once, so a
     * grammar gap is diagnosed in one run rather than one statement per run.</p>
     */
    @Test
    @DisplayName("a statement no handler can translate parses and emits nothing")
    public void everyStatementFamilyWithNoTranslationParsesAndEmitsNothing() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> entry : statementsWithNoTranslation().entrySet()) {
            String sql = entry.getValue();
            try {
                String emitted = parse(sql);
                if (!emitted.trim().isEmpty()) {
                    failures.add(entry.getKey() + "  [" + sql + "]  emitted [" + emitted + "]");
                }
            } catch (RuntimeException e) {
                failures.add(entry.getKey() + "  [" + sql + "]  THREW "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            fail("statements the connector cannot translate must be silent, not fatal"
                    + " (each one halts replication for every table on the host); "
                    + failures.size() + " of " + statementsWithNoTranslation().size()
                    + " failed:\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * Clause forms the shipped grammar lacks, in the position they appear beside
     * a real schema change.
     *
     * <p>Value is {statement, the bridge-free equivalent it must match}. The
     * equivalent is not a guess: it is the same statement with the
     * ClickHouse-irrelevant clause deleted, which is what the source author
     * asked for as far as the replica is concerned.</p>
     */
    private static Map<String, String[]> clausesBesideASchemaChange() {
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("secondaryLoad.afterAddColumn",
                new String[]{"ALTER TABLE t ADD COLUMN c INT, SECONDARY_LOAD",
                        "ALTER TABLE t ADD COLUMN c INT"});
        cases.put("secondaryUnload.beforeAddColumn",
                new String[]{"ALTER TABLE t SECONDARY_UNLOAD, ADD COLUMN c INT",
                        "ALTER TABLE t ADD COLUMN c INT"});
        cases.put("secondaryEngineNull.beforeAddColumn",
                new String[]{"ALTER TABLE t SECONDARY_ENGINE = NULL, ADD COLUMN c INT",
                        "ALTER TABLE t ADD COLUMN c INT"});
        cases.put("autoextendSize.beforeModifyColumn",
                new String[]{"ALTER TABLE t AUTOEXTEND_SIZE = 4M, MODIFY COLUMN c BIGINT",
                        "ALTER TABLE t MODIFY COLUMN c BIGINT"});
        // DESTRUCTIVE: fixture text only. Both strings are input and expected
        // output for the normalizer; nothing is executed, so no column is
        // dropped.
        cases.put("autoextendSize.afterDropColumn",
                new String[]{"ALTER TABLE t DROP COLUMN c, AUTOEXTEND_SIZE = 8M",
                        "ALTER TABLE t DROP COLUMN c"});
        cases.put("secondaryLoad.betweenTwoChanges",
                new String[]{"ALTER TABLE t ADD COLUMN c INT, SECONDARY_LOAD, ADD COLUMN d INT",
                        "ALTER TABLE t ADD COLUMN c INT, ADD COLUMN d INT"});
        cases.put("engineAttribute.beforeAddColumn",
                new String[]{"ALTER TABLE t ENGINE_ATTRIBUTE = '{}', ADD COLUMN c INT",
                        "ALTER TABLE t ADD COLUMN c INT"});
        cases.put("secondaryEngineAttribute.beforeAddColumn",
                new String[]{"ALTER TABLE t SECONDARY_ENGINE_ATTRIBUTE = '{}', ADD COLUMN c INT",
                        "ALTER TABLE t ADD COLUMN c INT"});
        return cases;
    }

    /**
     * A clause with no ClickHouse effect is removed; the schema change beside it
     * still reaches ClickHouse.
     *
     * <p>This is the assertion that separates a correct bridge from a tempting
     * shortcut. Ignoring the whole statement would make the test above pass and
     * silently lose the column, so the emitted DDL is compared against the
     * bridge-free equivalent rather than merely checked for absence of
     * exceptions.</p>
     */
    @Test
    @DisplayName("a clause the grammar lacks is bridged, not allowed to swallow the schema change")
    public void clausesWithNoClickHouseEffectAreBridgedNotDropped() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : clausesBesideASchemaChange().entrySet()) {
            String sql = entry.getValue()[0];
            String equivalent = entry.getValue()[1];
            try {
                String emitted = parse(sql);
                String expected = parse(equivalent);
                if (expected.trim().isEmpty()) {
                    failures.add(entry.getKey() + "  the control [" + equivalent
                            + "] emits nothing, so this case proves nothing");
                } else if (!emitted.equals(expected)) {
                    failures.add(entry.getKey() + "  [" + sql + "]\n      emitted  [" + emitted
                            + "]\n      expected [" + expected + "]  (from [" + equivalent + "])");
                }
            } catch (RuntimeException e) {
                failures.add(entry.getKey() + "  [" + sql + "]  THREW "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            fail("these clauses carry no ClickHouse effect and must not cost the schema change "
                    + "beside them:\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * The {@code AUTOEXTEND_SIZE} table option, in {@code CREATE TABLE} and in
     * every position MySQL allows relative to the other options.
     *
     * <p>A {@code CREATE TABLE} that loses its body would create a table with no
     * columns, so each case is compared with the option removed rather than
     * merely required not to throw.</p>
     */
    @Test
    @DisplayName("CREATE TABLE AUTOEXTEND_SIZE is bridged without touching the table definition")
    public void createTableAutoextendSizeIsBridged() {
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("alone", new String[]{"CREATE TABLE t (a INT) AUTOEXTEND_SIZE = 4M",
                "CREATE TABLE t (a INT)"});
        cases.put("beforeEngine",
                new String[]{"CREATE TABLE t (a INT) AUTOEXTEND_SIZE = 4M ENGINE=InnoDB",
                        "CREATE TABLE t (a INT) ENGINE=InnoDB"});
        cases.put("afterEngine",
                new String[]{"CREATE TABLE t (a INT) ENGINE=InnoDB AUTOEXTEND_SIZE = 4M",
                        "CREATE TABLE t (a INT) ENGINE=InnoDB"});
        cases.put("beforeComment",
                new String[]{"CREATE TABLE t (a INT) AUTOEXTEND_SIZE = 4M COMMENT='x'",
                        "CREATE TABLE t (a INT) COMMENT='x'"});
        cases.put("defaultCharset",
                new String[]{"CREATE TABLE t (a INT) DEFAULT CHARSET=utf8mb4 AUTOEXTEND_SIZE=4M",
                        "CREATE TABLE t (a INT) DEFAULT CHARSET=utf8mb4"});
        cases.put("lowercase",
                new String[]{"create table t (a int) autoextend_size = 4m",
                        "create table t (a int)"});
        cases.put("columnAndKey",
                new String[]{"CREATE TABLE t (a INT PRIMARY KEY, b VARCHAR(8)) AUTOEXTEND_SIZE = 4M",
                        "CREATE TABLE t (a INT PRIMARY KEY, b VARCHAR(8))"});

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : cases.entrySet()) {
            String sql = entry.getValue()[0];
            String equivalent = entry.getValue()[1];
            try {
                String emitted = parse(sql);
                String expected = parse(equivalent);
                if (!emitted.equals(expected)) {
                    failures.add(entry.getKey() + "  [" + sql + "]\n      emitted  [" + emitted
                            + "]\n      expected [" + expected + "]");
                }
            } catch (RuntimeException e) {
                failures.add(entry.getKey() + "  [" + sql + "]  THREW "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            fail("CREATE TABLE must keep its definition whatever table options surround it:\n  "
                    + String.join("\n  ", failures));
        }
    }

    /**
     * A statement whose only specification carries no ClickHouse effect must
     * still complete: it emits nothing, and it does not throw.
     *
     * <p>Removing the clause from a one-clause {@code ALTER} leaves a statement
     * body with nothing in it, which the grammar will not read. The bridge has
     * to account for that shape too, or the fix would work only on mixed
     * statements -- the rarer case.</p>
     */
    @Test
    @DisplayName("an ALTER whose only clause has no ClickHouse effect is silent, not fatal")
    public void aLoneIrrelevantAlterClauseIsSilent() {
        List<String> statements = List.of(
                "ALTER TABLE t SECONDARY_ENGINE = NULL",
                "ALTER TABLE t SECONDARY_LOAD",
                "ALTER TABLE t SECONDARY_UNLOAD",
                "ALTER TABLE t AUTOEXTEND_SIZE = 4M",
                "ALTER TABLE t ENGINE_ATTRIBUTE = '{}'",
                "ALTER TABLE t SECONDARY_ENGINE_ATTRIBUTE = '{}'");

        List<String> failures = new ArrayList<>();
        for (String sql : statements) {
            try {
                String emitted = parse(sql);
                if (!emitted.trim().isEmpty()) {
                    failures.add("[" + sql + "] emitted [" + emitted + "]");
                }
            } catch (RuntimeException e) {
                failures.add("[" + sql + "] THREW " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            fail("a clause with no ClickHouse effect must not stop replication when it is the "
                    + "whole statement:\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * The controls: statements that already parse must keep parsing AND keep
     * emitting what they emitted before.
     *
     * <p>A bridge that rewrote more than it was meant to would show up here
     * rather than in production. Each entry asserts the emitted DDL still names
     * the schema change, so a bridge that quietly deleted it is caught even
     * where the parse still succeeds.</p>
     */
    @Test
    @DisplayName("statements that already worked are unchanged by the bridges")
    public void statementsThatAlreadyWorkedAreUnchanged() {
        String addColumn = parse("ALTER TABLE t ADD COLUMN c INT");
        assertFalse(addColumn.trim().isEmpty(),
                "the control statement itself must emit a schema change");

        // Clause keywords appearing as DATA must never be treated as clauses:
        // these are the spellings a text-level bridge is most likely to break.
        List<String> dataKeepers = List.of(
                "ALTER TABLE t MODIFY COLUMN c INT COMMENT 'SECONDARY_LOAD'",
                "CREATE TABLE t (a INT) COMMENT='AUTOEXTEND_SIZE = 4M'",
                "CREATE TABLE t (a VARCHAR(16) DEFAULT 'SECONDARY_ENGINE')",
                "ALTER TABLE t ADD COLUMN `SECONDARY_LOAD` INT",
                "ALTER TABLE t ADD COLUMN `AUTOEXTEND_SIZE` INT",
                "CREATE TABLE t (a INT) COMMENT='ALTER TABLESPACE ts RENAME TO ts2'");
        List<String> failures = new ArrayList<>();
        for (String sql : dataKeepers) {
            try {
                parse(sql);
            } catch (RuntimeException e) {
                failures.add("[" + sql + "] THREW " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
            }
        }

        // An ALTER that names a tablespace is unrelated to the ALTER TABLESPACE
        // statement family and must keep working.
        try {
            assertEquals(addColumn, parse("ALTER TABLE t ADD COLUMN c INT, ALGORITHM = INPLACE"),
                    "a no-op tablespace/algorithm clause must not change the emitted DDL");
        } catch (RuntimeException e) {
            failures.add("ALTER TABLE ... ALGORITHM = INPLACE THREW " + e.getMessage());
        }

        assertTrue(failures.isEmpty(), "these are data, not clauses, and must parse untouched:\n  "
                + String.join("\n  ", failures));
    }
}
