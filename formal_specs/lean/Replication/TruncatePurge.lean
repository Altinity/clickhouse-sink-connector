/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
-- DESTRUCTIVE: nothing is destroyed; this model classifies TRUNCATE and DROP statements as values and runs nothing.
# A skipped TRUNCATE is loud, and a purged binlog is named (specs 06.08 section 3.3 item 5, 01.07 FM-01.07-5 and FM-01.07-7)

spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::performDDLOperation
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::handleEngineCompletion
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogServerIdPolicy.java::isSameServerIdCollision

-- DESTRUCTIVE: nothing is destroyed; the request text names TRUNCATE and DROP, and this spec executes neither.
Request: loudness fixes. Under `disable.drop.truncate=true` a skipped TRUNCATE was logged at WARN with
the same text as a skipped DROP; a purged binlog (MySQL error 1236 "Could not find first log file name in
binary log index file") was classified as a server_id collision because the bare code 1236 matched.

Properties:

-- DESTRUCTIVE: nothing is destroyed; the property names TRUNCATE and DROP as skipped statements, and nothing runs.
* **I-A (skip stays, and is loud).** A statement is skipped only when the flag is on, it is a TRUNCATE or a
  DROP, and it is not snapshot DDL. A skipped TRUNCATE is logged at ERROR, a skipped DROP at WARN, and a
  skipped statement is never executed. With the flag off, or for snapshot DDL, nothing is skipped.
* **I-B (a purged binlog is noticed, not blamed).** A failure whose text reports a purged binlog gets one
  ERROR purge notice, and it is not a server_id collision. The code 1236 alone never makes a collision: only
  the `same server_uuid/server_id` text does. The pre-fix code blamed server_id for the purged binlog; the
  witness theorems record that historical behaviour without making it a failing spec.

Model bounds: the DDL classifier and the message matching are abstracted to the facts the decisions read
(`Stmt`, and the three booleans of `Failure`). The retry decision is not modelled: it is unchanged by this fix.
-/

namespace Replication

-- DESTRUCTIVE: nothing is destroyed; the namespace only groups the model of TRUNCATE and DROP classification.
namespace TruncatePurge

/-- The level of a logged line. -/
inductive Level where
  | warn
  | error
  deriving DecidableEq, Repr

-- DESTRUCTIVE: nothing is destroyed; the constructors below are values for TRUNCATE and DROP statements, not runs.
/-- A DDL statement after classification: a TRUNCATE of a numbered table, a DROP, or any other statement. -/
inductive Stmt where
  | truncate (table : Nat)
  | drop
  | other
  deriving DecidableEq, Repr

-- DESTRUCTIVE: nothing is destroyed; the skip rule decides whether a TRUNCATE or DROP value runs, and no database is touched.
/-- The skip rule of `performDDLOperation`: flag on, a TRUNCATE or DROP, and not snapshot DDL. -/
def skipped (flag snapshot : Bool) : Stmt → Bool
  | .truncate _ => flag && !snapshot
  | .drop => flag && !snapshot
  | .other => false

/-- The level of the line logged for a skipped statement (after the fix). -/
def skipLevel : Stmt → Option Level
  -- DESTRUCTIVE: nothing is destroyed; this arm gives the level a skipped TRUNCATE value is logged at, and nothing runs.
  | .truncate _ => some .error
  | .drop => some .warn
  | .other => none

/-- The level logged for a statement: the skip line when it is skipped, nothing otherwise. -/
def loggedLevel (flag snapshot : Bool) (s : Stmt) : Option Level :=
  if skipped flag snapshot s then skipLevel s else none

-- DESTRUCTIVE: nothing is destroyed; the pre-fix level of a skipped TRUNCATE is described as data, and no statement runs.
/-- Before the fix every skipped statement, TRUNCATE included, was logged at WARN. -/
def baseLoggedLevel (flag snapshot : Bool) (s : Stmt) : Option Level :=
  if skipped flag snapshot s then some .warn else none

/-- A statement is executed exactly when it is not skipped. -/
def executes (flag snapshot : Bool) (s : Stmt) : Bool :=
  !(skipped flag snapshot s)

-- DESTRUCTIVE: nothing is destroyed; the theorem below checks the log level of a skipped TRUNCATE, and no TRUNCATE runs.
/-- I-A: a TRUNCATE skipped under the flag is logged at ERROR. -/
theorem truncate_skip_is_error (t : Nat) :
    loggedLevel true false (.truncate t) = some .error := rfl

-- DESTRUCTIVE: nothing is destroyed; the theorem below checks the log level of a skipped DROP, and no DROP runs.
/-- I-A: a DROP skipped under the flag stays at WARN. -/
theorem drop_skip_is_warn : loggedLevel true false .drop = some .warn := rfl

/-- I-A: with the flag off nothing is skipped and nothing is logged as skipped. -/
theorem flag_off_logs_nothing (snapshot : Bool) (s : Stmt) :
    loggedLevel false snapshot s = none := by
  cases s <;> rfl

/-- I-A: snapshot DDL is not skipped, so it is executed. -/
theorem snapshot_ddl_is_not_skipped (flag : Bool) (s : Stmt) :
    skipped flag true s = false := by
  cases s <;> simp [skipped]

/-- I-A: a skipped statement is not executed. -/
theorem skipped_is_not_executed (flag snapshot : Bool) (s : Stmt)
    (h : skipped flag snapshot s = true) : executes flag snapshot s = false := by
  simp [executes, h]

-- DESTRUCTIVE: nothing is destroyed; this theorem only says that other statements are not skipped, and nothing runs.
/-- I-A: a statement that is not a TRUNCATE or DROP is never skipped. -/
theorem other_never_skipped (flag snapshot : Bool) : loggedLevel flag snapshot .other = none := rfl

-- DESTRUCTIVE: nothing is destroyed; the witness records the pre-fix WARN for a skipped TRUNCATE, and no TRUNCATE runs.
/-- Historical witness (not a failing spec): the pre-fix code logged a skipped TRUNCATE at WARN, which
    violates I-A. -/
theorem base_truncate_skip_was_warn :
    baseLoggedLevel true false (.truncate 7) = some .warn := rfl

-- DESTRUCTIVE: nothing is destroyed; this witness shows the pre-fix level breaks the ERROR rule, and no TRUNCATE runs.
theorem base_truncate_skip_violates_error_rule :
    baseLoggedLevel true false (.truncate 7) ≠ some .error := by
  decide

/-- The facts a failure's message texts carry, for the causes the connector inspects. -/
structure Failure where
  code1236 : Bool
  purgedText : Bool
  sameServerText : Bool
  deriving DecidableEq, Repr

/-- MySQL error 1236 for a purged binlog: the code and the purged text, not the server_uuid/server_id text. -/
def purgedBinlog : Failure :=
  { code1236 := true, purgedText := true, sameServerText := false }

/-- MySQL error 1236 for a second binlog client with the same server_id. -/
def sameServerIdFailure : Failure :=
  { code1236 := true, purgedText := false, sameServerText := true }

/-- After the fix: a server_id collision is recognised by the server_uuid/server_id text only. -/
def collision (f : Failure) : Bool := f.sameServerText

/-- Before the fix: the code 1236 alone counted as a collision, so a purged binlog was blamed on server_id. -/
def baseCollision (f : Failure) : Bool := f.code1236 || f.sameServerText

/-- The ERROR that names a server_id collision (after the fix). -/
def serverIdNote (f : Failure) : Option Level :=
  if collision f then some .error else none

/-- The ERROR purge notice (FM-01.07-5), one per failed attempt that reports a purged binlog. -/
def purgeNote (f : Failure) : Option Level :=
  if f.purgedText then some .error else none

/-- I-B: the purged binlog gets the ERROR purge notice. -/
theorem purged_binlog_is_noticed : purgeNote purgedBinlog = some .error := rfl

/-- I-B: the purged binlog is not a server_id collision, so no server_id note is written. -/
theorem purged_binlog_not_collision : collision purgedBinlog = false := rfl

theorem purged_binlog_has_no_server_id_note : serverIdNote purgedBinlog = none := rfl

/-- I-B: a real server_id collision is still named. -/
theorem same_server_id_is_named : serverIdNote sameServerIdFailure = some .error := rfl

/-- Historical witness (not a failing spec): the pre-fix classifier blamed server_id for the purged binlog. -/
theorem base_blamed_purged_binlog : baseCollision purgedBinlog = true := rfl

/-- I-B for every failure: a failure that reports a purged binlog, and not the server_uuid/server_id text,
    never produces the server_id note. -/
theorem purged_never_blames_server_id (f : Failure) (hp : f.purgedText = true)
    (hs : f.sameServerText = false) : serverIdNote f = none := by
  simp [serverIdNote, collision, hs]

/-- I-B for every failure: the code 1236 alone, without the server_uuid/server_id text, is not a collision. -/
theorem code_alone_is_not_collision (f : Failure) (hs : f.sameServerText = false) :
    collision f = false := hs

-- DESTRUCTIVE: nothing is destroyed; this closes the namespace of the TRUNCATE and DROP classification model.
end TruncatePurge

end Replication
