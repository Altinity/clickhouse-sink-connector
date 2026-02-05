# Quick Start Guide - Comprehensive E2E Test

## One-Command Run

```bash
# From project root
cd sink-connector-lightweight/tests/e2e-comprehensive

# Setup and verify prerequisites
bash setup-test.sh

# Start containers
docker-compose -f docker-compose-comprehensive.yml up -d

# Run test (wait for containers to be healthy - about 30s)
docker exec -it e2e-comp-tools bash /scripts/run-comprehensive-test.sh

# View results
docker exec e2e-comp-tools cat /reports/test-report.txt
```

## Expected Output

```
================================================================================
        ClickHouse Sink Connector - Comprehensive E2E Test Report
================================================================================

Test Date: 2026-02-04 08:00:00
Duration: 5m 32s

--------------------------------------------------------------------------------
Phase Results:
--------------------------------------------------------------------------------

Phase 1: Setup                              PASS
Phase 2: Initial Snapshot (mysqlsh)         PASS
Phase 3: CDC Connector Startup              PASS
Phase 4: Live DML Operations                PASS
Phase 5: Data Validation & Checksum         PASS

--------------------------------------------------------------------------------
Test Summary:
--------------------------------------------------------------------------------

OVERALL STATUS: ✅ PRODUCTION READY

All phases completed successfully!
The ClickHouse Sink Connector has passed comprehensive validation.
```

## What Gets Tested

✅ **85,100+ rows** - Initial snapshot via mysqlsh  
✅ **100+ live DML ops** - INSERT, UPDATE, DELETE  
✅ **Transaction handling** - COMMIT/ROLLBACK  
✅ **DDL operations** - ALTER TABLE  
✅ **Data integrity** - MD5 checksum validation  
✅ **Edge cases** - NULLs, UTF-8, special characters  

## Test Duration

⏱️ **~5-7 minutes** total

## Cleanup

```bash
# Stop and remove everything
docker-compose -f docker-compose-comprehensive.yml down -v
```

## Need Help?

See [`README-COMPREHENSIVE-TEST.md`](README-COMPREHENSIVE-TEST.md) for detailed documentation.
