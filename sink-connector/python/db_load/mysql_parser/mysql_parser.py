"""Legacy path of ``ch_sink_tools.db_load.mysql_parser.mysql_parser`` -- kept so existing launchers and imports keep working.

There is ONE implementation, in ``sink-connector/python/ch_sink_tools/db_load/mysql_parser/mysql_parser.py`` (Spec 13.01 section 3.16). Importing this name
returns the same module object as ``ch_sink_tools.db_load.mysql_parser.mysql_parser``, so the two names cannot drift and patching either
patches both. Started as a script (``python db_load/mysql_parser/mysql_parser.py ...``) it runs the tool's main(). Works with or without the package installed: the Python root that holds
``ch_sink_tools`` is put on ``sys.path`` first.
"""
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import ch_sink_tools.db_load.mysql_parser.mysql_parser as _implementation  # noqa: E402

if __name__ == "__main__":
    sys.exit(_implementation.main(sys.argv))

sys.modules[__name__] = _implementation
