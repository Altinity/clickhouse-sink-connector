#!/bin/bash

################################################################################
# Detailed Column Diagnostic Script
# 
# Purpose: Deep dive into a specific column to understand why checksums mismatch
#
# Usage: ./column_detail_diagnostic.sh <column_name>
################################################################################

set -e

if [ $# -eq 0 ]; then
    echo "Usage: $0 <column_name>"
    echo "Example: $0 loyalty_points"
    exit 1
fi

COLUMN=$1

# Configuration
MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-test_db}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"

CLICKHOUSE_HOST="${CLICKHOUSE_HOST:-localhost}"
CLICKHOUSE_PORT="${CLICKHOUSE_PORT:-9000}"
CLICKHOUSE_DATABASE="${CLICKHOUSE_DATABASE:-test_db}"
CLICKHOUSE_USER="${CLICKHOUSE_USER:-default}"
CLICKHOUSE_PASSWORD="${CLICKHOUSE_PASSWORD:-}"

TABLE="customers"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo "================================================================"
echo "Detailed Diagnostic for Column: $COLUMN"
echo "================================================================"
echo ""

# Check 1: Data type comparison
echo -e "${BLUE}Check 1: Data Type Comparison${NC}"
echo "----------------------------------------------------------------"

echo "MySQL data type:"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -t -e "
SELECT 
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE,
    COLUMN_DEFAULT,
    EXTRA
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = '$MYSQL_DATABASE' 
  AND TABLE_NAME = '$TABLE'
  AND COLUMN_NAME = '$COLUMN'
"

echo ""
echo "ClickHouse data type:"
clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
SELECT 
    name,
    type,
    default_kind,
    default_expression,
    if(match(type,'Nullable'),1,0) as is_nullable
FROM system.columns
WHERE database = '$CLICKHOUSE_DATABASE' 
  AND table = '$TABLE'
  AND name = '$COLUMN'
FORMAT Vertical
"

echo ""

# Check 2: NULL value statistics
echo -e "${BLUE}Check 2: NULL Value Statistics${NC}"
echo "----------------------------------------------------------------"

