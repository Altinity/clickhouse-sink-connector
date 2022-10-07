# MySQL to ClickHouse Replication

Regression testing of MySQL to ClickHouse Replication.

Tests with using [TestFlows](https://testflows.com/) environment.

Make changes of clickhouse-sink-connector version in `mysql_to_clickhouse_replication/mysql_to_clickhouse_replication_env/docker-compose.yml`
line
```commandline
image: altinity/clickhouse-sink-connector: {version}
```

Where `version` is corresponds to clickhouse-sink-connector version you want to test.

Use `python3 -u mysql_to_clickhouse_replication/regression.py --only 
"/mysql to clickhouse replication/{module name}/{test name}/*" --test-to-end -o classic`
to run tests local.

You can start all tests by 

`python3 -u mysql_to_clickhouse_replication/regression.py --only 
"/mysql to clickhouse replication/*" --test-to-end -o classic`

Example,

```commandline
python3 -u mysql_to_clickhouse_replication/regression.py --only "/mysql to clickhouse replication/sanity/mysql to clickhouse connection ac/*" --test-to-end -o classic

```

To save service logs to mysql_to_clickhouse_replication/_instances folder use `--collect-service-logs`

To run all test with error ignore use `--test-to-end`
