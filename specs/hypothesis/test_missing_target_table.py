"""Property tests for the tri-state target-table probe and its classification.

spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java::isTableAbsent
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java::isTargetTableAbsent
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java::processBatchRecords
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java::processRecordsByTopic
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java::classify
request: a staging connector stopped every 13 minutes for hours on a dropped target table (2026-11-09)
spec: specs/08-schema-catalog/07-missing-target-table-failure.md, invariants 3.1-3.7

The property in words. An unresolved writer -- an empty column map -- is produced by
three states: the table does not exist, the catalog could not be read, and the
connection was never established. Only the first can never settle by retrying, and the
empty map does not say which happened. So the probe answers three values; both
collapse directions are wrong -- absence for an unreadable catalog stops a healthy
connector on a blip, and "could not answer" read as presence restores the unbounded
retry this change removes.

The new exception carries no `Code: NNN`, so code extraction alone files it UNKNOWN
and UNKNOWN is retried. Registering the type makes it FATAL, and the registration must
reach it through the wrapper the single-threaded engine adds without widening into
"every batch failure stops the connector".

This is a MODEL of the two decisions, not the Java: the artifact needs a Maven build
with the ClickHouse client jar. The model is pinned to the Java by
`test_vectors_asserted_by_the_java_unit_tests`, which repeats the exact tuples
`ClickHouseErrorClassifierFailureModesTest` asserts against the real classifier, and
by the FATAL code set, copied from `ClickHouseErrorClassifier.FATAL_ERROR_CODES`.

Run: `pytest specs/hypothesis/test_missing_target_table.py`
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
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java",
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java",
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java",
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java",
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java",
)


def test_the_modelled_java_sources_still_exist() -> None:
    """The model's subject: a rename here owes this spec an update."""
    root = Path(__file__).resolve().parents[2]
    missing = [p for p in JAVA_SOURCES if not (root / p).is_file()]
    assert not missing, f"modelled Java source(s) missing: {missing}"

# ---------------------------------------------------------------- the probe


class Catalog(Enum):
    """What a live `system.tables` read produced."""

    ABSENT = "absent"
    PRESENT = "present"
    UNREADABLE = "unreadable"


class Probe(Enum):
    """DBMetadata.isTableAbsent: TRUE / FALSE / null."""

    IS_TRUE = "TRUE"
    IS_FALSE = "FALSE"
    IS_NONE = "null"


class Action(Enum):
    STOP = "stop"
    RETRY = "retry"


def probe_answers(catalog: Catalog) -> Probe:
    """DBMetadata.isTableAbsent. A pure function of the catalog: never cached."""
    return {
        Catalog.ABSENT: Probe.IS_TRUE,
        Catalog.PRESENT: Probe.IS_FALSE,
        Catalog.UNREADABLE: Probe.IS_NONE,
    }[catalog]


def is_target_table_absent(catalog: Catalog) -> bool:
    """DbWriter.isTargetTableAbsent: Boolean.TRUE.equals(...)."""
    return probe_answers(catalog) is Probe.IS_TRUE


def decide(metadata_retrieved: bool, catalog: Catalog) -> Action:
    """The batch path. The probe is reached only when the writer is unresolved."""
    if metadata_retrieved:
        return Action.RETRY
    return Action.STOP if is_target_table_absent(catalog) else Action.RETRY


def decide_before(metadata_retrieved: bool, catalog: Catalog) -> Action:
    """The behaviour before this change: an unresolved writer always retried."""
    return Action.RETRY


# ------------------------------------------------------- the classification


class Thrown(Enum):
    """What a batch can throw, at the granularity `classify` cares about."""

    MISSING_TABLE = "missingTable"
    WITH_CODE = "withCode"
    NO_CODE = "noCode"
    WRAPPER = "wrapper"


@st.composite
def thrown_chains(draw, max_depth=4):
    """A cause chain: a leaf, wrapped 0..max_depth times.

    Returns `(thrown, code, terminal_at_root)` where `code` is the first parseable
    code in the chain or -1, and `terminal_at_root` says whether a terminal type
    is anywhere in it.
    """
    depth = draw(st.integers(min_value=0, max_value=max_depth))
    leaf = draw(st.sampled_from([Thrown.MISSING_TABLE, Thrown.WITH_CODE, Thrown.NO_CODE]))
    if leaf is Thrown.WITH_CODE:
        code = draw(st.integers(min_value=-5, max_value=1000))
        terminal = False
    elif leaf is Thrown.MISSING_TABLE:
        code = -1
        terminal = True
    else:
        code = -1
        terminal = False
    return depth, leaf, code, terminal


# FATAL_ERROR_CODES in ClickHouseErrorClassifier, copied verbatim.
FATAL_ERROR_CODES = frozenset({516, 497, 50, 53, 60, 81, 16, 396, 27, 33, 69, 349})


def classify(depth: int, leaf: Thrown, code: int, terminal: bool) -> str:
    """ClickHouseErrorClassifier.classify over the chain."""
    if terminal:  # hasTerminalCause walks the whole chain first
        return "FATAL"
    if code < 0:
        return "UNKNOWN"
    return "FATAL" if code in FATAL_ERROR_CODES else "RETRIABLE"


# ------------------------------------------------------------------- tests


