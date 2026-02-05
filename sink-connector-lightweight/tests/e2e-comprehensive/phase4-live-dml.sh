#!/bin/bash
set -e

echo "Phase 4: Execute Live DML Operations"

# Give connector a moment to stabilize
sleep 5

echo "Step 4.1: Executing live DML operations..."
echo "  This includes INSERTs, UPDATEs, DELETEs, and DDL changes"

# Execute the DML test script with utf8mb4 charset for emoji support
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" --default-character-set=utf8mb4 < /scripts/phase4-live-dml.sql

if [ $? -eq 0 ]; then
    echo "✅ Live DML operations executed successfully"
else
    echo "❌ Live DML operations failed"
    exit 1
fi

# Wait for CDC to catch up
echo "Step 4.2: Waiting for CDC to replicate changes..."
REPLICATION_WAIT=30
for i in $(seq 1 $REPLICATION_WAIT); do
    echo -ne "  Progress: [$i/$REPLICATION_WAIT]\r"
    sleep 1
done
echo ""

# Count changes made
MYSQL_COUNT=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DATABASE" -sN -e "
    SELECT 
        (SELECT COUNT(*) FROM customers WHERE customer_id > 100000) as new_customers,
        (SELECT COUNT(*) FROM orders WHERE order_id > 100000) as new_orders,
        (SELECT COUNT(*) FROM products WHERE product_id > 100000) as new_products
")

echo "  Changes detected in MySQL: $MYSQL_COUNT"

# Save phase metrics
cat > /reports/phase4-metrics.txt << EOF
Phase 4: Live DML Metrics
==========================
DML Script: phase4-live-dml.sql
Replication Wait: ${REPLICATION_WAIT}s
Changes Made: $MYSQL_COUNT
Status: SUCCESS
EOF

echo "✅ Phase 4: Live DML operations completed successfully"
exit 0
