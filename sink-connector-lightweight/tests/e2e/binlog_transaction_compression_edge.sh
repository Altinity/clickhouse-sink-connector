#!/usr/bin/env bash
# Edge cases for MySQL binlog transaction compression through the lightweight sink connector that the
# basic / hard / large / chaos harnesses do not reach (spec 01.08 sections 5 and 6), value level.
#
#   JAR=<lightweight jar> [GTID=on|off] [LABEL=<tag>] [VIA_REPLICA=0|1] [HEAP=4g] [CASES="..."] \
#   [IMAGE=<image providing java 17>] [WORK=<scratch dir>] tests/e2e/binlog_transaction_compression_edge.sh
#   Needs podman or docker (the script calls `docker`) and the images mysql:8.0,
#   clickhouse/clickhouse-server:24.8 and eclipse-temurin:17-jre.
#
# Source: MySQL 8.0, binlog_transaction_compression=ON (level 3), GTID per GTID. VIA_REPLICA=1 puts a
# MySQL replica between the source and the connector (log_replica_updates=ON, compression OFF on the
# replica): the connector then reads the REPLICA's binlog, which carries the source's payloads as
# received (a replica does not decompress and re-log them), so every case runs through a replica hop.
# Cases (each asserted per key and by whole-table value-level CRC):
#   E1  snapshot while compressed transactions are committing: initial snapshot of a table taking
#       concurrent compressed writes, including one 300k-row payload committed during the snapshot
#   E2  a session with binlog_row_value_options=PARTIAL_JSON (NOT SUPPORTED, doc/limitations.md): JSON_SET /
#       JSON_REPLACE / JSON_REMOVE partial updates inside a compressed payload and in an uncompressed
#       transaction. Debezium drops Update_rows_partial, so the whole row update is lost silently; the case
#       asserts the documented behaviour (so a change in it is noticed) and the documented repair
#       (re-synchronise the table) (spec 01.10 section 3.1)
#   E3  XA transactions: XA START ... XA PREPARE ... XA COMMIT, one XA ROLLBACK after PREPARE, one
#       XA COMMIT ONE PHASE. The rolled-back rows were replicated silently; the connector must now report
#       the rollback at ERROR naming the table and keep replicating; the re-synchronisation then converges
#   E4  one payload touching 300 tables (a TABLE_MAP per table inside one payload), then a second payload
#       updating all of them
#   E5  one row of E5_MB (256) MiB (compressible LONGBLOB) inside a compressed payload, updated in the same
#       transaction: the largest single inner event the decoder must hold, with a connector heap of HEAP
#   E6  flood of 160,000 single-row compressed transactions from 16 sessions: throughput, and the
#       connector's resident memory before and after (a native-memory leak in the zstd streams would show)
#   E7  a payload mixing tables of an included and an excluded database (database.include.list)
#   E8  binlog purged while the connector is down: the connector must fail LOUDLY (an ERROR/FATAL from this
#       start, or an exit -- never skip silently); the recovery path (reposition + re-synchronise, spec 11.04)
#       is then exercised with the ClickHouse mysql() table function standing in for ch-mysql-resync, the
#       reposition must keep the replica's existing rows (enable.snapshot.ddl=false), and the replica must converge
# Output ends with "RESULT PASS=n FAIL=m"; exit status 0 only when FAIL=0 and at least one check ran.
set -uo pipefail
JAR="${JAR:?set JAR}"; GTID="${GTID:-on}"; LABEL="${LABEL:-bce}"; VIA_REPLICA="${VIA_REPLICA:-0}"; HEAP="${HEAP:-4g}"
CASES="${CASES:-E1 E2 E3 E4 E5 E6 E7 E8}"
IMAGE="${IMAGE:-docker.io/eclipse-temurin:17-jre}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
TEMPLATE="${TEMPLATE:-$REPO/sink-connector-lightweight/docker/config.yml}"; LOG4J="${LOG4J:-$REPO/sink-connector-lightweight/docker/log4j2.xml}"
WORK="${WORK:-/tmp/csc-e2e-bce-$LABEL}"; mkdir -p "$WORK"
POD=cscbce-$LABEL; MY=mysql-bce-$LABEL; RP=replica-bce-$LABEL; CH=ch-bce-$LABEL; CC=csc-bce-$LABEL
MEX(){ docker exec -i $MY mysql -uroot -proot "$@"; }
REX(){ docker exec -i $RP mysql -uroot -proot -P3307 -h127.0.0.1 "$@"; }
CEX(){ docker exec -i $CH clickhouse-client -u root --password root "$@"; }
log(){ echo "[$(date +%H:%M:%S)] $*"; }
PASS=0; FAIL=0
ok(){ echo "PASS  $*"; PASS=$((PASS+1)); }
bad(){ echo "FAIL  $*"; FAIL=$((FAIL+1)); }
expect(){ if [ "$2" = "$3" ]; then ok "$1 = $3"; else bad "$1: got [$2] expected [$3]"; fi; }
myq(){ MEX -N test -e "$1" 2>/dev/null | tr -d '[:space:]'; }
chq(){ CEX -q "$1" 2>/dev/null | tr -d '[:space:]'; }
now(){ date +%s; }
conn_mem(){ docker stats --no-stream --format '{{.MemUsage}}' $CC 2>/dev/null | head -n 1 | awk '{print $1}'; }
running(){ [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }
case_on(){ case " $CASES " in *" $1 "*) return 0;; *) return 1;; esac; }
vt(){
  local tbl="$1" mcols="$2" ccols="$3" db="${4:-test}" mrows crows msum csum
  mrows=$(MEX -N "$db" -e "SELECT count(*) FROM $tbl" 2>/dev/null | tr -d '[:space:]')
  msum=$(MEX -N "$db" -e "SELECT IFNULL(SUM(CRC32(CONCAT_WS('~',$mcols))),0) FROM $tbl" 2>/dev/null | tr -d '[:space:]')
  crows=$(chq "SELECT count() FROM $db.$tbl FINAL WHERE is_deleted=0")
  csum=$(chq "SELECT toInt64(ifNull(sum(CRC32(concat_ws('~',$ccols))),0)) FROM $db.$tbl FINAL WHERE is_deleted=0")
  if [ -z "$mrows" ] || [ -z "$crows" ]; then bad "$tbl: a side could not be queried (mysql_rows=[$mrows] target_rows=[$crows])"; return; fi
  if [ -n "$msum" ] && [ "$mrows" = "$crows" ] && [ "$msum" = "$csum" ]; then ok "$tbl value-level (rows=$mrows)";
  else bad "$tbl mysql_rows=$mrows target_rows=$crows mysql_crc=$msum target_crc=$csum"
    echo "  MySQL:"; MEX -N "$db" -e "SELECT $mcols FROM $tbl ORDER BY 1 LIMIT 5" 2>/dev/null | cut -c1-200
    echo "  CH   :"; CEX -q "SELECT $ccols FROM $db.$tbl FINAL WHERE is_deleted=0 ORDER BY 1 LIMIT 5" 2>/dev/null | cut -c1-200
  fi
}
# wait until the replica's live count of a table equals the source's; $3 timeout (s)
converge(){
  local tbl="$1" db="${2:-test}" limit="${3:-300}" t0 m c
  t0=$(now)
  while [ $(( $(now) - t0 )) -lt "$limit" ]; do
    m=$(MEX -N "$db" -e "SELECT count(*) FROM $tbl" 2>/dev/null | tr -d '[:space:]'); c=$(chq "SELECT count() FROM $db.$tbl FINAL WHERE is_deleted=0")
    [ -n "$m" ] && [ "$m" = "$c" ] && { sleep 6; c=$(chq "SELECT count() FROM $db.$tbl FINAL WHERE is_deleted=0"); [ "$m" = "$c" ] && return 0; }
    sleep 3
  done
  log "$db.$tbl did not converge in $limit s (mysql=$m replica=$c)"; return 1
}
start_connector(){
  docker run -d --name $CC --pod $POD --runtime runc \
    -v "$WORK/config.yml:/config.yml:ro,Z" -v "$LOG4J:/log4j2.xml:ro,Z" -v "$JAR:/app.jar:ro,Z" \
    --entrypoint sh "$IMAGE" -c \
    "java -Xms$HEAP -Xmx$HEAP -XX:+ExitOnOutOfMemoryError -Dlog4j2.configurationFile=log4j2.xml -jar /app.jar /config.yml com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication" \
    >/dev/null 2>&1 && log "connector started ($CC, heap $HEAP)"
}
# Re-synchronise one table from the source (the recovery of spec 11.04; the harness uses ClickHouse's
# mysql() table function as a stand-in for ch-mysql-resync): every source row is written with a version
# above anything the connector wrote, and every replica key the source no longer has gets a tombstone.
resync_table(){
  local tbl="$1" cols="$2" v
  v="toUInt64(greatest(ifNull((SELECT max(_version) FROM test.$tbl), 0), toUInt64(toUnixTimestamp64Micro(now64(6))) * 1000) + 1000)"
  CEX -q "INSERT INTO test.$tbl ($cols, _version, is_deleted) SELECT $cols, $v, 0 FROM mysql('127.0.0.1:$CPORT', 'test', '$tbl', 'root', 'root')" 2>&1 | head -n 2
  CEX -q "INSERT INTO test.$tbl ($cols, _version, is_deleted) SELECT $cols, $v, 1 FROM test.$tbl FINAL WHERE is_deleted = 0 AND id NOT IN (SELECT id FROM mysql('127.0.0.1:$CPORT', 'test', '$tbl', 'root', 'root'))" 2>&1 | head -n 2
}
# Reposition the connector to the source's current position: the offset store is cleared and the next
# start takes a schema-only snapshot (the harness stands in for `change_replication_source` to the next
# transaction; valid only because nothing else is in flight at this point of the run).
reposition_to_current(){
  CEX -q "TRUNCATE TABLE altinity_sink_connector.replica_source_info" 2>/dev/null
  sed -i 's/snapshot.mode: "initial"/snapshot.mode: "schema_only"/' "$WORK/config.yml"
  # With enable.snapshot.ddl=true (this repository's docker default) the schema-only start executes Debezium's
  # DROP TABLE IF EXISTS + CREATE TABLE for every captured table and EMPTIES the replica (measured: 300,339 live
  # rows -> 0). A reposition of an existing replica must run with it off, as the production template does.
  sed -i 's/enable.snapshot.ddl: "true"/enable.snapshot.ddl: "false"/' "$WORK/config.yml"
}

