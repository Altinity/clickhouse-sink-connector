#!/usr/bin/env python3
"""Generate the concurrent writer workload of the compression chaos harness (spec 01.08 section 6).

    gen_workload.py <out_dir> <writers> <transactions_per_writer> <seed>

Writes <out_dir>/w<i>.sql for i in 1..writers. Each file is a stream of transactions a writer session
replays in a loop for the whole run, so every statement is safe to repeat: inserts use AUTO_INCREMENT or
INSERT ... ON DUPLICATE KEY UPDATE, deletes are bounded. The mix, per transaction, drawn with the
writer's own seeded RNG:

  40%  hot-key upserts on x_hot (1..20 rows among 2,000 keys shared by every writer) + 1..5 x_log appends
  15%  one multi-row x_log INSERT of 50..500 rows (several rows events inside one payload)
  10%  a two-table transfer (x_acct_a -= k, x_acct_b += k) -- the sum of a+b is invariant on the source
  10%  bounded x_log DELETE + delete-and-reinsert of a hot key in the same transaction
   5%  a wide row upsert (x_wide, 0.5..3 MiB of text) with an UPDATE of the same row after it
   5%  the same transaction with compression switched OFF for the session (an uncompressed transaction
       between compressed ones on the same keys), then ON again
   5%  a random session zstd level 1..22
   5%  SAVEPOINT / ROLLBACK TO SAVEPOINT in the middle of the transaction
   3%  a whole transaction that is rolled back (must never reach the replica)
   2%  a JSON partial update (JSON_SET / JSON_REMOVE) on x_hot

MySQL is the source of truth: the harness compares every table value by value at the end, so a
statement that fails on the source (a deadlock between writers rolls the transaction back) is simply
not part of the truth. The generator needs only the Python standard library.
"""
import random
import sys


def q(s):
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"


def hot_upserts(r, w, n):
    rows = []
    for _ in range(n):
        k = r.randint(1, 2000)
        v = r.randint(-10**12, 10**12)
        s = "w%d-%d" % (w, r.randint(0, 10**9))
        rows.append("(%d,%d,%s,JSON_OBJECT('w',%d,'n',%d,'tag',%s),NOW(6))" % (k, v, q(s), w, r.randint(0, 999), q(s[:8])))
    return ("INSERT INTO x_hot (id,v,s,j,ts) VALUES %s ON DUPLICATE KEY UPDATE v=VALUES(v), s=VALUES(s), "
            "j=VALUES(j), ts=VALUES(ts);" % ",".join(rows))


def log_append(r, w, n):
    rows = ",".join("(%d,%d,%s,NOW(6))" % (w, r.randint(0, 10**6), q("p" * r.randint(1, 200))) for _ in range(n))
    return "INSERT INTO x_log (w,n,payload,created) VALUES %s;" % rows


def txn(r, w):
    x = r.random()
    body = []
    pre, post = [], []
    if x < 0.40:
        body += [hot_upserts(r, w, r.randint(1, 20)), log_append(r, w, r.randint(1, 5))]
    elif x < 0.55:
        body += [log_append(r, w, r.randint(50, 500))]
    elif x < 0.65:
        a, b, k = r.randint(1, 200), r.randint(1, 200), r.randint(1, 1000)
        body += ["UPDATE x_acct_a SET bal = bal - %d, seq = seq + 1 WHERE id = %d;" % (k, a),
                 "UPDATE x_acct_b SET bal = bal + %d, seq = seq + 1 WHERE id = %d;" % (k, b)]
    elif x < 0.75:
        k = r.randint(1, 2000)
        body += ["DELETE FROM x_log WHERE w = %d AND n %% 97 = %d ORDER BY id LIMIT %d;" % (w, r.randint(0, 96), r.randint(1, 50)),
                 "DELETE FROM x_hot WHERE id = %d;" % k,
                 "INSERT INTO x_hot (id,v,s,j,ts) VALUES (%d,%d,'reinserted',JSON_OBJECT('r',1),NOW(6)) "
                 "ON DUPLICATE KEY UPDATE v=VALUES(v), s=VALUES(s), j=VALUES(j), ts=VALUES(ts);" % (k, r.randint(0, 10**9))]
    elif x < 0.80:
        k, n = r.randint(1, 60), r.randint(512 * 1024, 3 * 1024 * 1024)
        body += ["INSERT INTO x_wide (id,t,note) VALUES (%d, REPEAT(%s,%d), 'w%d') ON DUPLICATE KEY UPDATE t=VALUES(t), note=VALUES(note);"
                 % (k, q(chr(97 + r.randint(0, 25))), n, w),
                 "UPDATE x_wide SET note = CONCAT(note, '-u%d') WHERE id = %d;" % (r.randint(0, 99), k)]
    elif x < 0.85:
        pre = ["SET SESSION binlog_transaction_compression = OFF;"]
        post = ["SET SESSION binlog_transaction_compression = ON;"]
        body += [hot_upserts(r, w, r.randint(1, 10)), log_append(r, w, r.randint(1, 20))]
    elif x < 0.90:
        pre = ["SET SESSION binlog_transaction_compression_level_zstd = %d;" % r.randint(1, 22)]
        post = ["SET SESSION binlog_transaction_compression_level_zstd = 3;"]
        body += [hot_upserts(r, w, r.randint(1, 10)), log_append(r, w, r.randint(10, 100))]
    elif x < 0.95:
        body += [hot_upserts(r, w, r.randint(1, 5)), "SAVEPOINT sp;", hot_upserts(r, w, r.randint(1, 5)),
                 log_append(r, w, 3), "ROLLBACK TO SAVEPOINT sp;", log_append(r, w, r.randint(1, 5))]
    elif x < 0.98:
        return ["START TRANSACTION;", hot_upserts(r, w, r.randint(1, 10)), log_append(r, w, 10), "ROLLBACK;"]
    else:
        k = r.randint(1, 2000)
        body += ["UPDATE x_hot SET j = JSON_SET(COALESCE(j, JSON_OBJECT()), '$.p', %d, '$.q', %s) WHERE id = %d;" % (r.randint(0, 999), q("x" * r.randint(1, 30)), k),
                 "UPDATE x_hot SET j = JSON_REMOVE(j, '$.tag') WHERE id = %d AND j IS NOT NULL;" % r.randint(1, 2000)]
    return pre + ["START TRANSACTION;"] + body + ["COMMIT;"] + post


def main():
    out, writers, per, seed = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    for w in range(1, writers + 1):
        r = random.Random(seed * 1000 + w)
        with open("%s/w%d.sql" % (out, w), "w") as f:
            for _ in range(per):
                f.write("\n".join(txn(r, w)) + "\n")


if __name__ == "__main__":
    main()
