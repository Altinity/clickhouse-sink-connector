## Correction from the author — the end-to-end reproduction does NOT confirm this diagnosis

I am flagging this against my own PR before anyone spends review time on it.

I wrote an end-to-end integration test (`NullColumnValueRoundTripIT`, MySQL 8.0.36 -> connector -> ClickHouse 24.8, real containers) covering the four production failure shapes: NULL on insert, NULL blob, UPDATE-to-NULL, and several NULL columns in one row.

**It passes with the fix in place — and it also passes with the fix reverted.** That is a negative result on my own hypothesis, not a confirmation:

```
NullColumnValueRoundTripIT, fix applied     4/4 pass
NullColumnValueRoundTripIT, fix neutralised 4/4 pass   <-- should have failed
```

The generated statement in the IT already binds every column before the fix:

```
INSERT INTO `event`(`id`,`kafka_offset`,`request`,`recorded_at`,`_version`,`is_deleted`) VALUES (?,?,?,?,?,?)
```

So on this path a NULL-valued column is **not** dropped, and the unit-level failure I based the diagnosis on does not reproduce end to end.

### What this means

The unit tests in this PR are genuine — `QueryFormatterNullValueDropTest` and `NullValueColumnDropTest` both fail without the change and pass with it, and the statement they produce really does omit `kafka_offset`. But they reach `createColumns` with a value-filtered field list, and the live connector evidently does not, at least for a plain MySQL `INSERT ... VALUES (null, ...)`. Debezium appears to emit the full after-image with explicit nulls, so `setAfterStruct`'s `s.get(f) != null` filter does not strip the column the way my unit fixtures assume.

**Therefore: the code change here is defensible as hardening (schema is the right authority for INSERT membership, not a value-filtered list), but it is NOT proven to be the cause of the production data loss, and it should not be described as the fix for it until something reproduces that loss.**

### What is solid, independent of the above

- The production divergence is real and value-level verified against the source MySQL host: `txnrepo_uat.event.kafka_offset` 3,348 rows in one day, `trade_uat.enriched_trade.capped_by` 240, plus a fully silent blob loss on `trade_uat.changelog`.
- The connector's own error log records `Column index missing for column` 65,577 times across four tables, then writes the row anyway.

So the symptom and the error are established; the mechanism I proposed is not.

### What I am doing next

Finding the input shape that actually produces the dropped column — the error log proves the connector reaches that branch in production, so something in the real record stream differs from my fixture. Candidates I have not yet eliminated:

1. A stale `columnNameToIndexMap` reused across a schema change, so the map predates a column the current table has.
2. `getWithoutDefault` under `non.default.value`, which yields a different field set than `struct.get`.
3. A path where the before-image's filtered list is used against the after-image's schema.
4. Something specific to the bitemporal/history-mode write path these tables use.

Until one of those is demonstrated, treat this PR as unverified. I would rather say that now than have it merged as a fix for a bug it may not fix.
