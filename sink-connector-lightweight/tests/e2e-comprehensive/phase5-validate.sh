#!/bin/bash
set -e

echo "Phase 5: Data Validation & Checksum Verification"

cd /app/python

# Configuration
VALIDATION_REPORT="/reports/phase5-validation.txt"
CHECKSUM_REPORT="/reports/phase5-checksums.txt"

# Initialize reports
echo "Phase 5: Validation Results" > "$VALIDATION_REPORT"
echo "=============================" >> "$VALIDATION_REPORT"
echo "" >> "$VALIDATION_REPORT"

echo "Checksum Validation" > "$CHECKSUM_REPORT"
echo "===================" >> "$CHECKSUM_REPORT"
echo "" >> "$CHECKSUM_REPORT"

echo "======================================"
echo "CDC Connector Status Check"
echo "======================================"
echo ""

# Check if connector is still processing events
echo "Checking connector activity..." | tee -a "$VALIDATION_REPORT"
CONNECTOR_LOGS=$(docker logs e2e-comp-connector 2>&1 | tail -50 || echo "Unable to access connector logs")
echo "$CONNECTOR_LOGS" | grep -i "processing\|emitted\|records sent\|inserted" | tail -5 || echo "  No recent processing activity found"

# Check binlog position
echo ""
echo "Current binlog position:" | tee -a "$VALIDATION_REPORT"
echo "$CONNECTOR_LOGS" | grep -i "binlog" | tail -3 || echo "  No binlog position info found"
echo ""

# Give ClickHouse time to finish any pending async inserts
echo "⏳ Waiting 10s for ClickHouse to flush pending inserts..."
sleep 10

echo "======================================"
echo "Step 1: Row Count Validation with Retry"
echo "======================================"
echo ""

# Get list of tables
TABLES=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" -sN -e "
    SELECT table_name 
    FROM information_schema.tables 
    WHERE table_schema = '$MYSQL_DATABASE' 
    AND table_type = 'BASE TABLE'
    ORDER BY table_name
")

# Exponential backoff configuration
MAX_RETRIES=5
INITIAL_WAIT=5
MAX_WAIT=60

TOTAL_TABLES=0
MATCHED_TABLES=0
MISMATCHED_TABLES=0

echo "Table Counts:" >> "$VALIDATION_REPORT"
echo "-------------" >> "$VALIDATION_REPORT"

