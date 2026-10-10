#!/usr/bin/env python3
"""Deterministic review gates for pull requests (Spec 11.06).

Runs over the lines a change ADDS between a base ref and a head ref and
reports findings as ``path:line: [gate] message``. No network, no LLM,
standard library only (PyYAML is used for the YAML syntax check when it is
installed and skipped with a notice when it is not).

Gates
-----
destructive  Every added line that irreversibly removes data or objects
             (DROP TABLE/DATABASE/PARTITION/COLUMN/..., TRUNCATE, DELETE FROM,
             ALTER TABLE ... DELETE, DETACH PARTITION, SYSTEM DROP REPLICA,
             recursive+force rm) must carry a ``DESTRUCTIVE:`` comment of at
             least 20 characters within 5 lines, and the newest commit in the
             range that carries a ``Destructive-Op-Check: sites=<N>;
             result=pass`` trailer must attest exactly the number of sites in
             the change.
merge-stop   No added code may stop or pause ClickHouse merges: the SYSTEM
             statement that stops (TTL) merges, a statement template whose
             verb is interpolated, or a helper named after pausing merges
             (patterns below). Copies are verified by value-level
             checksums, not by freezing the part layout; a table whose merges
             are stopped piles up parts until inserts are rejected, and the
             stop aborts every in-flight merge. ``SYSTEM START MERGES`` is
             always allowed: it is the recovery action.
license      A dependency added to a Maven POM at compile/runtime scope (or
             with no scope) must not be a known GPL/LGPL/AGPL (ASF Category X)
             artifact or a test framework, and must be listed in
             THIRD_PARTY_NOTICES.md. Python requirement additions must not be
             known Category X packages.
hygiene      No merge-conflict markers, no swallowed exceptions (empty Java
             catch block, Python ``except ...: pass``, bare ``except:``), no
             debugger leftovers, no private keys or well-known token formats,
             and every changed YAML/JSON file must still parse.

Exit codes: 0 no findings, 1 findings, 2 usage or git error.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

GATE_NAMES = ("destructive", "merge-stop", "license", "hygiene")

# Files that implement or test this tool quote every pattern it detects.
SELF_PATHS = frozenset({
    "scripts/review_gates.py",
    "scripts/tests/test_review_gates.py",
})

PROSE_SUFFIXES = (".md", ".rst", ".txt", ".adoc", ".markdown")
# Grammars and proofs describe syntax and models; they execute nothing.
NON_EXECUTING_SUFFIXES = (".g4", ".lean")
PROSE_DIRS = ("doc/", "specs/", "formal_specs/", "release-notes/", ".claude/")
TEST_SEGMENTS = frozenset({"test", "tests", "__tests__", "testflows", "testdata"})
TEST_BASENAME_RE = re.compile(
    r"^(?:test_.*|.*_test\.[^.]+|conftest\.py|.*Tests?\.(?:java|kt|groovy)|.*IT\.java)$"
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    gate: str
    message: str

    def render(self) -> str:
        return f"{self.path}:{self.line}: [{self.gate}] {self.message}"


class GitError(RuntimeError):
    pass


# --------------------------------------------------------------------------
# git plumbing
# --------------------------------------------------------------------------

def git(repo: str, *args: str) -> str:
    proc = subprocess.run(
        ["git", "-C", repo, *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )
    if proc.returncode != 0:
        raise GitError(f"git {' '.join(args)} failed: {proc.stderr.strip()}")
    return proc.stdout


def changed_diff(repo: str, base: str, head: str) -> str:
    return git(repo, "diff", "--no-color", "--no-ext-diff", "--no-renames",
               "-U0", f"{base}...{head}")


def show_file(repo: str, ref: str, path: str) -> Optional[str]:
    proc = subprocess.run(
        ["git", "-C", repo, "show", f"{ref}:{path}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )
    if proc.returncode != 0:
        return None
    return proc.stdout


def merge_base(repo: str, base: str, head: str) -> str:
    return git(repo, "merge-base", base, head).strip()


def range_messages(repo: str, base: str, head: str) -> List[str]:
    """Commit messages in base..head, newest first."""
    out = git(repo, "log", "--format=%B%x00", f"{base}..{head}")
    return [m.strip() for m in out.split("\x00") if m.strip()]


HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def parse_added_lines(diff: str) -> Dict[str, List[Tuple[int, str]]]:
    """``git diff -U0`` -> {path: [(new line number, text)]} for added lines."""
    added: Dict[str, List[Tuple[int, str]]] = {}
    path: Optional[str] = None
    lineno = 0
    for raw in diff.splitlines():
        if raw.startswith("+++ "):
            target = raw[4:]
            path = None if target == "/dev/null" else target[2:] if target.startswith("b/") else target
            continue
        if raw.startswith("--- ") or raw.startswith("diff --git"):
            continue
        m = HUNK_RE.match(raw)
        if m:
            lineno = int(m.group(1))
            continue
        if path is None:
            continue
        if raw.startswith("+"):
            added.setdefault(path, []).append((lineno, raw[1:]))
            lineno += 1
        elif raw.startswith(" "):
            lineno += 1
    return added


# --------------------------------------------------------------------------
# path classification
# --------------------------------------------------------------------------

def is_prose(path: str) -> bool:
    return path.endswith(PROSE_SUFFIXES) or path.startswith(PROSE_DIRS)


def is_test(path: str) -> bool:
    p = PurePosixPath(path)
    if any(part in TEST_SEGMENTS for part in p.parts[:-1]):
        return True
    return bool(TEST_BASENAME_RE.match(p.name))


def exempt_from_code_gates(path: str) -> bool:
    return (path in SELF_PATHS or is_prose(path) or is_test(path)
            or path.endswith(NON_EXECUTING_SUFFIXES))


# --------------------------------------------------------------------------
# destructive gate
# --------------------------------------------------------------------------

DESTRUCTIVE_PATTERNS = (
    re.compile(
        r"\bDROP\s+(?:(?:MATERIALIZED|TEMPORARY|LIVE|WINDOW)\s+)?"
        r"(?:TABLE|DATABASE|PARTITION|VIEW|DICTIONARY|INDEX|COLUMN|PART|DETACHED)\b",
        re.IGNORECASE,
    ),
    re.compile(r"\bTRUNCATE\s+(?:TABLE\s+)?\S", re.IGNORECASE),
    re.compile(r"\bDELETE\s+FROM\b", re.IGNORECASE),
    re.compile(r"\bALTER\s+TABLE\b.*\bDELETE\b", re.IGNORECASE),
    re.compile(r"\bDELETE\s+WHERE\b", re.IGNORECASE),
    re.compile(r"\bDETACH\s+PARTITION\b", re.IGNORECASE),
    re.compile(r"\bSYSTEM\s+DROP\s+REPLICA\b", re.IGNORECASE),
)
WARNING_MARKER = "DESTRUCTIVE:"
WARNING_WINDOW = 5
MIN_WARNING_TEXT = 20
COMMENT_PREFIXES = ("#", "//", "<!--", "*", "/*", ";", "rem ")
COMMENT_STARTERS = ("#", "//", "--", "/*", "<!--", ";", "*")
TRAILER_RE = re.compile(r"^Destructive-Op-Check:\s*(.+)$", re.IGNORECASE | re.MULTILINE)
RM_WORD_RE = re.compile(r"\brm\b")
RM_RECURSIVE_RE = re.compile(r"^-\w*r\w*$|^--recursive$", re.IGNORECASE)
RM_FORCE_RE = re.compile(r"^-\w*f\w*$|^--force$", re.IGNORECASE)
STRING_LITERAL_RE = re.compile(r"\"(?:[^\"\\]|\\.)*\"|'(?:[^'\\]|\\.)*'")


def is_comment_only(line: str) -> bool:
    stripped = line.lstrip()
    if stripped.startswith("--"):
        rest = stripped[2:]
        # "--word" is a shell long option; an SQL comment is "-- ".
        return rest == "" or rest[0] in (" ", "\t", "-")
    return stripped.lower().startswith(COMMENT_PREFIXES)


def is_rm_recursive_force(line: str) -> bool:
    m = RM_WORD_RE.search(line)
    if not m:
        return False
    tokens = [t.replace('"', "").replace("'", "") for t in line[m.end():].split()]
    return any(RM_RECURSIVE_RE.match(t) for t in tokens) and any(RM_FORCE_RE.match(t) for t in tokens)


def is_destructive_line(line: str) -> bool:
    if is_comment_only(line):
        return False
    candidates = [line]
    no_block = re.sub(r"/\*.*?\*/", " ", line)
    unquoted = line.replace('"', "").replace("'", "").replace("\\", "")
    candidates += [no_block, unquoted, re.sub(r"\s*\+\s*", " ", unquoted)]
    for text in candidates:
        if is_rm_recursive_force(text) or any(p.search(text) for p in DESTRUCTIVE_PATTERNS):
            return True
    return False


def window_has_warning(window: Sequence[str]) -> bool:
    for wl in window:
        no_strings = STRING_LITERAL_RE.sub("", wl)
        at = no_strings.find(WARNING_MARKER)
        if at < 0:
            continue
        before = no_strings[:at]
        if not (is_comment_only(no_strings) or any(s in before for s in COMMENT_STARTERS)):
            continue
        if len(no_strings[at + len(WARNING_MARKER):].strip()) >= MIN_WARNING_TEXT:
            return True
    return False


def parse_trailer(message: str) -> Optional[Dict[str, str]]:
    m = TRAILER_RE.search(message)
    if not m:
        return None
    fields: Dict[str, str] = {}
    for chunk in re.split(r";", m.group(1)):
        if "=" in chunk:
            key, _, value = chunk.partition("=")
            fields[key.strip().lower()] = value.strip()
    return fields


def check_destructive(added: Dict[str, List[Tuple[int, str]]],
                      head_text, messages: Sequence[str]) -> List[Finding]:
    findings: List[Finding] = []
    sites: List[Tuple[str, int]] = []
    for path, lines in sorted(added.items()):
        if exempt_from_code_gates(path):
            continue
        content = None
        for lineno, text in lines:
            if not is_destructive_line(text):
                continue
            sites.append((path, lineno))
            if content is None:
                content = (head_text(path) or "").splitlines()
            lo = max(0, lineno - 1 - WARNING_WINDOW)
            window = content[lo:lineno + WARNING_WINDOW]
            if not window_has_warning(window):
                findings.append(Finding(
                    path, lineno, "destructive",
                    f"destructive operation without a '{WARNING_MARKER}' comment "
                    f"(>= {MIN_WARNING_TEXT} chars: what is destroyed and how the "
                    f"blast radius is bounded) within {WARNING_WINDOW} lines",
                ))
    if not sites:
        return findings
    trailer = None
    for msg in messages:  # newest first
        trailer = parse_trailer(msg)
        if trailer is not None:
            break
    first_path, first_line = sites[0]
    expected = f"Destructive-Op-Check: sites={len(sites)}; result=pass"
    if trailer is None:
        findings.append(Finding(first_path, first_line, "destructive",
                                f"{len(sites)} destructive site(s) but no commit in the range "
                                f"carries the trailer '{expected}'"))
    else:
        sites_value = trailer.get("sites", "")
        result_value = trailer.get("result", "").lower()
        if result_value not in ("pass", "passed"):
            findings.append(Finding(first_path, first_line, "destructive",
                                    f"Destructive-Op-Check trailer has result={result_value or '<missing>'}; "
                                    "every site must be reviewed and warned (result=pass)"))
        if not sites_value.isdigit() or int(sites_value) != len(sites):
            findings.append(Finding(first_path, first_line, "destructive",
                                    f"Destructive-Op-Check trailer attests sites={sites_value or '<missing>'} "
                                    f"but the change adds {len(sites)} destructive site(s); expected '{expected}'"))
    return findings


# --------------------------------------------------------------------------
# merge-stop gate
# --------------------------------------------------------------------------

STOP_MERGES_SQL_RE = re.compile(r"\bSYSTEM\s+STOP\s+(?:TTL\s+)?MERGES\b", re.IGNORECASE)
INTERP = (
    r"\{\{?[^{}\n]*\}\}?|%s|%\([^)\n]*\)s|\$\{[^}\n]*\}|\$\([^()\n]*\)"
    r"|`[^`\n]+`|\$\w+|<[^<>\n]+>|[\"']\s*\+\s*[\w.\[\]()]+\s*\+\s*[\"']"
)
TEMPLATED_MERGES_RE = re.compile(
    r"\bSYSTEM\s+[^\s(]*?(?:" + INTERP + r")[^\s]*?\s+(?:TTL\s+)?MERGES\b"
    r"|\bSYSTEM[\"']\s*,\s*[\w.\[\]()]+\s*,\s*[\"'](?:TTL[\"']\s*,\s*[\"'])?MERGES\b",
    re.IGNORECASE,
)
EMPTY_VERB_RE = re.compile(r"\bSYSTEM\s+(?:TTL\s+)?MERGES\b", re.IGNORECASE)
STOP_MERGES_IDENT_RE = re.compile(
    r"(?i:\b(?:stop|pause|disable|halt|suspend|freeze)_(?:ttl_)?merges\b"
    r"|(?:^|[\s\"'=(`])(?:\./|/(?:[\w.-]+/)*|--)(?:stop|pause|disable|halt|suspend|freeze)-(?:ttl-)?merges\b"
    r"|\b(?:stop|pause|disable|halt|suspend|freeze)-(?:ttl-)?merges"
    r"(?:\.(?:sh|bash|py|yml|yaml|sql|j2|groovy)\b|\s*\())"
    r"|\b(?:stop|pause|disable|halt|suspend|freeze)(?:Ttl)?Merges\b",
)
SQL_COMMENT_RE = re.compile(r"/\*.*?\*/|--\s[^\n]*|--$", re.DOTALL | re.MULTILINE)
LITERAL_SPLICE_RE = re.compile(r"[\"']\s*(?:\+|\\|,|\.)?\s*[\"']")


def _merge_stop_hit(text: str) -> bool:
    if STOP_MERGES_SQL_RE.search(text) or TEMPLATED_MERGES_RE.search(text):
        return True
    if STOP_MERGES_IDENT_RE.search(text):
        return True
    spliced = LITERAL_SPLICE_RE.sub("", SQL_COMMENT_RE.sub(" ", text))
    if STOP_MERGES_SQL_RE.search(spliced):
        return True
    detemplated = re.sub(INTERP, " ", text)
    return bool(EMPTY_VERB_RE.search(detemplated))


def check_merge_stop(added: Dict[str, List[Tuple[int, str]]]) -> List[Finding]:
    findings: List[Finding] = []
    for path, lines in sorted(added.items()):
        if exempt_from_code_gates(path):
            continue
        reported = set()
        # single lines, then runs of up to 4 consecutive added lines so a
        # statement split across lines is still seen whole
        for i, (lineno, text) in enumerate(lines):
            if lineno in reported:
                continue
            joined = text
            hit = _merge_stop_hit(text)
            j = i
            while not hit and j + 1 < len(lines) and j - i < 3 and lines[j + 1][0] == lines[j][0] + 1:
                j += 1
                joined += " " + lines[j][1].strip()
                hit = _merge_stop_hit(joined)
            if hit:
                reported.update(n for n, _ in lines[i:j + 1])
                findings.append(Finding(
                    path, lineno, "merge-stop",
                    "code stops or pauses ClickHouse merges; verify copies with value-level "
                    "checksums instead (SYSTEM START MERGES alone is allowed)",
                ))
    return findings


# --------------------------------------------------------------------------
# license gate
# --------------------------------------------------------------------------

# ASF Category X artifacts (and test frameworks) that must never reach the
# compile or runtime scope. Kept in sync with doc/licensing.md.
BANNED_MAVEN = (
    ("com.mysql", "mysql-connector-j", "GPL-2.0 with the Universal FOSS Exception"),
    ("mysql", "mysql-connector-java", "GPL-2.0 with the FOSS Exception"),
    ("org.mariadb.jdbc", "*", "LGPL-2.1"),
    ("junit", "junit", "a test framework (CPL-1.0)"),
    ("org.junit.jupiter", "*", "a test framework"),
    ("org.testcontainers", "*", "a test framework"),
    ("net.sourceforge.jtds", "jtds", "LGPL-2.1"),
    ("org.hibernate", "*", "LGPL-2.1"),
)
BANNED_PYTHON = {
    "psycopg2": "LGPL-3.0 (use pg8000)",
    "psycopg2-binary": "LGPL-3.0 (use pg8000)",
    "mysql-connector-python": "GPL-2.0 (use PyMySQL)",
    "mysqlclient": "GPL-2.0 (use PyMySQL)",
    "mysql-python": "GPL-2.0 (use PyMySQL)",
    "pyqt5": "GPL-3.0",
    "pyqt6": "GPL-3.0",
}
RUNTIME_SCOPES = ("", "compile", "runtime")
PY_REQ_FILE_RE = re.compile(r"(?:^|/)(?:requirements[^/]*\.txt|pyproject\.toml|setup\.cfg|setup\.py)$")
PY_REQ_NAME_RE = re.compile(r"""^\s*["']?([A-Za-z0-9][A-Za-z0-9._-]*)\s*(?:\[[^\]]*\])?\s*(?:[<>=!~;,"'\s]|$)""")


def _strip_ns(tag: str) -> str:
    return tag.split("}", 1)[1] if "}" in tag else tag


def maven_runtime_deps(pom_text: Optional[str]) -> Dict[Tuple[str, str], int]:
    """{(groupId, artifactId): line} for direct compile/runtime dependencies.

    Dependencies inside <dependencyManagement> only pin versions and are not
    dependencies; they are skipped. Line numbers are best-effort (the first
    line of the file that names the artifactId)."""
    if not pom_text:
        return {}
    try:
        root = ET.fromstring(pom_text)
    except ET.ParseError:
        return {}
    lines = pom_text.splitlines()
    result: Dict[Tuple[str, str], int] = {}

    def walk(node, in_mgmt: bool) -> None:
        for child in node:
            tag = _strip_ns(child.tag)
            if tag == "dependencyManagement":
                continue
            if tag == "dependency" and not in_mgmt:
                fields = {_strip_ns(c.tag): (c.text or "").strip() for c in child}
                if fields.get("scope", "") in RUNTIME_SCOPES and fields.get("optional", "") != "true":
                    key = (fields.get("groupId", ""), fields.get("artifactId", ""))
                    needle = f"<artifactId>{key[1]}</artifactId>"
                    line = next((i + 1 for i, l in enumerate(lines) if needle in l), 1)
                    result[key] = line
                continue
            walk(child, in_mgmt)

    walk(root, False)
    return result


def _banned_reason(group: str, artifact: str) -> Optional[str]:
    for g, a, why in BANNED_MAVEN:
        if g == group and (a == "*" or a == artifact):
            return why
    return None


def check_license(added: Dict[str, List[Tuple[int, str]]], base_text, head_text) -> List[Finding]:
    findings: List[Finding] = []
    notices = head_text("THIRD_PARTY_NOTICES.md") or ""
    for path in sorted(added):
        if path in SELF_PATHS or is_test(path):
            continue
        if PurePosixPath(path).name == "pom.xml":
            head_text_pom = head_text(path)
            if head_text_pom is not None:
                try:
                    ET.fromstring(head_text_pom)
                except ET.ParseError as exc:
                    findings.append(Finding(path, 1, "license", f"POM does not parse: {exc}"))
                    continue
            before = maven_runtime_deps(base_text(path))
            after = maven_runtime_deps(head_text_pom)
            for (group, artifact), line in sorted(after.items()):
                if (group, artifact) in before:
                    continue
                why = _banned_reason(group, artifact)
                if why:
                    findings.append(Finding(path, line, "license",
                                            f"{group}:{artifact} is {why}; it must not reach the compile/runtime "
                                            "scope of an Apache-2.0 artifact (use test/provided scope, exclude it, "
                                            "or replace it; see doc/licensing.md)"))
                elif artifact and artifact not in notices:
                    findings.append(Finding(path, line, "license",
                                            f"new runtime dependency {group}:{artifact} is not listed in "
                                            "THIRD_PARTY_NOTICES.md; add it with its license"))
        elif PY_REQ_FILE_RE.search(path):
            for lineno, text in added[path]:
                m = PY_REQ_NAME_RE.match(text)
                if not m:
                    continue
                name = m.group(1).lower().replace("_", "-")
                if name in BANNED_PYTHON:
                    findings.append(Finding(path, lineno, "license",
                                            f"Python dependency '{name}' is {BANNED_PYTHON[name]}; "
                                            "Category X licenses cannot be distributed with this project"))
    return findings


# --------------------------------------------------------------------------
# hygiene gate
# --------------------------------------------------------------------------

CONFLICT_RE = re.compile(r"^(?:<{7}|>{7})(?:\s|$)")
JAVA_EMPTY_CATCH_RE = re.compile(r"\bcatch\s*\([^)]*\)\s*\{\s*\}")
JAVA_CATCH_OPEN_RE = re.compile(r"\bcatch\s*\([^)]*\)\s*\{\s*$")
PY_BARE_EXCEPT_RE = re.compile(r"^\s*except\s*:\s*(?:#.*)?$")
PY_EXCEPT_PASS_RE = re.compile(r"^\s*except\b[^:]*:\s*pass\s*(?:#.*)?$")
PY_EXCEPT_OPEN_RE = re.compile(r"^\s*except\b[^:]*:\s*(?:#.*)?$")
PY_PASS_RE = re.compile(r"^\s*pass\s*(?:#.*)?$")
# Best-effort cleanup (closing a pipe, removing a temp file, reaping a child)
# may ignore these narrow OS errors; anything broader must be handled.
_CLEANUP_ERRORS = r"(?:OSError|FileNotFoundError|ProcessLookupError|ChildProcessError)"
PY_CLEANUP_EXCEPT_RE = re.compile(
    r"^\s*except\s*\(?\s*" + _CLEANUP_ERRORS + r"(?:\s*,\s*" + _CLEANUP_ERRORS + r")*"
    r"\s*\)?\s*(?:as\s+\w+\s*)?:")
DEBUGGER_RE = re.compile(r"\b(?:pdb\.set_trace\(\)|ipdb\.set_trace\(\)|breakpoint\(\)|import\s+i?pdb\b)")
SECRET_PATTERNS = (
    (re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"), "a private key"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "an AWS access key id"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"), "a GitHub token"),
    (re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b"), "a chat-service token"),
    (re.compile(r"\bglpat-[A-Za-z0-9_-]{20,}\b"), "a GitLab token"),
)
TEMPLATE_MARKERS = ("{{", "{%")


def _swallowed(path: str, lines: List[Tuple[int, str]]) -> List[Finding]:
    findings: List[Finding] = []
    java = path.endswith((".java", ".kt", ".groovy", ".scala"))
    py = path.endswith(".py")
    for i, (lineno, text) in enumerate(lines):
        nxt = lines[i + 1] if i + 1 < len(lines) and lines[i + 1][0] == lineno + 1 else None
        if java:
            if JAVA_EMPTY_CATCH_RE.search(text) or (
                    JAVA_CATCH_OPEN_RE.search(text) and nxt is not None and nxt[1].strip() == "}"):
                findings.append(Finding(path, lineno, "hygiene",
                                        "empty catch block swallows the exception; handle it, rethrow it, "
                                        "or log it with the reason it is safe to continue"))
        elif py:
            if PY_BARE_EXCEPT_RE.match(text):
                findings.append(Finding(path, lineno, "hygiene",
                                        "bare 'except:' also catches KeyboardInterrupt/SystemExit; name the exception"))
            if PY_CLEANUP_EXCEPT_RE.match(text):
                continue
            if PY_EXCEPT_PASS_RE.match(text) or (
                    PY_EXCEPT_OPEN_RE.match(text) and nxt is not None and PY_PASS_RE.match(nxt[1])):
                findings.append(Finding(path, lineno, "hygiene",
                                        "'except ...: pass' swallows the exception; handle it, re-raise it, "
                                        "or log why it is safe to ignore"))
    return findings


def check_hygiene(added: Dict[str, List[Tuple[int, str]]], head_text) -> Tuple[List[Finding], List[str]]:
    findings: List[Finding] = []
    notices: List[str] = []
    yaml_mod = None
    try:
        import yaml as yaml_mod  # type: ignore
    except ImportError:
        yaml_mod = None
    yaml_skipped = False
    for path, lines in sorted(added.items()):
        if path in SELF_PATHS:
            continue
        for lineno, text in lines:
            if CONFLICT_RE.match(text):
                findings.append(Finding(path, lineno, "hygiene", "merge-conflict marker"))
            for pattern, what in SECRET_PATTERNS:
                if pattern.search(text):
                    findings.append(Finding(path, lineno, "hygiene",
                                            f"looks like {what}; never commit credentials"))
            if path.endswith(".py") and not is_test(path) and DEBUGGER_RE.search(text):
                findings.append(Finding(path, lineno, "hygiene", "debugger leftover"))
        if not is_test(path):
            findings += _swallowed(path, lines)
        if path.endswith((".yml", ".yaml")):
            text = head_text(path)
            if text is None or any(m in text for m in TEMPLATE_MARKERS):
                continue
            if yaml_mod is None:
                yaml_skipped = True
                continue
            try:
                list(yaml_mod.safe_load_all(text))
            except yaml_mod.YAMLError as exc:
                mark = getattr(exc, "problem_mark", None)
                findings.append(Finding(path, (mark.line + 1) if mark else 1, "hygiene",
                                        f"YAML does not parse: {str(exc).splitlines()[0]}"))
        elif path.endswith(".json"):
            text = head_text(path)
            if text is None:
                continue
            try:
                json.loads(text)
            except ValueError as exc:
                findings.append(Finding(path, getattr(exc, "lineno", 1), "hygiene",
                                        f"JSON does not parse: {exc}"))
    if yaml_skipped:
        notices.append("PyYAML is not installed: YAML syntax check skipped (pip install pyyaml)")
    return findings, notices


# --------------------------------------------------------------------------
# driver
# --------------------------------------------------------------------------

def run_gates(repo: str, base: str, head: str, gates: Iterable[str]) -> Tuple[List[Finding], List[str]]:
    gates = list(gates)
    mb = merge_base(repo, base, head)
    added = parse_added_lines(changed_diff(repo, base, head))
    cache: Dict[Tuple[str, str], Optional[str]] = {}

    def text_at(ref: str):
        def get(path: str) -> Optional[str]:
            key = (ref, path)
            if key not in cache:
                cache[key] = show_file(repo, ref, path)
            return cache[key]
        return get

    head_text, base_text = text_at(head), text_at(mb)
    findings: List[Finding] = []
    notices: List[str] = []
    if "destructive" in gates:
        findings += check_destructive(added, head_text, range_messages(repo, mb, head))
    if "merge-stop" in gates:
        findings += check_merge_stop(added)
    if "license" in gates:
        findings += check_license(added, base_text, head_text)
    if "hygiene" in gates:
        hyg, notes = check_hygiene(added, head_text)
        findings += hyg
        notices += notes
    findings.sort(key=lambda f: (f.path, f.line, f.gate))
    return findings, notices


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Deterministic review gates over the lines a change adds (Spec 11.06).")
    parser.add_argument("--base", required=True, help="base ref, e.g. origin/2.11.0")
    parser.add_argument("--head", default="HEAD", help="head ref (default HEAD)")
    parser.add_argument("--gate", action="append", choices=("all",) + GATE_NAMES,
                        help="gate to run; repeatable (default all)")
    parser.add_argument("--repo", default=".", help="repository path (default .)")
    args = parser.parse_args(argv)
    gates = GATE_NAMES if not args.gate or "all" in args.gate else tuple(args.gate)
    try:
        findings, notices = run_gates(args.repo, args.base, args.head, gates)
    except GitError as exc:
        print(f"review_gates: {exc}", file=sys.stderr)
        return 2
    for note in notices:
        print(f"review_gates: notice: {note}", file=sys.stderr)
    for f in findings:
        print(f.render())
    print(f"review_gates: {len(findings)} finding(s) from gate(s) {', '.join(gates)}", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
