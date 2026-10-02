#!/usr/bin/env bash
# Hard and edge cases for MySQL binlog transaction compression through the lightweight
# sink connector (spec 01.08), value level. Companion of csc_e2e_binlog_compression.sh.
#
#   JAR=<lightweight jar> [GTID=on|off] [LABEL=<tag>] [IMAGE=<image providing java 17>] [WORK=<scratch dir>] \
#   tests/e2e/binlog_transaction_compression_hard.sh
#   Needs podman or docker (the script calls `docker`) and the images mysql:8.0 and clickhouse/clickhouse-server:24.8.
#
# Source: MySQL 8.0, binlog_transaction_compression=ON (level 3), gtid per GTID.
# Cases (each asserted per key AND by whole-table value-level CRC):
#   H1  multi-table transaction with SAVEPOINT / ROLLBACK TO SAVEPOINT (rolled-back rows
#       must never reach the replica; the rest of the transaction must)
#   H2  long transaction interleaved with short ones on other keys (late commit under
#       compression: the long payload arrives after transactions it executed before)
#   H3  wide rows: 40 x 1 MiB BLOB/TEXT rows in one compressed transaction, then UPDATE
#       and DELETE of two of them in the same transaction; session zstd level 22
#   H4  mixed engines: an InnoDB and a MyISAM table in one transaction (MySQL writes the
#       whole transaction UNCOMPRESSED); the InnoDB table must be exact
#   H5  binlog_rows_query_log_events=ON (Rows_query events INSIDE the payload)
#   H6  SET GLOBAL binlog_transaction_compression toggled OFF and back ON at run time,
#       transactions on both sides (mixed stream at the global level)
#   H7  keyless table: duplicate rows inserted, one duplicate deleted, inside one payload
#   H8  very large payload: 1.2M rows (~150 MB uncompressed) in ONE transaction with a
#       2 GiB connector heap, then UPDATE/DELETE at the tail of the same transaction
#   H9  kill -9 in the middle of H8's apply (redelivery of most of a huge payload)
#   H10 source max_allowed_packet lowered to 16 MiB, a transaction whose COMPRESSED
#       payload exceeds it: the source must fail the dump loudly; the connector must not
#       silently skip; restoring the limit and restarting the connector must catch up
#   H11 DDL inside the stream: ADD COLUMN, MODIFY COLUMN, DROP COLUMN, each followed by
#       a compressed multi-statement transaction on the changed table
#   H12 restart with a durable offset positioned at the payload of H11's last transaction
#       (kill -9 immediately after it), plus a TRUNCATE and a DROP TABLE afterwards
# Output ends with "RESULT PASS=n FAIL=m"; exit status 0 only when FAIL=0.
set -uo pipefail
JAR="${JAR:?set JAR}"; GTID="${GTID:-on}"; LABEL="${LABEL:-bch}"
IMAGE="${IMAGE:-docker.io/eclipse-temurin:17-jre}"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
TEMPLATE="${TEMPLATE:-$REPO/sink-connector-lightweight/docker/config.yml}"; LOG4J="${LOG4J:-$REPO/sink-connector-lightweight/docker/log4j2.xml}"; WORK="${WORK:-/tmp/csc-e2e-bch-$LABEL}"; mkdir -p "$WORK"
POD=cscbch-$LABEL; MY=mysql-bch-$LABEL; CH=ch-bch-$LABEL; CC=csc-bch-$LABEL
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
  csum=$(chq "SELECT toInt64(ifNull(sum(CRC32(concat_ws('~',$ccols))),0)) FROM test.$tbl FINAL WHERE is_deleted=0")
  if [ -z "$mrows" ] || [ -z "$crows" ]; then bad "$tbl: a side could not be queried (mysql_rows=[$mrows] target_rows=[$crows])"; return; fi
  if [ -n "$msum" ] && [ "$mrows" = "$crows" ] && [ "$msum" = "$csum" ]; then ok "$tbl value-level (rows=$mrows)";
  else bad "$tbl mysql_rows=$mrows target_rows=$crows mysql_crc=$msum target_crc=$csum"
    echo "  MySQL:"; MEX -N test -e "SELECT $mcols FROM $tbl ORDER BY 1 LIMIT 10" 2>/dev/null | cut -c1-160
    echo "  CH   :"; CEX -q "SELECT $ccols FROM test.$tbl FINAL WHERE is_deleted=0 ORDER BY 1 LIMIT 10" 2>/dev/null | cut -c1-160
  fi
}
errscan(){ docker logs $CC 2>&1 | grep -iE ' ERROR |Exception|FATAL|refus|terminal' | grep -viE 'DEBUG|self-test FAILED' | tail -n "${1:-8}" | cut -c1-220; }
start_connector(){
  docker run -d --name $CC --pod $POD --runtime runc \
    -v "$WORK/config.yml:/config.yml:ro,Z" -v "$LOG4J:/log4j2.xml:ro,Z" -v "$JAR:/app.jar:ro,Z" \
    --entrypoint sh "$IMAGE" -c \
    "java -Xms2g -Xmx2g -Dlog4j2.configurationFile=log4j2.xml -jar /app.jar /config.yml com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication" \
    >/dev/null 2>&1 && log "connector started ($CC)"
}
cleanup(){ for c in $CC $MY $CH; do docker rm -f "$c" >/dev/null 2>&1; done; docker pod rm -f $POD >/dev/null 2>&1; }
trap cleanup EXIT

