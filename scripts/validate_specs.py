#!/usr/bin/env python3
"""Spec-Driven Development Validator for ClickHouse Sink Connector.

Checks, in order (each pass appends to one error list; exit code 1 if any):

1. Governance documents exist (CONSTITUTION.md, README.md, SMART_RALPH_PROTOCOL.md)
   and CONSTITUTION.md defines every invariant heading I1..I14.
2. Every domain directory exists and every micro-spec carries the required
   sections (Executive Summary, Codebase Mapping, Invariants, Verification).
3. Formal verification suite:
   - lean-toolchain, lakefile.lean and README.md exist;
   - every module listed in the lakefile `roots` exists on disk, and every
     .lean file under Replication/ is listed in the roots (a module that is not
     a root is never built, so its proofs are never checked);
   - no .lean file anywhere under formal_specs/lean contains `sorry`, `admit`
     or `native_decide` (the "machine-checked" claim must stay honest);
   - with --lake, `lake build` is run and must exit 0.
4. Code <-> spec mapping: every backticked repo-relative path (and every
   backticked `com.altinity...` class name) inside a spec's "Codebase Mapping"
   section must exist in the tree.
5. Cited tests exist: every backticked `SomethingTest` / `SomethingIT`
   (optionally `.method()`) inside a spec's "Verification" section must name an
   existing class under sink-connector/src/test or
   sink-connector-lightweight/src/test, and the method (when given) must be
   declared in it. Backticked `Replication.Module.name` references must name a
   theorem/def in that Lean module.
6. Agent guidance files exist and point at the spec-driven mandate.
7. With --changed-base <ref>: if `git diff --name-only <ref>...HEAD` touches a
   file under `*/src/main/**` or a `*.g4` grammar but nothing under `specs/` or
   `formal_specs/`, fail -- unless some commit message in `<ref>..HEAD` carries
   `[spec-exempt: <reason>]` (any commit in the range, so a pull_request run
   that checks out the synthetic merge commit still sees the marker).
8. Invariant I14 (spec 10.06, bounded bookkeeping): every run of Java string
   literals under `*/src/main/**` (adjacent literals joined by `+`, on one
   line or across lines, are read as one) that aggregates over a table
   (`SELECT max|min|count|sum|avg|uniq|any(`) or carries a per-query execution
   cap (`SETTINGS max_execution_time`) must either read a `system.*` table or
   be preceded, within six lines, by an `I14-scan-allowed: <spec reference>`
   comment. A connector's bookkeeping must never depend on a scan of the data
   it replicates; the marker makes every sanctioned read over a target table
   visible and reviewable.

Findings in passes 4 and 5 can be waived per spec file (or per spec file and
token) through scripts/spec_validator_allowlist.txt; waived findings are
printed as warnings so the debt stays visible.
"""

from __future__ import annotations

import argparse
import fnmatch
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

EXPECTED_DOMAINS = [
    "01-cdc-engine",
    "02-versioning",
    "03-execution-engine",
    "04-query-generation",
    "05-sorting-key-mutation",
    "06-ddl-replication",
    "07-type-system",
    "08-schema-catalog",
    "09-offset-management",
    "10-resilience-monitoring",
    "11-verification-tooling",
    "12-replication-history",
]

REQUIRED_SPEC_SECTIONS = [
    "Executive Summary",
    "Codebase Mapping",
    "Invariants",
    "Verification",
]

# Every invariant the Constitution must define (one heading each).
INVARIANT_COUNT = 14

# Test source trees searched by the cited-test check.
TEST_TREES = [
    "sink-connector/src/test",
    "sink-connector-lightweight/src/test",
]

# Main source trees searched when a spec cites a class by package name.
MAIN_TREES = [
    "sink-connector/src/main/java",
    "sink-connector-lightweight/src/main/java",
]

# Directories never scanned when resolving abbreviated ("...") paths.
SKIP_DIRS = {".git", ".lake", "target", "node_modules", "__pycache__", ".idea"}

FORBIDDEN_LEAN_TOKENS = ("sorry", "admit", "native_decide")

