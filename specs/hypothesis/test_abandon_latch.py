"""Property tests for the abandonment latch and the driver's batch answer.

spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::reset
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::hasUnprovenAbandonment
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java::noteAcknowledgedForAbandonment
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::commitControlRecordOffset
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::isPipelineQuiescent
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java::failOnExecuteFailed
request: reset() empties outstandingSequences while the units it abandons are still unacknowledged, and
hasUnwrittenBatches() is exactly `!outstandingSequences.isEmpty()`. The instant the FIFO was emptied the
pipeline read as quiescent, so the next control record -- whose offset is the connector's CURRENT position,
past the abandoned rows -- was committed, and a restart after that commit resumed AFTER them. On 2026-11-10
one deployment logged 23 such resets in five hours while a missing target table forced the handoff cap.
Separately, a driver-reported `Statement.EXECUTE_FAILED` entry was only counted into an INFO line while every
staged row was marked applied, so the batch was reported written and its offset acknowledged past the row.
spec: specs/09-offset-management/01-fifo-batch-tracking.md, section 3.6
spec: specs/09-offset-management/04-quiescent-control-commit.md, section 3 and FM-09.04-4
spec: specs/03-execution-engine/06-jdbc-batch-flushing.md, section 3.2 and FM-03.06-4

THE SAME MODEL AS specs/lean/AbandonLatch.lean AND specs/dafny/AbandonLatch.dfy, in three parts.

1. THE LATCH as a pure function of its five fields (the Lean `St` and the Dafny `St`): P1 a reset arms it
   exactly when it abandons a unit, and neither a handoff nor an acknowledgement arms it; P2 an armed latch
   withholds the control-record commit; P3 a post-reset acknowledgement clears it and a pre-reset one does
   not; P4 the drain keeps reading an abandoned FIFO as empty.

2. THE FINDING PROPERTY, on the identity-tracking walk of specs/tla/AbandonLatch.tla: the walk keeps WHICH
   positions are outstanding and which are in ClickHouse, so it can state the property the pure function
   cannot -- the durable offset never leads the writes (the operating invariant: the offset is always late,
   never early). Every state of every step sequence must satisfy it, and the window the 2026-11-10 run lived in
   must be reachable.

3. THE BATCH ANSWER: only `EXECUTE_FAILED` fails a chunk, `SUCCESS_NO_INFO` does not, and a failed chunk
   marks no row applied because the check runs before the marking.

The model is restated from the specifications, not imported from the code under test, so a change in the
implementation cannot silently move the model with it. The counter and limit constants below are the Java
ones, restated for the same reason.

Run: `pytest specs/hypothesis/test_abandon_latch.py`
"""

from dataclasses import dataclass, replace
from pathlib import Path

from hypothesis import given, settings
from hypothesis import strategies as st

PROFILE = settings(derandomize=True, database=None, deadline=None, max_examples=500)

# The Java sources this model describes, each a single literal: `spec_binding` reads the source TEXT, so a
# path split over two adjacent literals is not a mention it can see. A rename or a removal must fail here
# rather than pass quietly, which is what the check below is for.
JAVA_SOURCES = (
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java",
    "sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java",
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java",
)

# java.sql.Statement.EXECUTE_FAILED: the statement did not happen.
EXECUTE_FAILED = -3
# java.sql.Statement.SUCCESS_NO_INFO: the statement happened and the driver did not know the row count.
SUCCESS_NO_INFO = -2

# The bound on the identity-tracking walk, the same shape as the TLA+ `CONSTANTS MaxPos`.
MAX_POS = 4


def test_the_modelled_java_sources_still_exist() -> None:
    """A model of a file that no longer exists proves nothing about the shipped code."""
    root = Path(__file__).resolve().parents[2]
    missing = [p for p in JAVA_SOURCES if not (root / p).is_file()]
    assert not missing, "the modelled sources are gone: %s" % (missing,)


# ---------------------------------------------------------------------------------------------
# 1. The latch, as the pure function of its fields that Lean and Dafny prove it to be.
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Latch:
    """The five fields the latch decision reads and writes."""

    outstanding: int  # outstandingSequences.size()
    armed: bool  # hasUnprovenAbandonment()
    watermark: int  # unprovenAbandonmentBelowSequence
    next_seq: int  # handoffCounter
    acked_seq: int  # the highest sequence a worker reported durably written and acknowledged


