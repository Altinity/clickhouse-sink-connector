# Spec 13.01: Toolset Architecture, Packaging and the Two Source Trees

## 1. Executive Summary & Purpose

`sink-connector/python/` holds the non-streaming toolset of the sink connector. These tools act outside the CDC stream:

- the initial snapshot: dump the source, translate its DDL, load it into ClickHouse;
- verification: row counts and value checksums, source against replica;
- diff search: locating the differing rows;
- ad hoc repair: re-synchronising tables after a source change that bypassed the binlog.

The code exists twice:

- the **legacy tree** (`db/`, `db_compare/`, `db_dump/`, `db_load/`): plain scripts started as `python db_x/tool.py` with `PYTHONPATH=.`. It carries most of the unit tests and every fix of specs 11.02 and 11.05.
- the **packaged tree** (`ch_sink_tools/`): an installable distribution `ch-sink-tools` 0.3.0. Its ten `ch-*` console scripts are what `pip install` puts on a host. It adds the whole PostgreSQL toolchain, and is otherwise an **older generation** of the MySQL tools.

This spec is the map of the toolset as built on 2.11.0. It covers:

- every tool, its lifecycle role, its entry point and which copy each launcher runs (§3.2, §3.3);
- packaging, dependencies, Python-version and import-time behaviour, all measured offline (§3.4 to §3.6);
- how the ANTLR grammars are generated, committed and imported (§3.7 to §3.9);
- the build, test and deploy scripts, the Dockerfiles and CI (§3.10 to §3.12);
- the **legacy-vs-packaged divergence matrix**, function by function (§3.14);
- where each tool's detailed behaviour is specified (13.02 to 13.08, §3.15).

Headline findings, each a DEFECT in §7:

- An installed `ch-mysql-checksum` reported "No difference" for tables it never compared (D-13.01-2, fixed since 2.11.0: it now runs its packaged side modules with its own interpreter and exits 1 when a side fails; see 13.06 §3.1 and §3.8).
- The packaged loader, which `ch-mysql-load` and `ch-mysql-resync` run, resolved a dump's time zone to a random IANA zone (D-13.01-1, fixed: both copies now map it deterministically, Spec 13.04 §3.9).
- Every `ch-*` MySQL command ships the copy without the spec 11.02/11.05 fixes: `eval()` of `--where`, passwords in logs. The 2.10.0 release notes say those fixes are in `ch_sink_tools` (D-13.01-4).
- The `[mysql]` extra cannot import the MySQL tools (D-13.01-5).
- `requires-python >=3.6` is false: the real floor is 3.10 (D-13.01-6).
- The grammar build script regenerates into the wrong tree (D-13.01-8). The committed PostgreSQL parser raises `NameError` on `DEFAULT -1` (D-13.01-9).
- The cron runner, both checksum Dockerfiles and `test_db.sh` cannot work as written (D-13.01-10, -11, -13).

## 2. Codebase Mapping on 2.11.0

Python root: `sink-connector/python/` (all paths below are repo-relative).

- **Packaging and launchers**
  - `sink-connector/python/pyproject.toml`: distribution metadata, dependencies, extras, the ten console scripts, package data.
  - `sink-connector/python/requirements.txt`: the flat install list used by `install.sh` and the Dockerfiles.
  - `sink-connector/python/install.sh`, `sink-connector/python/build_wheel.sh`, `sink-connector/python/build_grammars.sh`, `sink-connector/python/test_db.sh`.
  - `sink-connector/python/Dockerfile_clickhouse_checksum`, `sink-connector/python/Dockerfile_mysql_checksum`, `sink-connector/python/Dockerfile_db_load`.
  - `sink-connector/python/README.md`, `sink-connector/python/TESTING.md` (0 bytes).
- **Package markers**
  - Root: `sink-connector/python/__init__.py`.
  - Legacy: `sink-connector/python/db/__init__.py`, `sink-connector/python/db_compare/__init__.py`, `sink-connector/python/db_dump/__init__.py`, `sink-connector/python/db_load/__init__.py`, `sink-connector/python/db_load/mysql_parser/__init__.py`.
  - Legacy tests: `sink-connector/python/db_compare/tests/__init__.py`, `sink-connector/python/db_dump/tests/__init__.py`, `sink-connector/python/db_load/tests/__init__.py`, `sink-connector/python/tests/__init__.py`.
  - Packaged: `sink-connector/python/ch_sink_tools/__init__.py`, `sink-connector/python/ch_sink_tools/config/__init__.py`, `sink-connector/python/ch_sink_tools/db/__init__.py`, `sink-connector/python/ch_sink_tools/db_compare/__init__.py`, `sink-connector/python/ch_sink_tools/db_dump/__init__.py`, `sink-connector/python/ch_sink_tools/db_load/__init__.py`, `sink-connector/python/ch_sink_tools/db_load/mysql_parser/__init__.py`, `sink-connector/python/ch_sink_tools/db_load/postgres_parser/__init__.py`.
- **Legacy tree**: `sink-connector/python/db/`, `sink-connector/python/db_compare/`, `sink-connector/python/db_dump/`, `sink-connector/python/db_load/`.
  - The shared file only this tree has is `sink-connector/python/db/checksum_common.py`.
  - `sink-connector/python/db_load/mysql_resync.py` is a 16-line shim that imports the packaged tool.
- **Packaged tree**: `sink-connector/python/ch_sink_tools/` with subpackages `sink-connector/python/ch_sink_tools/config/`, `sink-connector/python/ch_sink_tools/db/`, `sink-connector/python/ch_sink_tools/db_compare/`, `sink-connector/python/ch_sink_tools/db_dump/`, `sink-connector/python/ch_sink_tools/db_load/`.
  - Package data: `sink-connector/python/ch_sink_tools/db_compare/scripts/postgres_checksum_runner.sh`.
- **ANTLR grammar sources**
  - MySQL: `sink-connector/python/antlr_grammars/mysql/MySqlLexer.g4`, `sink-connector/python/antlr_grammars/mysql/MySqlParser.g4`.
  - PostgreSQL: `sink-connector/python/antlr_grammars/postgres/PostgreSQLLexer.g4`, `sink-connector/python/antlr_grammars/postgres/PostgreSQLParser.g4`.
  - PostgreSQL base-class stubs: `sink-connector/python/antlr_grammars/postgres/PostgreSQLLexerBase.py`, `sink-connector/python/antlr_grammars/postgres/PostgreSQLParserBase.py`.
- **MySQL DDL wrapper modules (hand-written, both trees)**
  - `sink-connector/python/db_load/mysql_parser/mysql_parser.py` and `sink-connector/python/ch_sink_tools/db_load/mysql_parser/mysql_parser.py` (`convert_to_clickhouse_table_antlr`, `MyErrorListener`, `main`).
  - `sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py` and `sink-connector/python/ch_sink_tools/db_load/mysql_parser/CreateTableMySQLParserListener.py`.
- **MySQL generated files (byte-identical in both trees)**
  - `sink-connector/python/ch_sink_tools/db_load/mysql_parser/MySqlLexer.py`, `sink-connector/python/ch_sink_tools/db_load/mysql_parser/MySqlParser.py`, `sink-connector/python/ch_sink_tools/db_load/mysql_parser/MySqlParserListener.py`.
  - Plus `*.interp` and `*.tokens`.
- **PostgreSQL DDL parser (packaged only)**
  - Wrapper and listener: `sink-connector/python/ch_sink_tools/db_load/postgres_parser/postgres_parser.py` (`parse_postgres_ddl`, `_main`), `sink-connector/python/ch_sink_tools/db_load/postgres_parser/CreateTablePostgreSQLParserListener.py`.
  - Base classes: `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLLexerBase.py`, `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLParserBase.py`.
  - Generated: `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLLexer.py`, `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLParser.py`.
  - `sink-connector/python/ch_sink_tools/db_load/postgres_parser/README.md`.
- **CI that touches the Python tree**
  - `.github/workflows/spec-governance.yml` (line 88 runs one test file).
  - `.github/workflows/pull-request.yml` calls `.github/workflows/sink-connector-lightweight-checksum-tests.yml`. That workflow runs `sink-connector-lightweight/tests/checksum/test_checksum_replication.py` and `sink-connector-lightweight/tests/checksum/test_sysbench_checksum_replication.py`, which invoke the legacy checksum scripts with deps from `sink-connector-lightweight/tests/checksum/requirements.txt`.
  - `.github/workflows/docker-build.yml` builds no Python image.
- **Offline unit tests**
  - Legacy: `sink-connector/python/db_compare/tests/test_checksum_fidelity.py`, `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py`, `sink-connector/python/db_compare/tests/test_table_locking.py`, `sink-connector/python/db_compare/tests/test_bounded_source_lock.py`, `sink-connector/python/db_compare/tests/test_top_level_where_quoting.py`, `sink-connector/python/db_compare/tests/mysql_table_checksum_test.py`, `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py`, `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py`.
  - Mixed: `sink-connector/python/db_load/tests/test_loader_failure_modes.py`.
  - Packaged: `sink-connector/python/db_load/tests/test_mysql_resync.py`, `sink-connector/python/db_load/tests/test_resync_failure_modes.py`, `sink-connector/python/db_load/tests/test_postgres_type_mapper_unit.py`, `sink-connector/python/tests/test_naming.py`.
- **Documents this spec checks for drift**: `release-notes/2.10.0.md` (line 11), `specs/11-verification-tooling/02-db-compare-checksums.md`, `specs/11-verification-tooling/04-mysql-resync-after-unlogged-change.md`, `specs/11-verification-tooling/05-python-tool-unit-coverage.md`.

## 3. Contract (Behaviour as Built)

### 3.1 The two source trees and how a module is found

| Property | Legacy tree | Packaged tree |
|---|---|---|
| Top-level names | `db`, `db_compare`, `db_dump`, `db_load` (siblings of `ch_sink_tools`) | `ch_sink_tools.*` |
| Internal imports | `from db.mysql import *`, `from db_load.mysql_parser.mysql_parser import ...` (absolute, root-relative) | `from ch_sink_tools.db.mysql import (...)` (absolute, explicit names) |
| How it is started | `python db_compare/x.py ...` with cwd = `sink-connector/python` **and** `PYTHONPATH` containing that root. Without it, every legacy script except the resync shim dies with `ModuleNotFoundError: No module named 'db'` (reproduced, §5 R4). | `ch-*` console scripts after `pip install`, or `python -m ch_sink_tools.<pkg>.<mod>` from the root (all 13 runnable modules print their `--help` with no `PYTHONPATH`, reproduced). |
| Shared helper layer | `db/clickhouse.py`, `db/mysql.py`, `db/checksum_common.py` | `ch_sink_tools/db/clickhouse.py`, `ch_sink_tools/db/mysql.py`, `ch_sink_tools/db/postgres.py`. There is no `checksum_common` equivalent. |
| Tests | 146 of the 232 offline test functions import it | 86 of 232 import it (see §3.14.4) |
| Shipped by | the two checksum Dockerfiles, `test_db.sh`, the lightweight checksum CI, `install.sh` | the wheel and its console scripts, `build_wheel.sh --deploy`, `ch-mysql-resync` (also from the legacy shim) |

Sys.path mechanics as built:

- A script started by path gets its own directory as `sys.path[0]`, not the Python root. Hence the `PYTHONPATH=.` requirement.
- `install.sh` line 4 exports `PYTHONPATH="${PYTHONPATH}":.` but cannot propagate it to the caller (§3.10.1).
- `db_dump/mysql_dumper.py:26-27` and `db_load/clickhouse_loader.py:24-25` append the root to `sys.path`. They do it *after* their `from db.* import` lines (`db_dump/mysql_dumper.py:16`, `db_load/clickhouse_loader.py:4,21`), so the append never helps (D-13.01-14).
- `db_load/mysql_resync.py:11` inserts the root *before* importing `ch_sink_tools.db_load.mysql_resync.main` (line 13). It is the only legacy launcher that works without `PYTHONPATH`.
- The empty `sink-connector/python/__init__.py` makes the root itself a package directory. It changes nothing at runtime. Under pytest's default `prepend` import mode it makes `sink-connector/` the rootdir insertion point. The test files compensate by inserting `sink-connector/python` into `sys.path` themselves.

### 3.2 Tool inventory and lifecycle roles

Lifecycle roles:

- **SNAP-DUMP**: initial snapshot dump.
- **SNAP-LOAD**: snapshot load.
- **DDL**: DDL translation.
- **VERIFY**: value checksum.
- **COUNT**: row count.
- **DIFF**: diff search.
- **RESYNC**: resync or ad hoc patching.

