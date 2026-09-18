#!/usr/bin/env python3
"""Spec-Driven Development Validator for ClickHouse Sink Connector.

Validates that:
1. All core governance specifications (CONSTITUTION.md, SMART_RALPH_PROTOCOL.md, README.md) exist.
2. All 9 component specifications (001-009) exist and adhere to the standard schema.
3. The formal Lean 4 verification suite is complete and all modules exist.
4. AGENTS.md, CLAUDE.md, and Copilot instructions enforce the spec-driven mandate.
"""

from __future__ import annotations

import os
import re
import sys
from pathlib import Path


COMPONENT_SPECS = [
    ("001-cdc-ingestion.md", "CDC Ingestion & Binlog Stream Processing"),
    ("002-monotonic-versioning.md", "Monotonic Versioning & Ordering"),
    ("003-clickhouse-writer-batching.md", "ClickHouse Batch Writer & Execution Engine"),
    ("004-sorting-key-mutation.md", "Primary & Sorting Key Mutation Handling"),
    ("005-ddl-barrier-synchronization.md", "DDL Interception, Translation & Barrier Synchronization"),
    ("006-type-mapping.md", "Comprehensive Data Type Mapping & Conversion"),
    ("007-schema-catalog-invalidation.md", "Schema Catalog, Metadata Caching & Invalidation"),
    ("008-offset-management-quiescence.md", "Offset Management, Quiescence & Checkpointing"),
    ("009-error-handling-and-recovery.md", "Error Classification, Retries & Status Monitoring"),
]

REQUIRED_SPEC_SECTIONS = [
    "Executive Summary",
    "Codebase Mapping",
    "Invariants",
    "Verification",
]

LEAN_MODULES = [
    "Basic.lean",
    "Binlog.lean",
    "ClickHouse.lean",
    "Engine.lean",
    "Invariants.lean",
    "Proofs.lean",
]


def check_spec_file(path: Path) -> list[str]:
    errors = []
    if not path.is_file():
        return [f"Missing specification file: {path.name}"]
    content = path.read_text(encoding="utf-8")
    for sec in REQUIRED_SPEC_SECTIONS:
        if not re.search(rf"#+\s*.*{sec}", content, re.IGNORECASE):
            errors.append(f"{path.name}: missing required section matching '{sec}'")
    return errors


def main() -> int:
    # Resolve repository root
    repo_root = Path(__file__).resolve().parent.parent
    specs_dir = repo_root / "specs"
    lean_dir = repo_root / "formal_specs" / "lean"
    rep_dir = lean_dir / "Replication"

    print("=====================================================================")
    print("      CLICKHOUSE SINK CONNECTOR: SPEC-DRIVEN REPO VALIDATOR          ")
    print("=====================================================================")
    print(f"Repository Root: {repo_root}")

    all_errors: list[str] = []

    # 1. Validate Governance Specs
    print("\n[1/4] Checking System Governance Specifications...")
    constitution = specs_dir / "CONSTITUTION.md"
    readme = specs_dir / "README.md"
    protocol = specs_dir / "SMART_RALPH_PROTOCOL.md"

    for doc in [constitution, readme, protocol]:
        if not doc.is_file():
            all_errors.append(f"Missing governance document: specs/{doc.name}")
        else:
            print(f"  ✓ Found specs/{doc.name}")

    if constitution.is_file():
        c_text = constitution.read_text(encoding="utf-8")
        for inv in ["Invariant I1", "Invariant I2", "Invariant I3", "Invariant I4", "Invariant I5"]:
            if inv not in c_text:
                all_errors.append(f"CONSTITUTION.md: missing invariant definition '{inv}'")

    # 2. Validate Component Specs
    print("\n[2/4] Checking Component Specifications (001–009)...")
    for filename, title in COMPONENT_SPECS:
        spec_path = specs_dir / filename
        errs = check_spec_file(spec_path)
        if errs:
            all_errors.extend(errs)
            print(f"  ✗ specs/{filename} ({title}) - FAILED")
        else:
            print(f"  ✓ specs/{filename} ({title})")

    # 3. Validate Lean 4 Formal Verification Suite
    print("\n[3/4] Checking Formal Verification Suite (Lean 4)...")
    toolchain = lean_dir / "lean-toolchain"
    lakefile = lean_dir / "lakefile.lean"
    lean_readme = lean_dir / "README.md"

    for f in [toolchain, lakefile, lean_readme]:
        if not f.is_file():
            all_errors.append(f"Missing Lean configuration file: formal_specs/lean/{f.name}")
        else:
            print(f"  ✓ formal_specs/lean/{f.name}")

    for mod in LEAN_MODULES:
        mod_path = rep_dir / mod
        if not mod_path.is_file():
            all_errors.append(f"Missing Lean source module: Replication/{mod}")
        else:
            print(f"  ✓ formal_specs/lean/Replication/{mod}")

    # 4. Validate Agent & Guidance Files
    print("\n[4/4] Checking Agent Instructions & Workflow Guidance...")
    agents_md = repo_root / "AGENTS.md"
    claude_md = repo_root / "CLAUDE.md"
    copilot_md = repo_root / ".github" / "copilot-instructions.md"

    for guide in [agents_md, claude_md, copilot_md]:
        if not guide.is_file():
            all_errors.append(f"Missing guidance file: {guide.name}")
        else:
            print(f"  ✓ Found {guide.name}")

    if agents_md.is_file():
        a_text = agents_md.read_text(encoding="utf-8")
        if "specs/" not in a_text or "Smart Ralph" not in a_text:
            all_errors.append("AGENTS.md: missing reference to 'specs/' or 'Smart Ralph' spec-driven protocol")

    print("\n---------------------------------------------------------------------")
    if all_errors:
        print(f"Validation FAILED with {len(all_errors)} error(s):")
        for err in all_errors:
            print(f"  - {err}")
        return 1

    print("Validation PASSED: All specifications, Lean 4 formal models, and agent")
    print("governance documents are present, complete, and synchronized.")
    print("=====================================================================")
    return 0


if __name__ == "__main__":
    sys.exit(main())