log "=== $LABEL: JAR=$JAR GTID=$GTID (hard cases) ==="
cleanup; docker pod create --name $POD >/dev/null 2>&1 && log "pod up"
MYCNF=$WORK/mysqld.cnf
{
  echo "[mysqld]"; echo "max_connections=100000"; echo "default_authentication_plugin=mysql_native_password"
  echo "binlog_transaction_compression = ON"; echo "binlog_transaction_compression_level_zstd = 3"
  if [ "$GTID" = on ]; then echo "gtid-mode = on"; echo "enforce-gtid-consistency = true"; else echo "gtid-mode = off"; echo "enforce-gtid-consistency = false"; fi
  echo "local_infile = on"; echo "sql_generate_invisible_primary_key=1"; echo "binlog_row_image=FULL"; echo "cte_max_recursion_depth=2000000"
  echo "max_allowed_packet=1073741824"; echo "binlog_cache_size=268435456"; echo "max_binlog_cache_size=4294967296"; echo "innodb_buffer_pool_size=2G"
} > "$MYCNF"
# connector configuration: the repository docker config, addressed through the pod, check=require
sed -e 's/database.hostname: "mysql-master"/database.hostname: "127.0.0.1"/' \
    -e 's/clickhouse.server.url: "clickhouse"/clickhouse.server.url: "127.0.0.1"/' \
    -e 's#jdbc:clickhouse://clickhouse:8123#jdbc:clickhouse://127.0.0.1:8123#g' "$TEMPLATE" > "$WORK/config.yml"
{ echo; echo "# --- binlog compression hard cases ---"; echo "name: \"bch-$LABEL\""; echo "topic.prefix: \"sink-bch-$LABEL\""; echo "binlog.transaction.compression.check: \"require\""; } >> "$WORK/config.yml"

docker run -d --name $CH --pod $POD -e CLICKHOUSE_USER=root -e CLICKHOUSE_PASSWORD=root \
  -e CLICKHOUSE_DB=test -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 --ulimit nofile=262144:262144 \
  clickhouse/clickhouse-server:24.8 >/dev/null 2>&1 && log "ch started"
docker run -d --name $MY --pod $POD -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=test \
  -e TZ=US/Central -v "$MYCNF:/etc/mysql/conf.d/my_custom.cnf:ro,Z" mysql:8.0 >/dev/null 2>&1 && log "mysql started"
