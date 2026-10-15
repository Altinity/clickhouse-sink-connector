"""Generated columns in the snapshot loader's DDL translation (Spec 13.04 D-13.04-33, D-13.04-9, D-13.04-23),
run against BOTH copies.

A MySQL generated column becomes ``<type> NULL DEFAULT <expression>``: the MySQL Shell dump carries no generated
values, so rows loaded from it get the value computed from their columns, and streamed rows keep the value MySQL
sent. The expression is passed through as written (charset introducers dropped), except for the bit operators
ClickHouse lacks, which are translated on the parse tree; a bit operator on a non-integer operand, which
ClickHouse would evaluate differently, refuses the table with UnsafeTableDefinitionError.

The value checks run the translated DDL in ``clickhouse local`` (an embedded engine, no server) and are skipped
when it is not installed. Run from sink-connector/python:
    python -m pytest db_load/tests/test_loader_generated_columns.py
"""
import importlib
import logging
import os
import re
import shutil
import subprocess
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))  # sink-connector/python
sys.path.insert(0, ROOT)

COPIES = {
    "legacy": "db_load.clickhouse_loader",
    "packaged": "ch_sink_tools.db_load.clickhouse_loader",
}
CLICKHOUSE = shutil.which("clickhouse")
needs_clickhouse = pytest.mark.skipif(CLICKHOUSE is None, reason="clickhouse local not installed")


@pytest.fixture(params=sorted(COPIES))
def loader(request):
    return importlib.import_module(COPIES[request.param])


def translate(loader, ddl):
    return loader.convert_to_clickhouse_table("u", "t", ddl, True, False, None)


# The columns of the real-schema reproduction (MySQL 8.0 SHOW CREATE TABLE text).
BIT_FLAGS_DDL = """CREATE TABLE `t` (
  `id` bigint NOT NULL,
  `attrs` bigint DEFAULT '0',
  `is_reversal` int GENERATED ALWAYS AS (((`attrs` & (1 << 0)) > 0)) VIRTUAL,
  `is_pending` int GENERATED ALWAYS AS (((`attrs` & (1 << 2)) > 0)) VIRTUAL,
  `is_manual` tinyint(1) GENERATED ALWAYS AS (((`attrs` & (1 << 4)) > 0)) STORED,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;"""


def flag(bit):
    shift = f"if(toUInt64({bit}) >= 64, bitAnd(toUInt64(1), 0), bitShiftLeft(toUInt64(1), toUInt64({bit})))"
    return f"((bitAnd(toUInt64(`attrs`), toUInt64(({shift})))) > 0)"


def column_lines(ddl):
    return {line.split()[0]: line.rstrip(",") for line in ddl.splitlines() if line.startswith("`")}


