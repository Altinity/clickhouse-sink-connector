#!/usr/bin/env python3
"""Two defects the end-to-end scheduled checksum job found (spec 13.06 D-13.06-38, D-13.06-39).

- D-13.06-38: under binary.handling.mode=base64 the connector stores BIT(n>1) as lower-case
  hex text (base64 applies to BINARY/VARBINARY/BLOB only). The MySQL side rendered BIT(n>1)
  as base64 with --binary_encoding base64, so every table with such a column was DIFFERENT
  on clean data and the job failed.
- D-13.06-39: the job sources install.sh after `set -euo pipefail`; install.sh read
  "${PYTHONPATH}" unguarded, so with PYTHONPATH unset the job died before any checksum.

Found by sink-connector/python/tests_e2e/mysql/test_mysql_01_production_job.py. Offline: no
database, no network (install.sh runs against stand-in python3/pip). Run from
sink-connector/python:  python3 -m pytest db_compare/tests/test_scheduled_job_findings.py
"""
import argparse
import os
import shutil
import stat
import subprocess
import sys

import pytest

PYTHON_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, PYTHON_ROOT)

import db_compare.mysql_table_checksum as my  # noqa: E402
from db.checksum_common import DATETIME_MAX, DATETIME_MIN  # noqa: E402

OPTIONS = argparse.Namespace(min_date_value="1900-01-01", max_date_value="2299-12-31")


def expression(data_type, column_type, encoding):
    column = {"column_name": "c", "data_type": data_type, "column_type": column_type, "collation": None}
    return my.mysql_column_expression(column, OPTIONS, encoding, True, (DATETIME_MIN, DATETIME_MAX))


class TestBitColumnRendering:
    @pytest.mark.parametrize("encoding", ["hex", "base64", "raw"])
    def test_bit_n_is_lower_hex_under_every_encoding(self, encoding):
        assert expression("bit", "bit(16)", encoding) == "lower(hex(cast(`c` as binary)))"

    @pytest.mark.parametrize("encoding", ["hex", "base64", "raw"])
    def test_bit_1_is_still_an_integer(self, encoding):
        assert expression("bit", "bit(1)", encoding) == "`c`+0"

    def test_binary_types_still_follow_the_encoding(self):
        assert expression("varbinary", "varbinary(16)", "base64") == \
            "replace(to_base64(cast(`c` as binary)),'\\n','')"
        assert expression("blob", "blob", "hex") == "lower(hex(cast(`c` as binary)))"


def stand_in(bin_dir, name, body):
    path = bin_dir / name
    path.write_text("#!/bin/sh\n" + body)
    path.chmod(path.stat().st_mode | stat.S_IEXEC)


@pytest.mark.skipif(shutil.which("bash") is None, reason="bash not installed")
class TestInstallShUnderStrictMode:
    def run_install(self, tmp_path, pythonpath=None):
        """`set -euo pipefail; source ./install.sh` as the scheduled job does, with stand-in
        python3 (creates an empty .venv/bin/activate) and pip (does nothing)."""
        work = tmp_path / "tools"
        work.mkdir()
        shutil.copy(os.path.join(PYTHON_ROOT, "install.sh"), work / "install.sh")
        shutil.copy(os.path.join(PYTHON_ROOT, "requirements.txt"), work / "requirements.txt")
        bin_dir = tmp_path / "bin"
        bin_dir.mkdir()
        stand_in(bin_dir, "python3", 'mkdir -p .venv/bin && : > .venv/bin/activate\n')
        stand_in(bin_dir, "pip", "exit 0\n")
        env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
        env["PATH"] = f"{bin_dir}{os.pathsep}{env['PATH']}"
        if pythonpath is not None:
            env["PYTHONPATH"] = pythonpath
        return subprocess.run(["bash", "-c", 'set -euo pipefail\nsource ./install.sh\necho "PYTHONPATH=$PYTHONPATH"\n'],
                              cwd=work, env=env, capture_output=True, text=True)

    def test_sources_with_pythonpath_unset(self, tmp_path):
        result = self.run_install(tmp_path)
        assert result.returncode == 0, result.stderr
        assert result.stdout.strip().endswith("PYTHONPATH=:."), result.stdout

    def test_keeps_an_existing_pythonpath(self, tmp_path):
        result = self.run_install(tmp_path, pythonpath="/opt/lib")
        assert result.returncode == 0, result.stderr
        assert result.stdout.strip().endswith("PYTHONPATH=/opt/lib:."), result.stdout