# Invariant I14 (spec 10.06): query shapes that read the replicated data rather
# than the connector's own bookkeeping tables. A `system.*` target is catalog
# metadata and exempt; anything else needs an `I14-scan-allowed` marker.
SCAN_SHAPES = [
    ("aggregate read", re.compile(r"\bSELECT\s+(?:max|min|count|sum|avg|uniq|any)\s*\(", re.IGNORECASE)),
    ("per-query execution cap", re.compile(r"\bSETTINGS\s+max_execution_time\b", re.IGNORECASE)),
]
SCAN_FROM_RE = re.compile(r"\bFROM\s+([^\s,)]+)", re.IGNORECASE)
SCAN_SYSTEM_TARGET_RE = re.compile(r"^`?system`?\.")
SCAN_MARKER_RE = re.compile(r"I14-scan-allowed:\s*\S")
SCAN_MARKER_WINDOW = 6
# A Java string literal (escapes allowed, no raw newline) and the glue that joins
# two literals into one run: whitespace and `+` only. `"SELECT " + "max(x)"`,
# also across lines, is ONE run; `"SELECT max(x) FROM " + table` ends the run
# at the variable, so the FROM target stays unknown and needs a marker.
JAVA_STRING_LITERAL_RE = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
JAVA_LITERAL_GLUE_RE = re.compile(r"\s*\+\s*")

SPEC_GOVERNED_PATTERNS = ("*/src/main/*", "*.g4")
SPEC_DIRS = ("specs/", "formal_specs/")
SPEC_EXEMPT_RE = re.compile(r"\[spec-exempt:\s*[^\]]+\]")
ALLOWLIST_COMMENT_RE = re.compile(r"(?:^|\s)#")

HEADING_RE = re.compile(r"^(#+)\s*(.*?)\s*$", re.MULTILINE)
BACKTICK_RE = re.compile(r"`([^`\n]+)`")
TEST_REF_RE = re.compile(
    r"^([A-Z][A-Za-z0-9_]*(?:Test|IT))(?:[.#]([A-Za-z_][A-Za-z0-9_]*)(?:\(\))?)?$"
)
LEAN_REF_RE = re.compile(r"^Replication\.([A-Z][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_']*)$")
FQCN_RE = re.compile(r"^com\.altinity(?:\.[a-z_][a-z0-9_]*)*\.([A-Z][A-Za-z0-9_]*)(?:\.[A-Z][A-Za-z0-9_]*)*$")
LAKE_ROOT_RE = re.compile(r"`Replication\.([A-Za-z0-9_]+)")
PATH_EXTENSIONS = (
    ".java", ".py", ".lean", ".yml", ".yaml", ".properties", ".g4", ".md",
    ".sh", ".xml", ".json", ".sql", ".txt",
)


@dataclass
class Finding:
    """One validation finding, attributable to a spec file and a token."""

    spec: str  # repo-relative spec path ("" for repo-level findings)
    token: str
    message: str


@dataclass
class Allowlist:
    files: set[str] = field(default_factory=set)
    tokens: set[tuple[str, str]] = field(default_factory=set)
    used: set[str] = field(default_factory=set)

    @classmethod
    def load(cls, path: Path) -> "Allowlist":
        allow = cls()
        if not path.is_file():
            return allow
        for raw in path.read_text(encoding="utf-8").splitlines():
            # A '#' starts a comment only at line start or after whitespace, so
            # a hash-form method token such as `FooTest#method` survives.
            line = ALLOWLIST_COMMENT_RE.split(raw, 1)[0].strip()
            if not line:
                continue
            if "|" in line:
                spec, token = (p.strip() for p in line.split("|", 1))
                allow.tokens.add((spec, token))
            else:
                allow.files.add(line)
        return allow

    def waives(self, finding: Finding) -> bool:
        if finding.spec in self.files:
            self.used.add(finding.spec)
            return True
        if (finding.spec, finding.token) in self.tokens:
            self.used.add(f"{finding.spec} | {finding.token}")
            return True
        return False

    def entries(self) -> list[str]:
        return sorted(self.files) + sorted(f"{s} | {t}" for s, t in self.tokens)


# --------------------------------------------------------------------------- #
# Markdown helpers
# --------------------------------------------------------------------------- #

