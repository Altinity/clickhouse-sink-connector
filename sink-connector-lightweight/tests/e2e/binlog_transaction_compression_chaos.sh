#!/usr/bin/env bash
# Chaos, stress and recovery-time harness for MySQL binlog transaction compression through the
# lightweight sink connector (spec 01.08 section 6, Constitution Invariant I15), value level.
# Companion of binlog_transaction_compression.sh / _hard.sh / _large.sh.
#
#   JAR=<lightweight jar> [GTID=on|off] [LABEL=<tag>] [WRITERS=8] [BIG_ROWS=800000] [HEAP=4g] \
#   [IMAGE=<image providing java 17>] [WORK=<scratch dir>] [JAVAC=<javac>] [PHASES="..."] \
#   tests/e2e/binlog_transaction_compression_chaos.sh
#   Needs podman or docker (the script calls `docker`), the images mysql:8.0,
#   clickhouse/clickhouse-server:24.8 and eclipse-temurin:17-jre, python3 and a javac (JAVAC, default
#   `javac` on PATH) to build the fault proxy. Disk: ~40 GB of container storage. Runtime ~60 min.
#
# Topology (one pod): MySQL 8.0 (binlog_transaction_compression=ON, level 3, GTID per GTID) <- FaultProxy
# (tests/e2e/fault_proxy/FaultProxy.java: pass / reset / partition / slow) <- connector (heap HEAP,
# -XX:+ExitOnOutOfMemoryError, restarted within 5 s whenever its process exits, like systemd
# Restart=always) -> ClickHouse 24.8. WRITERS concurrent sessions replay a generated mix of compressed,
# session-uncompressed, varying-level, savepoint, rollback, multi-table, wide-row and JSON transactions
# for the whole run (tests/e2e/chaos/gen_workload.py) while a big-transaction writer commits payloads of
# BIG_ROWS rows (~1.5 GB uncompressed at the default) and the phases below inject one fault each.
#
# Every phase ends with a RECOVERY TIME measurement: when the fault is removed a marker row is committed
# on the source, and the phase's RTO is the time until that marker is visible in ClickHouse FINAL --
# i.e. restart + redelivery of the in-flight transaction + catch-up of everything the writers committed
# during the fault. The objective (Invariant I15) is 300 s plus the re-apply of one in-flight transaction;
# a phase over OBJECTIVE (default 420 s, i.e. 300 s + the ~120 s a BIG_ROWS payload takes to re-apply
# behind the writers' backlog) FAILS.
#   P0  steady state under the full writer mix (baseline)
#   I0  writers stopped for IDLE_S (200) s, longer than the binlog read timeout: no engine or process
#       restart may happen (the source heartbeat keeps a healthy idle connection alive, spec 01.09)
#   F1  kill -9 of the connector while a big payload is being applied
#   F2  network RESET (RST on both sides) of the binlog connection while writers and a big payload stream
#   F3  network PARTITION of the binlog connection until the source aborts the dump connection (it does
#       after net_write_timeout; PARTITION_S caps it) + 20 s, then healed; the FIN never reaches the connector (the phase asserts that a dead, silent connection was formed). The connector must
#       notice by itself; if it does not within OBJECTIVE the phase FAILS and the harness then applies the
#       operator recovery (restart the connector) and measures that too
#   F4  ClickHouse stopped for 60 s, then started
#   F5  ClickHouse frozen (docker pause: connections open, nothing answers) for 150 s, then thawed
#   F6  MySQL restarted cleanly while writers stream
#   F7  MySQL killed -9 while a big transaction is committing (crash recovery of the source)
#   F8  GLOBAL binlog_transaction_compression toggled 12 times, 5 s apart, under the writer mix
#   F9  five kill -9 of the connector 20 s apart while a big payload is being applied
#   F10 a thin link (3 MB/s) on the binlog connection for 120 s while a big payload streams
#   F11 DDL storm: 20 rounds of ADD COLUMN / DROP COLUMN on a table taking concurrent compressed inserts
# Final: writers stopped, the replica drained, and EVERY table compared value by value (row count and a
# per-row CRC over every column) with MySQL; the two-table transfer invariant checked on both sides; the
# source-side proof that Transaction_payload events were written; the connector log scanned for decode
# failures and OutOfMemoryError; the peak connector memory reported. Output: an RTO table and
# "RESULT PASS=n FAIL=m"; exit status 0 only when FAIL=0 and at least one check ran.
set -uo pipefail
JAR="${JAR:?set JAR}"; GTID="${GTID:-on}"; LABEL="${LABEL:-bcx}"; WRITERS="${WRITERS:-8}"
BIG_ROWS="${BIG_ROWS:-800000}"; HEAP="${HEAP:-4g}"; OBJECTIVE="${OBJECTIVE:-420}"; PARTITION_S="${PARTITION_S:-240}"
IMAGE="${IMAGE:-docker.io/eclipse-temurin:17-jre}"; JAVAC="${JAVAC:-javac}"
PHASES="${PHASES:-P0 I0 F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"; HERE="$REPO/sink-connector-lightweight/tests/e2e"
TEMPLATE="${TEMPLATE:-$REPO/sink-connector-lightweight/docker/config.yml}"; LOG4J="${LOG4J:-$REPO/sink-connector-lightweight/docker/log4j2.xml}"
WORK="${WORK:-/tmp/csc-e2e-bcx-$LABEL}"; mkdir -p "$WORK/wl" "$WORK/ctl" "$WORK/fp"
POD=cscbcx-$LABEL; MY=mysql-bcx-$LABEL; CH=ch-bcx-$LABEL; CC=csc-bcx-$LABEL; PX=px-bcx-$LABEL; WR=wr-bcx-$LABEL
MEX(){ docker exec -i $MY mysql -uroot -proot "$@"; }
CEX(){ docker exec -i $CH clickhouse-client -u root --password root "$@"; }
log(){ echo "[$(date +%H:%M:%S)] $*"; }
PASS=0; FAIL=0
ok(){ echo "PASS  $*"; PASS=$((PASS+1)); }
bad(){ echo "FAIL  $*"; FAIL=$((FAIL+1)); }
expect(){ if [ "$2" = "$3" ]; then ok "$1 = $3"; else bad "$1: got [$2] expected [$3]"; fi; }
myq(){ MEX -N test -e "$1" 2>/dev/null | tr -d '[:space:]'; }
chq(){ CEX -q "$1" 2>/dev/null | tr -d '[:space:]'; }
now(){ date +%s; }
proxy(){ echo "$*" > "$WORK/ctl/mode"; log "proxy <- $*"; }
conn_mem(){ docker stats --no-stream --format '{{.MemUsage}}' $CC 2>/dev/null | head -n 1; }
running(){ [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }

vt(){
  local tbl="$1" mcols="$2" ccols="$3" mrows crows msum csum
  mrows=$(myq "SELECT count(*) FROM $tbl")
  msum=$(myq "SELECT IFNULL(SUM(CRC32(CONCAT_WS('~',$mcols))),0) FROM $tbl")
  crows=$(chq "SELECT count() FROM test.$tbl FINAL WHERE is_deleted=0")
  csum=$(chq "SELECT toInt64(ifNull(sum(CRC32(concat_ws('~',$ccols))),0)) FROM test.$tbl FINAL WHERE is_deleted=0")
  if [ -z "$mrows" ] || [ -z "$crows" ]; then bad "$tbl: a side could not be queried (mysql_rows=[$mrows] target_rows=[$crows])"; return; fi
  if [ -n "$msum" ] && [ "$mrows" = "$crows" ] && [ "$msum" = "$csum" ]; then ok "$tbl value-level (rows=$mrows)";
  else bad "$tbl mysql_rows=$mrows target_rows=$crows mysql_crc=$msum target_crc=$csum"; fi
}

# ---------------------------------------------------------------- recovery-time bookkeeping
MARK=0
echo -e "phase\tfault\trto_s\toutcome\tconnector_restarts\tnote" > "$WORK/rto.tsv"
restarts(){ wc -l < "$WORK/supervisor.log" 2>/dev/null | tr -d ' '; }
# commit a marker on the source (retrying while the source is down) and wait for it on the replica.
# $1 phase, $2 fault, $3 timeout (s). Sets RTO and MARK_SEEN.
marker(){
  local phase="$1" fault="$2" limit="$3" t0 i seen=0
  MARK=$((MARK+1)); t0=$(now)
  for i in $(seq 1 150); do MEX test -e "INSERT INTO x_mark VALUES ($MARK, '$phase', NOW(6))" >/dev/null 2>&1 && break; sleep 2; done
  while [ $(( $(now) - t0 )) -lt "$limit" ]; do
    [ "$(chq "SELECT count() FROM test.x_mark FINAL WHERE id=$MARK AND is_deleted=0")" = "1" ] && { seen=1; break; }
    sleep 2
  done
  RTO=$(( $(now) - t0 )); MARK_SEEN=$seen
}
record(){ echo -e "$1\t$2\t$3\t$4\t$(restarts)\t$5" >> "$WORK/rto.tsv"; log "RTO $1 ($2): $3 s -> $4 ${5:+($5)}"; }
# measure and judge one phase against the objective
judge(){
  local phase="$1" fault="$2" note="${3:-}"
  marker "$phase" "$fault" "$OBJECTIVE"
  if [ "$MARK_SEEN" = 1 ]; then record "$phase" "$fault" "$RTO" "recovered" "$note"; ok "$phase $fault: replication recovered in $RTO s (objective $OBJECTIVE s)"
  else record "$phase" "$fault" "$RTO" "NOT-RECOVERED" "$note"; bad "$phase $fault: replication did not recover within $OBJECTIVE s"; fi
}

# ---------------------------------------------------------------- containers
start_connector(){
  docker run -d --name $CC --pod $POD --runtime runc \
    -v "$WORK/config.yml:/config.yml:ro,Z" -v "$LOG4J:/log4j2.xml:ro,Z" -v "$JAR:/app.jar:ro,Z" \
    --entrypoint sh "$IMAGE" -c \
    "java -Xms$HEAP -Xmx$HEAP -XX:+ExitOnOutOfMemoryError -Dlog4j2.configurationFile=log4j2.xml -jar /app.jar /config.yml com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication" \
    >/dev/null 2>&1 && log "connector started ($CC, heap $HEAP)"
}
# systemd Restart=always, RestartSec=5: a connector process that exits is started again, unless the
# harness holds it down ($WORK/hold) for an operator step.
supervisor(){
  : > "$WORK/supervisor.log"
  while [ ! -f "$WORK/stop_supervisor" ]; do
    if ! running $CC && [ ! -f "$WORK/hold" ]; then
      code=$(docker inspect -f '{{.State.ExitCode}}' $CC 2>/dev/null)
      docker start $CC >/dev/null 2>&1 && echo "$(date +%H:%M:%S) restarted (previous exit code $code)" >> "$WORK/supervisor.log"
    fi
    sleep 5
  done
}
memsampler(){ while [ ! -f "$WORK/stop_supervisor" ]; do echo "$(date +%H:%M:%S) $(conn_mem)" >> "$WORK/mem.log"; sleep 10; done; }
start_writers(){
  rm -f "$WORK/wl/stop"
  docker rm -f $WR >/dev/null 2>&1
  local script="" w
  for w in $(seq 1 "$WRITERS"); do
    script="$script (while [ ! -f /work/stop ]; do mysql -h127.0.0.1 -uroot -proot --force test < /work/w$w.sql >> /work/w$w.err 2>&1 || sleep 1; done) &"
  done
  docker run -d --name $WR --pod $POD -v "$WORK/wl:/work:Z" --entrypoint sh mysql:8.0 -c "$script wait" >/dev/null 2>&1 \
    && log "writers started ($WRITERS sessions)"
}
stop_writers(){ touch "$WORK/wl/stop"; for i in $(seq 1 120); do running $WR || break; sleep 2; done; log "writers stopped"; }
# a big transaction (BIG_ROWS rows of ~1.8 KB) into x_big, ids from $1, with an UPDATE and a DELETE at
# its tail; runs in the background, pid in BIGPID
big_txn(){
  local lo="$1" hi=$(( $1 + BIG_ROWS - 1 ))
  ( MEX --force test > "$WORK/big_$1.log" 2>&1 <<SQL
SET SESSION cte_max_recursion_depth = 4000000;
START TRANSACTION;
INSERT INTO x_big (id, payload)
  WITH RECURSIVE n(i) AS (SELECT $lo UNION ALL SELECT i+1 FROM n WHERE i < $hi)
  SELECT i, CONCAT(LPAD(i, 10, '0'), '-', REPEAT(MD5(i), 8), '-', REPEAT('payload-row-', 130)) FROM n;
UPDATE x_big SET payload = 'tail-update' WHERE id IN ($lo, $hi);
DELETE FROM x_big WHERE id = $((lo + 1));
COMMIT;
SQL
  ) &
  BIGPID=$!
}
big_count(){ chq "SELECT count() FROM test.x_big"; }
# wait until the replica holds between 5% and 60% of a big payload's rows beyond $1 (the payload is being
# applied); returns 1 if the window was missed
wait_mid_apply(){
  local base="$1" c i lo=$(( BIG_ROWS / 20 )) hi=$(( BIG_ROWS * 6 / 10 ))
  for i in $(seq 1 900); do
    c=$(big_count); c=$(( ${c:-0} - base ))
    [ "$c" -ge "$lo" ] && [ "$c" -le "$hi" ] && { log "big payload mid-apply ($c of $BIG_ROWS rows on the replica)"; return 0; }
    [ "$c" -gt "$hi" ] && { log "big payload applied too fast to catch mid-apply ($c rows)"; return 1; }
    sleep 0.5
  done
  return 1
}
cleanup(){
  touch "$WORK/stop_supervisor" "$WORK/wl/stop"
  docker logs $PX > "$WORK/proxy.log" 2>&1; docker logs $CC > "$WORK/connector.log" 2>&1
  for c in $CC $WR $PX $MY $CH; do docker rm -f "$c" >/dev/null 2>&1; done; docker pod rm -f $POD >/dev/null 2>&1
}
trap cleanup EXIT

log "=== $LABEL: JAR=$JAR GTID=$GTID WRITERS=$WRITERS BIG_ROWS=$BIG_ROWS HEAP=$HEAP OBJECTIVE=$OBJECTIVE s ==="
cleanup; rm -f "$WORK/stop_supervisor" "$WORK/hold" "$WORK/mem.log" "$WORK"/wl/*.err
"$JAVAC" --release 17 -d "$WORK/fp" "$HERE/fault_proxy/FaultProxy.java" || { bad "could not compile the fault proxy with $JAVAC"; exit 1; }
python3 "$HERE/chaos/gen_workload.py" "$WORK/wl" "$WRITERS" 400 "${SEED:-1}" || { bad "workload generation failed"; exit 1; }
echo pass > "$WORK/ctl/mode"
docker pod create --name $POD >/dev/null 2>&1 && log "pod up"
MYCNF=$WORK/mysqld.cnf
{
  echo "[mysqld]"; echo "max_connections=2000"; echo "default_authentication_plugin=mysql_native_password"
  echo "binlog_transaction_compression = ON"; echo "binlog_transaction_compression_level_zstd = 3"
  if [ "$GTID" = on ]; then echo "gtid-mode = on"; echo "enforce-gtid-consistency = true"; else echo "gtid-mode = off"; echo "enforce-gtid-consistency = false"; fi
  echo "binlog_row_image=FULL"; echo "cte_max_recursion_depth=4000000"; echo "max_allowed_packet=1073741824"
  echo "binlog_cache_size=268435456"; echo "innodb_buffer_pool_size=6G"; echo "innodb_redo_log_capacity=8589934592"
  echo "sql_generate_invisible_primary_key=1"; echo "max_binlog_size=268435456"
} > "$MYCNF"
sed -e 's/database.hostname: "mysql-master"/database.hostname: "127.0.0.1"/' \
    -e 's/database.port: "3306"/database.port: "13306"/' \
    -e 's/clickhouse.server.url: "clickhouse"/clickhouse.server.url: "127.0.0.1"/' \
    -e 's#jdbc:clickhouse://clickhouse:8123#jdbc:clickhouse://127.0.0.1:8123#g' "$TEMPLATE" > "$WORK/config.yml"
{ echo; echo "# --- binlog compression chaos ---"; echo "name: \"bcx-$LABEL\""; echo "topic.prefix: \"sink-bcx-$LABEL\""
  echo "binlog.transaction.compression.check: \"require\""; } >> "$WORK/config.yml"
grep -q 'database.port: "13306"' "$WORK/config.yml" || { bad "config template did not take the proxy port"; exit 1; }

docker run -d --name $CH --pod $POD -e CLICKHOUSE_USER=root -e CLICKHOUSE_PASSWORD=root \
  -e CLICKHOUSE_DB=test -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 --ulimit nofile=262144:262144 \
  clickhouse/clickhouse-server:24.8 >/dev/null 2>&1 && log "ch started"
docker run -d --name $MY --pod $POD -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=test \
  -e TZ=US/Central -v "$MYCNF:/etc/mysql/conf.d/my_custom.cnf:ro,Z" mysql:8.0 >/dev/null 2>&1 && log "mysql started"
docker run -d --name $PX --pod $POD -v "$WORK/fp:/fp:ro,Z" -v "$WORK/ctl:/ctl:Z" --entrypoint java "$IMAGE" \
  -cp /fp FaultProxy 13306 127.0.0.1 3306 /ctl/mode >/dev/null 2>&1 && log "fault proxy started (13306 -> 3306)"
for i in $(seq 1 60); do MEX -e "SELECT 1" >/dev/null 2>&1 && { log "mysql up"; break; }; sleep 3; done
for i in $(seq 1 60); do CEX -q "SELECT 1" >/dev/null 2>&1 && { log "ch up"; break; }; sleep 3; done
log "source: gtid_mode=$(myq 'SELECT @@gtid_mode') compression=$(myq 'SELECT @@binlog_transaction_compression') version=$(myq 'SELECT @@version') net_write_timeout=$(myq 'SELECT @@net_write_timeout')"

MEX --force test <<'SQL'
CREATE TABLE x_hot (id INT NOT NULL PRIMARY KEY, v BIGINT, s VARCHAR(64), j JSON, ts DATETIME(6));
CREATE TABLE x_log (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, w INT, n INT, payload VARCHAR(255), created DATETIME(6));
CREATE TABLE x_acct_a (id INT NOT NULL PRIMARY KEY, bal BIGINT, seq INT);
CREATE TABLE x_acct_b (id INT NOT NULL PRIMARY KEY, bal BIGINT, seq INT);
CREATE TABLE x_wide (id INT NOT NULL PRIMARY KEY, t MEDIUMTEXT, note VARCHAR(255));
CREATE TABLE x_big (id INT NOT NULL PRIMARY KEY, payload VARCHAR(2048));
CREATE TABLE x_ddl (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, a INT);
CREATE TABLE x_mark (id INT NOT NULL PRIMARY KEY, phase VARCHAR(16), t DATETIME(6));
SET SESSION cte_max_recursion_depth = 10000;
INSERT INTO x_acct_a WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 200) SELECT i, 1000000, 0 FROM n;
INSERT INTO x_acct_b WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 200) SELECT i, 1000000, 0 FROM n;
INSERT INTO x_hot WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 2000) SELECT i, i, 'seed', JSON_OBJECT('seed', i), NOW(6) FROM n;
INSERT INTO x_mark VALUES (0, 'seed', NOW(6));
SQL

start_connector
supervisor & SUPPID=$!
memsampler &
for i in $(seq 1 90); do [ "$(chq "SELECT count() FROM test.x_mark FINAL WHERE is_deleted=0")" = "1" ] && break; sleep 2; done
expect "snapshot replicated (x_hot rows)" "$(chq "SELECT count() FROM test.x_hot FINAL WHERE is_deleted=0")" "2000"
start_writers
BIGBASE=10000000

phase(){ case " $PHASES " in *" $1 "*) return 0;; *) log "--- $1 skipped (PHASES)"; return 1;; esac; }

# ------------------------------------------------------------------ P0 steady state
if phase P0; then
  log "=== P0: steady state under the writer mix (90 s) ==="
  s0=$(chq "SELECT count() FROM test.x_log"); sleep 90; s1=$(chq "SELECT count() FROM test.x_log")
  log "P0 x_log rows replicated in 90 s: $(( ${s1:-0} - ${s0:-0} )); connector memory $(conn_mem)"
  judge P0 steady-state
fi

# ------------------------------------------------------------------ I0 idle source: no false dead-connection
if phase I0; then
  log "=== I0: idle source for ${IDLE_S:-200} s (longer than the binlog read timeout): the connection must stay up ==="
  stop_writers
  docker logs $CC > "$WORK/i0_before.log" 2>&1; e0=$(grep -c 'Restarting the engine' "$WORK/i0_before.log"); r0=$(restarts)
  sleep "${IDLE_S:-200}"
  docker logs $CC > "$WORK/i0_after.log" 2>&1; e1=$(grep -c 'Restarting the engine' "$WORK/i0_after.log"); r1=$(restarts)
  expect "I0 engine restarts while the source was idle" "$((e1 - e0))" "0"
  expect "I0 process restarts while the source was idle" "$((r1 - r0))" "0"
  judge I0 "idle source ${IDLE_S:-200}s"
  start_writers
fi

# ------------------------------------------------------------------ F1 kill -9 mid-apply
if phase F1; then
  log "=== F1: kill -9 of the connector while a big payload is being applied ==="
  base=$(big_count); big_txn $BIGBASE; BIGBASE=$((BIGBASE + 10000000))
  if wait_mid_apply "${base:-0}"; then docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9 (supervisor restarts it)"; else bad "F1 kill did not land mid-apply"; fi
  wait $BIGPID; judge F1 "kill -9 mid-payload"
fi

# ------------------------------------------------------------------ F2 network reset
if phase F2; then
  log "=== F2: RST of the binlog connection while writers and a big payload stream ==="
  base=$(big_count); big_txn $BIGBASE; BIGBASE=$((BIGBASE + 10000000))
  wait_mid_apply "${base:-0}" || true
  proxy reset 1; sleep 1; wait $BIGPID
  judge F2 "network reset"
fi

# ------------------------------------------------------------------ F3 network partition
if phase F3; then
  log "=== F3: network partition of the binlog connection until the source gives up on it (max $PARTITION_S s), then healed ==="
  zombies0=$(docker logs $PX 2>&1 | grep -c ZOMBIE)
  proxy partition; tp=$(now); gone=""
  while [ $(( $(now) - tp )) -lt "$PARTITION_S" ]; do
    sleep 5
    d=$(MEX -N -e "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND LIKE 'Binlog Dump%'" 2>/dev/null | tr -d '[:space:]')
    if [ "$d" = "0" ] && [ -z "$gone" ]; then gone=$(( $(now) - tp )); log "F3 the source aborted the dump connection after ~$gone s of partition"; fi
    [ -n "$gone" ] && [ $(( $(now) - tp )) -ge $(( gone + 20 )) ] && break
  done
  proxy pass; sleep 2
  zombies=$(( $(docker logs $PX 2>&1 | grep -c ZOMBIE) - zombies0 ))
  if [ -n "$gone" ] && [ "$zombies" -gt 0 ]; then ok "F3 formed a dead binlog connection (source gave up after ~$gone s; $zombies connection(s) left open and silent on the connector side)"
  else bad "F3 did not form a dead connection (source gave up: [${gone:-no}], zombies: $zombies) -- the phase did not exercise dead-connection detection"; fi
  marker F3 "partition (self-recovery)" "$OBJECTIVE"
  if [ "$MARK_SEEN" = 1 ]; then record F3 "partition ${PARTITION_S}s" "$RTO" "recovered" "by itself"; ok "F3 partition: the connector recovered by itself in $RTO s"
  else
    record F3 "partition ${PARTITION_S}s" "$RTO" "NOT-RECOVERED" "no self-recovery"
    bad "F3 partition: the connector did not notice the dead binlog connection within $OBJECTIVE s (silent stall)"
    docker logs --since 10m $CC > "$WORK/f3_stall.log" 2>&1
    log "F3 connector log during the stall: $(grep -c . "$WORK/f3_stall.log") lines, last: $(tail -n 1 "$WORK/f3_stall.log" | cut -c1-200)"
    log "F3 operator recovery: restart the connector"
    t0=$(now); docker restart $CC >/dev/null 2>&1
    for i in $(seq 1 "$OBJECTIVE"); do [ "$(chq "SELECT count() FROM test.x_mark FINAL WHERE id=$MARK AND is_deleted=0")" = "1" ] && break; sleep 1; done
    r=$(( $(now) - t0 )); record F3 "partition ${PARTITION_S}s (operator restart)" "$r" "recovered-after-restart" "operator"
  fi
fi

# ------------------------------------------------------------------ F4 ClickHouse stopped
if phase F4; then
  log "=== F4: ClickHouse stopped for 60 s ==="
  docker stop -t 30 $CH >/dev/null 2>&1; sleep 60; docker start $CH >/dev/null 2>&1
  for i in $(seq 1 60); do CEX -q "SELECT 1" >/dev/null 2>&1 && break; sleep 2; done
  judge F4 "clickhouse down 60s"
fi

# ------------------------------------------------------------------ F5 ClickHouse frozen
if phase F5; then
  log "=== F5: ClickHouse frozen (pause) for 150 s ==="
  if docker pause $CH >/dev/null 2>&1; then sleep 150; docker unpause $CH >/dev/null 2>&1; judge F5 "clickhouse frozen 150s"
  else log "F5: docker pause is not supported here; skipped"; fi
fi

# ------------------------------------------------------------------ F6 MySQL clean restart
if phase F6; then
  log "=== F6: MySQL restarted cleanly while writers stream ==="
  docker restart -t 60 $MY >/dev/null 2>&1
  for i in $(seq 1 90); do MEX -e "SELECT 1" >/dev/null 2>&1 && break; sleep 2; done
  judge F6 "mysql restart"
fi

# ------------------------------------------------------------------ F7 MySQL kill -9 during a big commit
if phase F7; then
  log "=== F7: MySQL killed -9 while a big transaction is committing ==="
  big_txn $BIGBASE; BIGBASE=$((BIGBASE + 10000000)); sleep 8
  docker kill -s KILL $MY >/dev/null 2>&1; log "mysql killed -9"; wait $BIGPID
  sleep 3; docker start $MY >/dev/null 2>&1
  for i in $(seq 1 120); do MEX -e "SELECT 1" >/dev/null 2>&1 && break; sleep 2; done
  log "mysql back (crash recovery done)"
  judge F7 "mysql kill -9"
fi

# ------------------------------------------------------------------ F8 compression toggle storm
if phase F8; then
  log "=== F8: GLOBAL compression toggled 12 times under the writer mix ==="
  for i in $(seq 1 12); do
    if [ $((i % 2)) = 1 ]; then v=OFF; else v=ON; fi
    MEX -e "SET GLOBAL binlog_transaction_compression = $v" 2>/dev/null; sleep 5
  done
  MEX -e "SET GLOBAL binlog_transaction_compression = ON" 2>/dev/null
  judge F8 "compression toggle storm"
fi

# ------------------------------------------------------------------ F9 repeated kills
if phase F9; then
  log "=== F9: five kill -9 of the connector 20 s apart while a big payload is being applied ==="
  base=$(big_count); big_txn $BIGBASE; BIGBASE=$((BIGBASE + 10000000))
  wait_mid_apply "${base:-0}" || true
  for k in 1 2 3 4 5; do docker kill -s KILL $CC >/dev/null 2>&1; log "kill $k of 5"; sleep 20; done
  wait $BIGPID; judge F9 "5 x kill -9"
fi

# ------------------------------------------------------------------ F10 thin link
if phase F10; then
  log "=== F10: a 3 MB/s link on the binlog connection for 120 s while a big payload streams ==="
  proxy slow 3000000; big_txn $BIGBASE; BIGBASE=$((BIGBASE + 10000000)); sleep 120; proxy pass; wait $BIGPID
  judge F10 "thin link 3MB/s"
fi

# ------------------------------------------------------------------ F11 DDL storm
if phase F11; then
  log "=== F11: DDL storm (20 rounds of ADD/DROP COLUMN) under concurrent compressed inserts ==="
  ( for i in $(seq 1 2000); do MEX test -e "INSERT INTO x_ddl (a) VALUES ($i),($((i+100000)))" >/dev/null 2>&1; [ -f "$WORK/ddl_done" ] && break; done ) & INSPID=$!
  rm -f "$WORK/ddl_done"
  for i in $(seq 1 20); do
    MEX test -e "ALTER TABLE x_ddl ADD COLUMN c$i INT NOT NULL DEFAULT $i" 2>>"$WORK/ddl.err"
    MEX test -e "START TRANSACTION; INSERT INTO x_ddl (a, c$i) VALUES (-$i, $((i*10))); UPDATE x_ddl SET c$i = c$i + 1 WHERE id % 7 = 0; COMMIT;" 2>>"$WORK/ddl.err"
    [ "$i" -gt 1 ] && MEX test -e "ALTER TABLE x_ddl DROP COLUMN c$((i-1))" 2>>"$WORK/ddl.err"
    sleep 2
  done
  touch "$WORK/ddl_done"; wait $INSPID
  judge F11 "DDL storm"
fi

# ------------------------------------------------------------------ final: drain and compare
log "=== final: stop writers, drain, value-level comparison ==="
stop_writers
MEX -e "SET GLOBAL binlog_transaction_compression = ON" 2>/dev/null
marker FINAL drain 1200
[ "$MARK_SEEN" = 1 ] && ok "final drain completed in $RTO s" || bad "final drain did not complete within 1200 s"
sleep 10
vt x_hot "id,v,s,REPLACE(CAST(j AS CHAR),' ',''),CAST(ts AS CHAR)" "toString(id),toString(v),s,replaceAll(j,' ',''),toString(ts)"
vt x_log "id,w,n,payload,CAST(created AS CHAR)" "toString(id),toString(w),toString(n),payload,toString(created)"
vt x_acct_a "id,bal,seq" "toString(id),toString(bal),toString(seq)"
vt x_acct_b "id,bal,seq" "toString(id),toString(bal),toString(seq)"
vt x_wide "id,MD5(t),note" "toString(id),lower(hex(MD5(t))),note"
vt x_big "id,payload" "toString(id),payload"
vt x_mark "id,phase" "toString(id),phase"
dcols=$(MEX -N test -e "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='test' AND TABLE_NAME='x_ddl' AND COLUMN_NAME NOT LIKE 'my_row_id'" 2>/dev/null | tr -d '[:space:]')
ccols=$(chq "SELECT arrayStringConcat(groupArray(name),',') FROM (SELECT name FROM system.columns WHERE database='test' AND table='x_ddl' AND name NOT IN ('_version','is_deleted','my_row_id') ORDER BY position)")
expect "x_ddl column set after the DDL storm" "$ccols" "$dcols"
if [ -n "$dcols" ]; then
  vt x_ddl "$dcols" "$(echo "$dcols" | awk -F, '{for(i=1;i<=NF;i++) printf "%stoString(%s)", (i>1?",":""), $i}')"
fi
expect "transfer invariant on the replica (sum a + sum b)" "$(chq "SELECT (SELECT sum(bal) FROM test.x_acct_a FINAL WHERE is_deleted=0) + (SELECT sum(bal) FROM test.x_acct_b FINAL WHERE is_deleted=0)")" "400000000"
payloads=0
for f in $(MEX -N -e "SHOW BINARY LOGS" 2>/dev/null | awk '{print $1}' | tail -n 6); do
  n=$(MEX -N -e "SHOW BINLOG EVENTS IN '$f' LIMIT 20000" 2>/dev/null | awk -F'\t' '$3=="Transaction_payload"' | wc -l); payloads=$((payloads + n))
done
[ "$payloads" -gt 0 ] && ok "source wrote Transaction_payload events ($payloads in the last binlog files sampled)" || bad "no Transaction_payload event found on the source"
docker logs $CC > "$WORK/connector.log" 2>&1
if grep -q -e 'OutOfMemoryError' -e 'PayloadEventDecodingException' -e 'Stumbled upon long' "$WORK/connector.log"; then bad "connector log shows a decode failure or an OutOfMemoryError"
else ok "no decode failure and no OutOfMemoryError in the connector log ($(wc -l < "$WORK/connector.log") lines)"; fi
log "connector process restarts by the supervisor: $(restarts)"; cat "$WORK/supervisor.log"
log "engine restarts inside the process: $(grep -c 'Restarting the engine' "$WORK/connector.log")"
log "peak connector memory: $(awk '{print $2}' "$WORK/mem.log" | sort -h | tail -n 1)"
log "writer errors (deadlocks etc., part of the source's truth): $(cat "$WORK"/wl/*.err 2>/dev/null | grep -c ERROR)"
log "=== RECOVERY TIMES ==="; column -t -s $'\t' "$WORK/rto.tsv"
log "=== RESULT PASS=$PASS FAIL=$FAIL ($LABEL GTID=$GTID chaos) ==="
status=0; [ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ] || status=1
log "=== DONE (exit $status) ==="
exit "$status"