cleanup(){ for c in $CC $RP $MY $CH; do docker rm -f "$c" >/dev/null 2>&1; done; docker pod rm -f $POD >/dev/null 2>&1; }
trap cleanup EXIT

log "=== $LABEL: JAR=$JAR GTID=$GTID VIA_REPLICA=$VIA_REPLICA CASES=[$CASES] ==="
cleanup; docker pod create --name $POD >/dev/null 2>&1 && log "pod up"
mycnf(){
  local sid="$1" port="$2" comp="$3"
  echo "[mysqld]"; echo "server-id=$sid"; echo "port=$port"; echo "max_connections=2000"; echo "default_authentication_plugin=mysql_native_password"
  echo "binlog_transaction_compression = $comp"; echo "binlog_transaction_compression_level_zstd = 3"
  if [ "$GTID" = on ]; then echo "gtid-mode = on"; echo "enforce-gtid-consistency = true"; else echo "gtid-mode = off"; echo "enforce-gtid-consistency = false"; fi
  echo "binlog_row_image=FULL"; echo "cte_max_recursion_depth=4000000"; echo "max_allowed_packet=1073741824"
  echo "binlog_cache_size=268435456"; echo "innodb_buffer_pool_size=4G"; echo "innodb_redo_log_capacity=4294967296"
  echo "log_replica_updates=ON"; echo "max_binlog_size=268435456"; echo "replica_max_allowed_packet=1073741824"
}
mycnf 1 3306 ON > "$WORK/source.cnf"; mycnf 2 3307 OFF > "$WORK/replica.cnf"
docker run -d --name $CH --pod $POD -e CLICKHOUSE_USER=root -e CLICKHOUSE_PASSWORD=root \
  -e CLICKHOUSE_DB=test -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 --ulimit nofile=262144:262144 \
  clickhouse/clickhouse-server:24.8 >/dev/null 2>&1 && log "ch started"
