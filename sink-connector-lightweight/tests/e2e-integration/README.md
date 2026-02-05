# End-to-End Integration Tests

Comprehensive end-to-end testing for the ClickHouse Sink Connector using Docker Compose.

## Overview

This test suite validates complete MySQL to ClickHouse replication using the embedded sink connector (no Kafka required).

## Test Coverage

### Data Types (Tests 1-4)
- ✓ All numeric types (TINYINT, SMALLINT, MEDIUMINT, INT, BIGINT, DECIMAL, FLOAT, DOUBLE)
- ✓ All string types (CHAR, VARCHAR, TEXT, MEDIUMTEXT, LONGTEXT, BINARY, BLOB)
- ✓ Date/time types (DATE, DATETIME, TIMESTAMP, TIME, YEAR)
- ✓ Unicode and UTF-8 (emoji, Chinese, Arabic, Russian, mixed)

### DDL Operations (Tests 5-10)
- ✓ ALTER TABLE ADD COLUMN
- ✓ ALTER TABLE DROP COLUMN
- ✓ ALTER TABLE RENAME COLUMN
- ✓ ALTER TABLE MODIFY COLUMN (type changes)
- ✓ RENAME TABLE
- ✓ CREATE TABLE / DROP TABLE

### DML Operations (Tests 11-15)
- ✓ INSERT (single and bulk)
- ✓ UPDATE (single and multiple rows)
- ✓ DELETE (single and multiple rows)
- ✓ REPLACE
- ✓ INSERT ON DUPLICATE KEY UPDATE

### Transactions (Tests 16-19)
- ✓ Simple COMMIT
- ✓ ROLLBACK (verify data not replicated)
- ✓ Multi-statement transactions (atomicity)
- ✓ SAVEPOINT and nested transactions

### Edge Cases (Tests 20-24)
- ✓ NULL values
- ✓ Empty strings vs NULL
- ✓ Large TEXT/BLOB data
- ✓ Special float values
- ✓ Concurrent operations

### Complex Scenarios (Tests 25-27)
- ✓ Mixed operations within transactions
- ✓ Live schema changes during replication
- ✓ Bulk operations (100+ rows)

**Total: 27+ comprehensive test scenarios**

## Architecture

```
┌─────────┐       ┌──────────────────┐       ┌────────────┐
│  MySQL  │──────▶│  Sink Connector  │──────▶│ ClickHouse │
│  (CDC)  │       │   (Embedded)     │       │  (Target)  │
└─────────┘       └──────────────────┘       └────────────┘
     │                                              │
     │                                              │
     └────────────────────────────────────────────┘
              Validation (compare counts)
```

## Prerequisites

- Docker and Docker Compose installed
- At least 4GB RAM available for containers
- Ports 3306, 8123, 9000 available

## Quick Start

### Run Complete Test Suite

```bash
cd sink-connector-lightweight/tests/e2e-integration
docker-compose up --abort-on-container-exit
```

### View Results

The test runner will output colored results:
- 🟢 Green: Tests passed
- 🔴 Red: Tests failed
- 🟡 Yellow: Info/progress

### Check Logs

```bash
# View all logs
docker-compose logs

# View specific service
docker-compose logs sink-connector
docker-compose logs test-runner

# Follow logs
docker-compose logs -f sink-connector
```

### Clean Up

```bash
# Stop and remove containers
docker-compose down

# Remove volumes (full cleanup)
docker-compose down -v
```

## Manual Testing

### Run Tests Step-by-Step

```bash
# 1. Start services
docker-compose up -d mysql clickhouse sink-connector

# 2. Wait for services to be ready
docker-compose logs -f sink-connector

# 3. Execute test scenarios manually
docker exec -i e2e-mysql mysql -uroot -proot testdb < test-scenarios.sql

# 4. Wait for replication (30-60 seconds)
sleep 45

# 5. Run validation
docker exec e2e-test-runner bash /validate-results.sh
```

### Connect to Services

```bash
# Connect to MySQL
docker exec -it e2e-mysql mysql -uroot -proot testdb

# Connect to ClickHouse
docker exec -it e2e-clickhouse clickhouse-client

# Check connector logs
docker exec e2e-sink-connector cat /logs/connector.log
```

### Query Data

```bash
# Check MySQL tables
docker exec e2e-mysql mysql -uroot -proot -e "SHOW TABLES FROM testdb"

# Check ClickHouse tables
docker exec e2e-clickhouse clickhouse-client --query "SHOW TABLES FROM testdb"

# Compare row counts
docker exec e2e-mysql mysql -uroot -proot -sN -e "SELECT COUNT(*) FROM testdb.test_insert"
docker exec e2e-clickhouse clickhouse-client --query "SELECT COUNT(*) FROM testdb.test_insert"
```

