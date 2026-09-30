#!/usr/bin/env bash
# End-to-end verification of MySQL binlog transaction compression through the
# lightweight sink connector (spec 01.08), at the VALUE level.
#
#   JAR=<lightweight jar> [GTID=on|off] [COMP=on|off] [LABEL=<tag>] [CHECK=auto|require|skip] \
#   [IMAGE=<image providing java 17>] [WORK=<scratch dir>] tests/e2e/binlog_transaction_compression.sh
#
# Needs podman or docker (the script calls `docker`; alias it to podman if needed) and
# the images mysql:8.0, clickhouse/clickhouse-server:24.8 and a JRE image for the jar.
# MySQL 8.0 (binlog_transaction_compression per COMP, gtid_mode per GTID) -> the
# connector jar (one pod, runc) -> ClickHouse 24.8. Phases:
#   P1 steady-state CDC: a compressed multi-statement transaction whose later
#      statements touch keys its first (multi-row) statement inserted; a 300k-row
#      single-statement transaction (many ROWS events inside ONE payload); a
#      session-level compression toggle (mixed stream); DDL then DML; a keyless table.
#   P2 kill -9, write the same shape of transaction at once, restart (floor seeded
#      from the durable mark, spec 02.02 section 3.5).
#   P3 kill -9, drop the durable mark, write, restart (clock-seeded floor: the first
#      start after an upgrade).
#   P4 kill -9 while a 400k-row compressed transaction is being applied, restart
#      (spec 01.08 section 3.3).
#   P5 proof that the binlog carried Transaction_payload events, the preflight log
#      line (spec 01.08 section 3.4) and an error scan.
# Every table is compared by row count and a CRC over the canonical text of every
# column (FINAL, live rows); the per-key expectations of P1-P4 are asserted
# explicitly. The output ends with "RESULT PASS=n FAIL=m".
set -uo pipefail
JAR="${JAR:?set JAR to the lightweight jar}"; GTID="${GTID:-on}"; COMP="${COMP:-on}"; LABEL="${LABEL:-bc}"; CHECK="${CHECK:-auto}"
IMAGE="${IMAGE:-docker.io/eclipse-temurin:17-jre}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
TEMPLATE="${TEMPLATE:-$REPO/sink-connector-lightweight/docker/config.yml}"
LOG4J="${LOG4J:-$REPO/sink-connector-lightweight/docker/log4j2.xml}"
WORK="${WORK:-/tmp/csc-e2e-bc-$LABEL}"; mkdir -p "$WORK"
POD=cscbc-$LABEL; MY=mysql-bc-$LABEL; CH=ch-bc-$LABEL; CC=csc-bc-$LABEL
MEX(){ docker exec -i $MY mysql -uroot -proot "$@"; }
CEX(){ docker exec -i $CH clickhouse-client -u root --password root "$@"; }
log(){ echo "[$(date +%H:%M:%S)] $*"; }
PASS=0; FAIL=0
ok(){ echo "PASS  $*"; PASS=$((PASS+1)); }
bad(){ echo "FAIL  $*"; FAIL=$((FAIL+1)); }
expect(){ if [ "$2" = "$3" ]; then ok "$1 = $3"; else bad "$1: got [$2] expected [$3]"; fi; }
myq(){ MEX -N test -e "$1" 2>/dev/null | tr -d '[:space:]'; }
chq(){ CEX -q "$1" 2>/dev/null | tr -d '[:space:]'; }
wait_ch(){ local i; for i in $(seq 1 "$3"); do [ "$(chq "$1")" = "$2" ] && return 0; sleep 2; done; return 1; }
vt(){
  local tbl="$1" mcols="$2" ccols="$3" mrows crows msum csum
  mrows=$(myq "SELECT count(*) FROM $tbl")
  msum=$(myq "SELECT IFNULL(SUM(CRC32(CONCAT_WS('~',$mcols))),0) FROM $tbl")
  crows=$(chq "SELECT count() FROM test.$tbl FINAL WHERE is_deleted=0")
  csum=$(chq "SELECT toInt64(sum(CRC32(concat_ws('~',$ccols)))) FROM test.$tbl FINAL WHERE is_deleted=0")
  if [ -n "$msum" ] && [ "$mrows" = "$crows" ] && [ "$msum" = "$csum" ]; then ok "$tbl value-level (rows=$mrows)";
  else bad "$tbl mysql_rows=$mrows target_rows=$crows mysql_crc=$msum target_crc=$csum"
    echo "  MySQL:"; MEX -N test -e "SELECT $mcols FROM $tbl ORDER BY 1 LIMIT 12" 2>/dev/null
    echo "  CH   :"; CEX -q "SELECT $ccols FROM test.$tbl FINAL WHERE is_deleted=0 ORDER BY 1 LIMIT 12" 2>/dev/null
  fi
}
start_connector(){
  docker run -d --name $CC --pod $POD --runtime runc \
    -v "$WORK/config.yml:/config.yml:ro,Z" -v "$LOG4J:/log4j2.xml:ro,Z" -v "$JAR:/app.jar:ro,Z" \
    --entrypoint sh "$IMAGE" -c \
    "java -Xms2g -Xmx2g -Dlog4j2.configurationFile=log4j2.xml -jar /app.jar /config.yml com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication" \
    >/dev/null 2>&1 && log "connector started ($CC)"
}