docker run -d --name $MY --pod $POD -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=test \
  -e TZ=US/Central -v "$WORK/source.cnf:/etc/mysql/conf.d/my_custom.cnf:ro,Z" mysql:8.0 >/dev/null 2>&1 && log "mysql source started"
for i in $(seq 1 60); do MEX -e "SELECT 1" >/dev/null 2>&1 && { log "mysql source up"; break; }; sleep 3; done
for i in $(seq 1 60); do CEX -q "SELECT 1" >/dev/null 2>&1 && { log "ch up"; break; }; sleep 3; done
MEX -e "CREATE DATABASE IF NOT EXISTS other" 2>/dev/null
CPORT=3306
if [ "$VIA_REPLICA" = 1 ]; then
  docker run -d --name $RP --pod $POD -e MYSQL_ROOT_PASSWORD=root \
    -e TZ=US/Central -v "$WORK/replica.cnf:/etc/mysql/conf.d/my_custom.cnf:ro,Z" mysql:8.0 >/dev/null 2>&1 && log "mysql replica started (port 3307)"
  for i in $(seq 1 60); do REX -e "SELECT 1" >/dev/null 2>&1 && { log "mysql replica up"; break; }; sleep 3; done
  # test was created at init with binary logging off and other before the replica starts: create both there
  REX -e "CREATE DATABASE IF NOT EXISTS test; CREATE DATABASE IF NOT EXISTS other" 2>/dev/null
  if [ "$GTID" = on ]; then
    REX -e "RESET MASTER; CHANGE REPLICATION SOURCE TO SOURCE_HOST='127.0.0.1', SOURCE_PORT=3306, SOURCE_USER='root', SOURCE_PASSWORD='root', SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1; START REPLICA;" 2>&1 | grep -v Warning
  else
    MEX -e "FLUSH BINARY LOGS" 2>/dev/null
    sf=$(MEX -N -e "SHOW BINARY LOGS" 2>/dev/null | tail -n 1 | awk '{print $1}')
    REX -e "RESET MASTER; CHANGE REPLICATION SOURCE TO SOURCE_HOST='127.0.0.1', SOURCE_PORT=3306, SOURCE_USER='root', SOURCE_PASSWORD='root', SOURCE_LOG_FILE='$sf', SOURCE_LOG_POS=4, GET_SOURCE_PUBLIC_KEY=1; START REPLICA;" 2>&1 | grep -v Warning
  fi
  MEX -e "CREATE DATABASE IF NOT EXISTS other2; DROP DATABASE other2" 2>/dev/null
  sleep 5
  st=$(REX -e "SHOW REPLICA STATUS\G" 2>/dev/null | grep -E 'Replica_(IO|SQL)_Running:' | awk '{print $2}' | tr '\n' ' ')
  expect "replica threads running" "$st" "Yes Yes "
  CPORT=3307
