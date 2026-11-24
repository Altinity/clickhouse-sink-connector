// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/TableFilterConfig.java::removeRedundantInclude
// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java::start
// request: explicit exclusion with a redundant universal include (spec 10.08).
// Property: no restrictive include is removed; normalization is idempotent.
datatype Include = Absent | Universal | Restrictive
function Normalize(i: Include, excluded: bool): Include {
  if i == Universal && excluded then Absent else i
}
lemma Contract(i: Include, e: bool)
  ensures Normalize(Universal, true) == Absent
  ensures i == Restrictive ==> Normalize(i, e) == i
  ensures !e ==> Normalize(i, e) == i
  ensures Normalize(Normalize(i, e), e) == Normalize(i, e)
  ensures Normalize(i, e) != i ==> i == Universal && e
{}