cleanup(){ for c in $CC $MY $CH; do docker rm -f "$c" >/dev/null 2>&1; done; docker pod rm -f $POD >/dev/null 2>&1; }
# Containers are removed on every exit path; the exit status is the verdict (0 only when FAIL=0).
trap cleanup EXIT

log "=== $LABEL: JAR=$JAR GTID=$GTID COMP=$COMP CHECK=$CHECK ==="
cleanup; docker pod create --name $POD >/dev/null 2>&1 && log "pod up"

# MySQL configuration for this run
{
  echo "[mysqld]"; echo "max_connections=100000"; echo "default_authentication_plugin=mysql_native_password"
  echo "binlog_transaction_compression = $([ "$COMP" = on ] && echo ON || echo OFF)"
  echo "binlog_transaction_compression_level_zstd = 3"
  if [ "$GTID" = on ]; then echo "gtid-mode = on"; echo "enforce-gtid-consistency = true"; else echo "gtid-mode = off"; echo "enforce-gtid-consistency = false"; fi
  echo "local_infile = on"; echo "sql_generate_invisible_primary_key=1"; echo "binlog_row_image=FULL"; echo "cte_max_recursion_depth=400000"
} > "$WORK/mysqld.cnf"
# connector configuration: the repository's docker config, addressed through the pod
sed -e 's/database.hostname: "mysql-master"/database.hostname: "127.0.0.1"/' \
    -e 's/clickhouse.server.url: "clickhouse"/clickhouse.server.url: "127.0.0.1"/' \
    -e 's#jdbc:clickhouse://clickhouse:8123#jdbc:clickhouse://127.0.0.1:8123#g' "$TEMPLATE" > "$WORK/config.yml"
{ echo; echo "# --- binlog compression e2e ---"; echo "name: \"bc-$LABEL\""; echo "topic.prefix: \"sink-bc-$LABEL\""; echo "binlog.transaction.compression.check: \"$CHECK\""; } >> "$WORK/config.yml"

docker run -d --name $CH --pod $POD -e CLICKHOUSE_USER=root -e CLICKHOUSE_PASSWORD=root \
  -e CLICKHOUSE_DB=test -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 --ulimit nofile=262144:262144 \
  clickhouse/clickhouse-server:24.8 >/dev/null 2>&1 && log "ch started"
docker run -d --name $MY --pod $POD -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=test \
  -e TZ=US/Central -v "$WORK/mysqld.cnf:/etc/mysql/conf.d/my_custom.cnf:ro,Z" mysql:8.0 >/dev/null 2>&1 && log "mysql started"
for i in $(seq 1 60); do MEX -e "SELECT 1" >/dev/null 2>&1 && { log "mysql up"; break; }; sleep 3; done
for i in $(seq 1 60); do CEX -q "SELECT 1" >/dev/null 2>&1 && { log "ch up"; break; }; sleep 3; done
log "source: gtid_mode=$(myq 'SELECT @@gtid_mode') binlog_transaction_compression=$(myq 'SELECT @@binlog_transaction_compression') level=$(myq 'SELECT @@binlog_transaction_compression_level_zstd') version=$(myq 'SELECT @@version')"