fi
log "source: gtid_mode=$(myq 'SELECT @@gtid_mode') compression=$(myq 'SELECT @@binlog_transaction_compression') version=$(myq 'SELECT @@version'); connector reads port $CPORT"
sed -e 's/database.hostname: "mysql-master"/database.hostname: "127.0.0.1"/' \
    -e "s/database.port: \"3306\"/database.port: \"$CPORT\"/" \
    -e 's/database.include.list: test/database.include.list: "test"/' \
    -e 's/clickhouse.server.url: "clickhouse"/clickhouse.server.url: "127.0.0.1"/' \
    -e 's#jdbc:clickhouse://clickhouse:8123#jdbc:clickhouse://127.0.0.1:8123#g' "$TEMPLATE" > "$WORK/config.yml"
{ echo; echo "# --- binlog compression edge cases ---"; echo "name: \"bce-$LABEL\""; echo "topic.prefix: \"sink-bce-$LABEL\""
  echo "binlog.transaction.compression.check: \"auto\""; } >> "$WORK/config.yml"
# the replica's binlog must carry the source's payloads (proof that the hop exercises the decoder)
replica_payloads(){ local f n=0; for f in $(REX -N -e "SHOW BINARY LOGS" 2>/dev/null | awk '{print $1}'); do n=$((n + $(REX -N -e "SHOW BINLOG EVENTS IN '$f' LIMIT 200000" 2>/dev/null | awk -F'\t' '$3=="Transaction_payload"' | wc -l))); done; echo $n; }

MEX --force test <<'SQL'
CREATE TABLE e_snap (id INT NOT NULL PRIMARY KEY, v INT, pad VARCHAR(200));
SET SESSION cte_max_recursion_depth = 400000;
INSERT INTO e_snap WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 300000) SELECT i, 0, REPEAT('s', 150) FROM n;
CREATE TABLE e_json (id INT NOT NULL PRIMARY KEY, j JSON, note VARCHAR(32));
CREATE TABLE e_xa (id INT NOT NULL PRIMARY KEY, v INT);
CREATE TABLE e_row (id INT NOT NULL PRIMARY KEY, b LONGBLOB, tag VARCHAR(32));
CREATE TABLE e_flood (id INT NOT NULL PRIMARY KEY, w INT, v INT);
CREATE TABLE e_mix (id INT NOT NULL PRIMARY KEY, v INT);
CREATE TABLE e_purge (id INT NOT NULL PRIMARY KEY, v INT);
CREATE TABLE other.e_excluded (id INT NOT NULL PRIMARY KEY, v INT);
SQL
sql=""; for i in $(seq 1 300); do sql="$sql CREATE TABLE e_t$i (id INT NOT NULL PRIMARY KEY, v INT, s VARCHAR(16));"; done
MEX test -e "$sql" 2>/dev/null
[ "$(myq "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='test' AND TABLE_NAME LIKE 'e_t%'")" = "300" ] || bad "E4 setup: the 300 tables were not created on the source"

