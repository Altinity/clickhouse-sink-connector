#!/usr/bin/env bash
# Large rows and large transactions through the spill-to-disk INSERT path (spec 03.06 section 3.4),
# at a fixed connector heap, value level.
#
#   JAR=<lightweight jar> [LABEL=lrs] [HEAP=4g] [ROW_MB="64 128 256 512"] [BINARY_MODE=bytes|base64]
#   [TXN_ROWS=1000000] [TXN_ROW_KB=2] [KILL=1] [IMAGE=<image providing java 17>] [WORK=<scratch dir>]
#   tests/e2e/large_row_spill.sh
#   Needs podman or docker (the script calls `docker`) and the images mysql:8.0,
#   clickhouse/clickhouse-server:24.8 and eclipse-temurin:17-jre.
#
# Source: MySQL 8.0, GTID on, binlog_transaction_compression=ON, max_allowed_packet 1 GiB. Connector heap
# HEAP with -XX:+ExitOnOutOfMemoryError; whenever the process exits the script restarts it (as systemd
# Restart=always would), at most 3 times per case, and counts the OutOfMemoryError exits.
# Cases (each asserted by MD5 / CRC of the values, MySQL vs ClickHouse FINAL):
#   R<n>  for each n in ROW_MB: ONE transaction inserting a row of n MiB (LONGBLOB, compressible) and a small
#         row, then UPDATE of the big row's small column (a second full image of the big row).
#         Before the spill path, a 256 MiB row exhausted a 4 GiB heap and crash-looped on every restart.
#   K     (KILL=1) kill -9 while the largest row is being applied; the restart must re-apply it exactly.
#   T     ONE transaction of TXN_ROWS rows x 1 KiB of RANDOM_BYTES, then UPDATE of every row to TXN_ROW_KB KiB
#         in the same transaction (~TXN_ROWS x TXN_ROW_KB KiB incompressible).
#   S     the progress line shows chunks sent from a spill file, and no spill file is left behind.
# Output ends with "RESULT PASS=n FAIL=m"; exit status 0 only when FAIL=0 and at least one check ran.
set -uo pipefail
JAR="${JAR:?set JAR}"; LABEL="${LABEL:-lrs}"; HEAP="${HEAP:-4g}"; ROW_MB="${ROW_MB:-64 128 256 512}"
BINARY_MODE="${BINARY_MODE:-bytes}"; TXN_ROWS="${TXN_ROWS:-1000000}"; TXN_ROW_KB="${TXN_ROW_KB:-2}"; KILL="${KILL:-1}"
IMAGE="${IMAGE:-docker.io/eclipse-temurin:17-jre}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
TEMPLATE="${TEMPLATE:-$REPO/sink-connector-lightweight/docker/config.yml}"; LOG4J="${LOG4J:-$REPO/sink-connector-lightweight/docker/log4j2.xml}"
WORK="${WORK:-/tmp/csc-e2e-lrs-$LABEL}"; mkdir -p "$WORK"; chmod 777 "$WORK"
POD=csclrs-$LABEL; MY=mysql-lrs-$LABEL; CH=ch-lrs-$LABEL; CC=csc-lrs-$LABEL
MEX(){ docker exec -i $MY mysql -uroot -proot "$@"; }
CEX(){ docker exec -i $CH clickhouse-client -u root --password root "$@"; }
log(){ echo "[$(date +%H:%M:%S)] $*"; }
PASS=0; FAIL=0; ok(){ echo "PASS  $*"; PASS=$((PASS+1)); }; bad(){ echo "FAIL  $*"; FAIL=$((FAIL+1)); }
chq(){ CEX -q "$1" 2>/dev/null | tr -d '[:space:]'; }; myq(){ MEX -N test -e "$1" 2>/dev/null | tr -d '[:space:]'; }
now(){ date +%s; }
running(){ [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }
STARTS=0; OOMS=0; CASE_STARTS=0
start_connector(){
  STARTS=$((STARTS+1)); CASE_STARTS=$((CASE_STARTS+1))
  docker rm -f $CC >/dev/null 2>&1
  docker run -d --name $CC --pod $POD --runtime runc \
    -v "$WORK:/work:Z" -v "$WORK/config.yml:/config.yml:ro,Z" -v "$LOG4J:/log4j2.xml:ro,Z" -v "$JAR:/app.jar:ro,Z" \
    --entrypoint sh "$IMAGE" -c \
    "exec java -Xms$HEAP -Xmx$HEAP -XX:+ExitOnOutOfMemoryError -Xlog:gc:file=/work/gc$STARTS.log:uptime -Dlog4j2.configurationFile=log4j2.xml -jar /app.jar /config.yml com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication > /work/conn$STARTS.log 2>&1" \
    >/dev/null 2>&1 && log "connector start #$STARTS (heap $HEAP)"
}
# wait for a ClickHouse predicate; restart the connector whenever it exits (at most 3 restarts per case)
await(){ local desc="$1" q="$2" want="$3" limit="$4" t0 r=""; t0=$(now); CASE_STARTS=0
  while [ $(( $(now) - t0 )) -lt "$limit" ]; do
    r=$(chq "$q"); [ "$r" = "$want" ] && { AWAIT_S=$(( $(now) - t0 )); return 0; }
    if ! running $CC; then
      grep -q OutOfMemoryError "$WORK/conn$STARTS.log" 2>/dev/null && OOMS=$((OOMS+1))
      [ "$CASE_STARTS" -ge 3 ] && { log "$desc: connector exited again, restart budget spent"; return 1; }
      log "$desc: connector exited ($(grep -m1 -o 'OutOfMemoryError.\{0,50\}' "$WORK/conn$STARTS.log")), restarting"
      start_connector
    fi
    sleep 3
  done; log "$desc: not converged in $limit s (last=[$r])"; return 1; }
gcpeak(){ cat "$WORK"/gc*.log 2>/dev/null | grep -oE 'Pause[^>]*->[0-9]+M' | grep -oE '[0-9]+M$' | tr -d M | sort -n | tail -n 1; }

docker pod rm -f $POD >/dev/null 2>&1; docker pod create --name $POD >/dev/null 2>&1 && log "pod up"
docker run -d --name $CH --pod $POD -e CLICKHOUSE_USER=root -e CLICKHOUSE_PASSWORD=root \
  --ulimit nofile=262144:262144 docker.io/clickhouse/clickhouse-server:24.8 >/dev/null
docker run -d --name $MY --pod $POD -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=test docker.io/library/mysql:8.0 \
  --server-id=1 --log-bin=mysql-bin --binlog-format=ROW --binlog-row-image=FULL --gtid-mode=ON --enforce-gtid-consistency=ON \
  --binlog-transaction-compression=ON --max-allowed-packet=1073741824 \
  --innodb-log-file-size=2147483648 --innodb-buffer-pool-size=4294967296 >/dev/null
# Readiness over TCP: the image's first-start initialisation runs a temporary server with networking off, so
# a socket probe can succeed against it just before it shuts down for the real start.
for i in $(seq 1 90); do MEX -h127.0.0.1 -P3306 -e "SELECT 1" >/dev/null 2>&1 && break; sleep 2; done
for i in $(seq 1 60); do chq "SELECT 1" | grep -q 1 && break; sleep 2; done
log "source $(myq 'SELECT @@version') compression=$(myq 'SELECT @@binlog_transaction_compression') binary.handling.mode=$BINARY_MODE"
CEX -q "CREATE DATABASE IF NOT EXISTS test" >/dev/null 2>&1
MEX test -e "CREATE TABLE r (id INT NOT NULL PRIMARY KEY, b LONGBLOB, tag VARCHAR(32)); CREATE TABLE t (id INT NOT NULL PRIMARY KEY, b LONGBLOB); CREATE TABLE mark (id INT NOT NULL PRIMARY KEY);" 2>/dev/null
sed -e 's/database.hostname: "mysql-master"/database.hostname: "127.0.0.1"/' \
    -e 's/database.include.list: test/database.include.list: "test"/' \
    -e 's/clickhouse.server.url: "clickhouse"/clickhouse.server.url: "127.0.0.1"/' \
    -e 's#jdbc:clickhouse://clickhouse:8123#jdbc:clickhouse://127.0.0.1:8123#g' \
    -e '/^name:/d' -e '/^topic\.prefix:/d' -e '/^binary\.handling\.mode:/d' -e '/^insert\.spill\.directory:/d' \
    "$TEMPLATE" > "$WORK/config.yml"
{ echo; echo "name: \"lrs-$LABEL\""; echo "topic.prefix: \"sink-lrs-$LABEL\""; echo "binary.handling.mode: \"$BINARY_MODE\""
  echo "insert.spill.directory: \"/work/spill\""; } >> "$WORK/config.yml"
DEC=unhex; [ "$BINARY_MODE" = base64 ] && DEC=base64Decode
start_connector
MEX test -e "INSERT INTO mark VALUES (0)" 2>/dev/null
await "startup" "SELECT count() FROM test.mark FINAL WHERE is_deleted=0" 1 300 || bad "the connector did not come up"
big_txn(){ MEX test -e "START TRANSACTION; INSERT INTO r VALUES ($1, REPEAT('abcdefghij', $(( $2 * 104858 ))), 'big'), ($(($1+100000)), 'small', 'small'); UPDATE r SET tag='big-updated' WHERE id=$1; COMMIT;" 2>&1 | grep -v Warning; }
check_row(){ local id="$1" what="$2" m c
  m=$(myq "SELECT CONCAT(MD5(b),tag) FROM r WHERE id=$id"); c=$(chq "SELECT concat(lower(hex(MD5($DEC(b)))),tag) FROM test.r FINAL WHERE is_deleted=0 AND id=$id")
  [ -n "$m" ] && [ "$m" = "$c" ] && ok "$what value-exact in ${AWAIT_S}s (connector starts $STARTS, OOM exits $OOMS)" || bad "$what differs (mysql=$m clickhouse=$c)"; }
largest=0
for mb in $ROW_MB; do
  [ "$mb" -gt "$largest" ] && largest=$mb
  log "=== R$mb: a $mb MiB row inserted and updated in one transaction ==="
  big_txn "$mb" "$mb"
  if await "R$mb" "SELECT count() FROM test.r FINAL WHERE is_deleted=0 AND id=$mb AND tag='big-updated'" 1 900; then check_row "$mb" "R$mb ($mb MiB row)"
  else bad "R$mb: the $mb MiB row never arrived (connector starts $STARTS, OOM exits $OOMS)"; fi
done
if [ "$KILL" = 1 ] && [ "$largest" -gt 0 ]; then
  id=$((largest + 1)); log "=== K: kill -9 while a $largest MiB row is being applied ==="
  big_txn "$id" "$largest" & BT=$!
  wait $BT; sleep 2; docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9"
  if await "K" "SELECT count() FROM test.r FINAL WHERE is_deleted=0 AND id=$id AND tag='big-updated'" 1 900; then check_row "$id" "K (re-applied after kill -9)"
  else bad "K: the row was not re-applied after the kill"; fi
fi
if [ "$TXN_ROWS" -gt 0 ]; then
  log "=== T: one transaction of $TXN_ROWS rows, every row then updated to $TXN_ROW_KB KiB (incompressible) ==="
  MEX test -e "SET SESSION cte_max_recursion_depth=100000000; START TRANSACTION; INSERT INTO t WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < $TXN_ROWS) SELECT i, RANDOM_BYTES(1024) FROM n; UPDATE t SET b = REPEAT(b, $TXN_ROW_KB); COMMIT;" 2>&1 | grep -v Warning
  if await "T" "SELECT count() FROM test.t FINAL WHERE is_deleted=0 AND length($DEC(b))=$((TXN_ROW_KB*1024))" "$TXN_ROWS" 1800; then
    m=$(myq "SELECT CONCAT(COUNT(*),':',SUM(CRC32(b))) FROM t"); c=$(chq "SELECT concat(toString(count()),':',toString(sum(CRC32($DEC(b))))) FROM test.t FINAL WHERE is_deleted=0")
    [ "$m" = "$c" ] && ok "T ($TXN_ROWS rows x $TXN_ROW_KB KiB in one transaction) value-exact in ${AWAIT_S}s (OOM exits $OOMS)" || bad "T differs (mysql=$m clickhouse=$c)"
  else bad "T never converged (OOM exits $OOMS)"; fi
fi
log "=== S: spill evidence ==="
spilled=$(cat "$WORK"/conn*.log 2>/dev/null | grep -c 'sent from a spill file')
[ "$spilled" -gt 0 ] && ok "chunks sent from a spill file: $spilled" || bad "no chunk was sent from a spill file"
left=$(find "$WORK/spill" -name 'insert-*.values' 2>/dev/null | wc -l)
[ "$left" -eq 0 ] && ok "no spill file left behind" || bad "$left spill file(s) left behind"
[ "$OOMS" -eq 0 ] && ok "OutOfMemoryError exits = 0 (heap $HEAP)" || bad "OutOfMemoryError exits = $OOMS (heap $HEAP)"
log "connector starts=$STARTS OOM exits=$OOMS GC peak (heap after GC, MiB)=$(gcpeak)"
docker stop -t 30 $CC >/dev/null 2>&1
log "=== RESULT PASS=$PASS FAIL=$FAIL ($LABEL heap $HEAP mode $BINARY_MODE rows [$ROW_MB] txn $TXN_ROWS) ==="
log "=== DONE (exit $([ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ] && echo 0 || echo 1)) ==="
[ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ]
