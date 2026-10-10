# Review gates and review skills

Two complementary tools keep pull requests to this repository reviewable:

* **`scripts/review_gates.py`** runs deterministic checks over the lines a
  change adds. It runs in CI on every pull request
  (`.github/workflows/review-gates.yml`) and is specified by
  [Spec 11.06](../specs/11-verification-tooling/06-review-gates.md).
* **`.claude/skills/`** holds the review methodology: gate-based PR review,
  per-language deep-review checklists, reuse and design-debt review, the
  destructive-operation protocol, and the source-of-truth rules for this
  connector. Agents load them by name; humans can read them as checklists. See
  [`.claude/skills/README.md`](../.claude/skills/README.md).

The gates catch what a machine can prove. The skills cover what needs
judgement. Neither replaces the other.

## Running the gates

```bash
python3 scripts/review_gates.py --base origin/2.11.0            # all gates, HEAD
python3 scripts/review_gates.py --base origin/2.11.0 --gate destructive
python3 -m unittest discover -s scripts/tests -p 'test_review_gates.py' -v
```

Findings are printed one per line as `path:line: [gate] message`.

| Exit code | Meaning |
|---|---|
| 0 | no findings |
| 1 | at least one finding |
| 2 | usage or git error (for example an unknown base ref) |

The diff is `base...head`, so only the change itself is gated, not whatever
landed on the base branch since the branch point. Standard library only;
PyYAML is used for the YAML syntax check when installed.

## The gates

### destructive

Every added line that irreversibly removes data or objects is a *site*:

* `DROP TABLE|DATABASE|PARTITION|VIEW|DICTIONARY|INDEX|COLUMN|PART|DETACHED`
* `TRUNCATE`, `DELETE FROM`, `ALTER TABLE ... DELETE`, `DELETE WHERE`
* `DETACH PARTITION`, `SYSTEM DROP REPLICA`
* recursive and forced `rm`, in any flag order

Sites built by string concatenation (`"DROP " + "TABLE"`), hidden behind
inline comments, or split across quoted flags are still sites.

Each site needs a comment containing `DESTRUCTIVE:` and at least 20
characters of explanation within 5 lines. The comment says what is destroyed
and how the blast radius is bounded. A marker inside a string literal does not
count. For example:

```java
// DESTRUCTIVE: drops only the scratch table this rebuild created above; the
// live table is swapped in by EXCHANGE TABLES, never dropped.
statement.execute("DROP TABLE IF EXISTS " + scratchTable);
```

In addition, every commit that adds sites must attest exactly the number of
sites it adds, in its own message:

```
Destructive-Op-Check: sites=<N>; result=pass
```

A commit that adds no site needs no trailer. Several trailer lines in one
message are summed, which is what a squash merge of a multi-commit PR
produces. A stale or copied trailer with a different count fails, and the
finding names the commit. The one allowance is a squash merge (subject ending
in `(#N)`): its trailers were each checked in the PR, so their sum may exceed
the net count when the PR reworked a destructive line, but never fall short. The trailer is an
attestation that each site was reviewed, that its semantics were checked
against the code that actually executes it (not a parameter description), and
that a dry-run path exists where a tool can run against live data. The
`destructive-operation-safety` skill has the full protocol.

### merge-stop

No added code may stop or pause ClickHouse merges: `SYSTEM STOP MERGES`,
`SYSTEM STOP TTL MERGES`, a templated `SYSTEM <verb> MERGES` (f-string,
`%s`, `${...}`, concatenation, token list), or a helper named like
`stop_merges` / `pauseMerges`. Stopping merges buys a migration nothing,
because copies are verified with value-level checksums (`db_compare`), not by
freezing the part layout. It also causes harm: the stop aborts every
in-flight merge, and a table whose merges stay stopped accumulates parts until
ClickHouse rejects inserts. `SYSTEM START MERGES` on its own is always
allowed, because releasing merges is the recovery action. There is no
override.

### license

* A dependency added to any `pom.xml` at `compile` or `runtime` scope, or with
  no scope, must not be an ASF Category X (GPL/LGPL/AGPL) artifact or a test
  framework, for example MySQL Connector/J, the MariaDB driver, JUnit or
  Testcontainers.
* It must also be listed in `THIRD_PARTY_NOTICES.md`.
* Dependencies under `<dependencyManagement>` only pin versions and are
  ignored. `test` and `provided` scopes are allowed.
* Python requirement additions (`requirements*.txt`, `pyproject.toml`,
  `setup.cfg`, `setup.py`) must not be known Category X packages such as
  `psycopg2` (use `pg8000`), `mysql-connector-python` or `mysqlclient` (use
  `PyMySQL`).

This complements the `maven-enforcer-plugin` ban in
`sink-connector-lightweight/pom.xml`, which checks transitive dependencies at
build time. See [licensing.md](licensing.md).

### hygiene

* no merge-conflict markers
* no swallowed exceptions:
  * an empty Java `catch` block
  * `except ...: pass`
  * a bare `except:`

  Ignoring `OSError`, `FileNotFoundError`, `ProcessLookupError` or
  `ChildProcessError` during best-effort cleanup is allowed.
* no Python debugger leftovers
* no private keys or well-known token formats
* every changed YAML and JSON file still parses (templated YAML containing
  `{{` or `{%` is skipped)

## What is exempt

The destructive and merge-stop gates skip:

* tests (`test/`, `tests/`, `testflows/`, `test_*.py`, `*Test.java`, `*IT.java`)
* prose (`*.md`, `doc/`, `specs/`, `release-notes/`, `.claude/`)
* grammars and proofs (`*.g4`, `formal_specs/`)
* the gate's own source and tests

These files quote or model destructive statements without executing them.

## Not retroactive

When the commit that introduced `scripts/review_gates.py` is inside the
compared range, the checks start at that commit, so code reviewed and merged
before the checks existed is not re-judged. The release PR for the 2.11.0
line against `develop` is the case this is for: its range holds 139 commits
that predate the checks. Every change from the introducing commit onward is
checked in full.

## Calibration

Run over the 2.11.0 line before the checks existed (`3c0759b1..7f3102db`,
139 commits), they report 6 destructive sites without a `DESTRUCTIVE:`
comment: the PostgreSQL `DROP COLUMN` translation, one log message and one
operator hint naming `DROP TABLE`. Two squash commits also carry
older-format trailers that attest fewer sites than they add. They report no
merge-stop, license or hygiene findings. Those 6 lines are candidates for a warning comment the next
time they are touched.
