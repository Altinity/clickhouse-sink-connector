"""
Packaging floor: the declared Python floor must cover every module that imports
zoneinfo (a standard-library module from Python 3.9), the runtime requirements
must be exact pins, and tzdata must be declared as a runtime requirement.
"""

import ast
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
PYPROJECT = ROOT / "pyproject.toml"
REQUIREMENTS = ROOT / "requirements.txt"
PIN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*(\[[A-Za-z0-9,_-]+\])?==[A-Za-z0-9.+!_-]+$")
SKIPPED_DIRS = {".git", ".venv", "build", "__pycache__"}


def _declared_floor():
    text = PYPROJECT.read_text(encoding="utf-8")
    match = re.search(r'^requires-python\s*=\s*">=\s*(\d+)\.(\d+)"\s*$', text, re.MULTILINE)
    assert match, 'pyproject.toml must declare requires-python = ">=X.Y"'
    return int(match.group(1)), int(match.group(2))


def _python_files():
    for path in ROOT.rglob("*.py"):
        if not SKIPPED_DIRS.intersection(path.parts):
            yield path


def _zoneinfo_importers():
    importers = []
    for path in _python_files():
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.level == 0:
                names = [node.module or ""]
            else:
                continue
            if any(name.split(".")[0] == "zoneinfo" for name in names):
                importers.append(str(path.relative_to(ROOT)))
                break
    return sorted(importers)


def _requirement_lines():
    lines = [line.strip() for line in REQUIREMENTS.read_text(encoding="utf-8").splitlines()]
    return [line for line in lines if line and not line.startswith("#")]


def test_declared_floor_covers_zoneinfo_imports():
    importers = _zoneinfo_importers()
    assert importers, "expected at least one zoneinfo importer in the tree"
    floor = _declared_floor()
    assert floor >= (3, 9), "zoneinfo needs Python 3.9; imported by " + ", ".join(importers)


def test_runtime_requirements_are_exact_pins():
    requirements = _requirement_lines()
    assert requirements, "requirements.txt must list the runtime pins"
    unpinned = [line for line in requirements if not PIN.match(line)]
    assert not unpinned, "unpinned requirements: " + ", ".join(unpinned)


def test_tzdata_is_a_declared_runtime_requirement():
    assert any(line.startswith("tzdata==") for line in _requirement_lines())
    assert '"tzdata"' in PYPROJECT.read_text(encoding="utf-8")
