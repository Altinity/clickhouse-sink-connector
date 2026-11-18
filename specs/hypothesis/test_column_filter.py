"""Property tests for the column-list filter applied to translated schema DDL.

spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::keeps
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::from
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsCreateColumn
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsAlterColumn
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::declaresAnyKeptColumn
request: community issue 1579 -- with enable.snapshot.ddl the tables are created but
         column.include.list is ignored, so an excluded column such as a password is
         still created, empty (2026-11-11)
spec: specs/06-ddl-replication/04-alter-table-translation.md section 3.6,
      specs/08-schema-catalog/05-auto-table-creation.md section 3.1.2

The property in words. Debezium applies `column.include.list` / `column.exclude.list`
to the RECORD schema, so the writer never sends an excluded column's value. The
schema-DDL path translates the raw MySQL `CREATE TABLE` text instead and applied no
filter at all, so the two creation paths disagreed about one table: the DDL path built
the column and the record path never filled it. The fix asks the same matcher the
record path asks, once per declared column.

Three rules carry the fix, and every property below is one of them:

  an exclusion removes the column from the CREATE / ALTER it would otherwise emit;
  a key column is always kept, because ordering a ReplacingMergeTree by a column that
  was filtered out either references a missing column or changes which source rows
  collapse into one;
  a CREATE is never stranded with zero columns, because Debezium's key policy throws on
  one, so a statement whose every column the lists reject falls back to every source
  column -- and that fallback never widens any other statement;
  plus: an unjudged identifier (null or empty database or column name) is never
  rejected, since in CATALOG mode a null catalog matches nothing and would drop the
  whole table.

This is a MODEL of the two decisions, not the Java: the artifact needs a Maven build
with the Debezium and ClickHouse jars. The model is pinned to the Java by
`test_vectors_asserted_by_the_java_unit_tests`, which repeats the exact tuples
`DdlColumnFilterTest` and `DdlColumnFilterEmissionTest` assert against the real
classes, and by `JAVA_SOURCES`, which fails loudly if a modelled source is renamed.

Run: `pytest specs/hypothesis/test_column_filter.py`
"""

from __future__ import annotations

from enum import Enum
from pathlib import Path

from hypothesis import given, settings
from hypothesis import strategies as st

PROFILE = settings(derandomize=True, database=None, deadline=None, max_examples=500)

# The Java this model mirrors. `spec_binding` wants the path in code, not in the
# docstring above, and naming it here lets the check below fail loudly if one of
# these sources is renamed or deleted out from under the model.
JAVA_SOURCES = (
    "sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java",
    "sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java",
    "sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySQLDDLParserService.java",
)

# The test classes whose assertions pin the model, by the tuples they assert.
TEST_SOURCES = (
    "sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilterTest.java",
    "sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/DdlColumnFilterEmissionTest.java",
)


def test_the_modelled_java_sources_still_exist() -> None:
    """The model's subject: a rename here owes this spec an update."""
    root = Path(__file__).resolve().parents[2]
    missing = [p for p in JAVA_SOURCES + TEST_SOURCES if not (root / p).is_file()]
    assert not missing, f"modelled Java source(s) missing: {missing}"


# ------------------------------------------------------------ the filter itself


class Lists(Enum):
    """Which list `DdlColumnFilter` was built from."""

    UNSET = "unset"
    INCLUDE = "include"
    EXCLUDE = "exclude"


class Verdict(Enum):
    """What the Debezium matcher answers for one column.

    UNJUDGED is the empty answer: a null or empty database or column name, from which
    nothing can be decided.
    """

    UNJUDGED = "unjudged"
    SELECTED = "selected"
    REJECTED = "rejected"


def from_properties(include: str | None, exclude: str | None) -> Lists:
    """DdlColumnFilter.from. An include list, when set, is the whole rule."""
    if include is not None and include.strip():
        return Lists.INCLUDE
    if exclude is not None and exclude.strip():
        return Lists.EXCLUDE
    return Lists.UNSET


def keeps(lists: Lists, verdict: Verdict) -> bool:
    """DdlColumnFilter.keeps."""
    if lists is Lists.UNSET:
        return True
    return verdict is not Verdict.REJECTED


