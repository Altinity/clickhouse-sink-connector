# ClickHouse Sink Connector - Comprehensive E2E Test

## Overview

This comprehensive end-to-end test validates the complete production workflow for the ClickHouse Sink Connector, including:

1. **Initial Data Snapshot** - Using `mysqlsh` for efficient MySQL dump
2. **Bulk Data Loading** - Loading snapshot into ClickHouse via Python scripts
3. **CDC Connector** - Continuous Change Data Capture for ongoing replication
4. **Data Validation** - Checksum verification ensuring MySQL ↔ ClickHouse integrity
5. **Production Scenarios** - Real-world DML, DDL, transactions, and edge cases

## Architecture

```
┌─────────────┐       ┌──────────────┐       ┌─────────────┐
│   MySQL     │──────▶│  mysqlsh     │──────▶│ ClickHouse  │
│  (Source)   │       │   dump       │       │   (Sink)    │
└─────────────┘       └──────────────┘       └─────────────┘
      │                                              ▲
      │                                              │
      │               ┌──────────────┐               │
      └──────────────▶│ CDC Connector│───────────────┘
                      │  (Debezium)  │
                      └──────────────┘
                             │
                      ┌──────────────┐
                      │  Validation  │
                      │  (Checksum)  │
                      └──────────────┘
```

## Test Phases

### Phase 1: Setup
- ✅ Start MySQL and ClickHouse containers
- ✅ Load 85,100 test records across 5 tables
- ✅ Verify infrastructure health

**Test Data:**
- 10,000 customers
- 5,000 products
- 20,000 orders
- 50,000 order items
- 100 data type edge cases

### Phase 2: Initial Snapshot (mysqlsh)
- ✅ Dump entire MySQL database using [`mysql_dumper.py`](../../../sink-connector/python/db_dump/mysql_dumper.py)
- ✅ Load data into ClickHouse using [`clickhouse_loader.py`](../../../sink-connector/python/db_load/clickhouse_loader.py)
- ✅ Verify 100% data loaded (row count validation)

**Technology:**
- MySQL Shell (`mysqlsh`) - Optimized for large datasets
- ZSTD compression for efficient transfer
- Multi-threaded dump and load (4 threads default)

### Phase 3: Start CDC Connector
- ✅ Start sink connector with `snapshot.mode=schema_only`
- ✅ Connector begins tailing MySQL binlog
- ✅ Schema already exists (created in Phase 2)

**Configuration:**
- Snapshot mode: `schema_only` (data already loaded)
- ReplacingMergeTree with `is_deleted` column
- Schema evolution enabled

### Phase 4: Live DML Operations
Executes production-grade operations:

**INSERT Operations:**
- 5 new customers
- 5 new products
- 5 new orders
- 5 new order items

**UPDATE Operations:**
- Customer profile updates
- Product price changes
- Order status transitions
- Bulk updates

**DELETE Operations:**
- Order item deletion
- Order cancellation
- Customer removal (with cascade)

**Transaction Tests:**
- COMMIT - Should replicate ✅
- ROLLBACK - Should NOT replicate ✅
- Multi-statement transactions

**DDL Operations:**
- ALTER TABLE ADD COLUMN
- ALTER TABLE ADD INDEX
- ALTER TABLE MODIFY COLUMN

**Edge Cases:**
- Duplicate updates (deduplication by RMT)
- Insert-Update-Delete sequences
- NULL value handling
- Special characters (UTF-8, emoji)

### Phase 5: Validation

**Row Count Validation:**
- Compare MySQL vs ClickHouse row counts
- Uses `FINAL` clause for deduplicated counts
- Filters deleted rows (`is_deleted = 0`)

**Checksum Validation:**
Uses MD5 checksums from:
- [`mysql_table_checksum.py`](../../../sink-connector/python/db_compare/mysql_table_checksum.py)
- [`clickhouse_table_checksum.py`](../../../sink-connector/python/db_compare/clickhouse_table_checksum.py)

**Health Checks:**
- Connector process running
- No errors in logs
- Connection pool stable

### Phase 6: Report Generation
Generates comprehensive test report with:
- ✅ Phase-by-phase status
- ✅ Row counts and checksums
- ✅ Duration metrics
- ✅ Production readiness verdict