| Tool | Legacy file | Packaged file | Entry point | Role | External programs it runs | Detailed spec |
|---|---|---|---|---|---|---|
| MySQL dumper | `db_dump/mysql_dumper.py` | `ch_sink_tools/db_dump/mysql_dumper.py` | `ch-mysql-dump` | SNAP-DUMP | `mysqlsh` (checked on PATH, exit 1 if missing) | 13.03 |
| ClickHouse loader (MySQL dumps) | `db_load/clickhouse_loader.py` | `ch_sink_tools/db_load/clickhouse_loader.py` | `ch-mysql-load` | SNAP-LOAD + DDL | `clickhouse-client`, `zstd` (asserted), `gunzip`, `sed`, `/usr/bin/which` | 13.04 |
| MySQL DDL translator (ANTLR) | `db_load/mysql_parser/*` | `ch_sink_tools/db_load/mysql_parser/*` | none (library). `main(argv)` debug CLI by path. | DDL | none | 13.04 (DDL semantics); §3.8 here (wrapper and packaging) |
| PostgreSQL dump+load | none | `ch_sink_tools/db_dump/postgres_dumper.py` (+ `naming.py`, `db_load/postgres_type_mapper.py`, `config/column_type_overrides.py`, `config/override_reconciler.py`) | `ch-pg-dump` | SNAP-DUMP + SNAP-LOAD + DDL | `psql`, `pg_dump`, `pg_restore` (via `PG_BIN_DIR`), `clickhouse-client` | 13.05 |
| PostgreSQL DDL parser (ANTLR) | none | `ch_sink_tools/db_load/postgres_parser/*` | none. **No tool imports it** (§3.9). | DDL (unused) | none | 13.05; §3.9 here |
| MySQL side checksum | `db_compare/mysql_table_checksum.py` | `ch_sink_tools/db_compare/mysql_table_checksum.py` | none (spawned by the orchestrator by relative path) | VERIFY | none | 13.06 |
| ClickHouse side checksum (MySQL flavour) | `db_compare/clickhouse_table_checksum.py` | `ch_sink_tools/db_compare/clickhouse_table_checksum.py` | `ch-ch-checksum` | VERIFY | none | 13.06 |
| MySQL count | `db_compare/mysql_table_count.py` | `ch_sink_tools/db_compare/mysql_table_count.py` | none | COUNT | none | 13.06 |
| ClickHouse count | `db_compare/clickhouse_table_count.py` | `ch_sink_tools/db_compare/clickhouse_table_count.py` | `ch-ch-count` | COUNT | none | 13.06 |
| MySQL checksum orchestrator | `db_compare/top_level_table_checksum.py` | `ch_sink_tools/db_compare/top_level_table_checksum.py` | `ch-mysql-checksum` | VERIFY (driver) | argv lists without a shell: `<sys.executable> -m ch_sink_tools.db_compare.<side>_table_checksum` (packaged), `python db_compare/<side>_table_checksum.py` (legacy); side output parsed in Python | 13.06 (11.02) |
| PostgreSQL single-table checksum | none | `ch_sink_tools/db_compare/postgres_table_checksum.py` | `ch-pg-checksum` | VERIFY | none | 13.07 |
| PostgreSQL count | none | `ch_sink_tools/db_compare/postgres_table_count.py` | `ch-pg-count` | COUNT | none | 13.07 |
| PostgreSQL checksum orchestrator | none | `ch_sink_tools/db_compare/top_level_postgres_checksum.py` (+ `_expressions.py`) | `ch-checksum` | VERIFY (driver) + COUNT | none | 13.07 |
| Chunked XOR diff search | none | `ch_sink_tools/db_compare/auto_diff.py` | none. Imported lazily by `top_level_postgres_checksum.py:1867`. It has no `main` (`python -m` prints nothing). | DIFF | none | 13.07 |
| PostgreSQL cron wrapper | none | `ch_sink_tools/db_compare/scripts/postgres_checksum_runner.sh` (package data) | none (run by path) | VERIFY (scheduling) | `bash`, `python`, `tee` | 13.07; §3.10.5 here |
| Resync / ad hoc patch | `db_load/mysql_resync.py` (shim) | `ch_sink_tools/db_load/mysql_resync.py` | `ch-mysql-resync` | RESYNC | `mysqlsh`, `zstd`, `wc`, `clickhouse-client`, the loader (`sys.executable -m ch_sink_tools.db_load.clickhouse_loader` or `--loader-cmd`) | 13.08 (11.04) |
| Shared connection layer | `db/clickhouse.py`, `db/mysql.py` | `ch_sink_tools/db/clickhouse.py`, `ch_sink_tools/db/mysql.py`, `ch_sink_tools/db/postgres.py` | none | all | none | 13.02 |
| Shared checksum canonicalisation | `db/checksum_common.py` | none | none | VERIFY | none | 13.06 (11.02) |
| Dev end-to-end harness | `test_db.sh` | none | none | SNAP-LOAD + VERIFY | `docker exec`, legacy loader and checksums, `diff` | §3.10.4 here |

Two tools have no packaged entry point although their packaged module has a `main()`: `ch_sink_tools.db_compare.mysql_table_checksum` and `ch_sink_tools.db_compare.mysql_table_count`. `ch-mysql-checksum` reaches the MySQL side as `<sys.executable> -m ch_sink_tools.db_compare.mysql_table_checksum` (§3.14.5; before D-13.01-2 was fixed it used a cwd-relative script path).

### 3.3 Console scripts (`pyproject.toml:35-50`)

Each target was resolved in a fresh interpreter (`importlib.import_module` + `getattr(main)`) under three dependency profiles: §5 R2. Each was also imported under Python 3.6.8 and 3.9.7 with the third-party modules stubbed: §5 R3.

| Command | Target | `main` signature | Imports with core deps only | needs at import | Lowest Python that imports it (measured) | Unit tests on this copy |
|---|---|---|---|---|---|---|
| `ch-checksum` | `ch_sink_tools.db_compare.top_level_postgres_checksum:main` | `main()` | yes | clickhouse-driver, psycopg2, pyyaml | 3.6 | none |
| `ch-pg-checksum` | `...db_compare.postgres_table_checksum:main` | `main()` | yes | same | 3.6 | none |
| `ch-pg-count` | `...db_compare.postgres_table_count:main` | `main()` | yes | same | 3.6 | none |
| `ch-ch-checksum` | `...db_compare.clickhouse_table_checksum:main` | `main()` | yes | clickhouse-driver, pyyaml | 3.6 to import. Any `{partition_expression}` `--where` needs ≥ 3.10 (D-13.01-6). | none |
| `ch-ch-count` | `...db_compare.clickhouse_table_count:main` | `main()` | yes | same | 3.6 | none |
| `ch-pg-dump` | `...db_dump.postgres_dumper:main` | `main()` | yes | + `dataclasses` (stdlib ≥ 3.7) | 3.7 (3.6: `ModuleNotFoundError: dataclasses`) | `filter_tables_by_regex` only |
| `ch-mysql-checksum` | `...db_compare.top_level_table_checksum:main` | `main()` | **no** | sqlalchemy, pymysql, **pandas** | 3.6 | none |
| `ch-mysql-dump` | `...db_dump.mysql_dumper:main` | `main()` | **no** | sqlalchemy, pymysql, **pandas** | 3.6 | none |
| `ch-mysql-load` | `...db_load.clickhouse_loader:main` | `main()` | **no** | sqlalchemy, pymysql, **pandas**, antlr4 4.11.1, clickhouse-driver, pyyaml, `zoneinfo` (stdlib ≥ 3.9) | 3.9 (3.6: `ModuleNotFoundError: zoneinfo`) | 1 test (skipped, FM-11.05-1) |
| `ch-mysql-resync` | `...db_load.mysql_resync:main` | `main(argv=None) -> int` | yes (stdlib only) | none (shells out) | 3.7 (3.6: `SyntaxError` at `from __future__ import annotations`, line 33) | 33 |

All ten targets exist and are callable. No console script points at a missing function.

### 3.4 Distribution metadata and wheel contents

`pyproject.toml` as built:

| Key | Value | Line | Note |
|---|---|---|---|
| `build-system.requires` | `setuptools>=64`, `wheel` | 2 | `license` below is a PEP 639 SPDX string, which setuptools accepts from 77 on. With 64 to 76 the `[project]` table fails validation (D-13.01-20, code-read). |
| `name` / `version` | `ch-sink-tools` / `0.3.0` | 6-7 | `ch_sink_tools/__init__.py:5` says `__version__ = "0.2.0"`. The README installs `ch_sink_tools-0.2.0-py3-none-any.whl` (D-13.01-19). |
| `requires-python` | `>=3.6` | 10 | false: measured floors are in §3.3 (D-13.01-6) |
| `license` | `"Apache-2.0"` | 11 | |
| `dependencies` | `clickhouse-driver>=0.2.9`, `psycopg2-binary`, `pyyaml` | 16-20 | psycopg2-binary is forced even on MySQL-only hosts (design choice) |
| extra `mysql` | `pymysql`, `sqlalchemy>=1.4`, `antlr4-python3-runtime==4.11.1` | 23-27 | missing `pandas` (D-13.01-5) |
| extra `dataframe` | `pandas` | 28-30 | |
| extra `all` | `ch-sink-tools[mysql,dataframe]` | 31-33 | the only extra that makes the MySQL commands importable |
| `packages.find.include` | `ch_sink_tools*` | 52-53 | the legacy tree is never packaged |
| `package-data` | `*.interp`, `*.tokens` for both parser packages; `scripts/*.sh` for `ch_sink_tools.db_compare` | 55-58 | `.interp`/`.tokens` are not read by the ANTLR Python runtime (the ATN is serialised inside the generated `.py`), so they are dead weight. `README.md` of `postgres_parser` is not shipped. |

**Wheel verified offline** (§5 R1). Built with `pip wheel --no-deps --no-build-isolation --no-index` under setuptools 84.0.0 / wheel 0.48.0, from a copy of the tree.

- Result: `ch_sink_tools-0.3.0-py3-none-any.whl`, 1 241 730 bytes, 50 files under `ch_sink_tools/` plus `dist-info`.
- It contains every hand-written and generated module of the packaged tree, both parser packages' `.interp`/`.tokens`, and `scripts/postgres_checksum_runner.sh` (mode `0755` preserved).
- It contains no legacy file and no grammar source.
- `entry_points.txt` lists exactly the ten commands of §3.3.
- `METADATA` declares `License-Expression: Apache-2.0`, `Requires-Python: >=3.6` and the extras above. The README becomes the long description, with its `0.2.0` file names.
- No package data the tools need at runtime is missing from the wheel. The package-data problems are scripts whose paths do not work (§3.10.5), not missing files.

### 3.5 Dependencies: pins, importers, gaps

| Distribution | Pin (pyproject / requirements.txt) | Imported at | Import kind | Used by |
|---|---|---|---|---|
| clickhouse-driver | `>=0.2.9` / `>=0.2.9` | `db/clickhouse.py:3`, `ch_sink_tools/db/clickhouse.py:3` | top | every ClickHouse-side tool, PostgreSQL dumper, auto_diff |
| pyyaml | unpinned / unpinned | `db/clickhouse.py:5`, `ch_sink_tools/db/clickhouse.py:5`, both `top_level_table_checksum.py:2`, `top_level_postgres_checksum.py:21`, `column_type_overrides.py:228` (lazy), `postgres_dumper.py:1692` (lazy) | top / lazy | credential files, YAML configs |
| psycopg2-binary | unpinned / unpinned | `ch_sink_tools/db/postgres.py:14-15` | top | PostgreSQL tools |
| pymysql | `[mysql]` / unpinned | `db/mysql.py:7-8`, `ch_sink_tools/db/mysql.py:6-7` | top | SQLAlchemy driver (`mysql+pymysql://`) |
| SQLAlchemy | `[mysql] >=1.4` / `>=1.4` | `db/mysql.py:1`, `ch_sink_tools/db/mysql.py:1` | top | MySQL connections (`create_engine`, `text`). Works with the 2.1.1 in the test venv. |
| antlr4-python3-runtime | `[mysql] ==4.11.1` / `==4.11.1` | both `mysql_parser.py:2-3,7`, both listeners `:1`, `postgres_parser.py:23-24`, PG base stubs `:9` | top | DDL translation. The pin matches the generator version in every generated header (`# Generated from ... by ANTLR 4.11.1`). |
| pandas | `[dataframe]` / unpinned | `db/mysql.py:9`, `ch_sink_tools/db/mysql.py:8` (top); `ch_sink_tools/db/postgres.py:17` (inside `try`) | top / guarded | `mysql_execute_df` and every MySQL metadata helper built on it. **Required by every MySQL tool** (D-13.01-5). |
| numpy | transitive (pandas) | none directly | none | none |
| pytest | **undeclared** | `db_load/tests/test_postgres_type_mapper_unit.py:7`, `tests/test_naming.py:6` | top | tests only. No test requirement file exists. `TESTING.md` is empty. |
| build | **undeclared** | `build_wheel.sh:27` (`python -m build`) | none | wheel build (the README tells the user to `pip install build`) |

Stdlib modules with a version floor:

| Module | Floor | Where |
|---|---|---|
| `zoneinfo` | 3.9 | `db/checksum_common.py:10`, `db_load/clickhouse_loader.py:20`, `ch_sink_tools/db_load/clickhouse_loader.py:19` |
| `dataclasses` | 3.7 | `ch_sink_tools/config/column_type_overrides.py:24` |
| `from __future__ import annotations` | 3.7 | `ch_sink_tools/db_load/mysql_resync.py:33`, `ch_sink_tools/db_load/postgres_parser/CreateTablePostgreSQLParserListener.py:21` |
| `subprocess.run(capture_output=, text=)` | 3.7 | `postgres_dumper.py:781`, `mysql_resync.py:231,248,271,334` |
| unquoted `list[str]` annotation evaluated at def time | 3.9 | `ch_sink_tools/db_load/postgres_parser/postgres_parser.py:127` |
| calling a module-level `@staticmethod` object | 3.10 | `ch_sink_tools/db_compare/mysql_table_count.py:44`, `.../mysql_table_checksum.py:168`, `.../clickhouse_table_checksum.py:218` (packaged copies only; the legacy `fstr` has no decorator) |

`top_level_postgres_checksum.py` keeps 3.6-era compatibility shims: a "plain class for Python 3.6 compatibility" at line 73, and a `urllib2` fallback import at line 36. That is consistent with the metadata and with no other module.

Unused or missing: no declared distribution is unused. Missing: `pandas` from `[mysql]`, and the test and build tools.

### 3.6 Import-time behaviour (measured)