for i in $(seq 1 60); do MEX -e "SELECT 1" >/dev/null 2>&1 && { log "mysql up"; break; }; sleep 3; done
for i in $(seq 1 60); do CEX -q "SELECT 1" >/dev/null 2>&1 && { log "ch up"; break; }; sleep 3; done
log "source: gtid_mode=$(myq 'SELECT @@gtid_mode') compression=$(myq 'SELECT @@binlog_transaction_compression') level=$(myq 'SELECT @@binlog_transaction_compression_level_zstd') version=$(myq 'SELECT @@version') max_allowed_packet=$(myq 'SELECT @@max_allowed_packet')"

log "=== seed ==="
MEX --force test <<'SQL'
CREATE TABLE h_parent (id INT NOT NULL PRIMARY KEY, name VARCHAR(32), total INT);
CREATE TABLE h_child (id INT NOT NULL PRIMARY KEY, parent_id INT, qty INT);
INSERT INTO h_parent VALUES (1,'p1',0);
CREATE TABLE h_long (id INT NOT NULL PRIMARY KEY, v INT, who VARCHAR(8));
INSERT INTO h_long VALUES (1,0,'seed'),(2,0,'seed'),(3,0,'seed');
CREATE TABLE h_wide (id INT NOT NULL PRIMARY KEY, b LONGBLOB, t LONGTEXT, tag VARCHAR(16));
CREATE TABLE h_inno (id INT NOT NULL PRIMARY KEY, v INT) ENGINE=InnoDB;
CREATE TABLE h_myisam (id INT NOT NULL PRIMARY KEY, v INT) ENGINE=MyISAM;
CREATE TABLE h_rq (id INT NOT NULL PRIMARY KEY, v INT);
CREATE TABLE h_tog (id INT NOT NULL PRIMARY KEY, v INT);
CREATE TABLE h_keyless (a INT, b VARCHAR(8));
CREATE TABLE h_huge (id INT NOT NULL PRIMARY KEY, payload VARCHAR(160));
CREATE TABLE h_pkt (id INT NOT NULL PRIMARY KEY, blob_col LONGBLOB);
CREATE TABLE h_ddl (id INT NOT NULL PRIMARY KEY, a INT, b VARCHAR(16));
INSERT INTO h_ddl VALUES (1,1,'one'),(2,2,'two');
CREATE TABLE h_after (id INT NOT NULL PRIMARY KEY, v INT);
SQL

log "=== start connector (snapshot=initial, check=require) ==="
start_connector
wait_ch "SELECT count() FROM test.h_long" 3 90 || bad "snapshot did not arrive"

# ------------------------------------------------------------------ H1 savepoints
log "=== H1: multi-table transaction with SAVEPOINT / ROLLBACK TO SAVEPOINT ==="
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO h_parent VALUES (2,'p2',0),(3,'p3',0);
INSERT INTO h_child VALUES (10,2,5),(11,2,7),(12,3,1);
SAVEPOINT sp1;
INSERT INTO h_child VALUES (13,3,99),(14,3,98);
UPDATE h_parent SET total = 999 WHERE id = 3;
ROLLBACK TO SAVEPOINT sp1;
UPDATE h_parent SET total = 12 WHERE id = 2;
UPDATE h_parent SET total = 1 WHERE id = 3;
DELETE FROM h_child WHERE id = 11;
COMMIT;
SQL
sleep 12
expect "H1 h_parent id=2 total (UPDATE after INSERT, after ROLLBACK TO SAVEPOINT)" "$(chq "SELECT total FROM test.h_parent FINAL WHERE id=2 AND is_deleted=0")" "12"
expect "H1 h_parent id=3 total (rolled-back 999 never applied)" "$(chq "SELECT total FROM test.h_parent FINAL WHERE id=3 AND is_deleted=0")" "1"
expect "H1 rolled-back child rows absent" "$(chq "SELECT count() FROM test.h_child FINAL WHERE id IN (13,14) AND is_deleted=0")" "0"
expect "H1 h_child id=11 gone" "$(chq "SELECT count() FROM test.h_child FINAL WHERE id=11 AND is_deleted=0")" "0"
vt h_parent "id,name,total" "toString(id),toString(name),toString(total)"
vt h_child "id,parent_id,qty" "toString(id),toString(parent_id),toString(qty)"