def sections(content: str, title_pattern: str) -> list[str]:
    """Return the bodies of every section whose heading matches title_pattern.

    A section runs from its heading to the next heading of the same or a
    higher level, so sub-headings inside it are included.
    """
    headings = list(HEADING_RE.finditer(content))
    bodies: list[str] = []
    for idx, match in enumerate(headings):
        level = len(match.group(1))
        if not re.search(title_pattern, match.group(2), re.IGNORECASE):
            continue
        end = len(content)
        for later in headings[idx + 1:]:
            if len(later.group(1)) <= level:
                end = later.start()
                break
        bodies.append(content[match.end():end])
    return bodies


def backticked(text: str) -> list[str]:
    return [m.group(1).strip() for m in BACKTICK_RE.finditer(text)]


# --------------------------------------------------------------------------- #
# Repository index
# --------------------------------------------------------------------------- #

class RepoIndex:
    """Lazy file index used to resolve abbreviated paths and class names."""

    def __init__(self, root: Path):
        self.root = root
        self._files: list[str] | None = None
        self._test_classes: dict[str, list[Path]] | None = None

    def files(self) -> list[str]:
        if self._files is None:
            found: list[str] = []
            for dirpath, dirnames, filenames in os.walk(self.root):
                dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
                rel_dir = Path(dirpath).relative_to(self.root).as_posix()
                for name in filenames:
                    found.append(name if rel_dir == "." else f"{rel_dir}/{name}")
            self._files = found
        return self._files

    def find_suffix(self, suffix: str) -> list[str]:
        suffix = suffix.lstrip("/")
        return [f for f in self.files() if f == suffix or f.endswith("/" + suffix)]

    def test_classes(self) -> dict[str, list[Path]]:
        if self._test_classes is None:
            classes: dict[str, list[Path]] = {}
            for tree in TEST_TREES:
                base = self.root / tree
                if not base.is_dir():
                    continue
                for path in base.rglob("*.java"):
                    classes.setdefault(path.stem, []).append(path)
            self._test_classes = classes
        return self._test_classes

    def main_class_exists(self, fqcn: str) -> bool:
        match = FQCN_RE.match(fqcn)
        if not match:
            return False
        outer = match.group(1)
        prefix = fqcn.split("." + outer, 1)[0]
        rel = prefix.replace(".", "/") + "/" + outer + ".java"
        return any((self.root / tree / rel).is_file() for tree in MAIN_TREES)


# --------------------------------------------------------------------------- #
# Validation passes
# --------------------------------------------------------------------------- #

def check_governance(repo_root: Path) -> list[str]:
    errors: list[str] = []
    specs_dir = repo_root / "specs"
    constitution = specs_dir / "CONSTITUTION.md"
    for name in ("CONSTITUTION.md", "README.md", "SMART_RALPH_PROTOCOL.md"):
        if not (specs_dir / name).is_file():
            errors.append(f"Missing governance document: specs/{name}")
    if constitution.is_file():
        text = constitution.read_text(encoding="utf-8")
        for n in range(1, INVARIANT_COUNT + 1):
            if not re.search(rf"^#+\s*Invariant I{n}\b", text, re.MULTILINE):
                errors.append(f"specs/CONSTITUTION.md: missing heading for Invariant I{n}")
        if re.search(rf"^#+\s*Invariant I{INVARIANT_COUNT + 1}\b", text, re.MULTILINE):
            errors.append(
                f"specs/CONSTITUTION.md: defines Invariant I{INVARIANT_COUNT + 1} but the "
                f"validator's INVARIANT_COUNT is {INVARIANT_COUNT}; update both together"
            )
    return errors


def check_spec_schema(path: Path, repo_root: Path) -> list[str]:
    rel = path.relative_to(repo_root).as_posix()
    if not path.is_file():
        return [f"Missing specification file: {rel}"]
    content = path.read_text(encoding="utf-8")
    errors = []
    for sec in REQUIRED_SPEC_SECTIONS:
        if not re.search(rf"^#+\s*.*{sec}", content, re.IGNORECASE | re.MULTILINE):
            errors.append(f"{rel}: missing required section matching '{sec}'")
    return errors


