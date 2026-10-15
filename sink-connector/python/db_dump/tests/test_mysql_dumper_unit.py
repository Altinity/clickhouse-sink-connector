"""Unit coverage for the pure-logic helpers in db_dump/mysql_dumper.py.

Spec 11.05 (python-tooling test contract): the dumper's credential-redaction
and mysqlsh dump-clause construction are deterministic and must be covered
without a live database. The dumper previously had zero unit tests.
Credential redaction is security-critical (it guards against passwords leaking
into logs), so it is tested explicitly.
"""
import json

from db_dump import mysql_dumper as md


class TestRedactPassword:
    def test_registered_secret_is_masked(self):
        md.register_secret("s3cr3t-pw")
        out = md.redact_password("mysqlsh -u u -ps3cr3t-pw -h host")
        assert "s3cr3t-pw" not in out
        assert "****" in out

    def test_password_flag_value_masked_even_if_unregistered(self):
        out = md.redact_password("mysql --password 'neverRegistered123' -h h")
        assert "neverRegistered123" not in out
        assert "****" in out

    def test_secret_with_single_quote_is_fully_masked(self):
        # shlex.quote renders such a password as concatenated segments; masking
        # the literal value must still remove every trace of it.
        md.register_secret("my'secret")
        out = md.redact_password("cmd 'my'\"'\"'secret'")
        assert "my'secret" not in out

    def test_non_secret_text_preserved(self):
        out = md.redact_password("mysqlsh -u appuser -h dbhost --port 3306")
        assert "appuser" in out and "dbhost" in out


class TestDumpTablesClause:
    def _clause(self, **kw):
        base = dict(dump_dir="/dumps/d", dry_run=False, database="fees_staging",
                    tables_to_dump="['txn_fee']", data_only=True, schema_only=False,
                    where=None, partition_map=None, threads=8, bytes_per_chunk="256M",
                    consistent=False)
        base.update(kw)
        return md.generate_mysqlsh_dump_tables_clause(**base)

    def test_targets_database_table_and_dir(self):
        c = self._clause()
        assert "util.dumpTables(" in c
        assert "'fees_staging'" in c
        assert "['txn_fee']" in c
        assert "'/dumps/d'" in c

    def test_data_only_and_schema_only_flags(self):
        c = self._clause(data_only=True, schema_only=False)
        assert "'dataOnly': 1" in c
        assert "'ddlOnly': 0" in c

    def test_schema_only_sets_ddlonly(self):
        c = self._clause(data_only=False, schema_only=True)
        assert "'ddlOnly': 1" in c
        assert "'dataOnly': 0" in c

    def test_partitions_included_for_data_dump(self):
        c = self._clause(partition_map={"txn_fee": ["p2026"]}, schema_only=False)
        assert "partitions" in c

    def test_partitions_omitted_for_schema_only(self):
        c = self._clause(partition_map={"txn_fee": ["p2026"]}, data_only=False, schema_only=True)
        assert "partitions" not in c

    def test_threads_and_chunk_carried(self):
        c = self._clause(threads=16, bytes_per_chunk="128M")
        assert "'threads': 16" in c
        assert "128M" in c


class TestCheckProgramExists:
    def test_known_program(self):
        assert md.check_program_exists("sh") is True

    def test_missing_program(self):
        assert md.check_program_exists("definitely-not-a-real-binary-xyz") is False