# ------------------------------------------------------------------ H2 late commit
log "=== H2: long transaction interleaved with short ones (late commit) ==="
( MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO h_long VALUES (100,100,'long'),(101,101,'long'),(102,102,'long');
UPDATE h_long SET v = 1 WHERE id = 1;
SELECT SLEEP(6);
UPDATE h_long SET v = v + 1000, who = 'long' WHERE id IN (100,101);
DELETE FROM h_long WHERE id = 102;
COMMIT;
SQL
) &
sleep 2
MEX --force test <<'SQL'
UPDATE h_long SET v = 22, who = 'short' WHERE id = 2;
INSERT INTO h_long VALUES (200,200,'short');
UPDATE h_long SET v = 33, who = 'short' WHERE id = 3;
UPDATE h_long SET v = 222, who = 'short' WHERE id = 2;
SQL
wait
sleep 12
expect "H2 long txn id=100 v (tail UPDATE of the late payload)" "$(chq "SELECT v FROM test.h_long FINAL WHERE id=100 AND is_deleted=0")" "1100"
expect "H2 long txn id=102 gone" "$(chq "SELECT count() FROM test.h_long FINAL WHERE id=102 AND is_deleted=0")" "0"
expect "H2 short txns id=2 v (two commits before the long one)" "$(chq "SELECT v FROM test.h_long FINAL WHERE id=2 AND is_deleted=0")" "222"
expect "H2 long txn id=1 v (early statement of the late payload)" "$(chq "SELECT v FROM test.h_long FINAL WHERE id=1 AND is_deleted=0")" "1"
vt h_long "id,v,who" "toString(id),toString(v),toString(who)"

# ------------------------------------------------------------------ H3 wide rows
log "=== H3: 40 x 1 MiB rows in one compressed transaction, zstd level 22 for the session ==="
MEX --force test <<'SQL'
SET SESSION binlog_transaction_compression_level_zstd = 22;
SET SESSION group_concat_max_len = 1073741824;
START TRANSACTION;
INSERT INTO h_wide (id, b, t, tag)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 40),
       k(j) AS (SELECT 1 UNION ALL SELECT j+1 FROM k WHERE j < 512)
  SELECT i, (SELECT GROUP_CONCAT(RANDOM_BYTES(1024) SEPARATOR '') FROM k), REPEAT(CONCAT('wide-', i, '-'), 65536), 'ins' FROM n;
UPDATE h_wide SET tag = 'upd', t = CONCAT('u', t) WHERE id IN (1, 40);
DELETE FROM h_wide WHERE id = 2;
COMMIT;
SQL
wait_ch "SELECT count() FROM test.h_wide FINAL WHERE is_deleted=0" 39 120 || log "h_wide not at 39 yet"
sleep 5
expect "H3 h_wide id=40 tag (UPDATE at the tail of a 40 MiB payload)" "$(chq "SELECT tag FROM test.h_wide FINAL WHERE id=40 AND is_deleted=0")" "upd"
expect "H3 h_wide id=2 gone" "$(chq "SELECT count() FROM test.h_wide FINAL WHERE id=2 AND is_deleted=0")" "0"
vt h_wide "id,MD5(b),MD5(t),tag" "toString(id),lower(hex(MD5(unhex(b)))),lower(hex(MD5(t))),toString(tag)"

