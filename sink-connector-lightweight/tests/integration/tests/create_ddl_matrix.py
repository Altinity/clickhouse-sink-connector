from testflows.core import *

from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *

# The CREATE side of the DDL grammar.
#
# This module exists because the suite had no CREATE matrix at all: every DDL
# test started from a table that already existed, so `createDefinitions` --
# the rule that decides what the ClickHouse twin of a NEW table looks like --
# was never exercised statement by statement. A defect lived in that gap: the
# CREATE path handled NULL, PRIMARY KEY, AUTO_INCREMENT, UNIQUE and GENERATED
# but had no DEFAULT branch, so `CREATE TABLE t (a INT DEFAULT 7)` reached
# ClickHouse as a bare Nullable(Int32). Nothing failed -- every row that named
# the column replicated correctly -- and only a row that OMITTED it, or a
# read of system.columns, showed the loss.
#
# The rules, from MySqlParser.g4:
#
#   createTable              #copyCreateTable   CREATE ... LIKE other
#                          | #queryCreateTable  CREATE ... AS SELECT
#                          | #columnCreateTable CREATE ... ( definitions )
#   createDefinitions        '(' createDefinition (',' createDefinition)* ')'
#   createDefinition         columnDefinition | tableConstraint | ...
#   columnDefinition         dataType columnConstraint*
#   columnConstraint         #nullColumnConstraint
#                          | #defaultColumnConstraint
#                          | #invisibilityColumnConstraint
#                          | #visibilityColumnConstraint
#                          | #autoIncrementColumnConstraint
#                          | #primaryKeyColumnConstraint
#                          | #uniqueKeyColumnConstraint
#                          | #commentColumnConstraint
#                          | #formatColumnConstraint
#                          | #storageColumnConstraint
#                          | #referenceColumnConstraint
#                          | #collateColumnConstraint
#                          | #generatedColumnConstraint
#                          | #serialDefaultColumnConstraint
#                          | #checkColumnConstraint
#                          | #clusteringKeyColumnConstraint
#   tableConstraint          #primaryKeyTableConstraint | #uniqueKeyTableConstraint
#                          | #foreignKeyTableConstraint | #checkTableConstraint
#                          | #clusteringKeyTableConstraint
#
# A CREATE is proven by all three of its observable effects, because each one
# fails independently:
#   1. the table appears in ClickHouse at all (the DDL was replicated),
#   2. its column set, types and DEFAULT clauses match what MySQL declares,
#   3. a row written through MySQL arrives with the SOURCE's value -- never a
#      value ClickHouse's own DEFAULT invented in its place.

# The MySQL expression that renders column `c` as the text ClickHouse holds
# for it, for the types whose replicated form is not the text the MySQL client
# prints. The value is the same; only its spelling differs.
MYSQL_TEXT = {
    # ClickHouse Bool prints true/false; MySQL BOOLEAN is TINYINT(1), 1/0.
    "BOOLEAN": "IF(c, 'true', 'false')",
    # A BIT column is replicated as the hex digits of its bytes, two per byte;
    # MySQL's HEX() of a BIT value drops the leading zero (b'1010' is `A`).
    "BIT(4)": "LOWER(LPAD(HEX(c), 2, '0'))",
    # Binary bytes are replicated base64-encoded (binary.handling.mode).
    "VARBINARY(4)": "TO_BASE64(c)",
}


@TestStep(Then)
def column_matches(self, table_name, column_name, expected_type, context=None,
                   expected_kind=None, expected_default=None):
    """One replicated column's type and DEFAULT clause, read from the server.

    ClickHouse records the clause in `system.columns`: `default_kind` is
    `DEFAULT` with a non-empty `default_expression` when the source's DEFAULT
    was carried, and both are empty when it was dropped. A DEFAULT the
    translator drops leaves the type untouched, so a type-only assertion
    cannot see the defect this module exists to catch.

    ``self`` is required: the framework runs a step as
    ``self.func(current(), **args)``, so the current test object is the FIRST
    POSITIONAL argument and has to be named before the real parameters.
    """
    if context is None:
        context = self.context
    clickhouse = context.cluster.node("clickhouse")

    with Then(f"I check ClickHouse column {column_name} is {expected_type}"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT concat('|', type, '|') FROM system.columns "
            f"WHERE database = 'test' AND table = '{table_name}' "
            f"AND name = '{column_name}' FORMAT TSVRaw",
            message=f"|{expected_type}|",
        )

    if expected_kind is None:
        return

    with And(f"I check column {column_name} carries its DEFAULT clause"):
        # Fenced between pipes: the framework's `message=` is a SUBSTRING
        # test, so an expected value of `1` would also accept `10`.
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT concat('|', default_kind, '|', default_expression, '|') "
            f"FROM system.columns WHERE database = 'test' "
            f"AND table = '{table_name}' AND name = '{column_name}' FORMAT TSVRaw",
            message=f"|{expected_kind}|{expected_default}|",
        )


