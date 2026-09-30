/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# Streaming decode of a compressed transaction payload (spec 01.08 §3.2.1)

A `Transaction_payload` decompresses to the concatenation of the transaction's binlog v4
events, each announcing its own length in its header. The connector's decoder reads that
stream ONE event at a time instead of materializing it. This module models the stream as
length-prefixed byte blocks and proves:

* `step_encode` — one read step on the encoding of `b :: bs` yields exactly `b` and leaves
  exactly the encoding of `bs`: an event is recovered from its own prefix, nothing after it
  is needed.
* `step_retains_only_rest` — a step's result is the event it read plus the unread suffix;
  nothing of an earlier event survives a step (the decoder's memory is the current event,
  not the transaction).
* `decode_encode` — for EVERY list of events, of any count and any sizes (there is no bound
  on the total length: sizes are `Nat`, the analogue of the 64-bit header field), the
  streaming decoder returns exactly the events that were encoded, in order.
* `encode_length` — the decoded byte count equals the sum of the event sizes, which is what
  the end-of-pass check compares with the header's declared uncompressed size.
* `step_short_fails` — an event announcing more bytes than remain is refused (`none`), never
  returned truncated: a cut stream fails loudly instead of ending the transaction early.
* `decode_fuel_mono` — once a pass has returned a result, running it with more fuel
  returns the SAME result: the decoded stream is a function of the retained bytes alone,
  not of how far the reader was allowed to run. (That repeated Java passes re-read the
  same retained bytes is established by the JUnit test `passesAreLazyAndRepeatable`;
  the pure model cannot express a destructive read, so it does not claim it.)
* `decode_short_fails` — a stream whose first event announces more bytes than remain is
  refused by the whole decoder, at every fuel: a truncated payload never decodes to a
  shorter transaction.
-/

namespace Replication.PayloadStream

/-- The bytes of one inner event body. -/
abbrev Body := List Nat

/-- The decompressed payload: every event as its length followed by its bytes. -/
def encode : List Body → List Nat
  | [] => []
  | b :: bs => b.length :: (b ++ encode bs)

/-- One read step: an event's length, then exactly that many bytes. -/
def step : List Nat → Option (Body × List Nat)
  | [] => none
  | n :: rest => if n ≤ rest.length then some (rest.take n, rest.drop n) else none

/-- The streaming decoder, with fuel bounding the number of steps. -/
def decode : Nat → List Nat → Option (List Body)
  | _, [] => some []
  | 0, _ :: _ => none
  | fuel + 1, n :: rest =>
    match step (n :: rest) with
    | none => none
    | some (b, r) => (decode fuel r).map (b :: ·)

theorem step_encode (b : Body) (bs : List Body) :
    step (encode (b :: bs)) = some (b, encode bs) := by
  simp [encode, step, List.take_left, List.drop_left]

theorem step_retains_only_rest (s : List Nat) (b : Body) (r : List Nat)
    (h : step s = some (b, r)) : 1 + b.length + r.length = s.length := by
  cases s with
  | nil => simp [step] at h
  | cons n rest =>
    simp only [step] at h
    split at h
    · rename_i hle
      simp only [Option.some.injEq, Prod.mk.injEq] at h
      obtain ⟨hb, hr⟩ := h
      subst hb; subst hr
      simp [List.length_take, List.length_drop, Nat.min_eq_left hle]
      omega
    · simp at h

theorem step_short_fails (n : Nat) (rest : List Nat) (h : rest.length < n) :
    step (n :: rest) = none := by
  simp [step]
  omega

theorem encode_length (bs : List Body) :
    (encode bs).length = (bs.map (fun b => 1 + b.length)).foldr (· + ·) 0 := by
  induction bs with
  | nil => simp [encode]
  | cons b bs ih =>
    simp [encode, ih]
    omega

theorem decode_encode (bs : List Body) (fuel : Nat) (hf : bs.length ≤ fuel) :
    decode fuel (encode bs) = some bs := by
  induction bs generalizing fuel with
  | nil => cases fuel <;> simp [encode, decode]
  | cons b bs ih =>
    cases fuel with
    | zero => simp at hf
    | succ f =>
      have hstep := step_encode b bs
      have hrest : decode f (encode bs) = some bs := ih f (by simp at hf; omega)
      simp only [encode] at hstep ⊢
      simp only [decode, hstep, hrest, Option.map_some']

/-- Decoding the whole stream with enough fuel: the number of events never exceeds its length. -/
theorem decode_encode_full (bs : List Body) :
    decode (encode bs).length (encode bs) = some bs := by
  apply decode_encode
  rw [encode_length]
  induction bs with
  | nil => simp
  | cons b bs ih => simp; omega

/-- More fuel never changes a result the decoder has already produced. -/
theorem decode_fuel_mono (f k : Nat) (s : List Nat) (r : List Body)
    (h : decode f s = some r) : decode (f + k) s = some r := by
  induction f generalizing s r with
  | zero =>
    cases s with
    | nil => simp [decode] at h ⊢; exact h
    | cons n rest => simp [decode] at h
  | succ f ih =>
    cases s with
    | nil => simp [decode] at h ⊢; exact h
    | cons n rest =>
      have e1 : decode (f + 1) (n :: rest) =
          (match step (n :: rest) with
            | none => none
            | some (b, r) => (decode f r).map (b :: ·)) := rfl
      have e2 : decode (f + 1 + k) (n :: rest) =
          (match step (n :: rest) with
            | none => none
            | some (b, r) => (decode (f + k) r).map (b :: ·)) := by
        rw [Nat.succ_add]; rfl
      rw [e1] at h
      rw [e2]
      cases hs : step (n :: rest) with
      | none => simp only [hs] at h
      | some p =>
        obtain ⟨b, r'⟩ := p
        simp only [hs] at h
        show (decode (f + k) r').map (b :: ·) = some r
        cases hd : decode f r' with
        | none => simp [hd] at h
        | some rs =>
          rw [ih r' rs hd]
          simpa [hd] using h

/-- A first event longer than the remaining bytes makes the whole pass fail, at any fuel. -/
theorem decode_short_fails (fuel n : Nat) (rest : List Nat) (h : rest.length < n) :
    decode fuel (n :: rest) = none := by
  cases fuel with
  | zero => simp [decode]
  | succ f => simp only [decode, step_short_fails n rest h]

end Replication.PayloadStream
