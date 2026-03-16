"""
Comprehensive tests for the naming template engine (naming.py)
and filter_tables_by_regex (postgres_dumper.py).
"""

import pytest

from ch_sink_tools.db_dump.naming import (
    VALID_VARS,
    render_template,
    resolve_ch_names,
    validate_template,
)
from ch_sink_tools.db_dump.postgres_dumper import filter_tables_by_regex


# ── render_template() ─────────────────────────────────────────────────────


class TestRenderTemplate:
    def test_simple_variable(self):
        assert render_template("{{ database }}", {"database": "app"}) == "app"

    def test_static_string(self):
        assert render_template("litellm_dev", {"database": "app"}) == "litellm_dev"

    def test_variable_with_suffix(self):
        assert render_template("{{ database }}__prod", {"database": "app"}) == "app__prod"

    def test_variable_with_prefix(self):
        assert render_template("staging_{{ database }}", {"database": "app"}) == "staging_app"

    def test_variable_with_prefix_and_suffix(self):
        assert render_template("pre_{{ database }}_post", {"database": "app"}) == "pre_app_post"

    def test_multiple_variables(self):
        ctx = {"schema": "public", "table": "users"}
        assert render_template("{{ schema }}___{{ table }}", ctx) == "public___users"

    def test_all_three_variables(self):
        ctx = {"database": "app", "schema": "public", "table": "users"}
        assert render_template("{{ database }}.{{ schema }}.{{ table }}", ctx) == "app.public.users"

    def test_unknown_variable_left_intact(self):
        assert render_template("{{ unknown }}", {"database": "app"}) == "{{ unknown }}"

    def test_whitespace_in_braces(self):
        assert render_template("{{database}}", {"database": "app"}) == "app"
        assert render_template("{{  database  }}", {"database": "app"}) == "app"

    def test_empty_context(self):
        assert render_template("{{ database }}", {}) == "{{ database }}"

    def test_empty_template(self):
        assert render_template("", {"database": "app"}) == ""

    def test_no_variables_in_template(self):
        assert render_template("static_name", {}) == "static_name"

    def test_repeated_variable(self):
        assert render_template("{{ database }}_{{ database }}", {"database": "app"}) == "app_app"


# ── validate_template() ───────────────────────────────────────────────────


class TestValidateTemplate:
    def test_valid_database_template(self):
        validate_template("{{ database }}", "db_template")  # Should not raise

    def test_valid_static_template(self):
        validate_template("litellm_dev", "db_template")  # Should not raise

    def test_valid_table_template_with_required(self):
        validate_template("{{ table }}", "table_template", frozenset({"table"}))

    def test_valid_schema_table_template(self):
        validate_template("{{ schema }}___{{ table }}", "table_template", frozenset({"table"}))

    def test_unknown_variable_raises(self):
        with pytest.raises(ValueError, match="unknown"):
            validate_template("{{ foo }}", "test_template")

    def test_multiple_unknown_variables_raises(self):
        with pytest.raises(ValueError, match="unknown"):
            validate_template("{{ foo }}_{{ bar }}", "test_template")

    def test_missing_required_variable_raises(self):
        with pytest.raises(ValueError, match="must contain"):
            validate_template("static_name", "table_template", frozenset({"table"}))

    def test_required_var_present_passes(self):
        validate_template("{{ table }}", "table_template", frozenset({"table"}))

    def test_static_db_template_no_required_ok(self):
        validate_template("litellm_dev", "db_template")  # No required vars, static OK

    def test_valid_vars_constant(self):
        assert VALID_VARS == frozenset({"database", "schema", "table"})


# ── resolve_ch_names() ────────────────────────────────────────────────────


class TestResolveCHNames:
    def test_passthrough(self):
        ch_db, ch_table = resolve_ch_names("app", "public", "users", "{{ database }}", "{{ table }}")
        assert ch_db == "app"
        assert ch_table == "users"

    def test_static_database(self):
        ch_db, ch_table = resolve_ch_names("app", "public", "users", "litellm_dev", "{{ table }}")
        assert ch_db == "litellm_dev"
        assert ch_table == "users"

    def test_database_with_suffix(self):
        ch_db, ch_table = resolve_ch_names("app", "public", "users", "{{ database }}__prod", "{{ table }}")
        assert ch_db == "app__prod"
        assert ch_table == "users"

    def test_table_with_schema_prefix(self):
        ch_db, ch_table = resolve_ch_names("app", "public", "users", "{{ database }}", "{{ schema }}___{{ table }}")
        assert ch_db == "app"
        assert ch_table == "public___users"

    def test_complex_mapping(self):
        ch_db, ch_table = resolve_ch_names("mydb", "analytics", "events", "{{ database }}_prod", "{{ schema }}_{{ table }}")
        assert ch_db == "mydb_prod"
        assert ch_table == "analytics_events"

    def test_all_static(self):
        ch_db, ch_table = resolve_ch_names("app", "public", "users", "target_db", "target_table")
        assert ch_db == "target_db"
        assert ch_table == "target_table"


# ── filter_tables_by_regex() ──────────────────────────────────────────────


class TestFilterTablesByRegex:
    def test_no_filters(self):
        tables = ["users", "orders", "products"]
        assert filter_tables_by_regex(tables) == tables

    def test_include_pattern(self):
        tables = ["users", "orders", "products", "temp_backup"]
        result = filter_tables_by_regex(tables, include_pattern="users|orders")
        assert result == ["users", "orders"]

    def test_exclude_pattern(self):
        tables = ["users", "orders", "temp_backup", "old_data"]
        result = filter_tables_by_regex(tables, exclude_pattern="temp_.*|old_.*")
        assert result == ["users", "orders"]

    def test_include_and_exclude(self):
        tables = ["users", "orders", "temp_users", "products"]
        result = filter_tables_by_regex(tables, include_pattern="users|orders|temp_users", exclude_pattern="temp_.*")
        assert result == ["users", "orders"]

    def test_no_match_returns_empty(self):
        tables = ["users", "orders"]
        result = filter_tables_by_regex(tables, include_pattern="nonexistent")
        assert result == []

    def test_empty_list(self):
        assert filter_tables_by_regex([]) == []

    def test_regex_anchoring(self):
        tables = ["users", "user_settings", "power_users"]
        result = filter_tables_by_regex(tables, include_pattern="^users$")
        assert result == ["users"]
