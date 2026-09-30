#!/usr/bin/env bash
# Compressed binlog transactions larger than 2 GiB and 4 GiB uncompressed through the lightweight
# sink connector (spec 01.08 section 3.2.1), value level. Companion of
# binlog_transaction_compression.sh and binlog_transaction_compression_hard.sh.
#
#   JAR=<lightweight jar> [GTID=on|off] [LABEL=<tag>] [IMAGE=<image providing java 17>] [WORK=<scratch dir>] \
#   [EXPECT_STOCK_FAILURE=1] tests/e2e/binlog_transaction_compression_large.sh
#   Needs podman or docker (the script calls `docker`) and the images mysql:8.0 and
#   clickhouse/clickhouse-server:24.8. Disk: ~25 GB of scratch in the container storage.
#
# Source: MySQL 8.0, binlog_transaction_compression=ON (level 3), gtid per GTID. Connector heap 2 GiB,
# binlog.transaction.compression.check=require. Every case is asserted per key AND by whole-table
# value-level CRC (MySQL vs ClickHouse FINAL).
#   L1  ONE transaction of ~2.4 GB uncompressed (1.3M rows x ~1.8 KB): past Integer.MAX_VALUE, the
#       limit of the stock decoder; UPDATE and DELETE at the tail of the same transaction
#   L2  ONE transaction of ~4.6 GB uncompressed (2.5M rows): past 2^32
#   L3  kill -9 while an L1-sized payload is being applied; restart; converge
#   L4  after the large ones: a compressed and a session-uncompressed transaction (the stream continues)
#   Source-side proof: SHOW BINLOG EVENTS shows each large transaction as ONE Transaction_payload with
#   decompressed_size above 2^31 (L1, L3) and 2^32 (L2).
# EXPECT_STOCK_FAILURE=1 (negative control, for a build WITHOUT the streaming decoder): runs L1 only and
# passes when the connector stops with "Stumbled upon long even though int expected" and the replica
# does not receive the transaction.
# Output ends with "RESULT PASS=n FAIL=m"; exit status 0 only when FAIL=0 and at least one check ran.
set -uo pipefail
JAR="${JAR:?set JAR}"; GTID="${GTID:-on}"; LABEL="${LABEL:-bcl}"; STOCK="${EXPECT_STOCK_FAILURE:-0}"
IMAGE="${IMAGE:-docker.io/eclipse-temurin:17-jre}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
TEMPLATE="${TEMPLATE:-$REPO/sink-connector-lightweight/docker/config.yml}"; LOG4J="${LOG4J:-$REPO/sink-connector-lightweight/docker/log4j2.xml}"; WORK="${WORK:-/tmp/csc-e2e-bcl-$LABEL}"; mkdir -p "$WORK"
POD=cscbcl-$LABEL; MY=mysql-bcl-$LABEL; CH=ch-bcl-$LABEL; CC=csc-bcl-$LABEL
L1_ROWS=1300000; L2_ROWS=2500000; L3_BASE=10000000
MEX(){ docker exec -i $MY mysql -uroot -proot "$@"; }
CEX(){ docker exec -i $CH clickhouse-client -u root --password root "$@"; }
log(){ echo "[$(date +%H:%M:%S)] $*"; }
PASS=0; FAIL=0
ok(){ echo "PASS  $*"; PASS=$((PASS+1)); }
bad(){ echo "FAIL  $*"; FAIL=$((FAIL+1)); }
expect(){ if [ "$2" = "$3" ]; then ok "$1 = $3"; else bad "$1: got [$2] expected [$3]"; fi; }
myq(){ MEX -N test -e "$1" 2>/dev/null | tr -d '[:space:]'; }
chq(){ CEX -q "$1" 2>/dev/null | tr -d '[:space:]'; }
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
# Wait until the live row count on the replica is stable at (or above) a target.
wait_stable(){
  local q="$1" target="$2" tries="$3" prev=-1 stable=0 c i
  for i in $(seq 1 "$tries"); do
    c=$(chq "$q"); if [ "$c" = "$prev" ] && [ "${c:-0}" -ge "$target" ]; then stable=$((stable+1)); [ $stable -ge 3 ] && return 0; else stable=0; fi
    prev=$c; sleep 5
  done
  return 1
}
errscan(){ docker logs $CC 2>&1 | grep -iE ' ERROR |Exception|FATAL|refus|terminal' | grep -viE 'DEBUG|self-test FAILED' | tail -n "${1:-8}" | cut -c1-240; }
# Save the connector log to a file and search THAT: `docker logs | grep -q` under pipefail reports a
# match as a failure (grep -q exits early, docker logs dies of SIGPIPE, the pipeline returns 141).
connector_log_has(){ docker logs $CC > "$WORK/connector.log" 2>&1; grep -q -e "$1" "$WORK/connector.log"; }
# The one Transaction_payload of the first transaction in the current binlog file.
payload_size(){ MEX -N -e "SHOW BINLOG EVENTS IN '$1' LIMIT 6" 2>/dev/null | awk -F'\t' '$3=="Transaction_payload"{print $6}' | sed -n 's/.*decompressed_size=\([0-9]*\).*/\1/p' | head -n 1; }
conn_mem(){ docker stats --no-stream $CC 2>/dev/null | tail -n 1 | awk '{print $4, $5, $6}'; }
current_binlog(){ MEX -N -e "SHOW BINARY LOGS" 2>/dev/null | tail -n 1 | awk '{print $1}'; }
start_connector(){
  docker run -d --name $CC --pod $POD --runtime runc \
    -v "$WORK/config.yml:/config.yml:ro,Z" -v "$LOG4J:/log4j2.xml:ro,Z" -v "$JAR:/app.jar:ro,Z" \
    --entrypoint sh "$IMAGE" -c \
    "java -Xms2g -Xmx2g -Dlog4j2.configurationFile=log4j2.xml -jar /app.jar /config.yml com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication" \
    >/dev/null 2>&1 && log "connector started ($CC, heap 2 GiB)"
}
cleanup(){ for c in $CC $MY $CH; do docker rm -f "$c" >/dev/null 2>&1; done; docker pod rm -f $POD >/dev/null 2>&1; }
trap cleanup EXIT

