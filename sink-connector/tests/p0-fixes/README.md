: # P0 Critical Bugs - Test Environment

This directory contains the docker-compose test environment for validating the 7 P0 critical bug fixes.

## Bug Fixes Validated

### Concurrency Bugs
1. **BUG-CONC-1**: HashMap race conditions → ConcurrentHashMap
2. **BUG-CONC-2**: DDL cache synchronization 
3. **BUG-CONC-4**: Connection leak prevention
4. **BUG-CONC-5**: Check-then-act race with putIfAbsent

### Data Type Bugs
5. **BUG-DATA-1**: NULL validation for non-nullable columns
6. **BUG-DATA-6**: Binary data hex encoding

### Transaction Bugs
7. **BUG-TX-3**: Retry logic with exponential backoff

## Architecture

```
┌─────────────┐         ┌─────────────┐
│   MySQL     │────────▶│   Debezium  │
│  (Source)   │         │  Connector  │
└─────────────┘         └──────┬──────┘
                               │
                               ▼
                        ┌──────────────┐
                        │    Kafka     │
                        │   Topics     │
                        └──────┬───────┘
                               │
                               ▼
                     ┌─────────────────┐
                     │   ClickHouse    │
                     │ Sink Connector  │
                     │  (P0 FIXES)     │
                     └────────┬────────┘
                              │
                              ▼
                      ┌───────────────┐
                      │  ClickHouse   │
                      │   Database    │
                      └───────────────┘
```

## Quick Start

### 1. Build the Connector
```bash
cd ../../..  # Go to project root
mvn clean package -DskipTests
```

### 2. Start Test Environment
```bash
cd sink-connector/tests/p0-fixes
docker-compose up -d
```

### 3. Wait for Services to be Ready
```bash
# Check all services are healthy
docker-compose ps

# Should show all services as "healthy"
```

### 4. Configure Debezium Source Connector
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mysql-source-connector",
    "config": {
      "connector.class": "io.debezium.connector.mysql.MySqlConnector",
      "tasks.max": "1",
      "database.hostname": "mysql",
      "database.port": "3306",
      "database.user": "debezium",
      "database.password": "debezium",
      "database.server.id": "184054",
      "database.server.name": "testserver",
      "database.include.list": "testdb",
      "database.history.kafka.bootstrap.servers": "kafka:9092",
      "database.history.kafka.topic": "schema-changes.testdb",
      "include.schema.changes": "true"
    }
  }'
```

### 5. Configure ClickHouse Sink Connector
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d '{
    "name": "clickhouse-sink-connector",
    "config": {
      "connector.class": "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector",
      "tasks.max": "4",
      "topics": "testserver.testdb.test_null_handling,testserver.testdb.test_binary_data,testserver.testdb.test_concurrency,testserver.testdb.test_schema_evolution,testserver.testdb.test_multi_db",
      "clickhouse.server.url": "clickhouse",
      "clickhouse.server.port": "8123",
      "clickhouse.server.user": "default",
      "clickhouse.server.password": "clickhouse",
      "clickhouse.server.database": "testdb",
      "clickhouse.table.name": "{table}",
      "key.converter": "org.apache.kafka.connect.json.JsonConverter",
      "value.converter": "org.apache.kafka.connect.json.JsonConverter",
      "key.converter.schemas.enable": "false",
      "value.converter.schemas.enable": "false",
      "store.kafka.metadata": "true",
      "thread.pool.size": "4",
      "enable.schema.evolution": "true",
      "buffer.max.records": "1000"
    }
  }'
```

## Test Scenarios

### Test 1: NULL Handling (BUG-DATA-1)
```bash
# Insert record with NULL in optional field - should succeed
docker exec mysql-p0-test mysql -uroot -proot testdb -e \
  "INSERT INTO test_null_handling (required_field, optional_field) VALUES ('test1', NULL);"

# Try to insert NULL in required field - should fail with clear error
# (This would be caught at MySQL level, but ClickHouse should also validate)
```

### Test 2: Binary Data Encoding (BUG-DATA-6)
```bash
# Insert binary data
docker exec mysql-p0-test mysql -uroot -proot testdb -e \
  "INSERT INTO test_binary_data (binary_col, varbinary_col, blob_col) 
   VALUES (0xDEADBEEF, 0xCAFEBABE, 0x1234567890ABCDEF);"

# Verify hex encoding in ClickHouse
docker exec clickhouse-p0-test clickhouse-client --query \
  "SELECT binary_col, varbinary_col, blob_col FROM testdb.test_binary_data WHERE id = 3"

# Should show hex-encoded values like "deadbeef", "cafebabe", "1234567890abcdef"
```

### Test 3: Concurrency - HashMap Race (BUG-CONC-1)
```bash
# Generate high-volume concurrent inserts
for i in {1..1000}; do
  docker exec mysql-p0-test mysql -uroot -proot testdb -e \
    "INSERT INTO test_concurrency (database_name, table_name, thread_id, operation, timestamp, data) 
     VALUES ('db$((i%5))', 'table$i', $((i%10)), 'INSERT', UNIX_TIMESTAMP(), 'data$i');" &
done
wait

# Check for no data loss (should have 1000+ records)
docker exec clickhouse-p0-test clickhouse-client --query \
  "SELECT COUNT(*) FROM testdb.test_concurrency"
```

