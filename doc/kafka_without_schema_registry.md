** Instructions to run ClickHouse Sink Connector without Schema Registry **

Update docker version , either by setting the `SINK_VERSION` environment variable or editing the `docker-compose-no-schema-registry.yaml` file directly.
```
cd sink-connector/deploy/docker
```
Edit docker-compose-no-schema-registry.yaml
Change this line from
```
image: altinity/clickhouse-sink-connector:${SINK_VERSION}

to
image: altinityinfra/clickhouse-sink-connector:1130-b676996481d9c9b64a6bf18d1fdfa5780e9d0ebb-kafka
```
Start the docker containers without schema registry
```
docker compose -f docker-compose-no-schema-registry.yaml up
```

Create the debezium source connector and ClickHouse sink connector by running the following scripts.
```
cd sink-connector/deploy
./debezium-connector-setup-database-json-converter.sh
cd sink-connector/deploy
./sink-connector-setup-database-json-converter.sh
```

Create a table and insert rows in MySQL
password: root
```
docker exec -it <container_id> bash
mysql -u root -p

use test;

CREATE TABLE employees (
id INT AUTO_INCREMENT PRIMARY KEY,
name VARCHAR(100) NOT NULL,
department VARCHAR(50),
hire_date DATE
);

INSERT INTO employees (name, department, hire_date)
VALUES
('Alice Johnson', 'Engineering', '2023-06-01'),
('Bob Smith', 'Sales', '2022-11-15'),
('Charlie Lee', 'Marketing', '2024-01-10'),
('Diana Prince', 'Engineering', '2021-09-20');
```

