from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *
from integration.tests.steps.alter import (
    alter_table,
    drop_table,
    rename_table,
    create_table_like,
)

# Columns every clause can reference, so a clause always names a column that
# exists. Only `id` is written by the row assertions, so everything here except
# the key is nullable and no default is needed.
BASE_COLUMNS = (
    "c_base VARCHAR(10), c_mod INT, c_chg BIGINT, c_ren INT, "
    "c_drop INT, c_fk INT, pad INT"
)

# RANGE with a partition below MAXVALUE still to add, and a middle partition
# every partition operation can target.
BASE_PARTITION = (
    "RANGE (id) (PARTITION p1 VALUES LESS THAN (1000), "
    "PARTITION p_mid VALUES LESS THAN (5000))"
)

# The FOREIGN KEY / CHECK pre-states need a parent table in the same schema.
PARENT_DDL = "CREATE TABLE IF NOT EXISTS test.ddl_matrix_parent (id INT NOT NULL PRIMARY KEY)"


@TestStep(Given)
def live_row(table_name, value, context=None):
    """Insert one row through MySQL and require it in ClickHouse.

    A clause the translator mishandles rarely fails at DDL time: it stalls the
    stream, emits a statement ClickHouse rejects forever, or takes the table out
    of the captured set. Only a row written afterwards exposes all three.

    A module-level step rather than a method: every caller passes
    ``table_name=`` and ``value=`` by keyword, so a plain function leaves those
    call sites untouched and the step stays usable from more than one test.
    """
    mysql = context.cluster.node("mysql-master")
    clickhouse = context.cluster.node("clickhouse")

    with When(f"I write row {value} to MySQL after the DDL"):
        mysql.query(f"INSERT INTO test.`{table_name}` (id) VALUES ({value});")

    with Then(f"I check row {value} reached ClickHouse, so the stream is alive"):
        # Counted, not matched as text: `message=` is a SUBSTRING test, so a
        # value of 1 would also accept a row whose id reads 10.
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                found = int(clickhouse.query(
                    f"SELECT count() FROM test.`{table_name}` FINAL "
                    f"WHERE id = {value}"
                ).output.strip())
                assert found == 1, (
                    f"row {value} is not in test.`{table_name}`; the stream "
                    f"stopped after this DDL"
                )


def row(name, clause, **kwargs):
    """One matrix row: a table name, the clause, and whatever the clause needs."""
    entry = {"name": name, "clause": clause}
    entry.update(kwargs)
    return entry

@TestStep(Then)
def column_arrived(table_name, column_name, context=None):
    """Require a named column to exist on the replicated ClickHouse table.

    Some rows exist to prove a bridge did NOT fire: a clause that must be
    dropped is a clause whose KEYWORD may also appear as an ordinary column
    name, and a bridge anchored anywhere but the start of a specification
    would eat that column. Parsing proves nothing there -- the dropped column
    would never reach ClickHouse -- so the column's presence is the assertion.

    Fenced with pipes and read as TSVRaw because ``message=`` is a SUBSTRING
    test; a bare name match would also accept a prefix of a different column.
    """
    clickhouse = context.cluster.node("clickhouse")

    with Then(f"I check the column {column_name} reached ClickHouse"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT concat('|', name, '|') FROM system.columns "
            f"WHERE database = 'test' AND table = '{table_name}' "
            f"AND name = '{column_name}' FORMAT TSVRaw",
            message=f"|{column_name}|",
        )