log "=== seed ==="
MEX --force test <<'SQL'
CREATE TABLE t_a (id INT NOT NULL PRIMARY KEY, v INT, s VARCHAR(64));
INSERT INTO t_a VALUES (1,1,'seed');
CREATE TABLE t_b (id INT NOT NULL PRIMARY KEY, v INT, s VARCHAR(64));
INSERT INTO t_b VALUES (1,1,'seed');
CREATE TABLE t_big (id INT NOT NULL PRIMARY KEY, payload VARCHAR(200));
CREATE TABLE t_big2 (id INT NOT NULL PRIMARY KEY, payload VARCHAR(200));
CREATE TABLE t_keyless (a INT, b VARCHAR(16));
INSERT INTO t_keyless VALUES (1,'p');
CREATE TABLE t_mixed (id INT NOT NULL PRIMARY KEY, v INT);
INSERT INTO t_mixed VALUES (1,0);
SQL

log "=== start connector (snapshot=initial) ==="
start_connector
wait_ch "SELECT count() FROM test.t_a" 1 90 || bad "snapshot did not arrive"

log "=== P1: steady-state CDC under COMP=$COMP ==="
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO t_a VALUES (11,11,'i'),(12,12,'i'),(13,13,'i'),(14,14,'i'),(15,15,'i');
UPDATE t_a SET v = 1011, s = 'u' WHERE id = 11;
DELETE FROM t_a WHERE id = 12;
UPDATE t_a SET v = v + 100 WHERE id IN (13,14);
COMMIT;
START TRANSACTION;
INSERT INTO t_big (id, payload)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 300000)
  SELECT i, CONCAT('row-', i, '-', REPEAT('x', 40)) FROM n;
UPDATE t_big SET payload = 'updated-in-same-txn' WHERE id IN (1, 150000, 300000);
DELETE FROM t_big WHERE id = 2;
COMMIT;
SET SESSION binlog_transaction_compression = OFF;
START TRANSACTION; INSERT INTO t_mixed VALUES (2,2),(3,3); UPDATE t_mixed SET v = 22 WHERE id = 2; COMMIT;
SET SESSION binlog_transaction_compression = ON;
START TRANSACTION; INSERT INTO t_mixed VALUES (4,4),(5,5); UPDATE t_mixed SET v = 44 WHERE id = 4; DELETE FROM t_mixed WHERE id = 5; COMMIT;
SET SESSION binlog_transaction_compression = DEFAULT;
ALTER TABLE t_b ADD COLUMN extra INT NULL;
START TRANSACTION; INSERT INTO t_b VALUES (2,2,'i',NULL),(3,3,'i',NULL); UPDATE t_b SET extra = 7 WHERE id IN (1,2,3); DELETE FROM t_b WHERE id = 3; COMMIT;
START TRANSACTION; INSERT INTO t_keyless VALUES (2,'q'),(3,'r'); DELETE FROM t_keyless WHERE a = 1; COMMIT;
SQL
wait_ch "SELECT count() FROM test.t_big FINAL WHERE is_deleted=0" 299999 150 || log "t_big not at 299999 yet (comparing anyway)"
sleep 20
expect "P1 t_a id=11 v (UPDATE after multi-row INSERT in one txn)" "$(chq "SELECT v FROM test.t_a FINAL WHERE id=11 AND is_deleted=0")" "1011"
expect "P1 t_a id=12 gone (DELETE after multi-row INSERT in one txn)" "$(chq "SELECT count() FROM test.t_a FINAL WHERE id=12 AND is_deleted=0")" "0"
expect "P1 t_a id=13 v (multi-row UPDATE)" "$(chq "SELECT v FROM test.t_a FINAL WHERE id=13 AND is_deleted=0")" "113"
expect "P1 t_big id=150000 payload (UPDATE after 300k-row statement)" "$(chq "SELECT payload FROM test.t_big FINAL WHERE id=150000 AND is_deleted=0")" "updated-in-same-txn"
expect "P1 t_big id=2 gone" "$(chq "SELECT count() FROM test.t_big FINAL WHERE id=2 AND is_deleted=0")" "0"
expect "P1 t_mixed id=4 v (compressed after uncompressed)" "$(chq "SELECT v FROM test.t_mixed FINAL WHERE id=4 AND is_deleted=0")" "44"
expect "P1 t_b id=2 extra (DML after ADD COLUMN)" "$(chq "SELECT extra FROM test.t_b FINAL WHERE id=2 AND is_deleted=0")" "7"
vt t_a "id,v,s" "toString(id),toString(v),toString(s)"
vt t_b "id,v,s,IFNULL(extra,'#')" "toString(id),toString(v),toString(s),ifNull(toString(extra),'#')"
vt t_big "id,payload" "toString(id),toString(payload)"
vt t_mixed "id,v" "toString(id),toString(v)"
vt t_keyless "a,b" "toString(a),toString(b)"