# One large transaction into $1 (rows $2..$3), with an UPDATE and a DELETE at its tail.
large_txn(){
  local tbl="$1" lo="$2" hi="$3"
  MEX --force test <<SQL
SET SESSION cte_max_recursion_depth = 4000000;
START TRANSACTION;
INSERT INTO $tbl (id, payload)
  WITH RECURSIVE n(i) AS (SELECT $lo UNION ALL SELECT i+1 FROM n WHERE i < $hi)
  SELECT i, CONCAT(LPAD(i, 10, '0'), '-', REPEAT(MD5(i), 8), '-', REPEAT('payload-row-', 130)) FROM n;
UPDATE $tbl SET payload = 'tail-update' WHERE id IN ($lo, $hi);
DELETE FROM $tbl WHERE id = $((lo + 1));
COMMIT;
SQL
}

log "=== $LABEL: JAR=$JAR GTID=$GTID (large compressed transactions${STOCK:+, stock=$STOCK}) ==="
cleanup; docker pod create --name $POD >/dev/null 2>&1 && log "pod up"
MYCNF=$WORK/mysqld.cnf
{
  echo "[mysqld]"; echo "max_connections=1000"; echo "default_authentication_plugin=mysql_native_password"
  echo "binlog_transaction_compression = ON"; echo "binlog_transaction_compression_level_zstd = 3"
  if [ "$GTID" = on ]; then echo "gtid-mode = on"; echo "enforce-gtid-consistency = true"; else echo "gtid-mode = off"; echo "enforce-gtid-consistency = false"; fi
  echo "binlog_row_image=FULL"; echo "cte_max_recursion_depth=4000000"; echo "max_allowed_packet=1073741824"
  echo "binlog_cache_size=268435456"; echo "innodb_buffer_pool_size=4G"; echo "innodb_redo_log_capacity=8589934592"
} > "$MYCNF"
sed -e 's/database.hostname: "mysql-master"/database.hostname: "127.0.0.1"/' \
    -e 's/clickhouse.server.url: "clickhouse"/clickhouse.server.url: "127.0.0.1"/' \
    -e 's#jdbc:clickhouse://clickhouse:8123#jdbc:clickhouse://127.0.0.1:8123#g' "$TEMPLATE" > "$WORK/config.yml"
{ echo; echo "# --- binlog compression large cases ---"; echo "name: \"bcl-$LABEL\""; echo "topic.prefix: \"sink-bcl-$LABEL\""; echo "binlog.transaction.compression.check: \"require\""; } >> "$WORK/config.yml"

docker run -d --name $CH --pod $POD -e CLICKHOUSE_USER=root -e CLICKHOUSE_PASSWORD=root \
  -e CLICKHOUSE_DB=test -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 --ulimit nofile=262144:262144 \
  clickhouse/clickhouse-server:24.8 >/dev/null 2>&1 && log "ch started"
docker run -d --name $MY --pod $POD -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=test \
  -e TZ=US/Central -v "$MYCNF:/etc/mysql/conf.d/my_custom.cnf:ro,Z" mysql:8.0 >/dev/null 2>&1 && log "mysql started"
