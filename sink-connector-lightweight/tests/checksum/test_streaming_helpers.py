"""Pure guard/helper checks; no Docker daemon or database connections needed."""

import subprocess
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

import test_checksum_replication as checksum
import test_streaming_ordering as streaming


@pytest.fixture
def local_settings(monkeypatch):
    monkeypatch.setattr(streaming.stack, "MYSQL_HOST", "localhost")
    monkeypatch.setattr(streaming.stack, "CLICKHOUSE_HOST", "127.0.0.1")
    monkeypatch.setattr(streaming.stack, "DATABASE", "test")
    monkeypatch.delenv("DOCKER_HOST", raising=False)
    monkeypatch.delenv("DOCKER_CONTEXT", raising=False)


@pytest.mark.parametrize("setting,value", [
    ("MYSQL_HOST", "external.invalid"),
    ("CLICKHOUSE_HOST", "external.invalid"),
    ("DATABASE", "test`other"),
])
def test_preflight_refuses_unsafe_endpoints_before_processes(
        monkeypatch, local_settings, setting, value):
    monkeypatch.setattr(streaming.stack, setting, value)
    process = Mock(side_effect=AssertionError("must not execute Docker"))
    monkeypatch.setattr(streaming, "_process", process)
    with pytest.raises(pytest.fail.Exception):
        streaming._preflight()
    process.assert_not_called()


@pytest.mark.parametrize("variable,value", [
    ("DOCKER_HOST", "tcp://external.invalid:2375"),
    ("DOCKER_HOST", "unix:///other.sock"),
    ("DOCKER_CONTEXT", "other"),
])
def test_preflight_refuses_docker_overrides(monkeypatch, local_settings, variable, value):
    monkeypatch.setenv(variable, value)
    process = Mock(side_effect=AssertionError("must not execute Docker"))
    monkeypatch.setattr(streaming, "_process", process)
    with pytest.raises(pytest.fail.Exception):
        streaming._preflight()
    process.assert_not_called()


def test_preflight_refuses_remote_current_context(monkeypatch, local_settings):
    process = Mock(return_value='[{"Endpoints":{"docker":{"Host":"tcp://external.invalid:2375"}}}]')
    compose = Mock(side_effect=AssertionError("must not inspect remote compose"))
    monkeypatch.setattr(streaming, "_process", process)
    monkeypatch.setattr(streaming, "_compose", compose)
    with pytest.raises(pytest.fail.Exception, match="local Docker daemon"):
        streaming._preflight()
    compose.assert_not_called()


def test_managed_process_is_bounded_and_does_not_echo_output(monkeypatch):
    run = Mock(return_value=SimpleNamespace(returncode=1, stdout="sensitive", stderr="sensitive"))
    monkeypatch.setattr(streaming.subprocess, "run", run)
    with pytest.raises(pytest.fail.Exception) as error:
        streaming._process(["docker", "inspect", "container"])
    assert "sensitive" not in str(error.value)
    assert run.call_args.kwargs["timeout"] == 30


@pytest.mark.parametrize("failure", ["timeout", "exit"])
def test_checksum_subprocess_failure_is_bounded_and_redacted(monkeypatch, failure):
    command = ["python3", "checksum.py", "--password", "sensitive"]
    if failure == "timeout":
        run = Mock(side_effect=subprocess.TimeoutExpired(command, 120,
                                                        output="sensitive", stderr="sensitive"))
    else:
        run = Mock(return_value=SimpleNamespace(returncode=1, stdout="sensitive", stderr="sensitive"))
    monkeypatch.setattr(checksum.subprocess, "run", run)
    with pytest.raises(AssertionError) as error:
        checksum._run_checksum(command)
    assert "sensitive" not in str(error.value)
    assert run.call_args.kwargs["timeout"] == 120


def test_checksum_parser_retains_table_and_nonzero_count(monkeypatch):
    run = Mock(return_value=SimpleNamespace(returncode=0, stderr="", stdout=(
        "noise\nChecksum for table test.owned = ABCDEF count 2\n")))
    monkeypatch.setattr(checksum.subprocess, "run", run)
    assert checksum._run_checksum(["python3", "checksum.py"]) == {"owned": ("abcdef", 2)}


@pytest.mark.parametrize("count,mysql,clickhouse", [
    (0, {"owned": ("aa", 0)}, {"owned": ("aa", 0)}),
    (1, {}, {}),
    (1, {"owned": ("aa", 1)}, {}),
    (1, {"owned": ("aa", 0)}, {"owned": ("aa", 0)}),
    (1, {"owned": ("aa", 1)}, {"owned": ("bb", 1)}),
    (1, {"owned": ("aa", 1), "other": ("aa", 1)}, {"owned": ("aa", 1)}),
    (1, {"owned": ("aa", 1)}, {"owned": ("aa", 1), "other": ("aa", 1)}),
])
def test_checksum_contract_refuses_incomplete_or_different_results(count, mysql, clickhouse):
    with pytest.raises(AssertionError):
        streaming._assert_checksum_results("owned", count, mysql, clickhouse)


def test_checksum_contract_accepts_exact_nonempty_table():
    streaming._assert_checksum_results("owned", 2,
                                       {"owned": ("abcdef", 2)}, {"owned": ("abcdef", 2)})


def test_final_wait_does_not_accept_matching_count_with_wrong_value(monkeypatch):
    query = Mock(side_effect=[[(1, "old")], [(1, "committed")]])
    monkeypatch.setattr(streaming, "_query", query)
    monkeypatch.setattr(streaming.time, "sleep", lambda seconds: None)
    streaming._wait_rows(object(), "`test`.`owned`", "id, value", [(1, "committed")])
    assert query.call_count == 2
    assert "FINAL WHERE is_deleted = 0 ORDER BY id" in query.call_args.args[1]


def test_final_wait_has_a_deadline_for_wrong_values(monkeypatch):
    monkeypatch.setattr(streaming, "_query", Mock(return_value=[(1, "wrong")]))
    monkeypatch.setattr(streaming.time, "monotonic", Mock(side_effect=[0, 0, 121]))
    monkeypatch.setattr(streaming.time, "sleep", lambda seconds: None)
    with pytest.raises(pytest.fail.Exception, match="FINAL values did not match"):
        streaming._wait_rows(object(), "`test`.`owned`", "id, value", [(1, "committed")])


def test_checkpoint_rejects_wrong_source_values_before_checksum(monkeypatch):
    cursor = Mock()
    cursor.fetchall.return_value = ((1, "wrong"),)
    context = Mock()
    context.__enter__ = Mock(return_value=cursor)
    context.__exit__ = Mock(return_value=False)
    connection = Mock()
    connection.cursor.return_value = context
    checksum_call = Mock(side_effect=AssertionError("must not checksum unexpected source"))
    monkeypatch.setattr(streaming, "_mysql_checksums", checksum_call)
    with pytest.raises(AssertionError, match="Unexpected committed MySQL values"):
        streaming._checkpoint(("owned", "`test`.`owned`", connection, object()),
                              [(1, "committed")])
    checksum_call.assert_not_called()