log "=== P2: kill -9, write at once, restart (floor seeded from the durable mark) ==="
docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9"
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO t_a VALUES (101,101,'i'),(102,102,'i'),(103,103,'i'),(104,104,'i'),(105,105,'i');
UPDATE t_a SET v = 1101, s = 'u' WHERE id = 101;
DELETE FROM t_a WHERE id = 102;
UPDATE t_a SET v = v + 100 WHERE id IN (103,104,105);
COMMIT;
START TRANSACTION;
INSERT INTO t_b VALUES (101,101,'i',NULL),(102,102,'i',NULL),(103,103,'i',NULL);
UPDATE t_b SET extra = 9 WHERE id IN (101,102,103);
DELETE FROM t_b WHERE id = 103;
COMMIT;
SQL
docker start $CC >/dev/null 2>&1; log "connector restarted"
wait_ch "SELECT count() FROM test.t_a FINAL WHERE id IN (101,103,104,105) AND is_deleted=0" 4 90 || log "P2 rows not all visible yet"
sleep 15
expect "P2 t_a id=101 v (UPDATE after multi-row INSERT, mark-seeded floor)" "$(chq "SELECT v FROM test.t_a FINAL WHERE id=101 AND is_deleted=0")" "1101"
expect "P2 t_a id=102 gone (DELETE after multi-row INSERT, mark-seeded floor)" "$(chq "SELECT count() FROM test.t_a FINAL WHERE id=102 AND is_deleted=0")" "0"
expect "P2 t_a id=104 v (multi-row UPDATE)" "$(chq "SELECT v FROM test.t_a FINAL WHERE id=104 AND is_deleted=0")" "204"
expect "P2 t_b id=102 extra" "$(chq "SELECT extra FROM test.t_b FINAL WHERE id=102 AND is_deleted=0")" "9"
expect "P2 t_b id=103 gone" "$(chq "SELECT count() FROM test.t_b FINAL WHERE id=103 AND is_deleted=0")" "0"
vt t_a "id,v,s" "toString(id),toString(v),toString(s)"
vt t_b "id,v,s,IFNULL(extra,'#')" "toString(id),toString(v),toString(s),ifNull(toString(extra),'#')"

log "=== P3: kill -9, drop the durable version mark (first start after an upgrade), write, restart ==="
docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9"
CEX -q "TRUNCATE TABLE altinity_sink_connector.replica_version_high_water" 2>/dev/null && log "durable mark dropped" || log "WARN could not drop the mark table"
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO t_a VALUES (201,201,'i'),(202,202,'i'),(203,203,'i'),(204,204,'i'),(205,205,'i');
UPDATE t_a SET v = 1201, s = 'u' WHERE id = 201;
DELETE FROM t_a WHERE id = 202;
COMMIT;
SQL
docker start $CC >/dev/null 2>&1; log "connector restarted"
wait_ch "SELECT count() FROM test.t_a FINAL WHERE id IN (201,203,204,205) AND is_deleted=0" 4 90 || log "P3 rows not all visible yet"
sleep 15
expect "P3 t_a id=201 v (UPDATE after multi-row INSERT, clock-seeded floor)" "$(chq "SELECT v FROM test.t_a FINAL WHERE id=201 AND is_deleted=0")" "1201"
expect "P3 t_a id=202 gone (DELETE after multi-row INSERT, clock-seeded floor)" "$(chq "SELECT count() FROM test.t_a FINAL WHERE id=202 AND is_deleted=0")" "0"
vt t_a "id,v,s" "toString(id),toString(v),toString(s)"