@TestStep(Then)
def column_default_was_dropped(self, table_name, column_name, context=None):
    """The column exists but carries no DEFAULT -- the deliberate omission."""
    if context is None:
        context = self.context
    clickhouse = context.cluster.node("clickhouse")

    with Then(f"I check column {column_name} carries NO DEFAULT"):
        # toString: concat() takes only strings on ClickHouse 23.8, the version
        # this suite runs (a UInt8 argument is Code: 44 there).
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT concat('|', toString(default_kind = ''), '|', "
            f"toString(default_expression = ''), '|') FROM system.columns "
            f"WHERE database = 'test' AND table = '{table_name}' "
            f"AND name = '{column_name}' FORMAT TSVRaw",
            message="|1|1|",
        )


@TestStep(Then)
def source_value_wins(self, table_name, column_name, context=None,
                      insert="(id) VALUES (1)", mysql_text=None):
    """A row written by MySQL arrives with the value MySQL holds.

    The decisive check for a DEFAULT: ClickHouse may hold the same DEFAULT
    expression and still disagree, so the comparison is against the source
    row's value, read from MySQL and required in ClickHouse.

    ``insert`` replaces the default row for a table whose columns MySQL will
    not fill by itself (a NOT NULL column with no DEFAULT); it must write
    ``id = 1``. ``mysql_text`` is the MySQL expression that renders the column
    the way ClickHouse stores it, for the types the two engines print
    differently (see `MYSQL_TEXT`); it defaults to the column itself.
    """
    if context is None:
        context = self.context
    mysql = context.cluster.node("mysql-master")
    clickhouse = context.cluster.node("clickhouse")
    if mysql_text is None:
        mysql_text = column_name

    with When("I write a row through MySQL"):
        mysql.query(f"INSERT INTO test.`{table_name}` {insert};")

    with Then(f"I check {column_name} holds the value MySQL stored"):
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                expected = mysql_scalar(
                    mysql,
                    f"SELECT {mysql_text} FROM test.`{table_name}` "
                    f"WHERE id = 1")
                # Fenced in brackets so a row that has not arrived (no output)
                # cannot pass for a row holding the empty string. A NULL makes
                # the whole concat NULL, which TSVRaw prints as \N.
                actual = clickhouse.query(
                    f"SELECT concat('[', toString({column_name}), ']') "
                    f"FROM test.`{table_name}` "
                    f"FINAL WHERE id = 1 FORMAT TSVRaw").output.strip()
                assert actual != "", f"row 1 has not reached test.`{table_name}`"
                if actual != "\\N":
                    actual = actual[1:-1]
                assert cells_agree(expected, actual), (
                    f"{column_name} is {actual!r} on ClickHouse and "
                    f"{expected!r} on MySQL"
                )


