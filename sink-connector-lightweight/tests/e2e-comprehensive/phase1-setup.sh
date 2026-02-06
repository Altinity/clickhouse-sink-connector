#!/bin/bash
set -e

echo "Phase 1: Setup - Verify MySQL and ClickHouse"

# Wait for MySQL
echo "Waiting for MySQL to be ready..."
for i in {1..30}; do
    if mysql -h mysql -P 3306 -u root -proot_password -e "SELECT 1" > /dev/null 2>&1; then
        echo "✅ MySQL is ready"
        break
    fi
    echo "  Waiting for MySQL... ($i/30)"
    sleep 2
done

# Wait for ClickHouse
echo "Waiting for ClickHouse to be ready..."
for i in {1..30}; do
    if clickhouse-client --host "$CLICKHOUSE_HOST" --port 9000 --query "SELECT 1" > /dev/null 2>&1; then
        echo "✅ ClickHouse is ready"
        break
    fi
    echo "  Waiting for ClickHouse... ($i/30)"
    sleep 2
done

# Verify test data loaded in MySQL
echo "Verifying test data in MySQL..."
TABLE_COUNT=$(mysql -h mysql -P 3306 -u root -proot_password -D "$MYSQL_DATABASE" -sN -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$MYSQL_DATABASE'")
echo "  Tables found: $TABLE_COUNT"

ROW_COUNT=$(mysql -h mysql -P 3306 -u root -proot_password -D "$MYSQL_DATABASE" -sN -e "SELECT SUM(table_rows) FROM information_schema.tables WHERE table_schema = '$MYSQL_DATABASE'")
echo "  Approximate rows: $ROW_COUNT"

if [ "$TABLE_COUNT" -gt 0 ]; then
    echo "✅ MySQL test data loaded successfully"
else
    echo "❌ No test data found in MySQL"
    exit 1
fi

echo "✅ Phase 1: Setup completed successfully"
exit 0
