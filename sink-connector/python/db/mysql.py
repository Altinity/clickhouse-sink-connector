"""Legacy path of ``ch_sink_tools.db.mysql`` -- kept so existing imports keep working.

There is ONE implementation, in ``sink-connector/python/ch_sink_tools/db/mysql.py`` (Spec 13.01 section 3.16). Importing this name
returns the same module object as ``ch_sink_tools.db.mysql``, so the two names cannot drift and patching either
patches both. Works with or without the package installed: the Python root that holds
``ch_sink_tools`` is put on ``sys.path`` first.
"""
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import ch_sink_tools.db.mysql as _implementation  # noqa: E402

sys.modules[__name__] = _implementation