# ------------------------------------------------------------------ E1 snapshot during compressed writes
if case_on E1; then
  log "=== E1: initial snapshot while compressed transactions commit (incl. one 300k-row payload) ==="
  ( for i in $(seq 1 400); do MEX test -e "START TRANSACTION; UPDATE e_snap SET v = v + 1 WHERE id % 1000 = $((i % 1000)); INSERT INTO e_snap VALUES ($((400000 + i)), $i, 'during-snapshot') ON DUPLICATE KEY UPDATE v = v + 1; COMMIT;" 2>/dev/null; done ) & SNAPW=$!
  start_connector
  sleep 4
  MEX test -e "SET SESSION cte_max_recursion_depth=400000; START TRANSACTION; UPDATE e_snap SET pad = CONCAT('big-', id) WHERE id <= 300000; DELETE FROM e_snap WHERE id % 5000 = 1; COMMIT;" 2>/dev/null
  wait $SNAPW
  converge e_snap test 600 || true
  vt e_snap "id,v,pad" "toString(id),toString(v),pad"
else
  start_connector
  for i in $(seq 1 120); do [ "$(chq "SELECT count() FROM test.e_snap FINAL WHERE is_deleted=0")" = "300000" ] && break; sleep 3; done
fi
[ "$VIA_REPLICA" = 1 ] && log "replica-hop payloads so far: $(replica_payloads)"

# ------------------------------------------------------------------ E2 partial JSON
if case_on E2; then
  log "=== E2: binlog_row_value_options=PARTIAL_JSON (session) inside compressed payloads and uncompressed ==="
  MEX --force test <<'SQL'
INSERT INTO e_json VALUES (1, JSON_OBJECT('a', 1, 'b', REPEAT('x', 3000), 'c', JSON_ARRAY(1,2,3)), 'seed'),
                          (2, JSON_OBJECT('a', 2, 'b', REPEAT('y', 3000), 'c', JSON_ARRAY(4,5,6)), 'seed'),
                          (3, JSON_OBJECT('a', 3, 'b', 'short', 'c', JSON_OBJECT('k', 'v')), 'seed');
SQL
  converge e_json test 60 || true
  MEX --force test <<'SQL'
SET SESSION binlog_row_value_options = 'PARTIAL_JSON';
START TRANSACTION;
UPDATE e_json SET j = JSON_SET(j, '$.a', 101) WHERE id = 1;
UPDATE e_json SET j = JSON_REPLACE(j, '$.b', 'replaced'), note = 'rep' WHERE id = 2;
UPDATE e_json SET j = JSON_REMOVE(j, '$.c') WHERE id = 3;
COMMIT;
SET SESSION binlog_transaction_compression = OFF;
START TRANSACTION;
UPDATE e_json SET j = JSON_SET(j, '$.d', 'uncompressed-partial') WHERE id = 1;
UPDATE e_json SET j = JSON_SET(j, '$.a', 202) WHERE id = 2;
COMMIT;
SET SESSION binlog_transaction_compression = ON;
SQL
  f=$(MEX -N -e "SHOW BINARY LOGS" 2>/dev/null | tail -n 1 | awk '{print $1}')
  partial=$(MEX -N -e "SHOW BINLOG EVENTS IN '$f' LIMIT 400000" 2>/dev/null | awk -F'\t' '$3=="Update_rows_partial"' | wc -l)
  [ "$partial" -gt 0 ] && ok "E2 the source logged partial updates (Update_rows_partial outside payloads: $partial)" || bad "E2 the source logged no partial update: the case exercised nothing"
  sleep 15
  mrow=$(MEX -N test -e "SELECT note FROM e_json WHERE id=2" 2>/dev/null | tr -d '[:space:]')
  crow=$(chq "SELECT note FROM test.e_json FINAL WHERE id=2 AND is_deleted=0")
  if [ "$VIA_REPLICA" = 1 ]; then
    # the replica re-logs what it applies with its own binlog_row_value_options (''): full row images
    if [ "$mrow" = "rep" ] && [ "$crow" = "rep" ]; then
      ok "E2 through the replica hop the partial updates arrived: the replica re-logs them with full row images (doc/limitations.md)"
    else
      bad "E2 through the replica hop: source note=[$mrow], replica note=[$crow] -- the replica was expected to re-log the update in full"
    fi
  elif [ "$mrow" = "rep" ] && [ "$crow" = "seed" ]; then
    ok "E2 documented limitation reproduced (doc/limitations.md, spec 01.10 section 3.1): the partial updates did not reach ClickHouse, including a plain column changed by the same UPDATE (source note=$mrow, replica note=$crow)"
  else
    bad "E2 behaviour differs from the documented limitation (source note=[$mrow], replica note=[$crow]) -- update doc/limitations.md and spec 01.10"
  fi
  running $CC && ok "E2 replication continued" || bad "E2 the connector stopped"
  log "E2 repair (doc/limitations.md): re-synchronise e_json from the source"
  resync_table e_json "id, j, note"
  vt e_json "id,REPLACE(CAST(j AS CHAR),' ',''),note" "toString(id),replaceAll(j,' ',''),note"