def spec_files(repo_root: Path) -> tuple[list[Path], list[str]]:
    errors: list[str] = []
    found: list[Path] = []
    for domain in EXPECTED_DOMAINS:
        domain_dir = repo_root / "specs" / domain
        if not domain_dir.is_dir():
            errors.append(f"Missing domain directory: specs/{domain}")
            continue
        domain_specs = sorted(domain_dir.glob("*.md"))
        if not domain_specs:
            errors.append(f"No specifications found in domain: specs/{domain}")
            continue
        found.extend(domain_specs)
    return found, errors


def lakefile_roots(lakefile: Path) -> list[str]:
    return LAKE_ROOT_RE.findall(lakefile.read_text(encoding="utf-8"))


def check_lean(repo_root: Path) -> list[str]:
    errors: list[str] = []
    lean_dir = repo_root / "formal_specs" / "lean"
    rep_dir = lean_dir / "Replication"
    for name in ("lean-toolchain", "lakefile.lean", "README.md"):
        if not (lean_dir / name).is_file():
            errors.append(f"Missing Lean configuration file: formal_specs/lean/{name}")

    lakefile = lean_dir / "lakefile.lean"
    if lakefile.is_file():
        roots = lakefile_roots(lakefile)
        if not roots:
            errors.append("formal_specs/lean/lakefile.lean: no `Replication.*` roots declared")
        for mod in roots:
            if not (rep_dir / f"{mod}.lean").is_file():
                errors.append(
                    f"formal_specs/lean/lakefile.lean lists root Replication.{mod} "
                    f"but Replication/{mod}.lean does not exist"
                )
        if rep_dir.is_dir():
            for path in sorted(rep_dir.glob("*.lean")):
                if path.stem not in roots:
                    errors.append(
                        f"formal_specs/lean/Replication/{path.name} is not listed in the "
                        f"lakefile roots, so `lake build` never checks it"
                    )

    if lean_dir.is_dir():
        for path in sorted(lean_dir.rglob("*.lean")):
            if any(part in SKIP_DIRS for part in path.relative_to(lean_dir).parts):
                continue
            text = path.read_text(encoding="utf-8")
            for token in FORBIDDEN_LEAN_TOKENS:
                if re.search(rf"\b{token}\b", text):
                    errors.append(
                        f"{path.relative_to(repo_root).as_posix()}: contains '{token}' "
                        f"-- proof is incomplete or unchecked"
                    )
    return errors


def run_lake_build(repo_root: Path) -> list[str]:
    lean_dir = repo_root / "formal_specs" / "lean"
    try:
        proc = subprocess.run(
            ["lake", "build"], cwd=lean_dir, capture_output=True, text=True, check=False
        )
    except FileNotFoundError:
        return ["--lake requested but `lake` is not on PATH"]
    if proc.returncode != 0:
        tail = (proc.stdout + proc.stderr).strip().splitlines()[-20:]
        return ["`lake build` failed in formal_specs/lean:\n    " + "\n    ".join(tail)]
    return []


def _strip_locator(token: str) -> str:
    """Drop `#member`, `:line` and `:line-line` suffixes from a path token."""
    token = token.split("#", 1)[0]
    return re.sub(r":\d+(?:-\d+)?$", "", token)


def is_path_like(token: str) -> bool:
    if "/" not in token or any(ch.isspace() for ch in token):
        return False
    if token.startswith(("http://", "https://")) or "(" in token or "$" in token:
        return False
    return True


def resolve_path_token(token: str, index: RepoIndex) -> str | None:
    """Return an error message if the path token does not resolve, else None."""
    cleaned = _strip_locator(token)
    if "..." in cleaned:
        suffix = cleaned.rsplit("...", 1)[1]
        if not suffix.strip("/"):
            return None
        if not index.find_suffix(suffix):
            return f"no file in the tree ends with '{suffix.lstrip('/')}'"
        return None
    if any(ch in cleaned for ch in "*?["):
        if not list(index.root.glob(cleaned)):
            return f"glob '{cleaned}' matches nothing"
        return None
    target = index.root / cleaned
    if cleaned.endswith("/"):
        return None if target.is_dir() else f"directory '{cleaned}' does not exist"
    if target.exists():
        return None
    if cleaned.endswith(PATH_EXTENSIONS) or cleaned.split("/", 1)[0] in {
        "sink-connector", "sink-connector-lightweight", "sink-connector-client",
        "formal_specs", "specs", "scripts", "doc", "deploy", ".github",
    }:
        return f"path '{cleaned}' does not exist"
    return None


