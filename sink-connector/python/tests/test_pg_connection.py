"""
get_postgres_connection() on pg8000 (BSD-3), which replaced psycopg2 (LGPL-3.0)
for Apache-2.0 compliance (doc/licensing.md).

pg8000's ``timeout=`` is handed to socket.create_connection() and stays the
socket timeout for every later read; psycopg2's ``connect_timeout`` bounded
the connect only. These tests pin that the 20 s connect bound is kept and
then cleared, so a long checksum query is not cut off, and that the startup
options psycopg2 sent are still sent.
"""

import socket
import threading
import time
from unittest.mock import MagicMock, patch

import pytest

from ch_sink_tools.db import postgres


def _connect_with(conn):
    with patch.object(postgres.pg8000, "connect", return_value=conn) as connect:
        result = postgres.get_postgres_connection("h", "u", "p", "5432", "db")
    return result, connect


def test_connect_arguments_match_the_old_psycopg2_call():
    conn = MagicMock()
    _, connect = _connect_with(conn)
    kwargs = connect.call_args.kwargs
    assert kwargs["host"] == "h"
    assert kwargs["user"] == "u"
    assert kwargs["password"] == "p"
    assert kwargs["port"] == 5432
    assert kwargs["database"] == "db"
    assert kwargs["timeout"] == 20
    assert kwargs["startup_params"] == {"options": "-c statement_timeout=0"}


def test_connect_timeout_is_cleared_after_connecting():
    conn = MagicMock()
    result, _ = _connect_with(conn)
    conn._usock.settimeout.assert_called_once_with(None)
    assert result is conn
    assert conn.autocommit is True


def test_missing_socket_attribute_fails_loudly_and_closes():
    conn = MagicMock(spec=["close", "autocommit"])
    with pytest.raises(RuntimeError, match="_usock"):
        _connect_with(conn)
    conn.close.assert_called_once_with()


def test_a_real_socket_from_create_connection_keeps_its_timeout_unless_cleared():
    """The premise, on a real socket: create_connection(timeout) leaves the
    timeout on the socket, and settimeout(None) makes reads block again."""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]
    accepted = []
    t = threading.Thread(target=lambda: accepted.append(srv.accept()[0]))
    t.start()
    try:
        s = socket.create_connection(("127.0.0.1", port), 0.2)
        t.join(5)
        assert s.gettimeout() == 0.2
        start = time.monotonic()
        with pytest.raises(socket.timeout):
            s.recv(1)
        assert time.monotonic() - start < 5
        s.settimeout(None)
        assert s.gettimeout() is None
        s.close()
    finally:
        for a in accepted:
            a.close()
        srv.close()