for i in $(seq 1 60); do MEX -e "SELECT 1" >/dev/null 2>&1 && { log "mysql up"; break; }; sleep 3; done
for i in $(seq 1 60); do CEX -q "SELECT 1" >/dev/null 2>&1 && { log "ch up"; break; }; sleep 3; done
log "source: gtid_mode=$(myq 'SELECT @@gtid_mode') compression=$(myq 'SELECT @@binlog_transaction_compression') level=$(myq 'SELECT @@binlog_transaction_compression_level_zstd') version=$(myq 'SELECT @@version')"

MEX --force test <<'SQL'
CREATE TABLE l_big  (id INT NOT NULL PRIMARY KEY, payload VARCHAR(2048));
CREATE TABLE l_huge (id INT NOT NULL PRIMARY KEY, payload VARCHAR(2048));
CREATE TABLE l_kill (id INT NOT NULL PRIMARY KEY, payload VARCHAR(2048));
CREATE TABLE l_after (id INT NOT NULL PRIMARY KEY, v INT);
INSERT INTO l_after VALUES (1, 1);
SQL
start_connector
for i in $(seq 1 60); do [ "$(chq "SELECT count() FROM test.l_after FINAL WHERE is_deleted=0")" = "1" ] && break; sleep 3; done
expect "seed replicated" "$(chq "SELECT count() FROM test.l_after FINAL WHERE is_deleted=0")" "1"

# ------------------------------------------------------------------ L1
log "=== L1: one transaction of ~2.4 GB uncompressed ==="
MEX -e "FLUSH BINARY LOGS" 2>/dev/null; f=$(current_binlog)
t0=$(date +%s); large_txn l_big 1 $L1_ROWS; log "L1 committed on the source in $(( $(date +%s) - t0 )) s"
sz=$(payload_size "$f"); log "L1 source: $f Transaction_payload decompressed_size=$sz; binlog file bytes=$(MEX -N -e 'SHOW BINARY LOGS' 2>/dev/null | awk -v f="$f" '$1==f{print $2}')"
[ -n "$sz" ] && [ "$sz" -gt 2147483647 ] && ok "L1 is ONE Transaction_payload above 2^31 ($sz bytes)" || bad "L1 payload size [$sz] not above 2^31"

if [ "$STOCK" = 1 ]; then
  sleep 120
  if connector_log_has 'Stumbled upon long even though int expected'; then ok "stock build stopped on the >2 GiB payload (Stumbled upon long even though int expected)"; else bad "stock build did not report the int-limit failure"; fi
  c=$(chq "SELECT count() FROM test.l_big FINAL WHERE is_deleted=0")
  [ "${c:-0}" -lt $((L1_ROWS - 1)) ] && ok "stock build did not replicate the >2 GiB transaction (replica rows=$c)" || bad "stock build replicated it (rows=$c)"
  log "connector errors (last 6):"; errscan 6
  log "=== RESULT PASS=$PASS FAIL=$FAIL ($LABEL GTID=$GTID large, negative control) ==="
  status=0; [ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ] || status=1
  log "=== DONE (exit $status) ==="; exit "$status"
fi

t0=$(date +%s)
wait_stable "SELECT count() FROM test.l_big FINAL WHERE is_deleted=0" $((L1_ROWS - 1)) 360 || log "l_big not stable at $((L1_ROWS - 1))"
log "L1 apply time: $(( $(date +%s) - t0 )) s; connector memory: $(conn_mem)"
expect "L1 id=$L1_ROWS payload (tail UPDATE of a 2.4 GB payload)" "$(chq "SELECT payload FROM test.l_big FINAL WHERE id=$L1_ROWS AND is_deleted=0")" "tail-update"
expect "L1 id=2 gone (tail DELETE)" "$(chq "SELECT count() FROM test.l_big FINAL WHERE id=2 AND is_deleted=0")" "0"
vt l_big "id,payload" "toString(id),toString(payload)"

# ------------------------------------------------------------------ L2
log "=== L2: one transaction of ~4.6 GB uncompressed (past 2^32) ==="
MEX -e "FLUSH BINARY LOGS" 2>/dev/null; f=$(current_binlog)
t0=$(date +%s); large_txn l_huge 1 $L2_ROWS; log "L2 committed on the source in $(( $(date +%s) - t0 )) s"
sz=$(payload_size "$f"); log "L2 source: $f Transaction_payload decompressed_size=$sz; binlog file bytes=$(MEX -N -e 'SHOW BINARY LOGS' 2>/dev/null | awk -v f="$f" '$1==f{print $2}')"
[ -n "$sz" ] && [ "$sz" -gt 4294967295 ] && ok "L2 is ONE Transaction_payload above 2^32 ($sz bytes)" || bad "L2 payload size [$sz] not above 2^32"
t0=$(date +%s)
wait_stable "SELECT count() FROM test.l_huge FINAL WHERE is_deleted=0" $((L2_ROWS - 1)) 720 || log "l_huge not stable at $((L2_ROWS - 1))"
log "L2 apply time: $(( $(date +%s) - t0 )) s; connector memory: $(conn_mem)"
expect "L2 id=$L2_ROWS payload (tail UPDATE of a 4.6 GB payload)" "$(chq "SELECT payload FROM test.l_huge FINAL WHERE id=$L2_ROWS AND is_deleted=0")" "tail-update"
expect "L2 id=2 gone (tail DELETE)" "$(chq "SELECT count() FROM test.l_huge FINAL WHERE id=2 AND is_deleted=0")" "0"
vt l_huge "id,payload" "toString(id),toString(payload)"

