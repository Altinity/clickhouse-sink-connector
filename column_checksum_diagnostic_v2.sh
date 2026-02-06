#!/bin/bash

################################################################################
# Column-by-Column Checksum Diagnostic Script V2
# 
# Purpose: Identify which specific column(s) in the customers table are causing
#          checksum mismatches between MySQL and ClickHouse
################################################################################

set -e

# Configuration - use environment variables or defaults
MYSQL_HOST="${MYSQL_HOST:-mysql}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-e2e_testdb}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"

CLICKHOUSE_HOST="${CLICKHOUSE_HOST:-clickhouse}"
CLICKHOUSE_PORT="${CLICKHOUSE_PORT:-9000}"
CLICKHOUSE_DATABASE="${CLICKHOUSE_DATABASE:-e2e_testdb}"

TABLE="customers"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "================================================================"
echo "Column-by-Column Checksum Diagnostic for ${TABLE}"
echo "================================================================"
echo ""
echo "Database: $MYSQL_DATABASE"
echo "MySQL Host: $MYSQL_HOST"
echo "ClickHouse Host: $CLICKHOUSE_HOST"
echo ""

# Get list of columns from MySQL
COLUMNS=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -s -e "
SELECT COLUMN_NAME 
FROM information_schema.COLUMNS 
WHERE TABLE_SCHEMA = '$MYSQL_DATABASE' 
  AND TABLE_NAME = '$TABLE'
  AND COLUMN_NAME NOT IN ('_version', '_sign', 'is_deleted', '_is_deleted')
ORDER BY COLUMN_NAME
")

echo -e "${BLUE}Columns to analyze:${NC}"
echo "$COLUMNS"
echo ""

echo "================================================================"
printf "%-25s | %-10s | %-10s | %-10s\n" "Column" "MySQL Vals" "CH Vals" "Match?"
echo "================================================================"

MISMATCH_COLUMNS=()
MATCH_COUNT=0
MISMATCH_COUNT=0

for column in $COLUMNS; do
    # Get distinct values count from MySQL
    MYSQL_COUNT=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -s -e "
    SELECT COUNT(DISTINCT \`$column\`)
    FROM $TABLE
    " 2>/dev/null || echo "ERROR")
    
    # Get distinct values count from ClickHouse
    CH_COUNT=$(clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --database="$CLICKHOUSE_DATABASE" --query="
    SELECT COUNT(DISTINCT \`$column\`)
    FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
    WHERE is_deleted = 0 OR is_deleted IS NULL
    " 2>/dev/null || echo "ERROR")
    
    # Simple comparison for now
    if [ "$MYSQL_COUNT" = "$CH_COUNT" ] && [ "$MYSQL_COUNT" != "ERROR" ]; then
        MATCH="✅ SAME"
        MATCH_COUNT=$((MATCH_COUNT + 1))
        COLOR=$GREEN
    else
        MATCH="❌ DIFF"
        MISMATCH_COUNT=$((MISMATCH_COUNT + 1))
        MISMATCH_COLUMNS+=("$column")
        COLOR=$RED
    fi
    
    printf "${COLOR}%-25s${NC} | %-10s | %-10s | ${COLOR}%-10s${NC}\n" "$column" "$MYSQL_COUNT" "$CH_COUNT" "$MATCH"
done

echo "================================================================"
echo ""

echo -e "${BLUE}Summary:${NC}"
echo "Total columns: ${#COLUMNS[@]}"
echo -e "${GREEN}Same distinct count: $MATCH_COUNT${NC}"
echo -e "${RED}Different distinct count: $MISMATCH_COUNT${NC}"
echo ""

if [ $MISMATCH_COUNT -gt 0 ]; then
    echo -e "${YELLOW}Columns with potential issues:${NC}"
    for col in "${MISMATCH_COLUMNS[@]}"; do
        echo -e "${RED}  • $col${NC}"
    done
    echo ""
fi

echo "================================================================"
echo "Now running detailed analysis on suspicious columns..."
echo "================================================================"
echo ""

# Analyze NULL counts specifically
echo "NULL Value Analysis:"
echo "================================================================"
printf "%-25s | %-10s | %-10s | %-10s\n" "Column" "MySQL NULLs" "CH NULLs" "Match?"
echo "================================================================"

for column in $COLUMNS; do
    MYSQL_NULL=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -s -e "
    SELECT SUM(CASE WHEN \`$column\` IS NULL THEN 1 ELSE 0 END)
    FROM $TABLE
    ")
    
    CH_NULL=$(clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --database="$CLICKHOUSE_DATABASE" --query="
    SELECT SUM(CASE WHEN \`$column\` IS NULL THEN 1 ELSE 0 END)
    FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
    WHERE is_deleted = 0 OR is_deleted IS NULL
    FORMAT TSVRaw
    " 2>/dev/null || echo "ERROR")
    
    if [ "$MYSQL_NULL" = "$CH_NULL" ]; then
        MATCH="✅ SAME"
        COLOR=$GREEN
    else
        MATCH="❌ DIFF"
        COLOR=$RED
    fi
    
    printf "${COLOR}%-25s${NC} | %-10s | %-10s | ${COLOR}%-10s${NC}\n" "$column" "$MYSQL_NULL" "$CH_NULL" "$MATCH"
done

echo "================================================================"
echo ""

echo "Diagnostic complete!"