fi

# ------------------------------------------------------------------ E3 XA
if case_on E3; then
  log "=== E3: XA transactions (with compression on, the XA body is inside a Transaction_payload) ==="
  MEX --force test <<'SQL'
XA START 'x1'; INSERT INTO e_xa VALUES (1, 1), (2, 2); UPDATE e_xa SET v = 10 WHERE id = 1; XA END 'x1'; XA PREPARE 'x1';
XA COMMIT 'x1';
XA START 'x2'; INSERT INTO e_xa VALUES (3, 3); XA END 'x2'; XA PREPARE 'x2';
XA ROLLBACK 'x2';
XA START 'x3'; INSERT INTO e_xa VALUES (4, 4); DELETE FROM e_xa WHERE id = 2; XA END 'x3'; XA COMMIT 'x3' ONE PHASE;
SQL
  sleep 15
  docker logs $CC > "$WORK/e3.log" 2>&1
  if grep -q 'XA ROLLBACK' "$WORK/e3.log" && grep 'XA ROLLBACK' "$WORK/e3.log" | grep -q 'test.e_xa'; then
    ok "E3 the rolled-back XA transaction was reported at ERROR with its table ($(grep -m1 -o 'Tables written by its PREPARE: [^.]*\.[^ .]*' "$WORK/e3.log"))"
  else bad "E3 no ERROR for the XA ROLLBACK after PREPARE (its rows stay in ClickHouse silently)"; fi
  running $CC && ok "E3 replication continued after the report" || bad "E3 the connector stopped on the XA rollback"
  log "E3 recovery (spec 01.10): re-synchronise the reported table"
  resync_table e_xa "id, v"
  expect "E3 rolled-back XA row absent after the re-synchronisation" "$(chq "SELECT count() FROM test.e_xa FINAL WHERE id=3 AND is_deleted=0")" "0"
  vt e_xa "id,v" "toString(id),toString(v)"
fi

# ------------------------------------------------------------------ E4 300 tables in one payload
if case_on E4; then
  log "=== E4: one payload touching 300 tables, then one payload updating all of them ==="
  sql="START TRANSACTION;"; for i in $(seq 1 300); do sql="$sql INSERT INTO e_t$i VALUES (1, $i, 't$i'), (2, $((i*2)), 'u$i');"; done; sql="$sql COMMIT;"
  MEX test -e "$sql" 2>/dev/null
  sql="START TRANSACTION;"; for i in $(seq 1 300); do sql="$sql UPDATE e_t$i SET v = v + 1000 WHERE id = 1; DELETE FROM e_t$i WHERE id = 2;"; done; sql="$sql COMMIT;"
  MEX test -e "$sql" 2>/dev/null
  sleep 20
  badt=0
  for i in $(seq 1 300); do
    [ "$(chq "SELECT concat(toString(count()),':',toString(sum(v))) FROM test.e_t$i FINAL WHERE is_deleted=0")" = "1:$((i + 1000))" ] || badt=$((badt+1))
  done
  expect "E4 tables whose replica equals the source (of 300)" "$((300 - badt))" "300"
fi

