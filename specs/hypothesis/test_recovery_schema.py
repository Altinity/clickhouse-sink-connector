"""Execute the real Java recovery decision (spec 01.11).

spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/RecoverySchemaPolicy.java::needsSnapshot
spec-for: sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/HistoryPreservingRecoverySnapshotter.java::shouldSnapshotSchema
request: preserve recovered history at a retained streaming checkpoint.
ENV-SETUP: Python virtual environment with pytest and hypothesis; set JAVA_HOME to Java 17.
JDK: Java 17, as required by pom.xml.
"""
from __future__ import annotations

import os
from pathlib import Path
import subprocess

from hypothesis import given, settings, strategies as st
import pytest

ROOT = Path(__file__).resolve().parents[2]
POLICY_SOURCE = "sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/RecoverySchemaPolicy.java"
SNAPSHOTTER_SOURCE = "sink-connector-lightweight/src/main/java/io/debezium/snapshot/mode/HistoryPreservingRecoverySnapshotter.java"
BRIDGE = """
package io.debezium.snapshot.mode;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class RecoveryProbe {
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            System.out.println(RecoverySchemaPolicy.needsSnapshot(
                    line.charAt(0) == '1', line.charAt(1) == '1', line.charAt(2) == '1'));
        }
    }
}
"""


@pytest.fixture(scope="module")
def java_probe(tmp_path_factory):
    # Require an explicit JDK location for real Java execution.
    prefix = Path(os.environ.get("JAVA_PREFIX") or os.environ.get("JAVA_HOME")
                  or os.environ["JAVA_HOME"])
    java, javac = prefix / "bin/java", prefix / "bin/javac"
    assert java.is_file() and javac.is_file(), "real Java execution requires the JDK"
    work = tmp_path_factory.mktemp("recovery-schema-java")
    bridge = work / "RecoveryProbe.java"
    bridge.write_text(BRIDGE)
    subprocess.run([str(javac), "-d", str(work), str(ROOT / POLICY_SOURCE),
                    str(bridge)], check=True, capture_output=True, text=True, timeout=8)
    return java, work


@settings(max_examples=12, derandomize=True, database=None, deadline=None)
@given(st.lists(st.tuples(st.booleans(), st.booleans(), st.booleans()), min_size=1, max_size=64))
def test_actual_java_only_skips_a_safe_snapshot(java_probe, cases):
    java, work = java_probe
    output = subprocess.run([str(java), "-cp", str(work), "io.debezium.snapshot.mode.RecoveryProbe"],
                            input="".join("".join("1" if bit else "0" for bit in case) + "\n"
                                          for case in cases),
                            check=True, capture_output=True, text=True, timeout=3)
    rows = output.stdout.splitlines()
    assert len(rows) == len(cases) > 0
    for (offset, running, restored), row in zip(cases, rows, strict=True):
        assert (row == "false") == (offset and not running and restored)


def test_snapshotter_uses_restored_history_and_the_real_policy():
    source = (ROOT / SNAPSHOTTER_SOURCE).read_text()
    assert "StandardBeanNames.DATABASE_SCHEMA" in source
    assert "historical.tableInformationComplete()" in source
    assert "RecoverySchemaPolicy.needsSnapshot(offsetExists, snapshotInProgress, restoredTables)" in source
