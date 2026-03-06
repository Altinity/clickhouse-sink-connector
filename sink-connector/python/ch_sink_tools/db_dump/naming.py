"""
Template engine for PostgreSQL → ClickHouse name mapping.

PostgreSQL uses a 3-level naming hierarchy (database → schema → table),
while ClickHouse uses a 2-level hierarchy (database → table).  This module
provides pure functions that render Jinja-style ``{{ variable }}`` templates
to bridge the two, allowing flexible control over how PostgreSQL names are
mapped to ClickHouse names.

Supported template variables:
    - ``database`` – the PostgreSQL database name
    - ``schema``   – the PostgreSQL schema name
    - ``table``    – the PostgreSQL table name

Example usage::

    >>> render_template("{{ database }}__prod", {"database": "app"})
    'app__prod'
    >>> resolve_ch_names("app", "public", "users",
    ...                  "{{ database }}", "{{ schema }}___{{ table }}")
    ('app', 'public___users')
"""

import re
from typing import Dict, FrozenSet, Tuple

# ---------------------------------------------------------------------------
# Module-level constants
# ---------------------------------------------------------------------------

_TEMPLATE_VAR_RE = re.compile(r'\{\{\s*(\w+)\s*\}\}')
"""Regex that matches ``{{ var }}`` placeholders with optional inner whitespace."""

VALID_VARS: FrozenSet[str] = frozenset({'database', 'schema', 'table'})
"""The only variable names that may appear inside template placeholders."""


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def render_template(template: str, context: Dict[str, str]) -> str:
    """Render a Jinja-style template string using *context*.

    Each ``{{ var }}`` placeholder whose variable name is present in *context*
    is replaced with the corresponding value.  Placeholders that reference
    variables **not** in *context* are left intact (i.e. the literal
    ``{{ var }}`` text is preserved).

    Args:
        template: The template string, e.g. ``"{{ database }}__prod"``.
        context:  A mapping of variable names to their replacement values.

    Returns:
        The rendered string with known placeholders substituted.

    Examples:
        >>> render_template("{{ database }}", {"database": "app"})
        'app'
        >>> render_template("{{ unknown }}", {"database": "app"})
        '{{ unknown }}'
        >>> render_template("litellm_dev", {"database": "app"})
        'litellm_dev'
    """

    def _replacer(match: re.Match) -> str:
        var_name = match.group(1)
        if var_name in context:
            return context[var_name]
        return match.group(0)  # leave placeholder intact

    return _TEMPLATE_VAR_RE.sub(_replacer, template)


def validate_template(
    template: str,
    name: str,
    required_vars: FrozenSet[str] = frozenset(),
) -> None:
    """Validate a template string at startup time.

    Ensures that every placeholder variable used in *template* is one of the
    recognised :data:`VALID_VARS`.  Optionally checks that at least one of
    *required_vars* appears in the template.

    A purely static string (no placeholders at all) is considered valid unless
    *required_vars* demands the presence of at least one variable.

    Args:
        template:      The template string to validate.
        name:          A human-readable name for the template (used in error
                       messages, e.g. ``"db_template"``).
        required_vars: If non-empty, at least one of these variables must
                       appear in the template.

    Raises:
        ValueError: If the template contains unknown variables or is missing
                    all of the *required_vars*.

    Examples:
        >>> validate_template("{{ database }}", "db_template")  # OK
        >>> validate_template("{{ foo }}", "db_template")
        Traceback (most recent call last):
            ...
        ValueError: Template 'db_template' contains unknown variable(s): ...
    """
    found_vars = set(_TEMPLATE_VAR_RE.findall(template))

    # Check for unknown variables.
    unknown = found_vars - VALID_VARS
    if unknown:
        raise ValueError(
            f"Template '{name}' contains unknown variable(s): {unknown}. "
            f"Valid variables: {VALID_VARS}"
        )

    # Check that at least one required variable is present.
    if required_vars and not (found_vars & required_vars):
        raise ValueError(
            f"Template '{name}' must contain at least one of: {required_vars}"
        )


def resolve_ch_names(
    pg_database: str,
    pg_schema: str,
    pg_table: str,
    db_template: str,
    table_template: str,
) -> Tuple[str, str]:
    """Render both the database and table templates for a PostgreSQL object.

    Builds a context dict from the PostgreSQL identifiers and renders
    *db_template* and *table_template* against it.

    Args:
        pg_database:    PostgreSQL database name.
        pg_schema:      PostgreSQL schema name.
        pg_table:       PostgreSQL table name.
        db_template:    Template for the ClickHouse database name.
        table_template: Template for the ClickHouse table name.

    Returns:
        A ``(ch_database, ch_table)`` tuple of rendered strings.

    Examples:
        >>> resolve_ch_names("app", "public", "users",
        ...                  "{{ database }}", "{{ table }}")
        ('app', 'users')
        >>> resolve_ch_names("app", "public", "users",
        ...                  "{{ database }}__prod",
        ...                  "{{ schema }}___{{ table }}")
        ('app__prod', 'public___users')
    """
    ctx: Dict[str, str] = {
        "database": pg_database,
        "schema": pg_schema,
        "table": pg_table,
    }
    return (render_template(db_template, ctx), render_template(table_template, ctx))
