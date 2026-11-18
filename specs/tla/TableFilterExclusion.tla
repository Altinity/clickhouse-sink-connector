---------------------- MODULE TableFilterExclusion ----------------------
\* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/TableFilterConfig.java::removeRedundantInclude
\* spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java::start
\* request: exclusion with universal include; exhaust all 3 include kinds x 2 exclusion states.
EXTENDS TLC
VARIABLES inc, excluded
vars == <<inc, excluded>>
Normalize(i, e) == IF i = "universal" /\ e THEN "absent" ELSE i
Init == /\ inc \in {"absent", "universal", "restrictive"}
        /\ excluded \in BOOLEAN
Next == UNCHANGED vars
ExclusionWorks == (inc = "universal" /\ excluded) => Normalize(inc, excluded) = "absent"
NoBroadening == inc = "restrictive" => Normalize(inc, excluded) = inc
NoExclusionUnchanged == ~excluded => Normalize(inc, excluded) = inc
OnlyRedundantChanges == Normalize(inc, excluded) # inc => (inc = "universal" /\ excluded)
Idempotent == Normalize(Normalize(inc, excluded), excluded) = Normalize(inc, excluded)
=============================================================================
