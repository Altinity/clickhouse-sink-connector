import re

from testflows.core import *

from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *

# The DML rules of the MySQL grammar, derived from the parser the connector
# SHIPS rather than from the grammar file in this repo (the checked-in
# MySqlParser.g4 is stale). Enumerate them with
# `probe/scratch-grammar/dml_rules.py`, which reflects
# debezium-ddl-parser-3.1.3.Final with javap.
#
# Note which rules do NOT exist in the shipped grammar, because this header
# used to document them as if they did:
#
#   setFirst   ABSENT -- INSERT ... SET is the second alternative of
#              insertStatement itself (INSERT ... SET col = val), not a
#              separate rule.
#   partitions ABSENT -- INSERT / REPLACE spell the partition list inline as
#              PARTITION '(' uidList ')'. Only DELETE has a `partitions`-like
#              list, and it too spells it inline.
#
# The shipped rules, with their alternatives:
#
#   insertStatement          INSERT priority? IGNORE? INTO? tableName (AS? uid)?
#                            (PARTITION ( uidList ))?
#                            ( ( '(' fullColumnNameList ')' )? insertStatementValue
#                            | SET updatedElement (',' ...)* )
#                            ( ON DUPLICATE KEY UPDATE updatedElement (',' ...)* )?
#   replaceStatement         REPLACE priority? INTO? tableName
#                            (PARTITION ( uidList ))?
#                            ( ( '(' ... ')' )? insertStatementValue | SET ... )
#   insertStatementValue     selectStatement
#                          | (VALUES | VALUE) '(' expressionsWithDefaults ')'
#                            (',' '(' ... ')')*
#   updatedElement           fullColumnName '=' (expression | DEFAULT)
#   singleUpdateStatement    UPDATE priority? IGNORE? tableName (AS? uid)?
#                            SET updatedElement (',' ...)* (WHERE expression)?
#                            orderByClause? limitClause?
#   multipleUpdateStatement  UPDATE priority? IGNORE? tableSources
#                            SET updatedElement (',' ...)* (WHERE expression)?
#   singleDeleteStatement    DELETE priority? QUICK? IGNORE? FROM tableName
#                            (AS? uid)? (PARTITION ( uidList ))? (WHERE expression)?
#                            orderByClause? (LIMIT limitClauseAtom)?
#   multipleDeleteStatement  DELETE priority? QUICK? IGNORE?
#                            ( tableName ('.' '*')? (',' ...)* FROM tableSources
#                            | FROM tableName ('.' '*')? (',' ...)* USING tableSources )
#                            (WHERE expression)?
#   tableSource              tableSourceBase (AS? uid)? (indexHint ...)*
#                          | tableSourceNested (AS? uid)?
#                          | tableJson
#
# DML never reaches the DDL listener: the connector consumes INSERT / UPDATE /
# DELETE / REPLACE as binlog row events, so no DDL-side handler ever sees the
# statement text. A DML shape is therefore proven only end to end -- the
# statement runs on MySQL and the resulting row must arrive in ClickHouse. That
# is what this module does, one matrix row per rule and alternative.

# Columns every case can address. `id` is the key and is not nullable, so a
# row always names it; everything else is nullable so a partial-column form
# (ON DUPLICATE KEY, SET, DEFAULT) is valid MySQL.
BASE_COLUMNS = "c_int INT, c_str VARCHAR(20), c_def INT DEFAULT 7"

# A table with two rows already present, so a statement that must affect an
# existing row has one to affect.
SEED_ROWS = "(1, 10, 'one', 7), (2, 20, 'two', 7)"

# The id of the row written after a matrix row's statements (see the driver).
LAST_ROW_ID = 4999


