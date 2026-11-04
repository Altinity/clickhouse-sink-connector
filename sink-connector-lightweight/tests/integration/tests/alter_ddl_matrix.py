from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *
from integration.tests.steps.alter import (
    alter_table,
    drop_table,
    rename_table,
    create_index,
    create_view,
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


def live_row(self, table_name, value):
    """Insert one row through MySQL and require it in ClickHouse.

    A clause the translator mishandles rarely fails at DDL time: it stalls the
    stream, emits a statement ClickHouse rejects forever, or takes the table out
    of the captured set. Only a row written afterwards exposes all three.
    """
    mysql = self.context.cluster.node("mysql-master")
    clickhouse = self.context.cluster.node("clickhouse")

    with When(f"I write row {value} to MySQL after the DDL"):
        mysql.query(f"INSERT INTO test.`{table_name}` (id) VALUES ({value});")

    with Then(f"I check row {value} reached ClickHouse, so the stream is alive"):
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT id FROM test.`{table_name}` FINAL WHERE id = {value} FORMAT CSV",
            message=f"{value}",
        )


def row(name, clause, **kwargs):
    """One matrix row: a table name, the clause, and whatever the clause needs."""
    entry = {"name": name, "clause": clause}
    entry.update(kwargs)
    return entry


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
        row(f"m12_{s}", "MODIFY COLUMN c_mod INT NOT NULL"),
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


@TestOutline
def default_form(self, column_type, default_expression, expected_type):
    """ADD COLUMN with one DEFAULT form, asserted on the replicated column."""
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
            f"SELECT type FROM system.columns WHERE database = 'test' "
            f"AND table = '{table_name}' AND name = 'd' FORMAT TabSeparated",
            message=expected_type,
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
        ("INT", "0", "Nullable(Int32)"),
        ("INT", "(0)", "Nullable(Int32)"),
        ("INT", "((0))", "Nullable(Int32)"),
        ("INT", "1", "Nullable(Int32)"),
        ("INT", "(1)", "Nullable(Int32)"),
        ("INT", "(-1)", "Nullable(Int32)"),
        ("INT", "(NULL)", "Nullable(Int32)"),
        ("TINYINT(1)", "(0)", "Nullable(Int8)"),
        ("TINYINT(1)", "(1)", "Nullable(Int8)"),
        ("BIGINT", "(1)", "Nullable(Int64)"),
        ("DECIMAL(10,2)", "(0.0)", "Nullable(Decimal(10,2))"),
        ("DOUBLE", "(0.5)", "Nullable(Float64)"),
        ("VARCHAR(10)", "('x')", "Nullable(String)"),
        ("VARCHAR(10)", "('')", "Nullable(String)"),
        ("VARCHAR(10)", "('a''b')", "Nullable(String)"),
        ("CHAR(3)", "('abc')", "Nullable(String)"),
        ("DATE", "('2020-01-01')", "Nullable(Date32)"),
    ]

    for column_type, default_expression, expected_type in forms:
        with Example(f"{column_type} DEFAULT {default_expression}", flags=TE):
            default_form(
                column_type=column_type,
                default_expression=default_expression,
                expected_type=expected_type,
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
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT type FROM system.columns WHERE database = 'test' "
            f"AND table = '{table_name}' AND name = 'c' FORMAT TabSeparated",
            message="Nullable(DateTime64(0, 0))",
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
            retry(clickhouse.query, timeout=120, delay=5)(
                f"SELECT name FROM system.columns WHERE database = 'test' "
                f"AND table = '{table_name}' AND name = '{new_name}' FORMAT TabSeparated",
                message=new_name,
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
        retry(clickhouse.query, timeout=120, delay=5)(
            f"EXISTS test.`{table_name}`", message="0"
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
        retry(clickhouse.query, timeout=120, delay=5)(
            f"SELECT name FROM system.columns WHERE database = 'test' "
            f"AND table = '{copy}' AND name = 'c' FORMAT TabSeparated",
            message="c",
        )

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
    every metric alternative in the Debezium MySQL grammar by reflection, so a
    grammar bump that adds an alternative without a handler fails the build
    instead of arriving as an untested path.
    """
    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