def handoff(s):
    """A producer hands a unit off: a fresh sequence, one more outstanding unit."""
    return replace(s, outstanding=s.outstanding + 1, next_seq=s.next_seq + 1)


def reset(s):
    """reset(): retire every sequence assigned so far, empty the FIFO, arm the latch if it abandoned."""
    if s.outstanding > 0:
        return replace(s, outstanding=0, watermark=s.next_seq, armed=True)
    return replace(s, outstanding=0, watermark=s.next_seq)


def ack(s, seq):
    """noteAcknowledgedForAbandonment(seq)."""
    acked = max(seq, s.acked_seq)
    if s.armed and seq >= s.watermark:
        return replace(s, acked_seq=acked, armed=False)
    return replace(s, acked_seq=acked)


def old_gate(s):
    """The pre-fix control-record gate: the FIFO's emptiness alone."""
    return s.outstanding == 0


def commit_allowed(s):
    """The fixed control-record gate: the FIFO's emptiness AND the latch."""
    return s.outstanding == 0 and not s.armed


def drain_allowed(s):
    """drainBeforeStop / awaitPipelineQuiescent / the start-time refusal in setup()."""
    return s.outstanding == 0


def valid(s):
    """The watermark never runs ahead of the counter, and neither does an acknowledgement."""
    return s.watermark <= s.next_seq and s.acked_seq <= s.next_seq


latch_states = st.builds(
    Latch,
    outstanding=st.integers(min_value=0, max_value=8),
    armed=st.booleans(),
    watermark=st.integers(min_value=0, max_value=8),
    next_seq=st.integers(min_value=0, max_value=8),
    acked_seq=st.integers(min_value=0, max_value=8),
)


@PROFILE
@given(latch_states)
def test_p1_a_reset_arms_exactly_when_it_abandons(s) -> None:
    """P1: a reset arms the latch iff it abandoned a unit, or something had already armed it."""
    assert reset(s).armed == (s.outstanding > 0 or s.armed)


@PROFILE
@given(latch_states)
def test_p1_a_handoff_never_arms(s) -> None:
    """P1: a handoff is a read of the stream, never evidence that the abandonment is over."""
    assert handoff(s).armed == s.armed


@PROFILE
@given(latch_states, st.integers(min_value=0, max_value=8))
def test_p1_an_acknowledgement_never_arms(s, seq) -> None:
    """P1: an acknowledgement can only lower the latch."""
    assert ack(s, seq).armed is False or s.armed


@PROFILE
@given(latch_states)
def test_p2_an_armed_latch_blocks_the_control_record_commit(s) -> None:
    """P2: armed withholds the commit whatever the FIFO holds."""
    assert commit_allowed(replace(s, armed=True)) is False


@PROFILE
@given(latch_states)
def test_p2_the_pre_fix_gate_commits_over_the_abandonment(s) -> None:
    """P2: the same state the fixed gate refuses, the FIFO's emptiness alone lets through."""
    abandoned = reset(replace(s, outstanding=max(s.outstanding, 1)))
    assert abandoned.outstanding == 0
    assert old_gate(abandoned) is True
    assert commit_allowed(abandoned) is False


@PROFILE
@given(latch_states, st.integers(min_value=0, max_value=8))
def test_p3_a_post_reset_acknowledgement_clears_the_latch(s, seq) -> None:
    """P3: a unit assigned at or after the reset proves the redelivered stream reached the head."""
    abandoned = reset(replace(s, outstanding=max(s.outstanding, 1)))
    assert ack(abandoned, max(seq, abandoned.watermark)).armed is False


@PROFILE
@given(latch_states, st.integers(min_value=0, max_value=8))
def test_p3_a_pre_reset_acknowledgement_keeps_the_latch(s, seq) -> None:
    """P3: a unit that predates the reset says nothing about the redelivered stream."""
    armed = replace(s, armed=True)
    if seq < armed.watermark:
        assert ack(armed, seq).armed is True


@PROFILE
@given(latch_states)
def test_p4_the_latch_never_blocks_the_drain(s) -> None:
    """P4: the drain predicates keep reading an abandoned FIFO as empty, or the restart would be refused."""
    assert drain_allowed(reset(s)) is True
    assert drain_allowed(replace(s, armed=True)) == (s.outstanding == 0)