- **All modules, full venv** (Python 3.12.11; antlr4 4.11.1, PyMySQL 1.2.3, psycopg2-binary 2.9.13, SQLAlchemy 2.1.1, clickhouse-driver 0.2.11, PyYAML 6.0.3, pandas 2.3.3). All 53 non-test, non-generated modules of both trees import cleanly, each in a fresh subprocess with the root on `sys.path` (§5 R5). That is 35 packaged and 18 legacy, counting package `__init__`s.
- **Dependency profiles** (§5 R2): core only, core+`[mysql]`, core+`[dataframe]`. The six PostgreSQL/ClickHouse commands and `ch-mysql-resync` import under every profile. `ch-mysql-checksum`, `ch-mysql-dump` and `ch-mysql-load` fail under every profile except `[all]`: `No module named 'sqlalchemy'` without `[mysql]`, `No module named 'pandas'` with only `[mysql]`.
- **Old interpreters** (§5 R3, third-party modules stubbed). Python 3.6.8:
  - `ch-pg-dump` fails with `ModuleNotFoundError: No module named 'dataclasses'`.
  - `ch-mysql-load` and the legacy checksum and loader scripts fail with `No module named 'zoneinfo'`.
  - `ch-mysql-resync` fails with `SyntaxError: future feature annotations is not defined (mysql_resync.py, line 33)`. The same `SyntaxError` hits `CreateTablePostgreSQLParserListener.py:21` when the tree is compiled.
  - The spec-governance test file itself fails to import under 3.6.
- Python 3.9.7: everything imports. The packaged `fstr` then raises `TypeError: 'staticmethod' object is not callable` when called (§5 R6).
- **Side effects at import**: the checksum and count runners replace the process-wide log record factory (`logging.setLogRecordFactory(record_factory)`, which adds `record.user = "me"`) when imported. Importing them as a library changes logging for the host process. Nothing connects at import time.

### 3.7 ANTLR grammars: sources, generation, committed output, import path

Sources:

| Grammar | Source | Lines | Generated, committed at | Import path used at runtime |
|---|---|---|---|---|
| MySQL lexer+parser | `antlr_grammars/mysql/MySqlLexer.g4`, `MySqlParser.g4` | 1 357 + 2 826 | `db_load/mysql_parser/` **and** `ch_sink_tools/db_load/mysql_parser/` (all 7 files byte-identical, §5 R7): `MySqlLexer.py` 7 239, `MySqlParser.py` 56 033, `MySqlParserListener.py` 5 429 lines, plus `.interp`, `.tokens` | legacy: `db_load.mysql_parser.MySqlLexer` (`mysql_parser.py:4-6`). Packaged: `ch_sink_tools.db_load.mysql_parser.MySqlLexer`. The generated listener imports its parser relatively (`from .MySqlParser import MySqlParser`) when `"." in __name__`. |
| PostgreSQL lexer+parser | `antlr_grammars/postgres/PostgreSQLLexer.g4`, `PostgreSQLParser.g4` (`superClass = PostgreSQLLexerBase / PostgreSQLParserBase`) | 1 475 + 5 469 | `ch_sink_tools/db_load/postgres_parser/` only: `PostgreSQLLexer.py` 3 377, `PostgreSQLParser.py` 72 767, `PostgreSQLParserListener.py` 6 527 lines, plus `.interp`, `.tokens`, and copies of the two base stubs (byte-identical to `antlr_grammars/postgres/`) | `ch_sink_tools.db_load.postgres_parser.*`. The generated files import the bases relatively (`from .PostgreSQLLexerBase import PostgreSQLLexerBase`). |

Generation (`build_grammars.sh`, no shebang, no `set -e`):

1. Lines 1-3 download a JDK 19.0.2 tarball and `antlr-4.11.1-complete.jar` with `wget` on every run. Nothing is cached and no checksum is verified. Then the JDK is untarred into the cwd.
2. Line 4: `cp antlr_grammars/mysql/*.g4 .` litters the Python root with two `.g4` files.
3. Line 5 generates the MySQL grammar `-Dlanguage=Python3 -no-visitor ... -o db_load/mysql_parser`. That is the **legacy** directory only.
4. Lines 15-29 copy the PostgreSQL base stubs into `db_load/postgres_parser/` and generate `-listener -no-visitor` into `db_load/postgres_parser`. That directory **does not exist in the 2.11.0 tree**, and no module imports from it.

Consequences, as built:

- Regenerating never updates `ch_sink_tools/`, which is what the wheel ships and what the PostgreSQL wrapper imports. The two MySQL copies are identical today only because someone copied the output by hand (D-13.01-8).
- The PostgreSQL grammar comes from the grammars-v4 project. Its embedded actions and predicates are written `{this.CheckLaMinus()}?`, `{this.PushTag();}`, `{this.OnlyAcceptableOps()}?`. That project rewrites `this.` to `self.` for the Python target with a transform step this script does not run. The committed `PostgreSQLLexer.py:3294-3374` and `PostgreSQLParser.py:36010,58290,72714` therefore contain `this.X()`.
- The base stubs also lack 11 of the 13 methods called: `CheckLaMinus`, `CheckLaStar`, `HandleLessLessGreaterGreater`, `CharIsLetter`, `CheckIfUtf32Letter`, `PushTag`, `PopTag`, `IsTag`, `IsSemiColon`, `HandleNumericFail`, `UnterminatedBlockCommentDebugAssert` on the lexer, and `ParseRoutineBody` on the parser. The stubs define lowercase look-alikes (`pushTag`, `isTag`, `isSemicolon`) that the generated code never calls. Any input that reaches one of these actions raises `NameError: name 'this' is not defined` (§3.9, D-13.01-9).
- Currency of the committed output against the committed grammars, checked offline without the ANTLR jar (§5 R7):
  - MySQL: the 350 parser rule names of `MySqlParser.g4` equal the 350 `RULE_*` constants of `MySqlParser.py`. The 1 155 non-fragment lexer rules equal the 1 155 names in `MySqlLexer.tokens`.
  - PostgreSQL: 719 vs 720 rules. The extra is `c_expr`, a rule whose alternatives are all labelled, so my regex missed it. The lexer has 8 rules without a token type, all `-> type(...)` or `-> more` rules, as ANTLR does.
  - No evidence of grammar/output drift. Full regeneration was not run (no ANTLR jar offline).

### 3.8 MySQL DDL wrapper modules (`mysql_parser/*`, both trees)

The two copies of `mysql_parser.py` (55 lines) and `CreateTableMySQLParserListener.py` (282 lines) are identical except for import paths (§3.14). They do **not** behave identically: each listener calls `is_binary_datatype` from its own tree's `db/mysql.py`, and those differ (D-13.01-7).

`mysql_parser.py`:

| Function | Contract as built |
|---|---|
| `MyErrorListener.syntaxError(...)` | Raises a bare `Exception(f"Syntax error at line {line} column {column}")`. The ANTLR message `msg` is dropped. |
| `convert_to_clickhouse_table_antlr(source, rmt_delete_support, partition_options='', datetime_timezone=None)` | Builds `InputStream → MySqlLexer → CommonTokenStream → MySqlParser`, adds `MyErrorListener` (line 26), parses `sqlStatements()` and walks with `CreateTableMySQLParserListener`. Logs the whole parse tree at DEBUG (`Trees.toStringTree`, line 31) and returns `(clickhouse_sql, columns_map)`. The default `ConsoleErrorListener` is not removed, so each syntax error is also printed to stderr (`line 1:34 no viable alternative ...`) before the exception (D-13.01-23). A statement the listener does not handle returns `("", {})`: `buffer` is `""` and `columns_map` is still the `{}` from `__init__`. |
| `main(argv)` | Debug CLI: sets root logging to DEBUG on stdout, reads `argv[1]` as a file and translates it with `rmt_delete_support=True`. Not a console script. `--help` is taken as a file name (`FileNotFoundError: '--help'`, reproduced). Run by path it needs `PYTHONPATH` (`No module named 'db_load'`, reproduced). |

`CreateTableMySQLParserListener` hooks. Only the packaging and wrapper-level contract is here. Type mapping and engine semantics are 13.04's:

| Hook | Generated rule exists? | Behaviour |
|---|---|---|
| `enterColumnCreateTable` / `exitColumnCreateTable` | yes | Emits `CREATE TABLE <name> (<cols>, `_version` UInt64 DEFAULT 0, <is_deleted|_is_deleted> UInt8 DEFAULT 0 \| `_sign` Int8 DEFAULT 1) engine=ReplacingMergeTree(_version[,is_deleted]) <partition_by> order by <pk or tuple()>` |
| `exitColumnDeclaration` / `translateColumnDefinition` / `convertDataType` | yes | Per-column text and the `columns_map` entries `{column_name, datatype, nullable, mysql_datatype, generated, has_is_deleted_column}`. The loader uses these to build the `input()` structure. |
| `exitPrimaryKeyTableConstraint` | yes | `order by` = the original text of the key column list |
| `exitPartitionClause` | yes, but it is the **window-function** `PARTITION BY` rule (`MySqlParser.g4:2541`), not table partitioning (`partitionDefinitions`, line 496) | Calls `ctx.partitionTypeDef()`, which does not exist: `AttributeError` on any statement with `OVER (PARTITION BY ...)`. `partition_keys` is never set from a `PARTITION BY RANGE COLUMNS` table (reproduced). Partitioning reaches the DDL only through the loader's `partition_options` argument. |
| `exitAlterList` | **no** (no `alterList` rule in this grammar) | Never fires. It would call the undefined `self.translateFieldDefinition` (line 202). |
| `exitAlterTable` | yes | Appends ` rename table <t> to <new>` for `ALTER TABLE ... RENAME`. All other ALTER text is commented out (lines 260-270). |
| `exitRenameTable` / `exitTruncateTable` / `exitDropTable` | yes | `buffer` = the original statement text ("same syntax as CH") |

Wrapper-level defects in the listener, all present in **both** copies and reproduced in §5 R8:

- Line 37 (fixed): `re.sub("CHARSET.*", '', dataTypeText, flags=re.IGNORECASE)`. On 2.11.0 the flag was passed as `count`, so `varchar(10) charset latin1` reached ClickHouse verbatim (D-13.01-15).
- Lines 77-89 (fixed): the `NULL` modifier test is case-insensitive (`text.upper() == "NULL"`). On 2.11.0 a lower-case `null` fell into the `NOT` branch with `notSymbol` pre-set to `True`, so `columns_map` said `nullable: False` and the loader declared the column `String` instead of `Nullable(String)` in `input()` (D-13.01-16).
- Line 98: the collation-introducer strip `re.sub(r"\b_.*?'", "'", text)` also deletes any identifier that starts with `_`, up to the next quote. `GENERATED ALWAYS AS (concat(_code, 'x'))` becomes `MATERIALIZED concat('x')`, silently (D-13.01-3).

### 3.9 PostgreSQL DDL parser package (packaged only)

- `parse_postgres_ddl(source: str) -> dict` (`postgres_parser.py:52-120`):
  - Removes the default error listeners on both lexer and parser and installs `_PostgreSQLErrorListener`. That listener raises `SyntaxError("PostgreSQL DDL parse error at line L col C: msg")`.
  - Parses `root()` and walks `CreateTablePostgreSQLParserListener`.
  - Returns `listener.get_result()`. Keys are documented at lines 67-92: `action` ∈ {create_table, alter_table, drop_table, rename_table, unknown}, `table_name`, `raw_sql`, `columns`, `primary_keys`, `clickhouse_sql`, `alter_cmds`, `new_name`.
- `_main(argv)` (line 127) is a debug CLI that prints the result as JSON. It is only reachable by `python -m` or by path. Its annotation `list[str]` makes the module unimportable before Python 3.9.
- `__init__.py` re-exports `parse_postgres_ddl`.
- Its header comments (`postgres_parser.py:1,11`, `__init__.py:1`, `CreateTablePostgreSQLParserListener.py:1`) and its `README.md` give the path as `db_load/postgres_parser/...` and the import as `from db_load.postgres_parser.postgres_parser import parse_postgres_ddl`. Neither exists. The README also says "the Java sink-connector uses" the result. No Java or Python code in the repository imports the package; only `build_grammars.sh` and `pyproject.toml` name it (D-13.01-18).
- Measured behaviour (§5 R9):
  - Works: `CREATE TABLE t (id bigint PRIMARY KEY, amount numeric(10,2))` returns `create_table`, `[('id','Int64'),('amount','Decimal(10, 2)')]`. `ALTER TABLE t ADD COLUMN note text` and `DROP TABLE t` parse.
  - Raises `NameError: name 'this' is not defined`: `... n int DEFAULT -1`, `... DEFAULT 4/2`, `... DEFAULT $$x$$` (D-13.01-9).

### 3.10 Build, install, test and deploy scripts

#### 3.10.1 `install.sh` (4 lines, executable, no shebang)

| Step | Command | Effect as built |
|---|---|---|
| 1 | `python3 -m venv .venv` | creates `.venv` in the cwd |
| 2 | `source .venv/bin/activate` | `source` is a bash builtin: under `sh` or dash this line fails. Under bash it activates only the child shell running the script. |
| 3 | `pip install -r requirements.txt` | installs into `.venv` only if step 2 worked; otherwise into whatever `pip` is on `PATH` |
| 4 | `export PYTHONPATH="${PYTHONPATH}":.` | lost when the script exits. The caller must `source install.sh` for steps 2 and 4 to matter. Nothing says so (D-13.01-22). |

#### 3.10.2 `build_wheel.sh` (bash, `set -euo pipefail`)

| Input | Type | Default | Effect |
|---|---|---|---|
| `$1 == --deploy` | flag | off | after the build, `scp` the wheel to `${TARGET_USER}@${TARGET_HOST}:/tmp/` and `ssh` `${TARGET_VENV}/bin/pip install --force-reinstall /tmp/<wheel>`. `--force-reinstall` without `--no-deps` also reinstalls every dependency from the index. No confirmation. |
| `CH_DEPLOY_HOST` | env | `ch-server` | target host |
| `CH_DEPLOY_USER` | env | `clickhouse` | ssh user |
| `CH_DEPLOY_VENV` | env | `/opt/python-dump/.venv` | remote venv |

Steps:

- `cd` to the script's directory.
- `rm -rf dist/ build/ *.egg-info ch_sink_tools.egg-info`.
- `python -m build --wheel --outdir dist/`. This needs the undeclared `build` package and network access for the isolated build environment.
- Pick the newest `dist/ch_sink_tools-*.whl`. Print its size.
- "Verify wheel contents" (line 38): `unzip -l <wheel> | grep -E '(ch_sink_tools/[^/]+\.py|ch_sink_tools/[^/]+/$)' | head -20`. Wheels contain no directory entries, so this prints only `ch_sink_tools/__init__.py` (reproduced on the built wheel, §5 R1). It verifies nothing and cannot fail the build (D-13.01-21).

Exit code: non-zero on any failing step (`set -e`).

#### 3.10.3 `build_grammars.sh`

See §3.7. There are no arguments. It needs network access (`wget`) and writes the JDK, the jar, two `.g4` copies and the generated files under the cwd. It must be run from `sink-connector/python`; any other cwd fails at line 4. Its exit code is that of the last command (the PostgreSQL ANTLR run).

#### 3.10.4 `test_db.sh` (9 lines, no shebang, no `set -e`)

| Input | Effect |
|---|---|
| `$1` → `DATABASE` | unquoted everywhere: SQL `drop database if exists $DATABASE`, file names `$DATABASE.ch`/`$DATABASE.mysql`, `--dump_dir $HOME/dbdumps/$DATABASE` |

Commands:

1. `docker exec -it clickhouse clickhouse-client -uroot --password root --query "drop database if exists $DATABASE"`. Destructive, with fixed credentials. `-it` fails without a TTY.
2. `python db_load/clickhouse_loader.py --clickhouse_host localhost --clickhouse_database $DATABASE --dump_dir $HOME/dbdumps/$DATABASE --clickhouse_user root --clickhouse_password root --threads 4 --mysql_source_database $DATABASE --mysqlshell`.
3. `python db_compare/clickhouse_table_checksum.py ... --tables_regex . --threads 4 | grep "Checksum for table" | awk '{print $11" "$13" "$15}' | sort > $DATABASE.ch`. With the format `'%(asctime)s - %(levelname)s - %(threadName)s - %(message)s'`, fields 11/13/15 are table, md5 and count.
4. The same for `db_compare/mysql_table_checksum.py` into `$DATABASE.mysql`.
5. `diff $DATABASE.ch $DATABASE.mysql | grep "<\|>"`.

The script sets no `PYTHONPATH`. Steps 2-4 therefore die with `ModuleNotFoundError: No module named 'db'` unless the caller exported it (reproduced, §5 R4). The pipes mask the failure and both files come out empty. `diff` of two empty files prints nothing, which is exactly what success looks like (D-13.01-13).

#### 3.10.5 `ch_sink_tools/db_compare/scripts/postgres_checksum_runner.sh` (package data)

| Input | Type | Default | Effect |
|---|---|---|---|
| `$1` (when it does not start with `-`) | path | `${SCRIPT_DIR}/config_postgres_system.yml` | config passed as `--config`. A missing file exits 2. The default file is not in the tree or the wheel. |
| remaining args | passthrough | none | appended to the Python command |
| `PG_CH_CHECKSUM_LOG_DIR` | env | `/var/log/pg_ch_checksum` | log dir. Falls back to `/tmp/pg_ch_checksum` if it cannot be created. |
| `PYTHON_VENV` | env | `${PYTHON_DIR}/.venv` | sourced if `bin/activate` exists |

Exit codes as documented: 0 when every table passes, 1 on any FAIL/MISSING/ERROR, 2 on a startup error. The runner returns `PIPESTATUS[0]` of `python ... | tee`.

Paths:

- `SCRIPT_DIR` is `.../ch_sink_tools/db_compare/scripts`, so `PYTHON_DIR=$(dirname SCRIPT_DIR)` is `.../ch_sink_tools/db_compare`.
- The runner exports `PYTHONPATH=${PYTHON_DIR}`, `cd`s there and runs `python db_compare/top_level_postgres_checksum.py`, that is `.../ch_sink_tools/db_compare/db_compare/top_level_postgres_checksum.py`. That file does not exist in a checkout or in an installed wheel. The legacy tree has no `top_level_postgres_checksum.py` either.
- Every run therefore prints `python: can't open file ...` and `CHECKSUM FAILED (exit=2)` (reproduced from a copy of the tree, §5 R10).
- The cron examples in its header (lines 13, 17) use an `/opt/python-dump/db_compare/` layout that neither tree has (D-13.01-10).

### 3.11 Dockerfiles

| File | Base | Copies | Installs | Entrypoint | Behaviour as built |
|---|---|---|---|---|---|
| `Dockerfile_clickhouse_checksum` | `python:3.10` | **legacy** `db/`, `db_compare/` (lines 11-12) | `pip install -r requirements.txt` (all 7 deps, unpinned except antlr) | exec form `["python3.10", "/app/db_compare/clickhouse_table_checksum.py", "--clickhouse_host", "$CLICKHOUSE_HOST", ..., "--clickhouse_password", "$CLICKHOUSE_PASSWORD", "--tables_regex", "."]` (line 20) | Exec-form `ENTRYPOINT` performs no variable substitution: the tool receives the literal strings `$CLICKHOUSE_HOST`, `$CLICKHOUSE_DATABASE`, `$CLICKHOUSE_USER`, `$CLICKHOUSE_PASSWORD` and fails to resolve host `$CLICKHOUSE_HOST` (D-13.01-11, code-read). The `ENV` defaults (`clickhouse_host_value` ...) are never used. |
| `Dockerfile_mysql_checksum` | `python:3.10` | legacy `db/`, `db_compare/` | same | same pattern with `$MYSQL_HOST` ... (line 20) | same defect. A password given with `-e` would also sit on the process command line. |
| `Dockerfile_db_load` | `mysql:latest` | **nothing** from this tree | `apt-get` of `clickhouse-client` from `repo.clickhouse.tech` (with `apt-key adv`) and of `mysql-shell` | `["mysqlsh"]` | Ships no Python tool despite its name. `apt-get` presumes a Debian-based `mysql:latest`, and the repository host and `apt-key` are the retired ClickHouse apt setup. Whether the image builds today could not be checked offline (D-13.01-12, code-read). |

Both checksum images:

- `RUN cd /app/db && export PYTHONPATH=.` and `RUN cd ..` (lines 17-18) are no-ops: each `RUN` is a new shell.
- `ENV PYTHONPATH "${PYTHONPATH}:/app/db"` expands to `:/app/db`. The `/app/db` entry is useless, since `db` would have to be at `/app/db/db`. The imports work **only by accident**: the empty leading component puts the cwd (`WORKDIR /app`) on `sys.path`. Reproduced: with `PYTHONPATH=":<root>/db"` and cwd = root the legacy checksum starts. With `PYTHONPATH="<root>/db"` it fails `No module named 'db'`.
- The header comment of `Dockerfile_clickhouse_checksum` (line 2) documents `-e MYSQL_HOST=...` variables, a copy-paste from the MySQL file.

No workflow builds any of the three images (§3.12).

### 3.12 CI coverage of the Python tree

| Workflow | Trigger | What it runs | Copy exercised |
|---|---|---|---|
| `.github/workflows/spec-governance.yml:84-88` | every PR, pushes to `2.11.0` | `python3 -m unittest discover -s scripts/tests`, then `python3 -m unittest sink-connector/python/db_load/tests/test_mysql_resync.py` on Python 3.12 with **no** dependencies installed | packaged `mysql_resync` (29 tests, stdlib only) |
| `.github/workflows/pull-request.yml:20-22` → `sink-connector-lightweight-checksum-tests.yml` | every PR | live MySQL+ClickHouse compose stack. `pytest` of `sink-connector-lightweight/tests/checksum/test_checksum_replication.py` and `test_sysbench_checksum_replication.py`. Those run `python3 db_compare/mysql_table_checksum.py` / `db_compare/clickhouse_table_checksum.py` with cwd and `PYTHONPATH` = `sink-connector/python`, deps from `sink-connector-lightweight/tests/checksum/requirements.txt` (no antlr4, no psycopg2). | **legacy** side scripts (not the orchestrator) |
| `docker-build.yml`, `release.yml`, `publish.yml` | various | Java images only | none |

Not run anywhere:

- the 232-test offline suite. Its baseline is 227 passed, 5 skipped in 6.13 s on the dev host. FM-11.05-3 records this.
- any wheel build, any import of the packaged MySQL tools, `build_grammars.sh`, the three Dockerfiles, any linter.

### 3.13 Documentation drift (README.md, TESTING.md, release notes)

| Document | Claim | As built |
|---|---|---|
| `README.md:9,12,15,54` | install/build `ch_sink_tools-0.2.0-py3-none-any.whl` | the build produces `ch_sink_tools-0.3.0-py3-none-any.whl` |
| `README.md:28-30` | `ch-mysql-checksum`/`-dump`/`-load` "requires `[mysql]` extra" | they need `[all]` (`pandas`) |
| `README.md:31` | `ch-mysql-resync` "needs ... the `[mysql]` extra for the loader" | the loader needs `[all]` |
| `README.md:25-26` / `pyproject.toml:36` comment | `ch-ch-checksum`/`ch-ch-count` listed among "PostgreSQL checksum tools" | they are the MySQL-flavoured ClickHouse side. `ch-checksum` builds its own ClickHouse expressions (`_expressions.py`). |
| `README.md:43` | `ch-pg-count --config config.yml` | `postgres_table_count.py: error: the following arguments are required: --pg_host, --pg_database` (reproduced) |
| `README.md:46` | `ch-pg-dump --pg-host pgserver --ch-host chserver --pg-database mydb` | `postgres_dumper.py: error: unrecognized arguments: --pg-host pgserver --ch-host chserver --pg-database mydb` (flags are `--pg_host`, `--ch_host`, `--pg_database`; reproduced) |
| `README.md` (whole) | describes only the package | the legacy tree, `PYTHONPATH`, the Dockerfiles and `test_db.sh` are undocumented. The repository root README links "Initial data dump and load (MySQL)" to this file. |
| `TESTING.md` | exists | 0 bytes |
| `release-notes/2.10.0.md:11` | "`ch_sink_tools` Python toolkit ... Passwords are masked in logs, `eval()` is removed from checksum formatting, and table locks are held only for the duration of each table checksum" | true of the **legacy** tree only. In `ch_sink_tools`: `eval()` remains in all three `fstr`; the dumper and loader log commands with passwords; `resolve_credentials_from_config` logs the ClickHouse password at DEBUG; the orchestrator takes `FLUSH TABLE ... WITH READ LOCK` per table in the submission loop (§3.14.2). |
| `postgres_parser/README.md`, module headers | path `db_load/postgres_parser/...`, Java consumer | §3.9 |
| `__init__.py` docstrings | `ch_sink_tools/db_load/mysql_parser/__init__.py:1` and `db_load/mysql_parser/__init__.py:1` say `"""db_load"""`; legacy `__version__ = "0.1"` in four packages | cosmetic |

### 3.14 Legacy-vs-packaged divergence matrix

Method: an AST-based function diff. For every file pair it parses both copies and normalises the pure import rewrite (drops the `ch_sink_tools.` prefix). It then compares each top-level function, class and method body and reports legacy-only, packaged-only, identical or CHANGED with −/+ line counts, plus differing module-level statements (§5 R11). Each CHANGED entry below was then read and summarised by behaviour.

#### 3.14.1 Pairs that do not differ in behaviour

| File | Legacy / packaged lines | Result |
|---|---|---|
| `db_compare/clickhouse_table_count.py` | 178 / 182 | every function identical; only `from db.clickhouse import *` vs explicit names |
| `db_load/mysql_parser/mysql_parser.py` | 55 / 55 | identical except imports |
| `db_load/mysql_parser/CreateTableMySQLParserListener.py` | 282 / 282 | text identical except imports. Behaviour differs through `is_binary_datatype` (below). |
| `db_load/mysql_parser/MySqlLexer.py`, `MySqlParser.py`, `MySqlParserListener.py`, `*.interp`, `*.tokens` | identical | byte-identical |
| `db/__init__.py`, `db_load/mysql_parser/__init__.py` | 0/0, 3/3 | identical |
| `db_compare/__init__.py`, `db_dump/__init__.py`, `db_load/__init__.py` | 3 / 1 | docstring; the legacy copies add `__version__ = "0.1"` |

#### 3.14.2 Pairs that differ in behaviour (function by function)

**`db/clickhouse.py`** (72 / 69); identical: `get_table_partition_key`, `execute_sql`.

| Function | Legacy | Packaged |
|---|---|---|
| `clickhouse_connection` | `password=password` | `password=password or ""` |
| `clickhouse_execute_conn` | closes the cursor in `finally` | no `finally`: the cursor leaks on an exception |
| `resolve_credentials_from_config` | `yaml.safe_load`; DEBUG `clickhouse_password ****` | `yaml.load(f, Loader=yaml.FullLoader)`; DEBUG logs the **password in clear** |

**`db/mysql.py`** (229 / 212); identical: `get_tables_from_regex_sql`, `get_tables_from_regex`, `get_partitions_from_regex`, `mysql_execute_df`, `execute_mysql`, `resolve_credentials_from_config`, `estimate_table_count`, `get_min_max_pk_value`, `mysql_columns`, `mysql_pk_columns`, `divide_table_into_even_chunks`.

| Function | Legacy | Packaged |
|---|---|---|
| `binary_datatypes` (module constant) | 16 keywords incl. `tinyblob`, `mediumblob`, `longblob`, `geometrycollection` | 12 keywords |
| `is_binary_datatype` | lower-cases, strips from the first `(`, exact keyword match. "It must never substring-match." | substring test for `blob`/`binary`/`varbinary`/`bit` anywhere in the (case-sensitive) text, else an exact lower-case match. `enum('orbit','x')` and `set('blobby','y')` are binary (D-13.01-7). |
| `get_mysql_connection` | `quote_plus` on user and password in the SQLAlchemy URL | raw: `@ : / # ?` in a password break the URL |
| `get_table_partition_key` | `.mappings().fetchall()` (dict rows) | `.fetchall()` (tuples) |
| `mysql_columns_by_data_type` | present (used by the legacy orchestrator for timestamp, binary and JSON columns) | absent |

