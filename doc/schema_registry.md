# Confluent Schema Registry
### Schema Registry API calls.

REST API to get subjects
```
curl -X GET http://localhost:80801/subjects
["SERVER5432-key",
"SERVER5432-value",
"SERVER5432.test.customers-key",
"SERVER5432.test.customers-value",
"SERVER5432.test.employees-key",
"SERVER5432.test.employees-value",
"SERVER5432.test.employees_predated-key",
"SERVER5432.test.employees_predated-value"]
```

#### REST API to get schemas(Key)
```
curl -X GET  http://localhost:8081/schemas/ids/3
{"schema":"{\"type\":\"record\",\"name\":\"Key\",\"namespace\":\"SERVER5432.test.customers\",
\"fields\":[{\"name\":\"customerNumber\",\"type\":\"int\"}],\"connect.name\":\"SERVER5432.test.customers.Key\"}"}
```


#### REST API to get schemas(Value)
```
curl -X GET  http://localhost:8081/schemas/ids/4
{"schema":"{\"type\":\"record\",\"name\":\"Envelope\",\"namespace\":\"SERVER5432.test.customers\",
\"fields\":[{\"name\":\"before\",\"type\":[\"null\",{\"type\":\"record\",\"name\":\"Value\",
\"fields\":[{\"name\":\"customerNumber\",\"type\":\"int\"},{\"name\":\"customerName\",\"type\":\"string\"},
{\"name\":\"contactLastName\",\"type\":\"string\"},{\"name\":\"contactFirstName\",\"type\":\"string\"},
{\"name\":\"phone\",\"type\":\"string\"},{\"name\":\"addressLine1\",\"type\":\"string\"},
{\"name\":\"addressLine2\",\"type\":[\"null\",\"string\"],\"default\":null},
{\"name\":\"city\",\"type\":\"string\"},
{\"name\":\"state\",\"type\":[\"null\",\"string\"],\"default\":null},
{\"name\":\"postalCode\",\"type\":[\"null\",\"string\"],\"default\":null},
{\"name\":\"country\",\"type\":\"string\"},
{\"name\":\"salesRepEmployeeNumber\",\"type\":[\"null\",\"int\"],\"default\":null},
{\"name\":\"creditLimit\",\
"type\":[\"null\",{\"type\":\"bytes\",\"scale\":2,\"precision\":10,
\"connect.version\":1,\"connect.parameters\":{\"scale\":\"2\",
\"connect.decimal.precision\":\"10\"},
\"connect.name\":\"org.apache.kafka.connect.data.Decimal\",\"logicalType\":\"decimal\"}],\"default\":null}],
\"connect.name\":\"SERVER5432.test.customers.Value\"}],\"default\":null},
{\"name\":\"after\",\"type\":[\"null\",\"Value\"],\"default\":null},{\"name\":\"source\",\
"type\":{\"type\":\"record\",\"name\":\"Source\",\"namespace\":\"io.debezium.connector.mysql\",\
"fields\":[{\"name\":\"version\",\"type\":\"string\"},{\"name\":\"connector\",\"type\":\"string\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"ts_ms\",\"type\":\"long\"},{\"name\":\"snapshot\",\"type\":[{\"type\":\"string\",\"connect.version\":1,\"connect.parameters\":{\"allowed\":\"true,last,false,incremental\"},\"connect.default\":\"false\",\"connect.name\":\"io.debezium.data.Enum\"},\"null\"],\"default\":\"false\"},{\"name\":\"db\",\"type\":\"string\"},{\"name\":\"sequence\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"table\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"server_id\",\"type\":\"long\"},{\"name\":\"gtid\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"file\",\"type\":\"string\"},{\"name\":\"pos\",\"type\":\"long\"},{\"name\":\"row\",\"type\":\"int\"},{\"name\":\"thread\",\"type\":[\"null\",\"long\"],\"default\":null},{\"name\":\"query\",\"type\":[\"null\",\"string\"],\"default\":null}],\"connect.name\":\"io.debezium.connector.mysql.Source\"}},{\"name\":\"op\",\"type\":\"string\"},{\"name\":\"ts_ms\",\"type\":[\"null\",\"long\"],\"default\":null},{\"name\":\"transaction\",\"type\":[\"null\",{\"type\":\"record\",\"name\":\"ConnectDefault\",\"namespace\":\"io.confluent.connect.avro\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"total_order\",\"type\":\"long\"},{\"name\":\"data_collection_order\",\"type\":\"long\"}]}],\"default\":null}],\"connect.name\":\"SERVER5432.test.customers.Envelope\"}"}
```

#### REST API to get versions associated with schema.
```
 curl -X GET http://localhost:8081/schemas/ids/4/versions
[{"subject":"SERVER5432.test.customers-value","version":1}]
```

#### Alter Schema
```
alter table test.customers drop column country
```

#### Check binlog

```
 show binlog events in 'mysql-bin.000003';
+------------------+-----+----------------+-----------+-------------+------------------------------------------------------------------------------------------------------------------------------------------+
| Log_name         | Pos | Event_type     | Server_id | End_log_pos | Info                                                                                                                                     |
+------------------+-----+----------------+-----------+-------------+------------------------------------------------------------------------------------------------------------------------------------------+
| mysql-bin.000003 |   4 | Format_desc    |       268 |         126 | Server ver: 8.0.30, Binlog ver: 4                                                                                                        |
| mysql-bin.000003 | 126 | Previous_gtids |       268 |         197 | 83109c79-2912-11ed-b91f-0242ac150007:1-56                                                                                                |
| mysql-bin.000003 | 197 | Gtid           |       268 |         276 | SET @@SESSION.GTID_NEXT= '83109c79-2912-11ed-b91f-0242ac150007:57'                                                                       |
| mysql-bin.000003 | 276 | Query          |       268 |         474 | use `test`; /* ApplicationName=DBeaver 21.2.1 - SQLEditor <Script-5.sql> */ alter table test.customers drop column country /* xid=623 */ |
+------------------+-----+----------------+-----------+-------------+------------------------------------------------------------------------------------------------------------------------------------------+
```

### Check the versions for subject
```
curl -X GET http://localhost:8081/schemas/ids/4/versions
[{"subject":"SERVER5432.test.customers-value","version":1}
```