---
name: code-quality-bash
description: >
  Use when writing or reviewing shell scripts in this repo (build helpers,
  CI scripts, anything under scripts/), or when asked for
  "worldclass"/"robust" Bash. Covers strict-mode, quoting, error handling,
  and a deep review checklist for the defect classes that make shell
  scripts silently do the wrong thing.
---

# Worldclass Bash

## Overview
Robust, defensive shell scripting for this project's build and CI helper
scripts. Shell is easy to get subtly wrong — unquoted variables, unchecked
exit codes, and word-splitting bugs are the dominant defect classes, not
style.

## When to Use
- Writing or reviewing any `.sh` file in this repo
- A user asks for "worldclass / robust" Bash
- Reviewing a build, CI, or operational script for correctness

## Core Rules

1. **Strict mode at the top of every script:**
   `set -euo pipefail` — exit on error, exit on unset variable, and make a
   pipeline fail if any stage fails (not just the last one).
2. **Quote every variable expansion**: `"$var"`, `"${array[@]}"`. Unquoted
   expansions are the single most common source of word-splitting and
   globbing bugs.
3. **Check exit codes explicitly** where `set -e` isn't enough (inside a
   conditional, inside a pipeline you need to inspect `PIPESTATUS` for).
4. **Prefer `[[ ]]` over `[ ]`** for conditionals — safer word-splitting
   behavior and supports pattern matching.
5. **`local` for function-scoped variables** — avoid leaking state between
   functions in anything beyond a trivial script.
6. **`mktemp` for temp files/dirs**, cleaned up with a `trap ... EXIT`, not
   a hardcoded path that can collide or leak.
7. **Shellcheck-clean.** Run `shellcheck` in CI; treat its warnings as real
   findings, not noise to suppress.

```bash
# BAD
for f in $(ls *.txt); do
  cp $f /tmp/backup/
done

# GOOD
set -euo pipefail
for f in *.txt; do
  [[ -e "$f" ]] || continue
  cp -- "$f" /tmp/backup/
done
```

## Deep Review Checklist (Bash)
Run this over every changed `.sh` file during `pr-self-review`'s
per-language pass.

1. **Missing `set -euo pipefail`** (or a deliberate, commented exception).
2. **Unquoted variable/command-substitution expansions**, especially around
   filenames that might contain spaces.
3. **Parsing `ls` output** instead of globbing or `find -print0` /
   `read -d ''`.
4. **Unchecked exit codes** on commands whose failure should abort the
   script but currently doesn't (especially inside a pipeline, where
   `pipefail` is required to catch it).
5. **Destructive commands** (`rm -rf`, truncation, overwrite-in-place)
   without a `DESTRUCTIVE:` comment and the safeguards described in
   `destructive-operation-safety`.
6. **Hardcoded temp paths** instead of `mktemp`, risking collisions or
   leftover state between runs.
7. **Word-splitting on `$@` vs `"$@"`** when forwarding arguments to
   another command.

## Anti-Patterns
Missing strict mode · unquoted expansions · parsing `ls` · ignoring
`shellcheck` warnings · hardcoded `/tmp` paths without `mktemp` ·
destructive operations with no dry-run or confirmation path.

## Sources
ShellCheck documentation · Google Shell Style Guide
(google.github.io/styleguide/shellguide.html) · `bash` strict-mode
conventions (`set -euo pipefail`).

## Cross-References
`pr-self-review`, `destructive-operation-safety`.