**`db_compare/mysql_table_count.py`** (234 / 232); identical: `compute_count`, `select_table_statements`, `get_tables_from_regexp`, `calculate_table_count`, `record_factory`, `main`.

| Function | Legacy | Packaged |
|---|---|---|
| `fstr` | literal `template.replace('{partition_expression}', ...)` | `@staticmethod` + `eval(f"f'{template}'")`. Arbitrary code in `--where` runs. `TypeError` before Python 3.10. |
| `calculate_sql_count` | logs `Count failed for <t>` and re-raises | `future.result()` raises first; the later `future.exception()` check is dead |

**`db_compare/mysql_table_checksum.py`** (482 / 439); identical: `get_tables_from_regexp`, `calculate_checksum_single_thread`, `record_factory`.

| Function | Legacy | Packaged |
|---|---|---|
| `mysql_datetime_rendering`, `mysql_column_expression`, `build_mysql_row_expression`, `build_argument_parser` | present: spec 11.02 per-type rendering, shared clamp from `db/checksum_common.py`, binary encodings, float/JSON exclusion | absent; `get_table_checksum_query` (+85 lines) inlines an older expression builder |
| `select_table_statements` | session `set time_zone = '+00:00'` (TIMESTAMP as UTC instants); sums a `clamped` column | no `time_zone` statement: TIMESTAMP rendered in the session zone. No clamp count. |
| `compute_checksum` | re-raises; the caller owns the connection | closes the connection in `finally` |
| `calculate_checksum` | sums 6 columns and uses `checksum_from_aggregate`; WARNING on clamped values; error log per table | sums 5 and computes `md5("cnt#a#b#c#d#")` inline; no clamp warning |
| `fstr` | literal replace | `eval` (as above) |
| `main` | `build_argument_parser()` incl. `--source_timezone`, `--binary_encoding {hex,base64,raw}`, `--include_*` | its own parser without those options |

**`db_compare/clickhouse_table_checksum.py`** (514 / 423); identical: `get_connection`, `get_primary_key_columns`, `get_tables_from_regex`, `record_factory`.

| Function | Legacy | Packaged |
|---|---|---|
| `is_datetime_type`, `clickhouse_datetime_rendering`, `clickhouse_column_expression`, `build_clickhouse_row_expression`, `get_engine_full`, `sign_column_from_engine`, `partition_key_within_sorting_key`, `build_argument_parser` | present | absent |
| `get_table_checksum_query` | uses the builders above (source zone, timestamp/JSON/hex columns, clamp) | +78 lines of older inline logic; uses a `format_decimal` UDF |
| `select_table_statements` | sign filter only when a sign column was derived from the engine; `do_not_merge_across_partitions_select_final=1` only when the partition key is within the sorting key | sign filter on `args.sign_column` (default `_sign`); the setting is always on |
| `calculate_checksum` | no count pre-check; derives the sign column from `engine_full` | issues `select count(*)` first. Its `rowcount == 0` branch is dead because `execute_sql` returns one row. |
| `compute_checksum` | `checksum_from_aggregate` + clamp WARNING | `md5` of `str(value)+'#'` per column |
| `main` | validates `--source_timezone`; datetime bounds via `datetime_bounds` | runs `CREATE FUNCTION if not exists format_decimal ...` on the replica **every run**, a write that needs a grant. `--include_json_columns` defaults to `True` (legacy `False`); `--sign_column` defaults to `_sign` (legacy `''`). |
| `fstr` | literal replace | `eval` (as above) |

**`db_compare/top_level_table_checksum.py`** (693 / 477); identical: `parse_config`, `get_tables_from_regexp`, `analyze_differences`, `unlock_tables`, `run_quick_safe_command`, `record_factory`. Whitespace-only: `validate_config`, `parse_checksum`, `run_quick_safe_checksum`, `match_table_include_list`.

| Function | Legacy | Packaged |
|---|---|---|
| `LockAcquisitionError`, `_mysql_error_code`, `_is_lock_wait_timeout`, `close_connection`, `quote_mysql_identifier`, `include_flags_clause`, `normalize_where_override`, `resolve_source_timezone` | present | absent |
| `get_mysql_checksum_command` / `get_clickhouse_checksum_command` | `set -eo pipefail;python db_compare/...` with `--source_timezone`, `--binary_encoding <arg>` on both sides, timestamp/JSON/hex column lists, partition date `toDate('d')` | `set -e pipefail;python db_compare/...`. Pipefail is **not** enabled, because `pipefail` becomes a positional parameter. Fixed `--min_datetime_value "1969-12-31 18:00:00" --max_datetime_value "2299-12-31 00:00:00"`. `--binary_encoding base64` on the MySQL side only. Partition date `toDate(\\'d\\')`. |
| `compute_checksum` | takes the per-table source lock (`LOCK TABLES <quoted> READ` with `SET SESSION lock_wait_timeout`). Runs source and replicas concurrently under the lock; results in submission order; unlock and close in `finally`. | no lock (the caller locks); results in completion order |
| `lock_tables` | `LOCK TABLES ... READ`, bounded wait, `LockAcquisitionError` | `FLUSH TABLE `t` WITH READ LOCK`, unbounded wait |
| `run_config` | normalises the `where` overrides; resolves the source zone; fetches timestamp/binary/JSON columns; a lock timeout is a logged COVERAGE GAP (or fatal with `--fail_on_lock_timeout`) | locks each table on a new connection **in the submission loop**, before submitting. With many tables, many locks are held while earlier futures run. Unlocks on collection. No coverage-gap handling. |
| `valid_date` | `except ValueError` | bare `except:` |
| `main` | adds `--source_timezone`, `--binary_encoding`, `--include_floating_point_columns`, `--include_json_columns`, `--lock_wait_timeout` (30), `--fail_on_lock_timeout` | none of these |

**`db_dump/mysql_dumper.py`** (329 / 289); identical: `check_program_exists`, `record_factory`.

| Function | Legacy | Packaged |
|---|---|---|
| `register_secret`, `redact_password` | present | absent |
| `run_command`, `run_quick_command` | DEBUG `cmd <redacted>` | DEBUG `cmd <command with --password "<secret>">` |
| `generate_mysqlsh_command` | `--password <shlex.quote(pw)>`, registers the secret, passes `consistent` | `--password "<pw>"`: double quotes, so `$`, `` ` ``, `"` and `\` in a password are shell-interpreted |
| `generate_mysqlsh_dump_tables_clause` | `"consistent": int(consistent)` in the dump options | no `consistent` key (mysqlsh default applies) |
| `main` | `--consistent` (default True) / `--no_consistent` | neither flag |
| module | `SCRIPT_DIR` + `sys.path.append` after the import (ineffective) | none |

**`db_load/clickhouse_loader.py`** (655 / 623); identical: `run_command`, `run_quick_command`, `get_connection`, `parse_schema_path`, `parse_schema_path_mysqlshell`, `find_primary_key`, `find_dump_timezone`, `find_create_table`, `find_partitioning_options`, `convert_to_clickhouse_table_regexp`, `convert_to_clickhouse_table`, `load_schema`, `load_schema_mysqlshell`, `get_column_list`, `check_program_exists`, `main`.

| Function | Legacy | Packaged |
|---|---|---|
| `get_unix_timezone_from_mysql_timezone` | identical in both copies since the D-13.01-1 fix: `UTC`, a fixed-offset `Etc/GMT±N` zone or a named zone, UTC + WARNING when undeterminable (Spec 13.04 §3.9) | identical. On 2.11.0 it discarded `sorted(timezones)`, iterated the unordered set and returned the last zone iterated when nothing matched, so the result changed with `PYTHONHASHSEED` (D-13.01-1). |
| `load_data` | `load_data_mysqlshell(..., dry_run=dry_run)`; `--password <shlex>`; secret registered | `dry_run=False` passed, with no effect because `execute_load` reads the global `args.dry_run`; `--password '<pw>'`; `--config-file '<path>'` |
| `load_data_mysqlshell` | password = the resolved `clickhouse_password` (config file or CLI); shlex-quoted | password = `args.clickhouse_password` (CLI only; a config-file password reaches `clickhouse-client` through `--config-file`); single-quoted |
| `execute_load` | `logging.info(redact_password(cmd))` | `logging.info(cmd)`: password in clear (FM-11.05-1) |
| `register_secret`, `redact_password` | present | absent |

**`db_load/mysql_resync.py`** (16 / 673). The legacy file is a launcher that inserts the root into `sys.path` and calls `ch_sink_tools.db_load.mysql_resync.main()`. There is one implementation, the packaged one (33 functions/methods, all packaged-only by construction). `python db_load/mysql_resync.py` and `ch-mysql-resync` behave identically.

#### 3.14.3 Files present in only one tree

- **Legacy only**:
  - `db/checksum_common.py` (147 lines): the spec 11.02 canonicalisation shared by both checksum sides (`checksum_from_aggregate`, the datetime clamp, `validate_timezone`, `canonical_datetime_bound`, ...).
  - All of `db_compare/tests/`, `db_dump/tests/`, `db_load/tests/`, `tests/`.
  - `__init__.py` at the root.
- **Packaged only**:
  - `config/column_type_overrides.py` (559) and `config/override_reconciler.py` (298); `db/postgres.py` (630).
  - `db_compare/_expressions.py` (62), `auto_diff.py` (1 149), `postgres_table_checksum.py` (798), `postgres_table_count.py` (204), `top_level_postgres_checksum.py` (2 141), `scripts/postgres_checksum_runner.sh` (134).
  - `db_dump/naming.py` (160) and `postgres_dumper.py` (2 836); `db_load/postgres_type_mapper.py` (532).
  - The `db_load/postgres_parser/` package: 598 hand-written lines (`postgres_parser.py` 150, the listener 335, `__init__.py` 8, the two bases 105) plus the generated files.

#### 3.14.4 Which copy each consumer uses

| Consumer | Copy |
|---|---|
| `ch-*` console scripts, the wheel, `build_wheel.sh --deploy` | packaged |
| `ch-mysql-resync` default loader (`sys.executable -m ch_sink_tools.db_load.clickhouse_loader`, `mysql_resync.py:348`) | packaged. `--loader-cmd "python db_load/clickhouse_loader.py"` selects the legacy one: `run_loader` sets `PYTHONPATH` and cwd to the package root (`mysql_resync.py:360-364`). |
| `Dockerfile_clickhouse_checksum`, `Dockerfile_mysql_checksum` | legacy |
| `test_db.sh`, `install.sh` users, the lightweight checksum CI | legacy |
| `postgres_checksum_runner.sh` | intends the packaged orchestrator; reaches nothing (§3.10.5) |
| Tests: `db_compare/tests/*` (111 test functions), `db_dump/tests/*` (12), `db_load/tests/test_clickhouse_loader_unit.py` (21), `test_loader_failure_modes.py::TestLegacyLoaderFailurePath` (2) | legacy: 146 functions |
| Tests: `test_mysql_resync.py` (29), `test_resync_failure_modes.py` (4), `test_postgres_type_mapper_unit.py` (16), `tests/test_naming.py` (36), `test_loader_failure_modes.py::TestPackagedLoaderRedaction` (1, skipped) | packaged: 86 functions |
| Packaged modules with **no** unit test | the MySQL checksum and count runners, `top_level_table_checksum`, `mysql_dumper`, `clickhouse_loader` (one skipped test), `mysql_parser`, `db/*`, `config/*`, `auto_diff`, all three PostgreSQL verification modules, `postgres_dumper` (except `filter_tables_by_regex`) |

#### 3.14.5 Cross-tree mixing hazards

- `ch-mysql-checksum` (packaged) now spawns `<sys.executable> -m ch_sink_tools.db_compare.mysql_table_checksum` and `... clickhouse_table_checksum` as argv lists without a shell, with the directory holding the imported `ch_sink_tools` first on the child's `PYTHONPATH`, so it runs the packaged sides of its own installation from any cwd, and a failed side is verdict `ERROR` with exit 1 (13.06 §3.1, §3.8; D-13.01-2 fixed, pinned by `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_side_module_starts_from_a_foreign_cwd_without_pythonpath`). On 2.11.0 it spawned `python db_compare/mysql_table_checksum.py` and `python db_compare/clickhouse_table_checksum.py` **relative to the cwd**, using whatever `python` is first on `PATH`. Three cases, as built on 2.11.0:
  - **cwd without `db_compare/`** (any installed use): both children fail with `can't open file`. Under `set -e pipefail` the pipeline exits 0 with the error text on stdout. Both sides parse as `(table, None, None)`, and `analyze_differences` logs `No difference for <t>` (reproduced, §5 R12; D-13.01-2). This is a stronger form of FM-11.02-2: any failure, not only a name collision, becomes a false "equal".
  - **cwd = `sink-connector/python` with `PYTHONPATH=.`**: the children are the **legacy** side scripts. Their parsers accept every flag the packaged orchestrator passes (reproduced, §5 R13). The MySQL side then runs with `--binary_encoding base64` and the ClickHouse side with the legacy default `hex`, both with `source_timezone=UTC` and the 1969-12-31 18:00:00 clamp floor. That differs from the result of either tree run alone.
  - **cwd = `sink-connector/python` without `PYTHONPATH`**: the children die with `No module named 'db'`, which is the first case again.
- The packaged `ch-mysql-load` and the legacy loader create different column types for the same DDL whenever an `enum`/`set` label contains `bit`, `blob` or `binary` (D-13.01-7). A table snapshotted with one copy and repaired with `ch-mysql-resync` (packaged loader, `CREATE TABLE ... AS <live>`) keeps the live type, so this matters for initial loads.

### 3.15 Tool → detailed spec cross-reference