# ------------------------------------------------------------------ L3
log "=== L3: kill -9 while an L1-sized payload is being applied ==="
MEX -e "FLUSH BINARY LOGS" 2>/dev/null; f=$(current_binlog)
large_txn l_kill $((L3_BASE + 1)) $((L3_BASE + L1_ROWS))
sz=$(payload_size "$f"); [ -n "$sz" ] && [ "$sz" -gt 2147483647 ] && ok "L3 is ONE Transaction_payload above 2^31 ($sz bytes)" || bad "L3 payload size [$sz] not above 2^31"
for i in $(seq 1 600); do c=$(chq "SELECT count() FROM test.l_kill"); [ "${c:-0}" -gt 150000 ] && break; sleep 1; done
before=$(chq 'SELECT count() FROM test.l_kill')
docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9 mid-apply (replica rows before the kill: $before of $L1_ROWS)"
[ "${before:-0}" -gt 0 ] && [ "${before:-0}" -lt "$L1_ROWS" ] && ok "L3 kill landed inside the payload ($before of $L1_ROWS rows applied)" || bad "L3 kill did not land inside the payload (rows=$before)"
sleep 3; docker start $CC >/dev/null 2>&1; log "connector restarted"
wait_stable "SELECT count() FROM test.l_kill FINAL WHERE is_deleted=0" $((L1_ROWS - 1)) 480 || log "l_kill not stable at $((L1_ROWS - 1))"
log "l_kill after restart: live=$(chq 'SELECT count() FROM test.l_kill FINAL WHERE is_deleted=0') raw(incl. redeliveries)=$(chq 'SELECT count() FROM test.l_kill')"
expect "L3 id=$((L3_BASE + L1_ROWS)) payload after the restart" "$(chq "SELECT payload FROM test.l_kill FINAL WHERE id=$((L3_BASE + L1_ROWS)) AND is_deleted=0")" "tail-update"
expect "L3 id=$((L3_BASE + 2)) gone after the restart" "$(chq "SELECT count() FROM test.l_kill FINAL WHERE id=$((L3_BASE + 2)) AND is_deleted=0")" "0"
vt l_kill "id,payload" "toString(id),toString(payload)"

# ------------------------------------------------------------------ L4
log "=== L4: the stream continues: a compressed and a session-uncompressed transaction ==="
MEX --force test <<'SQL'
START TRANSACTION; INSERT INTO l_after VALUES (2, 2), (3, 3); UPDATE l_after SET v = 11 WHERE id = 1; COMMIT;
SET SESSION binlog_transaction_compression = OFF;
START TRANSACTION; INSERT INTO l_after VALUES (4, 4); DELETE FROM l_after WHERE id = 3; COMMIT;
SQL
wait_stable "SELECT count() FROM test.l_after FINAL WHERE is_deleted=0" 3 60 || log "l_after not at 3"
expect "L4 l_after id=1 v" "$(chq "SELECT v FROM test.l_after FINAL WHERE id=1 AND is_deleted=0")" "11"
vt l_after "id,v" "toString(id),toString(v)"

log "=== evidence ==="
log "preflight:"; docker logs $CC 2>&1 | grep -i -E 'self-test passed|binlog_transaction_compression=' | head -n 3 | cut -c1-240
docker logs $CC > "$WORK/connector.log" 2>&1
loglines=$(wc -l < "$WORK/connector.log")
if [ "${loglines:-0}" -lt 10 ]; then bad "connector log could not be read ($loglines lines): the decode-failure check has nothing to inspect"
elif grep -q -e 'Stumbled upon long' -e 'OutOfMemoryError' -e 'PayloadEventDecodingException' "$WORK/connector.log"; then bad "connector log shows a decode failure or OOM"
else ok "no decode failure and no OutOfMemoryError in the connector log ($loglines lines inspected)"; fi
log "connector errors (last 10):"; errscan 10
log "=== RESULT PASS=$PASS FAIL=$FAIL ($LABEL GTID=$GTID large) ==="
status=0; [ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ] || status=1
log "=== DONE (exit $status) ==="
exit "$status"