@PROFILE
@given(st.lists(st.sampled_from(("handoff", "reset", "ack")), max_size=16),
       st.integers(min_value=0, max_value=8))
def test_the_latch_fields_stay_well_formed_under_every_transition(names, seq) -> None:
    """No transition can put the watermark or the acknowledgement ahead of the counter."""
    s = Latch(outstanding=0, armed=False, watermark=0, next_seq=0, acked_seq=0)
    for name in names:
        if name == "handoff":
            s = handoff(s)
        elif name == "reset":
            s = reset(s)
        else:
            # A worker can only hold a sequence the counter has assigned, so the acknowledgement is
            # drawn from the sequences that exist rather than from the whole integer range.
            s = ack(s, seq % (s.next_seq + 1))
        assert valid(s), "%s broke well-formedness: %r" % (name, s)


@PROFILE
@given(st.lists(st.sampled_from(("handoff", "reset")), max_size=16))
def test_the_counter_is_never_reset_so_later_sequences_are_never_retired(names) -> None:
    """handoffCounter is monotone: a sequence assigned after a reset is always at or above the watermark."""
    s = Latch(outstanding=0, armed=False, watermark=0, next_seq=0, acked_seq=0)
    previous = s.next_seq
    for name in names:
        s = handoff(s) if name == "handoff" else reset(s)
        assert s.next_seq >= previous, "the counter went backwards: %r" % (s,)
        previous = s.next_seq


# ---------------------------------------------------------------------------------------------
# 2. The identity-tracking walk: which positions are outstanding, which are in ClickHouse.
#    The TLA+ model of specs/tla/AbandonLatch.tla in Python, bounded by MAX_POS.
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Walk:
    """next_pos positions have been handed off; `outstanding` and `covered` say what became of them."""

    next_pos: int = 0
    outstanding: frozenset = frozenset()
    covered: frozenset = frozenset()
    armed: bool = False
    watermark: int = 0
    committed: int = -1


def gaps(w):
    """The positions read so far and not in ClickHouse: what a reset can abandon."""
    return frozenset(p for p in range(w.next_pos) if p not in w.covered)


def head(w):
    """The offset FIFO's head. Acknowledgements are in order, so only this position can leave it."""
    return min(w.outstanding) if w.outstanding else None


def step_handoff(w):
    """The reader hands the next position off. The binlog is read sequentially, so it cannot move past a
    position it has not delivered: after a reset the replacement engine re-reads the abandoned positions
    before it hands off anything new. Without that guard the walk could let a fresh unit be delivered and
    acknowledged over an abandoned one, which no reader can do -- the same guard as `Handoff` in the TLA+."""
    if w.next_pos >= MAX_POS:
        return w
    if not gaps(w) <= w.outstanding:
        return w
    return replace(w, outstanding=w.outstanding | frozenset((w.next_pos,)), next_pos=w.next_pos + 1)


def step_write(w):
    pending = sorted(w.outstanding - w.covered)
    if not pending:
        return w
    return replace(w, covered=w.covered | frozenset((pending[0],)))


def step_ack(w):
    h = head(w)
    if h is None or h not in w.covered:
        return w
    return replace(w, outstanding=w.outstanding - frozenset((h,)),
                   armed=w.armed and not (h >= w.watermark))


def step_reset(w):
    if not w.outstanding:
        return w
    g = gaps(w)
    return replace(w, outstanding=frozenset(), armed=True,
                   watermark=w.next_pos if not g else min(g))


def step_redeliver(w):
    g = gaps(w)
    if g <= w.outstanding:
        return w
    return replace(w, outstanding=w.outstanding | g)


def step_commit(w):
    if w.outstanding or w.armed or w.next_pos == 0:
        return w
    return replace(w, committed=w.next_pos - 1)


STEPS = {
    "handoff": step_handoff,
    "write": step_write,
    "ack": step_ack,
    "reset": step_reset,
    "redeliver": step_redeliver,
    "commit": step_commit,
}


def gate_open(w):
    """The fixed control-record gate."""
    return not w.outstanding and not w.armed