## Quick Start

### Prerequisites

```bash
# Ensure Docker/Podman is installed
docker --version  # or podman --version

# Ensure you're in the project root
cd /home/minguyen/workspace/clickhouse-sink-connector
```

### Run the Test

```bash
# Navigate to test directory
cd sink-connector-lightweight/tests/e2e-comprehensive

# Make scripts executable
chmod +x *.sh

# Start the test
docker-compose -f docker-compose-comprehensive.yml up -d

# Run comprehensive test inside tools container
docker exec -it e2e-comp-tools bash /scripts/run-comprehensive-test.sh

# View results
docker exec e2e-comp-tools cat /reports/test-report.txt
```

### Alternative: Run Phases Individually

```bash
# Start infrastructure
docker-compose -f docker-compose-comprehensive.yml up -d

# Enter tools container
docker exec -it e2e-comp-tools bash

# Run phases manually
bash /scripts/phase1-setup.sh
bash /scripts/phase2-snapshot.sh
bash /scripts/phase3-cdc.sh
bash /scripts/phase4-live-dml.sh
bash /scripts/phase5-validate.sh
```

## Expected Test Duration

| Phase | Duration | Description |
|-------|----------|-------------|
| Phase 1 | ~30s | Infrastructure startup |
| Phase 2 | ~2-3min | mysqlsh dump + ClickHouse load (85K rows) |
| Phase 3 | ~15s | CDC connector startup |
| Phase 4 | ~30s | Live DML + replication wait |
| Phase 5 | ~1-2min | Checksum validation |
| **Total** | **~5-7min** | End-to-end test completion |

## Test Validation Criteria

✅ **PASS Criteria:**
- All 85,100+ rows loaded in Phase 2
- Row counts match 100% (MySQL == ClickHouse)
- Checksums match for all validated tables
- No connector errors or crashes
- All INSERT/UPDATE/DELETE operations replicated
- ROLLBACK transactions properly ignored
- DDL changes applied to ClickHouse

❌ **FAIL Criteria:**
- Row count mismatch > 0
- Checksum mismatch on any table
- Connector process terminated
- Missing or incomplete replication
- Data integrity violations

## File Structure

```
e2e-comprehensive/
├── README-COMPREHENSIVE-TEST.md          # This file
├── docker-compose-comprehensive.yml      # Container orchestration
├── Dockerfile.e2e-tools                  # Tools container (mysqlsh, Python)
├── run-comprehensive-test.sh             # Main orchestrator
├── phase1-setup.sh                       # Phase 1: Setup
├── phase2-snapshot.sh                    # Phase 2: mysqlsh dump/load
├── phase3-cdc.sh                         # Phase 3: Start CDC
├── phase4-live-dml.sh                    # Phase 4: DML executor
├── phase4-live-dml.sql                   # Phase 4: DML operations
├── phase5-validate.sh                    # Phase 5: Validation
├── test-data.sql                         # Initial test data (85K rows)
├── connector-config.yml                  # Connector configuration
├── mysql-credentials.cnf                 # MySQL credentials
├── clickhouse-client.xml                 # ClickHouse client config
└── init-clickhouse.sql                   # ClickHouse initialization
```

## Reports Generated

After test completion, view reports:

```bash
# Main test report
docker exec e2e-comp-tools cat /reports/test-report.txt

# Phase-specific metrics
docker exec e2e-comp-tools cat /reports/phase2-metrics.txt
docker exec e2e-comp-tools cat /reports/phase3-metrics.txt
docker exec e2e-comp-tools cat /reports/phase4-metrics.txt
docker exec e2e-comp-tools cat /reports/phase5-validation.txt
docker exec e2e-comp-tools cat /reports/phase5-checksums.txt

# Test logs
docker exec e2e-comp-tools cat /logs/comprehensive-test.log
```

## Cleanup

```bash
# Stop and remove all containers
docker-compose -f docker-compose-comprehensive.yml down -v

# Remove generated data
docker volume prune -f
```

## Troubleshooting

### Issue: Containers fail to start

```bash
# Check container status
docker-compose -f docker-compose-comprehensive.yml ps

# View logs
docker-compose -f docker-compose-comprehensive.yml logs mysql
docker-compose -f docker-compose-comprehensive.yml logs clickhouse
docker-compose -f docker-compose-comprehensive.yml logs e2e-tools
```

