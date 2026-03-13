# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : column_type_overrides.py
# -- Date         :
# -- Summary      : Column type override configuration for PostgreSQL → ClickHouse
# --                type mapping.  Supports two override modes:
# --
# --   Direct  — replaces the default CH type for a column at CREATE TABLE time.
# --             ClickHouse handles implicit conversion at INSERT time.
# --
# --   Alias   — adds a companion ALIAS column with a user-defined expression.
# --             The original column retains its default mapped type.  The ALIAS
# --             column is computed on read and never included in INSERT statements.
# --
# -- Configuration sources:
# --   1. YAML file  (--column-type-overrides-file /path/to/overrides.yml)
# --   2. CLI string (--column-type-overrides "direct:schema.table.col=Type,...")
# --
"""

import re
import logging
from dataclasses import dataclass, field
from typing import List, Optional

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass
class DirectOverride:
    """A direct type override: replaces the CH column type."""
    database: str     # e.g. "mydb" or "*"
    schema: str       # e.g. "public" or "*"
    table: str        # e.g. "events" or "*"
    column: str       # e.g. "created_at"
    target_type: str  # e.g. "DateTime64(3)"


@dataclass
class AliasOverride:
    """An alias override: adds a companion ALIAS column."""
    column: str        # source column name
    alias_type: str    # CH type for the alias, e.g. "DateTime64(3)"
    expression: str    # CH expression, e.g. "parseDateTime64BestEffort(col)"

    # Database/schema/table context (used for matching)
    database: str = "*"
    schema: str = "*"
    table: str = "*"

    @property
    def alias_column_name(self) -> str:
        """Returns '{column}_{normalized_type}' format.

        Examples:
            column='created_at', alias_type='DateTime64(3)' → 'created_at_datetime64_3_'
            column='amount', alias_type='Float64' → 'amount_float64_'
        """
        return f"{self.column}_{normalize_type_name(self.alias_type)}"


# ---------------------------------------------------------------------------
# Type name normalisation
# ---------------------------------------------------------------------------

def normalize_type_name(ch_type: str) -> str:
    """Normalise a ClickHouse type name for use as a column name suffix.

    Lowercases, replaces non-alphanumeric characters with underscores,
    collapses consecutive underscores, and ensures a single trailing
    underscore to prevent naming collisions.

    Examples:
        'DateTime64(3)'    → 'datetime64_3_'
        'Float64'          → 'float64_'
        'Nullable(String)' → 'nullable_string_'
        'JSON'             → 'json_'
    """
    result = re.sub(r'[^a-z0-9]', '_', ch_type.lower())
    # Collapse consecutive underscores
    result = re.sub(r'_+', '_', result)
    # Strip trailing underscore(s) then add exactly one
    result = result.rstrip('_') + '_'
    return result


# ---------------------------------------------------------------------------
# Configuration class
# ---------------------------------------------------------------------------

class ColumnTypeOverrideConfig:
    """Parsed column type override configuration.

    Provides lookup methods for direct and alias overrides with support
    for exact database.schema.table.column matches and wildcard ('*') fallbacks.
    """

    def __init__(
        self,
        direct_overrides: Optional[List[DirectOverride]] = None,
        alias_overrides: Optional[List[AliasOverride]] = None,
    ):
        self._direct: List[DirectOverride] = direct_overrides or []
        self._alias: List[AliasOverride] = alias_overrides or []

    # -- Lookup methods ----------------------------------------------------

    def get_direct_override(
        self, database: str, schema: str, table: str, column: str
    ) -> Optional[str]:
        """Return the target CH type for a direct override, or None.

        All comparisons are case-insensitive (consistent with the Java code
        which normalises everything to lowercase).

        Matching priority:
          1. Exact database.schema.table.column match
          2. *.schema.table.column match (wildcard database)
          3. *.*.table.column match (wildcard database+schema)
          4. *.*.*.column match (global wildcard)
          5. No match → None
        """
        exact_match = None
        db_wild_match = None
        schema_wild_match = None
        global_wild_match = None

        db_l = database.lower()
        schema_l = schema.lower()
        table_l = table.lower()
        column_l = column.lower()

        for d in self._direct:
            if d.column.lower() != column_l:
                continue

            d_db = d.database.lower()
            d_schema = d.schema.lower()
            d_table = d.table.lower()

            if d_db == db_l and d_schema == schema_l and d_table == table_l:
                exact_match = d.target_type
            elif d_db == '*' and d_schema == schema_l and d_table == table_l:
                db_wild_match = d.target_type
            elif d_db == '*' and d_schema == '*' and d_table == table_l:
                schema_wild_match = d.target_type
            elif d_db == '*' and d_schema == '*' and d_table == '*':
                global_wild_match = d.target_type

        return exact_match or db_wild_match or schema_wild_match or global_wild_match

    def get_alias_overrides(
        self, database: str, schema: str, table: str
    ) -> List[AliasOverride]:
        """Return all alias overrides that match the given database.schema.table.

        All comparisons are case-insensitive.  A match occurs when database,
        schema, and table all match (either exactly or via wildcard '*').
        """
        db_l = database.lower()
        schema_l = schema.lower()
        table_l = table.lower()

        result = []
        for a in self._alias:
            db_match = (a.database.lower() == db_l or a.database == '*')
            schema_match = (a.schema.lower() == schema_l or a.schema == '*')
            table_match = (a.table.lower() == table_l or a.table == '*')
            if db_match and schema_match and table_match:
                result.append(a)
        return result

    def get_direct_overrides(
        self, database: str, schema: str, table: str
    ) -> List[DirectOverride]:
        """Return all direct overrides that match the given database.schema.table.

        All comparisons are case-insensitive.  A match occurs when database,
        schema, and table all match (either exactly or via wildcard '*').
        """
        db_l = database.lower()
        schema_l = schema.lower()
        table_l = table.lower()

        result = []
        for d in self._direct:
            db_match = (d.database.lower() == db_l or d.database == '*')
            schema_match = (d.schema.lower() == schema_l or d.schema == '*')
            table_match = (d.table.lower() == table_l or d.table == '*')
            if db_match and schema_match and table_match:
                result.append(d)
        return result

    def has_overrides(self) -> bool:
        """Return True if any overrides are configured."""
        return bool(self._direct or self._alias)

    @staticmethod
    def normalize_type_name(ch_type: str) -> str:
        """Convenience static method delegating to module-level function."""
        return normalize_type_name(ch_type)

    # -- Factory methods ---------------------------------------------------

    @classmethod
    def from_yaml(cls, path: str) -> 'ColumnTypeOverrideConfig':
        """Load override configuration from a YAML file.

        Expected format:
            column_type_overrides:
              direct:
                - table: "mydb.public.my_table"   # database.schema.table
                  column: "my_column"
                  target_type: "DateTime64(3)"
              alias:
                - table: "mydb.public.my_table"
                  column: "my_column"
                  alias_type: "DateTime64(3)"
                  expression: "parseDateTime64BestEffort(my_column)"

        Backward-compatible: 'schema.table' (2-part) still works with wildcard database.
        """
        import yaml
        with open(path, 'r') as f:
            data = yaml.safe_load(f) or {}

        overrides_data = data.get('column_type_overrides', data)
        return cls._from_dict(overrides_data)

    @classmethod
    def from_cli_string(cls, s: str) -> 'ColumnTypeOverrideConfig':
        """Parse override configuration from a CLI string.

        Format (comma-separated entries):
          direct:<schema>.<table>.<column>=<CHType>
          alias:<schema>.<table>.<column>=<CHType>|<expression>

        Examples:
          "direct:public.my_table.my_column=DateTime64(3)"
          "alias:public.my_table.my_column=DateTime64(3)|parseDateTime64BestEffort(my_column)"
          "direct:public.events.created_at=DateTime64(3),alias:public.events.created_at=DateTime64(3)|parseDateTime64BestEffort(created_at)"
        """
        direct_overrides = []
        alias_overrides = []

        if not s or not s.strip():
            return cls(direct_overrides, alias_overrides)

        entries = s.split(',')
        for entry in entries:
            entry = entry.strip()
            if not entry:
                continue

            if entry.startswith('direct:'):
                d = cls._parse_direct_entry(entry[len('direct:'):])
                if d:
                    direct_overrides.append(d)
            elif entry.startswith('alias:'):
                a = cls._parse_alias_entry(entry[len('alias:'):])
                if a:
                    alias_overrides.append(a)
            else:
                logger.warning(
                    f"column_type_overrides: unknown entry prefix in '{entry}', "
                    f"expected 'direct:' or 'alias:'"
                )

        return cls(direct_overrides, alias_overrides)

    @classmethod
    def from_connector_properties(cls, props: dict) -> Optional['ColumnTypeOverrideConfig']:
        """Parse column type overrides from a Java-style flat property dict.

        This handles the connector config.yml format where keys look like:

        4-part (preferred):
            column_type_override.direct.<database>.<schema>.<table>.<column>: "<CHType>"
            column_type_override.alias.<database>.<schema>.<table>.<column>: "<CHType>|<expression>"

        3-part (backward compat — database defaults to '*'):
            column_type_override.direct.<schema>.<table>.<column>: "<CHType>"
            column_type_override.alias.<schema>.<table>.<column>: "<CHType>|<expression>"

        Args:
            props: a flat dict of connector config properties (e.g. loaded
                   from config.yml via yaml.safe_load).

        Returns:
            a ColumnTypeOverrideConfig if any overrides were found, else None.
        """
        DIRECT_PREFIX = 'column_type_override.direct.'
        ALIAS_PREFIX = 'column_type_override.alias.'

        direct_overrides = []
        alias_overrides = []

        for key, value in props.items():
            key_str = str(key)
            value_str = str(value).strip()

            if key_str.startswith(DIRECT_PREFIX):
                suffix = key_str[len(DIRECT_PREFIX):]
                parts = suffix.split('.')
                if len(parts) == 4:
                    database, schema, table, column = parts
                elif len(parts) == 3:
                    database = '*'
                    schema, table, column = parts
                else:
                    logger.warning(
                        f"column_type_overrides: skipping malformed direct key: '{key_str}'"
                    )
                    continue
                direct_overrides.append(DirectOverride(
                    database=database,
                    schema=schema,
                    table=table,
                    column=column,
                    target_type=value_str,
                ))

            elif key_str.startswith(ALIAS_PREFIX):
                suffix = key_str[len(ALIAS_PREFIX):]
                parts = suffix.split('.')
                if len(parts) == 4:
                    database, schema, table, column = parts
                elif len(parts) == 3:
                    database = '*'
                    schema, table, column = parts
                else:
                    logger.warning(
                        f"column_type_overrides: skipping malformed alias key: '{key_str}'"
                    )
                    continue

                pipe_idx = value_str.find('|')
                if pipe_idx < 0:
                    logger.warning(
                        f"column_type_overrides: alias value missing '|' separator: "
                        f"'{key_str}={value_str}'"
                    )
                    continue

                alias_type = value_str[:pipe_idx].strip()
                expression = value_str[pipe_idx + 1:].strip()

                if not alias_type or not expression:
                    logger.warning(
                        f"column_type_overrides: alias entry has empty type or expression: "
                        f"'{key_str}={value_str}'"
                    )
                    continue

                alias_overrides.append(AliasOverride(
                    column=column,
                    alias_type=alias_type,
                    expression=expression,
                    database=database,
                    schema=schema,
                    table=table,
                ))

        if direct_overrides or alias_overrides:
            config = cls(direct_overrides, alias_overrides)
            logger.info(
                f"Parsed {len(direct_overrides)} direct override(s) and "
                f"{len(alias_overrides)} alias override(s) from connector properties"
            )
            return config
        return None

    @classmethod
    def from_cli_args(
        cls,
        overrides_file: Optional[str] = None,
        overrides_string: Optional[str] = None,
    ) -> Optional['ColumnTypeOverrideConfig']:
        """Create a ColumnTypeOverrideConfig from CLI arguments.

        If both file and string are provided, file takes precedence.
        Returns None if neither is provided.
        """
        if overrides_file:
            logger.info(f"Loading column type overrides from file: {overrides_file}")
            return cls.from_yaml(overrides_file)
        if overrides_string:
            logger.info(f"Parsing column type overrides from CLI string")
            return cls.from_cli_string(overrides_string)
        return None

    # -- Internal parsing helpers ------------------------------------------

    @classmethod
    def _from_dict(cls, data: dict) -> 'ColumnTypeOverrideConfig':
        """Parse overrides from a dict (typically from YAML)."""
        direct_overrides = []
        alias_overrides = []

        for item in (data.get('direct') or []):
            table_spec = item.get('table', '*')
            database, schema, table = cls._split_table_spec(table_spec)
            column = item.get('column')
            target_type = item.get('target_type')
            if column and target_type:
                direct_overrides.append(DirectOverride(
                    database=database,
                    schema=schema,
                    table=table,
                    column=column,
                    target_type=target_type,
                ))
            else:
                logger.warning(
                    f"column_type_overrides: skipping incomplete direct entry: {item}"
                )

        for item in (data.get('alias') or []):
            table_spec = item.get('table', '*')
            database, schema, table = cls._split_table_spec(table_spec)
            column = item.get('column')
            alias_type = item.get('alias_type')
            expression = item.get('expression')
            if column and alias_type and expression:
                alias_overrides.append(AliasOverride(
                    column=column,
                    alias_type=alias_type,
                    expression=expression,
                    database=database,
                    schema=schema,
                    table=table,
                ))
            else:
                logger.warning(
                    f"column_type_overrides: skipping incomplete alias entry: {item}"
                )

        config = cls(direct_overrides, alias_overrides)
        if config.has_overrides():
            logger.info(
                f"Loaded {len(direct_overrides)} direct override(s) "
                f"and {len(alias_overrides)} alias override(s)"
            )
        return config

    @staticmethod
    def _split_table_spec(table_spec: str):
        """Split table spec into (database, schema, table).

        Formats:
          'db.schema.table' → ('db', 'schema', 'table')
          'schema.table'    → ('*', 'schema', 'table')     # backward compat
          'table'           → ('*', '*', 'table')
          '*'               → ('*', '*', '*')
        """
        if table_spec == '*':
            return ('*', '*', '*')
        parts = table_spec.split('.')
        if len(parts) >= 3:
            return (parts[0], parts[1], '.'.join(parts[2:]))
        elif len(parts) == 2:
            return ('*', parts[0], parts[1])
        else:
            return ('*', '*', table_spec)

    @classmethod
    def _parse_direct_entry(cls, entry: str) -> Optional[DirectOverride]:
        """Parse a single direct override from CLI string format.

        Format: <schema>.<table>.<column>=<CHType>
        Example: public.events.created_at=DateTime64(3)
        """
        m = re.match(r'^([^=]+)=(.+)$', entry)
        if not m:
            logger.warning(f"column_type_overrides: invalid direct entry: '{entry}'")
            return None

        qualified_col = m.group(1).strip()
        target_type = m.group(2).strip()

        database, schema, table, column = cls._split_qualified_column(qualified_col)
        if not column:
            logger.warning(
                f"column_type_overrides: could not parse column from '{qualified_col}'"
            )
            return None

        return DirectOverride(
            database=database,
            schema=schema,
            table=table,
            column=column,
            target_type=target_type,
        )

    @classmethod
    def _parse_alias_entry(cls, entry: str) -> Optional[AliasOverride]:
        """Parse a single alias override from CLI string format.

        Format: <schema>.<table>.<column>=<CHType>|<expression>
        Example: public.events.created_at=DateTime64(3)|parseDateTime64BestEffort(created_at)
        """
        m = re.match(r'^([^=]+)=([^|]+)\|(.+)$', entry)
        if not m:
            logger.warning(f"column_type_overrides: invalid alias entry: '{entry}'")
            return None

        qualified_col = m.group(1).strip()
        alias_type = m.group(2).strip()
        expression = m.group(3).strip()

        database, schema, table, column = cls._split_qualified_column(qualified_col)
        if not column:
            logger.warning(
                f"column_type_overrides: could not parse column from '{qualified_col}'"
            )
            return None

        return AliasOverride(
            column=column,
            alias_type=alias_type,
            expression=expression,
            database=database,
            schema=schema,
            table=table,
        )

    @staticmethod
    def _split_qualified_column(qualified: str):
        """Split qualified column into (database, schema, table, column).

        Formats:
          'db.schema.table.column' → ('db', 'schema', 'table', 'column')
          'schema.table.column'    → ('*', 'schema', 'table', 'column')
          'table.column'           → ('*', '*', 'table', 'column')
          'column'                 → ('*', '*', '*', 'column')
        """
        parts = qualified.split('.')
        if len(parts) >= 4:
            return (parts[0], parts[1], parts[2], '.'.join(parts[3:]))
        elif len(parts) == 3:
            return ('*', parts[0], parts[1], parts[2])
        elif len(parts) == 2:
            return ('*', '*', parts[0], parts[1])
        elif len(parts) == 1:
            return ('*', '*', '*', parts[0])
        return ('*', '*', '*', None)

    def __repr__(self):
        return (
            f"ColumnTypeOverrideConfig("
            f"direct={len(self._direct)}, "
            f"alias={len(self._alias)})"
        )