| Tool / layer | Detailed spec | Related domain-11 spec |
|---|---|---|
| `db/clickhouse.py`, `db/mysql.py`, `ch_sink_tools/db/*` (connections, credentials, session settings) | 13.02 | 11.05 §3.1 |
| `mysql_dumper` (`ch-mysql-dump`) | 13.03 | 11.05 §3.2 |
| `clickhouse_loader` (`ch-mysql-load`), `mysql_parser/*`, `config/*` | 13.04 | 11.05 §3.3-3.4 |
| `postgres_dumper` (`ch-pg-dump`), `postgres_type_mapper`, `naming`, `postgres_parser/*` | 13.05 | none |
| `mysql_table_checksum`, `clickhouse_table_checksum` (`ch-ch-checksum`), `mysql_table_count`, `clickhouse_table_count` (`ch-ch-count`), `top_level_table_checksum` (`ch-mysql-checksum`), `db/checksum_common.py` | 13.06 | 11.02 |
| `top_level_postgres_checksum` (`ch-checksum`), `postgres_table_checksum` (`ch-pg-checksum`), `postgres_table_count` (`ch-pg-count`), `_expressions`, `auto_diff`, `postgres_checksum_runner.sh` | 13.07 | none |
| `mysql_resync` (`ch-mysql-resync`) | 13.08 | 11.04 |
| Packaging, trees, grammars, Dockerfiles, CI, launch scripts | 13.01 (this spec) | 11.05 FM-11.05-3 |

## 4. Invariants Preserved

These are the properties the toolset's architecture must have. The "As built" note says which hold on 2.11.0.

- **I-13.01-1 One behaviour per tool.** For a given tool and input, the legacy and packaged copies produce the same SQL, DDL, verdict and log content (secrets aside). *As built: violated* for every pair in §3.14.2, and for the listener through `is_binary_datatype`.
- **I-13.01-2 Every console script imports under the extras the README names for it.** *As built: violated* for the three MySQL commands under `[mysql]` (D-13.01-5).
- **I-13.01-3 `requires-python` is the real floor.** Every module imports and every code path runs on the declared minimum. *As built: violated*: the floor is 3.10 (D-13.01-6).
- **I-13.01-4 Generated code is reproducible from the committed grammars into the directory that is imported.** *As built: violated* (D-13.01-8, D-13.01-9). The committed MySQL output does match its grammar (§3.7).
- **I-13.01-5 A launcher that cannot run its tool fails non-zero and never prints a success-shaped result.** *As built: violated* by `test_db.sh` (D-13.01-13). On 2.11.0 it was also violated by `ch-mysql-checksum` from a foreign cwd (D-13.01-2, now fixed). It holds for `postgres_checksum_runner.sh` (exit 2) and for the legacy scripts without `PYTHONPATH` (exit 1).
- **I-13.01-6 The wheel contains every file a packaged tool reads at runtime and no source-tree path is assumed.** *As built*: the contents hold (§3.4). The path assumption is violated by `postgres_checksum_runner.sh` (and was by `ch-mysql-checksum` until D-13.01-2 was fixed).
- **I-13.01-7 No secret is written to a log or a process argument by the copy a user installs.** *As built: violated* by the packaged dumper, loader and `resolve_credentials_from_config` (D-13.01-4).
- **I-13.01-8 Documentation matches the build.** *As built: violated* (§3.13).

## 5. Verification Criteria

End-to-end suites (Pull Request Pipeline, `.github/workflows/pull-request.yml` jobs `python-toolset-e2e-mysql` and
`python-toolset-e2e-postgres`, defined in `.github/workflows/python-toolset-e2e-mysql.yml` and
`.github/workflows/python-toolset-e2e-postgres.yml`): `sink-connector/python/tests_e2e/mysql/` and
`sink-connector/python/tests_e2e/postgres/` run the installed tools (`pip install -e`, `source ./install.sh` in a copy of
the tree as the scheduled checksum job does) against real MySQL, PostgreSQL, ClickHouse and the lightweight connector.
Each suite's `JUSTIFICATION.md` maps every test to the fix it proves, with its outcome on the pre-fix tools. The tool
contracts they exercise are specified in 13.03 to 13.08 (§5 of each lists its e2e tests). This partly closes
D-13.01-24: the console scripts are installed and run in CI; the wheel build, the Python floor, grammar regeneration and
the Dockerfiles remain unchecked.

Offline reproductions run for this spec. Python 3.12.11 venv with the dependencies of §3.6, plus the system Python 3.6.8 and 3.9.7 interpreters. All were throwaway scripts outside the repository; no database or network was used.

- **R1 Wheel.** `pip wheel --no-deps --no-build-isolation --no-index -w <out> <copy of sink-connector/python>` (setuptools 84.0.0, wheel 0.48.0) prints `Successfully built ch-sink-tools`, `ch_sink_tools-0.3.0-py3-none-any.whl size=1241730`. Listing the zip gives 50 `ch_sink_tools/` files and the `entry_points.txt` of §3.3. Applying the `build_wheel.sh:38` regex to the member names yields `['ch_sink_tools/__init__.py']`; there are no directory entries.
- **R2 Profiles.** Each console target is imported in a fresh interpreter with a meta-path finder that raises `ModuleNotFoundError` for blocked top-level names: core blocks `pymysql,sqlalchemy,antlr4,pandas`; core+mysql blocks `pandas`; core+dataframe blocks `pymysql,sqlalchemy,antlr4`. Output as in §3.6.
- **R3 Old interpreters.** Under `python3.6` and `python3.9`, every entry point plus two legacy modules is imported with `clickhouse_driver, yaml, psycopg2, pymysql, sqlalchemy, pandas, antlr4` replaced by stub modules. 3.6 prints `ch-pg-dump FAIL ModuleNotFoundError: No module named 'dataclasses'`, `ch-mysql-load FAIL ... 'zoneinfo'`, `ch-mysql-resync FAIL SyntaxError: future feature annotations is not defined (mysql_resync.py, line 33)`. `compile()` of every file under 3.6 reports 2 syntax failures out of 80 (`mysql_resync.py:33`, `CreateTablePostgreSQLParserListener.py:21`). `python3.6 -m unittest sink-connector/python/db_load/tests/test_mysql_resync.py` fails at import with the same `SyntaxError`.
- **R4 Launch matrix.** `python <script> --help` for the nine legacy launchers from the Python root:
  - without `PYTHONPATH`: eight print `ModuleNotFoundError: No module named 'db'` (or `'db_load'`), exit 1; only `db_load/mysql_resync.py` works.
  - with `PYTHONPATH=.`: all print usage. `mysql_parser.py --help` raises `FileNotFoundError: ... '--help'`.
  - `python -m ch_sink_tools.<...> --help` works for all 13 packaged runnable modules.
- **R5 Import all.** All 53 modules print `OK`.
- **R6 fstr.** With `--where`-style template `"1=1 and {partition_expression}=20240101 and {__import__('os').getpid() > 0}"`:
  - the packaged `mysql_table_count.fstr` and `clickhouse_table_checksum.fstr` return `'1=1 and toYYYYMMDD(d)=20240101 and True'` on 3.12. The embedded expression was executed.
  - the legacy copies return the template with only the placeholder replaced.
  - on 3.9.7 both packaged copies raise `TypeError: 'staticmethod' object is not callable`.
- **R7 Grammar currency.** Rule-name and token-name sets as in §3.7. `filecmp.cmp(shallow=False)` of the seven MySQL generated files across trees: `True`.
- **R8 Listener.** `convert_to_clickhouse_table_antlr(ddl, True)` from both trees:
  - `e enum('orbit','x')`: legacy `e enum('orbit','x') NOT NULL`, packaged `e String NOT NULL`.
  - `v varchar(10) charset latin1`: both keep `charset latin1`.
  - `v int null`: both emit `v int null` but map `('v','int',False)`.
  - `g ... GENERATED ALWAYS AS (concat(_code, 'x'))`: both emit `MATERIALIZED concat('x')`.
  - `SELECT ... OVER (PARTITION BY d)`: `AttributeError: 'PartitionClauseContext' object has no attribute 'partitionTypeDef'`.
  - `PARTITION BY RANGE COLUMNS(d)`: no `partition by` emitted.
- **R9 PostgreSQL parser.** Outputs as in §3.9. The method-presence check lists the 11 missing lexer-base methods and `ParseRoutineBody`.
- **R10 Cron runner.** Run with `PG_CH_CHECKSUM_LOG_DIR=<tmp>` and `PYTHON_VENV=/nonexistent` from a copy of the tree. With a config: `python: can't open file '.../ch_sink_tools/db_compare/db_compare/top_level_postgres_checksum.py'`, `CHECKSUM FAILED (exit=2)`. Without one: `ERROR: Config file not found: .../scripts/config_postgres_system.yml`, exit 2.
- **R11 Function diff.** As described in §3.14 (AST walk, `ch_sink_tools.` normalised, `difflib` per body).
- **R12 False match.** From an empty temporary cwd, the packaged `compute_checksum('mydb', {}, {}, 't1', ..., 'mysql-host', ['ch-host'], ...)` returns `[('ch-host','t1',None,None), ('mysql-host','t1',None,None)]`. The mysql pipeline alone returns `rc=0`. `analyze_differences` logs `INFO No difference for t1`.
- **R13 Mixing.** The legacy `build_argument_parser()` of both side scripts parses the exact argv built by the packaged command builders. The resulting namespaces show `binary_encoding='base64'` (MySQL side) vs `'hex'` (ClickHouse side) and `source_timezone='UTC'`.
- **R14 Time zone.** `get_unix_timezone_from_mysql_timezone` from both loaders, in four subprocesses with `PYTHONHASHSEED=0..3`:

  | offset | legacy (all seeds) | packaged, seeds 0..3 |
  |---|---|---|
  | `+05:45` | `Asia/Kathmandu` | `Asia/Kathmandu` / `Asia/Katmandu` |
  | `+01:00` | `Africa/Algiers` | `Africa/Brazzaville`, `WET`, `Africa/Casablanca`, `Atlantic/Canary` |
  | `+13:37` (no zone) | `UTC` | `Asia/Kuala_Lumpur`, `Europe/San_Marino`, `Europe/Lisbon`, `Japan` |

- **R15 README.** `python -m ch_sink_tools.db_dump.postgres_dumper --pg-host pgserver --ch-host chserver --pg-database mydb` and `python -m ch_sink_tools.db_compare.postgres_table_count --config config.yml` print the argparse errors quoted in §3.13.
- **Baseline suite**: `python -m pytest -q -p no:cacheprovider db_compare/tests db_load/tests db_dump/tests tests` from `sink-connector/python` gives `227 passed, 5 skipped in 6.13s`. `python -m unittest sink-connector/python/db_load/tests/test_mysql_resync.py` from the repo root (the CI command) gives `Ran 29 tests ... OK`.

Existing tests that pin parts of this contract:

- `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestPackagedLoaderRedaction::test_logged_command_is_redacted` (skipped, DEFECT; packaged copy).
- `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestLegacyLoaderFailurePath::test_logged_command_is_redacted` (legacy copy).
- `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestUnixTimezoneFromMysqlTimezone::test_unknown_offset_falls_back_to_utc` pins the **legacy** fallback only.
- `sink-connector/python/db_load/tests/test_clickhouse_loader_unit.py::TestDdlConversionAntlr::test_returns_ddl_and_columns` (legacy translator).
- `sink-connector/python/db_dump/tests/test_mysql_dumper_unit.py::TestRedactPassword::test_registered_secret_is_masked` (legacy only; the packaged dumper has no `redact_password`).
- `sink-connector/python/db_compare/tests/test_checksum_failure_modes.py::TestVerdictOnUnparseableOutput::test_both_sides_unparseable_is_never_reported_equal` (passing since the verdict fix; legacy orchestrator; same verdict logic as packaged).
- `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_side_module_starts_from_a_foreign_cwd_without_pythonpath` (packaged orchestrator; G6).

Required new tests (GAPs, referenced from §6):

- G1: import every console script under `[mysql]` only.
- G2: run the offline suite under the declared minimum Python.
- G3: an equality test that runs each function pair of §3.14.2 on both trees and asserts the same output.
- G4: regenerate the grammars in CI and `git diff --exit-code`.
- G5: packaged `get_unix_timezone_from_mysql_timezone` is deterministic and falls back to UTC. Closed by `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestDumpTimezoneMapping`.
- G6: `ch-mysql-checksum` from a foreign cwd exits non-zero. Added (see the list above).
- G7: build each Dockerfile and run `--help`.
- G8: `parse_postgres_ddl` on `DEFAULT -1`.
- G9: listener tests for `charset`, `null` and `_ident` inside generated expressions. `charset` and `null` are covered by `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestColumnModifiers`; `_ident` remains open.

## 6. Failure Modes & Recovery

The component is the delivery layer: which code runs, under which interpreter and dependencies, and from where. Its failures either run the wrong copy of a tool or make a launcher print a result it never computed.