Retrieve the message from Redpanda.
```
docker exec -it <redpanda_container> bash
rpk topic list
redpanda@82ba4f537cce:/$ rpk topic list
NAME                           PARTITIONS  REPLICAS
SERVER5432                     6           1
SERVER5432.test.employees      1           1
SERVER5432.transaction         6           1
config-storage-topic-debezium  1           1
config-storage-topic-sink      1           1
offset-storage-topic-debezium  25          1
offset-storage-topic-sink      25          1
schema_history_topic           1           1
status-storage-topic-debezium  5           1
status-storage-topic-sink      5           1
redpanda@82ba4f537cce:/$ rpk  topic consume SERVER5432.test.employees
{
"topic": "SERVER5432.test.employees",
"key": "{\"schema\":{\"type\":\"struct\",\"fields\":[{\"type\":\"int32\",\"optional\":false,\"field\":\"id\"}],\"optional\":false,\"name\":\"SERVER5432.test.employees.Key\"},\"payload\":{\"id\":1}}",
"value": "{\"schema\":{\"type\":\"struct\",\"fields\":[{\"type\":\"struct\",\"fields\":[{\"type\":\"int32\",\"optional\":false,\"field\":\"id\"},{\"type\":\"string\",\"optional\":false,\"field\":\"name\"},{\"type\":\"string\",\"optional\":true,\"field\":\"department\"},{\"type\":\"int32\",\"optional\":true,\"name\":\"io.debezium.time.Date\",\"version\":1,\"field\":\"hire_date\"}],\"optional\":true,\"name\":\"SERVER5432.test.employees.Value\",\"field\":\"before\"},{\"type\":\"struct\",\"fields\":[{\"type\":\"int32\",\"optional\":false,\"field\":\"id\"},{\"type\":\"string\",\"optional\":false,\"field\":\"name\"},{\"type\":\"string\",\"optional\":true,\"field\":\"department\"},{\"type\":\"int32\",\"optional\":true,\"name\":\"io.debezium.time.Date\",\"version\":1,\"field\":\"hire_date\"}],\"optional\":true,\"name\":\"SERVER5432.test.employees.Value\",\"field\":\"after\"},{\"type\":\"struct\",\"fields\":[{\"type\":\"string\",\"optional\":false,\"field\":\"version\"},{\"type\":\"string\",\"optional\":false,\"field\":\"connector\"},{\"type\":\"string\",\"optional\":false,\"field\":\"name\"},{\"type\":\"int64\",\"optional\":false,\"field\":\"ts_ms\"},{\"type\":\"string\",\"optional\":true,\"name\":\"io.debezium.data.Enum\",\"version\":1,\"parameters\":{\"allowed\":\"true,last,false,incremental\"},\"default\":\"false\",\"field\":\"snapshot\"},{\"type\":\"string\",\"optional\":false,\"field\":\"db\"},{\"type\":\"string\",\"optional\":true,\"field\":\"sequence\"},{\"type\":\"string\",\"optional\":true,\"field\":\"table\"},{\"type\":\"int64\",\"optional\":false,\"field\":\"server_id\"},{\"type\":\"string\",\"optional\":true,\"field\":\"gtid\"},{\"type\":\"string\",\"optional\":false,\"field\":\"file\"},{\"type\":\"int64\",\"optional\":false,\"field\":\"pos\"},{\"type\":\"int32\",\"optional\":false,\"field\":\"row\"},{\"type\":\"int64\",\"optional\":true,\"field\":\"thread\"},{\"type\":\"string\",\"optional\":true,\"field\":\"query\"}],\"optional\":false,\"name\":\"io.debezium.connector.mysql.Source\",\"field\":\"source\"},{\"type\":\"string\",\"optional\":false,\"field\":\"op\"},{\"type\":\"int64\",\"optional\":true,\"field\":\"ts_ms\"},{\"type\":\"struct\",\"fields\":[{\"type\":\"string\",\"optional\":false,\"field\":\"id\"},{\"type\":\"int64\",\"optional\":false,\"field\":\"total_order\"},{\"type\":\"int64\",\"optional\":false,\"field\":\"data_collection_order\"}],\"optional\":true,\"name\":\"event.block\",\"version\":1,\"field\":\"transaction\"}],\"optional\":false,\"name\":\"SERVER5432.test.employees.Envelope\",\"version\":1},\"payload\":{\"before\":null,\"after\":{\"id\":1,\"name\":\"Alice Johnson\",\"department\":\"Engineering\",\"hire_date\":19509},\"source\":{\"version\":\"2.1.0.Alpha1\",\"connector\":\"mysql\",\"name\":\"SERVER5432\",\"ts_ms\":1758122504000,\"snapshot\":\"false\",\"db\":\"test\",\"sequence\":null,\"table\":\"employees\",\"server_id\":955,\"gtid\":\"823c77a7-93d9-11f0-ab44-0242ac1b0002:9\",\"file\":\"mysql-bin.000003\",\"pos\":727,\"row\":0,\"thread\":18,\"query\":null},\"op\":\"c\",\"ts_ms\":1758122504199,\"transaction\":{\"id\":\"823c77a7-93d9-11f0-ab44-0242ac1b0002:9\",\"total_order\":1,\"data_collection_order\":1}}}",
"timestamp": 1758122504787,
"partition": 0,
"offset": 0
}
3f6a31cb6662 :) use test;

USE test

Query id: 10a22f6b-b1bd-497d-b1d9-f742f88caec5

Ok.

0 rows in set. Elapsed: 0.001 sec.

3f6a31cb6662 :) show tables;

SHOW TABLES

Query id: 8902863d-3ff7-40e9-b772-3533f675c80a

┌─name──────┐
│ employees │
└───────────┘

1 row in set. Elapsed: 0.003 sec.

3f6a31cb6662 :) select * from employees;

SELECT *
FROM employees

Query id: 2e1ad664-6779-4412-9c18-521aa425327f

┌─id─┬─name──────────┬─department──┬──hire_date─┬────────────_version─┬─_sign─┐
│  1 │ Alice Johnson │ Engineering │ 2023-06-01 │ 1968334561473462281 │     0 │
└────┴───────────────┴─────────────┴────────────┴─────────────────────┴───────┘
┌─id─┬─name─────────┬─department──┬──hire_date─┬────────────_version─┬─_sign─┐
│  2 │ Bob Smith    │ Sales       │ 2022-11-15 │ 1968334561473462281 │     0 │
│  3 │ Charlie Lee  │ Marketing   │ 2024-01-10 │ 1968334561473462281 │     0 │
│  4 │ Diana Prince │ Engineering │ 2021-09-20 │ 1968334561473462281 │     0 │
└────┴──────────────┴─────────────┴────────────┴─────────────────────┴───────┘

4 rows in set. Elapsed: 0.004 sec.

3f6a31cb6662 :)
```
Producing messages(With rpk)
```
redpanda@dacd196f001b:/tmp$ rpk topic produce SERVER5432.test.employees -f '%k %v{json}'
{"schema":{"type":"struct","fields":[{"type":"int32","optional":false,"field":"id"}],"optional":false,"name":"SERVER5432.test.employees.Key"},"payload":{"id":5}} {"schema":{"type":"struct","fields":[{"type":"struct","fields":[{"type":"int32","optional":false,"field":"id"},{"type":"string","optional":false,"field":"name"},{"type":"string","optional":true,"field":"department"},{"type":"int32","optional":true,"name":"io.debezium.time.Date","version":1,"field":"hire_date"}],"optional":true,"name":"SERVER5432.test.employees.Value","field":"before"},{"type":"struct","fields":[{"type":"int32","optional":false,"field":"id"},{"type":"string","optional":false,"field":"name"},{"type":"string","optional":true,"field":"department"},{"type":"int32","optional":true,"name":"io.debezium.time.Date","version":1,"field":"hire_date"}],"optional":true,"name":"SERVER5432.test.employees.Value","field":"after"},{"type":"struct","fields":[{"type":"string","optional":false,"field":"version"},{"type":"string","optional":false,"field":"connector"},{"type":"string","optional":false,"field":"name"},{"type":"int64","optional":false,"field":"ts_ms"},{"type":"string","optional":true,"name":"io.debezium.data.Enum","version":1,"parameters":{"allowed":"true,last,false,incremental"},"default":"false","field":"snapshot"},{"type":"string","optional":false,"field":"db"},{"type":"string","optional":true,"field":"sequence"},{"type":"string","optional":true,"field":"table"},{"type":"int64","optional":false,"field":"server_id"},{"type":"string","optional":true,"field":"gtid"},{"type":"string","optional":false,"field":"file"},{"type":"int64","optional":false,"field":"pos"},{"type":"int32","optional":false,"field":"row"},{"type":"int64","optional":true,"field":"thread"},{"type":"string","optional":true,"field":"query"}],"optional":false,"name":"io.debezium.connector.mysql.Source","field":"source"},{"type":"string","optional":false,"field":"op"},{"type":"int64","optional":true,"field":"ts_ms"},{"type":"struct","fields":[{"type":"string","optional":false,"field":"id"},{"type":"int64","optional":false,"field":"total_order"},{"type":"int64","optional":false,"field":"data_collection_order"}],"optional":true,"name":"event.block","version":1,"field":"transaction"}],"optional":false,"name":"SERVER5432.test.employees.Envelope","version":1},"payload":{"before":null,"after":{"id":5,"name":"Dia","department":"Engineering","hire_date":18890},"source":{"version":"2.1.0.Alpha1","connector":"mysql","name":"SERVER5432","ts_ms":1758131704000,"snapshot":"last","db":"test","sequence":null,"table":"employees","server_id":0,"gtid":null,"file":"mysql-bin.000003","pos":918,"row":0,"thread":null,"query":null},"op":"r","ts_ms":1758131704136,"transaction":null}}
Produced to partition 0 at offset 0 with timestamp 1758136725967.
Sink connector

sink          | [INFO ] 2025-09-17 19:18:46.972 [Task: Sink Connector thread-pool-0askId] ClickHouseSinkConnectorConfig - **** Task(0), AUTO CREATE TABLE (employees) Database(test) ***
sink          | [INFO ] 2025-09-17 19:18:46.999 [Task: Sink Connector thread-pool-0askId] ClickHouseAutoCreateTable - **** AUTO CREATE TABLE for database(test), Query :CREATE TABLE test.`employees`(`id` Int32 NOT NULL,`name` String NOT NULL,`department` String NULL,`hire_date` Date32 NULL,`_version` UInt64,`_sign` UInt8)  Engine=ReplacingMergeTree(_version,_sign) PRIMARY KEY(id) ORDER BY(id))
sink          | [INFO ] 2025-09-17 19:18:47.087 [Task: Sink Connector thread-pool-0askId] PreparedStatementExecutor - *** INSERT QUERY for Database(test) ***: insert into `employees`(`id`,`name`,`department`,`hire_date`,`_version`,`_sign`) select `id`,`name`,`department`,`hire_date`,`_version`,`_sign` from input('`id` Int32,`name` String,`department` Nullable(String),`hire_date` Nullable(Date32),`_version` UInt64,`_sign` UInt8')
sink          | [INFO ] 2025-09-17 19:18:47.132 [Task: Sink Connector thread-pool-0askId] PreparedStatementExecutor - *************** EXECUTED BATCH Successfully Records: 1************** task(0) Thread ID: Sink Connector thread-pool-0 Result: [I@40ed7c07 Database: test Table: employees
https://stackoverflow.com/questions/62070151/how-to-send-key-value-messages-with-the-kafka-console-producer
```

Producing messages(with kafka-console-producer.sh)
```
kafka-console-producer.sh --broker-list localhost:9092 --topic topic-name --property "parse.key=true" --property "key.separator=:"
```