log "=== P4: kill -9 while a large compressed transaction is being applied ==="
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO t_big2 (id, payload)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 400000)
  SELECT i, CONCAT('big2-', i, '-', REPEAT('y', 60)) FROM n;
UPDATE t_big2 SET payload = 'tail-update' WHERE id IN (7, 399999);
DELETE FROM t_big2 WHERE id = 8;
COMMIT;
SQL
for i in $(seq 1 120); do c=$(chq "SELECT count() FROM test.t_big2"); [ "${c:-0}" -gt 20000 ] && break; sleep 1; done
log "t_big2 rows visible before the kill: $(chq 'SELECT count() FROM test.t_big2')"
docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9 mid-apply"; sleep 3
docker start $CC >/dev/null 2>&1; log "connector restarted"
prev=-1; stable=0
for i in $(seq 1 150); do c=$(chq "SELECT count() FROM test.t_big2 FINAL WHERE is_deleted=0"); if [ "$c" = "$prev" ] && [ "${c:-0}" -ge 399999 ]; then stable=$((stable+1)); [ $stable -ge 4 ] && break; else stable=0; fi; prev=$c; sleep 3; done
log "t_big2 live rows after restart: $(chq 'SELECT count() FROM test.t_big2 FINAL WHERE is_deleted=0') (raw rows incl. redeliveries: $(chq 'SELECT count() FROM test.t_big2'))"
expect "P4 t_big2 id=7 payload (UPDATE at the tail of the payload)" "$(chq "SELECT payload FROM test.t_big2 FINAL WHERE id=7 AND is_deleted=0")" "tail-update"
expect "P4 t_big2 id=8 gone" "$(chq "SELECT count() FROM test.t_big2 FINAL WHERE id=8 AND is_deleted=0")" "0"
vt t_big2 "id,payload" "toString(id),toString(payload)"

log "=== P5: binlog evidence, preflight line, error scan ==="
payloads=0; total=0
for f in $(MEX -N -e "SHOW BINARY LOGS" 2>/dev/null | awk '{print $1}'); do
  n=$(MEX -N -e "SHOW BINLOG EVENTS IN '$f'" 2>/dev/null | awk -F'\t' '{print $3}' | grep -c -i 'Transaction_payload'); payloads=$((payloads+n))
  t=$(MEX -N -e "SHOW BINLOG EVENTS IN '$f'" 2>/dev/null | wc -l); total=$((total+t))
done
log "binlog: Transaction_payload events=$payloads of $total events; binlog bytes=$(MEX -N -e 'SHOW BINARY LOGS' 2>/dev/null | awk '{s+=$2} END {print s}')"
# P1 turns compression on for ONE session-level transaction (the mixed-stream case), so a
# COMP=off run carries exactly one payload event and a COMP=on run many.
if [ "$COMP" = on ]; then [ "$payloads" -gt 1 ] && ok "binlog carried Transaction_payload events ($payloads)" || bad "too few Transaction_payload events in the binlog although COMP=on ($payloads)"; else
  [ "$payloads" -eq 1 ] && ok "COMP=off: exactly the one session-level compressed transaction in the binlog" || bad "COMP=off: expected exactly 1 Transaction_payload event (the session-level one), found $payloads"; fi
log "preflight lines:"; docker logs $CC 2>&1 | grep -i -E 'binlog_transaction_compression|Transaction_payload|decoder self-test' | head -6
log "connector errors:"; docker logs $CC 2>&1 | grep -iE 'ERROR|Exception|FATAL|refus|terminal' | grep -viE 'DEBUG' | tail -12

log "=== RESULT PASS=$PASS FAIL=$FAIL ($LABEL GTID=$GTID COMP=$COMP) ==="
status=0; [ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ] || status=1
log "=== DONE (exit $status) ==="
exit "$status"