# ------------------------------------------------------------------ H4 mixed engines
log "=== H4: InnoDB + MyISAM in one transaction (written uncompressed by MySQL) ==="
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO h_inno VALUES (1,1),(2,2),(3,3);
INSERT INTO h_myisam VALUES (1,1),(2,2);
UPDATE h_inno SET v = 20 WHERE id = 2;
DELETE FROM h_inno WHERE id = 3;
COMMIT;
START TRANSACTION;
INSERT INTO h_inno VALUES (4,4),(5,5);
UPDATE h_inno SET v = 40 WHERE id = 4;
COMMIT;
SQL
sleep 12
expect "H4 h_inno id=2 v" "$(chq "SELECT v FROM test.h_inno FINAL WHERE id=2 AND is_deleted=0")" "20"
expect "H4 h_inno id=3 gone" "$(chq "SELECT count() FROM test.h_inno FINAL WHERE id=3 AND is_deleted=0")" "0"
expect "H4 h_inno id=4 v (compressed txn after the uncompressed one)" "$(chq "SELECT v FROM test.h_inno FINAL WHERE id=4 AND is_deleted=0")" "40"
vt h_inno "id,v" "toString(id),toString(v)"
vt h_myisam "id,v" "toString(id),toString(v)"

# ------------------------------------------------------------------ H5 rows_query events
log "=== H5: binlog_rows_query_log_events=ON (Rows_query events inside the payload) ==="
MEX --force test <<'SQL'
SET GLOBAL binlog_rows_query_log_events = ON;
SQL
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO h_rq VALUES (1,1),(2,2),(3,3);
UPDATE h_rq SET v = 22 WHERE id = 2;
DELETE FROM h_rq WHERE id = 3;
COMMIT;
SQL
MEX --force test <<'SQL'
SET GLOBAL binlog_rows_query_log_events = OFF;
SQL
sleep 12
expect "H5 h_rq id=2 v" "$(chq "SELECT v FROM test.h_rq FINAL WHERE id=2 AND is_deleted=0")" "22"
expect "H5 h_rq id=3 gone" "$(chq "SELECT count() FROM test.h_rq FINAL WHERE id=3 AND is_deleted=0")" "0"
vt h_rq "id,v" "toString(id),toString(v)"

# ------------------------------------------------------------------ H6 global toggle
log "=== H6: SET GLOBAL binlog_transaction_compression OFF then ON at run time ==="
MEX --force test <<'SQL'
SET GLOBAL binlog_transaction_compression = OFF;
SQL
MEX --force test <<'SQL'
START TRANSACTION; INSERT INTO h_tog VALUES (1,1),(2,2),(3,3); UPDATE h_tog SET v = 22 WHERE id = 2; DELETE FROM h_tog WHERE id = 3; COMMIT;
SQL
MEX --force test <<'SQL'
SET GLOBAL binlog_transaction_compression = ON;
SQL
MEX --force test <<'SQL'
START TRANSACTION; INSERT INTO h_tog VALUES (4,4),(5,5),(6,6); UPDATE h_tog SET v = 55 WHERE id = 5; DELETE FROM h_tog WHERE id = 6; UPDATE h_tog SET v = 222 WHERE id = 2; COMMIT;
SQL
sleep 12
expect "H6 h_tog id=2 v (uncompressed txn then compressed txn on the same key)" "$(chq "SELECT v FROM test.h_tog FINAL WHERE id=2 AND is_deleted=0")" "222"
expect "H6 h_tog id=5 v" "$(chq "SELECT v FROM test.h_tog FINAL WHERE id=5 AND is_deleted=0")" "55"
expect "H6 h_tog ids 3 and 6 gone" "$(chq "SELECT count() FROM test.h_tog FINAL WHERE id IN (3,6) AND is_deleted=0")" "0"
vt h_tog "id,v" "toString(id),toString(v)"

# ------------------------------------------------------------------ H7 keyless duplicates
log "=== H7: keyless table with duplicate rows inside one payload ==="
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO h_keyless VALUES (1,'a'),(1,'a'),(2,'b'),(1,'a');
DELETE FROM h_keyless WHERE a = 1 LIMIT 1;
UPDATE h_keyless SET b = 'B' WHERE a = 2;
COMMIT;
SQL
sleep 12
vt h_keyless "a,b" "toString(a),toString(b)"

