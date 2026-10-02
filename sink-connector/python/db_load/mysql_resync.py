#!/usr/bin/env python3
"""Legacy-tree entry point for ``ch-mysql-resync`` (see ``ch_sink_tools/db_load/mysql_resync.py`` for the tool).

The packaged module only shells out to ``clickhouse-client``, ``zstd``, ``mysqlsh`` and the loader, so running it from
this checkout needs no installation: ``python db_load/mysql_resync.py ...`` from ``sink-connector/python``. Point
``--loader-cmd`` at this tree's loader (``python db_load/clickhouse_loader.py``) when the package is not installed.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ch_sink_tools.db_load.mysql_resync import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