@TestOutline
def create_default_form(self, column_type, default_expression, expected_type,
                        expected_kind, expected_default, value_column=True):
    """CREATE TABLE with one DEFAULT form, asserted on the replicated column.

    The same literal family the ALTER matrix exercises, but reached through
    the CREATE path, which had no coverage and silently dropped every one of
    them. `expected_default` is the expression the SERVER stores, which is the
    value the translator emitted and not the MySQL spelling: ClickHouse
    normalises `0x10` to `16` and `.5` to `0.5`.

    An expression default (a parenthesised non-constant, `CURRENT_TIMESTAMP`
    aside) is NOT carried: a CREATE back-fills no row, so the omission
    diverges nothing, and guessing a constant would be worse than omitting it.
    A `DEFAULT NULL` on a nullable column is not carried either, because the
    type already implies it. Those cases assert the drop rather than skipping
    the DEFAULT check.
    """
    table_name = f"cd_{getuid()}"

    with Given("I create a table with this DEFAULT in MySQL"):
        create_mysql_table(
            table_name=table_name,
            columns=f"pad INT, c {column_type} DEFAULT {default_expression}",
            primary_key="id",
        )

    with Then("I check ClickHouse created the table with that column"):
        column_matches(
            table_name=table_name,
            column_name="c",
            expected_type=expected_type,
            expected_kind=expected_kind,
            expected_default=expected_default,
        )

    if expected_kind is None:
        with And("I check the DEFAULT was dropped, not invented"):
            column_default_was_dropped(table_name=table_name, column_name="c")

    if value_column:
        with And("I check a source row's own value reaches ClickHouse"):
            source_value_wins(
                table_name=table_name, column_name="c",
                mysql_text=MYSQL_TEXT.get(column_type),
            )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation("1.0"),
)
def create_default_forms(self):
    """Every DEFAULT form a CREATE TABLE column definition accepts.

    The literal family, bare and parenthesised, exactly as the ALTER matrix
    enumerates it -- but on the path that had no DEFAULT branch. `(0)` is the
    form that shipped broken on ALTER and `DEFAULT 7` the form that shipped
    broken here; a translator that handles one path and not the other is the
    defect, so both are pinned.
    """
    forms = [
        # --- grammar `constant`: decimalLiteral, bare and parenthesised ------
        ("INT", "7", "Nullable(Int32)", "DEFAULT", "7"),
        ("INT", "(7)", "Nullable(Int32)", "DEFAULT", "7"),
        ("INT", "-1", "Nullable(Int32)", "DEFAULT", "-1"),
        ("INT", "(-1)", "Nullable(Int32)", "DEFAULT", "-1"),
        ("INT", "+1", "Nullable(Int32)", "DEFAULT", "1"),
        ("INT", "((0))", "Nullable(Int32)", "DEFAULT", "0"),
        ("BIGINT", "1", "Nullable(Int64)", "DEFAULT", "1"),
        ("TINYINT(1)", "1", "Nullable(Int8)", "DEFAULT", "1"),
        # --- grammar `constant`: real / decimal literals ---------------------
        ("DECIMAL(10,2)", "1.5", "Nullable(Decimal(10, 2))", "DEFAULT", "1.5"),
        ("DECIMAL(10,2)", "-1.5", "Nullable(Decimal(10, 2))", "DEFAULT", "-1.5"),
        ("DOUBLE", ".5", "Nullable(Float64)", "DEFAULT", "0.5"),
        ("DOUBLE", "1.5e3", "Nullable(Float64)", "DEFAULT", "1500."),
        # --- grammar `constant`: stringLiteral -------------------------------
        ("VARCHAR(10)", "'z'", "Nullable(String)", "DEFAULT", "'z'"),
        ("VARCHAR(10)", "('z')", "Nullable(String)", "DEFAULT", "'z'"),
        ("VARCHAR(10)", "''", "Nullable(String)", "DEFAULT", "''"),
        ("VARCHAR(10)", "'a''b'", "Nullable(String)", "DEFAULT", "'a\\'b'"),
        # --- grammar `constant`: hexadecimalLiteral --------------------------
        ("INT", "0x1F", "Nullable(Int32)", "DEFAULT", "31"),
        ("VARBINARY(4)", "x'CAFE'", "Nullable(String)", "DEFAULT", "'cafe'"),
        # --- grammar `constant`: BIT_STRING ----------------------------------
        ("BIT(4)", "b'1010'", "Nullable(String)", "DEFAULT", "'0a'"),
        # The NUMERIC bit literal `0b1010` is a different token from b'1010',
        # and it has no row here. MySqlDdlNormalizer.quoteBitValueLiterals
        # rewrites it for the connector's own parser, but Debezium parses the
        # binlog statement first, with the same grammar and without the
        # normalizer, and stops the engine on it (Spec 06.03 FM-06.03-1).
        # Measured end to end: `CREATE TABLE t (c INT DEFAULT 0b1010)` halts
        # the stream, and this suite shares one connector across every row.
        # The rewrite itself is pinned by MySqlDdlNormalizerTest.
        # --- grammar `constant`: booleanLiteral ------------------------------
        # ClickHouse stores the literal in lower case.
        ("BOOLEAN", "TRUE", "Nullable(Bool)", "DEFAULT", "true"),
        # --- grammar `defaultValue`: NULL_LITERAL ---------------------------
        # The column is nullable, so `DEFAULT NULL` is implied by its type and
        # is deliberately not emitted (Spec 06.04 section 3.2.3). Emitting it
        # makes a later `MODIFY COLUMN c Int32` fail with Code: 70.
        ("INT", "NULL", "Nullable(Int32)", None, None),
        # --- a DATE literal, whose ClickHouse type is Date32 ------------------
        ("DATE", "'2020-01-01'", "Nullable(Date32)", "DEFAULT", "'2020-01-01'"),
    ]

    for column_type, default_expression, expected_type, expected_kind, expected_default in forms:
        with Example(f"{column_type} DEFAULT {default_expression}", flags=TE):
            create_default_form(
                column_type=column_type,
                default_expression=default_expression,
                expected_type=expected_type,
                expected_kind=expected_kind,
                expected_default=expected_default,
            )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation("1.0"),
)
def create_default_expression_forms(self):
    """The DEFAULT alternatives a CREATE cannot carry, and must not guess.

    `CURRENT_TIMESTAMP` is the grammar's one unparenthesised non-literal and
    ClickHouse derives the same instant with `now()` / `now64(scale)`, so it
    is carried. Every other expression default is per-row computation: a
    CREATE back-fills no row, so dropping it diverges nothing -- every row
    arrives with the value MySQL computed. The assertion is that the column
    exists, carries no DEFAULT, and still replicates the source's own value.
    """
    cases = [
        # --- grammar `currentTimestamp`, the one carried non-literal ---------
        # The type names the zone the suite configures
        # (clickhouse.datetime.timezone is UTC in helpers/default_config.py).
        ("DATETIME", "CURRENT_TIMESTAMP", "Nullable(DateTime64(0, 'UTC'))",
         "DEFAULT", "now64(0)"),
        ("DATETIME(3)", "CURRENT_TIMESTAMP(3)", "Nullable(DateTime64(3, 'UTC'))",
         "DEFAULT", "now64(3)"),
        # --- grammar `'(' expression ')'`: a per-row computation ------------
        ("INT", "(1 + 2)", "Nullable(Int32)", None, None),
        ("INT", "(ABS(-7))", "Nullable(Int32)", None, None),
        ("INT", "(CAST('5' AS SIGNED))", "Nullable(Int32)", None, None),
        ("VARCHAR(10)", "(CONCAT('a','b'))", "Nullable(String)", None, None),
        # --- grammar `CAST '(' expression AS convertedDataType ')' ----------
        ("INT", "(CAST(3.7 AS SIGNED))", "Nullable(Int32)", None, None),
    ]

    for column_type, default_expression, expected_type, kind, default in cases:
        with Example(f"{column_type} DEFAULT {default_expression}", flags=TE):
            table_name = f"ce_{getuid()}"

            with Given("I create a table with this DEFAULT in MySQL"):
                create_mysql_table(
                    table_name=table_name,
                    columns=f"pad INT, c {column_type} DEFAULT {default_expression}",
                    primary_key="id",
                )

            with Then("I check ClickHouse created the column with the source type"):
                column_matches(
                    table_name=table_name,
                    column_name="c",
                    expected_type=expected_type,
                )

            if kind is None:
                with And("I check the expression was dropped, not guessed"):
                    column_default_was_dropped(
                        table_name=table_name, column_name="c")
            else:
                with And("I check the derived expression was carried"):
                    column_matches(
                        table_name=table_name,
                        column_name="c",
                        expected_type=expected_type,
                        expected_kind=kind,
                        expected_default=default,
                    )

            with And("I check a source row's own value still reaches ClickHouse"):
                source_value_wins(table_name=table_name, column_name="c")


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation("1.0"),
)
def create_constraint_forms(self):
    """Every `columnConstraint` alternative, on a CREATE.

    The constraint set is what made the missing DEFAULT branch invisible: each
    neighbouring alternative WAS handled, so a table declaring all of them
    produced a plausible-looking ClickHouse statement -- one column short of
    its default. Each alternative is pinned here, with and without a DEFAULT
    alongside it, because they share one parser loop and a branch added for
    one must not disturb another.
    """
    # Each case: the column definitions, the column under test, its ClickHouse
    # type, whether it declares a DEFAULT, the key create_mysql_table adds as a
    # table constraint (None when the definitions carry the key themselves),
    # and the row written to prove replication.
    key, plain_row = "id", "(id) VALUES (1)"
    cases = [
        # --- #nullColumnConstraint ------------------------------------------
        # A NOT NULL column with no DEFAULT has to be named by the row: MySQL
        # refuses an INSERT that omits it (ERROR 1364, strict mode).
        ("pad INT, c INT NOT NULL", "c", "Int32", False, key,
         "(id, c) VALUES (1, 5)"),
        ("pad INT, c INT NULL", "c", "Nullable(Int32)", False, key, plain_row),
        # --- #primaryKeyColumnConstraint ------------------------------------
        # The column-level PRIMARY KEY is the table's only key: a second one
        # as a table constraint is ERROR 1068, so `id` carries it itself.
        ("pad INT, id INT NOT NULL PRIMARY KEY", "id", "Int32", False, None,
         plain_row),
        # --- #uniqueKeyColumnConstraint -------------------------------------
        ("pad INT, c INT UNIQUE", "c", "Nullable(Int32)", False, key, plain_row),
        # --- #autoIncrementColumnConstraint ---------------------------------
        # MySQL allows AUTO_INCREMENT only on a key column (ERROR 1075), so
        # the constraint sits on `id`, which create_mysql_table then keys.
        ("pad INT, id INT NOT NULL AUTO_INCREMENT", "id", "Int32", False, key,
         plain_row),
        # --- #defaultColumnConstraint ---------------------------------------
        ("pad INT, c INT DEFAULT 7", "c", "Nullable(Int32)", True, key, plain_row),
        ("pad INT, c INT NOT NULL DEFAULT 7", "c", "Int32", True, key, plain_row),
        # --- #generatedColumnConstraint -------------------------------------
        ("pad INT, c INT GENERATED ALWAYS AS (pad * 2) VIRTUAL", "c",
         "Nullable(Int32)", True, key, plain_row),
        # --- #commentColumnConstraint ---------------------------------------
        ("pad INT, c INT COMMENT 'note'", "c", "Nullable(Int32)", False, key,
         plain_row),
        # --- #checkColumnConstraint -----------------------------------------
        ("pad INT, c INT CHECK (c > 0)", "c", "Nullable(Int32)", False, key,
         plain_row),
        # --- several at once, DEFAULT among them ----------------------------
        ("pad INT, c INT NOT NULL DEFAULT 3 COMMENT 'n' UNIQUE", "c", "Int32",
         True, key, plain_row),
    ]

    for definitions, column_name, expected_type, has_default, primary_key, source_row in cases:
        with Example(definitions, flags=TE):
            table_name = f"cc_{getuid()}"

            with Given("I create the table in MySQL"):
                create_mysql_table(
                    table_name=table_name,
                    columns=definitions,
                    primary_key=primary_key,
                )

            with Then("I check ClickHouse created the column with the source type"):
                column_matches(
                    table_name=table_name,
                    column_name=column_name,
                    expected_type=expected_type,
                )

            if not has_default:
                with And("I check no DEFAULT was invented"):
                    column_default_was_dropped(
                        table_name=table_name, column_name=column_name)

            with And("I check a source row's own value reaches ClickHouse"):
                source_value_wins(
                    table_name=table_name, column_name=column_name,
                    insert=source_row)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation("1.0"),
)
def create_table_forms(self):
    """The three `createTable` alternatives.

    `#columnCreateTable` is the form every other test in the suite uses.
    `#copyCreateTable` (CREATE ... LIKE) and `#queryCreateTable` (CREATE ...
    AS SELECT) are separate alternatives with their own emission path, and
    neither had a test.
    """
    with Example("#columnCreateTable, the ordinary form", flags=TE):
        table_name = f"cf_col_{getuid()}"
        with Given("I create a table with a definition list in MySQL"):
            create_mysql_table(
                table_name=table_name,
                columns="pad INT, c INT DEFAULT 7",
                primary_key="id",
            )
        with Then("I check the DEFAULT survived the CREATE"):
            column_matches(
                table_name=table_name, column_name="c",
                expected_type="Nullable(Int32)",
                expected_kind="DEFAULT", expected_default="7",
            )
        with And("I check a source row replicas"):
            source_value_wins(table_name=table_name, column_name="c")

    with Example("#copyCreateTable, CREATE ... LIKE", flags=TE):
        source_table = f"cf_src_{getuid()}"
        copy_table = f"cf_copy_{getuid()}"
        mysql = self.context.cluster.node("mysql-master")

        with Given("I create the table that will be copied"):
            create_mysql_table(
                table_name=source_table,
                columns="pad INT, c INT DEFAULT 7",
                primary_key="id",
            )
        with When("I create a copy with LIKE in MySQL"):
            mysql.query(
                f"CREATE TABLE test.`{copy_table}` LIKE test.`{source_table}`;")
        with Then("I check the copy replicates with its DEFAULT"):
            column_matches(
                table_name=copy_table, column_name="c",
                expected_type="Nullable(Int32)",
                expected_kind="DEFAULT", expected_default="7",
            )
        with And("I check a source row replicas"):
            source_value_wins(table_name=copy_table, column_name="c")

    # Two forms have no example here: a CREATE TABLE carrying an AUTOEXTEND_SIZE
    # table option, and a column whose SRID clause follows another attribute
    # (`g GEOMETRY DEFAULT NULL SRID 4326`). MySqlDdlNormalizer rewrites both
    # for the connector's own parser, but Debezium parses the binlog statement
    # first, with the same grammar and without the normalizer, and stops the
    # engine on it (Spec 06.03 FM-06.03-1). Measured end to end: each of the
    # two statements halts the stream, and this suite shares one connector
    # across every example. The rewrites are pinned by MySqlDdlNormalizerTest.

    with Example("#queryCreateTable, CREATE ... AS SELECT", flags=TE):
        query_table = f"cf_q_{getuid()}"
        mysql = self.context.cluster.node("mysql-master")
        clickhouse = self.context.cluster.node("clickhouse")

        with When("I create a table from a query in MySQL"):
            # The SELECT sets the column types: `1` is INT and `'x'` a
            # one-character VARCHAR.
            mysql.query(
                f"CREATE TABLE test.`{query_table}` AS "
                f"SELECT 1 AS id, 'x' AS c;")
        with And("I insert a row so the connector sees the table"):
            mysql.query(
                f"INSERT INTO test.`{query_table}` VALUES (2, 'y');")
        with Then("I check the table replicates with both rows"):
            # Two rows, as on MySQL: the row the SELECT produced is written to
            # the binlog as a row event like any other, so it replicates too.
            retry(clickhouse.query, timeout=120, delay=5)(
                f"SELECT concat('|', toString(count()), '|') FROM test.`{query_table}` "
                f"FINAL FORMAT TSVRaw",
                message="|2|",
            )
        with And("I check the copied value reached ClickHouse"):
            retry(clickhouse.query, timeout=120, delay=5)(
                f"SELECT concat('|', c, '|') FROM test.`{query_table}` "
                f"FINAL FORMAT TSVRaw",
                message="|y|",
            )


@TestModule
@Name("create ddl matrix")
def module(self):
    """CREATE grammar coverage: every column constraint and createTable form.

    The companion `alter_ddl_matrix` module covers ALTER and
    `dml_ddl_matrix` covers DML. Neither exercises the CREATE path, which is
    where a DEFAULT on a new table was silently dropped: the table looked
    correct, the DDL replicated, and only a column that omitted its value
    diverged. Each row here asserts the replicated column's type AND its
    DEFAULT clause, then writes a source row and requires the source's value.
    """
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