- **FM-13.01-1 A dump's time zone resolves to a random zone in the packaged loader**
  - **Trigger**: `ch-mysql-load`, or `ch-mysql-resync patch` (whose default loader is the packaged module), on a dump whose `SET TIME_ZONE='<offset>'` matches several IANA zones today, or none.
  - **Behaviour**: `ch_sink_tools/db_load/clickhouse_loader.py:266-284` discards `sorted(timezones)` (line 269) and iterates the unordered set. It breaks on the first match, and with no match returns the last zone iterated instead of `"UTC"`. The load runs under `export TZ=<zone>` (`clickhouse_loader.py:517`, and line 440 on the gzip path). Zones sharing today's offset can differ on other dates (`WET` vs `Africa/Algiers`), so `TIMESTAMP` values of other dates, or of every date when nothing matched, are shifted.
  - **Detection**: none. The chosen zone is not logged above DEBUG. The spec 11.04 canary or the spec 11.02 checksum exposes it after the load.
  - **Blast radius**: every `TIMESTAMP` column of every table loaded in that run, and it differs from run to run.
  - **Recovery**: reload with the legacy loader (`--loader-cmd "python db_load/clickhouse_loader.py"` for resync), or with a dump taken under `SET TIME_ZONE='+00:00'`. Verify with the legacy checksum.
  - **RTO**: a reload of the affected tables (proportional to size; unmeasured).
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestDumpTimezoneMapping::test_deterministic_across_hash_seeds`
  - **FIXED**: D-13.01-1 (= D-13.04-2). Both copies map the zone deterministically and fall back to UTC with a WARNING (Spec 13.04 §3.9).

- **FM-13.01-2 An installed `ch-mysql-checksum` reports equality without comparing**
  - **Trigger**: `ch-mysql-checksum --config_file ...` from any directory that has no `db_compare/` with working side scripts. That is every use of the installed wheel, and every use from the Python root without `PYTHONPATH`.
  - **Behaviour**: `ch_sink_tools/db_compare/top_level_table_checksum.py:159,186` run `set -e pipefail;python db_compare/<side>_table_checksum.py ... | grep -i checksum | awk ...`. `python` fails (`can't open file` / `No module named 'db'`). Pipefail is off, so the pipeline returns awk's 0. stderr is merged into the captured stdout. `parse_checksum` (lines 61-75) returns `(table, None, None)` for both sides, and `analyze_differences` logs `No difference for <t>`. The run ends `sys.exit(0)` (line 364).
  - **Detection**: an ERROR `Invalid checksum output from b"python: can't open file ..."` per side, next to the INFO verdict. Nothing fails.
  - **Blast radius**: every table of the run is reported equal. A scheduler trusting the log verdict or the exit code records a fully unverified replica as verified.
  - **Recovery**: run the legacy orchestrator from `sink-connector/python` with `PYTHONPATH=.` (`python db_compare/top_level_table_checksum.py`). Treat any `Invalid checksum output` line as a failed run.
  - **RTO**: one legacy re-run (proportional to data size; unmeasured).
  - **Test**: `sink-connector/python/db_compare/tests/test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_both_sides_failing_exits_non_zero_and_never_reports_equal` (and `...::test_side_module_starts_from_a_foreign_cwd_without_pythonpath`, G6).
  - **FIXED**: D-13.01-2 (= D-13.06-1). The packaged sides run as `<sys.executable> -m ch_sink_tools.db_compare.<side>` from any cwd; a failed side is verdict `ERROR` and the run exits 1.

- **FM-13.01-3 A generated column becomes a different MATERIALIZED expression**
  - **Trigger**: initial load (schema phase) of a table with a generated column whose expression contains an identifier starting with `_` before a quote, e.g. `concat(_code, 'x')`.
  - **Behaviour**: `CreateTableMySQLParserListener.py:98` (both copies) strips `\b_.*?'`, so the DDL gets `MATERIALIZED concat('x')`. The loader skips generated columns when loading (`clickhouse_loader.py:496`), so ClickHouse computes the wrong value for every row.
  - **Detection**: none at load. A checksum excludes nothing here, so the spec 11.02 checksum reports a difference later.
  - **Blast radius**: that column on every row of the table.
  - **Recovery**: `ALTER TABLE ... MODIFY COLUMN <col> <type> MATERIALIZED <correct expr>` and `ALTER TABLE ... MATERIALIZE COLUMN <col>` on the replica, or recreate the table from the connector's DDL and reload.
  - **RTO**: one column rewrite or reload (unmeasured).
  - **Test**: GAP: G9.
  - **DEFECT**: D-13.01-3, the collation-introducer regex is not anchored to an introducer.

- **FM-13.01-4 The installed copy leaks credentials or executes `--where`**
  - **Trigger**: any `ch-mysql-dump`, `ch-mysql-load`, `ch-ch-checksum` or `ch-mysql-checksum` run with a password on the command line or at DEBUG. Or any packaged checksum/count run whose `--where` contains `{partition_expression}`, including `--partition_date` runs of `ch-mysql-checksum`.
  - **Behaviour**:
    - The packaged `run_command`/`run_quick_command`/`execute_load` log the full command with `--password "<pw>"` or `'<pw>'`.
    - `ch_sink_tools/db/clickhouse.py:68` logs the config-file password at DEBUG.
    - The three packaged `fstr` (`mysql_table_count.py:44-46`, `mysql_table_checksum.py:168-170`, `clickhouse_table_checksum.py:218-220`) `eval` the template as an f-string.
    - `release-notes/2.10.0.md:11` states all of this was fixed "in `ch_sink_tools`".
  - **Detection**: none. The leak is silent, and the `eval` is silent unless the expression raises.
  - **Blast radius**: MySQL and ClickHouse credentials in log files. Arbitrary Python executed with the operator's privileges by anyone who can influence a `--where` or a YAML `where` override.
  - **Recovery**: rotate the credentials, scrub the logs, and run the legacy copies until the packaged tree is re-synchronised with them.
  - **RTO**: a credential rotation (operator-dependent; unmeasured).
  - **Test**: `sink-connector/python/db_load/tests/test_loader_failure_modes.py::TestPackagedLoaderRedaction::test_logged_command_is_redacted` (skipped). GAP for the dumper, `resolve_credentials_from_config` and `fstr` of the packaged copies (G3).
  - **DEFECT**: D-13.01-4, the entry points ship the pre-fix generation of the MySQL tools, and the release notes say otherwise.

- **FM-13.01-5 The MySQL commands do not start under the documented extra**
  - **Trigger**: `pip install "ch_sink_tools-...whl[mysql]"` as the README says, then `ch-mysql-checksum`, `ch-mysql-dump` or `ch-mysql-load`. This includes `ch-mysql-resync patch`, whose loader subprocess then fails.
  - **Behaviour**: `ch_sink_tools/db/mysql.py:8` imports `pandas` at module top, and pandas is only in `[dataframe]`. The command dies with `ModuleNotFoundError: No module named 'pandas'` before parsing arguments. Under resync the load log shows it and the table is `LOAD_FAILED`.
  - **Detection**: loud (traceback, non-zero exit).
  - **Blast radius**: no data effect. The tool is unusable until reinstalled.
  - **Recovery**: `pip install "<wheel>[all]"` or `pip install pandas`.
  - **RTO**: one package install (minutes; unmeasured).
  - **Test**: GAP: G1.
  - **DEFECT**: D-13.01-5, `[mysql]` omits a hard dependency of every MySQL tool.

- **FM-13.01-6 A tool fails on a Python the metadata accepts**
  - **Trigger**: installing on Python 3.6 to 3.9. pip accepts it because of `Requires-Python: >=3.6`.
  - **Behaviour**:
    - 3.6: `ch-pg-dump` (`dataclasses`), `ch-mysql-load` (`zoneinfo`) and `ch-mysql-resync` (`SyntaxError`) fail at import.
    - 3.7 and 3.8: `ch-mysql-load` and the legacy checksum scripts fail at import (`zoneinfo`).
    - ≤ 3.9: the packaged `fstr` raises `TypeError` on the first `{partition_expression}` `--where`, mid-run, after other tables may have been checksummed. `postgres_parser` does not import before 3.9.
  - **Detection**: loud (traceback).
  - **Blast radius**: tool unavailable, or a checksum run aborted part-way.
  - **Recovery**: use Python ≥ 3.10.
  - **RTO**: an interpreter install (unmeasured).
  - **Test**: GAP: G2.
  - **DEFECT**: D-13.01-6, `requires-python` understates the floor (3.10).

- **FM-13.01-7 The two loaders create different column types for the same DDL**
  - **Trigger**: an initial load of a table with an `enum(...)` or `set(...)` whose labels contain `bit`, `blob` or `binary` (e.g. `'orbit'`, `'blobby'`, `'binary_ok'`).
  - **Behaviour**: the packaged `is_binary_datatype` (`ch_sink_tools/db/mysql.py:13-17`) substring-matches, so `CreateTableMySQLParserListener.py:51` maps the column to `String`. The legacy one (`db/mysql.py:16-25`) keeps `enum(...)`.
  - **Detection**: none. Visible only by comparing `SHOW CREATE TABLE` across loads.
  - **Blast radius**: schema of the replica depends on which copy did the initial load. The connector's later DDL and inserts may disagree with a `String` column.
  - **Recovery**: recreate the table from the connector's DDL and reload with the legacy loader.
  - **RTO**: one table reload (unmeasured).
  - **Test**: GAP: G3.
  - **DEFECT**: D-13.01-7, a shared helper diverged, changing the output of a textually identical listener.

- **FM-13.01-8 Regenerated grammars never reach the shipped package**
  - **Trigger**: a grammar change followed by `bash build_grammars.sh`.
  - **Behaviour**: MySQL output goes to `db_load/mysql_parser` only. PostgreSQL output goes to `db_load/postgres_parser`, a directory that nothing imports (`build_grammars.sh:5,15-29`). `ch_sink_tools/` keeps the old generated files, and the wheel ships them.
  - **Detection**: none. Only a manual diff of the two `mysql_parser` directories shows it.
  - **Blast radius**: the packaged loader and the PostgreSQL parser keep parsing with the old grammar. The two MySQL trees diverge.
  - **Recovery**: copy the generated files into `ch_sink_tools/db_load/{mysql,postgres}_parser/` by hand and rebuild the wheel.
  - **RTO**: minutes (unmeasured).
  - **Test**: GAP: G4.
  - **DEFECT**: D-13.01-8, the generator writes into the wrong tree.

- **FM-13.01-9 The PostgreSQL DDL parser crashes on ordinary DDL**
  - **Trigger**: `parse_postgres_ddl` on DDL containing a unary minus or division in an expression, a dollar-quoted string, `<<`, `..`, or a `CREATE FUNCTION` body.
  - **Behaviour**: generated actions such as `this.CheckLaMinus()` (`PostgreSQLLexer.py:3334`) raise `NameError: name 'this' is not defined`. The base stubs lack the methods anyway (§3.7).
  - **Detection**: loud (exception). No tool calls the parser today, so nothing fails in production.
  - **Blast radius**: any future caller. Today it is dead code shipped in the wheel (≈ 3 MB of generated code).
  - **Recovery**: regenerate with the grammars-v4 Python transform (`this.` → `self.`) and implement the missing base methods, or drop the package.
  - **RTO**: unmeasured (a code change).
  - **Test**: GAP: G8.
  - **DEFECT**: D-13.01-9, generated without the target-language transform.

- **FM-13.01-10 The PostgreSQL cron runner always fails**
  - **Trigger**: running `postgres_checksum_runner.sh` from the wheel or a checkout, e.g. from the cron line in its header.
  - **Behaviour**: it `cd`s to `ch_sink_tools/db_compare` and runs `python db_compare/top_level_postgres_checksum.py`, which does not exist there (`postgres_checksum_runner.sh:29-30,98,104`). Exit 2, `CHECKSUM FAILED`. Without an argument the default config path is also missing.
  - **Detection**: loud (exit 2 and a `CHECKSUM FAILED` line on stderr), but it reads like a data failure.
  - **Blast radius**: no periodic PostgreSQL verification happens.
  - **Recovery**: schedule `ch-checksum --config <file>` directly.
  - **RTO**: one crontab edit (minutes).
  - **Test**: GAP: a test that runs the runner from an installed wheel with a stub config and expects the orchestrator to start.
  - **DEFECT**: D-13.01-10, the paths are written for a layout neither tree has.

- **FM-13.01-11 The checksum Docker images connect to a host called `$CLICKHOUSE_HOST`**
  - **Trigger**: `docker run -e CLICKHOUSE_HOST=... <image>` (or the MySQL image) as the header comment suggests.
  - **Behaviour**: the exec-form `ENTRYPOINT` (`Dockerfile_clickhouse_checksum:20`, `Dockerfile_mysql_checksum:20`) passes `$CLICKHOUSE_HOST` etc. literally. The tool fails to resolve that host and exits 1.
  - **Detection**: loud (connection error).
  - **Blast radius**: the image is unusable as documented.
  - **Recovery**: override the entrypoint with real arguments: `docker run --entrypoint python3.10 <image> /app/db_compare/clickhouse_table_checksum.py --clickhouse_host ch-host ...`.
  - **RTO**: minutes.
  - **Test**: GAP: G7.
  - **DEFECT**: D-13.01-11, env-var placeholders in an exec-form entrypoint.

- **FM-13.01-12 The load image builds no loader**
  - **Trigger**: `docker build -f Dockerfile_db_load .`.
  - **Behaviour**: the image contains no Python tool, and its `apt-get` steps presume a Debian base and a retired ClickHouse apt repository (`Dockerfile_db_load:2,5-11`).
  - **Detection**: the build fails loudly. If it succeeds, the image has only `mysqlsh` and `clickhouse-client`.
  - **Blast radius**: no containerised load path exists.
  - **Recovery**: install the wheel (`[all]`) on a host with `clickhouse-client` and `zstd`.
  - **RTO**: unmeasured.
  - **Test**: GAP: G7.
  - **DEFECT**: D-13.01-12, the Dockerfile does not package the tool its name promises.

- **FM-13.01-13 `test_db.sh` passes when nothing ran**
  - **Trigger**: `./test_db.sh mydb` in a shell without `PYTHONPATH=.`.
  - **Behaviour**: the database is dropped, then the loader and both checksums die with `No module named 'db'`. The pipes leave two empty files, and `diff` prints nothing (`test_db.sh:5-9`).
  - **Detection**: none. The tracebacks scroll past, and empty output is the pass signal.
  - **Blast radius**: the developer believes a load and checksum round trip passed. The ClickHouse database `mydb` has been dropped.
  - **Recovery**: `export PYTHONPATH=.` and re-run. Check that both `mydb.ch` and `mydb.mysql` are non-empty.
  - **RTO**: one re-run.
  - **Test**: GAP: a shell test that runs `test_db.sh` with a missing tool and expects a non-zero exit.
  - **DEFECT**: D-13.01-13, no `set -eo pipefail` and no output check.

