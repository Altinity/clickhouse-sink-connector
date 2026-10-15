---
name: code-quality-jvm
description: >
  Use when writing or reviewing Java (or Scala) in this repo's JVM modules
  (sink-connector, sink-connector-lightweight), or when asked for
  "worldclass"/"idiomatic" Java. Covers Effective Java greatest hits
  (records, immutability, Optional, static factories, builders, sealed
  types), a deep concurrency/resource/correctness review checklist, and the
  google-java-format/Checkstyle/SpotBugs/ErrorProne toolchain.
---

# Worldclass Java / Scala (Java 17+, modern Scala)

## Overview
Idiomatic JVM code for this project's Java 17 / Maven modules:
`sink-connector` and `sink-connector-lightweight`. Favor immutability,
exhaustive types, and the standard JVM quality gates. The Scala section
below applies only if a module in scope uses Scala; otherwise skip it.

## When to Use
- Writing or reviewing `.java` (or `.scala`) in `sink-connector` or
  `sink-connector-lightweight`
- Designing a JVM API, domain model, or error model for this connector
- A user asks for "worldclass / idiomatic" Java or Scala

## Java — Core Rules
Why these: Effective Java's through-line is immutability + clear contracts.
Modern Java (records, sealed types, pattern switch) makes them low-friction.

1. **Records for immutable data carriers** — DTOs, events, value objects:
   `record Point(int x, int y) {}`. Auto `equals`/`hashCode`/`toString`/accessors,
   implicitly final. Prefer over Lombok/POJOs.
2. **Minimize mutability (EJ 17).** `final` fields by default; immutable classes
   unless there's a compelling reason — they're thread-safe and safe as map keys.
3. **Sealed hierarchies + records + exhaustive switch** (Java 17/21):
   `sealed interface Shape permits Circle, Rectangle` → compiler-checked switch,
   no `default` needed.
4. **`Optional` over `null`** for possibly-absent return types. Never for fields
   or params; never call `.get()` blindly.
5. **try-with-resources** for any `AutoCloseable` — never manual `try/finally close`.
6. **Static factories over constructors (EJ 1)** — named, cacheable, can return
   subtypes (`List.of`, `Optional.of`).
7. **Builder for many params (EJ 2)** — beyond ~4 params or many optionals.
8. **Favor composition over inheritance (EJ 18).** Design for inheritance or
   prohibit it (`final`).
9. **Program to interfaces (EJ 20, 64)** — refer to objects by interface type
   (`Map` not `HashMap`).
10. **Honor `equals`/`hashCode` together (EJ 10–11)** — records do this for you.
11. **Fail fast & defensive copies (EJ 49–50)** — validate params early, copy
    mutable inputs/outputs at boundaries.
12. **Streams judiciously (EJ 45)** — great for transformations; don't force every
    loop into a stream. Readability first.

```java
// BAD                                        // GOOD
class Point { int x, y;                       record Point(int x, int y) {}
  Point(int x,int y){this.x=x;this.y=y;} }
String s = map.get(k);                        Optional<String> s = Optional.ofNullable(map.get(k));
if (s != null) use(s);                        s.ifPresent(this::use);
Conn c = open(); try { use(c); }              try (Conn c = open()) { use(c); }
finally { c.close(); }
```

**Java tooling:** Maven (`mvn -o test`, offline, per `AGENTS.md`) ·
`google-java-format` · Checkstyle (style) · SpotBugs (bug patterns) ·
Error Prone (compile-time bug detection). Wire as CI gates.

## Scala — Core Rules
Why these: Scala rewards referential transparency and sum types; restraint
(Principle of Least Power) keeps a large language readable.

1. **`val` over `var`; immutable collections.** Mutable state only to model
   genuinely mutable things.
2. **`Option` over `null`; never `.get`.** Treat `Option` as a collection —
   `map`/`flatMap`/`fold`/`getOrElse`.
3. **`Either[Err, T]` / `Try[T]` over thrown exceptions.** One failure mode →
   `Option`; multiple → sealed-trait error ADT / `Either`.
4. **Case classes + sealed traits = ADTs** — immutable, `copy`, structural
   equality, free extractors.
5. **Pattern matching over if-else chains** — expression-oriented, no fall-through.
6. **Exhaustiveness checking** — seal traits and enable `-Xfatal-warnings` so
   non-exhaustive matches are compile errors.
7. **`for`-comprehensions** to sequence `Option`/`Either`/`Try`/`Future` instead
   of nested `flatMap`.
8. **Implicits / `given`s sparingly and explicitly** — powerful but fragile.

```scala
// BAD                                        // GOOD
var total = 0                                 val total = items.map(_.price).sum
for (i <- items) total += i.price
def find(k: K): V = map(k)   // throws        def find(k: K): Option[V] = map.get(k)
if (x != null) use(x)                         opt.fold(default)(use)
```

**Scala tooling:** sbt · scalafmt (`.scalafmt.conf`, `maxColumn <= 100`, `--check`
in CI) for formatting · scalafix (`DisableSyntax.noVars/noNulls/noThrows`,
`OrganizeImports`, `RemoveUnused`, `--check` in CI) for lint/refactor.

## Deep Review Checklist (Java)
Run this over every changed `.java` file during `pr-self-review`'s
per-language pass. Each item is a bug class a reviewer must actively hunt
for, not a style rule.

**Concurrency** (Java Concurrency in Practice; SpotBugs MT_/IS_/JLM_ patterns)
1. **Every shared mutable field has exactly one documented guard** (a lock,
   `volatile` for single-writer flags, an `Atomic*`, or confinement). Mixed
   guarded/unguarded access is a race. `@GuardedBy` or a comment names it.
   This matters especially in the batch executor and offset-tracking paths,
   where a race directly risks Invariant I8 (Durable Offset Quiescence).