def matrix_rows():
    """Every MySQL 8.0 ALTER TABLE clause, one row each.

    The connector parses MySQL with the grammar in its Debezium dependency.
    That grammar's `alterSpecification` rule has 50 labelled alternatives, and
    `AlterTableDdlVariationMatrixTest` (unit) classifies all 50 -- including
    two that exist only in the Debezium grammar and are absent from this
    repository's own `MySqlParser.g4`.

    This module covers the alternatives whose clause is valid MySQL 8.0 AND
    leaves a table that still accepts a write, which is what makes the
    post-DDL row assertion meaningful. The remaining alternatives are covered
    at translation level in that unit matrix, and here is why each one cannot
    be asserted the same way:

      * ADD / DROP PRIMARY KEY -- a source key change is a documented
        unsupported source operation (the ClickHouse sorting key cannot be
        altered).
      * DISCARD / IMPORT TABLESPACE and DISCARD / IMPORT PARTITION -- the
        statement's whole purpose is to leave the table unreadable until the
        matching IMPORT, so a row write after it cannot succeed by design.
      * DISABLE / ENABLE KEYS -- MyISAM-only.
      * UPGRADE PARTITIONING -- MySQL 5.7 only, removed in 8.0.
      * EXCHANGE PARTITION -- needs a structurally identical non-partitioned
        table, which the connector would also have to replicate.
      * {WITH|WITHOUT} VALIDATION -- not standalone MySQL 8.0 alter options;
        they qualify an ADD/ALTER CHECK, which is covered in the forms below.
      * ADD SPATIAL INDEX -- needs a NOT NULL geometry column, which changes
        the replicated row shape every other row asserts against.
      * SECONDARY_ENGINE = <engine>, SECONDARY_LOAD, SECONDARY_UNLOAD -- the
        secondary-engine (RAPID) clauses. They are valid MySQL 8.0.13+, but
        this suite runs `mysql:8.0.36` Community, which ships no RAPID engine,
        so MySQL itself rejects them here and no binlog event is ever written.
        A clause MySQL refuses proves nothing about the connector.
        MySqlDdlNormalizerTest covers the parse and the bridge.

    The `ALTER TABLE t AUTOEXTEND_SIZE = 4M` CREATE-side twin, `CREATE TABLE
    ... ENGINE=InnoDB AUTOEXTEND_SIZE = 4M`, is covered by
    `MySqlDdlNormalizerTest` and in the create matrix, not here.
    """
    s = getuid()
    return [
        # --- columns: ADD ------------------------------------------------
        row(f"m01_{s}", "ADD COLUMN c_add INT"),
        row(f"m02_{s}", "ADD COLUMN (c_add1 INT, c_add2 VARCHAR(10))"),
        row(f"m03_{s}", "ADD COLUMN c_defs INT, ADD INDEX idx_defs (c_defs)"),
        row(f"m04_{s}", "ADD (c_defs2 INT, INDEX idx_defs2 (c_defs2))"),
        row(f"m05_{s}", "ADD COLUMN c_first VARCHAR(10) FIRST"),
        row(f"m06_{s}", "ADD COLUMN c_after VARCHAR(10) AFTER c_base"),
        row(f"m07_{s}", "ADD COLUMN c_notnull INT NOT NULL DEFAULT 7"),
        row(f"m08_{s}", "ADD COLUMN c_def0 INT DEFAULT (0)"),
        row(f"m09_{s}", "ADD COLUMN c_deflit VARCHAR(20) NOT NULL DEFAULT ('lit')"),
        row(f"m10_{s}", "ADD COLUMN c_defnull INT DEFAULT (NULL)"),
        # --- columns: MODIFY / CHANGE / RENAME / DROP --------------------
        row(f"m11_{s}", "MODIFY COLUMN c_mod BIGINT NULL"),
        # DEFAULT 0 because c_mod is NULL in the row written before this clause
        # and MySQL refuses NOT NULL on a column that already holds NULL
        # (ERROR 1138); the clause under test is the widening, not the NULL.
        row(f"m12_{s}", "MODIFY COLUMN c_mod INT NOT NULL DEFAULT 0"),
        row(f"m13_{s}", "MODIFY COLUMN c_mod BIGINT FIRST"),
        row(f"m14_{s}", "MODIFY COLUMN c_mod BIGINT AFTER id"),
        row(f"m15_{s}", "CHANGE COLUMN c_chg c_changed BIGINT"),
        row(f"m16_{s}", "RENAME COLUMN c_ren TO c_renamed"),
        row(f"m17_{s}", "DROP COLUMN c_drop"),
        row(f"m18_{s}", "DROP COLUMN c_drop, DROP COLUMN pad"),
        # --- indexes -----------------------------------------------------
        row(f"m19_{s}", "ADD INDEX idx_clause (c_base)"),
        row(f"m20_{s}", "ADD KEY idx_key (c_base)"),
        row(f"m21_{s}", "ADD UNIQUE KEY uk_key (c_base)"),
        row(f"m22_{s}", "ADD UNIQUE INDEX uk_idx (c_base)"),
        row(f"m23_{s}", "ADD FULLTEXT INDEX ft_idx (c_base)"),
        row(
            f"m24_{s}",
            "DROP INDEX idx_old",
            columns=f"{BASE_COLUMNS}, INDEX idx_old (c_base)",
        ),
        row(
            f"m25_{s}",
            "RENAME INDEX idx_old TO idx_new",
            columns=f"{BASE_COLUMNS}, INDEX idx_old (c_base)",
        ),
        row(
            f"m26_{s}",
            "ALTER INDEX idx_old INVISIBLE",
            columns=f"{BASE_COLUMNS}, INDEX idx_old (c_base)",
        ),
        row(
            f"m27_{s}",
            "ALTER INDEX idx_old VISIBLE",
            columns=f"{BASE_COLUMNS}, INDEX idx_old (c_base)",
        ),
        # --- constraints --------------------------------------------------
        row(
            f"m28_{s}",
            "ADD CONSTRAINT fk_ref FOREIGN KEY (c_fk) REFERENCES ddl_matrix_parent (id)",
            pre=[PARENT_DDL],
        ),
        row(f"m29_{s}", "ADD CONSTRAINT chk_positive CHECK (id > 0)"),
        row(f"m30_{s}", "ADD CONSTRAINT chk_noenforce CHECK (id > 0) NOT ENFORCED"),
        row(
            f"m31_{s}",
            "DROP FOREIGN KEY fk_ref",
            pre=[
                PARENT_DDL,
                "ALTER TABLE test.{table} ADD CONSTRAINT fk_ref FOREIGN KEY (c_fk) "
                "REFERENCES ddl_matrix_parent (id)",
            ],
        ),
        row(
            f"m32_{s}",
            "DROP CHECK chk_positive",
            pre=["ALTER TABLE test.{table} ADD CONSTRAINT chk_positive CHECK (id > 0)"],
        ),
        row(
            f"m33_{s}",
            "DROP CONSTRAINT chk_noenforce",
            pre=[
                "ALTER TABLE test.{table} ADD CONSTRAINT chk_noenforce "
                "CHECK (id > 0) NOT ENFORCED"
            ],
        ),
        row(
            f"m34_{s}",
            "ALTER CHECK chk_positive NOT ENFORCED",
            pre=["ALTER TABLE test.{table} ADD CONSTRAINT chk_positive CHECK (id > 0)"],
        ),
        row(
            f"m35_{s}",
            "ALTER CHECK chk_positive ENFORCED",
            pre=[
                "ALTER TABLE test.{table} ADD CONSTRAINT chk_positive "
                "CHECK (id > 0) NOT ENFORCED"
            ],
        ),
        # --- column defaults ----------------------------------------------
        row(f"m36_{s}", "ALTER COLUMN c_base SET DEFAULT 'sd'"),
        row(f"m37_{s}", "ALTER COLUMN c_base SET DEFAULT (5)"),
        row(f"m38_{s}", "ALTER COLUMN c_base DROP DEFAULT"),
        # --- table options and alter options ------------------------------
        row(f"m39_{s}", "COMMENT = 'ddl_matrix'"),
        row(f"m40_{s}", "ENGINE = InnoDB"),
        row(f"m41_{s}", "ALGORITHM = INPLACE"),
        row(f"m42_{s}", "ALGORITHM = COPY"),
        row(f"m43_{s}", "ALGORITHM = INSTANT"),
        row(f"m44_{s}", "ALGORITHM = DEFAULT"),
        row(f"m45_{s}", "LOCK = NONE"),
        row(f"m46_{s}", "LOCK = SHARED"),
        row(f"m47_{s}", "LOCK = EXCLUSIVE"),
        row(f"m48_{s}", "LOCK = DEFAULT"),
        row(f"m49_{s}", "ALGORITHM = INPLACE, LOCK = NONE, ADD COLUMN c_algo INT"),
        row(f"m50_{s}", "FORCE"),
        row(f"m51_{s}", "ORDER BY c_base"),
        row(f"m52_{s}", "CONVERT TO CHARACTER SET utf8mb4"),
        row(f"m53_{s}", "DEFAULT CHARACTER SET = utf8mb4"),
        # --- partitions ---------------------------------------------------
        row(
            f"m54_{s}",
            "ADD PARTITION (PARTITION p_last VALUES LESS THAN (MAXVALUE))",
            partition_by_mysql=BASE_PARTITION,
        ),
        row(f"m55_{s}", "DROP PARTITION p_mid", partition_by_mysql=BASE_PARTITION),
        row(f"m56_{s}", "TRUNCATE PARTITION p_mid", partition_by_mysql=BASE_PARTITION),
        row(f"m57_{s}", "ANALYZE PARTITION p_mid", partition_by_mysql=BASE_PARTITION),
        row(f"m58_{s}", "CHECK PARTITION p_mid", partition_by_mysql=BASE_PARTITION),
        row(f"m59_{s}", "OPTIMIZE PARTITION p_mid", partition_by_mysql=BASE_PARTITION),
        row(f"m60_{s}", "REBUILD PARTITION p_mid", partition_by_mysql=BASE_PARTITION),
        row(f"m61_{s}", "REPAIR PARTITION p_mid", partition_by_mysql=BASE_PARTITION),
        row(
            f"m62_{s}",
            "REORGANIZE PARTITION p_mid INTO (PARTITION p_mid VALUES LESS THAN (5000))",
            partition_by_mysql=BASE_PARTITION,
        ),
        row(f"m63_{s}", "REMOVE PARTITIONING", partition_by_mysql=BASE_PARTITION),
        row(
            f"m64_{s}",
            "COALESCE PARTITION 1",
            partition_by_mysql="HASH (id) PARTITIONS 4",
        ),
        # --- column visibility (MySQL 8.0.23+) ----------------------------
        row(f"m65_{s}", "ALTER COLUMN c_base SET INVISIBLE"),
        row(f"m66_{s}", "ALTER COLUMN c_base SET VISIBLE"),
        # --- AUTOEXTEND_SIZE (MySQL 8.0.23+) -------------------------------
        # The bundled parser has no terminal for AUTOEXTEND_SIZE, so
        # MySqlDdlNormalizer drops the specification before the parser sees it.
        # These rows exist because a unit test proves only that the STATEMENT
        # PARSES; it cannot prove the STREAM SURVIVES the statement. A clause
        # bridged into the wrong text still parses and still stalls the table.
        #
        # AUTOEXTEND_SIZE sizes a tablespace's growth step, so it has no
        # ClickHouse effect. The requirement is that the ALTER neither fails
        # nor stalls. 4M is the documented multiple; InnoDB is asserted for
        # every matrix table by create_mysql_table.
        row(f"m67_{s}", "AUTOEXTEND_SIZE = 4M"),
        # The same clause beside one that DOES translate, in both orders, so the
        # bridge has to drop one specification without eating its neighbour's
        # comma.
        row(f"m68_{s}", "AUTOEXTEND_SIZE = 4M, MODIFY COLUMN c_mod BIGINT"),
        row(f"m69_{s}", "MODIFY COLUMN c_mod BIGINT, AUTOEXTEND_SIZE = 8M"),
        # A column NAMED after a bridged keyword must NOT be bridged away. The
        # clause here is an ordinary ADD COLUMN, and the column has to reach
        # ClickHouse: the keyword is matched where a specification starts, not
        # anywhere the word appears.
        row(
            f"m70_{s}",
            "ADD COLUMN secondary_load INT",
            check_column="secondary_load",
        ),
    ]


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddIndex("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddKey("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddFullText("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddSpecial("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddConstraint("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Check("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Constraint("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropCheck("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropConstraint("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropDefault("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Index("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_CharacterSet("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_ConvertToCharacterSet(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Algorithm("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Force("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Lock("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Validation("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Default(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_FirstAfter(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Multiple(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Default(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_FirstAfter(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_FirstAfter(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop_Multiple(
        "1.0"
    ),
)
def alter_clause_matrix(self):
    """Every MySQL 8.0 ALTER TABLE clause, applied live, with replication proven after."""
    for clickhouse_table_engine in self.context.clickhouse_table_engines:
        mysql = self.context.cluster.node("mysql-master")
        clickhouse = self.context.cluster.node("clickhouse")

        for entry in matrix_rows():
            with Example(f"{entry['name']} {clickhouse_table_engine}", flags=TE):
                with Given("I create a replicated table for this clause"):
                    create_mysql_table(
                        table_name=entry["name"],
                        columns=entry.get("columns", BASE_COLUMNS),
                        primary_key="id",
                        partition_by_mysql=entry.get("partition_by_mysql", False),
                    )

                    # One write forces the connector to create the ClickHouse
                    # table, so the translated ALTER has a target.
                    mysql.query(
                        f"INSERT INTO test.`{entry['name']}` (id) VALUES (1);"
                    )
                    retry(clickhouse.query, timeout=120, delay=5)(
                        f"EXISTS test.`{entry['name']}`", message="1"
                    )

                for statement in entry.get("pre", []):
                    with And(
                        "I prepare the state the clause operates on",
                        description=statement.format(table=entry["name"]),
                    ):
                        mysql.query(statement.format(table=entry["name"]))

                with When("I apply the MySQL clause", description=entry["clause"]):
                    alter_table(table_name=entry["name"], clause=entry["clause"])

                live_row(table_name=entry["name"], value=2)

                if entry.get("check_column"):
                    column_arrived(
                        table_name=entry["name"],
                        column_name=entry["check_column"],
                    )


@TestOutline
def default_form(self, column_type, default_expression, expected_type, expected_default):
    """ADD COLUMN with one DEFAULT form, asserted on the replicated column.

    The DEFAULT itself is asserted, not only the type. A DEFAULT the translator
    drops leaves the column TYPE untouched, so a type-only check passed the
    defect this matrix exists to catch: `DEFAULT (0)` arrived at MySQL wrapped
    in the grammar's `'(' expression ')'` alternative, the translator handled
    only the bare literal, and the column was created without its default.
    ClickHouse records the clause in `system.columns`: `default_kind` is
    `DEFAULT` with a non-empty `default_expression` when it was carried, and
    both are empty when it was not.

    `expected_default` is the expression ClickHouse stores, which is the
    value the translator emitted and NOT the MySQL spelling: the server
    normalises it (`0x10` becomes `16`, `.5` becomes `0.5`, `0.0` becomes
    `0.`, and a boolean becomes lower case). Each expectation here was read
    from a ClickHouse server of the version this suite runs, not derived from
    the input.
    """
    table_name = f"d_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create a replicated table"):
        create_mysql_table(table_name=table_name, columns="pad INT", primary_key="id")

    with When(f"I add a column {column_type} DEFAULT {default_expression} in MySQL"):
        alter_table(
            table_name=table_name,
            clause=f"ADD COLUMN d {column_type} DEFAULT {default_expression}",
        )

    with Then("I check the ClickHouse column carries the source type"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT concat('|', type, '|') FROM system.columns "
            f"WHERE database = 'test' AND table = '{table_name}' "
            f"AND name = 'd' FORMAT TSVRaw",
            message=f"|{expected_type}|",
        )

    with And("I check a DEFAULT was carried at all, not only the type"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT concat('|', default_kind = 'DEFAULT', '|', "
            f"default_expression != '', '|') FROM system.columns "
            f"WHERE database = 'test' AND table = '{table_name}' "
            f"AND name = 'd' FORMAT TSVRaw",
            message="|1|1|",
        )

    with And("I check ClickHouse holds that DEFAULT and not a different one"):
        # The framework asserts `message in output`, a SUBSTRING test, so the
        # value is fenced between pipes: unfenced, an expected `''` matches
        # every output and an expected `1` would also match `10`. TSVRaw is
        # used because TabSeparated escapes quotes and a string literal would
        # then never match the expression the server stored.
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT concat('|', lower(default_expression), '|') "
            f"FROM system.columns WHERE database = 'test' "
            f"AND table = '{table_name}' AND name = 'd' FORMAT TSVRaw",
            message=f"|{expected_default.lower()}|",
        )

    with And("I write a row after the DDL and require it in ClickHouse"):
        live_row(table_name=table_name, value=1)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Default("1.0"),
)
def default_literal_forms(self):
    """Every literal DEFAULT form a MySQL column definition accepts.

    `DEFAULT (0)` is the form that shipped broken: MySQL parses a parenthesised
    default through its `'(' expression ')'` rule, so a plain constant arrives
    wrapped, and a translator that handles only the bare literal drops it. The
    whole literal family is here, bare and parenthesised, because a translator
    that unwraps one depth and not two, or a string literal and not a bit
    literal, is the same defect at a different input.
    """
    forms = [
        # --- grammar `constant`: stringLiteral, bare and parenthesised --------
        # A doubled quote is the MySQL spelling; ClickHouse stores the same
        # value re-rendered with a backslash escape.
        ("VARCHAR(10)", "'x'", "Nullable(String)", "'x'"),
        ("VARCHAR(10)", "('x')", "Nullable(String)", "'x'"),
        ("VARCHAR(10)", "''", "Nullable(String)", "''"),
        ("VARCHAR(10)", "('')", "Nullable(String)", "''"),
        ("VARCHAR(10)", "'a''b'", "Nullable(String)", "'a\\'b'"),
        ("VARCHAR(10)", "('a''b')", "Nullable(String)", "'a\\'b'"),
        ("CHAR(3)", "('abc')", "Nullable(String)", "'abc'"),
        # --- grammar `constant`: decimalLiteral and '-' decimalLiteral --------
        ("INT", "0", "Nullable(Int32)", "0"),
        ("INT", "(0)", "Nullable(Int32)", "0"),
        ("INT", "((0))", "Nullable(Int32)", "0"),
        ("INT", "1", "Nullable(Int32)", "1"),
        ("INT", "(1)", "Nullable(Int32)", "1"),
        ("INT", "(-1)", "Nullable(Int32)", "-1"),
        ("INT", "((-1))", "Nullable(Int32)", "-1"),
        ("INT", "+1", "Nullable(Int32)", "1"),
        ("TINYINT(1)", "(0)", "Nullable(Int8)", "0"),
        ("TINYINT(1)", "(1)", "Nullable(Int8)", "1"),
        ("BIGINT", "(1)", "Nullable(Int64)", "1"),
        ("DECIMAL(10,2)", "0.0", "Nullable(Decimal(10, 2))", "0."),
        ("DECIMAL(10,2)", "(0.0)", "Nullable(Decimal(10, 2))", "0."),
        ("DOUBLE", "0.5", "Nullable(Float64)", "0.5"),
        ("DOUBLE", "(0.5)", "Nullable(Float64)", "0.5"),
        # A real literal may start at the dot: `.5` is valid MySQL and reaches
        # ClickHouse as a leading digit, or the server rejects it (Code 62).
        ("DOUBLE", ".5", "Nullable(Float64)", "0.5"),
        ("DOUBLE", "(.5)", "Nullable(Float64)", "0.5"),
        ("DOUBLE", "-.5", "Nullable(Float64)", "-0.5"),
        ("DOUBLE", "(-.5)", "Nullable(Float64)", "-0.5"),
        ("DECIMAL(10,2)", ".5", "Nullable(Decimal(10, 2))", "0.5"),
        # --- grammar `constant`: booleanLiteral ------------------------------
        ("INT", "TRUE", "Nullable(Int32)", "TRUE"),
        ("INT", "FALSE", "Nullable(Int32)", "FALSE"),
        ("BOOLEAN", "(TRUE)", "Nullable(Bool)", "TRUE"),
        # --- grammar `constant`: hexadecimalLiteral --------------------------
        # A hex literal keeps its numeric meaning for an integer column and its
        # BYTES for a string column, and MySQL's HEX() pads an odd digit count,
        # so `0x1` is the byte 01, not 1.
        ("INT", "0x10", "Nullable(Int32)", "16"),
        ("INT", "(0x10)", "Nullable(Int32)", "16"),
        ("INT", "0x1", "Nullable(Int32)", "1"),
        ("VARBINARY(4)", "0x1", "Nullable(String)", "'01'"),
        ("VARBINARY(4)", "0xabc", "Nullable(String)", "'0abc'"),
        ("VARBINARY(4)", "0x4142", "Nullable(String)", "'4142'"),
        ("VARBINARY(4)", "X'4142'", "Nullable(String)", "'4142'"),
        ("VARCHAR(8)", "X'4142'", "Nullable(String)", "'4142'"),
        # --- grammar `constant`: BIT_STRING ----------------------------------
        ("INT", "b'1010'", "Nullable(Int32)", "10"),
        ("BIT(4)", "b'1010'", "Nullable(String)", "'0a'"),
        ("VARCHAR(8)", "b'1010'", "Nullable(String)", "'0a'"),
        # --- grammar `constant`: NOT? nullLiteral ----------------------------
        ("INT", "NULL", "Nullable(Int32)", "null"),
        ("INT", "(NULL)", "Nullable(Int32)", "null"),
        ("VARCHAR(10)", "NULL", "Nullable(String)", "null"),
        # --- grammar `constant`: REAL_LITERAL exponent form ------------------
        # ClickHouse evaluates the exponent, so the stored expression is the
        # plain value rather than the spelling the translator emitted.
        ("DOUBLE", "1.5e3", "Nullable(Float64)", "1500."),
        ("DOUBLE", "1.5E-3", "Nullable(Float64)", "0.0015"),
        # --- grammar `defaultValue`: '(' expression ')' around a constant -----
        # The outermost wrapper is the one that shipped broken; the nested form
        # proves the unwrap is a loop, not a single depth.
        ("INT", "((1))", "Nullable(Int32)", "1"),
        # --- a DATE literal, whose ClickHouse type is Date32 ------------------
        ("DATE", "'2020-01-01'", "Nullable(Date32)", "'2020-01-01'"),
        ("DATE", "('2020-01-01')", "Nullable(Date32)", "'2020-01-01'"),
    ]

    for column_type, default_expression, expected_type, expected_default in forms:
        with Example(
            f"{column_type} DEFAULT {default_expression}",
            flags=TE,
        ):
            default_form(
                column_type=column_type,
                default_expression=default_expression,
                expected_type=expected_type,
                expected_default=expected_default,
            )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Default("1.0"),
)
def modify_column_default_is_dropped(self):
    """MODIFY COLUMN with a non-constant DEFAULT drops it and keeps the stream alive.

    ClickHouse would back-fill the column with the moment of the ALTER, which is
    not a value any MySQL row holds, so the DEFAULT is deliberately not carried
    through MODIFY. The type change still applies and the stream keeps running.
    """
    table_name = f"modts_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create a replicated table"):
        create_mysql_table(
            table_name=table_name, columns="c DATETIME, pad INT", primary_key="id"
        )

    with When("I MODIFY the column to DEFAULT CURRENT_TIMESTAMP in MySQL"):
        alter_table(
            table_name=table_name,
            clause="MODIFY COLUMN c DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP",
        )

    with Then("I check the ClickHouse column was retyped and the stream is alive"):
        # Compared for equality after stripping, so a type that merely CONTAINS
        # the expected text (or an error message that does) cannot pass.
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                column_type = clickhouse.query(
                    f"SELECT type FROM system.columns WHERE database = 'test' "
                    f"AND table = '{table_name}' AND name = 'c' FORMAT TabSeparated"
                ).output.strip()
                assert column_type == "Nullable(DateTime64(0))", (
                    f"column c is {column_type!r}, expected "
                    f"'Nullable(DateTime64(0))'"
                )

    live_row(table_name=table_name, value=1)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename_Multiple("1.0"),
)
def rename_columns_sequentially(self):
    """Rename every column of a table one statement at a time.

    MySQL has no multi-column RENAME, so "rename multiple" is a sequence of
    single renames. Each must land on ClickHouse before the next is sent,
    because a rename of a column the replica does not yet hold fails.
    """
    table_name = f"ren_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create a replicated table with several columns"):
        create_mysql_table(
            table_name=table_name, columns="a INT, b INT, c INT", primary_key="id"
        )

    live_row(table_name=table_name, value=1)

    for old_name, new_name in (("a", "a2"), ("b", "b2"), ("c", "c2")):
        with When(f"I rename {old_name} to {new_name} in MySQL"):
            alter_table(
                table_name=table_name,
                clause=f"RENAME COLUMN {old_name} TO {new_name}",
            )

        with Then(f"I check ClickHouse has {new_name}"):
            # Counted, not matched as text: `message="a2"` is a SUBSTRING test
            # and would also pass on a column still named "xa2".
            for attempt in retries(timeout=120, delay=5):
                with attempt:
                    present = int(clickhouse.query(
                        f"SELECT count() FROM system.columns WHERE database = 'test' "
                        f"AND table = '{table_name}' AND name = '{new_name}'"
                    ).output.strip())
                    assert present == 1, (
                        f"column {new_name} is missing from test.`{table_name}`"
                    )

    live_row(table_name=table_name, value=2)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter("1.0"),
)
def alter_table_rename_to(self):
    """`ALTER TABLE ... RENAME TO` renames the replica table.

    It is a different grammar alternative from the standalone RENAME TABLE
    statement, and the connector translates it to the same ClickHouse RENAME.
    """
    old_name = f"altrn_old_{getuid()}"
    new_name = f"altrn_new_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create a replicated table"):
        create_mysql_table(table_name=old_name, columns="pad INT", primary_key="id")

    live_row(table_name=old_name, value=1)

    with When(f"I run ALTER TABLE ... RENAME TO {new_name} in MySQL"):
        alter_table(table_name=old_name, clause=f"RENAME TO {new_name}")

    with Then(f"I check ClickHouse has {new_name}"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"EXISTS test.`{new_name}`", message="1"
        )

    with And("I write a row through the renamed table and require it"):
        live_row(table_name=new_name, value=2)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename("1.0"),
)
def rename_table_statement(self):
    """The standalone RENAME TABLE statement renames the replica table."""
    old_name = f"rt_old_{getuid()}"
    new_name = f"rt_new_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create a replicated table"):
        create_mysql_table(table_name=old_name, columns="pad INT", primary_key="id")

    live_row(table_name=old_name, value=1)

    with When(f"I RENAME the table to {new_name} in MySQL"):
        rename_table(table_name=old_name, new_table_name=new_name)

    with Then(f"I check ClickHouse has {new_name}"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"EXISTS test.`{new_name}`", message="1"
        )

    with And("I write a row through the renamed table and require it"):
        live_row(table_name=new_name, value=2)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoDrop("1.0"),
)
def drop_table_statement(self):
    """DROP TABLE removes the replica table and the connector keeps working."""
    table_name = f"drp_{getuid()}"
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create a replicated table with a row"):
        create_mysql_table(table_name=table_name, columns="pad INT", primary_key="id")

    live_row(table_name=table_name, value=1)

    with When("I DROP the table in MySQL"):
        drop_table(table_name=table_name)

    with Then("I check the ClickHouse table is gone"):
        # Compared as a count, never through `message=`: that is a SUBSTRING
        # test, so an expected "0" also matches a stray "0" in an error text
        # (Code 60, a path containing 0) and the check would pass vacuously.
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                present = int(clickhouse.query(
                    "SELECT count() FROM system.tables "
                    f"WHERE database = 'test' AND name = '{table_name}'"
                ).output.strip())
                assert present == 0, (
                    f"test.`{table_name}` still exists in ClickHouse after DROP"
                )

    with And("I check replication still works on a fresh table"):
        other = f"drp_other_{getuid()}"
        create_mysql_table(table_name=other, columns="pad INT", primary_key="id")
        live_row(table_name=other, value=1)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoCreate("1.0"),
)
def create_table_like_statement(self):
    """CREATE TABLE ... LIKE replicates the source shape to a new replica table.

    The connector maps it to CREATE TABLE ... AS, copying the source column
    definitions. Rows written through the new table must replicate, which is
    what proves the column set is complete.
    """
    source = f"likesrc_{getuid()}"
    copy = f"likecp_{getuid()}"
    mysql = self.context.cluster.node("mysql-master")
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create and fill the source table"):
        create_mysql_table(
            table_name=source, columns="c INT, pad INT", primary_key="id"
        )
        mysql.query(f"INSERT INTO test.`{source}` (id, c) VALUES (1, 10);")

    with When("I CREATE TABLE LIKE the source in MySQL"):
        create_table_like(table_name=source, new_table_name=copy)

    with Then("I check the copy exists in ClickHouse with the source columns"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"EXISTS test.`{copy}`", message="1"
        )
        # Counted, not matched as text: `message="c"` is a SUBSTRING test and
        # "c" occurs in any column name that contains it.
        for attempt in retries(timeout=120, delay=5):
            with attempt:
                present = int(clickhouse.query(
                    f"SELECT count() FROM system.columns WHERE database = 'test' "
                    f"AND table = '{copy}' AND name = 'c'"
                ).output.strip())
                assert present == 1, f"column c is missing from test.`{copy}`"

    with And("I write through the copy and require the row"):
        live_row(table_name=copy, value=2)


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter("1.0"),
)
def statements_without_clickhouse_equivalent(self):
    """DDL ClickHouse has no equivalent for must not stall the replication stream.

    CREATE INDEX, CREATE VIEW, CREATE TRIGGER, CREATE PROCEDURE, CREATE
    FUNCTION, CREATE EVENT, CREATE TABLE ... AS SELECT and ALTER DATABASE have
    no translator handler, so they emit nothing. The requirement is not that
    ClickHouse gains the object -- it has none of MySQL's equivalents -- but
    that the statement neither fails nor stops the stream. A handler that threw
    here would wedge the binlog position for every later event on every table.
    """
    table_name = f"noeq_{getuid()}"
    mysql = self.context.cluster.node("mysql-master")
    clickhouse = self.context.cluster.node("clickhouse")

    with Given("I create a replicated table"):
        create_mysql_table(
            table_name=table_name, columns="c INT, pad INT", primary_key="id"
        )

    live_row(table_name=table_name, value=1)

    statements = [
        ("CREATE INDEX", f"CREATE INDEX idx_noeq ON test.`{table_name}` (c)"),
        ("DROP INDEX", f"DROP INDEX idx_noeq ON test.`{table_name}`"),
        (
            "CREATE VIEW",
            f"CREATE VIEW test.v_{table_name} AS SELECT id, c FROM test.`{table_name}`",
        ),
        (
            "CREATE TRIGGER",
            f"CREATE TRIGGER trg_{table_name} BEFORE INSERT ON test.`{table_name}` "
            f"FOR EACH ROW SET @noeq_trigger = 1",
        ),
        (
            "CREATE FUNCTION",
            f"CREATE FUNCTION f_{table_name}() RETURNS INT DETERMINISTIC RETURN 1",
        ),
        (
            "CREATE EVENT",
            f"CREATE EVENT e_{table_name} ON SCHEDULE EVERY 1 DAY "
            f"DO DELETE FROM test.`{table_name}` WHERE id < 0",
        ),
        (
            "CREATE TABLE AS SELECT",
            f"CREATE TABLE test.ctas_{table_name} AS "
            f"SELECT id, c FROM test.`{table_name}` WHERE 1 = 0",
        ),
        ("ALTER DATABASE", "ALTER DATABASE test CHARACTER SET utf8mb4"),
    ]

    for position, (kind, statement) in enumerate(statements):
        with Example(kind, flags=TE):
            with When(f"I run {kind} in MySQL"):
                mysql.query(statement)

            with Then("I check the ClickHouse table still exists"):
                retry(clickhouse.query, timeout=120, delay=5)(
                    f"EXISTS test.`{table_name}`", message="1"
                )

            live_row(table_name=table_name, value=position + 2)


@TestModule
@Name("alter ddl matrix")
def module(self):
    """Exhaustive DDL coverage: every MySQL DDL variation, applied live.

    The `alter` module proves the column clauses that were already translated
    correctly. This module covers the clause set MySQL actually accepts and for
    each one requires that the replication stream keeps carrying rows
    afterwards. A clause that is deliberately skipped and a clause that is
    mistranslated look identical at DDL time; only a row written afterwards
    tells them apart.

    The companion unit matrix `AlterTableDdlVariationMatrixTest` classifies
    every alternative of the Debezium MySQL grammar's `alterSpecification`
    rule by reflection, so a
    grammar bump that adds an alternative without a handler fails the build
    instead of arriving as an untested path.
    """
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