## Validation Criteria

### Row Count Validation
Every table is validated to ensure:
```
MySQL row count == ClickHouse row count
```

### Data Integrity Validation
- NULL values preserved correctly
- Transaction boundaries respected (COMMIT/ROLLBACK)
- Update/Delete operations reflected accurately
- Schema changes applied correctly

### Performance Metrics
- Replication lag: < 10 seconds for 1000 rows
- Throughput: > 1000 rows/second
- Memory usage: < 512MB per connector instance

## Troubleshooting

### Tests Fail with "Connection Refused"

**Cause**: Services not ready yet

**Solution**:
```bash
# Wait longer for services
docker-compose up -d
sleep 30
docker-compose up test-runner
```

### Tests Fail with "Table Not Found"

**Cause**: Replication lag or connector not running

**Solution**:
```bash
# Check connector status
docker-compose ps
docker-compose logs sink-connector

# Increase replication wait time in run-tests.sh
# Edit REPLICATION_WAIT_TIME variable
```

### Row Count Mismatches

**Cause**: Transaction not committed or replication lag

**Solution**:
```bash
# Check MySQL binlog position
docker exec e2e-mysql mysql -uroot -proot -e "SHOW MASTER STATUS"

# Check connector offset
docker-compose logs sink-connector | grep -i offset

# Verify transaction commits
docker exec e2e-mysql mysql -uroot -proot testdb -e "SELECT * FROM test_tx_commit"
```

### Out of Memory Errors

**Cause**: Insufficient Docker resources

**Solution**:
```bash
# Increase Docker memory limit
# Docker Desktop: Settings → Resources → Memory → 4GB+

# Or reduce batch sizes in config.yml
batch_size: 500
buffer_count: 5000
```

## Test Customization

### Add New Test Scenarios

Edit [`test-scenarios.sql`](test-scenarios.sql):

```sql
-- Test 28: Your new test
DROP TABLE IF EXISTS test_your_feature;
CREATE TABLE test_your_feature (id INT PRIMARY KEY, data VARCHAR(100));
INSERT INTO test_your_feature VALUES (1, 'test_data');
```

Add validation in [`validate-results.sh`](validate-results.sh):

```bash
compare_counts "test_your_feature" "Test 28: Your feature"
```

### Modify Configuration

Edit [`config.yml`](config.yml) to test different settings:

```yaml
sink:
  batch_size: 5000  # Increase batch size
  thread_pool_size: 8  # More threads
  enable_transaction_support: false  # Disable transactions
```

### Change Wait Time

Edit [`run-tests.sh`](run-tests.sh):

```bash
# Increase if replication is slow
REPLICATION_WAIT_TIME=60
```

## Continuous Integration

### GitHub Actions

```yaml
name: E2E Tests
on: [push]
jobs:
  e2e-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Run E2E Tests
        run: |
          cd sink-connector-lightweight/tests/e2e-integration
          docker-compose up --abort-on-container-exit
          EXIT_CODE=$?
          docker-compose logs
          docker-compose down -v
          exit $EXIT_CODE
```

### Jenkins Pipeline

```groovy
pipeline {
    agent any
    stages {
        stage('E2E Test') {
            steps {
                dir('sink-connector-lightweight/tests/e2e-integration') {
                    sh 'docker-compose up --abort-on-container-exit'
                    sh 'docker-compose down -v'
                }
            }
        }
    }
}
```

## Performance Testing

### Load Test (1 Million Rows)

```bash
# Generate large dataset
docker exec e2e-mysql mysql -uroot -proot testdb << 'EOF'
DROP PROCEDURE IF EXISTS generate_data;
DELIMITER //
CREATE PROCEDURE generate_data()
BEGIN
    DECLARE i INT DEFAULT 0;
    CREATE TABLE IF NOT EXISTS test_perf (
        id INT PRIMARY KEY AUTO_INCREMENT,
        data VARCHAR(100),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    WHILE i < 1000000 DO
        INSERT INTO test_perf (data) VALUES (CONCAT('data_', i));
        SET i = i + 1;
        IF i % 10000 = 0 THEN
            COMMIT;
        END IF;
    END WHILE;
END//
DELIMITER ;
CALL generate_data();
EOF

# Monitor replication
watch -n 1 'docker exec e2e-clickhouse clickhouse-client --query "SELECT COUNT(*) FROM testdb.test_perf"'
```

## See Also

- [Build and Test Guide](../../../BUILD-AND-TEST.md)
- [Configuration Reference](../../../CONFIGURATION-REFERENCE.md)
- [Production Deployment Guide](../../../PRODUCTION-DEPLOYMENT-GUIDE.md)
- [Transaction Support Documentation](../../../sink-connector/doc/TRANSACTION-SUPPORT.md)
