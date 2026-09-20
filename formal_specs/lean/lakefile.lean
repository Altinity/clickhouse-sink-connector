import Lake
open Lake DSL

package «Replication»

@[default_target]
lean_lib «Replication» where
  -- Library configuration exposing the replication proofs.
  roots := #[`Replication.Basic,
             `Replication.Binlog,
             `Replication.ClickHouse,
             `Replication.Engine,
             `Replication.Invariants,
             `Replication.Proofs,
             `Replication.Upgrade,
             `Replication.Snapshot,
             `Replication.GeneratedColumn,
             `Replication.DdlTranslation]
