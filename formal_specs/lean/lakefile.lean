import Lake
open Lake DSL

package «Replication» where
  -- Package configuration for MySQL to ClickHouse formal replication verification
  version := v!"2.11.0"
  keywords := #["replication", "clickhouse", "mysql", "formal-verification", "cdc"]

lean_lib «Replication» where
  -- Library configuration exposing foundational replication proofs
  roots := #[`Replication.Basic,
             `Replication.Binlog,
             `Replication.ClickHouse,
             `Replication.Engine,
             `Replication.Invariants,
             `Replication.Proofs]
