import Lake
open Lake DSL

package «Replication»

-- `@[default_target]` is what makes a bare `lake build` (the command every spec
-- and README cites) actually compile the library. Without it Lake has no
-- default target, prints "Build completed successfully" and builds NOTHING, so
-- the "machine-checked" claim rested on `lake build Replication` being typed
-- explicitly.
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
             `Replication.DdlBarrier,
             `Replication.OffsetFifo,
             `Replication.DdlTranslation,
             `Replication.BatchOrder]
