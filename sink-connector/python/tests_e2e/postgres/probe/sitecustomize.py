"""Observation hook for the e2e suite (loaded only when a test asks for it).

Python imports ``sitecustomize`` at start-up when it is on ``sys.path``. This
one wraps pg8000's dbapi.Cursor.execute (the cursor the tools' execute_pg
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
    import pg8000.dbapi as _dbapi
except Exception:  # pg8000 missing: nothing to observe
    _dbapi = None

if _dbapi is not None:
    _orig_execute = _dbapi.Cursor.execute

    def _probing_execute(self, operation, args=(), stream=None):
        text = str(operation)
        # Use the private ``_c`` attribute rather than the public
        # ``connection`` property: the latter emits a DeprecationWarning on
        # every access (pg8000 DB-API extension), which would be noisy here.
        conn = self._c
        if not conn.autocommit and "md5(" in text and "concat_ws" in text:
            probe = conn.cursor()
            try:
                probe.execute("SHOW transaction_isolation")
                level = probe.fetchone()[0]
            finally:
                probe.close()
            sys.stderr.write(f"PYTOOLS_E2E_PROBE isolation={level}\n")
            sys.stderr.flush()
        return _orig_execute(self, operation, args, stream)

    _dbapi.Cursor.execute = _probing_execute
