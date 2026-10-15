---
name: code-quality-go
description: >
  Use when writing or reviewing Go in this repo's client module
  (sink-connector-client), or when asked for "worldclass"/"idiomatic" Go.
  Covers error handling, interfaces, concurrency (goroutines/channels), and
  a deep review checklist for defect classes common in Go client libraries.
---

# Worldclass Go

## Overview
Idiomatic Go for `sink-connector-client`, the Go client for this project.
Favor explicit error handling, small interfaces, and straightforward
concurrency over cleverness — this is client-library code other programs
will depend on, so its public API and error contract matter more than
internal elegance.

## When to Use
- Writing or reviewing `.go` files in `sink-connector-client`
- Designing a Go public API, error type, or concurrency pattern
- A user asks for "worldclass / idiomatic" Go

## Core Rules

1. **Errors are values, checked explicitly.** Never ignore an error return
   (`_ = f()` is a red flag unless genuinely justified and commented).
   Wrap with context (`fmt.Errorf("doing X: %w", err)`) so a caller can
   `errors.Is`/`errors.As` without losing the chain.
2. **Small interfaces, defined by the consumer.** Prefer `io.Reader`-sized
   interfaces over large ones; accept interfaces, return concrete types.
3. **Avoid goroutine leaks.** Every goroutine must have a clear exit
   condition; use `context.Context` for cancellation and propagate it
   through the call chain rather than inventing a bespoke done-channel per
   function.
4. **Channels for ownership transfer, mutexes for shared state.** Don't
   reach for channels where a simple `sync.Mutex`-guarded field is clearer
   and cheaper.
5. **`defer` for cleanup**, close to the resource acquisition, including on
   error paths.
6. **Table-driven tests** (`[]struct{ ... }` + a loop) over repeated
   near-identical test functions.
7. **Keep the public API surface minimal** — exported types/functions are a
   compatibility promise for this client library; prefer unexported helpers
   unless something genuinely needs to be public.

```go
// BAD
data, _ := conn.Read()
go func() { process(data) }()  // no way to stop this goroutine

// GOOD
data, err := conn.Read()
if err != nil {
    return fmt.Errorf("reading from connection: %w", err)
}
go func(ctx context.Context) {
    select {
    case <-ctx.Done():
        return
    default:
        process(data)
    }
}(ctx)
```

## Deep Review Checklist (Go)
Run this over every changed `.go` file during `pr-self-review`'s
per-language pass.

1. **Ignored errors** — any `_ = f()` or unchecked return value; confirm it
   is genuinely safe to ignore, and comment why if so.
2. **Goroutine leaks** — a spawned goroutine with no cancellation path, or
   one that blocks forever on a channel nobody will ever write to/close.
3. **Nil pointer dereference on a zero-value struct** returned from a
   constructor-like function on an error path.
4. **Data races** — shared state written from more than one goroutine
   without a mutex/channel; run `go test -race` in CI and treat a race as a
   Blocker, not a flaky-test annoyance.
5. **Context propagation** — a long-running call (network I/O, retries)
   that doesn't accept and respect a `context.Context`, making it
   impossible for a caller to time out or cancel.
6. **Error wrapping loses the original type** — using `fmt.Errorf("%v",
   err)` instead of `%w` where a caller might need `errors.Is`/`errors.As`.
7. **Exported API churn** — a change to an exported type/function/field in
   this client library is a compatibility break for every consumer; flag it
   loudly even if the PR doesn't mention compatibility.
8. **Retry logic without backoff or a cap** — a client retrying a failed
   call to the connector or to ClickHouse with no backoff/limit can turn a
   transient failure into sustained load.

## Anti-Patterns
Ignored errors · unbounded goroutines with no cancellation · large
"kitchen sink" interfaces · mutating exported struct fields with no
accessor discipline where invariants need protecting · panics used for
ordinary error handling (reserve `panic` for truly unrecoverable
programmer errors).

## Sources
Effective Go (go.dev/doc/effective_go) · Go Code Review Comments
(go.dev/wiki/CodeReviewComments) · `go vet` / `staticcheck` documentation.

## Cross-References
`pr-self-review`, `code-quality-architecture`.