class Col:
    """A declared column: what the lists say about it, and whether the key names it."""

    __slots__ = ("verdict", "is_key")

    def __init__(self, verdict: Verdict, is_key: bool) -> None:
        self.verdict = verdict
        self.is_key = is_key

    def __repr__(self) -> str:
        return f"Col({self.verdict.value}, key={self.is_key})"

    def __eq__(self, other: object) -> bool:
        return (
            isinstance(other, Col)
            and self.verdict is other.verdict
            and self.is_key is other.is_key
        )

    def __hash__(self) -> int:
        return hash((self.verdict, self.is_key))


def keeps_column(lists: Lists, col: Col) -> bool:
    """keepsColumn: the key promotion."""
    return keeps(lists, col.verdict) or col.is_key


def any_raw_kept(lists: Lists, cols: tuple[Col, ...]) -> bool:
    """declaresAnyKeptColumn asks the RAW matcher, not keepsColumn."""
    return any(keeps(lists, c.verdict) for c in cols)


def bypassed(lists: Lists, cols: tuple[Col, ...]) -> bool:
    """columnFilterBypassedForCreate: configured, no column selected, some declared."""
    return lists is not Lists.UNSET and not any_raw_kept(lists, cols) and bool(cols)


def emitted(lists: Lists, cols: tuple[Col, ...], col: Col) -> bool:
    """What the CREATE emits for one column."""
    return keeps_column(lists, col) or bypassed(lists, cols)


def emitted_before(lists: Lists, cols: tuple[Col, ...], col: Col) -> bool:
    """The translator before this change: the DDL path applied no filter at all."""
    return True


# ---------------------------------------------------- the tables the tests use

# The statements DdlColumnFilterEmissionTest translates, as the columns they declare.
# The reported list is column.include.list = app.users.(id|name),app.orders.(id|user_id)
# and the reported table is
# CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), password VARCHAR(100)).
USERS = (Col(Verdict.SELECTED, True), Col(Verdict.SELECTED, False), Col(Verdict.REJECTED, False))
ALL_REJECTED = (Col(Verdict.REJECTED, False), Col(Verdict.REJECTED, False))
ONLY_REJECTED_KEY = (Col(Verdict.REJECTED, True),)
NO_LIST = (Col(Verdict.REJECTED, False), Col(Verdict.UNJUDGED, False))


# ------------------------------------------------------------------- vectors


def test_vectors_asserted_by_the_java_unit_tests() -> None:
    """The exact tuples DdlColumnFilterTest and DdlColumnFilterEmissionTest assert."""
    # DdlColumnFilterTest.noListKeepsEverything
    assert keeps(Lists.UNSET, Verdict.REJECTED) is True
    # DdlColumnFilterTest.includeListKeepsOnlyListedColumns
    assert keeps(Lists.INCLUDE, Verdict.SELECTED) is True
    assert keeps(Lists.INCLUDE, Verdict.REJECTED) is False
    # DdlColumnFilterTest.excludeListDropsOnlyListedColumns
    assert keeps(Lists.EXCLUDE, Verdict.SELECTED) is True
    assert keeps(Lists.EXCLUDE, Verdict.REJECTED) is False
    # DdlColumnFilterTest.includeWinsOverExclude
    assert from_properties("app.users.(id|name)", "app.users.password") is Lists.INCLUDE
    # DdlColumnFilterTest.blankListKeepsEverything
    assert from_properties("   ", None) is Lists.UNSET
    assert from_properties(None, None) is Lists.UNSET
    # DdlColumnFilterEmissionTest.noListCreatesEveryColumn
    assert all(emitted(Lists.UNSET, USERS, c) for c in USERS)
    # ...includeListKeepsTheExcludedColumnOutOfTheCreate: the password is gone, and the
    # emitted CREATE still ends in ORDER BY id
    assert [c for c in USERS if emitted(Lists.INCLUDE, USERS, c)] == list(USERS[:2])
    # ...keyColumnIsAlwaysCreatedWhenTheListExcludesIt
    assert emitted(Lists.EXCLUDE, ONLY_REJECTED_KEY, ONLY_REJECTED_KEY[0]) is True
    # ...noColumnOfTheTableIsKeptSoEveryColumnIsCreated
    assert all(emitted(Lists.INCLUDE, ALL_REJECTED, c) for c in ALL_REJECTED)
    # ...excludeListDropsOnlyTheListedColumn
    assert [c for c in USERS if emitted(Lists.EXCLUDE, USERS, c)] == list(USERS[:2])