2. **No check-then-act across a lock boundary** — `if (!map.containsKey(k))
   map.put(k, v)` on a `ConcurrentHashMap` is a race; use `putIfAbsent` /
   `computeIfAbsent`. `volatile x++` is not atomic.
3. **`InterruptedException` is never swallowed** — either rethrow or restore
   with `Thread.currentThread().interrupt()` before returning. A loop that
   catches it and continues makes shutdown hang.
4. **Executors and threads are shut down** on every exit path (`shutdown()`
   + bounded `awaitTermination`, then `shutdownNow()`); threads are named and
   daemon-ness is deliberate. Exceptions from `submit()`ed tasks are lost
   unless the `Future` is read — prefer `execute()` with an uncaught handler
   or check every future.
5. **Queues and caches are bounded**; an unbounded `LinkedBlockingQueue`
   feeding a slower consumer is a heap-exhaustion bug. Backpressure is
   explicit.
6. **No blocking calls or callbacks to foreign code while holding a lock**;
   lock ordering is consistent (deadlock). `wait()` is always in a loop on
   its condition.
7. **Publication is safe** — objects shared across threads are published
   through `final` fields, a `volatile`, a concurrent collection or a lock;
   no `this` escape from a constructor (starting a thread or registering a
   listener in a constructor).
8. **Iteration over shared collections** — `ConcurrentModificationException`
   when a collection is modified while iterated; snapshot or use a concurrent
   collection.

**Resources and errors**
9. **Every `AutoCloseable` (Connection, Statement, ResultSet, stream,
   channel) is in try-with-resources**, including on error and early-return
   paths. A `PreparedStatement` reused across batches is closed exactly once.
10. **No catch of `Exception`/`Throwable` that hides unrelated failures**;
    never an empty catch; never log-and-continue where the caller needs to
    know; never log-and-rethrow (double logging) — handle once. Wrapped
    exceptions keep the cause (`new X(msg, e)`). A swallowed exception on
    the apply path is a direct violation of Invariant I9 (Loud Failure).
11. **`finally` does not throw or return** (masks the original exception).
12. **Errors carry context** — what failed, on which table/offset/source,
    and what the operator should do.

**Correctness**
13. **`equals` and `hashCode` together**, consistent with `compareTo` where
    sorted collections are used; mutable fields are never part of a hash key.
14. **Integer overflow and unit errors** — `int` byte counts over 2 GiB,
    `long` millis vs nanos vs seconds, `Duration` misuse; `Math.toIntExact`
    / `addExact` where overflow would corrupt state.
15. **Nulls at boundaries** — values from maps, JDBC (`getInt` returns 0 for
    NULL; check `wasNull()`), deserialized records and config. JDBC
    `wasNull()` handling matters directly here: the column-kind contract
    (ordinary columns must bind `NULL`, not silently coerce to a default)
    depends on getting this right.
16. **String/charset/locale** — `getBytes()` without a charset,
    `toLowerCase()` without `Locale.ROOT`, `String.format` with default
    locale for machine-readable output.
17. **Time** — `System.currentTimeMillis()` for measuring durations (use
    `nanoTime()`), `java.util.Date`/`SimpleDateFormat` (not thread-safe),
    implicit default time zone — timestamp handling is especially
    sensitive here around DST transitions and source-vs-commit time.
18. **Static mutable state** — singletons and static caches shared across
    tasks/tests; hidden coupling and test-order dependence.

**Logging and performance**
19. **Parameterized logging** (`log.info("x={}", x)`) — no string
    concatenation or `String.format` in hot-path log statements; no
    per-record INFO/WARN logging; no secrets or full row payloads in logs.
20. **Hot-path allocation** — boxing in tight loops, regex compiled per
    call (`String.split`/`replaceAll` with a regex in a loop), streams in
    per-record paths where a loop is clearer and cheaper.
21. **JDBC** — batches are bounded; statements are prepared, never built by
    concatenating untrusted values (SQL injection); identifiers are quoted.

**Design**
22. **Class size and responsibility** — a class over ~1,000 lines or a
    method over ~80 lines that the diff grows further is a design finding;
    extract cohesive collaborators with their own tests rather than adding
    another branch to the god class.
23. **Visibility** — fields `private final` by default; widening visibility
    "for tests" is a smell (prefer package-private + same-package test).
24. **Tests** — JUnit tests assert behavior, not implementation; no
    `Thread.sleep` for synchronization (use latches/awaitility); every
    concurrency fix has a test that fails without it.

## Anti-Patterns
**Java:** `null` returns instead of `Optional` · mutable public fields · deep
inheritance over composition · returning concrete types (`ArrayList`) from APIs ·
skipping `hashCode` when overriding `equals` · `Optional` for fields/params ·
manual resource close.
**Scala:** `var` and mutable collections passed around · `null`/`.get` on `Option`
· exceptions for control flow · non-sealed traits losing exhaustiveness · implicit
overuse.

## Sources
Java Concurrency in Practice (Goetz et al.) · SEI CERT Oracle Coding Standard
for Java (VNA, THI, TPS, ERR rules) · SpotBugs bug descriptions · Effective
Java 3rd ed. (Bloch) · Google Java Style Guide
(google.github.io/styleguide/javaguide.html) · Oracle records/sealed docs ·
Twitter Effective Scala (twitter.github.io/effectivescala) · scalafmt/scalafix docs.

## Cross-References
`pr-self-review`, `code-quality-architecture`, `sink-connector-mysql-source-of-truth`.
