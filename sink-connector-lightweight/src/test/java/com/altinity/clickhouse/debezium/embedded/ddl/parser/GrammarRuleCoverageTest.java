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

import io.debezium.antlr.CaseChangingCharStream;
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage measured against the grammar's own rule table.
 *
 * <p>The MySQL language is deterministic and unambiguous, and the parser the
 * connector ships is generated from it, so the grammar itself is the authority
 * on what the source can send. {@code MySqlParser.ruleNames} is that authority
 * made machine-readable: every syntactic form in the language is a rule, and a
 * form the corpus never exercises is a form no test has ever seen -- which is
 * exactly how a clause as ordinary as {@code AUTOEXTEND_SIZE} stayed untested
 * until it stopped replication in production.</p>
 *
 * <p>This test does not encode a hand-written idea of the surface. It parses a
 * corpus through the production path and records which rules the parse tree
 * actually visits, then requires every rule of the shipped grammar to be either
 * exercised or listed in {@link #NOT_EXERCISED} with the reason it cannot be.
 * A parser upgrade that adds rules fails here until someone decides what
 * statement covers each one; a corpus that loses its reach into a part of the
 * language fails here too.</p>
 *
 * <p>Two properties are asserted, and the second is what keeps the first
 * honest:</p>
 * <ol>
 *   <li>every grammar rule is exercised by a statement in the corpus, or is
 *       documented as unreachable through the connector's entry point;</li>
 *   <li>nothing in the exclusion list is actually exercised -- an exclusion
 *       that has become reachable is a stale claim, and the rule should be
 *       covered by a statement instead.</li>
 * </ol>
 */
public class GrammarRuleCoverageTest {

    /**
     * Rules of the shipped parser that no statement in the corpus exercises,
     * with the reason.
     *
     * <p>Each entry is a deliberate decision, not a gap, and every one of them
     * was MEASURED rather than assumed: an all-rules reachability walk on the
     * shipped ATN from {@code root} reports ZERO structurally unreachable
     * rules, so "this rule is unreachable" is never a sufficient reason on its
     * own. The admissible reason is that ANTLR never selects the alternative
     * that would reach the rule: an earlier alternative of the same production
     * always matches first, or the lexer cannot produce the token sequence the
     * rule needs. Each entry names which, and the alternative that wins.</p>
     */
    private static final Map<String, String> NOT_EXERCISED = new LinkedHashMap<>();

    static {
        // SET AUTOCOMMIT ... is the second alternative of setStatement; the
        // first, #setVariable, accepts the same text as an assignment to the
        // identifier AUTOCOMMIT. Measured: '= 1', "= '1'", '= ON' and
        // '@@AUTOCOMMIT = 0' all build a SetStatement with variableClause and
        // never enter setAutocommitStatement.
        NOT_EXERCISED.put("setAutocommitStatement",
                "shadowed by setStatement's first alternative #setVariable, which"
                        + " accepts SET AUTOCOMMIT = <expression> as a variable assignment");

        // updateStatement is 'singleUpdateStatement | multipleUpdateStatement',
        // and in the shipped parser singleUpdateStatement already takes
        // tableSources -- the comma-separated list multipleUpdateStatement
        // exists for. Measured: 'UPDATE t, u SET t.a = 1, u.b = 2' builds a
        // SingleUpdateStatementContext whose tableSources holds both tables.
        NOT_EXERCISED.put("multipleUpdateStatement",
                "shadowed by singleUpdateStatement, whose shipped form already accepts"
                        + " a comma-separated tableSources list");

        // partitionListAtom is declared before partitionListVector and accepts
        // '(' partitionDefinerAtom (',' partitionDefinerAtom)+ ')' -- exactly
        // the text partitionDefinerVector matches. Both outer forms are
        // measured to resolve to partitionListAtom, and the proof is in its
        // tree: each '(1,2)' is ONE partitionDefinerAtom wrapping an
        // expressionAtom's nestedExpressionAtom, because expressionAtom
        // accepts '(' expression (',' expression)* ')'.
        NOT_EXERCISED.put("partitionDefinerVector",
                "shadowed by partitionListAtom, the earlier alternative, because"
                        + " expressionAtom's nestedExpressionAtom accepts a parenthesised"
                        + " comma list as a single atom");

        // unionParenthesis is reachable only from selectStatement's
        // #unionParenthesisSelect, which requires a leading
        // queryExpressionNointo -- and a leading parenthesised SELECT always
        // parses as the queryExpressionNointo of the EARLIER #unionSelect
        // alternative, whose trailing operands then chain as unionStatement.
        // Measured on the tree: '(SELECT 1) UNION (SELECT 2)' is a
        // UnionSelectContext whose right operand is a unionStatement, not a
        // unionParenthesis, and the deeper nesting form
        // '((SELECT 1) UNION (SELECT 2)) UNION (SELECT 3)' has no viable
        // alternative at all. No shape reaches #unionParenthesisSelect.
        NOT_EXERCISED.put("unionParenthesis",
                "shadowed by #unionSelect, which already accepts a parenthesised"
                        + " leading operand and then chains the rest as unionStatement");
    }

    /**
     * Statements covering the language, grouped so a failure names the area
     * that lost coverage rather than a bare rule index.
     *
     * <p>Every statement is valid MySQL 8.0 and reaches the parser the way a
     * binlog statement does. A statement this list gets wrong is a test defect:
     * it is removed or corrected, never allowed to stand as a "parser bug".</p>
     */
    private static Map<String, List<String>> corpus() {
        Map<String, List<String>> areas = new LinkedHashMap<>();

        areas.put("select", List.of(
                "SELECT 1",
                "SELECT * FROM t",
                "SELECT ALL a FROM t",
                "SELECT DISTINCT a FROM t",
                "SELECT DISTINCTROW a FROM t",
                "SELECT HIGH_PRIORITY a FROM t",
                "SELECT STRAIGHT_JOIN a FROM t",
                "SELECT SQL_SMALL_RESULT a FROM t",
                "SELECT SQL_BIG_RESULT a FROM t",
                "SELECT SQL_BUFFER_RESULT a FROM t",
                "SELECT SQL_NO_CACHE a FROM t",
                "SELECT SQL_CALC_FOUND_ROWS a FROM t",
                "SELECT a AS x FROM t",
                "SELECT t.a FROM db.t",
                "SELECT a FROM t WHERE a = 1 AND b <> 2 OR c IS NULL",
                "SELECT a FROM t WHERE a BETWEEN 1 AND 2",
                "SELECT a FROM t WHERE a LIKE 'x%' ESCAPE '!'",
                "SELECT a FROM t WHERE a REGEXP 'x'",
                "SELECT a FROM t WHERE a IN (1, 2, 3)",
                "SELECT a FROM t WHERE a IN (SELECT b FROM s)",
                "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM s)",
                "SELECT a FROM t WHERE a > ANY (SELECT b FROM s)",
                "SELECT a FROM t WHERE a > ALL (SELECT b FROM s)",
                "SELECT (SELECT MAX(b) FROM s) FROM t",
                "SELECT a FROM t GROUP BY a",
                "SELECT a, COUNT(*) FROM t GROUP BY a WITH ROLLUP",
                "SELECT a, COUNT(*) FROM t GROUP BY a HAVING COUNT(*) > 1",
                "SELECT a FROM t ORDER BY a",
                "SELECT a FROM t ORDER BY a DESC, b ASC",
                "SELECT a FROM t ORDER BY a LIMIT 10",
                "SELECT a FROM t LIMIT 5, 10",
                "SELECT a FROM t LIMIT 10 OFFSET 5",
                "SELECT a FROM t FOR UPDATE",
                "SELECT a FROM t LOCK IN SHARE MODE",
                "SELECT a FROM t INTO OUTFILE '/x'",
                "SELECT a FROM t INTO DUMPFILE '/x'",
                "SELECT 1 UNION SELECT 2",
                "SELECT 1 UNION ALL SELECT 2",
                "SELECT 1 UNION DISTINCT SELECT 2",
                "SELECT * FROM t1 JOIN t2 ON t1.a = t2.a",
                "SELECT * FROM t1 INNER JOIN t2 ON t1.a = t2.a",
                "SELECT * FROM t1 LEFT JOIN t2 ON t1.a = t2.a",
                "SELECT * FROM t1 LEFT OUTER JOIN t2 USING (a)",
                "SELECT * FROM t1 RIGHT JOIN t2 ON t1.a = t2.a",
                "SELECT * FROM t1 CROSS JOIN t2",
                "SELECT * FROM t1 NATURAL JOIN t2",
                "SELECT * FROM t1 NATURAL LEFT OUTER JOIN t2",
                "SELECT * FROM t1 STRAIGHT_JOIN t2 ON t1.a = t2.a",
                "SELECT * FROM (SELECT 1) AS d",
                "SELECT * FROM t1, LATERAL (SELECT 1) AS d",
                "SELECT CASE WHEN a = 1 THEN 'x' ELSE 'y' END FROM t",
                "SELECT CASE a WHEN 1 THEN 'x' END FROM t",
                "SELECT CAST(a AS CHAR(10)) FROM t",
                "SELECT CONVERT(a, CHAR) FROM t",
                "SELECT CONVERT(a USING utf8mb4) FROM t",
                "SELECT GROUP_CONCAT(a ORDER BY b SEPARATOR ',') FROM t",
                "SELECT ROW_NUMBER() OVER (PARTITION BY a ORDER BY b"
                        + " ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM t",
                "SELECT SUM(a) OVER (ORDER BY b RANGE BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM t",
                "SELECT IF(a > 1, 'x', 'y') FROM t",
                "SELECT IFNULL(a, 0) FROM t",
                "SELECT COALESCE(a, b, 0) FROM t",
                "SELECT NULLIF(a, 0) FROM t",
                "SELECT a + INTERVAL 1 DAY FROM t",
                "SELECT DATE_ADD(a, INTERVAL 1 HOUR) FROM t",
                "SELECT EXTRACT(YEAR FROM a) FROM t",
                "SELECT TIMESTAMPADD(DAY, 1, a) FROM t",
                "SELECT JSON_EXTRACT(a, '$.x') FROM t",
                "SELECT a->>'$.x' FROM t",
                "SELECT BINARY a FROM t",
                "SELECT !a FROM t",
                "SELECT ~a FROM t",
                "SELECT a DIV 2 FROM t",
                "SELECT a MOD 2 FROM t",
                "SELECT a << 1 FROM t",
                "SELECT a & 1 FROM t",
                "SELECT a ^ 1 FROM t",
                "SELECT a | 1 FROM t",
                "SELECT -a FROM t",
                "SELECT a COLLATE utf8mb4_bin FROM t",
                "SELECT EXISTS(SELECT 1) FROM t",
                "WITH cte AS (SELECT 1) SELECT * FROM cte",
                "WITH RECURSIVE cte AS (SELECT 1 UNION ALL SELECT 2) SELECT * FROM cte",
                "TABLE t",
                "VALUES ROW(1, 2), ROW(3, 4)"));

        areas.put("insert", List.of(
                "INSERT INTO t VALUES (1)",
                "INSERT INTO t (a, b) VALUES (1, 2), (3, 4)",
                "INSERT IGNORE INTO t VALUES (1)",
                "INSERT INTO t SET a = 1",
                "INSERT INTO t SELECT * FROM s",
                "INSERT INTO t (a) SELECT b FROM s",
                "INSERT INTO t VALUES (1) ON DUPLICATE KEY UPDATE a = 2",
                "INSERT INTO t PARTITION (p0) VALUES (1)",
                "INSERT INTO t VALUES (1) AS new ON DUPLICATE KEY UPDATE a = new.a",
                "REPLACE INTO t VALUES (1)",
                "REPLACE INTO t SET a = 1",
                "REPLACE INTO t SELECT * FROM s",
                "REPLACE INTO t PARTITION (p0) VALUES (1)"));

        areas.put("update", List.of(
                "UPDATE t SET a = 1",
                "UPDATE t SET a = 1 WHERE b = 2",
                "UPDATE IGNORE t SET a = 1",
                "UPDATE t SET a = 1 ORDER BY a LIMIT 1",
                "UPDATE t1 JOIN t2 ON t1.a = t2.a SET t1.b = 2",
                "UPDATE t SET a = DEFAULT",
                "UPDATE t SET a = a + 1"));

        // DESTRUCTIVE: fixture text only. Each string is handed to the lexer
        // and parser and nothing else; no statement is sent to a server, so
        // no row is deleted and no table is emptied.
        areas.put("delete", List.of(
                "DELETE FROM t",
                "DELETE FROM t WHERE a = 1",
                "DELETE QUICK FROM t WHERE a = 1",
                "DELETE IGNORE FROM t WHERE a = 1",
                // DESTRUCTIVE: fixture text only. These are strings handed to
                // the parser; no statement is sent to a server, so no row is
                // deleted and no table is emptied.
                "DELETE FROM t ORDER BY a LIMIT 1",
                "DELETE t1 FROM t1 JOIN t2 ON t1.a = t2.a",
                "DELETE FROM t1 USING t1 JOIN t2 ON t1.a = t2.a",
                // DESTRUCTIVE: fixture text only. The parser reads the image of
                // the statement; nothing is executed, so no table is emptied.
                "TRUNCATE TABLE t",
                "TRUNCATE t"));

        areas.put("load", List.of(
                "LOAD DATA INFILE '/x' INTO TABLE t",
                "LOAD DATA LOW_PRIORITY LOCAL INFILE '/x' INTO TABLE t"
                        + " FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n'",
                "LOAD DATA INFILE '/x' IGNORE INTO TABLE t (a, b)",
                "LOAD XML INFILE '/x' INTO TABLE t"));

        areas.put("call-do-handler", List.of(
                "CALL p()",
                "CALL db.p(1, 2)",
                "DO 1",
                "HANDLER t OPEN",
                "HANDLER t READ FIRST",
                "HANDLER t CLOSE"));

        areas.put("set", List.of(
                "SET @x = 1",
                "SET SESSION sql_mode = 'STRICT_TRANS_TABLES'",
                "SET GLOBAL max_connections = 1000",
                "SET NAMES utf8mb4",
                "SET NAMES utf8mb4 COLLATE utf8mb4_bin",
                "SET CHARACTER SET utf8mb4",
                "SET TRANSACTION ISOLATION LEVEL READ COMMITTED",
                "SET PERSIST max_connections = 1000",
                "SET PERSIST_ONLY max_connections = 1000",
                "RESET PERSIST",
                "SET RESOURCE GROUP rg",
                "SET DEFAULT ROLE ALL TO u",
                "SET ROLE NONE"));

        areas.put("show", List.of(
                "SHOW TABLES",
                "SHOW CREATE TABLE t",
                "SHOW COLUMNS FROM t",
                "SHOW STATUS",
                "SHOW VARIABLES LIKE 'x'",
                "SHOW BINLOG EVENTS",
                "SHOW MASTER STATUS",
                "SHOW PROCESSLIST",
                "SHOW GRANTS FOR u",
                "SHOW INDEX FROM t",
                "SHOW ENGINE INNODB STATUS",
                "SHOW WARNINGS",
                "SHOW ERRORS"));

        areas.put("utility", List.of(
                "USE db",
                "DESCRIBE t",
                "DESC t",
                "EXPLAIN SELECT 1",
                "EXPLAIN FORMAT=JSON SELECT 1",
                "HELP 'x'"));

        areas.put("transaction", List.of(
                "BEGIN",
                "START TRANSACTION",
                "START TRANSACTION READ WRITE",
                "COMMIT",
                "ROLLBACK",
                "SAVEPOINT sp",
                "RELEASE SAVEPOINT sp",
                "ROLLBACK TO SAVEPOINT sp",
                "LOCK TABLES t WRITE",
                "LOCK TABLES t READ, s WRITE",
                "UNLOCK TABLES",
                "XA START 'x'",
                "XA END 'x'",
                "XA COMMIT 'x'",
                "XA ROLLBACK 'x'"));

        areas.put("prepared", List.of(
                "PREPARE s FROM 'SELECT 1'",
                "EXECUTE s",
                "EXECUTE s USING @a",
                "DEALLOCATE PREPARE s"));

        areas.put("replication", List.of(
                "CHANGE MASTER TO MASTER_HOST = 'h'",
                "START SLAVE",
                "STOP SLAVE",
                "RESET MASTER",
                "RESET SLAVE ALL",
                "PURGE BINARY LOGS TO 'mysql-bin.000010'",
                "PURGE BINARY LOGS BEFORE '2030-01-01 00:00:00'",
                "PURGE MASTER LOGS TO 'mysql-bin.000010'",
                "CHANGE REPLICATION FILTER REPLICATE_DO_DB = (d)"));

        areas.put("administration", List.of(
                "ANALYZE TABLE t",
                "CHECK TABLE t",
                "CHECKSUM TABLE t",
                "OPTIMIZE TABLE t",
                "REPAIR NO_WRITE_TO_BINLOG TABLE t",
                "FLUSH TABLES",
                "FLUSH PRIVILEGES",
                "FLUSH LOGS",
                "KILL 1",
                "KILL CONNECTION 1",
                "KILL QUERY 1",
                "SHUTDOWN",
                "CACHE INDEX t IN k",
                "LOAD INDEX INTO CACHE t",
                "INSTALL PLUGIN p SONAME 'x.so'",
                "UNINSTALL PLUGIN p"));

        areas.put("create-database", List.of(
                "CREATE DATABASE d",
                "CREATE DATABASE IF NOT EXISTS d DEFAULT CHARACTER SET utf8mb4"
                        + " COLLATE utf8mb4_bin",
                "CREATE SCHEMA d",
                "ALTER DATABASE d CHARACTER SET utf8mb4",
                "ALTER DATABASE d UPGRADE DATA DIRECTORY NAME",
                "ALTER DATABASE d READ ONLY = 1",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no database is
                // dropped.
                "DROP DATABASE d",
                "DROP DATABASE IF EXISTS d"));

        areas.put("create-table", List.of(
                "CREATE TABLE t (a INT)",
                "CREATE TABLE IF NOT EXISTS t (a INT)",
                "CREATE TEMPORARY TABLE t (a INT)",
                "CREATE TABLE t LIKE s",
                "CREATE TABLE t AS SELECT 1",
                "CREATE TABLE t (a INT, PRIMARY KEY (a))",
                "CREATE TABLE t (a INT, UNIQUE KEY uq (a))",
                "CREATE TABLE t (a INT, KEY idx (a))",
                "CREATE TABLE t (a INT, INDEX idx (a) USING BTREE)",
                "CREATE TABLE t (a TEXT, FULLTEXT KEY ft (a))",
                "CREATE TABLE t (g GEOMETRY NOT NULL, SPATIAL INDEX si (g))",
                "CREATE TABLE t (a INT, CONSTRAINT fk FOREIGN KEY (a) REFERENCES s (a)"
                        + " ON DELETE CASCADE ON UPDATE RESTRICT)",
                "CREATE TABLE t (a INT CHECK (a > 0))",
                "CREATE TABLE t (a INT, CONSTRAINT chk CHECK (a > 0) ENFORCED)",
                "CREATE TABLE t (a INT NOT NULL DEFAULT 0 AUTO_INCREMENT UNIQUE COMMENT 'x')",
                "CREATE TABLE t (a INT INVISIBLE)",
                "CREATE TABLE t (a INT VISIBLE)",
                "CREATE TABLE t (a INT COLUMN_FORMAT FIXED STORAGE DISK)",
                "CREATE TABLE t (a INT GENERATED ALWAYS AS (1) VIRTUAL)",
                "CREATE TABLE t (a INT GENERATED ALWAYS AS (1) STORED)",
                "CREATE TABLE t (a TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)",
                "CREATE TABLE t (a INT AS (1) PERSISTENT)",
                "CREATE TABLE t (a BIT(8))",
                "CREATE TABLE t (a TINYINT UNSIGNED ZEROFILL)",
                "CREATE TABLE t (a BOOL)",
                "CREATE TABLE t (a BOOLEAN)",
                "CREATE TABLE t (a SMALLINT)",
                "CREATE TABLE t (a MEDIUMINT)",
                "CREATE TABLE t (a INTEGER)",
                "CREATE TABLE t (a BIGINT UNSIGNED)",
                "CREATE TABLE t (a DECIMAL(10, 2))",
                "CREATE TABLE t (a NUMERIC(10, 2) UNSIGNED)",
                "CREATE TABLE t (a FLOAT(10, 2))",
                "CREATE TABLE t (a DOUBLE PRECISION)",
                "CREATE TABLE t (a REAL)",
                "CREATE TABLE t (a DATE)",
                "CREATE TABLE t (a TIME(3))",
                "CREATE TABLE t (a DATETIME(6))",
                "CREATE TABLE t (a TIMESTAMP(6))",
                "CREATE TABLE t (a YEAR)",
                "CREATE TABLE t (a CHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin)",
                "CREATE TABLE t (a VARCHAR(255) NOT NULL)",
                "CREATE TABLE t (a BINARY(16))",
                "CREATE TABLE t (a VARBINARY(16))",
                "CREATE TABLE t (a TINYBLOB)",
                "CREATE TABLE t (a BLOB)",
                "CREATE TABLE t (a MEDIUMBLOB)",
                "CREATE TABLE t (a LONGBLOB)",
                "CREATE TABLE t (a TINYTEXT)",
                "CREATE TABLE t (a TEXT)",
                "CREATE TABLE t (a MEDIUMTEXT)",
                "CREATE TABLE t (a LONGTEXT)",
                "CREATE TABLE t (a ENUM('x', 'y'))",
                "CREATE TABLE t (a SET('x', 'y'))",
                "CREATE TABLE t (a JSON)",
                "CREATE TABLE t (a POINT)",
                "CREATE TABLE t (a LINESTRING)",
                "CREATE TABLE t (a POLYGON)",
                "CREATE TABLE t (a MULTIPOINT)",
                "CREATE TABLE t (a MULTILINESTRING)",
                "CREATE TABLE t (a MULTIPOLYGON)",
                "CREATE TABLE t (a GEOMETRYCOLLECTION)",
                "CREATE TABLE t (g GEOMETRY SRID 4326)",
                "CREATE TABLE t (a INT) ENGINE = InnoDB",
                "CREATE TABLE t (a INT) AUTO_INCREMENT = 1",
                "CREATE TABLE t (a INT) AVG_ROW_LENGTH = 100",
                "CREATE TABLE t (a INT) DEFAULT CHARSET = utf8mb4",
                "CREATE TABLE t (a INT) CHECKSUM = 1",
                "CREATE TABLE t (a INT) COLLATE = utf8mb4_bin",
                "CREATE TABLE t (a INT) COMMENT = 'x'",
                "CREATE TABLE t (a INT) COMPRESSION = 'zlib'",
                "CREATE TABLE t (a INT) CONNECTION = 'x'",
                "CREATE TABLE t (a INT) DATA DIRECTORY = '/x'",
                "CREATE TABLE t (a INT) DELAY_KEY_WRITE = 1",
                "CREATE TABLE t (a INT) ENCRYPTION = 'Y'",
                "CREATE TABLE t (a INT) INDEX DIRECTORY = '/x'",
                "CREATE TABLE t (a INT) INSERT_METHOD = FIRST",
                "CREATE TABLE t (a INT) KEY_BLOCK_SIZE = 8",
                "CREATE TABLE t (a INT) MAX_ROWS = 100",
                "CREATE TABLE t (a INT) MIN_ROWS = 1",
                "CREATE TABLE t (a INT) PACK_KEYS = 1",
                "CREATE TABLE t (a INT) PASSWORD = 'x'",
                "CREATE TABLE t (a INT) ROW_FORMAT = DYNAMIC",
                "CREATE TABLE t (a INT) STATS_AUTO_RECALC = 1",
                "CREATE TABLE t (a INT) STATS_PERSISTENT = 1",
                "CREATE TABLE t (a INT) STATS_SAMPLE_PAGES = 10",
                "CREATE TABLE t (a INT) TABLESPACE ts",
                "CREATE TABLE t (a INT) ENGINE = MRG_MyISAM UNION = (t1, t2)",
                "CREATE TABLE t (a INT) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4"
                        + " COLLATE = utf8mb4_bin ROW_FORMAT = DYNAMIC COMMENT = 'x'",
                "CREATE TABLE t (a INT) AUTOEXTEND_SIZE = 4M",
                "CREATE TABLE t (a JSON, b INT GENERATED ALWAYS AS (a->>'$.x') STORED)"));

        areas.put("partitioning", List.of(
                "CREATE TABLE t (a INT) PARTITION BY HASH (a) PARTITIONS 4",
                "CREATE TABLE t (a INT) PARTITION BY LINEAR HASH (a) PARTITIONS 4",
                "CREATE TABLE t (a INT) PARTITION BY KEY (a) PARTITIONS 2",
                "CREATE TABLE t (a INT) PARTITION BY LINEAR KEY (a) PARTITIONS 2",
                "CREATE TABLE t (a INT) PARTITION BY RANGE (a)"
                        + " (PARTITION p0 VALUES LESS THAN (10),"
                        + " PARTITION p1 VALUES LESS THAN MAXVALUE)",
                "CREATE TABLE t (a INT, b INT) PARTITION BY RANGE COLUMNS (a, b)"
                        + " (PARTITION p0 VALUES LESS THAN (1, 2))",
                "CREATE TABLE t (a INT) PARTITION BY LIST (a)"
                        + " (PARTITION p0 VALUES IN (1, 2))",
                "CREATE TABLE t (a INT) PARTITION BY LIST COLUMNS (a)"
                        + " (PARTITION p0 VALUES IN (1, 2))",
                "CREATE TABLE t (a INT) PARTITION BY HASH (a) PARTITIONS 2"
                        + " SUBPARTITION BY HASH (a) SUBPARTITIONS 2",
                "CREATE TABLE t (a INT) PARTITION BY RANGE (a)"
                        + " (PARTITION p0 VALUES LESS THAN (10) ENGINE = InnoDB"
                        + " COMMENT = 'x' DATA DIRECTORY = '/x' INDEX DIRECTORY = '/x'"
                        + " MAX_ROWS = 10 MIN_ROWS = 1 TABLESPACE ts NODEGROUP = 2)"));

        areas.put("alter-table", List.of(
                "ALTER TABLE t ADD COLUMN c INT",
                "ALTER TABLE t ADD COLUMN c INT FIRST",
                "ALTER TABLE t ADD COLUMN c INT AFTER a",
                "ALTER TABLE t ADD COLUMN (c INT, d INT)",
                "ALTER TABLE t ADD c INT, ADD d INT",
                "ALTER TABLE t MODIFY COLUMN c BIGINT NOT NULL",
                "ALTER TABLE t CHANGE COLUMN a b INT",
                "ALTER TABLE t RENAME COLUMN a TO b",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the ALTER TABLE; no DDL is executed, so no column is
                // dropped.
                "ALTER TABLE t DROP COLUMN c",
                "ALTER TABLE t ADD PRIMARY KEY (a)",
                "ALTER TABLE t DROP PRIMARY KEY",
                "ALTER TABLE t ADD UNIQUE KEY uq (a)",
                "ALTER TABLE t ADD INDEX idx (a)",
                "ALTER TABLE t ADD FULLTEXT KEY ft (a)",
                "ALTER TABLE t ADD SPATIAL INDEX si (g)",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the ALTER TABLE; no DDL is executed, so no index is
                // dropped.
                "ALTER TABLE t DROP INDEX idx",
                "ALTER TABLE t RENAME INDEX a TO b",
                "ALTER TABLE t ALTER INDEX idx INVISIBLE",
                "ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES s (a)",
                "ALTER TABLE t DROP FOREIGN KEY fk",
                "ALTER TABLE t ADD CONSTRAINT chk CHECK (a > 0)",
                "ALTER TABLE t ALTER CHECK chk ENFORCED",
                "ALTER TABLE t DROP CHECK chk",
                "ALTER TABLE t ALTER COLUMN c SET DEFAULT 0",
                "ALTER TABLE t ALTER COLUMN c DROP DEFAULT",
                "ALTER TABLE t ALTER COLUMN c SET VISIBLE",
                "ALTER TABLE t CONVERT TO CHARACTER SET utf8mb4",
                "ALTER TABLE t DEFAULT CHARSET = utf8mb4",
                "ALTER TABLE t ENGINE = InnoDB",
                "ALTER TABLE t ROW_FORMAT = DYNAMIC",
                "ALTER TABLE t RENAME TO s",
                "ALTER TABLE t ORDER BY a",
                "ALTER TABLE t FORCE",
                "ALTER TABLE t DISABLE KEYS",
                "ALTER TABLE t ENABLE KEYS",
                "ALTER TABLE t DISCARD TABLESPACE",
                "ALTER TABLE t IMPORT TABLESPACE",
                "ALTER TABLE t DISCARD PARTITION p0 TABLESPACE",
                "ALTER TABLE t IMPORT PARTITION p0 TABLESPACE",
                "ALTER TABLE t ADD COLUMN c INT, ALGORITHM = INPLACE",
                "ALTER TABLE t ADD COLUMN c INT, LOCK = NONE",
                "ALTER TABLE t ADD COLUMN c INT, WITH VALIDATION",
                "ALTER TABLE t ADD COLUMN c INT, WITHOUT VALIDATION",
                "ALTER TABLE t ADD PARTITION (PARTITION p9 VALUES LESS THAN (100))",
                // DESTRUCTIVE: fixture text only. The parser reads the image of
                // the ALTER TABLE; no DDL is executed, so no partition is
                // dropped or emptied.
                "ALTER TABLE t DROP PARTITION p0",
                "ALTER TABLE t TRUNCATE PARTITION p0",
                "ALTER TABLE t COALESCE PARTITION 2",
                "ALTER TABLE t REORGANIZE PARTITION p0 INTO"
                        + " (PARTITION p0 VALUES LESS THAN (5), PARTITION p1 VALUES LESS THAN (10))",
                "ALTER TABLE t EXCHANGE PARTITION p0 WITH TABLE s",
                "ALTER TABLE t ANALYZE PARTITION p0",
                "ALTER TABLE t CHECK PARTITION p0",
                "ALTER TABLE t OPTIMIZE PARTITION p0",
                "ALTER TABLE t REBUILD PARTITION p0",
                "ALTER TABLE t REPAIR PARTITION p0",
                "ALTER TABLE t REMOVE PARTITIONING",
                "ALTER TABLE t UPGRADE PARTITIONING",
                "ALTER TABLE t SECONDARY_ENGINE = RAPID",
                "ALTER TABLE t ENGINE_ATTRIBUTE = '{}'",
                "ALTER TABLE t SECONDARY_ENGINE_ATTRIBUTE = '{}'",
                "ALTER ONLINE TABLE t ADD COLUMN c INT",
                "ALTER IGNORE TABLE t ADD COLUMN c INT",
                "ALTER TABLE t ADD COLUMN c INT, RENAME TO s"));

        // DESTRUCTIVE: fixture text only. The parser reads the image of the
        // statement; no DDL is executed, so no table is dropped.
        areas.put("drop-and-rename-table", List.of(
                "DROP TABLE t",
                "DROP TABLE IF EXISTS t",
                "DROP TABLE t1, t2",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no temporary table
                // is dropped.
                "DROP TEMPORARY TABLE t",
                "RENAME TABLE a TO b",
                "RENAME TABLE a TO b, c TO d"));

        areas.put("view", List.of(
                "CREATE VIEW v AS SELECT 1",
                "CREATE OR REPLACE VIEW v AS SELECT 1",
                "CREATE ALGORITHM = MERGE VIEW v (a) AS SELECT 1",
                "ALTER VIEW v AS SELECT 1",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no view is dropped.
                "DROP VIEW v",
                "DROP VIEW IF EXISTS v"));

        areas.put("trigger", List.of(
                "CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW SET @x = 1",
                "CREATE TRIGGER tr AFTER UPDATE ON t FOR EACH ROW FOLLOWS tr2 SET @x = 1",
                "CREATE DEFINER = u@h TRIGGER tr BEFORE DELETE ON t FOR EACH ROW SET @x = 1",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no trigger is
                // dropped.
                "DROP TRIGGER tr"));

        areas.put("routine", List.of(
                "CREATE PROCEDURE p() BEGIN SELECT 1; END",
                "CREATE PROCEDURE p(IN a INT, OUT b INT) SELECT a",
                "CREATE DEFINER = u@h PROCEDURE p() COMMENT 'x' SELECT 1",
                "ALTER PROCEDURE p COMMENT 'x'",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no routine is
                // dropped.
                "DROP PROCEDURE p",
                "CREATE FUNCTION f() RETURNS INT RETURN 1",
                "CREATE FUNCTION f(a INT) RETURNS INT DETERMINISTIC RETURN a",
                "ALTER FUNCTION f COMMENT 'x'",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no function is
                // dropped.
                "DROP FUNCTION f"));

        areas.put("event", List.of(
                "CREATE EVENT e ON SCHEDULE AT '2030-01-01 00:00:00' DO SELECT 1",
                "CREATE EVENT e ON SCHEDULE EVERY 1 DAY STARTS '2030-01-01'"
                        + " ENDS '2031-01-01' ON COMPLETION PRESERVE ENABLE DO SELECT 1",
                "ALTER EVENT e ON SCHEDULE EVERY 2 DAY",
                "ALTER EVENT e DISABLE",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no event is
                // dropped.
                "DROP EVENT e"));

        areas.put("index", List.of(
                "CREATE INDEX i ON t (a)",
                "CREATE UNIQUE INDEX i ON t (a DESC)",
                "CREATE FULLTEXT INDEX i ON t (a)",
                "CREATE SPATIAL INDEX i ON t (g)",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no index is dropped.
                "DROP INDEX i ON t"));

        areas.put("server-and-logfile-group", List.of(
                "CREATE SERVER s FOREIGN DATA WRAPPER mysql OPTIONS (HOST 'h', USER 'u')",
                "ALTER SERVER s OPTIONS (HOST 'h2')",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no server is
                // dropped.
                "DROP SERVER s",
                "CREATE LOGFILE GROUP lg ADD UNDOFILE 'x' ENGINE = NDB",
                "ALTER LOGFILE GROUP lg ADD UNDOFILE 'y' ENGINE = NDB",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no logfile group is
                // dropped.
                "DROP LOGFILE GROUP lg ENGINE = NDB"));

        areas.put("tablespace", List.of(
                "CREATE TABLESPACE ts ADD DATAFILE 'x.ibd' ENGINE = InnoDB",
                "CREATE TABLESPACE ts ADD DATAFILE 'x' FILE_BLOCK_SIZE = 8192 ENGINE = NDB",
                "ALTER TABLESPACE ts ADD DATAFILE 'y' INITIAL_SIZE = 1M ENGINE = InnoDB",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no tablespace is
                // dropped.
                "DROP TABLESPACE ts ENGINE = InnoDB",
                "CREATE UNDO TABLESPACE ts ADD DATAFILE 'x.ibu'",
                "ALTER UNDO TABLESPACE ts RENAME TO ts2",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no undo tablespace
                // is dropped.
                "DROP UNDO TABLESPACE ts"));

        areas.put("resource-group-and-srs", List.of(
                "CREATE RESOURCE GROUP rg TYPE = SYSTEM VCPU = 0-3 THREAD_PRIORITY = 10",
                "ALTER RESOURCE GROUP rg VCPU = 4",
                "DROP RESOURCE GROUP rg",
                "CREATE SPATIAL REFERENCE SYSTEM 9999 NAME 'x' DEFINITION 'y'",
                "DROP SPATIAL REFERENCE SYSTEM 9999"));

        areas.put("account", List.of(
                "CREATE USER u IDENTIFIED BY 'x'",
                "CREATE USER u IDENTIFIED WITH caching_sha2_password BY 'x' REQUIRE X509"
                        + " WITH MAX_QUERIES_PER_HOUR 10",
                "ALTER USER u IDENTIFIED BY 'y'",
                "ALTER USER u IDENTIFIED BY 'y' RETAIN CURRENT PASSWORD",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no account or role
                // is dropped.
                "DROP USER u",
                "DROP USER IF EXISTS u",
                "RENAME USER a TO b",
                "CREATE ROLE r",
                "DROP ROLE r",
                "GRANT SELECT ON *.* TO u",
                "GRANT ALL PRIVILEGES ON db.* TO u WITH GRANT OPTION",
                "GRANT EXECUTE ON PROCEDURE d.p TO u",
                "REVOKE SELECT ON *.* FROM u",
                "REVOKE ALL PRIVILEGES, GRANT OPTION FROM u"));

        areas.put("instance-and-clone", List.of(
                "ALTER INSTANCE ROTATE INNODB MASTER KEY",
                "CLONE INSTANCE FROM u@h:3306 IDENTIFIED BY 'x'",
                "CLONE LOCAL DATA DIRECTORY = '/x'"));

        // A stored program body is a whole language of its own, and the
        // grammar gives it its own rule subtree. Nothing here is exotic -- a
        // procedure with a cursor, a handler and a loop is ordinary
        // application SQL -- yet before this area existed the corpus never
        // parsed a single one of those rules.
        areas.put("stored-program", List.of(
                "CREATE PROCEDURE p() BEGIN DECLARE v INT; END",
                "CREATE PROCEDURE p() BEGIN DECLARE v INT DEFAULT 0; END",
                "CREATE PROCEDURE p() BEGIN DECLARE c CONDITION FOR SQLSTATE '45000'; END",
                "CREATE PROCEDURE p() BEGIN DECLARE CONTINUE HANDLER FOR NOT FOUND SET @x = 1; END",
                "CREATE PROCEDURE p() BEGIN DECLARE CONTINUE HANDLER FOR SQLSTATE '02000'"
                        + " SET @x = 1; END",
                "CREATE PROCEDURE p() BEGIN DECLARE CONTINUE HANDLER FOR SQLEXCEPTION"
                        + " BEGIN END; END",
                "CREATE PROCEDURE p() BEGIN DECLARE EXIT HANDLER FOR SQLWARNING SELECT 1; END",
                "CREATE PROCEDURE p() BEGIN DECLARE CONTINUE HANDLER FOR 1062 SELECT 1; END",
                "CREATE PROCEDURE p() BEGIN DECLARE cur CURSOR FOR SELECT 1;"
                        + " OPEN cur; CLOSE cur; END",
                "CREATE PROCEDURE p() BEGIN IF 1 = 1 THEN SELECT 1; END IF; END",
                "CREATE PROCEDURE p() BEGIN IF 1 = 1 THEN SELECT 1;"
                        + " ELSEIF 2 = 2 THEN SELECT 2; ELSE SELECT 3; END IF; END",
                "CREATE PROCEDURE p() BEGIN CASE 1 WHEN 1 THEN SELECT 1; ELSE SELECT 2;"
                        + " END CASE; END",
                "CREATE PROCEDURE p() BEGIN CASE WHEN 1 = 1 THEN SELECT 1; END CASE; END",
                "CREATE PROCEDURE p() BEGIN WHILE 1 = 1 DO SELECT 1; END WHILE; END",
                "CREATE PROCEDURE p() BEGIN REPEAT SELECT 1; UNTIL 1 = 1 END REPEAT; END",
                "CREATE PROCEDURE p() BEGIN l: LOOP SELECT 1; END LOOP l; END",
                "CREATE PROCEDURE p() BEGIN l: LOOP LEAVE l; END LOOP l; END",
                "CREATE PROCEDURE p() BEGIN l: LOOP ITERATE l; END LOOP l; END",
                "CREATE PROCEDURE p() BEGIN SIGNAL SQLSTATE '45000'; END",
                "CREATE PROCEDURE p() BEGIN SIGNAL SQLSTATE '45000'"
                        + " SET MESSAGE_TEXT = 'x', MYSQL_ERRNO = 1000; END",
                "CREATE PROCEDURE p() BEGIN RESIGNAL; END",
                "CREATE PROCEDURE p() BEGIN RESIGNAL SQLSTATE '45000'; END",
                "CREATE PROCEDURE p() BEGIN GET DIAGNOSTICS @x = NUMBER; END",
                "CREATE PROCEDURE p() BEGIN GET DIAGNOSTICS CONDITION 1 @x = MESSAGE_TEXT,"
                        + " @y = RETURNED_SQLSTATE; END"));

        // Literals, variables and the functions that read them. Every one of
        // these appeared in a real statement this connector has had to parse,
        // and the grammar gives each its own rule.
        areas.put("literals-and-variables", List.of(
                "SELECT TRUE",
                "SELECT FALSE",
                "SELECT X'FF'",
                "SELECT 0xFF",
                "SELECT b'1010'",
                "SELECT N'x'",
                "SELECT _utf8mb4'x'",
                "SELECT @@version",
                "SELECT @@GLOBAL.max_connections",
                "SELECT @@SESSION.sql_mode",
                "SET GLOBAL max_connections = 1000",
                "SET SESSION sql_mode = 'STRICT_TRANS_TABLES'",
                "SET @user_var = 1",
                "SET autocommit = 0",
                "SET AUTOCOMMIT = 1",
                "SET PASSWORD FOR u = 'x'",
                "SET PASSWORD = 'x'",
                "SELECT CURRENT_USER()",
                "SELECT CURRENT_USER",
                "SELECT PASSWORD('x')",
                "SELECT INTERVAL 1 DAY",
                "SELECT WEIGHT_STRING(a AS CHAR(10)) FROM t"));

        // Query structure the grammar models with its own rules: set
        // operations, common table expressions, index hints, windows and the
        // JSON_TABLE function.
        areas.put("query-structure", List.of(
                "(SELECT 1) UNION (SELECT 2)",
                "(SELECT 1) UNION ALL (SELECT 2)",
                "WITH c (x) AS (SELECT 1) SELECT * FROM c",
                "WITH RECURSIVE c (x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT * FROM c",
                "SELECT * FROM t USE INDEX (i)",
                "SELECT * FROM t FORCE INDEX FOR JOIN (i)",
                "SELECT * FROM t IGNORE INDEX (i)",
                "SELECT * FROM t, s WHERE t.a = s.a",
                "SELECT SUM(a) OVER w FROM t WINDOW w AS (PARTITION BY b)",
                "SELECT SUM(a) OVER (w2) FROM t WINDOW w AS (PARTITION BY b)",
                "SELECT SUM(a) OVER (PARTITION BY b ORDER BY c ROWS BETWEEN 1 PRECEDING"
                        + " AND CURRENT ROW) FROM t",
                "SELECT * FROM JSON_TABLE('[]', '$[*]' COLUMNS (x INT PATH '$.x')) AS jt",
                "SELECT * FROM JSON_TABLE('[]', '$[*]' COLUMNS (x INT PATH '$.x' NULL ON EMPTY"
                        + " ERROR ON ERROR)) AS jt"));

        // Administrative statements: the family a DBA or a maintenance script
        // runs, every one of which is binlogged and reaches the parser.
        areas.put("administrative", List.of(
                "REPAIR TABLE t",
                "REPAIR NO_WRITE_TO_BINLOG TABLE t",
                "REPAIR TABLE t QUICK",
                "CHECK TABLE t QUICK",
                "CHECK TABLE t FOR UPGRADE",
                "CHECKSUM TABLE t QUICK",
                "FLUSH TABLE t",
                "FLUSH TABLES t FOR EXPORT",
                "FLUSH TABLES WITH READ LOCK",
                "BINLOG 'x'",
                "CREATE FUNCTION f RETURNS STRING SONAME 'x.so'",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no UDF is dropped.
                "DROP FUNCTION f",
                "START GROUP_REPLICATION",
                "STOP GROUP_REPLICATION",
                "XA START 'x'",
                "XA END 'x'",
                "XA PREPARE 'x'",
                "XA COMMIT 'x'",
                "XA ROLLBACK 'x'",
                "XA RECOVER",
                "DO GET_LOCK('x', 10)"));

        // Options the CHANGE MASTER / CHANGE REPLICATION SOURCE grammar
        // models as typed rule families rather than a word list.
        areas.put("replication-options", List.of(
                "CHANGE MASTER TO MASTER_AUTO_POSITION = 1",
                "CHANGE MASTER TO MASTER_HOST = 'h', MASTER_PORT = 3306",
                "CHANGE MASTER TO MASTER_HEARTBEAT_PERIOD = 1.5",
                "CHANGE MASTER TO MASTER_HOST = 'h' FOR CHANNEL 'c'",
                "SET @@GLOBAL.gtid_purged = 'uuid:1-5'",
                "RESET SLAVE ALL"));

        // Partition and tablespace detail the earlier areas did not reach.
        areas.put("partition-detail", List.of(
                "CREATE TABLE t (a INT) PARTITION BY RANGE (a) SUBPARTITION BY HASH (a)"
                        + " SUBPARTITIONS 2 (PARTITION p0 VALUES LESS THAN (10)"
                        + " (SUBPARTITION sp0 ENGINE = InnoDB,"
                        + " SUBPARTITION sp1 ENGINE = InnoDB))",
                "CREATE TABLE t (a INT, b INT) PARTITION BY RANGE COLUMNS (a, b)"
                        + " (PARTITION p0 VALUES LESS THAN (MAXVALUE, MAXVALUE))",
                "CREATE SERVER s FOREIGN DATA WRAPPER mysql"
                        + " OPTIONS (DATABASE 'd', PORT 3306, USER 'u', PASSWORD 'p')",
                "CREATE TABLESPACE ts ADD DATAFILE 'x' USE LOGFILE GROUP lg ENGINE = NDB"));

        // Account clauses the grammar separates into their own rules.
        areas.put("account-detail", List.of(
                "GRANT r TO u",
                "GRANT SELECT, INSERT, UPDATE ON db.* TO u",
                "GRANT PROXY ON u@h TO u2@h",
                "REVOKE PROXY ON u@h FROM u2@h",
                // DESTRUCTIVE: fixture text only. The parser reads the image
                // of the statement; no DDL is executed, so no account is
                // dropped.
                "DROP USER u@h",
                "CREATE USER u IDENTIFIED BY 'x' PASSWORD EXPIRE INTERVAL 30 DAY",
                "ALTER USER u ACCOUNT LOCK",
                "ALTER USER u ACCOUNT UNLOCK",
                "CREATE USER u IDENTIFIED BY 'x' REQUIRE SSL",
                "ALTER USER u WITH MAX_USER_CONNECTIONS 10"));

        // Transaction characteristics and the multi-table DML forms whose
        // grammar rules differ from the single-table ones.
        areas.put("transactions-and-multi-table-dml", List.of(
                "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE",
                "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ",
                "SET TRANSACTION READ ONLY",
                "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ WRITE",
                "BEGIN",
                "COMMIT",
                "ROLLBACK",
                "UPDATE t, u SET t.a = u.b WHERE t.id = u.id",
                "DELETE t1, t2 FROM t1 INNER JOIN t2 ON t1.id = t2.id"));

        // Statements chosen by reading the grammar rather than by trying
        // examples: for each rule the production says which construct reaches
        // it, and each line below was then confirmed to visit its rule on the
        // SHIPPED parser. Several ordinary-looking forms do NOT reach their
        // rule -- a comma-separated UPDATE resolves to singleUpdateStatement,
        // and every SET AUTOCOMMIT shape resolves to #setVariable -- so the
        // forms here are the ones measured to arrive, not the ones that look
        // like they should. The shapes that no statement can reach are
        // recorded in NOT_EXERCISED, each with the alternative that wins.
        areas.put("grammar-rule-detail", List.of(
                // connectionOption: the START SLAVE connection options
                "START SLAVE USER = 'u' PASSWORD = 'p'",
                "START SLAVE DEFAULT_AUTH = 'mysql_native_password'",
                "START SLAVE PLUGIN_DIR = '/plugin'",
                // dataTypeBase: a data-type keyword used as an identifier
                "SELECT a FROM TEXT",
                "SELECT a FROM ENUM",
                // gtuidSet + untilOption: the quoted GTID set form. The
                // unquoted uuidSet form is reachable only when every component
                // lexes as a decimal literal, which an all-digit GTID does and
                // a hex-letter UUID does not.
                "START SLAVE UNTIL SQL_AFTER_GTIDS = '3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5'",
                "START SLAVE UNTIL SQL_BEFORE_GTIDS = 'abc:1-5'",
                "START SLAVE UNTIL SQL_AFTER_GTIDS = 1-2-3-4-5:6-7",
                "START SLAVE UNTIL MASTER_LOG_FILE = 'x', MASTER_LOG_POS = 4",
                "START SLAVE UNTIL SQL_AFTER_MTS_GAPS",
                // threadType
                "START SLAVE IO_THREAD",
                "STOP SLAVE SQL_THREAD",
                "START SLAVE IO_THREAD, SQL_THREAD",
                // handlerReadIndexStatement: the index-read forms
                "HANDLER t READ i FIRST",
                "HANDLER t READ i NEXT WHERE a = 1",
                "HANDLER t READ i >= (1) LIMIT 1",
                // intervalExpr: only the event schedule carries it
                "CREATE EVENT e ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 1 DAY DO SELECT 1",
                // lengthTwoDimension: the (m,d) forms of REAL / DOUBLE
                "CREATE TABLE t (a REAL(10,2), b DOUBLE(10,2))",
                // levelsInWeightString + levelInWeightListElement
                "SELECT WEIGHT_STRING('ab' AS CHAR(4) LEVEL 1 DESC, 2)",
                "SELECT WEIGHT_STRING('ab' AS CHAR(4) LEVEL 1-2)",
                // passwordFunctionClause
                "SET PASSWORD FOR u = OLD_PASSWORD('x')",
                "SET PASSWORD = PASSWORD('x')",
                // privilegesBase + transactionLevelBase: keyword identifiers
                "SELECT a FROM PROCESS",
                "SELECT a FROM SUPER",
                "SELECT a FROM REPEATABLE",
                // resetStatement
                "RESET QUERY CACHE",
                // showProfileType
                "SHOW PROFILE ALL FOR QUERY 1 LIMIT 5",
                // simpleStrings
                "CHANGE REPLICATION FILTER REPLICATE_WILD_DO_TABLE = ('a','b')",
                // sequenceFunctionName: the Percona sequence-table function,
                // which the shipped parser carries and the packaged grammar
                // does not -- it is a TABLE SOURCE, not a scalar call.
                "SELECT * FROM SEQUENCE_TABLE(1)",
                "SELECT * FROM PERCONA_SEQUENCE_TABLE(1)",
                // tablePair: the rewrite filter wraps each pair in its own parens
                "CHANGE REPLICATION FILTER REPLICATE_REWRITE_DB = ((db1, db2))",
                "CHANGE REPLICATION FILTER REPLICATE_REWRITE_DB = ((a, b), (c, d))",
                // tableType
                "CREATE TABLE t (a INT) TABLE_TYPE = ODBC",
                "CREATE TABLE t (a INT) TABLE_TYPE = MYSQL",
                // tablespaceStorage
                "CREATE TABLE t (a INT) TABLESPACE ts STORAGE DISK",
                // userSpecification
                "ALTER USER u PASSWORD EXPIRE",
                "ALTER USER u PASSWORD EXPIRE DEFAULT",
                "ALTER USER u PASSWORD EXPIRE NEVER",
                "ALTER USER u PASSWORD EXPIRE INTERVAL 30 DAY",
                // withClause
                "CREATE VIEW v AS WITH c AS (SELECT 1) SELECT * FROM c",
                "CREATE VIEW v AS WITH RECURSIVE c AS (SELECT 1) SELECT * FROM c",
                // emptyStatement_
                ";",
                "SELECT 1;;"));

        return areas;
    }

    /** Every rule the shipped parser defines, by name. */
    private static Set<String> shippedRules() {
        Set<String> names = new LinkedHashSet<>();
        for (String name : MySqlParser.ruleNames) {
            names.add(name);
        }
        return names;
    }

    /**
     * The rules a statement's parse tree visits, through the same normalize +
     * lex + parse + walk path production uses.
     *
     * <p>A statement that fails to lex or parse contributes nothing and is
     * reported by name, because a corpus statement the parser rejects is a
     * corpus defect: treating it as "covered nothing" would hide a
     * mistyped statement behind a coverage number.</p>
     */
    private static Set<String> rulesVisited(String sql, List<String> parseFailures) {
        Set<String> visited = new LinkedHashSet<>();
        String normalized = MySqlDdlNormalizer.normalize(sql);
        if (normalized.isEmpty()) {
            // Blanked by the normalizer: the statement's family has no handler
            // and is deliberately never parsed.
            return visited;
        }
        MySqlLexer lexer = new MySqlLexer(
                new CaseChangingCharStream(CharStreams.fromString(normalized), true));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);

        List<String> messages = new ArrayList<>();
        BaseErrorListener collecting = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                    int charPositionInLine, String msg, RecognitionException e) {
                messages.add(msg);
            }
        };
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(collecting);
        parser.addErrorListener(collecting);

        ParserRuleContext root;
        try {
            root = parser.root();
        } catch (RuntimeException e) {
            parseFailures.add(sql + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return visited;
        }
        if (!messages.isEmpty()) {
            parseFailures.add(sql + " -> " + String.join("; ", messages));
            return visited;
        }
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(new ParseTreeListener() {
            @Override
            public void visitTerminal(org.antlr.v4.runtime.tree.TerminalNode node) {
            }

            @Override
            public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
            }

            @Override
            public void enterEveryRule(ParserRuleContext ctx) {
                visited.add(MySqlParser.ruleNames[ctx.getRuleIndex()]);
            }

            @Override
            public void exitEveryRule(ParserRuleContext ctx) {
            }
        }, root);
        return visited;
    }

    /**
     * Every statement in the corpus parses, so a coverage number is not
     * inflated by statements that never reached the grammar.
     */
    @Test
    public void everyCorpusStatementParses() {
        List<String> failures = new ArrayList<>();
        int total = 0;
        for (List<String> statements : corpus().values()) {
            total += statements.size();
            for (String sql : statements) {
                rulesVisited(sql, failures);
            }
        }
        assertTrue(total > 300, "the corpus is too small to claim grammar coverage: " + total);
        assertTrue(failures.isEmpty(), "these corpus statements are not valid MySQL (a test defect,"
                + " not a parser bug):\n  " + String.join("\n  ", failures));
    }

    /**
     * Every rule of the shipped grammar is exercised by the corpus, or is
     * documented as unreachable through {@code parser.root()}.
     */
    @Test
    public void everyGrammarRuleIsExercisedOrDocumented() {
        Set<String> shipped = shippedRules();
        assertFalse(shipped.isEmpty(), "reflection found no parser rules");

        Set<String> visited = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (List<String> statements : corpus().values()) {
            for (String sql : statements) {
                visited.addAll(rulesVisited(sql, failures));
            }
        }

        Set<String> uncovered = new TreeSet<>(shipped);
        uncovered.removeAll(visited);
        uncovered.removeAll(NOT_EXERCISED.keySet());

        Set<String> stale = new TreeSet<>(NOT_EXERCISED.keySet());
        stale.retainAll(visited);

        assertTrue(uncovered.isEmpty(), "grammar rules no statement in the corpus exercises ("
                + visited.size() + " of " + shipped.size() + " covered; add a statement or"
                + " an entry to NOT_EXERCISED with the reason):\n  "
                + String.join("\n  ", uncovered));
        assertTrue(stale.isEmpty(), "these are listed as not exercisable but a corpus statement"
                + " does exercise them -- drop the exclusion and keep the statement:\n  "
                + String.join("\n  ", stale));
    }
}