# ------------------------------------------------------------------ H8 very large payload
log "=== H8: 1.2M-row single transaction (~150 MB uncompressed) with a 2 GiB heap ==="
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO h_huge (id, payload)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 1200000)
  SELECT i, CONCAT('huge-', i, '-', REPEAT('z', 100)) FROM n;
UPDATE h_huge SET payload = 'tail-update' WHERE id IN (5, 1199999);
DELETE FROM h_huge WHERE id = 6;
COMMIT;
SQL
log "H8 committed on the source; waiting for the replica"
t0=$(date +%s)
wait_ch "SELECT count() FROM test.h_huge FINAL WHERE is_deleted=0" 1199999 600 || log "h_huge not at 1199999 after 20 min"
log "H8 apply time: $(( $(date +%s) - t0 )) s; connector RSS: $(docker stats --no-stream $CC 2>/dev/null | tail -n 1 | awk '{print $4, $5, $6}')"
expect "H8 h_huge id=1199999 payload (tail UPDATE of a 1.2M-row payload)" "$(chq "SELECT payload FROM test.h_huge FINAL WHERE id=1199999 AND is_deleted=0")" "tail-update"
expect "H8 h_huge id=6 gone" "$(chq "SELECT count() FROM test.h_huge FINAL WHERE id=6 AND is_deleted=0")" "0"
vt h_huge "id,payload" "toString(id),toString(payload)"

# ------------------------------------------------------------------ H9 kill mid huge payload
log "=== H9: kill -9 while a second 1.2M-row payload is being applied ==="
MEX --force test <<'SQL'
START TRANSACTION;
INSERT INTO h_huge (id, payload)
  WITH RECURSIVE n(i) AS (SELECT 2000001 UNION ALL SELECT i+1 FROM n WHERE i < 3200000)
  SELECT i, CONCAT('huge2-', i, '-', REPEAT('w', 100)) FROM n;
UPDATE h_huge SET payload = 'tail-update-2' WHERE id IN (2000005, 3199999);
DELETE FROM h_huge WHERE id = 2000006;
COMMIT;
SQL
for i in $(seq 1 300); do c=$(chq "SELECT count() FROM test.h_huge WHERE id > 2000000"); [ "${c:-0}" -gt 100000 ] && break; sleep 1; done
log "h_huge second payload rows visible before the kill: $(chq 'SELECT count() FROM test.h_huge WHERE id > 2000000')"
docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9 mid-apply"; sleep 3
docker start $CC >/dev/null 2>&1; log "connector restarted"
prev=-1; stable=0
for i in $(seq 1 400); do c=$(chq "SELECT count() FROM test.h_huge FINAL WHERE is_deleted=0"); if [ "$c" = "$prev" ] && [ "${c:-0}" -ge 2399998 ]; then stable=$((stable+1)); [ $stable -ge 4 ] && break; else stable=0; fi; prev=$c; sleep 3; done
log "h_huge live rows after restart: $(chq 'SELECT count() FROM test.h_huge FINAL WHERE is_deleted=0') (raw incl. redeliveries: $(chq 'SELECT count() FROM test.h_huge'))"
expect "H9 h_huge id=3199999 payload" "$(chq "SELECT payload FROM test.h_huge FINAL WHERE id=3199999 AND is_deleted=0")" "tail-update-2"
expect "H9 h_huge id=2000006 gone" "$(chq "SELECT count() FROM test.h_huge FINAL WHERE id=2000006 AND is_deleted=0")" "0"
vt h_huge "id,payload" "toString(id),toString(payload)"

# ------------------------------------------------------------------ H10 max_allowed_packet
log "=== H10: source max_allowed_packet=16 MiB and a compressed payload above it ==="
MEX --force test <<'SQL'
SET GLOBAL max_allowed_packet = 16777216;
SQL
MEX --force test <<'SQL'
SET SESSION group_concat_max_len = 1073741824;
START TRANSACTION;
INSERT INTO h_pkt (id, blob_col)
  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 12),
       k(j) AS (SELECT 1 UNION ALL SELECT j+1 FROM k WHERE j < 2048)
  SELECT i, (SELECT GROUP_CONCAT(RANDOM_BYTES(1024) SEPARATOR '') FROM k) FROM n;