# ---------------------------------------------------------------- properties


@given(verdict=st.sampled_from(list(Verdict)))
def test_no_list_keeps_every_column(verdict) -> None:
    assert keeps(Lists.UNSET, verdict) is True


@given(lists=st.sampled_from([Lists.INCLUDE, Lists.EXCLUDE]))
def test_a_configured_list_rejects_exactly_the_rejected_column(lists) -> None:
    assert keeps(lists, Verdict.REJECTED) is False
    assert keeps(lists, Verdict.SELECTED) is True


@given(lists=st.sampled_from(list(Lists)))
def test_an_unjudged_column_is_never_rejected(lists) -> None:
    """CATALOG mode answers "no" for a null catalog; a whole table would vanish."""
    assert keeps(lists, Verdict.UNJUDGED) is True
    assert keeps_column(lists, Col(Verdict.UNJUDGED, False)) is True


@given(lists=st.sampled_from(list(Lists)))
def test_a_key_column_is_always_kept(lists) -> None:
    for verdict in Verdict:
        assert keeps_column(lists, Col(verdict, True)) is True


columns = st.builds(Col, verdict=st.sampled_from(list(Verdict)), is_key=st.booleans())
tables = st.lists(columns, min_size=0, max_size=8).map(tuple)


@given(lists=st.sampled_from(list(Lists)), cols=tables)
def test_no_create_is_ever_stranded_with_zero_columns(lists, cols) -> None:
    """A CREATE of no columns is untranslatable: Debezium's key policy throws."""
    emitted_cols = [c for c in cols if emitted(lists, cols, c)]
    if cols:
        assert emitted_cols, f"{lists} filtered every column of {cols}"


@given(lists=st.sampled_from(list(Lists)), cols=tables)
def test_the_emission_never_invents_a_column(lists, cols) -> None:
    assert all(c in cols for c in cols if emitted(lists, cols, c))


@given(lists=st.sampled_from(list(Lists)), cols=tables)
def test_only_a_rejected_non_key_column_is_ever_dropped(lists, cols) -> None:
    dropped = [c for c in cols if not emitted(lists, cols, c)]
    assert all(c.verdict is Verdict.REJECTED and not c.is_key for c in dropped)
    # ...and only a configured list can drop anything at all.
    if lists is Lists.UNSET:
        assert not dropped


@given(lists=st.sampled_from(list(Lists)), cols=tables)
def test_the_change_touches_exactly_the_rejected_non_key_columns(lists, cols) -> None:
    for col in cols:
        assert (emitted(lists, cols, col) is not emitted_before(lists, cols, col)) is (
            not emitted(lists, cols, col)
        )


@given(cols=tables)
def test_a_table_with_a_selected_column_is_never_bypassed(cols) -> None:
    """The fallback stays narrow: it never widens past a table the lists still match."""
    for col in cols:
        if col.verdict is Verdict.SELECTED:
            assert bypassed(Lists.INCLUDE, cols) is False
            assert [c for c in cols if emitted(Lists.INCLUDE, cols, c)] == [
                c for c in cols if keeps_column(Lists.INCLUDE, c)
            ]
            break


@given(cols=tables)
def test_a_bypassed_create_keeps_every_source_column(cols) -> None:
    if bypassed(Lists.INCLUDE, cols):
        assert all(emitted(Lists.INCLUDE, cols, c) for c in cols)


@given(cols=tables)
def test_a_bypass_requires_a_declared_column(cols) -> None:
    if not cols:
        assert bypassed(Lists.INCLUDE, cols) is False


@given(statements=st.lists(st.sampled_from([USERS, ALL_REJECTED, ONLY_REJECTED_KEY, NO_LIST]),
                           min_size=1, max_size=12),
       lists=st.sampled_from(list(Lists)))
def test_no_statement_inherits_the_previous_statement_bypass(statements, lists) -> None:
    """The bypass flag is a field on the listener, which translates every statement.

    Section 3.6: the emission of a statement is a function of that statement alone, so
    a table created with every column does not carry that decision into the next one.
    """
    for cols in statements:
        # Recompute from scratch, exactly as a fresh statement does.
        if any_raw_kept(lists, cols):
            assert bypassed(lists, cols) is False
        for col in cols:
            assert emitted(lists, cols, col) is (keeps_column(lists, col) or bypassed(lists, cols))