- **FM-13.01-14 A legacy dumper or loader started by path cannot import its helpers**
  - **Trigger**: `python db_dump/mysql_dumper.py ...` or `python db_load/clickhouse_loader.py ...` without `PYTHONPATH`. This includes `--loader-cmd "python db_load/clickhouse_loader.py"` with an explicit `--loader-cwd` and a scrubbed environment.
  - **Behaviour**: `from db.mysql import *` (line 16) / `from db.mysql import is_binary_datatype` (line 4) runs before the `sys.path.append` at lines 26-27 / 24-25, so the import fails: `ModuleNotFoundError: No module named 'db'`.
  - **Detection**: loud (exit 1).
  - **Blast radius**: tool unavailable. Under resync the table is `LOAD_FAILED`.
  - **Recovery**: `export PYTHONPATH=<sink-connector/python>`.
  - **RTO**: seconds.
  - **Test**: GAP: launch each legacy script by path with an empty `PYTHONPATH` and expect usage on `--help`.
  - **DEFECT**: D-13.01-14, the path fix is placed after the import it is meant to enable.

- **FM-13.01-15 A lower-case `charset` makes the CREATE TABLE fail**
  - **Trigger**: schema load of hand-written or tool-produced DDL with a lower-case `charset <cs>` attribute on a column.
  - **Behaviour**: `CreateTableMySQLParserListener.py:37` passes `re.IGNORECASE` as `count`. The attribute stays in the type, so ClickHouse rejects `varchar(10) charset latin1`. The loader's broad `except` (FM-11.05-5) does not apply here, because the ANTLR path succeeded.
  - **Detection**: loud (ClickHouse syntax error on CREATE).
  - **Blast radius**: that table is not created.
  - **Recovery**: upper-case the attribute in the dump's DDL file and reload the schema.
  - **RTO**: minutes per table.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestColumnModifiers::test_lower_case_charset_is_stripped`
  - **FIXED**: D-13.01-15 (= D-13.04-28). `flags=re.IGNORECASE`.

- **FM-13.01-16 A lower-case `null` constraint loads NULLs as non-null**
  - **Trigger**: data load (mysqlsh path) of a table whose DDL declares a column `... null` in lower case.
  - **Behaviour**: `CreateTableMySQLParserListener.py:77-89` records `nullable: False` while emitting `NULL` into the DDL. `clickhouse_loader.py:505-513` declares that column `String` in `input(...)`, so a TSV `\N` is read into a non-Nullable `String` before conversion. With ClickHouse defaults that yields the type default instead of NULL, or a conversion error. The effect is inferred from the code path; no ClickHouse run.
  - **Detection**: none, or a loud conversion error, depending on the target type.
  - **Blast radius**: NULLs of that column on every row.
  - **Recovery**: fix the DDL case and reload the table.
  - **RTO**: one table reload.
  - **Test**: `sink-connector/python/db_load/tests/test_loader_s1_fixes.py::TestColumnModifiers::test_lower_case_null_is_nullable`
  - **FIXED**: D-13.01-16 (= D-13.04-7). The NULL modifier test is case-insensitive.

- **FM-13.01-17 Packaging regressions merge unseen**
  - **Trigger**: any change under `sink-connector/python/`.
  - **Behaviour**:
    - CI runs only the 29 packaged resync tests on Python 3.12 without dependencies (`spec-governance.yml:88`), and live checksums of the legacy side scripts.
    - The wheel build, the console-script imports, the extras, the declared Python floor, the grammar regeneration and the three Dockerfiles are never exercised (§3.12).
  - **Detection**: none until an operator installs and runs.
  - **Blast radius**: FM-13.01-2, -5, -6, -8, -10, -11 and -12 all reached `2.11.0` this way.
  - **Recovery**: build the wheel and run R1-R5 of §5 manually before a release.
  - **RTO**: about 1 minute for R1-R5 on the dev host (the offline suite alone takes 6.13 s, measured).
  - **Test**: GAP: G1, G2, G4, G7 as workflow steps.
  - **DEFECT**: D-13.01-24, no CI job builds or imports what users install (extends FM-11.05-3).

Summary: 17 failure modes, 13 DEFECT, 13 GAP.

## 7. Defect Register

| ID | Severity | Copy (legacy/packaged/both) | Location | Evidence | Summary |
|---|---|---|---|---|---|
| D-13.01-1 | S1 | packaged | `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py:266-284` | reproduced (R14: four hash seeds, four different zones for `+01:00`, `Japan`/`Europe/Lisbon`/... for an unmatched offset; legacy stable and `UTC`) | FIXED (with D-13.04-2): deterministic mapping in both copies, UTC + WARNING when undeterminable. Test `test_loader_s1_fixes.py::TestDumpTimezoneMapping`. Was: dump time zone resolved by unordered set iteration, last zone returned when nothing matched; affected `ch-mysql-load` and `ch-mysql-resync`. |
| D-13.01-2 | S1 | packaged | `sink-connector/python/ch_sink_tools/db_compare/top_level_table_checksum.py:159,186` | reproduced (R12: empty cwd → both sides `None` → `No difference for t1`, pipeline rc 0) | FIXED (same fix as D-13.06-1): sides run as `<sys.executable> -m ch_sink_tools.db_compare.<side>` without a shell; a failed side is `ERROR`, exit 1. Test: `test_packaged_checksum_verdicts.py::TestPackagedSidesAndFailures::test_side_module_starts_from_a_foreign_cwd_without_pythonpath`. Was: `ch-mysql-checksum` spawned cwd-relative side scripts under `set -e pipefail`; installed use reported equality without comparing. |
| D-13.01-3 | S1 | both | `sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py:98` (same line in packaged) | reproduced (R8: `concat(_code, 'x')` → `MATERIALIZED concat('x')`) | Introducer-strip regex deletes identifiers starting with `_`, silently changing MATERIALIZED expressions. |
| D-13.01-4 | S2 | packaged | `sink-connector/python/ch_sink_tools/db_compare/mysql_table_count.py:44-46`, `.../mysql_table_checksum.py:168-170`, `.../clickhouse_table_checksum.py:218-220`, `sink-connector/python/ch_sink_tools/db_dump/mysql_dumper.py` `run_command`/`generate_mysqlsh_command`, `sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py` `execute_load`, `sink-connector/python/ch_sink_tools/db/clickhouse.py:68`; `release-notes/2.10.0.md:11` | reproduced (R6: `{__import__('os').getpid() > 0}` evaluated to `True`); code-read for the log lines (§3.14.2) | The installed copy keeps `eval()` of `--where` and logs passwords; release notes claim both fixed in `ch_sink_tools`. |
| D-13.01-5 | S3 | packaged | `sink-connector/python/pyproject.toml:23-30`, `sink-connector/python/ch_sink_tools/db/mysql.py:8` | reproduced (R2: `No module named 'pandas'` under `[mysql]`) | `[mysql]` extra omits pandas, a top-level import of every MySQL tool; README promises `[mysql]` suffices. |
| D-13.01-6 | S3 | both | `sink-connector/python/pyproject.toml:10`; `ch_sink_tools/config/column_type_overrides.py:24`; `ch_sink_tools/db_load/clickhouse_loader.py:19`; `ch_sink_tools/db_load/mysql_resync.py:33`; `ch_sink_tools/db_load/postgres_parser/postgres_parser.py:127`; packaged `fstr` `@staticmethod`; `db/checksum_common.py:10` | reproduced (R3 on 3.6.8; R6 `TypeError` on 3.9.7) | `requires-python >=3.6` while the real floor is 3.10. |
| D-13.01-7 | S3 | packaged (divergence) | `sink-connector/python/ch_sink_tools/db/mysql.py:13-17` vs `sink-connector/python/db/mysql.py:16-25`, used at `CreateTableMySQLParserListener.py:51` | reproduced (R8: `enum('orbit','x')` → packaged `String`, legacy `enum`) | Identical listener produces different DDL per tree because `is_binary_datatype` substring-matches in the packaged copy. |
| D-13.01-8 | S3 | both | `sink-connector/python/build_grammars.sh:4-5,15-29` | code-read (outputs `-o db_load/mysql_parser` and `-o ../../db_load/postgres_parser`; the importers use `ch_sink_tools.db_load.*`) | Grammar regeneration writes into the legacy tree and a non-imported directory, never into the shipped package. |
| D-13.01-9 | S3 | packaged | `sink-connector/python/ch_sink_tools/db_load/postgres_parser/PostgreSQLLexer.py:3294-3374`, `PostgreSQLParser.py:36010,58290,72714`, `PostgreSQLLexerBase.py` | reproduced (R9: `DEFAULT -1`, `4/2`, `$$x$$` → `NameError: name 'this' is not defined`) | PostgreSQL parser generated without the Python transform; base stubs lack 12 called methods. Package unused. |
| D-13.01-10 | S3 | packaged | `sink-connector/python/ch_sink_tools/db_compare/scripts/postgres_checksum_runner.sh:29-33,98,104` | reproduced (R10: exit 2, `can't open file .../db_compare/db_compare/top_level_postgres_checksum.py`) | Shipped cron runner resolves paths for a non-existent layout and always fails. |
| D-13.01-11 | S3 | legacy | `sink-connector/python/Dockerfile_clickhouse_checksum:20`, `sink-connector/python/Dockerfile_mysql_checksum:20` | code-read (exec-form `ENTRYPOINT` performs no `$VAR` substitution) | Checksum images pass literal `$CLICKHOUSE_HOST`/`$MYSQL_HOST`... to the tool; imports work only via the accidental empty `PYTHONPATH` component. |
| D-13.01-12 | S3 | n/a (no Python) | `sink-connector/python/Dockerfile_db_load:2-19` | code-read (no `COPY` of any tool; `apt-get` on `mysql:latest`; retired apt repository). Image build not attempted offline. | "db_load" image contains no loader and its install steps presume a Debian base. |
| D-13.01-13 | S3 | legacy | `sink-connector/python/test_db.sh:5-9` | reproduced in part (R4: the three commands fail `No module named 'db'` without `PYTHONPATH`); the empty-diff pass is code-read | Dev harness drops the DB, masks tool failures through pipes and prints nothing, which looks like success. |
| D-13.01-14 | S3 | legacy | `sink-connector/python/db_dump/mysql_dumper.py:16,26-27`, `sink-connector/python/db_load/clickhouse_loader.py:4,21,24-25` | reproduced (R4) | `sys.path.append` placed after the imports it should enable. |
| D-13.01-15 | S3 | both | `sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py:37` | reproduced (R8: `charset latin1` kept in the ClickHouse DDL) | FIXED (with D-13.04-28): `flags=re.IGNORECASE`. Test `test_loader_s1_fixes.py::TestColumnModifiers::test_lower_case_charset_is_stripped`. Was: `re.IGNORECASE` passed as `count`; lower-case `charset` survived into ClickHouse DDL. |
| D-13.01-16 | S3 | both | `sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py:65-89` | reproduced (R8: DDL `v int null`, map `nullable False`); load effect code-read | FIXED (with D-13.04-7): case-insensitive `NULL` test. Test `test_loader_s1_fixes.py::TestColumnModifiers::test_lower_case_null_is_nullable`. Was: lower-case `null` recorded as NOT NULL in the column map used to build the load structure. |
| D-13.01-17 | S4 | both | `sink-connector/python/db_load/mysql_parser/CreateTableMySQLParserListener.py:148-154,189-245` | reproduced (R8: `AttributeError` on window `PARTITION BY`; RANGE COLUMNS ignored; `exitAlterList` never fires) | Listener hooks bound to a different grammar: window-clause hook crashes, ALTER hook is dead and calls an undefined method. |
| D-13.01-18 | S4 | both | `sink-connector/python/README.md:9-54`, `sink-connector/python/TESTING.md`, `sink-connector/python/ch_sink_tools/db_load/postgres_parser/README.md:1,30,98`, `postgres_parser.py:1,11`, `pyproject.toml:36` | reproduced (R15: both quick-start commands rejected by argparse) | README, TESTING.md and parser docs drift from the build (versions, flags, extras, paths, consumers). |
| D-13.01-19 | S4 | packaged | `sink-connector/python/ch_sink_tools/__init__.py:5`, `sink-connector/python/pyproject.toml:7`, `sink-connector/python/README.md:9` | code-read | Three different version strings (0.2.0 / 0.3.0 / 0.2.0). |
| D-13.01-20 | S4 | packaged | `sink-connector/python/pyproject.toml:2,11` | code-read (PEP 639 SPDX `license` string requires setuptools ≥ 77; the build-system floor is 64). Not reproducible offline: only setuptools 84 was available, and it builds. | Build-system floor admits setuptools versions that reject the metadata. |
| D-13.01-21 | S4 | packaged | `sink-connector/python/build_wheel.sh:27,38` | reproduced (R1: the regex matches only `ch_sink_tools/__init__.py`) | "Verify wheel contents" verifies nothing; `build` is not declared anywhere. |
| D-13.01-22 | S4 | legacy | `sink-connector/python/install.sh:1-4` | code-read | No shebang, bash-only `source`, venv activation and `PYTHONPATH` lost when run as a script. |
| D-13.01-23 | S4 | both | `sink-connector/python/db_load/mysql_parser/mysql_parser.py:17,26,48` (same in packaged) | reproduced (R8: duplicate `line 1:34 no viable alternative` on stderr; R4: `--help` → `FileNotFoundError`) | Default ANTLR console listener not removed, parser message dropped from the exception, debug `main` has no argument handling. |
| D-13.01-24 | S4 | both | `.github/workflows/spec-governance.yml:84-88`, `.github/workflows/docker-build.yml` | code-read | No CI builds the wheel, imports the console scripts, checks the Python floor, regenerates grammars or builds the Python Dockerfiles (extends FM-11.05-3). |

What could not be determined offline:

- whether `Dockerfile_db_load` builds against today's `mysql:latest`;
- the setuptools 64-76 behaviour (D-13.01-20);
- ClickHouse's actual handling of the non-Nullable `input()` column of FM-13.01-16;
- full grammar regeneration (no ANTLR jar or JDK available offline).