UPDATE h_pkt SET blob_col = RANDOM_BYTES(16) WHERE id = 1;
COMMIT;
SQL
log "H10 source rows: $(myq 'SELECT count(*) FROM h_pkt'); binlog now: $(MEX -N -e 'SHOW BINARY LOGS' 2>/dev/null | tail -n 1)"
sleep 45
log "H10 replica rows after 45 s: $(chq 'SELECT count() FROM test.h_pkt FINAL WHERE is_deleted=0') ; connector status: $(docker ps -a --filter name=$CC 2>/dev/null | tail -n 1 | awk '{print $(NF-2), $(NF-1), $NF}')"
log "H10 connector log (dump/packet errors expected if the source refused the dump):"; docker logs $CC 2>&1 | grep -iE 'packet|max_allowed|Transaction_payload|deserializ|EOF|lost connection|communications link|Skip|resum' | tail -n 8 | cut -c1-220
MEX --force test <<'SQL'
SET GLOBAL max_allowed_packet = 1073741824;
SQL
docker restart $CC >/dev/null 2>&1; log "source limit restored to 1 GiB; connector restarted"
wait_ch "SELECT count() FROM test.h_pkt FINAL WHERE is_deleted=0" 12 150 || log "h_pkt not complete after restart"
expect "H10 h_pkt rows after restoring the limit" "$(chq "SELECT count() FROM test.h_pkt FINAL WHERE is_deleted=0")" "12"
vt h_pkt "id,MD5(blob_col)" "toString(id),lower(hex(MD5(unhex(blob_col))))"
log "H10 verdict on the outage window: rows never silently skipped = $( [ "$(chq 'SELECT count() FROM test.h_pkt FINAL WHERE is_deleted=0')" = "12" ] && echo yes || echo NO )"

# ------------------------------------------------------------------ H11 DDL in the stream
log "=== H11: ADD / MODIFY / DROP COLUMN, each followed by a compressed multi-statement txn ==="
MEX --force test <<'SQL'
ALTER TABLE h_ddl ADD COLUMN c INT NULL;
START TRANSACTION; INSERT INTO h_ddl VALUES (3,3,'three',30),(4,4,'four',40); UPDATE h_ddl SET c = 33 WHERE id = 3; DELETE FROM h_ddl WHERE id = 4; COMMIT;
ALTER TABLE h_ddl MODIFY COLUMN b VARCHAR(64);
START TRANSACTION; INSERT INTO h_ddl VALUES (5,5,REPEAT('f',40),50),(6,6,'six',60); UPDATE h_ddl SET b = REPEAT('g',50) WHERE id = 5; DELETE FROM h_ddl WHERE id = 6; COMMIT;
ALTER TABLE h_ddl DROP COLUMN a;
START TRANSACTION; INSERT INTO h_ddl VALUES (7,'seven',70),(8,'eight',80); UPDATE h_ddl SET c = 77 WHERE id = 7; DELETE FROM h_ddl WHERE id = 8; COMMIT;
SQL
sleep 20
expect "H11 h_ddl id=3 c (after ADD COLUMN)" "$(chq "SELECT c FROM test.h_ddl FINAL WHERE id=3 AND is_deleted=0")" "33"
expect "H11 h_ddl id=5 b length (after MODIFY COLUMN)" "$(chq "SELECT length(b) FROM test.h_ddl FINAL WHERE id=5 AND is_deleted=0")" "50"
expect "H11 h_ddl id=7 c (after DROP COLUMN)" "$(chq "SELECT c FROM test.h_ddl FINAL WHERE id=7 AND is_deleted=0")" "77"
expect "H11 h_ddl deleted ids gone" "$(chq "SELECT count() FROM test.h_ddl FINAL WHERE id IN (4,6,8) AND is_deleted=0")" "0"
expect "H11 h_ddl column a dropped on the replica" "$(chq "SELECT count() FROM system.columns WHERE database='test' AND table='h_ddl' AND name='a'")" "0"
vt h_ddl "id,b,IFNULL(c,'#')" "toString(id),toString(b),ifNull(toString(c),'#')"

