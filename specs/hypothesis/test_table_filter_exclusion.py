"""Execute the real Java normalizer over generated property maps.

spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/TableFilterConfig.java::removeRedundantInclude
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java::start
request: allow a table exclusion with the universal include (spec 10.08).
ENV-SETUP: Python virtual environment with pytest and hypothesis; set JAVA_HOME to Java 17.
JDK: Java 17, matching the project requirement.
Use JAVA_PREFIX or JAVA_HOME to select the JDK.
A missing JDK fails the test rather than skipping real Java execution.
Compile once, then one bounded JVM per generated batch, not per case. This
executes Java, not a Python copy; the companion JUnit tests use Debezium's
real validator and table predicate. A startup source check binds the hook.
"""
from __future__ import annotations

import base64
import os
from pathlib import Path
import subprocess

from hypothesis import given, settings, strategies as st
import pytest

SOURCE = "sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/TableFilterConfig.java"
STARTUP = "sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java"
ROOT = Path(__file__).resolve().parents[2]

BRIDGE = r'''
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.altinity.clickhouse.debezium.embedded.config.TableFilterConfig;
public class TableFilterProbe {
  static String decode(String s) {
    return s.equals("-") ? null : new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
  }
  static String encode(String s) {
    return s == null ? "-" : Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }
  public static void main(String[] args) throws Exception {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      String[] values = line.split("\\|", -1);
      Properties p = new Properties();
      String include = decode(values[0]), exclude = decode(values[1]);
      if (include != null) p.setProperty("include", include);
      if (exclude != null) p.setProperty("exclude", exclude);
      p.setProperty("unrelated", "preserved");
      boolean changed = TableFilterConfig.removeRedundantInclude(p, "include", "exclude");
      boolean again = TableFilterConfig.removeRedundantInclude(p, "include", "exclude");
      if (again || !"preserved".equals(p.getProperty("unrelated"))) throw new AssertionError();
      System.out.println(changed + "|" + encode(p.getProperty("include")) + "|" + encode(p.getProperty("exclude")));
    }
  }
}
'''


@pytest.fixture(scope="module")
def java_probe(tmp_path_factory):
    # Require an explicit JDK location for real Java execution.
    prefix = Path(os.environ.get("JAVA_PREFIX") or os.environ.get("JAVA_HOME")
                  or os.environ["JAVA_HOME"])
    javac = str(prefix / "bin" / "javac")
    java = str(prefix / "bin" / "java")
    assert Path(javac).is_file() and Path(java).is_file(), "a JDK is required for real Java execution"
    work = tmp_path_factory.mktemp("table-filter-java")
    bridge = work / "TableFilterProbe.java"
    bridge.write_text(BRIDGE)
    subprocess.run([javac, "-d", str(work), str(ROOT / SOURCE), str(bridge)],
                   check=True, capture_output=True, text=True, timeout=8)
    return java, work


def encode(value):
    return "-" if value is None else base64.b64encode(value.encode()).decode()


def decode(value):
    return None if value == "-" else base64.b64decode(value).decode()


OPTION = st.one_of(st.none(), st.sampled_from([
    "", " ", "\t\n", ".*", " .* ", "\t.*\n", "app\\.kept", "app\\..*",
    "app\\.blocked", "[", "a,b", " ", " .* ",
]), st.text(alphabet="abc.*\\[] ,\t", max_size=30))


@settings(max_examples=12, derandomize=True, database=None, deadline=None)
@given(st.lists(st.tuples(OPTION, OPTION), min_size=1, max_size=32))
def test_actual_java_preserves_scope_and_exclusion(java_probe, cases):
    java, work = java_probe
    result = subprocess.run([java, "-cp", str(work), "TableFilterProbe"],
                            input="".join(encode(i) + "|" + encode(e) + "\n" for i, e in cases),
                            text=True, capture_output=True, check=True, timeout=3)
    rows = result.stdout.splitlines()
    assert len(rows) == len(cases) > 0
    for (include, exclude), row in zip(cases, rows):
        changed, actual_include, actual_exclude = row.split("|")
        # Java trim strips code points <= U+0020; isBlank uses Character whitespace.
        universal = include is not None and include.strip(" \t\n\r\f\v") == ".*"
        active = exclude is not None and bool(exclude.strip())
        expected_change = universal and active
        assert (changed == "true") == expected_change
        assert decode(actual_include) == (None if expected_change else include)
        assert decode(actual_exclude) == exclude


def test_all_application_starts_normalize_after_reload_before_capture():
    source = (ROOT / STARTUP).read_text()
    method = source[source.index("public static void start("):source.index("public static void stop()")]
    assert method.index("loadPropertiesFile(configurationFile)") < method.index("TableFilterConfig.removeRedundantInclude")
    assert method.index("TableFilterConfig.removeRedundantInclude") < method.index("new DebeziumChangeEventCapture()")
    assert "RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST.name()" in method
    assert "RelationalDatabaseConnectorConfig.TABLE_EXCLUDE_LIST.name()" in method
    assert 'log.warn("Removed redundant table.include.list=.*' in method
