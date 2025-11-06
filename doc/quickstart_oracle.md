Setup Oracle using the enterprise image. You need an account setup to download the enterprise image from container registry(Oracle)
https://debezium.io/blog/2022/09/30/debezium-oracle-series-part-1/

Start oracle,clickhouse using docker-compose
`docker compose -f docker-compose-oracle.yml up`

If you are connecting to a diffferent oracle/clickhouse, update the following fields in `config_oracle.yml` before starting sink connector.

**Oracle**
```
database.hostname: "localhost"
database.port: "1521"

database.dbname: "ORCLCDB"
database.pdb.name: "ORCLPDB1"

#database.pdb.name: "ORCLPDB1"

database.user: "c##dbzuser"

database.password: "dbz"

database.connection.url: "jdbc:oracle:thin:@//localhost:1521/ORCLCDB"

database.server.name: "dbserver1"
database.connection.adapter: "logminer"
```

**ClickHouse**
in the jdbc.url, change `localhost` if its not running locally
```
clickhouse.server.url: "localhost"

clickhouse.server.user: "root"

clickhouse.server.password: "root"

clickhouse.server.port: "8123"

offset.storage.jdbc.url: "jdbc:clickhouse://localhost:8123/altinity_sink_connector"
schema.history.internal.jdbc.url: "jdbc:clickhouse://localhost:8123/altinity_sink_connector"

```

**NOTE**: Currently DDL is not supported, the default is MySQL ANTLR parser , we need to integrate Debezium Oracle ANTLR parser. So the tables have to be manually created in ClickHouse.


Start sink connector JAR.

`java -jar clickhouse-debezium-embedded-0.0.4.jar ../docker/config_oracle.yml `

*Testing*

*Oracle*
```
SQL> CREATE TABLE customers (id number(9,0) primary key, name varchar2(50));
SQL> insert into customers values(1008, 'Anne5');
INSERT INTO customers VALUES (1001, 'Salles Thomas');
INSERT INTO customers VALUES (1002, 'George Bailey');
INSERT INTO customers VALUES (1003, 'Edward Walker');
INSERT INTO customers VALUES (1004, 'Anne Kretchmar');
SQL> insert into customers values(1005, 'Anne2');
SQL> insert into customers values(1005, 'Anne2');
SQL> insert into customers values(1005, 'Anne2');

```

*ClickHouse*
```

be4cbfdee689 :) use ORCLPDB1


be4cbfdee689 :) select * from CUSTOMERS;

SELECT *
FROM CUSTOMERS

Query id: 6cf7e2cf-dc1c-485f-b50f-77650b07b76a

┌───ID─┬─NAME───────────┬────────────_version─┬─_sign─┐
│ 1001 │ Salles Thomas  │ 1753390061439000001 │     0 │
│ 1002 │ George Bailey  │ 1753390061442000002 │     0 │
│ 1003 │ Edward Walker  │ 1753390061442000003 │     0 │
│ 1004 │ Anne Kretchmar │ 1753390061442000004 │     0 │
│ 1005 │ Anne2          │ 1753390061443000005 │     0 │
│ 1006 │ Anne2          │ 1753390061443000006 │     0 │
│ 1007 │ Anne3          │ 1753390061443000007 │     0 │
└──────┴────────────────┴─────────────────────┴───────┘
```