### Issue: Phase 2 fails (dump/load)

```bash
# Check if mysqlsh is installed
docker exec e2e-comp-tools which mysqlsh

# Check dump directory
docker exec e2e-comp-tools ls -la /dumps/mysql-snapshot/

# Verify Python scripts
docker exec e2e-comp-tools python --version
docker exec e2e-comp-tools ls -la /app/python/
```

### Issue: Phase 5 checksum mismatch

```bash
# Run checksum manually
docker exec e2e-comp-tools bash
cd /app/python

# MySQL checksum
python db_compare/mysql_table_checksum.py \
  --mysql_host mysql \
  --mysql_database e2e_testdb \
  --tables_regex "^customers$" \
  --no_wc \
  --defaults_file /root/.my.cnf

# ClickHouse checksum
python db_compare/clickhouse_table_checksum.py \
  --clickhouse_host clickhouse \
  --clickhouse_database e2e_testdb \
  --tables_regex "^customers$" \
  --no_wc \
  --clickhouse_user default \
  --clickhouse_password clickhouse_pass
```

### Issue: Connector not starting

```bash
# Check connector logs
docker exec e2e-comp-connector cat /logs/connector.log

# Verify connector JAR exists
docker exec e2e-comp-connector ls -la /app.jar

# Check connector process
docker exec e2e-comp-connector pgrep -f app.jar
```

## Customization

### Adjust Test Data Volume

Edit [`test-data.sql`](test-data.sql):

```sql
-- Change loop limit in generate_customers()
WHILE i <= 10000 DO  -- Change to 50000 for more data
```

### Add Custom Tables

1. Add table definition to [`test-data.sql`](test-data.sql)
2. Add DML operations to [`phase4-live-dml.sql`](phase4-live-dml.sql)
3. Update validation in [`phase5-validate.sh`](phase5-validate.sh)

### Modify Replication Wait Time

Edit [`phase4-live-dml.sh`](phase4-live-dml.sh):

```bash
REPLICATION_WAIT=30  # Change to 60 for larger datasets
```

## Integration with CI/CD

### GitHub Actions Example

```yaml
name: E2E Comprehensive Test

on: [push, pull_request]

jobs:
  e2e-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Run E2E Test
        run: |
          cd sink-connector-lightweight/tests/e2e-comprehensive
          chmod +x *.sh
          docker-compose -f docker-compose-comprehensive.yml up -d
          docker exec e2e-comp-tools bash /scripts/run-comprehensive-test.sh
      
      - name: Upload Test Report
        uses: actions/upload-artifact@v3
        with:
          name: test-report
          path: /reports/
```

## Production Deployment Validation

This test validates the connector is production-ready for:

✅ **High-Volume Initial Loads** - 85K+ rows via mysqlsh  
✅ **Continuous Replication** - CDC captures all changes  
✅ **Data Integrity** - MD5 checksum validation  
✅ **Transaction Support** - COMMIT/ROLLBACK handling  
✅ **Schema Evolution** - DDL changes replicated  
✅ **Edge Cases** - NULLs, special chars, deletes  
✅ **Deduplication** - ReplacingMergeTree handles duplicates  

## References

- [MySQL Shell Dump Utilities](https://dev.mysql.com/doc/mysql-shell/8.0/en/mysql-shell-utilities-dump-instance-schema.html)
- [ClickHouse ReplacingMergeTree](https://clickhouse.com/docs/en/engines/table-engines/mergetree-family/replacingmergetree)
- [Debezium MySQL Connector](https://debezium.io/documentation/reference/stable/connectors/mysql.html)

## Support

For issues or questions:
- Check existing tests: [`sink-connector-lightweight/tests/e2e-integration/`](../e2e-integration/)
- Review connector documentation: [`PRODUCTION-DEPLOYMENT-GUIDE.md`](../../../PRODUCTION-DEPLOYMENT-GUIDE.md)
- Check production readiness: [`PRODUCTION-READINESS-REPORT.md`](../../../PRODUCTION-READINESS-REPORT.md)

---

**Last Updated:** 2026-02-04  
**Test Version:** 1.0  
**Maintainer:** ClickHouse Sink Connector Team