def run_clickhouse(query):
    result = subprocess.run([CLICKHOUSE, "local", "--multiquery", "-q", query], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    return [line.split("\t") for line in result.stdout.splitlines()]


def computed(ddl, insert_columns, rows, select):
    """Create the translated table in clickhouse local, insert only ``insert_columns`` (as the loader does, the
    generated columns omitted) and return ``select`` ordered by the first inserted column."""
    create = ddl.replace("CREATE TABLE `t`", "CREATE TABLE t", 1)
    def literal(v):
        return "NULL" if v is None else f"'{v}'" if isinstance(v, str) else str(v)

    values = ",".join("(" + ",".join(literal(v) for v in row) + ")" for row in rows)
    return run_clickhouse(f"{create};\nINSERT INTO t ({', '.join(insert_columns)}) VALUES {values};\n"
                          f"SELECT {select} FROM t ORDER BY {insert_columns[0]} FORMAT TSV")


class TestBitFlagColumns:
    def test_repro_columns_become_default_expressions(self, loader):
        ddl, _ = translate(loader, BIT_FLAGS_DDL)
        lines = column_lines(ddl)
        assert lines["`is_reversal`"] == "`is_reversal` int NULL DEFAULT " + flag(0)
        assert lines["`is_pending`"] == "`is_pending` int NULL DEFAULT " + flag(2)
        assert lines["`is_manual`"] == "`is_manual` tinyint(1) NULL DEFAULT " + flag(4)
        assert "MATERIALIZED" not in ddl and "&" not in ddl and "<<" not in ddl

    def test_generated_columns_stay_out_of_the_load(self, loader):
        _, cols = translate(loader, BIT_FLAGS_DDL)
        assert [c["column_name"] for c in cols if c["generated"]] == ["`is_reversal`", "`is_pending`", "`is_manual`"]
        assert loader.get_column_list({"db1.t": cols}, "db1", "t", []) == "\\`id\\`,\\`attrs\\`"

    @needs_clickhouse
    def test_loaded_rows_get_mysql_values(self, loader):
        # MySQL: (a & (1 << n)) > 0 over unsigned 64-bit integers, NULL for a NULL attribute; -1 has every bit set.
        ddl, _ = translate(loader, BIT_FLAGS_DDL)
        rows = computed(ddl, ["id", "attrs"], [(1, 5), (2, None), (3, 16), (4, -1), (5, 0)],
                        "id, is_reversal, is_pending, is_manual")
        assert rows == [["1", "1", "1", "0"], ["2", "\\N", "\\N", "\\N"], ["3", "0", "0", "1"],
                        ["4", "1", "1", "1"], ["5", "0", "0", "0"]]


OPERATORS_DDL = """CREATE TABLE `t` (
  `id` int NOT NULL,
  `a` bigint DEFAULT NULL,
  `b` int DEFAULT NULL,
  `c` int DEFAULT NULL,
  `p1` bigint unsigned GENERATED ALWAYS AS (`a` + `b` & `c`) VIRTUAL,
  `p2` bigint unsigned GENERATED ALWAYS AS (`a` | `b` & `c`) VIRTUAL,
  `p3` bigint unsigned GENERATED ALWAYS AS (`a` & `b` | `c`) VIRTUAL,
  `p4` bigint GENERATED ALWAYS AS (`a` + `b` * `c`) VIRTUAL,
  `p5` bigint GENERATED ALWAYS AS (`a` ^ `b` * `c`) VIRTUAL,
  `p6` bigint unsigned GENERATED ALWAYS AS (1 << `b` + 1) VIRTUAL,
  `p7` bigint unsigned GENERATED ALWAYS AS (~`a` & 255) VIRTUAL,
  `p8` int GENERATED ALWAYS AS (`a` > 0 or `b` > 0 and `c` > 0) VIRTUAL,
  `p9` int GENERATED ALWAYS AS (((`a` >> 1) & 3) = 2) STORED,
  `p10` int GENERATED ALWAYS AS (not (`a` is null) and -`b` < 0) VIRTUAL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;"""


def mysql_reference(a, b, c):
    """What MySQL 8.0 computes for p1..p10 (bit operations on unsigned 64-bit integers, precedence
    ^ > * > + - > << >> > & > |, AND > OR)."""
    u = lambda x: x % (1 << 64)  # noqa: E731
    shl = lambda x, n: 0 if n >= 64 else u(x << n)  # noqa: E731
    return [u(a + b) & u(c), u(a) | (u(b) & u(c)), (u(a) & u(b)) | u(c), a + b * c, (u(a) ^ u(b)) * c,
            shl(1, b + 1), u(~u(a)) & 255, int(a > 0 or (b > 0 and c > 0)), int(((u(a) >> 1) & 3) == 2),
            int(a is not None and -b < 0)]


class TestPrecedenceAndOperators:
    def test_unparenthesised_chains_follow_mysql_precedence(self, loader):
        ddl, _ = translate(loader, OPERATORS_DDL)
        lines = column_lines(ddl)
        assert lines["`p1`"].endswith("DEFAULT bitAnd(toUInt64((`a` + `b`)), toUInt64(`c`))")
        assert lines["`p2`"].endswith("DEFAULT bitOr(toUInt64(`a`), toUInt64(bitAnd(toUInt64(`b`), toUInt64(`c`))))")
        assert lines["`p3`"].endswith("DEFAULT bitOr(toUInt64(bitAnd(toUInt64(`a`), toUInt64(`b`))), toUInt64(`c`))")
        # no bit operator: passed through as written, ClickHouse applies the same precedence as MySQL
        assert lines["`p4`"].endswith("DEFAULT `a` + `b` * `c`")
        assert lines["`p5`"].endswith("DEFAULT (bitXor(toUInt64(`a`), toUInt64(`b`)) * `c`)")
        assert lines["`p6`"].endswith("DEFAULT if(toUInt64((`b` + 1)) >= 64, bitAnd(toUInt64(1), 0), "
                                      "bitShiftLeft(toUInt64(1), toUInt64((`b` + 1))))")
        assert lines["`p7`"].endswith("DEFAULT bitAnd(toUInt64(bitNot(toUInt64(`a`))), toUInt64(255))")
        assert lines["`p8`"].endswith("DEFAULT `a` > 0 or `b` > 0 and `c` > 0")

    @needs_clickhouse
    def test_values_equal_mysql(self, loader):
        ddl, _ = translate(loader, OPERATORS_DDL)
        # Inputs keep every result inside its column type, where MySQL would store it rather than fail.
        inputs = [(1, 6, 3, 5), (2, 13, 63, 7), (3, -2, 1, 0), (4, 0, 0, -1), (5, 255, 62, 2), (6, 4, -1, 0)]
        rows = computed(ddl, ["id", "a", "b", "c"], inputs, ", ".join(f"p{i}" for i in range(1, 11)))
        assert rows == [[str(v) for v in mysql_reference(a, b, c)] for (_, a, b, c) in inputs]

    @needs_clickhouse
    def test_shift_by_64_or_more_is_zero_and_null_stays_null(self, loader):
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `n` int DEFAULT NULL,\n"
                                   "  `v` bigint unsigned GENERATED ALWAYS AS ((1 << `n`)) VIRTUAL,\n"
                                   "  `w` bigint unsigned GENERATED ALWAYS AS ((`n` >> 64)) VIRTUAL,\n"
                                   "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        rows = computed(ddl, ["id", "n"], [(1, 63), (2, 64), (3, 200), (4, None)], "v, w")
        assert rows == [["9223372036854775808", "0"], ["0", "0"], ["0", "0"], ["\\N", "\\N"]]
        # ClickHouse's own result for a count of 64 or more depends on the code path (the C++ shift is undefined
        # there; a scalar evaluation gives 1 << (64 mod 64) = 1): the same expressions evaluated on constants.
        lines = column_lines(ddl)
        for column, n in (("`v`", 64), ("`v`", 200), ("`w`", 5)):
            expression = lines[column].split(" DEFAULT ", 1)[1].replace("`n`", f"toNullable(toInt32({n}))")
            assert run_clickhouse(f"SELECT {expression} FORMAT TSV") == [["0"]], (column, n, expression)

    def test_underscore_identifiers_are_kept(self, loader):
        # D-13.04-23: the old charset-introducer strip cut `_flags` out of the expression.
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `_flags` int DEFAULT NULL,\n"
                                   "  `f` int GENERATED ALWAYS AS ((`_flags` & 1)) STORED,\n"
                                   "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        assert column_lines(ddl)["`f`"] == "`f` int NULL DEFAULT (bitAnd(toUInt64(`_flags`), toUInt64(1)))"


def one_generated_column(expression, column_type="varchar(64)"):
    return ("CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `a` int DEFAULT NULL,\n  `b` int DEFAULT NULL,\n"
            "  `d` decimal(10,2) DEFAULT NULL,\n  `f` double DEFAULT NULL,\n  `s` varchar(10) DEFAULT NULL,\n"
            f"  `g` {column_type} GENERATED ALWAYS AS ({expression}) VIRTUAL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")


def previous_rendering(expression):
    """The expression text the loader emitted before D-13.04-33 (after `MATERIALIZED`)."""
    return re.sub(r"\b_.*?'", "'", expression)


# Expressions ClickHouse already accepted: they loaded as MATERIALIZED before and must load unchanged (as DEFAULT).
PASS_THROUGH = [
    "(`a` is null)",
    "(`a` is not null)",
    "(case when (`a` > 0) then `a` else 0 end)",
    "((`a` * 2) + `b`)",
    "(`a` - `b`)",
    "((`a` > 0) and (`b` < 5))",
    "coalesce(`a`,`b`,0)",
    "ifnull(`a`,0)",
    "concat(`s`,_utf8mb4'-',ifnull(`s`,_utf8mb4''))",
    "(`s` = _utf8mb4'x')",
    "if((`a` > 0),_utf8mb4'pos',_utf8mb4'neg')",
]


class TestPassThrough:
    @pytest.mark.parametrize("expression", PASS_THROUGH)
    def test_expression_is_unchanged_but_default(self, loader, expression):
        ddl, _ = translate(loader, one_generated_column(expression))
        assert column_lines(ddl)["`g`"] == "`g` varchar(64) NULL DEFAULT " + previous_rendering(expression)
        assert "MATERIALIZED" not in ddl

    @needs_clickhouse
    def test_pass_through_expressions_load(self, loader):
        for expression in PASS_THROUGH:
            ddl, _ = translate(loader, one_generated_column(expression))
            rows = computed(ddl, ["id", "a", "b", "s"], [(1, 3, 4, "q")], "id, g")
            assert len(rows) == 1 and rows[0][0] == "1", (expression, rows)

    def test_underscore_identifier_in_a_function_is_kept(self, loader):
        # D-13.01-3: the old charset-introducer strip turned this into concat('x').
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `_code` varchar(8) DEFAULT NULL,\n"
                                   "  `g` varchar(16) GENERATED ALWAYS AS (concat(`_code`,_utf8mb4'x')) VIRTUAL,\n"
                                   "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        assert column_lines(ddl)["`g`"] == "`g` varchar(16) NULL DEFAULT concat(`_code`,'x')"

    @needs_clickhouse
    def test_concatenated_key_loads_with_mysql_values(self, loader):
        # A STORED key built with concat and ifnull, as on the real schema family.
        ddl, _ = translate(loader, "CREATE TABLE `t` (\n  `id` int NOT NULL,\n  `spec_id` int NOT NULL,\n"
                                   "  `account` varchar(32) DEFAULT NULL,\n  `book` varchar(16) DEFAULT NULL,\n"
                                   "  `concat_key` varchar(100) GENERATED ALWAYS AS (concat(`spec_id`,_utf8mb4'-',"
                                   "ifnull(`account`,_utf8mb4''),_utf8mb4'-',ifnull(`book`,_utf8mb4''))) STORED,\n"
                                   "  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
        assert column_lines(ddl)["`concat_key`"] == ("`concat_key` varchar(100) NULL DEFAULT concat(`spec_id`,'-',"
                                                     "ifnull(`account`,''),'-',ifnull(`book`,''))")
        inputs = [(1, 7, "ACC", "B1"), (2, 8, None, "B2"), (3, 9, "X", None), (4, 10, None, None)]
        rows = computed(ddl, ["id", "spec_id", "account", "book"], inputs, "concat_key")
        # MySQL: concat of the parts, ifnull turning a NULL account or book into ''.
        assert rows == [[f"{spec}-{account or ''}-{book or ''}"] for (_, spec, account, book) in inputs]


# A bit operator on a non-integer operand: MySQL converts the operand to an integer by rounding (2.5 & 3 = 3),
# toUInt64 truncates (2), and MySQL evaluates binary strings bytewise. These never loaded before (no & in
# ClickHouse), so refusing them breaks nothing.
NON_INTEGER_BIT_OPERANDS = ["(`d` & 1)", "(`f` << 1)", "(`s` & 1)", "(`a` & 1.5)", "(~(`d`))", "((`a` + `d`) | 1)"]


class TestNonIntegerBitOperands:
    @pytest.mark.parametrize("expression", NON_INTEGER_BIT_OPERANDS)
    def test_refused_naming_table_and_column(self, loader, expression):
        with pytest.raises(loader.UnsafeTableDefinitionError) as refused:
            translate(loader, one_generated_column(expression, "bigint"))
        message = str(refused.value)
        assert "`t`" in message and "generated column `g`" in message and "non-integer" in message, message

    @needs_clickhouse
    def test_clickhouse_truncates_where_mysql_rounds(self):
        # The translation would compute 2 where MySQL computes 3 for 2.5 & 3 (and 2 for 1.5 << 1, MySQL 4).
        assert run_clickhouse("SELECT bitAnd(toUInt64(toDecimal64(2.5, 1)), toUInt64(3)), "
                              "bitShiftLeft(toUInt64(toFloat64(1.5)), toUInt64(1)) FORMAT TSV") == [["2", "2"]]


# A STORED key in the style of the real schemas: CONVERT(... USING latin1) (absent in ClickHouse) and IFNULL of a
# number, a date or a decimal with '' (no common type in ClickHouse; MySQL returns the column's text).
KEY_DDL = """CREATE TABLE `t` (
  `id` int NOT NULL,
  `n` int DEFAULT NULL,
  `s` varchar(16) CHARACTER SET latin1 DEFAULT NULL,
  `dt` date DEFAULT NULL,
  `p` decimal(12,4) DEFAULT NULL,
  `k` varchar(200) CHARACTER SET latin1 GENERATED ALWAYS AS (concat(`id`,_latin1'-',convert(ifnull(`n`,_utf8mb4'') using latin1),_latin1'-',ifnull(`s`,_latin1''),_latin1'-',ifnull(`dt`,_latin1''),_latin1'-',convert(ifnull(`p`,_utf8mb4'') using latin1),_latin1'-',convert(`p` using latin1))) STORED,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;"""


def mysql_key(id_, n, s, dt, p):
    """What MySQL computes for `k`: IFNULL gives the column's text or ''; CONVERT(NULL USING ...) is NULL, and a
    NULL argument makes CONCAT NULL; a DECIMAL(12,4) prints four decimals."""
    if p is None:
        return None
    text = lambda v: "" if v is None else str(v)  # noqa: E731
    return "-".join([str(id_), text(n), text(s), text(dt), f"{p:.4f}", f"{p:.4f}"])


class TestConvertUsingAndMixedIfnull:
    def test_translation(self, loader):
        ddl, _ = translate(loader, KEY_DDL)
        assert column_lines(ddl)["`k`"] == (
            "`k` varchar(200)  NULL DEFAULT concat(`id`,'-',toString(ifnull(toString(`n`),'')),'-',ifnull(`s`,''),'-',"
            "ifnull(toString(`dt`),''),'-',toString(ifnull(toDecimalString(`p`, 4),'')),'-',toDecimalString(`p`, 4))")

    @needs_clickhouse
    def test_values_equal_mysql(self, loader):
        ddl, _ = translate(loader, KEY_DDL)
        inputs = [(1, 5, "abc", "2024-01-02", 1.5), (2, None, None, None, 0.25), (3, -7, "", "1999-12-31", 12.0),
                  (4, 9, "x", None, None)]
        rows = computed(ddl, ["id", "n", "s", "dt", "p"], inputs, "k")
        assert rows == [["\\N" if mysql_key(*r) is None else mysql_key(*r)] for r in inputs]

    def test_coalesce_of_a_number_and_a_string(self, loader):
        ddl, _ = translate(loader, one_generated_column("coalesce(`a`,_utf8mb4'none')"))
        assert column_lines(ddl)["`g`"] == "`g` varchar(64) NULL DEFAULT coalesce(toString(`a`),'none')"

    @pytest.mark.parametrize("expression", ["ifnull(`s`,_utf8mb4'')", "ifnull(`a`,0)", "coalesce(`a`,`b`,0)"])
    def test_same_kind_arguments_are_unchanged(self, loader, expression):
        ddl, _ = translate(loader, one_generated_column(expression))
        assert column_lines(ddl)["`g`"] == "`g` varchar(64) NULL DEFAULT " + previous_rendering(expression)

    @pytest.mark.parametrize("expression", ["convert(`f` using latin1)", "convert(`vb` using latin1)",
                                            "ifnull(`f`,_utf8mb4'')", "ifnull(`bt`,_utf8mb4'')"])
    def test_refused_where_the_text_differs(self, loader, expression):
        ddl = one_generated_column(expression).replace(
            "  `s` varchar(10) DEFAULT NULL,\n", "  `s` varchar(10) DEFAULT NULL,\n  `vb` varbinary(8) DEFAULT NULL,\n"
            "  `bt` bit(1) DEFAULT NULL,\n")
        with pytest.raises(loader.UnsafeTableDefinitionError) as refused:
            translate(loader, ddl)
        assert "`t`" in str(refused.value) and "generated column `g`" in str(refused.value), refused.value

    @needs_clickhouse
    def test_clickhouse_text_of_float_decimal_and_bool(self):
        # toString differs from MySQL's text for these (MySQL: 1e20, 1.5000, raw byte 0x01), and NULL stays NULL.
        assert run_clickhouse("SELECT toString(toFloat64(1e20)), toString(toDecimal64(1.5, 4)), "
                              "toDecimalString(toDecimal64(1.5, 4), 4), toString(true), "
                              "toString(CAST(NULL AS Nullable(Int32))) FORMAT TSV") == \
            [["100000000000000000000", "1.5", "1.5000", "true", "\\N"]]


# Numbers MySQL writes as text: CONCAT/CONCAT_WS arguments and string-typed generated columns.
NUMBERS_DDL = """CREATE TABLE `t` (
  `id` int NOT NULL,
  `q` int DEFAULT NULL,
  `p` decimal(10,2) DEFAULT NULL,
  `s` varchar(16) DEFAULT NULL,
  `g1` varchar(64) GENERATED ALWAYS AS (concat(`p`,_utf8mb4'/',`q`)) VIRTUAL,
  `g2` varchar(64) GENERATED ALWAYS AS ((`q` / 2)) VIRTUAL,
  `g3` varchar(64) GENERATED ALWAYS AS ((`p` / `q`)) VIRTUAL,
  `g4` varchar(64) GENERATED ALWAYS AS (((`p` * `q`) / 7)) VIRTUAL,
  `g5` varchar(64) GENERATED ALWAYS AS ((`q` + `p`)) VIRTUAL,
  `g6` varchar(64) GENERATED ALWAYS AS ((1.5 * `q`)) VIRTUAL,
  `g7` varchar(64) GENERATED ALWAYS AS (concat_ws(_utf8mb4'-',`s`,`q`,`p`)) VIRTUAL,
  `g8` int GENERATED ALWAYS AS ((`q` * 2)) VIRTUAL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;"""


def mysql_numbers(q, p, s):
    """What MySQL 8.0 stores in g1..g8: DECIMAL text keeps its scale; a / b has the dividend's scale + 4
    (div_precision_increment) rounded half away from zero, NULL for a zero divisor; + - keep the larger scale,
    * adds the scales; CONCAT is NULL with a NULL argument; CONCAT_WS skips NULL values."""
    from decimal import Decimal, ROUND_HALF_UP

    def div(a, b, scale):
        if a is None or b is None or b == 0:
            return None
        return str((Decimal(a) / Decimal(b)).quantize(Decimal(1).scaleb(-scale), rounding=ROUND_HALF_UP))

    p = None if p is None else Decimal(p)
    g1 = None if p is None or q is None else f"{p:.2f}/{q}"
    g4 = None if p is None or q is None else div(p * q, 7, 6)
    g5 = None if p is None or q is None else f"{Decimal(q) + p:.2f}"
    g6 = None if q is None else f"{Decimal('1.5') * q:.1f}"
    g7 = "-".join(v for v in (s, None if q is None else str(q), None if p is None else f"{p:.2f}") if v is not None)
    g8 = None if q is None else str(q * 2)
    return [g1, div(q, 2, 4), div(p, q, 6), g4, g5, g6, g7, g8]


class TestNumbersWrittenAsText:
    def test_translation(self, loader):
        ddl, _ = translate(loader, NUMBERS_DDL)
        lines = column_lines(ddl)
        assert lines["`g1`"] == "`g1` varchar(64) NULL DEFAULT concat(toDecimalString(`p`, 2),'/',`q`)"
        assert lines["`g2`"] == ("`g2` varchar(64) NULL DEFAULT toDecimalString(if(ifNull(toDecimal256(2, 0) = 0, 1), "
                                 "NULL, toDecimal256(round(divideDecimal(toDecimal256(`q`, 0), coalesce(nullIf("
                                 "toDecimal256(2, 0), 0), toDecimal256(1, 0)), 5), 4), 4)), 4)")
        assert lines["`g7`"] == ("`g7` varchar(64) NULL DEFAULT arrayStringConcat(arrayFilter(__v -> __v IS NOT NULL, "
                                 "[toString(`s`), toString(`q`), toDecimalString(`p`, 2)]), '-')")
        assert lines["`g8`"] == "`g8` int NULL DEFAULT (`q` * 2)"  # a numeric column: passed through

    @needs_clickhouse
    def test_values_equal_mysql(self, loader):
        ddl, _ = translate(loader, NUMBERS_DDL)
        inputs = [(1, 3, "1.50", "x"), (2, -7, "-2.25", None), (3, 0, "0.10", "y"), (4, None, None, None),
                  (5, 2, "1.00", ""), (6, 3, "2.00", "z")]
        rows = computed(ddl, ["id", "q", "p", "s"], inputs, ", ".join(f"g{i}" for i in range(1, 9)))
        assert rows == [["\\N" if v is None else v for v in mysql_numbers(q, p, s)] for (_, q, p, s) in inputs]

    @needs_clickhouse
    def test_clickhouse_text_differs_without_the_translation(self):
        # toString(3/2) is 1.5 (MySQL 1.5000), concat_ws returns NULL for a NULL value (MySQL skips it).
        assert run_clickhouse("SELECT toString(3/2), concat_ws('-', 'a', NULL, 'b') FORMAT TSV") == [["1.5", "\\N"]]

    @pytest.mark.parametrize("expression,divergence", [
        ("concat(`f`,_utf8mb4'x')", "FLOAT/DOUBLE"),
        ("concat(`bt`,_utf8mb4'x')", "BIT(1)"),
        ("(`f` / 2)", "quotient"),
    ])
    def test_divergences_that_loaded_before_still_load_with_a_warning(self, loader, expression, divergence, caplog):
        ddl_in = one_generated_column(expression).replace(
            "  `s` varchar(10) DEFAULT NULL,\n", "  `s` varchar(10) DEFAULT NULL,\n  `bt` bit(1) DEFAULT NULL,\n")
        with caplog.at_level(logging.WARNING):
            ddl, _ = translate(loader, ddl_in)
        assert column_lines(ddl)["`g`"] == "`g` varchar(64) NULL DEFAULT " + previous_rendering(expression)
        warnings = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
        assert any("`t`" in w and "generated column `g`" in w and divergence in w for w in warnings), warnings
        if CLICKHOUSE is not None:
            assert len(computed(ddl, ["id", "a", "b", "s"], [(1, 3, 4, "q")], "id, g")) == 1


# Decimal results stored in DECIMAL generated columns.
DECIMAL_DDL = """CREATE TABLE `t` (
  `id` int NOT NULL,
  `q` int DEFAULT NULL,
  `r` int DEFAULT NULL,
  `p` decimal(10,2) DEFAULT NULL,
  `g1` decimal(10,4) GENERATED ALWAYS AS ((`q` / `r`)) VIRTUAL,
  `g2` decimal(10,1) GENERATED ALWAYS AS ((`p` * 3)) VIRTUAL,
  `g3` decimal(12,2) GENERATED ALWAYS AS ((`p` / `r`)) STORED,
  `g4` decimal(10,4) GENERATED ALWAYS AS (((`q` / `r`) + `p`)) VIRTUAL,
  `g5` decimal(10,2) GENERATED ALWAYS AS ((`q` * `r`)) VIRTUAL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;"""
DECIMAL_SCALES = {"g1": 4, "g2": 1, "g3": 2, "g4": 4, "g5": 2}


def mysql_decimals(q, r, p):
    """What MySQL 8.0 stores in g1..g5: the exact value with MySQL's scale rules (/ : dividend scale + 4, rounded
    half away from zero; NULL for a zero or NULL divisor), then rounded half away from zero to the column's
    scale on assignment."""
    from decimal import Decimal, ROUND_HALF_UP

    def at(value, scale):
        return None if value is None else value.quantize(Decimal(1).scaleb(-scale), rounding=ROUND_HALF_UP)

    def div(a, b, scale):
        return None if a is None or b is None or b == 0 else at(Decimal(a) / Decimal(b), scale)

    p = None if p is None else Decimal(p)
    q_r = div(q, r, 4)
    values = {
        "g1": q_r,
        "g2": None if p is None else p * 3,
        "g3": div(p, r, 6),
        "g4": None if q_r is None or p is None else q_r + p,
        "g5": None if q is None or r is None else Decimal(q * r),
    }
    return [None if values[g] is None else f"{at(values[g], DECIMAL_SCALES[g]):.{DECIMAL_SCALES[g]}f}"
            for g in sorted(DECIMAL_SCALES)]


class TestDecimalColumns:
    def test_translation(self, loader):
        ddl, _ = translate(loader, DECIMAL_DDL)
        lines = column_lines(ddl)
        assert lines["`g1`"] == ("`g1` decimal(10,4) NULL DEFAULT round(if(ifNull(toDecimal256(`r`, 0) = 0, 1), NULL, "
                                 "toDecimal256(round(divideDecimal(toDecimal256(`q`, 0), coalesce(nullIf("
                                 "toDecimal256(`r`, 0), 0), toDecimal256(1, 0)), 5), 4), 4)), 4)")
        assert lines["`g2`"] == "`g2` decimal(10,1) NULL DEFAULT round((toDecimal256(`p`, 2) * toDecimal256(3, 0)), 1)"
        assert lines["`g5`"] == "`g5` decimal(10,2) NULL DEFAULT (`q` * `r`)"  # integers: exact, passed through

    @needs_clickhouse
    def test_values_equal_mysql(self, loader):
        ddl, _ = translate(loader, DECIMAL_DDL)
        inputs = [(1, 2, 3, "0.05"), (2, -1, 2, "-0.05"), (3, 1, 0, "1.00"), (4, 5, None, None), (5, 1, 8, "2.25"),
                  (6, -2, 3, "-2.25"), (7, 7, -2, "0.35")]
        select = ", ".join(f"toDecimalString({g}, {s})" for (g, s) in sorted(DECIMAL_SCALES.items()))
        rows = computed(ddl, ["id", "q", "r", "p"], inputs, select)
        assert rows == [["\\N" if v is None else v for v in mysql_decimals(q, r, p)] for (_, q, r, p) in inputs]

    @needs_clickhouse
    def test_clickhouse_truncates_without_the_translation(self):
        # ClickHouse alone: 2/3 is a Float64, the cast to Decimal(10,4) truncates (MySQL stores 0.6667), and a
        # cast of 0.15 to one decimal truncates to 0.1 (MySQL 0.2).
        assert run_clickhouse("SELECT toDecimalString(CAST(2/3 AS Decimal(10,4)), 4), "
                              "toDecimalString(CAST(toDecimal64(0.15, 2) AS Decimal(10,1)), 1) FORMAT TSV") == \
            [["0.6666", "0.1"]]


class TestRegexpFallback:
    def test_regexp_fallback_refuses_generated_columns(self, loader):
        with pytest.raises(loader.UnsafeTableDefinitionError, match="generated"):
            loader.convert_to_clickhouse_table_regexp("u", "t", BIT_FLAGS_DDL, True, None)