def test_vectors_asserted_by_the_java_unit_tests() -> None:
    """The exact tuples ClickHouseErrorClassifierFailureModesTest asserts."""
    # missingTargetTableIsFatal / missingTargetTableWrappedInBatchFailureIsFatal
    assert classify(1, Thrown.MISSING_TABLE, -1, True) == "FATAL"
    assert classify(0, Thrown.MISSING_TABLE, -1, True) == "FATAL"
    # plainBatchFailureIsNotFatal: a wrapper with no cause and no code
    assert classify(1, Thrown.NO_CODE, -1, False) == "UNKNOWN"
    # selfHealingServerConditionsAreRetriable: 202 is NOT fatal
    assert classify(1, Thrown.WITH_CODE, 202, False) == "RETRIABLE"
    # privilegeErrorsOutsideTheFatalSetAreRetried: 164 and 291 are not in the set
    assert classify(0, Thrown.WITH_CODE, 164, False) == "RETRIABLE"
    assert classify(0, Thrown.WITH_CODE, 291, False) == "RETRIABLE"
    # ... and 497 is
    assert classify(0, Thrown.WITH_CODE, 497, False) == "FATAL"


@given(catalog=st.sampled_from(list(Catalog)))
def test_a_present_table_is_never_reported_absent(catalog) -> None:
    if catalog is Catalog.PRESENT:
        assert is_target_table_absent(catalog) is False
        assert decide(False, catalog) is Action.RETRY


@given(catalog=st.sampled_from(list(Catalog)))
def test_nothing_is_ever_absent_except_a_positively_absent_table(catalog) -> None:
    assert is_target_table_absent(catalog) is (catalog is Catalog.ABSENT)


@given(catalog=st.sampled_from(list(Catalog)))
def test_an_unreadable_catalog_is_never_reported_absent(catalog) -> None:
    if catalog is Catalog.UNREADABLE:
        assert is_target_table_absent(catalog) is False
        assert decide(False, catalog) is Action.RETRY


def test_the_probe_keeps_present_and_unknown_apart() -> None:
    """A boolean probe collapses these two, and both directions are wrong."""
    assert probe_answers(Catalog.PRESENT) is not probe_answers(Catalog.UNREADABLE)


@given(catalog=st.sampled_from(list(Catalog)), retrieved=st.booleans())
def test_the_batch_stops_if_and_only_if_the_absence_is_proven(retrieved, catalog) -> None:
    expected = Action.STOP if (not retrieved and catalog is Catalog.ABSENT) else Action.RETRY
    assert decide(retrieved, catalog) is expected


@given(catalog=st.sampled_from(list(Catalog)), retrieved=st.booleans())
def test_the_change_touches_the_absent_case_and_nothing_else(retrieved, catalog) -> None:
    changed = decide(retrieved, catalog) is not decide_before(retrieved, catalog)
    assert changed is (not retrieved and catalog is Catalog.ABSENT)


@given(catalogs=st.lists(st.sampled_from(list(Catalog)), min_size=1, max_size=20))
def test_an_unresolved_writer_never_acknowledges_while_the_table_is_missing(catalogs) -> None:
    """Section 3.7: the offset never leads the writes.

    A batch on a present table is written and acknowledged. A batch on a missing
    or unreadable catalog either stops (missing) or retries (unreadable), and
    neither acknowledges anything.
    """
    acknowledged = 0
    for catalog in catalogs:
        # The writer resolves -- metadata retrieved -- exactly when the catalog
        # lists the table. Everywhere else it is unresolved and the probe runs.
        metadata_retrieved = catalog is Catalog.PRESENT
        action = decide(metadata_retrieved, catalog)
        if action is Action.RETRY and metadata_retrieved:
            acknowledged += 1
        assert not (action is Action.RETRY and catalog is Catalog.ABSENT)
        assert not (action is Action.STOP and catalog is not Catalog.ABSENT)
    assert 0 <= acknowledged <= len(catalogs)


@given(catalog=st.sampled_from(list(Catalog)))
def test_a_recreated_table_is_picked_up_by_the_next_attempt(catalog) -> None:
    """Section 3.3: nothing is cached, so absence now and presence later is legal."""
    first = probe_answers(Catalog.ABSENT)
    second = probe_answers(catalog)
    assert first is Probe.IS_TRUE
    if catalog is Catalog.PRESENT:
        assert second is Probe.IS_FALSE


@given(chain=thrown_chains())
def test_a_terminal_type_anywhere_in_the_chain_is_fatal(chain) -> None:
    depth, leaf, code, terminal = chain
    assert (classify(depth, leaf, code, terminal) == "FATAL") or not terminal


@given(chain=thrown_chains())
def test_wrapping_never_changes_the_verdict(chain) -> None:
    """The wrapper the single-threaded engine adds must not hide the reason."""
    depth, leaf, code, terminal = chain
    assert classify(depth, leaf, code, terminal) == classify(
        depth + 1, leaf, code, terminal
    )


@given(code=st.integers(min_value=-1, max_value=1000))
def test_the_code_set_is_the_only_thing_beyond_the_terminal_types(code) -> None:
    verdict = classify(0, Thrown.WITH_CODE, code, False)
    if code < 0:
        assert verdict == "UNKNOWN"
    else:
        assert verdict == ("FATAL" if code in FATAL_ERROR_CODES else "RETRIABLE")