echo "MySQL NULL analysis:"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -t -e "
SELECT 
    COUNT(*) as total_rows,
    SUM(CASE WHEN \`$COLUMN\` IS NULL THEN 1 ELSE 0 END) as null_count,
    SUM(CASE WHEN \`$COLUMN\` IS NOT NULL THEN 1 ELSE 0 END) as not_null_count,
    ROUND(SUM(CASE WHEN \`$COLUMN\` IS NULL THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as null_percentage
FROM $TABLE
"

echo ""
echo "ClickHouse NULL analysis:"
clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
SELECT 
    COUNT(*) as total_rows,
    SUM(CASE WHEN \`$COLUMN\` IS NULL THEN 1 ELSE 0 END) as null_count,
    SUM(CASE WHEN \`$COLUMN\` IS NOT NULL THEN 1 ELSE 0 END) as not_null_count,
    ROUND(SUM(CASE WHEN \`$COLUMN\` IS NULL THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as null_percentage
FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
WHERE is_deleted = 0 OR is_deleted IS NULL
FORMAT Vertical
"

echo ""

# Check 3: Value distribution
echo -e "${BLUE}Check 3: Value Distribution (Top 10 values)${NC}"
echo "----------------------------------------------------------------"

echo "MySQL value distribution:"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -t -e "
SELECT 
    \`$COLUMN\` as value,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM $TABLE), 2) as percentage
FROM $TABLE
GROUP BY \`$COLUMN\`
ORDER BY count DESC
LIMIT 10
"

echo ""
echo "ClickHouse value distribution:"
clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
SELECT 
    \`$COLUMN\` as value,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM $CLICKHOUSE_DATABASE.$TABLE FINAL WHERE is_deleted = 0 OR is_deleted IS NULL), 2) as percentage
FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
WHERE is_deleted = 0 OR is_deleted IS NULL
GROUP BY \`$COLUMN\`
ORDER BY count DESC
LIMIT 10
FORMAT PrettyCompact
"

echo ""

# Check 4: Sample values comparison
echo -e "${BLUE}Check 4: Sample Values Comparison (First 20 rows by customer_id)${NC}"
echo "----------------------------------------------------------------"

echo "MySQL sample values:"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -t -e "
SELECT 
    customer_id,
    \`$COLUMN\` as value,
    CASE 
        WHEN \`$COLUMN\` IS NULL THEN 'NULL'
        ELSE CAST(\`$COLUMN\` AS CHAR)
    END as value_str,
    LENGTH(CAST(\`$COLUMN\` AS CHAR)) as str_length
FROM $TABLE
ORDER BY customer_id
LIMIT 20
"

echo ""
echo "ClickHouse sample values:"
clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
SELECT 
    customer_id,
    \`$COLUMN\` as value,
    CASE 
        WHEN \`$COLUMN\` IS NULL THEN 'NULL'
        ELSE toString(\`$COLUMN\`)
    END as value_str,
    length(toString(\`$COLUMN\`)) as str_length
FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
WHERE is_deleted = 0 OR is_deleted IS NULL
ORDER BY customer_id
LIMIT 20
FORMAT PrettyCompact
"

echo ""

# Check 5: Specific problematic rows (if any)
echo -e "${BLUE}Check 5: Rows with Potential Issues${NC}"
echo "----------------------------------------------------------------"

echo "MySQL - rows where value differs from expected:"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -t -e "
SELECT 
    customer_id,
    \`$COLUMN\` as value
FROM $TABLE
WHERE \`$COLUMN\` IS NULL 
   OR \`$COLUMN\` = 0
ORDER BY customer_id
LIMIT 20
"

echo ""
echo "ClickHouse - rows where value differs from expected:"
clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
SELECT 
    customer_id,
    \`$COLUMN\` as value
FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
WHERE (is_deleted = 0 OR is_deleted IS NULL)
  AND (\`$COLUMN\` IS NULL OR \`$COLUMN\` = 0)
ORDER BY customer_id
LIMIT 20
FORMAT PrettyCompact
"

echo ""

# Check 6: Encoding/representation check (for string columns)
echo -e "${BLUE}Check 6: String Encoding Check (if applicable)${NC}"
echo "----------------------------------------------------------------"

echo "MySQL - sample with HEX encoding:"
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -t -e "
SELECT 
    customer_id,
    \`$COLUMN\` as value,
    HEX(\`$COLUMN\`) as hex_value,
    CHAR_LENGTH(\`$COLUMN\`) as char_length,
    LENGTH(\`$COLUMN\`) as byte_length
FROM $TABLE
WHERE \`$COLUMN\` IS NOT NULL
ORDER BY customer_id
LIMIT 10
"

echo ""
echo "ClickHouse - sample with HEX encoding:"
clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
SELECT 
    customer_id,
    \`$COLUMN\` as value,
    hex(\`$COLUMN\`) as hex_value,
    length(\`$COLUMN\`) as char_length
FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
WHERE (is_deleted = 0 OR is_deleted IS NULL)
  AND \`$COLUMN\` IS NOT NULL
ORDER BY customer_id
LIMIT 10
FORMAT PrettyCompact
"

echo ""

# Summary
echo "================================================================"
echo -e "${YELLOW}Diagnostic Summary for Column: $COLUMN${NC}"
echo "================================================================"
echo ""
echo "Review the output above to identify:"
echo "  1. Data type differences (nullable, default values)"
echo "  2. NULL count discrepancies"
echo "  3. Value distribution differences"
echo "  4. Encoding or precision issues"
echo ""
echo "Common issues to look for:"
echo "  • MySQL has 0, ClickHouse has NULL (or vice versa)"
echo "  • Different default value handling"
echo "  • Timestamp/datetime precision differences"
echo "  • String encoding differences (UTF8, trailing spaces)"
echo "  • Decimal precision differences"
echo ""
echo "================================================================"
