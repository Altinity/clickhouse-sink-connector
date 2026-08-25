-- Keyless source tables: no PRIMARY KEY and no UNIQUE key, so the connector
-- has to supply the row identity itself.

-- Grows past one row, which is where the original collapse became visible.
create table keyless_basic(a int, b varchar(64));

-- Holds NULLs, including an all-NULL row.
create table keyless_nulls(a int, b varchar(64));

-- Carries binary data. The row identity must depend on the VALUE, not on any
-- per-JVM object identity, or the key changes when the connector restarts.
create table keyless_binary(a int, payload blob);

-- Takes schema changes while replicating: the identity must not freeze the
-- table, and must keep telling rows apart after a column is added.
create table keyless_evolves(a int, b varchar(64));

-- Already declares a column named _row_key, which the generated column must
-- not collide with.
create table keyless_name_clash(_row_key int, v varchar(64));

-- Control: a table WITH a primary key must be untouched by any of this.
create table keyed_control(id int not null primary key, v varchar(64));