for TABLE in $TABLES; do
    TOTAL_TABLES=$((TOTAL_TABLES + 1))
    
    echo "Validating table: $TABLE"
    
    retry_count=0
    wait_time=$INITIAL_WAIT
    match_found=false
    
    while [ $retry_count -lt $MAX_RETRIES ]; do
        # Get MySQL count
        MYSQL_COUNT=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" -sN -e "SELECT COUNT(*) FROM \`$TABLE\`")
        
        # Get ClickHouse count (using FINAL to get deduplicated count)
        CH_COUNT=$(clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT COUNT(*) FROM ${CLICKHOUSE_DATABASE}.\`$TABLE\` FINAL WHERE is_deleted = 0 OR is_deleted IS NULL")
        
        if [ "$MYSQL_COUNT" -eq "$CH_COUNT" ]; then
            echo "  ✅ MySQL: $MYSQL_COUNT, ClickHouse: $CH_COUNT - MATCH" | tee -a "$VALIDATION_REPORT"
            match_found=true
            MATCHED_TABLES=$((MATCHED_TABLES + 1))
            break
        else
            if [ $retry_count -lt $((MAX_RETRIES - 1)) ]; then
                echo "  ⏳ MySQL: $MYSQL_COUNT, ClickHouse: $CH_COUNT - MISMATCH (retry $((retry_count + 1))/$MAX_RETRIES)" | tee -a "$VALIDATION_REPORT"
                echo "     Waiting ${wait_time}s for CDC replication to catch up..."
                sleep $wait_time
                
                # Exponential backoff: double wait time, cap at MAX_WAIT
                wait_time=$((wait_time * 2))
                if [ $wait_time -gt $MAX_WAIT ]; then
                    wait_time=$MAX_WAIT
                fi
                
                retry_count=$((retry_count + 1))
            else
                echo "  ❌ MySQL: $MYSQL_COUNT, ClickHouse: $CH_COUNT - FAILED after $MAX_RETRIES retries" | tee -a "$VALIDATION_REPORT"
                match_found=false
                MISMATCHED_TABLES=$((MISMATCHED_TABLES + 1))
                
                # Run diagnostics for failed table
                echo ""
                echo "======================================"
                echo "Diagnostic: Finding Missing Rows in $TABLE"
                echo "======================================"
                echo ""
                
                # Table-specific diagnostics
                case "$TABLE" in
                    customers)
                        echo "MySQL customer IDs created in Phase 4 (last 20):" | tee -a "$VALIDATION_REPORT"
                        mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" -e "SELECT customer_id, first_name, last_name FROM customers WHERE customer_id > 10000 ORDER BY customer_id DESC LIMIT 20" | tee -a "$VALIDATION_REPORT"
                        
                        echo "" | tee -a "$VALIDATION_REPORT"
                        echo "ClickHouse customer IDs (last 20):" | tee -a "$VALIDATION_REPORT"
                        clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT customer_id, first_name, last_name FROM ${CLICKHOUSE_DATABASE}.customers FINAL WHERE customer_id > 10000 AND (is_deleted = 0 OR is_deleted IS NULL) ORDER BY customer_id DESC LIMIT 20 FORMAT Pretty" | tee -a "$VALIDATION_REPORT"
                        ;;
                    data_types_test)
                        echo "MySQL data_types_test IDs (last 20):" | tee -a "$VALIDATION_REPORT"
                        mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" -e "SELECT id FROM data_types_test ORDER BY id DESC LIMIT 20" | tee -a "$VALIDATION_REPORT"
                        
                        echo "" | tee -a "$VALIDATION_REPORT"
                        echo "ClickHouse data_types_test IDs (last 20):" | tee -a "$VALIDATION_REPORT"
                        clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT id FROM ${CLICKHOUSE_DATABASE}.data_types_test FINAL WHERE (is_deleted = 0 OR is_deleted IS NULL) ORDER BY id DESC LIMIT 20 FORMAT Pretty" | tee -a "$VALIDATION_REPORT"
                        ;;
                    *)
                        echo "MySQL row sample (first 5):" | tee -a "$VALIDATION_REPORT"
                        mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" -e "SELECT * FROM \`$TABLE\` LIMIT 5" | tee -a "$VALIDATION_REPORT"
                        
                        echo "" | tee -a "$VALIDATION_REPORT"
                        echo "ClickHouse row sample (first 5):" | tee -a "$VALIDATION_REPORT"
                        clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT * FROM ${CLICKHOUSE_DATABASE}.\`$TABLE\` FINAL WHERE (is_deleted = 0 OR is_deleted IS NULL) LIMIT 5 FORMAT Pretty" | tee -a "$VALIDATION_REPORT"
                        ;;
                esac
                
                echo "" | tee -a "$VALIDATION_REPORT"
                echo "Checking connector offset storage..." | tee -a "$VALIDATION_REPORT"
                docker exec e2e-comp-connector cat /tmp/e2e-comp-offsets.dat 2>/dev/null | tee -a "$VALIDATION_REPORT" || echo "  Offset file not found or inaccessible" | tee -a "$VALIDATION_REPORT"
                
                echo "" | tee -a "$VALIDATION_REPORT"
                echo "Recent connector logs (last 50 lines):" | tee -a "$VALIDATION_REPORT"
                docker logs e2e-comp-connector 2>&1 | tail -50 | tee -a "$VALIDATION_REPORT"
                
                echo ""
                echo "❌ CRITICAL: Row count mismatch for $TABLE after all retries" | tee -a "$VALIDATION_REPORT"
                echo "   This indicates potential data loss - connector should NEVER lose data" | tee -a "$VALIDATION_REPORT"
                echo ""
                
                break
            fi
        fi
    done
    
    if [ "$match_found" = false ]; then
        # Already incremented MISMATCHED_TABLES above
        :
    fi
    echo ""
done

echo "" >> "$VALIDATION_REPORT"
echo "Row Count Summary:" >> "$VALIDATION_REPORT"
echo "  Total Tables: $TOTAL_TABLES" >> "$VALIDATION_REPORT"
echo "  Matched: $MATCHED_TABLES" >> "$VALIDATION_REPORT"
echo "  Mismatched: $MISMATCHED_TABLES" >> "$VALIDATION_REPORT"
echo "" >> "$VALIDATION_REPORT"

if [ "$MISMATCHED_TABLES" -eq 0 ]; then
    echo "✅ All row counts match - No data loss detected"
    echo ""
else
    echo "❌ Row count validation FAILED - Exiting before checksum validation"
    echo ""
    cat "$VALIDATION_REPORT"
    exit 1
fi

echo "======================================"
echo "Step 2: Checksum Validation with Retry"
echo "======================================"
echo ""

# Give ClickHouse additional time to finish any pending inserts before checksums
echo "⏳ Waiting 10s for ClickHouse to complete all pending operations..."
sleep 10

# Create MySQL credentials file
cat > /root/.my.cnf << EOF
[client]
user=${MYSQL_USER}
password=${MYSQL_PASSWORD}
host=${MYSQL_HOST}
port=${MYSQL_PORT}
EOF

# Sample tables for checksum (can be expanded to all tables)
KEY_TABLES=("customers" "orders" "products")

CHECKSUM_PASS=0
CHECKSUM_FAIL=0

# Checksum retry configuration
CHECKSUM_MAX_RETRIES=3
CHECKSUM_WAIT=10

for TABLE in "${KEY_TABLES[@]}"; do
    echo "Computing checksum for $TABLE..."
    
    checksum_retry_count=0
    checksum_match=false
    
    while [ $checksum_retry_count -lt $CHECKSUM_MAX_RETRIES ]; do
        # MySQL checksum - use regex matching without --no_wc flag
        # Note: loyalty_points column has been backfilled, no longer excluded
        MYSQL_CHECKSUM=$(python db_compare/mysql_table_checksum.py \
            --mysql_host "$MYSQL_HOST" \
            --mysql_port "$MYSQL_PORT" \
            --mysql_database "$MYSQL_DATABASE" \
            --tables_regex "^${TABLE}\$" \
            --threads 1 \
            --threads_per_table 1 \
            --defaults_file /root/.my.cnf 2>&1 | grep "Checksum for table" | awk '{print $13}' || echo "error")
        
        # ClickHouse checksum - use regex matching without --no_wc flag
        # Note: loyalty_points column has been backfilled, no longer excluded
        CH_CHECKSUM=$(python db_compare/clickhouse_table_checksum.py \
            --clickhouse_host "$CLICKHOUSE_HOST" \
            --clickhouse_port "$CLICKHOUSE_PORT" \
            --clickhouse_user "$CLICKHOUSE_USER" \
            --clickhouse_password "$CLICKHOUSE_PASSWORD" \
            --clickhouse_database "$CLICKHOUSE_DATABASE" \
            --tables_regex "^${TABLE}\$" \
            --threads 1 \
            --sign_column "is_deleted" 2>&1 | grep "Checksum for table" | awk '{print $13}' || echo "error")
        
        # Compare checksums
        if [ "$MYSQL_CHECKSUM" = "$CH_CHECKSUM" ] && [ "$MYSQL_CHECKSUM" != "error" ]; then
            echo "  ✅ $TABLE: MATCH (checksum: $MYSQL_CHECKSUM)" | tee -a "$CHECKSUM_REPORT"
            checksum_match=true
            CHECKSUM_PASS=$((CHECKSUM_PASS + 1))
            break
        else
            if [ $checksum_retry_count -lt $((CHECKSUM_MAX_RETRIES - 1)) ]; then
                echo "  ⏳ $TABLE: MISMATCH (MySQL: $MYSQL_CHECKSUM, CH: $CH_CHECKSUM) - retry $((checksum_retry_count + 1))/$CHECKSUM_MAX_RETRIES" | tee -a "$CHECKSUM_REPORT"
                echo "     Waiting ${CHECKSUM_WAIT}s for ClickHouse to stabilize..."
                sleep $CHECKSUM_WAIT
                checksum_retry_count=$((checksum_retry_count + 1))
            else
                echo "  ❌ $TABLE: MISMATCH after $CHECKSUM_MAX_RETRIES retries (MySQL: $MYSQL_CHECKSUM, CH: $CH_CHECKSUM)" | tee -a "$CHECKSUM_REPORT"
                checksum_match=false
                CHECKSUM_FAIL=$((CHECKSUM_FAIL + 1))
                break
            fi
        fi
    done
    
    if [ "$checksum_match" = false ]; then
        # Already incremented CHECKSUM_FAIL above
        :
    fi
done

echo "" >> "$CHECKSUM_REPORT"
echo "Checksum Summary:" >> "$CHECKSUM_REPORT"
echo "  Tables Validated: $((CHECKSUM_PASS + CHECKSUM_FAIL))" >> "$CHECKSUM_REPORT"
echo "  Matched: $CHECKSUM_PASS" >> "$CHECKSUM_REPORT"
echo "  Mismatched: $CHECKSUM_FAIL" >> "$CHECKSUM_REPORT"

# Step 3: Connection pool and connector health
echo ""
echo "======================================"
echo "Step 3: Connector Health Summary"
echo "======================================"
echo ""

CONNECTOR_HEALTHY="SKIPPED"
echo "  ⚠️  Connector health check skipped (requires host docker access)" | tee -a "$VALIDATION_REPORT"
echo "  Note: This test validates data loaded in Phase 2 and CDC events" | tee -a "$VALIDATION_REPORT"
echo ""

# Step 4: Final validation decision
echo "" >> "$VALIDATION_REPORT"
echo "Final Validation:" >> "$VALIDATION_REPORT"
echo "-----------------" >> "$VALIDATION_REPORT"
echo "  Row Count Validation: $MATCHED_TABLES/$TOTAL_TABLES tables matched" >> "$VALIDATION_REPORT"
echo "  Checksum Validation: $CHECKSUM_PASS/$((CHECKSUM_PASS + CHECKSUM_FAIL)) tables matched" >> "$VALIDATION_REPORT"
echo "  Connector Health: $CONNECTOR_HEALTHY" >> "$VALIDATION_REPORT"
echo "" >> "$VALIDATION_REPORT"

# Determine overall status (connector health not required for snapshot validation)
if [ "$MISMATCHED_TABLES" -eq 0 ] && [ "$CHECKSUM_FAIL" -eq 0 ]; then
    echo "OVERALL: ✅ PASS - All validations successful" >> "$VALIDATION_REPORT"
    echo "======================================"
    echo "✅ Phase 5: Validation completed - ALL CHECKS PASSED"
    echo "======================================"
    echo ""
    echo "Summary:"
    echo "  - All $TOTAL_TABLES tables have 100% row count match"
    echo "  - All $CHECKSUM_PASS checksum validations passed"
    echo "  - Zero data loss detected"
    echo ""
    exit 0
else
    echo "OVERALL: ❌ FAIL - Some validations failed" >> "$VALIDATION_REPORT"
    echo "======================================"
    echo "❌ Phase 5: Validation completed - SOME CHECKS FAILED"
    echo "======================================"
    echo ""
    cat "$VALIDATION_REPORT"
    exit 1
fi
