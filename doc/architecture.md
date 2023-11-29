# Kafka Sink Connector Architecture

## Exactly Once Semantics

Exactly once semantics are guaranteed by storing offsets(by topic, partition) in a separate 
clickhouse table. The table will be ordered by time and a materialized view will be created as a 
cache to avoid expensive table scans.

Topic management will be disabled in Kafka connect and the `precommit()` function in 
SinkTask would return the offsets stored in clickhouse.

References:
[1] https://stackoverflow.com/questions/55608325/clickhouse-select-last-record-without-max-on-all-table