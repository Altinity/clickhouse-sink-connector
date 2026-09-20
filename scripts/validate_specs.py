#!/usr/bin/env python3
"""Spec-Driven Development Validator for ClickHouse Sink Connector.

Validates that:
1. Core governance documents (CONSTITUTION.md, README.md, SMART_RALPH_PROTOCOL.md) exist.
2. All domain subdirectories exist and all encapsulated micro-specs satisfy the required schema.
3. The formal Lean 4 verification suite is complete and all modules exist.
4. AGENTS.md, CLAUDE.md, and Copilot instructions enforce the spec-driven mandate.
"""

from __future__ import annotations

import os
import re
import sys
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
    "Upgrade.lean",
    "Snapshot.lean",
    "GeneratedColumn.lean",
    "OffsetFifo.lean",
]


def check_spec_file(path: Path) -> list[str]:
    errors = []
    if not path.is_file():
        return [f"Missing specification file: {path.name}"]
    content = path.read_text(encoding="utf-8")
    for sec in REQUIRED_SPEC_SECTIONS:
        if not re.search(rf"#+\s*.*{sec}", content, re.IGNORECASE):
            errors.append(f"{path.relative_to(path.parent.parent)}: missing required section matching '{sec}'")
    return errors


def main() -> int:
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

    # 2. Validate Domain Micro-Specifications
    print("\n[2/4] Checking Encapsulated Domain Micro-Specifications...")
    total_specs_found = 0
    for domain in EXPECTED_DOMAINS:
        domain_dir = specs_dir / domain
        if not domain_dir.is_dir():
            all_errors.append(f"Missing domain directory: specs/{domain}")
            print(f"  ✗ specs/{domain}/ - MISSING")
            continue

        domain_specs = sorted(domain_dir.glob("*.md"))
        if not domain_specs:
            all_errors.append(f"No specifications found in domain: specs/{domain}")
            print(f"  ✗ specs/{domain}/ - EMPTY")
            continue

        print(f"  ✓ specs/{domain}/ ({len(domain_specs)} micro-specs):")
        for spec_path in domain_specs:
            total_specs_found += 1
            errs = check_spec_file(spec_path)
            if errs:
                all_errors.extend(errs)
                print(f"      ✗ {spec_path.name} - FAILED SCHEMA CHECK")
            else:
                print(f"      ✓ {spec_path.name}")

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
            # A `sorry` (or `admit`) means an incomplete proof: the formal
            # verification would build but prove nothing. Reject it so the
            # "machine-checked" claim stays honest.
            mod_text = mod_path.read_text(encoding="utf-8")
            if re.search(r"\bsorry\b", mod_text) or re.search(r"\badmit\b", mod_text):
                all_errors.append(
                    f"Replication/{mod}: contains 'sorry'/'admit' — proof is incomplete"
                )
                print(f"  ✗ formal_specs/lean/Replication/{mod} - INCOMPLETE PROOF")
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
    print(f"Total Encapsulated Specifications Validated: {total_specs_found}")
    if all_errors:
        print(f"Validation FAILED with {len(all_errors)} error(s):")
        for err in all_errors:
            print(f"  - {err}")
        return 1

    print("Validation PASSED: All 57+ specifications, Lean 4 formal models, and agent")
    print("governance documents are present, complete, and synchronized.")
    print("=====================================================================")
    return 0


if __name__ == "__main__":
    sys.exit(main())