# ------------------------------------------------------------------ E5 one 256 MiB row
if case_on E5; then
  log "=== E5: one ${E5_MB:-256} MiB row inside a compressed payload (connector heap $HEAP) ==="
  m0=$(conn_mem)
  MEX test -e "START TRANSACTION; INSERT INTO e_row VALUES (1, REPEAT('abcdefghij', $(( ${E5_MB:-256} * 104858 ))), 'big'), (2, 'small', 'small'); UPDATE e_row SET tag = 'big-updated' WHERE id = 1; COMMIT;" 2>&1 | grep -v Warning
  converge e_row test 300 || true
  vt e_row "id,MD5(b),tag" "toString(id),lower(hex(MD5(unhex(b)))),tag"
  log "E5 connector memory before/after: $m0 / $(conn_mem)"
  running $CC && ok "E5 connector still running after the ${E5_MB:-256} MiB row" || bad "E5 connector died on the ${E5_MB:-256} MiB row with heap $HEAP (exit $(docker inspect -f '{{.State.ExitCode}}' $CC); $(docker logs $CC 2>&1 | grep -m1 -o 'OutOfMemoryError.\{0,60\}'))"
  if ! running $CC; then
    # What a supervisor does after the OOM exit: start the process again. It resumes from the durable offset and
    # re-applies the transaction; the row must arrive, nothing may be lost past it.
    t0=$(now); docker rm -f $CC >/dev/null 2>&1; start_connector
    converge e_row test 300 && ok "E5 recovered after the OOM exit by a plain restart in $(( $(now) - t0 )) s" || bad "E5 did not recover after a restart (heap $HEAP too small for a ${E5_MB:-256} MiB row: raise -Xmx)"
    vt e_row "id,MD5(b),tag" "toString(id),lower(hex(MD5(unhex(b)))),tag"
  fi
fi

# ------------------------------------------------------------------ E6 flood of tiny payloads
if case_on E6; then
  log "=== E6: 160,000 single-row compressed transactions from 16 sessions ==="
  sleep 10; m0=$(conn_mem); t0=$(now)
  for w in $(seq 1 16); do
    ( lo=$(( (w-1) * 10000 + 1 )); hi=$(( w * 10000 ))
      docker exec -i $MY sh -c "i=$lo; while [ \$i -le $hi ]; do echo \"INSERT INTO e_flood VALUES (\$i, $w, \$i);\"; i=\$((i+1)); done | mysql -uroot -proot test" 2>/dev/null ) &
  done
  wait; tsrc=$(( $(now) - t0 ))
  converge e_flood test 900 || true
  tall=$(( $(now) - t0 ))
  log "E6 source committed 160,000 transactions in $tsrc s; replica converged $tall s after the start ($(( 160000 / (tall > 0 ? tall : 1) )) txn/s end to end)"
  vt e_flood "id,w,v" "toString(id),toString(w),toString(v)"
  sleep 20; m1=$(conn_mem)
  log "E6 connector resident memory before/after the flood: $m0 / $m1"
fi

# ------------------------------------------------------------------ E7 included + excluded database in one payload
if case_on E7; then
  log "=== E7: a payload mixing an included and an excluded database ==="
  MEX -e "START TRANSACTION; INSERT INTO other.e_excluded VALUES (1, 1); INSERT INTO test.e_mix VALUES (1, 1), (2, 2); UPDATE other.e_excluded SET v = 5; UPDATE test.e_mix SET v = 20 WHERE id = 2; DELETE FROM test.e_mix WHERE id = 1; COMMIT;" 2>/dev/null
  converge e_mix test 60 || true
  vt e_mix "id,v" "toString(id),toString(v)"
  expect "E7 excluded database not replicated" "$(chq "SELECT count() FROM system.tables WHERE database='other'")" "0"
fi

