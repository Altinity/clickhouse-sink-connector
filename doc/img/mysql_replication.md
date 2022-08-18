### Snapshot Mode
Debezium does not offer parallelism in snapshot mode.
Also tables are not snapshotted in parallel,
If the table list has tableA, tableB

tableB snapshot is started only after tableA snapshot is finished.


500k records in 1 minute
1 Million records in 2 minutes

https://issues.redhat.com/projects/DBZ/issues/DBZ-823?filter=allopenissues

https://debezium.zulipchat.com/#narrow/stream/302529-users/topic/Increase.20snapshot.20throughput.20-.20MySQL

