"""Observation hook for the e2e suite (loaded only when a test asks for it).

Python imports ``sitecustomize`` at start-up when it is on ``sys.path``. This
one wraps psycopg2's RealDictCursor.execute (the cursor the tools' execute_pg
uses) and, right before every checksum chunk query that runs inside an open
transaction, asks the same transaction for its isolation level and prints

    PYTOOLS_E2E_PROBE isolation=<level>

to stderr. It reads one setting and changes nothing, so the tool behaves as it
would without the hook. Used by the REPEATABLE READ snapshot test.
"""

import importlib.util
import os
import sys

# Chain to the sitecustomize this file shadows (an interpreter may ship one).
_here = os.path.dirname(os.path.abspath(__file__))
for _entry in sys.path:
    _candidate = os.path.join(_entry or ".", "sitecustomize.py")
    if os.path.abspath(_entry or ".") != _here and os.path.isfile(_candidate):
        _spec = importlib.util.spec_from_file_location("_shadowed_sitecustomize", _candidate)
        _spec.loader.exec_module(importlib.util.module_from_spec(_spec))
        break

try:
    import psycopg2.extras as _extras
except Exception:  # psycopg2 missing: nothing to observe
    _extras = None

if _extras is not None:
    _orig_execute = _extras.RealDictCursor.execute

    def _probing_execute(self, query, vars=None):
        text = str(query)
        conn = self.connection
        if not conn.autocommit and "md5(" in text and "concat_ws" in text:
            with conn.cursor() as probe:
                probe.execute("SHOW transaction_isolation")
                level = probe.fetchone()[0]
            sys.stderr.write(f"PYTOOLS_E2E_PROBE isolation={level}\n")
            sys.stderr.flush()
        return _orig_execute(self, query, vars)

    _extras.RealDictCursor.execute = _probing_execute