@TestStep(Given)
def replicated_table(self, table_name, context=None, columns=BASE_COLUMNS,
                     partition_by_mysql=False, key="id"):
    """A replicated MySQL table in `test`, empty, with the ClickHouse twin up.

    ``self`` is required: the framework runs a step as
    ``self.func(current(), **args)``, so the current test object is the FIRST
    POSITIONAL argument. Without it the test lands in ``table_name`` and the
    call site's ``table_name=`` keyword raises
    ``TypeError: got multiple values for argument 'table_name'``.
    """
    if context is None:
        context = self.context
    clickhouse = context.cluster.node("clickhouse")
    mysql = context.cluster.node("mysql-master")

    with Given("I create a replicated table", description=table_name):
        create_mysql_table(
            table_name=table_name,
            columns=columns,
            primary_key=key,
            partition_by_mysql=partition_by_mysql,
        )
        # One row forces the connector to create the ClickHouse table, so every
        # assertion below has a target that already exists.
        mysql.query(f"INSERT INTO test.`{table_name}` (id) VALUES (0);")
        retry(clickhouse.query, timeout=120, delay=5)(
            f"EXISTS test.`{table_name}`", message="1"
        )


@TestStep(Then)
def row_count_matches(self, table_name, context=None):
    """The ClickHouse row count equals MySQL's, after RMT collapse."""
    if context is None:
        context = self.context
    clickhouse = context.cluster.node("clickhouse")
    mysql = context.cluster.node("mysql-master")

    with Then("I check ClickHouse holds exactly the rows MySQL holds"):
        # Retried because replication is asynchronous: the counts converge.
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                # Compared as integers, never as text: the framework's
                # `message=` is a SUBSTRING test, so a count of 2 would also
                # accept 12.
                expected = int(mysql_scalar(
                    mysql, f"SELECT count(*) FROM test.`{table_name}`"))
                actual = int(clickhouse.query(
                    f"SELECT count() FROM test.`{table_name}` FINAL").output.strip())
                assert actual == expected, (
                    f"ClickHouse holds {actual} rows, MySQL holds {expected}"
                )


@TestStep(Then)
def cell_matches(self, statement, context=None):
    """One expression is evaluated on both engines and must agree.

    The statement is written for MySQL. On ClickHouse the table is a
    ReplacingMergeTree, which keeps every version of a row and every deleted
    row until a merge, so the same statement reads the table with FINAL: an
    UPDATE would otherwise answer with both the old and the new value, and a
    count would include rows MySQL no longer holds.
    """
    if context is None:
        context = self.context
    clickhouse = context.cluster.node("clickhouse")
    mysql = context.cluster.node("mysql-master")

    collapsed = re.sub(
        r"(\bFROM\s+test\.(?:`[^`]+`|\w+))", r"\1 FINAL", statement, count=1)
    assert collapsed != statement, f"no test.<table> to read with FINAL in: {statement}"

    with Then("I check the two engines agree on the value", description=statement):
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                expected = mysql_scalar(mysql, statement)
                actual = clickhouse.query(f"{collapsed} FORMAT TSVRaw").output.strip()
                assert cells_agree(expected, actual), (
                    f"{statement} returned {actual!r} on ClickHouse and "
                    f"{expected!r} on MySQL"
                )


def row(name, statements, checks=(), **kwargs):
    """One matrix row: the table setup, the statements to run, and the checks."""
    entry = {"name": name, "statements": statements, "checks": checks}
    entry.update(kwargs)
    return entry