# ------------------------------------------------------------------ E8 binlog purged while down
if case_on E8; then
  log "=== E8: binlog purged while the connector is down ==="
  MEX test -e "INSERT INTO e_purge VALUES (1, 1)" 2>/dev/null; converge e_purge test 60 || true
  docker stop -t 30 $CC >/dev/null 2>&1; log "connector stopped"
  SRCX=MEX; [ "$VIA_REPLICA" = 1 ] && SRCX=REX
  MEX test -e "START TRANSACTION; INSERT INTO e_purge VALUES (2, 2), (3, 3); UPDATE e_purge SET v = 10 WHERE id = 1; COMMIT;" 2>/dev/null
  sleep 3
  $SRCX -e "FLUSH BINARY LOGS" 2>/dev/null; $SRCX -e "FLUSH BINARY LOGS" 2>/dev/null
  last=$($SRCX -N -e "SHOW BINARY LOGS" 2>/dev/null | tail -n 1 | awk '{print $1}')
  $SRCX -e "PURGE BINARY LOGS TO '$last'" 2>/dev/null
  if [ "$GTID" = on ]; then log "E8 gtid_purged now: $($SRCX -N -e 'SELECT @@GLOBAL.gtid_purged' 2>/dev/null | tr -d '\n' | cut -c1-120)"; fi
  MEX test -e "INSERT INTO e_purge VALUES (4, 4)" 2>/dev/null
  since=$(date -u +%Y-%m-%dT%H:%M:%S); t0=$(now)
  docker start $CC >/dev/null 2>&1; log "connector started against a source that purged the binlog it needs"
  loud=0
  for i in $(seq 1 150); do
    # only what this start logged, and only ERROR / FATAL lines: a startup INFO/WARN that merely mentions
    # purged GTIDs is not a failure
    docker logs --since "$since" $CC 2>&1 | grep -E ' (ERROR|FATAL) ' > "$WORK/e8.log"
    if grep -q -i -e 'purged' -e 'no longer available' -e 'Could not find first log file' -e '1236' "$WORK/e8.log"; then loud=1; break; fi
    running $CC || { loud=2; break; }
    sleep 2
  done
  det=$(( $(now) - t0 ))
  if [ $loud = 1 ]; then ok "E8 the connector failed LOUDLY on the purged binlog within $det s: $(grep -m1 -i -o -e '[^-]*purged.\{0,100\}' -e '[^-]*no longer available.\{0,60\}' -e '[^-]*1236.\{0,80\}' "$WORK/e8.log" | head -n 1 | cut -c1-200)"
  elif [ $loud = 2 ]; then ok "E8 the connector exited (code $(docker inspect -f '{{.State.ExitCode}}' $CC)) within $det s rather than skip the purged binlog"
  else bad "E8 no ERROR/FATAL within $det s of starting against a purged binlog"; fi
  miss=$(chq "SELECT count() FROM test.e_purge FINAL WHERE is_deleted=0 AND id IN (2,3)")
  expect "E8 the rows in the purged binlog were NOT silently applied or skipped past (replica still lacks them)" "$miss" "0"
  log "E8 recovery: reposition to the current source position (offsets cleared, schema-only start), then re-synchronise e_purge"
  t0=$(now); docker stop -t 30 $CC >/dev/null 2>&1
  before_rows=$(chq "SELECT count() FROM test.e_snap FINAL WHERE is_deleted=0")
  reposition_to_current
  docker rm -f $CC >/dev/null 2>&1; start_connector
  for i in $(seq 1 60); do docker logs $CC 2>&1 | grep -q -i 'streaming' && break; sleep 2; done
  sleep 10
  after_rows=$(chq "SELECT count() FROM test.e_snap FINAL WHERE is_deleted=0")
  expect "E8 the reposition kept the replica's existing rows (e_snap live rows before/after the schema-only start)" "$after_rows" "$before_rows"
  log "E8 DDL the schema-only start executed on ClickHouse:"
  CEX -q "SELECT event_time, query_kind, substring(query, 1, 120) FROM system.query_log WHERE type = 'QueryFinish' AND event_time >= now() - INTERVAL 90 SECOND AND query_kind IN ('Drop', 'Create', 'Rename', 'Alter') AND query NOT ILIKE '%system.%' ORDER BY event_time LIMIT 20" 2>&1 | cut -c1-180
  resync_table e_purge "id, v"
  MEX test -e "INSERT INTO e_purge VALUES (5, 5); UPDATE e_purge SET v = 44 WHERE id = 4" 2>/dev/null
  converge e_purge test 180 && ok "E8 recovered in $(( $(now) - t0 )) s (reposition + re-synchronise + resume)" || bad "E8 did not recover"
  vt e_purge "id,v" "toString(id),toString(v)"
fi

log "=== evidence ==="
[ "$VIA_REPLICA" = 1 ] && expect "replica threads still running at the end (the hop itself stayed healthy)" "$(REX -e 'SHOW REPLICA STATUS\G' 2>/dev/null | grep -E 'Replica_(IO|SQL)_Running:' | awk '{print $2}' | tr '\n' ' ')" "Yes Yes "
[ "$VIA_REPLICA" = 1 ] && { n=$(replica_payloads); [ "$n" -gt 0 ] && ok "the replica's binlog carries the source's payloads ($n Transaction_payload)" || bad "the replica's binlog has no Transaction_payload (the hop did not exercise the decoder)"; }
docker logs $CC > "$WORK/connector.log" 2>&1
if grep -q -e 'OutOfMemoryError' -e 'PayloadEventDecodingException' -e 'Stumbled upon long' "$WORK/connector.log"; then bad "connector log shows a decode failure or OutOfMemoryError"
else ok "no decode failure and no OutOfMemoryError in the connector log ($(wc -l < "$WORK/connector.log") lines)"; fi
log "connector errors (last 8):"; grep -E ' ERROR |FATAL' "$WORK/connector.log" | tail -n 8 | cut -c1-240
log "=== RESULT PASS=$PASS FAIL=$FAIL ($LABEL GTID=$GTID VIA_REPLICA=$VIA_REPLICA edge) ==="
status=0; [ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ] || status=1
log "=== DONE (exit $status) ==="
exit "$status"
