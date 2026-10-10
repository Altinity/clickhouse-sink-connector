---
name: code-reuse-review
description: >
  Use during PR review when a diff adds new code that might duplicate
  something that already exists in the repo. Checks whether the author
  actually looked before writing, and whether the new code should instead
  extend, call, or replace an existing utility.
---

# Code Reuse Review

## Overview
A diff that adds a new helper, parser, or utility is only a problem if the
repo already has one that does the same thing. This skill is the
reviewer's half of `code-reuse-first` — checking, from the outside, whether
an author's new code duplicates existing logic rather than reusing it.

Earlier guidance in this space mistakenly optimized for brevity (shortest
possible diff) as the goal; that framing was dropped — the actual goal is
not writing code that already exists, not minimizing line count.

## When to Use
- Reviewing a PR that adds a new module, function, or script
- Any diff that "re-implements" parsing, retry, config-loading, or
  comparison logic that sounds like it should already exist somewhere

## Review Questions

1. **Does something equivalent already exist in this repo?** Grep for the
   concept (not just the exact name) across `sink-connector`,
   `sink-connector-lightweight`, `sink-connector-client`, and
   `sink-connector/python` before accepting that new code is necessary.
2. **Did the author actually read the existing code**, or just confirm a
   file with a similar name exists and move on? A new function with a
   suspiciously similar signature to an existing one, placed in a new file,
   is a signal reuse wasn't considered.
3. **Is the duplication justified?** Sometimes a small, local copy is
   correct — e.g., when sharing code across language boundaries
   (`sink-connector-client` in Go genuinely can't call into
   `sink-connector`'s Java code) or when coupling two call sites would be
   worse than a few duplicated lines. State the reason in the PR if so;
   silent duplication is the problem, not duplication itself.
4. **Does the new code belong where it was added**, or does it belong in an
   existing shared location (e.g., a comparison helper added inside a CLI
   script instead of in `sink-connector/python/db_compare`, where other
   comparison logic already lives)?
5. **If existing code was close-but-not-quite right, was it extended rather
   than copied-and-modified?** A copy-paste-and-tweak is a maintenance
   liability: the next fix has to be applied twice, and usually isn't.

## Example

```python
# BAD: new file sink-connector/python/ch_sink_tools/compare_helpers.py
# re-implements row hashing that db_compare already does
def hash_row(row):
    return hashlib.sha256(str(row).encode()).hexdigest()

# GOOD: import and reuse
from sink_connector.python.db_compare.hashing import hash_row
```

## Anti-Patterns
A new utility with the same purpose as an existing one, placed in a new
file "to keep things simple" · copy-pasted logic with small tweaks instead
of a shared, parameterized function · duplication introduced silently, with
no note in the PR explaining why reuse wasn't possible.

## Cross-References
`code-reuse-first`, `pr-self-review`, `code-quality-architecture`.