def matrix_rows():
    """Every DML shape the MySQL grammar defines, one row per alternative."""
    s = getuid()
    seeded = f"INSERT INTO test.`{{table}}` VALUES {SEED_ROWS};"
    return [
        # --- insertStatementValue: VALUES / VALUE with one and many tuples --
        row(
            f"d01_{s}",
            ["INSERT INTO test.`{table}` (id, c_int, c_str) VALUES (1, 10, 'a');"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d02_{s}",
            ["INSERT INTO test.`{table}` (id, c_int, c_str) VALUES (1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c');"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d03_{s}",
            ["INSERT INTO test.`{table}` (id, c_int) VALUE (1, 10);"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # --- insertStatement: the priority and IGNORE modifiers ------------
        row(
            f"d04_{s}",
            ["INSERT LOW_PRIORITY INTO test.`{table}` (id, c_int) VALUES (1, 10);"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d05_{s}",
            ["INSERT HIGH_PRIORITY INTO test.`{table}` (id, c_int) VALUES (1, 10);"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d06_{s}",
            [seeded, "INSERT IGNORE INTO test.`{table}` (id, c_int) VALUES (2, 99);"],
            ["SELECT count(*) FROM test.`{table}`",
             "SELECT c_int FROM test.`{table}` WHERE id = 2"],
        ),
        row(
            f"d07_{s}",
            ["INSERT INTO test.`{table}` SET id = 1, c_int = 10, c_str = 'set';"],
            ["SELECT c_str FROM test.`{table}` WHERE id = 1"],
        ),
        # --- insertStatementValue: a SELECT as the value source ------------
        row(
            f"d08_{s}",
            [
                "CREATE TABLE IF NOT EXISTS test.src_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
                "INSERT INTO test.src_{table} VALUES (1, 10);",
                "INSERT INTO test.`{table}` (id, c_int) SELECT id, c_int FROM test.src_{table};",
            ],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        # --- insertStatement: ON DUPLICATE KEY UPDATE ----------------------
        row(
            f"d09_{s}",
            [
                seeded,
                "INSERT INTO test.`{table}` (id, c_int) VALUES (1, 111) "
                "ON DUPLICATE KEY UPDATE c_int = VALUES(c_int);",
            ],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT count(*) FROM test.`{table}`"],
        ),
        # --- insertStatement: PARTITION ( ... ) ----------------------------
        # MySQL rejects a row routed to a partition it does not belong in
        # (ERROR 1748), so the row names an id above the p0 boundary.
        row(
            f"d10_{s}",
            ["INSERT INTO test.`{table}` PARTITION (p1) (id, c_int) VALUES (2000, 10);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 2000"],
            partition_by_mysql=(
                "RANGE (id) (PARTITION p0 VALUES LESS THAN (1000), "
                "PARTITION p1 VALUES LESS THAN (5000))"
            ),
        ),
        # --- replaceStatement: every alternative ---------------------------
        row(
            f"d11_{s}",
            [seeded, "REPLACE INTO test.`{table}` (id, c_int) VALUES (1, 111);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d12_{s}",
            [seeded, "REPLACE INTO test.`{table}` SET id = 1, c_int = 111;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d13_{s}",
            [seeded, "REPLACE LOW_PRIORITY INTO test.`{table}` (id, c_int) VALUES (1, 111);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d14_{s}",
            [seeded, "REPLACE INTO test.`{table}` (id, c_int) VALUES (1, 111), (3, 30);"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # --- singleUpdateStatement ----------------------------------------
        row(
            f"d15_{s}",
            [seeded, "UPDATE test.`{table}` SET c_int = 99 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d16_{s}",
            [seeded, "UPDATE test.`{table}` SET c_int = 99, c_str = 'both' WHERE id = 1;"],
            ["SELECT c_str FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d17_{s}",
            [seeded, "UPDATE LOW_PRIORITY test.`{table}` SET c_int = 99 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d18_{s}",
            [seeded, "UPDATE IGNORE test.`{table}` SET c_int = 99 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d19_{s}",
            [seeded, "UPDATE test.`{table}` AS t SET t.c_int = 99 WHERE t.id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d20_{s}",
            [seeded, "UPDATE test.`{table}` SET c_int = 99 ORDER BY id LIMIT 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d21_{s}",
            [seeded, "UPDATE test.`{table}` SET c_def = DEFAULT WHERE id = 1;"],
            ["SELECT c_def FROM test.`{table}` WHERE id = 1"],
        ),
        row(
            f"d22_{s}",
            [seeded, "UPDATE test.`{table}` SET c_int = c_int + 1 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        # --- singleUpdateStatement with no WHERE (every row) ---------------
        row(
            f"d23_{s}",
            [seeded, "UPDATE test.`{table}` SET c_int = 77;"],
            ["SELECT count(*) FROM test.`{table}` WHERE c_int = 77"],
        ),
        # --- multipleUpdateStatement --------------------------------------
        # The joined table must hold the row the UPDATE is meant to touch, so
        # its INSERT is a statement of this row like any other.
        row(
            f"d24_{s}",
            [
                "CREATE TABLE IF NOT EXISTS test.mu_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
                "INSERT INTO test.mu_{table} VALUES (1, 0);",
                seeded,
                "UPDATE test.`{table}`, test.mu_{table} SET test.`{table}`.c_int = 55 "
                "WHERE test.`{table}`.id = test.mu_{table}.id;",
            ],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        # --- singleDeleteStatement ----------------------------------------
        row(
            f"d25_{s}",
            [seeded, "DELETE FROM test.`{table}` WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d26_{s}",
            [seeded, "DELETE LOW_PRIORITY FROM test.`{table}` WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d27_{s}",
            [seeded, "DELETE QUICK FROM test.`{table}` WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d28_{s}",
            [seeded, "DELETE IGNORE FROM test.`{table}` WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d29_{s}",
            [seeded, "DELETE FROM test.`{table}` WHERE id = 1 ORDER BY id LIMIT 1;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # A row in p1, so the partition-targeted DELETE has something to delete.
        row(
            f"d30_{s}",
            [
                "INSERT INTO test.`{table}` (id, c_int) VALUES (2000, 10);",
                "DELETE FROM test.`{table}` PARTITION (p1) WHERE id = 2000;",
            ],
            ["SELECT count(*) FROM test.`{table}`"],
            partition_by_mysql=(
                "RANGE (id) (PARTITION p0 VALUES LESS THAN (1000), "
                "PARTITION p1 VALUES LESS THAN (5000))"
            ),
        ),
        row(
            f"d31_{s}",
            [seeded, "DELETE FROM test.`{table}`;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # --- multipleDeleteStatement --------------------------------------
        row(
            f"d32_{s}",
            [
                seeded,
                "CREATE TABLE IF NOT EXISTS test.md_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
                "INSERT INTO test.md_{table} VALUES (1, 0);",
                "DELETE test.`{table}`, test.md_{table} FROM test.`{table}` "
                "INNER JOIN test.md_{table} ON test.`{table}`.id = test.md_{table}.id "
                "WHERE test.`{table}`.id = 1;",
            ],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        row(
            f"d33_{s}",
            [
                seeded,
                "CREATE TABLE IF NOT EXISTS test.mu2_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
                "INSERT INTO test.mu2_{table} VALUES (1, 0);",
                "DELETE FROM test.`{table}` USING test.`{table}` "
                "INNER JOIN test.mu2_{table} ON test.`{table}`.id = test.mu2_{table}.id "
                "WHERE test.`{table}`.id = 1;",
            ],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # --- multi-table delete naming the target with .* -----------------
        row(
            f"d34_{s}",
            [
                seeded,
                "CREATE TABLE IF NOT EXISTS test.mu3_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
                "INSERT INTO test.mu3_{table} VALUES (1, 0);",
                "DELETE test.`{table}`.*, test.mu3_{table}.* FROM test.`{table}` "
                "INNER JOIN test.mu3_{table} ON test.`{table}`.id = test.mu3_{table}.id;",
            ],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # --- INSERT ... AS <row alias> (MySQL 8.0.19+) ---------------------
        # The row alias follows the VALUES list, not the table name: MySQL's
        # documented form is `VALUES (...) AS new ON DUPLICATE KEY UPDATE`.
        # It gives ON DUPLICATE KEY UPDATE a way to name the row being
        # inserted without the deprecated VALUES() function.
        row(
            f"d35_{s}",
            [
                seeded,
                "INSERT INTO test.`{table}` (id, c_int) VALUES (1, 111) AS new "
                "ON DUPLICATE KEY UPDATE c_int = new.c_int;",
            ],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT count(*) FROM test.`{table}`"],
        ),
        # --- DEFAULT inside a VALUES list ----------------------------------
        # insertStatementValue is `VALUES '(' expressionsWithDefaults ')'`:
        # the DEFAULT keyword is legal in the value list, not only in SET.
        row(
            f"d36_{s}",
            ["INSERT INTO test.`{table}` (id, c_int, c_def) VALUES (1, 10, DEFAULT);"],
            ["SELECT c_def FROM test.`{table}` WHERE id = 1"],
        ),
        # --- INSERT ... SET with several assignments -----------------------
        # The SET alternative of insertStatement accepts a comma list; d07
        # covers only the single-assignment form.
        row(
            f"d37_{s}",
            ["INSERT INTO test.`{table}` SET id = 1, c_int = 10, c_str = 'multi';"],
            ["SELECT c_str FROM test.`{table}` WHERE id = 1",
             "SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        # --- REPLACE ... PARTITION ( ... ) --------------------------------
        # replaceStatement has the same inline PARTITION list as INSERT.
        row(
            f"d38_{s}",
            [
                "INSERT INTO test.`{table}` (id, c_int) VALUES (2000, 10);",
                "REPLACE INTO test.`{table}` PARTITION (p1) (id, c_int) VALUES (2000, 111);",
            ],
            ["SELECT c_int FROM test.`{table}` WHERE id = 2000"],
            partition_by_mysql=(
                "RANGE (id) (PARTITION p0 VALUES LESS THAN (1000), "
                "PARTITION p1 VALUES LESS THAN (5000))"
            ),
        ),
        # --- REPLACE with a SELECT as the value source --------------------
        row(
            f"d39_{s}",
            [
                "CREATE TABLE IF NOT EXISTS test.rs_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
                "INSERT INTO test.rs_{table} VALUES (1, 111);",
                "REPLACE INTO test.`{table}` (id, c_int) SELECT id, c_int FROM test.rs_{table};",
            ],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        # --- DELETE ... AS <alias> ----------------------------------------
        # singleDeleteStatement accepts `AS? uid` after the table name.
        row(
            f"d40_{s}",
            [seeded, "DELETE FROM test.`{table}` AS d WHERE d.id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # --- nested table source in multipleUpdateStatement ----------------
        # tableSource's third alternative is tableSourceNested -- a
        # parenthesised join, which d24 does not use.
        row(
            f"d41_{s}",
            [
                seeded,
                "CREATE TABLE IF NOT EXISTS test.nu_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
                "INSERT INTO test.nu_{table} VALUES (1, 0);",
                "UPDATE (test.`{table}` INNER JOIN test.nu_{table} "
                "ON test.`{table}`.id = test.nu_{table}.id) "
                "SET test.`{table}`.c_int = 55;",
            ],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"],
        ),
        # --- UPDATE ... SET with several updatedElements and ORDER BY/LIMIT -
        # d16 covers two assignments without ORDER BY; d20 covers ORDER BY
        # with one. This is the full singleUpdateStatement tail at once.
        row(
            f"d42_{s}",
            [
                seeded,
                "UPDATE test.`{table}` SET c_int = 99, c_str = 'tail' "
                "WHERE c_int > 0 ORDER BY id DESC LIMIT 1;",
            ],
            ["SELECT count(*) FROM test.`{table}` WHERE c_str = 'tail'"],
        ),
        # --- DELETE with ORDER BY but no LIMIT ----------------------------
        row(
            f"d43_{s}",
            [seeded, "DELETE FROM test.`{table}` WHERE id > 0 ORDER BY id;"],
            ["SELECT count(*) FROM test.`{table}`"],
        ),
        # --- UPDATE ... SET <col> = DEFAULT on several columns ------------
        row(
            f"d44_{s}",
            [seeded, "UPDATE test.`{table}` SET c_int = DEFAULT, c_def = DEFAULT WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT c_def FROM test.`{table}` WHERE id = 1"],
        ),
        # --- multi-row INSERT where one row uses DEFAULT -------------------
        # expressionsWithDefaults appears once per tuple, so the per-tuple
        # form must be honoured tuple by tuple.
        row(
            f"d45_{s}",
            ["INSERT INTO test.`{table}` (id, c_int, c_def) VALUES "
             "(1, 10, DEFAULT), (2, 20, 99), (3, 30, DEFAULT);"],
            ["SELECT c_def FROM test.`{table}` WHERE id = 2",
             "SELECT count(*) FROM test.`{table}`"],
        ),

        # --- the optional modifiers each DML clause exposes ----------------
        # One row per optional clause of insertStatement / replaceStatement /
        # singleUpdateStatement / singleDeleteStatement / multipleDeleteStatement,
        # derived by sweeping the accessors javap reports on those contexts in
        # the parser the connector ships. The unit suite pins that each parses;
        # these prove the rows still arrive.
        row(f"d46_{s}",
            ["INSERT INTO test.`{table}` (id, c_int) VALUES (1, 10) AS new(x, y) "
             "ON DUPLICATE KEY UPDATE c_int = y;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d47_{s}",
            ["INSERT LOW_PRIORITY INTO test.`{table}` (id, c_int) VALUES (1, 11);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d48_{s}",
            ["INSERT HIGH_PRIORITY INTO test.`{table}` (id, c_int) VALUES (1, 11);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d49_{s}",
            ["INSERT IGNORE INTO test.`{table}` (id, c_int) VALUES (1, 11);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d50_{s}",
            ["REPLACE LOW_PRIORITY INTO test.`{table}` (id, c_int) VALUES (1, 12);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d51_{s}", [seeded, "UPDATE LOW_PRIORITY test.`{table}` SET c_int = 13 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d52_{s}", [seeded, "UPDATE IGNORE test.`{table}` SET c_int = 14 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d53_{s}", [seeded, "UPDATE test.`{table}` AS x SET x.c_int = 15 WHERE x.id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d54_{s}",
            [seeded,
             "CREATE TABLE IF NOT EXISTS test.uj_{table} (id INT NOT NULL PRIMARY KEY, c_int INT);",
             "INSERT INTO test.uj_{table} VALUES (1, 16);",
             "UPDATE test.`{table}` INNER JOIN test.uj_{table} "
             "ON test.`{table}`.id = test.uj_{table}.id SET test.`{table}`.c_int = test.uj_{table}.c_int;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d55_{s}", [seeded, "DELETE LOW_PRIORITY FROM test.`{table}` WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"]),
        row(f"d56_{s}", [seeded, "DELETE QUICK FROM test.`{table}` WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"]),
        row(f"d57_{s}", [seeded, "DELETE IGNORE FROM test.`{table}` WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"]),
        row(f"d58_{s}", [seeded, "DELETE FROM test.`{table}` WHERE id = 1 LIMIT 10;"],
            ["SELECT count(*) FROM test.`{table}`"]),

        # --- INSERT ... SET with a row alias (MySQL 8.0.19+) -----------------
        # The alias follows the whole assignment list. The shipped grammar puts
        # `AS? uid` only after a VALUES list, so this form reached the parser
        # as a syntax error; MySqlDdlNormalizer drops the clause.
        row(f"d59_{s}", [seeded,
            "INSERT INTO test.`{table}` SET id = 1, c_int = 222 AS new "
            "ON DUPLICATE KEY UPDATE c_int = new.c_int"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT count(*) FROM test.`{table}`"]),
        row(f"d60_{s}", [seeded,
            # One alias per assigned column: MySQL refuses a column-alias
            # list shorter than the SET list (ERROR 1353).
            "INSERT INTO test.`{table}` SET id = 1, c_int = 223 AS new(n, m) "
            "ON DUPLICATE KEY UPDATE c_int = m"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT count(*) FROM test.`{table}`"]),

        # --- VALUES ROW(...) row constructors (MySQL 8.0.19+) -----------------
        # Both the INSERT form and the standalone VALUES statement; the shipped
        # grammar has no ROW keyword in either values list.
        row(f"d61_{s}", [seeded,
            "INSERT INTO test.`{table}` (id, c_int) VALUES ROW(3, 333), ROW(4, 444)"],
            ["SELECT count(*) FROM test.`{table}`",
             "SELECT c_int FROM test.`{table}` WHERE id = 3"]),
        row(f"d62_{s}", [seeded,
            "INSERT INTO test.`{table}` (id, c_int) VALUES ROW(5, 555)"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 5",
             "SELECT count(*) FROM test.`{table}`"]),

        # --- NULL is the shape the prime directive turns on ----------------
        # Every case here compares against MySQL's own value, so a NULL that
        # ClickHouse replaced with its column DEFAULT (0, '' or 7 for c_def)
        # is a value mismatch, not a row-count miss.
        row(f"d63_{s}",
            ["INSERT INTO test.`{table}` (id, c_int, c_str) VALUES (1, NULL, NULL);"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT c_str FROM test.`{table}` WHERE id = 1"]),
        row(f"d64_{s}", [seeded,
            "UPDATE test.`{table}` SET c_int = NULL, c_str = NULL WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT c_str FROM test.`{table}` WHERE id = 1"]),
        # A NULL written over a NOT NULL column's defaulted value.
        row(f"d65_{s}", [seeded,
            "UPDATE test.`{table}` SET c_def = NULL WHERE id = 1;"],
            ["SELECT c_def FROM test.`{table}` WHERE id = 1"]),
        # NULL in several columns of a new row at once.
        row(f"d66_{s}", [seeded,
            "INSERT INTO test.`{table}` (id, c_int, c_str, c_def) "
            "VALUES (3, NULL, NULL, NULL);"],
            ["SELECT count(*) FROM test.`{table}`",
             "SELECT c_int FROM test.`{table}` WHERE id = 3"]),
        # --- empty and boundary values -------------------------------------
        row(f"d67_{s}", [seeded, "UPDATE test.`{table}` SET c_str = '' WHERE id = 1;"],
            ["SELECT c_str FROM test.`{table}` WHERE id = 1"]),
        row(f"d68_{s}", [seeded, "UPDATE test.`{table}` SET c_int = 0 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d69_{s}", [seeded,
            "UPDATE test.`{table}` SET c_int = -2147483648 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        row(f"d70_{s}", [seeded,
            "UPDATE test.`{table}` SET c_int = 2147483647 WHERE id = 1;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1"]),
        # A string that needs escaping on the ClickHouse side.
        row(f"d71_{s}", [seeded,
            "UPDATE test.`{table}` SET c_str = 'a''b' WHERE id = 1;"],
            ["SELECT c_str FROM test.`{table}` WHERE id = 1"]),
        # A multi-byte string, so a byte/character mix-up in the writer shows.
        # Compared as bytes, HEX() on both engines: the MySQL client prints a
        # value in its session character set, and when that is not the
        # column's (the suite's client session is latin1) the printed text is
        # not what the column holds. ClickHouse must hold the column's bytes.
        row(f"d72_{s}", [seeded,
            "UPDATE test.`{table}` SET c_str = 'éàü' WHERE id = 1;"],
            ["SELECT HEX(c_str) FROM test.`{table}` WHERE id = 1"]),

        # --- priority modifiers the grammar allows on INSERT and REPLACE -----
        # `priority=(LOW_PRIORITY | DELAYED | HIGH_PRIORITY)` on insertStatement
        # and `(LOW_PRIORITY | DELAYED)` on replaceStatement. LOW_PRIORITY and
        # HIGH_PRIORITY were already exercised above; DELAYED was not, on
        # either statement. MySQL 8 accepts the keyword and ignores it for
        # InnoDB (the DELAYED insert handler was removed in 5.7), so the
        # statement runs and the row must still replicate.
        row(f"d73_{s}", [seeded,
            "INSERT DELAYED INTO test.`{table}` (id, c_int, c_str) VALUES (3, 30, 'three');"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 3",
             "SELECT count(*) FROM test.`{table}`"]),
        row(f"d74_{s}", [seeded,
            "REPLACE DELAYED INTO test.`{table}` (id, c_int, c_str) VALUES (1, 111, 'rep');"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT count(*) FROM test.`{table}`"]),

        # --- REPLACE's remaining value-source and clause combinations --------
        # The grammar pairs REPLACE with PARTITION, a column list, a VALUES list
        # OR a SET list. All four combinations were reachable; only
        # PARTITION+column-list was exercised.
        row(f"d75_{s}", [seeded,
            "REPLACE INTO test.`{table}` PARTITION (p0) SET id = 1, c_int = 122;"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT count(*) FROM test.`{table}`"],
            partition_by_mysql="RANGE (id) (PARTITION p0 VALUES LESS THAN (2), "
                               "PARTITION p1 VALUES LESS THAN (3), "
                               "PARTITION p2 VALUES LESS THAN MAXVALUE)"),
        row(f"d76_{s}", [seeded,
            "REPLACE INTO test.`{table}` SET id = 2, c_int = 123, c_str = 'r';"],
            ["SELECT c_int FROM test.`{table}` WHERE id = 2",
             "SELECT count(*) FROM test.`{table}`"]),

        # --- INSERT ... SELECT with a set operation -------------------------
        # `insertStatementValue` is a selectStatement, and selectStatement has a
        # unionSelect alternative. The plain SELECT form is exercised above; a
        # UNION between two SELECTs is the one that exercises the set-operation
        # branch of the same value source.
        row(f"d77_{s}",
            ["CREATE TABLE IF NOT EXISTS test.uj2_{table} "
             "(id INT NOT NULL PRIMARY KEY, c_int INT);",
             "INSERT INTO test.uj2_{table} VALUES (1, 41), (2, 42);",
             "INSERT INTO test.`{table}` (id, c_int) "
             "SELECT id, c_int FROM test.uj2_{table} WHERE id = 1 "
             "UNION SELECT id, c_int FROM test.uj2_{table} WHERE id = 2;"],
            ["SELECT count(*) FROM test.`{table}`",
             "SELECT c_int FROM test.`{table}` WHERE id = 1",
             "SELECT c_int FROM test.`{table}` WHERE id = 2"]),

        # --- DELETE over a multi-partition list -----------------------------
        # `singleDeleteStatement` takes `PARTITION '(' partitionNames ')'`, a
        # LIST. One partition was exercised; two in the same statement is the
        # form that proves the list is parsed as a list and not as the first
        # element only.
        row(f"d78_{s}", [seeded,
            "DELETE FROM test.`{table}` PARTITION (p0, p1) WHERE id = 1;"],
            ["SELECT count(*) FROM test.`{table}`"],
            partition_by_mysql="RANGE (id) (PARTITION p0 VALUES LESS THAN (2), "
                               "PARTITION p1 VALUES LESS THAN (3), "
                               "PARTITION p2 VALUES LESS THAN MAXVALUE)"),
    ]


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Updates("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Deletes("1.0"),
)
def dml_shape_matrix(self):
    """Every DML alternative of the MySQL grammar, applied live.

    The connector consumes DML as binlog row events, so no DDL-side handler
    ever sees a DML statement. The only proof that a DML shape replicates is to
    run it on MySQL and require the resulting row state in ClickHouse, which is
    what each row below does: the statement runs, then a count and a value
    comparison must agree on both engines.

    Two shapes had no coverage at all before this module: `REPLACE INTO`
    (all four alternatives) and the multi-table forms of `UPDATE` and
    `DELETE`.
    """
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        mysql = self.context.cluster.node("mysql-master")
        clickhouse = self.context.cluster.node("clickhouse")

        for entry in matrix_rows():
            with Example(f"{entry['name']} {clickhouse_table_engine}", flags=TE):
                with Given("I create a replicated table for this statement"):
                    replicated_table(
                        table_name=entry["name"],
                        partition_by_mysql=entry.get("partition_by_mysql", False),
                    )

                for statement in entry["statements"]:
                    with When("I run the MySQL statement",
                              description=statement.format(table=entry["name"])):
                        mysql.query(statement.format(table=entry["name"]))

                with And("I write a last row and wait for it in ClickHouse"):
                    # The table's events arrive in order, so once this row is
                    # there every statement above has been applied. Without it
                    # a statement that leaves the count as it was (an INSERT
                    # and a DELETE of the same row) passes every check before
                    # its events arrive, and the table is then dropped under
                    # the rows still in flight: ClickHouse answers Code: 60 and
                    # the connector stops for every test that follows. 4999 is
                    # inside the last partition of every partitioned row here.
                    mysql.query(
                        f"INSERT INTO test.`{entry['name']}` (id) VALUES ({LAST_ROW_ID});")
                    for attempt in retries(timeout=120, delay=5):
                        with attempt:
                            found = int(clickhouse.query(
                                f"SELECT count() FROM test.`{entry['name']}` FINAL "
                                f"WHERE id = {LAST_ROW_ID}").output.strip())
                            assert found == 1, (
                                f"row {LAST_ROW_ID} has not reached "
                                f"test.`{entry['name']}`"
                            )

                with Then("I wait for the pipeline to carry the change"):
                    row_count_matches(table_name=entry["name"])

                for check in entry["checks"]:
                    cell_matches(statement=check.format(table=entry["name"]))


@TestModule
@Name("dml ddl matrix")
def module(self):
    """DML grammar coverage: every INSERT / REPLACE / UPDATE / DELETE shape.

    The companion `alter_ddl_matrix` module covers the DDL grammar; this one
    covers the DML grammar, which the connector meets as binlog row events
    rather than as statement text. Both matrices are derived from the grammar
    rules in the Debezium MySQL parser, so the set of shapes is the set the
    grammar defines rather than a hand-picked sample.
    """
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