@PROFILE
@given(st.lists(st.sampled_from(sorted(STEPS)), max_size=24))
def test_the_durable_offset_never_leads_the_writes(names) -> None:
    """Every reachable state: what the offset covers is in ClickHouse, and an open gate means nothing is
    missing below the reader's head. This is the property the pre-fix gate did not have."""
    w = Walk()
    for name in names:
        w = STEPS[name](w)
        g = gaps(w)
        assert w.next_pos <= MAX_POS
        assert w.outstanding <= frozenset(range(MAX_POS))
        assert w.covered <= frozenset(range(MAX_POS))
        assert w.committed <= w.next_pos - 1
        assert all(p in w.covered for p in range(w.committed + 1)), (
            "after %s the offset covers a position that is not in ClickHouse: %r" % (name, w))
        assert g <= w.outstanding or w.armed, (
            "after %s an uncovered position below the head is neither outstanding nor latched: %r"
            % (name, w))
        if gate_open(w):
            assert g == frozenset(), (
                "after %s the gate is open over a position that is not in ClickHouse: %r" % (name, w))


def test_the_2026_11_10_window_is_reachable() -> None:
    """Two units handed off, none written, the engine reset: the FIFO reads empty while both positions are
    missing. The pre-fix gate commits here; the fixed gate refuses; the drain still proceeds."""
    w = Walk()
    w = step_handoff(w)
    w = step_handoff(w)
    w = step_reset(w)
    assert w.outstanding == frozenset()
    assert w.armed is True
    assert gaps(w) == frozenset((0, 1))
    assert old_gate(Latch(len(w.outstanding), w.armed, w.watermark, w.next_pos, 0)) is True
    assert gate_open(w) is False
    assert drain_allowed(Latch(len(w.outstanding), w.armed, w.watermark, w.next_pos, 0)) is True


def test_a_redelivery_is_what_clears_the_window() -> None:
    """Not a timer and not an emptiness check: only the re-read reaching the FIFO's head clears the latch."""
    w = Walk()
    w = step_handoff(w)
    w = step_handoff(w)
    w = step_reset(w)
    w = step_redeliver(w)
    assert w.outstanding == frozenset((0, 1))
    w = step_write(w)
    w = step_write(w)
    w = step_ack(w)
    w = step_ack(w)
    assert w.armed is False
    assert w.outstanding == frozenset()
    w = step_commit(w)
    assert w.committed == 1


# ---------------------------------------------------------------------------------------------
# 3. The driver's batch answer.
# ---------------------------------------------------------------------------------------------


def failed_count(result):
    """Entries the driver flagged as not executed."""
    return sum(1 for entry in result if entry == EXECUTE_FAILED)


def batch_reported_written(result):
    """failOnExecuteFailed: the chunk is reported written only when the driver flagged nothing."""
    return failed_count(result) == 0


def rows_marked_applied(result):
    """Rows marked setAppliedToClickHouse(true): every staged row, but only when the chunk is written."""
    return len(result) if failed_count(result) == 0 else 0


def batch_reported_written_before(result):
    """The pre-fix executor: the flagged entry was counted into an INFO line and the chunk still counted."""
    return True


batch_results = st.lists(st.sampled_from((EXECUTE_FAILED, SUCCESS_NO_INFO, 0, 1, 7)), max_size=12)


@PROFILE
@given(batch_results)
def test_the_flagged_count_never_exceeds_the_answer(result) -> None:
    assert 0 <= failed_count(result) <= len(result)


@PROFILE
@given(batch_results)
def test_a_flagged_chunk_is_reported_unwritten_and_marks_no_row(result) -> None:
    if failed_count(result) > 0:
        assert batch_reported_written(result) is False
        assert rows_marked_applied(result) == 0
        assert batch_reported_written_before(result) is True


@PROFILE
@given(batch_results)
def test_a_clean_chunk_marks_every_row_applied(result) -> None:
    if failed_count(result) == 0:
        assert batch_reported_written(result) is True
        assert rows_marked_applied(result) == len(result)


@PROFILE
@given(st.integers(min_value=-8, max_value=8))
def test_success_no_info_is_a_success(entry) -> None:
    """Only EXECUTE_FAILED fails the chunk; SUCCESS_NO_INFO is a success with an unknown row count."""
    if entry != EXECUTE_FAILED:
        assert batch_reported_written([entry]) is True


@PROFILE
@given(st.integers(min_value=0, max_value=6), st.integers(min_value=0, max_value=6))
def test_one_flagged_entry_fails_the_whole_chunk(clean_before, clean_after) -> None:
    result = [1] * clean_before + [EXECUTE_FAILED] + [1] * clean_after
    assert batch_reported_written(result) is False
    assert rows_marked_applied(result) == 0