def check_codebase_mapping(spec: Path, repo_root: Path, index: RepoIndex) -> list[Finding]:
    rel = spec.relative_to(repo_root).as_posix()
    content = spec.read_text(encoding="utf-8")
    findings: list[Finding] = []
    seen: set[str] = set()
    for body in sections(content, r"Codebase Mapping"):
        for token in backticked(body):
            if token in seen:
                continue
            seen.add(token)
            if is_path_like(token):
                problem = resolve_path_token(token, index)
                if problem:
                    findings.append(Finding(rel, token, f"{rel}: Codebase Mapping cites `{token}` but {problem}"))
            elif FQCN_RE.match(token.split("#", 1)[0]):
                if not index.main_class_exists(token.split("#", 1)[0]):
                    findings.append(Finding(rel, token, f"{rel}: Codebase Mapping cites class `{token}` which does not exist under {' or '.join(MAIN_TREES)}"))
    return findings


def _method_declared(java_files: list[Path], method: str) -> bool:
    pattern = re.compile(rf"\b(?:void|[A-Za-z_][\w<>\[\], ]*)\s+{re.escape(method)}\s*\(")
    return any(pattern.search(p.read_text(encoding="utf-8", errors="replace")) for p in java_files)


def _lean_declares(repo_root: Path, module: str, name: str) -> bool | None:
    path = repo_root / "formal_specs" / "lean" / "Replication" / f"{module}.lean"
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8")
    return re.search(rf"^\s*(?:theorem|def|lemma|abbrev|structure|inductive)\s+{re.escape(name)}\b", text, re.MULTILINE) is not None


def check_verification_refs(spec: Path, repo_root: Path, index: RepoIndex) -> tuple[list[Finding], list[tuple[str, str, str]]]:
    """Return (findings, refs) where refs are (spec, token, status) triples."""
    rel = spec.relative_to(repo_root).as_posix()
    content = spec.read_text(encoding="utf-8")
    findings: list[Finding] = []
    refs: list[tuple[str, str, str]] = []
    seen: set[str] = set()
    for body in sections(content, r"Verification"):
        for token in backticked(body):
            if token in seen:
                continue
            seen.add(token)
            test_match = TEST_REF_RE.match(token)
            lean_match = LEAN_REF_RE.match(token)
            if test_match:
                cls, method = test_match.group(1), test_match.group(2)
                files = index.test_classes().get(cls)
                if not files:
                    status = "missing class"
                    findings.append(Finding(rel, token, f"{rel}: Verification cites `{token}` but no {cls}.java exists under {' or '.join(TEST_TREES)}"))
                elif method and not _method_declared(files, method):
                    status = "missing method"
                    findings.append(Finding(rel, token, f"{rel}: Verification cites `{token}` but {cls} declares no method {method}()"))
                else:
                    status = "ok"
                refs.append((rel, token, status))
            elif lean_match:
                declared = _lean_declares(repo_root, lean_match.group(1), lean_match.group(2))
                if declared is None:
                    status = "missing module"
                    findings.append(Finding(rel, token, f"{rel}: Verification cites `{token}` but Replication/{lean_match.group(1)}.lean does not exist"))
                elif not declared:
                    status = "missing declaration"
                    findings.append(Finding(rel, token, f"{rel}: Verification cites `{token}` but Replication/{lean_match.group(1)}.lean declares no {lean_match.group(2)}"))
                else:
                    status = "ok"
                refs.append((rel, token, status))
    return findings, refs


def check_guidance(repo_root: Path) -> list[str]:
    errors: list[str] = []
    guides = [repo_root / "AGENTS.md", repo_root / "CLAUDE.md", repo_root / ".github" / "copilot-instructions.md"]
    for guide in guides:
        if not guide.is_file():
            errors.append(f"Missing guidance file: {guide.relative_to(repo_root).as_posix()}")
    agents_md = guides[0]
    if agents_md.is_file():
        text = agents_md.read_text(encoding="utf-8")
        if "specs/" not in text or "Smart Ralph" not in text:
            errors.append("AGENTS.md: missing reference to 'specs/' or 'Smart Ralph' spec-driven protocol")
    return errors