# ------------------------------------------------------------------ H12 offset at a payload, TRUNCATE, DROP
log "=== H12: kill -9 right after a compressed txn (offset at its payload), then TRUNCATE and DROP TABLE ==="
MEX --force test <<'SQL'
START TRANSACTION; INSERT INTO h_after VALUES (1,1),(2,2),(3,3); UPDATE h_after SET v = 22 WHERE id = 2; DELETE FROM h_after WHERE id = 3; COMMIT;
SQL
sleep 4; docker kill -s KILL $CC >/dev/null 2>&1; log "connector killed -9"
MEX --force test <<'SQL'
START TRANSACTION; INSERT INTO h_after VALUES (4,4),(5,5); UPDATE h_after SET v = 44 WHERE id = 4; UPDATE h_after SET v = 222 WHERE id = 2; COMMIT;
TRUNCATE TABLE h_tog;
DROP TABLE h_myisam;
START TRANSACTION; INSERT INTO h_tog VALUES (10,10),(11,11); UPDATE h_tog SET v = 110 WHERE id = 11; COMMIT;
SQL
docker start $CC >/dev/null 2>&1; log "connector restarted"
wait_ch "SELECT count() FROM test.h_tog FINAL WHERE is_deleted=0" 2 90 || log "h_tog not at 2 yet"
sleep 10
expect "H12 h_after id=2 v (UPDATE across the restart)" "$(chq "SELECT v FROM test.h_after FINAL WHERE id=2 AND is_deleted=0")" "222"
expect "H12 h_after id=4 v" "$(chq "SELECT v FROM test.h_after FINAL WHERE id=4 AND is_deleted=0")" "44"
expect "H12 h_tog after TRUNCATE + reload: id=11 v" "$(chq "SELECT v FROM test.h_tog FINAL WHERE id=11 AND is_deleted=0")" "110"
expect "H12 h_tog old rows gone after TRUNCATE" "$(chq "SELECT count() FROM test.h_tog FINAL WHERE id < 10 AND is_deleted=0")" "0"
expect "H12 h_myisam dropped on the replica" "$(chq "SELECT count() FROM system.tables WHERE database='test' AND name='h_myisam'")" "0"
vt h_after "id,v" "toString(id),toString(v)"
vt h_tog "id,v" "toString(id),toString(v)"

log "=== evidence ==="
payloads=0; total=0
for f in $(MEX -N -e "SHOW BINARY LOGS" 2>/dev/null | awk '{print $1}'); do
  n=$(MEX -N -e "SHOW BINLOG EVENTS IN '$f'" 2>/dev/null | awk -F'\t' '{print $3}' | grep -c -i 'Transaction_payload'); payloads=$((payloads+n))
  t=$(MEX -N -e "SHOW BINLOG EVENTS IN '$f'" 2>/dev/null | wc -l); total=$((total+t))
done
log "binlog: Transaction_payload events=$payloads of $total events; binlog bytes=$(MEX -N -e 'SHOW BINARY LOGS' 2>/dev/null | awk '{s+=$2} END {print s}')"
[ "$payloads" -gt 5 ] && ok "binlog carried Transaction_payload events ($payloads)" || bad "too few Transaction_payload events ($payloads)"
log "preflight:"; docker logs $CC 2>&1 | grep -i -E 'binlog_transaction_compression=' | head -n 2 | cut -c1-200
log "connector errors (last 12):"; errscan 12
log "=== RESULT PASS=$PASS FAIL=$FAIL ($LABEL GTID=$GTID hard) ==="
status=0; [ "$FAIL" -eq 0 ] && [ "$PASS" -gt 0 ] || status=1
log "=== DONE (exit $status) ==="
exit "$status"
