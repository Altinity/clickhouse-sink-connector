# sink-connector/python/db_load/postgres_parser/__init__.py
#
# Public re-export of the top-level parse API.
# Generated ANTLR files live alongside this package init.

from .postgres_parser import parse_postgres_ddl

__all__ = ["parse_postgres_ddl"]