def java_string_literal_runs(text: str) -> list[tuple[int, str]]:
    """Every run of Java string literals in `text` as (1-based line of its first
    literal, concatenated content). Adjacent literals joined only by whitespace
    and `+` -- on one line or across lines -- form one run, so a query split as
    `"SELECT " + "max(_version) FROM t"` is seen whole. Anything else between two
    literals (a variable, a method call, a comma) ends the run."""
    runs: list[list] = []
    last_end = -1
    for m in JAVA_STRING_LITERAL_RE.finditer(text):
        if runs and JAVA_LITERAL_GLUE_RE.fullmatch(text[last_end:m.start()]):
            runs[-1][1] += m.group(1)
        else:
            runs.append([text.count("\n", 0, m.start()) + 1, m.group(1)])
        last_end = m.end()
    return [(line, content) for line, content in runs]


def check_bookkeeping_scans(repo_root: Path) -> list[str]:
    """Invariant I14: no unmarked aggregate read over a replicated table in main code."""
    errors: list[str] = []
    for tree in MAIN_TREES:
        base = repo_root / tree
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.java")):
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            lines = text.splitlines()
            rel = path.relative_to(repo_root).as_posix()
            for line_no, content in java_string_literal_runs(text):
                for shape_name, shape in SCAN_SHAPES:
                    if not shape.search(content):
                        continue
                    if shape_name == "aggregate read":
                        m = SCAN_FROM_RE.search(content)
                        if m and SCAN_SYSTEM_TARGET_RE.match(m.group(1)):
                            continue
                    context = "\n".join(lines[max(0, line_no - 1 - SCAN_MARKER_WINDOW):line_no - 1])
                    if SCAN_MARKER_RE.search(context):
                        continue
                    errors.append(
                        f"{rel}:{line_no}: {shape_name} over a non-system table without an "
                        f"`I14-scan-allowed: <spec reference>` comment within {SCAN_MARKER_WINDOW} preceding lines "
                        f"(Invariant I14, spec 10.06): {content.strip()[:140]}"
                    )
    return errors


