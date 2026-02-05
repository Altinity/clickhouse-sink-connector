#!/bin/bash
set -e

echo "Phase 2: Initial Snapshot using mysqlsh"

DUMP_DIR="/dumps/mysql-snapshot"
MYSQL_SOURCE_DB="${MYSQL_DATABASE}"

# Clean previous dumps
echo "Cleaning previous dump directory..."
rm -rf "$DUMP_DIR"
mkdir -p "$DUMP_DIR"

# Drop and recreate ClickHouse database to ensure clean state
echo "Resetting ClickHouse database..."
clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "DROP DATABASE IF EXISTS ${CLICKHOUSE_DATABASE}"
clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "CREATE DATABASE IF NOT EXISTS ${CLICKHOUSE_DATABASE}"
echo "✅ ClickHouse database reset complete"

# Step 1: Dump MySQL using mysqlsh
echo "Step 2.1: Dumping MySQL database using mysqlsh..."
echo "  Source: ${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_SOURCE_DB}"
echo "  Destination: ${DUMP_DIR}"

cd /app/python

python db_dump/mysql_dumper.py \
    --mysql_host "$MYSQL_HOST" \
    --mysql_user "$MYSQL_USER" \
    --mysql_password "$MYSQL_PASSWORD" \
    --mysql_port "$MYSQL_PORT" \
    --mysql_database "$MYSQL_SOURCE_DB" \
    --dump_dir "$DUMP_DIR" \
    --include_tables_regex ".*" \
    --threads 4 \
    --bytes_per_chunk "64M"

if [ $? -eq 0 ]; then
    echo "✅ MySQL dump completed successfully"
else
    echo "❌ MySQL dump failed"
    exit 1
fi

# Verify dump files
DUMP_FILE_COUNT=$(find "$DUMP_DIR" -type f | wc -l)
echo "  Dump files created: $DUMP_FILE_COUNT"

if [ "$DUMP_FILE_COUNT" -eq 0 ]; then
    echo "❌ No dump files created"
    exit 1
fi

# Step 2: Load into ClickHouse
echo "Step 2.2: Loading dump into ClickHouse..."
echo "  Target: ${CLICKHOUSE_HOST}:${CLICKHOUSE_PORT}/${CLICKHOUSE_DATABASE}"

# Create clickhouse-client config
cat > /app/clickhouse-client.xml << EOF
<clickhouse>
    <host>${CLICKHOUSE_HOST}</host>
    <port>${CLICKHOUSE_PORT}</port>
    <user>${CLICKHOUSE_USER}</user>
    <password>${CLICKHOUSE_PASSWORD}</password>
</clickhouse>
EOF

python db_load/clickhouse_loader.py \
    --clickhouse_host "$CLICKHOUSE_HOST" \
    --clickhouse_port "$CLICKHOUSE_PORT" \
    --clickhouse_user "$CLICKHOUSE_USER" \
    --clickhouse_password "$CLICKHOUSE_PASSWORD" \
    --clickhouse_database "$CLICKHOUSE_DATABASE" \
    --clickhouse_config_file "/app/clickhouse-client.xml" \
    --mysql_source_database "$MYSQL_SOURCE_DB" \
    --dump_dir "$DUMP_DIR" \
    --threads 4 \
    --mysqlshell \
    --rmt_delete_support

if [ $? -eq 0 ]; then
    echo "✅ ClickHouse load completed successfully"
else
    echo "❌ ClickHouse load failed"
    exit 1
fi

# Step 3: Verify data loaded
echo "Step 2.3: Verifying data loaded into ClickHouse..."

CH_TABLE_COUNT=$(clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT COUNT(*) FROM system.tables WHERE database = '$CLICKHOUSE_DATABASE'")
echo "  Tables created: $CH_TABLE_COUNT"

CH_ROW_COUNT=$(clickhouse-client --host "$CLICKHOUSE_HOST" --port "$CLICKHOUSE_PORT" --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT SUM(total_rows) FROM system.tables WHERE database = '$CLICKHOUSE_DATABASE'")
echo "  Total rows loaded: $CH_ROW_COUNT"

if [ "$CH_ROW_COUNT" -gt 0 ]; then
    echo "✅ Data verification passed"
else
    echo "❌ No data found in ClickHouse"
    exit 1
fi

# Save snapshot metrics
cat > /reports/phase2-metrics.txt << EOF
Phase 2: Initial Snapshot Metrics
==================================
Dump Directory: ${DUMP_DIR}
Dump Files: ${DUMP_FILE_COUNT}
Tables Created: ${CH_TABLE_COUNT}
Rows Loaded: ${CH_ROW_COUNT}
Status: SUCCESS
EOF

echo "✅ Phase 2: Initial Snapshot completed successfully"
exit 0
