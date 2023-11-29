# Load Testing using SysBench.

## Setup
We use `SysBench` to perform load testing.
https://github.com/akopytov/sysbench

Before starting the connectors, create the topic with max partitions 

`rpk topic create SERVER5432.sbtest.sbtest1  -p 6`

To Run SysBench tests, run the `debezium-connector-setup-sysbench.sh` script in `deploy` folder to 
create the MySQL debezium connector. The connector is setup to read from the `sbtest` table required by SysBench

For sink, the default `sink-connector-setup-schema-registry.sh` script can be executed to create 
the ClickHouse Sink Connector.

## SysBench Tests
| SysBench Test         | Status |
|-----------------------|--------|
| oltp_insert           | Pass   |
| oltp_read_write       | Pass   |
| oltp_update_index     | Pass   |
| oltp_update_non_index | Pass   |
| oltp_delete           | Pass   |
| bulk_insert           | Pass   |

## Insert tests(SysBench)
`sysbench/run_sysbench_insert_load_test.sh` script executes the oltp_insert lua script in Sysbench.

## Update/Delete tests(SysBench)


## Performance Numbers
![](img/insert_performance_tests.png) \

`select database, table, event_type, partition_id, count() c, round(avg(rows)) from system.part_log
where event_date >= today() and event_type = 'NewPart'
group by database, table, event_type, partition_id
order by c desc`\

`
select sum(tmp.rows), tmp.event_time from (
select rows,event_time ,event_type  from system.part_log pl where database='test' and table='sbtest1' and event_type='NewPart' order by event_time desc) tmp group by tmp.event_time`

With the SysBench insert tests(6 Kafka partitions), the following are the numbers we observed on 8-core i7 64 GB RAM instance.\
<b>ClickHouse Rows Insertion Rate: 9k/second, 2.1 MB/second.

## ClickHouse insertion rate
`select database, table, event_type, partition_id, count() c, round(avg(rows)) from system.part_log
where event_date >= today() and event_type = 'NewPart'
group by database, table, event_type, partition_id
order by c desc`

Target: 
5 threads , 600k/second

## Binary logs

`show binary logs;`

`show binlog events in `mysql-bin.000003`