def _git(repo_root: Path, *args: str) -> str:
    proc = subprocess.run(["git", "-C", str(repo_root), *args], capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout


def is_spec_governed(path: str) -> bool:
    return any(fnmatch.fnmatch(path, pat) for pat in SPEC_GOVERNED_PATTERNS)


def is_spec_change(path: str) -> bool:
    return path.startswith(SPEC_DIRS)


def check_changed_base(repo_root: Path, base: str) -> list[str]:
    try:
        changed = [p for p in _git(repo_root, "diff", "--name-only", f"{base}...HEAD").splitlines() if p.strip()]
        # Every commit message in the range, not only HEAD's: on a pull_request
        # run HEAD may be the synthetic merge commit, whose auto-generated
        # message never carries the marker even when the PR's own commit does.
        range_messages = _git(repo_root, "log", "--format=%B%x00", f"{base}..HEAD")
    except RuntimeError as exc:
        return [f"--changed-base: {exc}"]
    governed = sorted(p for p in changed if is_spec_governed(p))
    if not governed:
        return []
    if any(is_spec_change(p) for p in changed):
        return []
    exempt = SPEC_EXEMPT_RE.search(range_messages)
    if exempt:
        print(f"  ! spec-governed files changed without a spec change, exempted by a commit in {base}..HEAD: {exempt.group(0)}")
        return []
    listing = "\n    ".join(governed)
    return [
        "spec-governed source changed but no file under specs/ or formal_specs/ changed "
        f"(diff {base}...HEAD). Declare the spec first (AGENTS.md: Spec First, Code Second) "
        f"or mark a commit in {base}..HEAD with `[spec-exempt: <reason>]`. Files:\n    " + listing
    ]


# --------------------------------------------------------------------------- #
# Driver
# --------------------------------------------------------------------------- #

@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    refs: list[tuple[str, str, str]] = field(default_factory=list)
    spec_count: int = 0


def validate(repo_root: Path, allowlist: Allowlist, changed_base: str | None = None,
             run_lake: bool = False, quiet: bool = False) -> Report:
    report = Report()
    index = RepoIndex(repo_root)

    def say(msg: str) -> None:
        if not quiet:
            print(msg)

    say("[1/8] Governance documents and Constitution invariants I1..I%d" % INVARIANT_COUNT)
    report.errors += check_governance(repo_root)

    say("[2/8] Domain micro-specification schema")
    specs, errs = spec_files(repo_root)
    report.errors += errs
    for spec in specs:
        report.spec_count += 1
        report.errors += check_spec_schema(spec, repo_root)

    say("[3/8] Lean 4 suite: roots, modules, forbidden tokens" + (", lake build" if run_lake else ""))
    report.errors += check_lean(repo_root)
    if run_lake:
        report.errors += run_lake_build(repo_root)

    say("[4/8] Codebase Mapping paths and classes resolve against the tree")
    say("[5/8] Verification citations name existing tests / Lean declarations")
    for spec in specs:
        findings = check_codebase_mapping(spec, repo_root, index)
        v_findings, refs = check_verification_refs(spec, repo_root, index)
        report.refs += refs
        for finding in findings + v_findings:
            if allowlist.waives(finding):
                report.warnings.append("(allowlisted) " + finding.message)
            else:
                report.errors.append(finding.message)

    for entry in allowlist.entries():
        if entry not in allowlist.used:
            report.warnings.append(f"allowlist entry no longer needed: {entry}")

    say("[6/8] Agent guidance files")
    report.errors += check_guidance(repo_root)

    if changed_base:
        say(f"[7/8] Spec-first gate for changes since {changed_base}")
        report.errors += check_changed_base(repo_root, changed_base)
    else:
        say("[7/8] Spec-first gate skipped (no --changed-base)")

    say("[8/8] Invariant I14: aggregate reads over replicated tables carry an I14-scan-allowed marker")
    report.errors += check_bookkeeping_scans(repo_root)
    return report


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate the spec-driven repository state.")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: parent of scripts/)")
    parser.add_argument("--allowlist", type=Path, default=None,
                        help="allowlist file (default: scripts/spec_validator_allowlist.txt under the repo root)")
    parser.add_argument("--changed-base", metavar="REF", default=None,
                        help="fail if `git diff --name-only REF...HEAD` changes */src/main/** or *.g4 without a specs/ or formal_specs/ change, unless a commit in REF..HEAD carries [spec-exempt: <reason>]")
    parser.add_argument("--lake", action="store_true", help="also run `lake build` in formal_specs/lean")
    parser.add_argument("--report-refs", action="store_true",
                        help="print every test / Lean citation found in Verification sections with its status")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    repo_root = args.repo_root.resolve()
    allowlist_path = args.allowlist or (repo_root / "scripts" / "spec_validator_allowlist.txt")
    allowlist = Allowlist.load(allowlist_path)

    print("=====================================================================")
    print("      CLICKHOUSE SINK CONNECTOR: SPEC-DRIVEN REPO VALIDATOR          ")
    print("=====================================================================")
    print(f"Repository Root: {repo_root}")

    report = validate(repo_root, allowlist, changed_base=args.changed_base, run_lake=args.lake)

    if args.report_refs:
        print("\nVerification citations:")
        for spec, token, status in report.refs:
            print(f"  [{status:>19}] {spec}: {token}")

    print("\n---------------------------------------------------------------------")
    print(f"Specifications validated: {report.spec_count}")
    print(f"Verification citations checked: {len(report.refs)}")
    if report.warnings:
        print(f"Warnings ({len(report.warnings)}):")
        for warn in report.warnings:
            print(f"  ! {warn}")
    if report.errors:
        print(f"Validation FAILED with {len(report.errors)} error(s):")
        for err in report.errors:
            print(f"  - {err}")
        return 1
    print("Validation PASSED.")
    print("=====================================================================")
    return 0


if __name__ == "__main__":
    sys.exit(main())