### Test 4: Schema Evolution - DDL Cache (BUG-CONC-2)
```bash
# Add column while inserts are happening
docker exec mysql-p0-test mysql -uroot -proot testdb -e \
  "ALTER TABLE test_schema_evolution ADD COLUMN phone VARCHAR(20)"

# Insert with new column
docker exec mysql-p0-test mysql -uroot -proot testdb -e \
  "INSERT INTO test_schema_evolution (name, email, phone) 
   VALUES ('Dave', 'dave@example.com', '555-1234')"

# Verify schema updated in ClickHouse
docker exec clickhouse-p0-test clickhouse-client --query \
  "DESCRIBE testdb.test_schema_evolution"
```

### Test 5: Connection Leak (BUG-CONC-4)
```bash
# Trigger exceptions by inserting invalid data
for i in {1..100}; do
  # This will cause exceptions but connections should not leak
  docker exec mysql-p0-test mysql -uroot -proot testdb -e \
    "INSERT INTO test_concurrency VALUES (999999999999, 'db1', 't1', 1, 'TEST', 0, 'x')" 2>/dev/null || true &
done
wait

# Check connector is still healthy
curl -s http://localhost:8083/connectors/clickhouse-sink-connector/status | jq '.connector.state'
# Should be "RUNNING", not crashed
```

### Test 6: Retry Logic (BUG-TX-3)
```bash
# Monitor connector logs for retry messages
docker logs -f kafka-connect-p0-test 2>&1 | grep -i "retry"

# Look for messages like:
# "Batch attempt 1 failed..., retrying in 1000ms..."
# "Batch attempt 2 failed..., retrying in 2000ms..."
# "EXECUTED BATCH Successfully"
```

## Validation Queries

### Check Data Integrity
```bash
# Compare MySQL and ClickHouse counts
MYSQL_COUNT=$(docker exec mysql-p0-test mysql -uroot -proot testdb -N -e \
  "SELECT COUNT(*) FROM test_null_handling")
  
CH_COUNT=$(docker exec clickhouse-p0-test clickhouse-client --query \
  "SELECT COUNT(*) FROM testdb.test_null_handling" -t)

echo "MySQL: $MYSQL_COUNT, ClickHouse: $CH_COUNT"
```

### Check for Errors
```bash
# Check connector errors
curl -s http://localhost:8083/connectors/clickhouse-sink-connector/status | \
  jq '.tasks[].trace'

# Check ClickHouse logs
docker logs clickhouse-p0-test 2>&1 | grep -i error

# Check Kafka Connect logs
docker logs kafka-connect-p0-test 2>&1 | grep -i "ERROR\|Exception" | tail -20
```

## Running Unit Tests

```bash
# Run the Java unit tests
cd ../../..  # Project root
mvn test -Dtest=ConcurrencyBugsTest
mvn test -Dtest=DataTypeBugsTest
```

## Cleanup

```bash
# Stop and remove all containers
docker-compose down -v

# Remove all data volumes
docker volume rm p0-fixes_clickhouse-data p0-fixes_mysql-data
```

## Success Criteria

All 7 P0 fixes are validated if:

1. ✅ No `ConcurrentModificationException` in logs
2. ✅ No connection leak errors (connector remains healthy)
3. ✅ NULL validation errors are clear and logged
4. ✅ Binary data is hex-encoded correctly
5. ✅ DDL cache updates properly synchronized
6. ✅ Retry logic shows exponential backoff (1s, 2s, 4s)
7. ✅ All data successfully replicated from MySQL to ClickHouse

## Monitoring

### Real-time Metrics
```bash
# Watch connector status
watch -n 5 'curl -s http://localhost:8083/connectors/clickhouse-sink-connector/status | jq .'

# Watch ClickHouse record counts
watch -n 5 'docker exec clickhouse-p0-test clickhouse-client --query "SELECT COUNT(*) FROM testdb.test_concurrency"'

# Watch for errors in logs
docker logs -f kafka-connect-p0-test 2>&1 | grep --color -E "ERROR|WARN|SUCCESS|retry"
```

## Troubleshooting

### Connector Not Starting
```bash
# Check connector config
curl -s http://localhost:8083/connectors/clickhouse-sink-connector | jq .

# Restart connector
curl -X POST http://localhost:8083/connectors/clickhouse-sink-connector/restart
```

### Data Not Replicating
```bash
# Check Kafka topics
docker exec kafka-p0-test kafka-topics --list --bootstrap-server localhost:9092

# Check topic data
docker exec kafka-p0-test kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic testserver.testdb.test_null_handling \
  --from-beginning --max-messages 5
```

### Database Connection Issues
```bash
# Test ClickHouse connection
docker exec clickhouse-p0-test clickhouse-client --query "SELECT 1"

# Test MySQL connection
docker exec mysql-p0-test mysql -uroot -proot -e "SELECT 1"
```

## Related Documentation

- [Concurrency Bugs](../../../issues/CONCURRENCY-BUGS.md)
- [Data Type Bugs](../../../issues/DATA-TYPE-BUGS.md)
- [Fixes Priority](../../../issues/FIXES-PRIORITY.md)
- [Production Readiness](../../../issues/PRODUCTION-READINESS.md)
