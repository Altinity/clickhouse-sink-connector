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
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grammar-derived coverage of the STATEMENT surface.
 *
 * <p>A DDL statement reaches the connector as binlog SQL text. The parser
 * accepts the whole MySQL language, but the listener only implements a
 * handler for the statement kinds it translates, and {@code parseSql} returns
 * without complaining when no handler matched. An unhandled but perfectly
 * parseable statement therefore produces nothing at all -- no error, no log.
 * That is the dangerous class: the table exists in MySQL, ClickHouse never
 * learns about it, and nothing anywhere says why.</p>
 *
 * <p>The statement kinds are not guessable, so they are read from the parser
 * the connector ships: {@code ddlStatement} enumerates every DDL alternative
 * the grammar defines. This test then requires each one to be accounted for --
 * either a handler exists, or it is named in {@link #INTENTIONALLY_UNHANDLED}
 * with the reason ClickHouse needs no translation for it.</p>
 *
 * <p>A parser upgrade that adds a statement kind fails here until someone
 * decides which bucket it belongs in. That decision is the whole point: the
 * failure is a prompt to think, not a bug in the test.</p>
 */
public class StatementSurfaceCoverageTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    /**
     * DDL alternatives the connector translates, keyed by the lower-case name
     * of the {@code enter*} handler in {@link MySqlDDLParserListenerImpl}.
     *
     * <p>{@code createTable} is handled through its three labelled forms, which
     * the grammar raises as {@code ColumnCreateTable}, {@code CopyCreateTable}
     * and {@code QueryCreateTable}; the first two emit a table, the third
     * deliberately does not ({@code DbWriter.initializeTableEngine} creates the
     * ClickHouse table from the first record instead).</p>
     */
    private static final Set<String> HANDLED = new TreeSet<>(List.of(
            "alterTable",
            "createDatabase",
            "createTable",           // via columnCreateTable / copyCreateTable
            "dropDatabase",
            "dropTable",
            "renameTable",
            "truncateTable"
    ));

    /**
     * DDL the connector does not translate, with the reason ClickHouse needs
     * nothing from it.
     *
     * <p>Every entry here is a deliberate exclusion, not a gap: an object that
     * exists only in MySQL (a user, a role, a stored procedure, a trigger, a
     * bare index) has no row data to replicate, so there is nothing to create
     * on the ClickHouse side until the object's TABLE is itself replicated --
     * and then the table handler is what runs.</p>
     */
    private static final Set<String> INTENTIONALLY_UNHANDLED = new TreeSet<>(List.of(
            // Objects with no ClickHouse counterpart that carries row data.
            "alterDatabase",          // default charset/collation only
            "alterEvent",
            "alterFunction",
            "alterInstance",
            "alterLogfileGroup",
            "alterProcedure",
            "alterServer",
            "alterTablespace",
            "alterView",
            "createEvent",
            "createFunction",
            "createIndex",            // indexes are not modelled in ClickHouse
            "createLogfileGroup",
            "createProcedure",
            "createRole",
            "createServer",
            "createTablespaceInnodb",
            "createTablespaceNdb",
            "createTrigger",
            "createView",
            "dropEvent",
            "dropFunction",
            "dropIndex",
            "dropLogfileGroup",
            "dropProcedure",
            "dropRole",
            "dropServer",
            "dropTablespace",
            "dropTrigger",
            "dropView",
            "setRole"
    ));

    /**
     * The DDL alternatives of the shipped parser, derived by parsing rather
     * than by listing.
     *
     * <p>{@code ddlStatement} is an unlabelled rule, so its alternatives are
     * named sub-rules instead of {@code Context} subclasses. Reflection over
     * the supers of {@code DdlStatementContext} would therefore find nothing;
     * the accessor methods on the context are the alternative list.</p>
     */
    private static Set<String> shippedDdlAlternatives() {
        Set<String> names = new TreeSet<>();
        for (Method method : MySqlParser.DdlStatementContext.class.getDeclaredMethods()) {
            // The alternatives are the rule accessors. The inherited
            // getRuleContext()/getParent() also return a *Context type and
            // must not be mistaken for alternatives.
            if (method.getParameterCount() == 0
                    && !method.getName().startsWith("get")
                    && method.getReturnType().getSimpleName().endsWith("Context")) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * The statement kinds the listener actually implements a handler for.
     *
     * <p>Returns grammar-alternative names: {@code enterAlterTable} becomes
     * {@code alterTable}, matching how the accessors on
     * {@code DdlStatementContext} are named. Without the lower-casing the two
     * spellings differ by one capital and every lookup misses.</p>
     */
    private static Set<String> handledByListener() {
        Set<String> handlers = new TreeSet<>();
        for (Method method : MySqlDDLParserListenerImpl.class.getDeclaredMethods()) {
            if (method.getName().startsWith("enter") && method.getParameterCount() == 1) {
                String name = method.getName().substring("enter".length());
                handlers.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
            }
        }
        return handlers;
    }

    @Test
    public void everyDdlAlternativeIsEitherHandledOrDocumented() {
        Set<String> shipped = shippedDdlAlternatives();
        assertFalse(shipped.isEmpty(), "reflection found no ddlStatement alternatives");

        Set<String> accountedFor = new TreeSet<>(HANDLED);
        accountedFor.addAll(INTENTIONALLY_UNHANDLED);

        // Shipped but in neither bucket: a statement kind nobody decided about.
        Set<String> undecided = new TreeSet<>(shipped);
        undecided.removeAll(accountedFor);

        Set<String> stale = new TreeSet<>(accountedFor);
        stale.removeAll(shipped);

        assertTrue(undecided.isEmpty(), "statement kinds with no decision (add to HANDLED or "
                + "INTENTIONALLY_UNHANDLED):\n  " + String.join("\n  ", undecided));
        assertTrue(stale.isEmpty(), "these are listed but the shipped grammar no longer defines them:\n  "
                + String.join("\n  ", stale));
    }

    /**
     * Every statement claimed as handled really is: the listener declares a
     * handler for it, and it parses.
     *
     * <p>A name in {@link #HANDLED} with no handler behind it would be a
     * documented lie -- exactly the silent no-op this test exists to prevent.</p>
     */
    @Test
    public void everyHandledStatementHasAHandler() {
        Set<String> handlers = handledByListener();
        assertFalse(handlers.isEmpty(), "reflection found no enter* handlers on the listener");

        List<String> missing = new ArrayList<>();
        for (String statement : HANDLED) {
            // createTable arrives as one of its three labelled forms rather
            // than as "CreateTable". Two of the three need a handler: the
            // column form and the LIKE form both emit a table. The query form
            // (CTAS) deliberately has none -- MySQL logs it as the CREATE plus
            // the row events the SELECT inserted, and the ClickHouse table is
            // created from the first record's schema instead.
            boolean covered = handlers.contains(statement)
                    || (statement.equals("createTable")
                        && handlers.containsAll(List.of("columnCreateTable", "copyCreateTable")));
            if (!covered) {
                missing.add(statement);
            }
        }
        assertTrue(missing.isEmpty(), "listed as handled but the listener has no handler:\n  "
                + String.join("\n  ", missing));
    }

    /**
     * A statement the connector does not translate parses cleanly and emits
     * nothing -- it does not throw.
     *
     * <p>{@code ErrorListenerImpl.syntaxError} raises
     * {@code RuntimeException("Error parsing DDL")}, and that stops the
     * replication stream. An unhandled statement is dropped; it must not also
     * be fatal, or one CREATE TRIGGER on the source halts every later change
     * to every table on the host.</p>
     */
    @Test
    public void unhandledStatementsParseWithoutThrowing() {
        List<String> statements = List.of(
                "CREATE INDEX ix ON t (id)",
                "CREATE VIEW v AS SELECT 1",
                "CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW SET @x = 1",
                "CREATE PROCEDURE p() BEGIN SELECT 1; END",
                "CREATE USER u IDENTIFIED BY 'x'",
                "CREATE ROLE r",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no index or view is
                // dropped.
                "DROP INDEX ix ON t",
                "DROP VIEW v",
                "DROP USER u",
                "SET ROLE r"
        );

        List<String> failures = new ArrayList<>();
        for (String sql : statements) {
            try {
                service.setDdlEventTimestampMs(1700000000123L);
                StringBuffer output = new StringBuffer();
                service.parseSql(sql, "t", output);
                if (!output.toString().trim().isEmpty()) {
                    failures.add(sql + " -> emitted [" + output + "] instead of nothing");
                }
            } catch (RuntimeException e) {
                failures.add(sql + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), "unhandled statements must be silent, not fatal:\n  "
                + String.join("\n  ", failures));
    }

    /**
     * Every alternative of the five non-DDL, non-DML statement groups parses
     * silently.
     *
     * <p>One valid MySQL 8 statement per alternative of the five statement
     * groups that are neither DDL nor DML, keyed by the alternative's accessor
     * name on the group context. {@code sqlStatement} has seven alternatives:
     * {@code ddlStatement} is covered here, {@code dmlStatement} in {@link
     * DmlStatementSurfaceCoverageTest}, and the other five -- transaction,
     * replication, prepared, administration, utility -- are probed below.
     * Without them "we cover the statement surface" would be true of a quarter
     * of it.</p>
     *
     * <p>Measured, not guessed: the shipped parser declares 25 {@code
     * administrationStatement} alternatives, 15 {@code replicationStatement},
     * 9 {@code transactionStatement}, 7 {@code utilityStatement} and 3 {@code
     * preparedStatement} -- 59 in all. The earlier version of this test probed
     * five, one per group, which is why the sweep below is keyed on the
     * alternative rather than on the group: a group-level probe says the group
     * is reachable, never that each of its statements survives.</p>
     *
     * <p>Each statement is real MySQL syntax, and each was run through the
     * shipped parser and the production translation path: all 59 parse and
     * emit nothing. Emitting nothing is correct -- none of these statements
     * carries row data, so ClickHouse needs nothing from them. Parsing is the
     * requirement, because {@code ErrorListenerImpl.syntaxError} throws and one
     * unparseable statement halts replication for every table on the host.</p>
     *
     * <p>The administration rows are fixture TEXT only: the statement is
     * classified by the parser and never executed, so no user, grant, table or
     * server state is touched.</p>
     */
    private static Map<String, String> otherGroupProbes() {
        Map<String, String> probes = new LinkedHashMap<>();

        // transactionStatement (9)
        probes.put("startTransaction", "START TRANSACTION");
        probes.put("beginWork", "BEGIN");
        probes.put("commitWork", "COMMIT");
        probes.put("rollbackWork", "ROLLBACK");
        probes.put("savepointStatement", "SAVEPOINT sp1");
        probes.put("rollbackStatement", "ROLLBACK TO SAVEPOINT sp1");
        probes.put("releaseStatement", "RELEASE SAVEPOINT sp1");
        probes.put("lockTables", "LOCK TABLES t WRITE");
        probes.put("unlockTables", "UNLOCK TABLES");

        // replicationStatement (15)
        probes.put("changeMaster", "CHANGE MASTER TO MASTER_HOST = 'h'");
        probes.put("changeReplicationFilter",
                "CHANGE REPLICATION FILTER REPLICATE_DO_DB = (db)");
        probes.put("purgeBinaryLogs", "PURGE BINARY LOGS TO 'binlog.000001'");
        probes.put("resetMaster", "RESET MASTER");
        probes.put("resetSlave", "RESET SLAVE");
        probes.put("startSlave", "START SLAVE");
        probes.put("stopSlave", "STOP SLAVE");
        probes.put("startGroupReplication", "START GROUP_REPLICATION");
        probes.put("stopGroupReplication", "STOP GROUP_REPLICATION");
        probes.put("xaStartTransaction", "XA START 'x1'");
        probes.put("xaEndTransaction", "XA END 'x1'");
        probes.put("xaPrepareStatement", "XA PREPARE 'x1'");
        probes.put("xaCommitWork", "XA COMMIT 'x1'");
        probes.put("xaRollbackWork", "XA ROLLBACK 'x1'");
        probes.put("xaRecoverWork", "XA RECOVER");

        // preparedStatement (3)
        probes.put("prepareStatement", "PREPARE stmt1 FROM 'SELECT 1'");
        probes.put("executeStatement", "EXECUTE stmt1");
        probes.put("deallocatePrepare", "DEALLOCATE PREPARE stmt1");

        // administrationStatement (25)
        probes.put("alterUser", "ALTER USER 'u'@'%' ACCOUNT LOCK");
        probes.put("createUser", "CREATE USER 'u'@'%'");
        probes.put("dropUser", "DROP USER 'u'@'%'");
        probes.put("grantStatement", "GRANT SELECT ON db.* TO 'u'@'%'");
        probes.put("grantProxy", "GRANT PROXY ON 'u'@'%' TO 'v'@'%'");
        probes.put("renameUser", "RENAME USER 'u'@'%' TO 'w'@'%'");
        probes.put("revokeStatement", "REVOKE SELECT ON db.* FROM 'u'@'%'");
        probes.put("revokeProxy", "REVOKE PROXY ON 'u'@'%' FROM 'v'@'%'");
        probes.put("analyzeTable", "ANALYZE TABLE t");
        probes.put("checkTable", "CHECK TABLE t");
        probes.put("checksumTable", "CHECKSUM TABLE t");
        probes.put("optimizeTable", "OPTIMIZE TABLE t");
        probes.put("repairTable", "REPAIR TABLE t");
        probes.put("createUdfunction", "CREATE FUNCTION f RETURNS STRING SONAME 'f.so'");
        probes.put("installPlugin", "INSTALL PLUGIN p SONAME 'p.so'");
        probes.put("uninstallPlugin", "UNINSTALL PLUGIN p");
        probes.put("setStatement", "SET @x = 1");
        probes.put("showStatement", "SHOW TABLES");
        probes.put("binlogStatement", "BINLOG 'x'");
        probes.put("cacheIndexStatement", "CACHE INDEX t IN k");
        probes.put("flushStatement", "FLUSH TABLES");
        probes.put("killStatement", "KILL 1");
        probes.put("loadIndexIntoCache", "LOAD INDEX INTO CACHE t");
        probes.put("resetStatement", "RESET QUERY CACHE");
        probes.put("shutdownStatement", "SHUTDOWN");

        // utilityStatement (7)
        probes.put("simpleDescribeStatement", "DESCRIBE t");
        probes.put("fullDescribeStatement", "EXPLAIN SELECT 1");
        probes.put("helpStatement", "HELP 'SELECT'");
        probes.put("useStatement", "USE db");
        probes.put("signalStatement", "SIGNAL SQLSTATE '45000'");
        probes.put("resignalStatement", "RESIGNAL");
        probes.put("diagnosticsStatement", "GET DIAGNOSTICS @c = NUMBER");
        return probes;
    }

    /** The accessor names a statement-group context exposes, i.e. its alternatives. */
    private static Set<String> alternativesOf(Class<?> groupContext) {
        Set<String> names = new TreeSet<>();
        for (Method method : groupContext.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && !method.getName().startsWith("get")
                    && method.getReturnType().getSimpleName().endsWith("Context")) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * Every alternative of the five non-DDL/DML statement groups is probed, and
     * no probe names an alternative the shipped parser does not define.
     *
     * <p>The groups are enumerated from {@code SqlStatementContext} itself, so a
     * parser upgrade that adds a whole group fails here rather than going
     * silently unprobed.</p>
     */
    @Test
    public void everyOtherStatementGroupParsesSilently() {
        Map<String, Class<?>> groups = new LinkedHashMap<>();
        groups.put("transactionStatement", MySqlParser.TransactionStatementContext.class);
        groups.put("replicationStatement", MySqlParser.ReplicationStatementContext.class);
        groups.put("preparedStatement", MySqlParser.PreparedStatementContext.class);
        groups.put("administrationStatement", MySqlParser.AdministrationStatementContext.class);
        groups.put("utilityStatement", MySqlParser.UtilityStatementContext.class);

        Set<String> shippedGroups = new TreeSet<>();
        for (Method method : MySqlParser.SqlStatementContext.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && !method.getName().startsWith("get")
                    && method.getReturnType().getSimpleName().endsWith("Context")) {
                shippedGroups.add(method.getName());
            }
        }
        assertFalse(shippedGroups.isEmpty(), "reflection found no sqlStatement alternatives");
        Set<String> ungrouped = new TreeSet<>(shippedGroups);
        ungrouped.removeAll(groups.keySet());
        ungrouped.remove("ddlStatement");
        ungrouped.remove("dmlStatement");
        assertTrue(ungrouped.isEmpty(), "sqlStatement alternatives in no probed group:\n  "
                + String.join("\n  ", ungrouped));

        Set<String> shipped = new TreeSet<>();
        for (Class<?> groupContext : groups.values()) {
            shipped.addAll(alternativesOf(groupContext));
        }
        Set<String> probed = new TreeSet<>(otherGroupProbes().keySet());

        Set<String> unprobed = new TreeSet<>(shipped);
        unprobed.removeAll(probed);
        Set<String> stale = new TreeSet<>(probed);
        stale.removeAll(shipped);

        assertTrue(unprobed.isEmpty(), "statement alternatives with no probe:\n  "
                + String.join("\n  ", unprobed));
        assertTrue(stale.isEmpty(), "probes for alternatives the shipped grammar no longer defines:\n  "
                + String.join("\n  ", stale));
        assertEquals(shipped, probed, "the shipped statement surface and the probe set disagree");

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> probe : otherGroupProbes().entrySet()) {
            String sql = probe.getValue();
            try {
                service.setDdlEventTimestampMs(1700000000123L);
                StringBuffer output = new StringBuffer();
                service.parseSql(sql, "t", output);
                if (!output.toString().trim().isEmpty()) {
                    failures.add(probe.getKey() + " [" + sql + "] -> emitted [" + output + "]");
                }
            } catch (RuntimeException e) {
                failures.add(probe.getKey() + " [" + sql + "] -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), "these statements must parse silently:\n  "
                + String.join("\n  ", failures));
    }

    /** Guard: the two buckets must not overlap. */
    @Test
    public void theTwoBucketsAreDisjoint() {
        Set<String> overlap = new TreeSet<>(HANDLED);
        overlap.retainAll(INTENTIONALLY_UNHANDLED);
        assertEquals(new TreeSet<String>(), overlap,
                "a statement cannot be both handled and intentionally unhandled");
    }
}
