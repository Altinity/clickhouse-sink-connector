#!/bin/bash

################################################################################
# Column-by-Column Checksum Diagnostic Script
# 
# Purpose: Identify which specific column(s) in the customers table are causing
#          checksum mismatches between MySQL and ClickHouse
#
# Approach: For each column, compute an individual checksum and compare
#           MySQL vs ClickHouse to pinpoint the source of discrepancy
################################################################################

set -e

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
EXCLUDED_COLUMNS="_version,_sign,is_deleted,_is_deleted"

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

# Step 1: Get all columns from MySQL
echo -e "${BLUE}Step 1: Fetching column list from MySQL...${NC}"
echo ""

MYSQL_COLUMNS=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -s -e "
SELECT COLUMN_NAME 
FROM information_schema.COLUMNS 
WHERE TABLE_SCHEMA = '$MYSQL_DATABASE' 
  AND TABLE_NAME = '$TABLE'
  AND COLUMN_NAME NOT IN ('$EXCLUDED_COLUMNS')
ORDER BY COLUMN_NAME
" | tr '\n' ' ')

echo "Columns found: $MYSQL_COLUMNS"
echo ""

# Step 2: Get all columns from ClickHouse
echo -e "${BLUE}Step 2: Fetching column list from ClickHouse...${NC}"
echo ""

# Create a temporary file for excluded columns list
IFS=',' read -ra EXCLUDED_ARRAY <<< "$EXCLUDED_COLUMNS"
EXCLUDED_QUOTED=""
for col in "${EXCLUDED_ARRAY[@]}"; do
    if [ -z "$EXCLUDED_QUOTED" ]; then
        EXCLUDED_QUOTED="'$col'"
    else
        EXCLUDED_QUOTED="$EXCLUDED_QUOTED,'$col'"
    fi
done

CH_COLUMNS=$(clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
SELECT name 
FROM system.columns 
WHERE database = '$CLICKHOUSE_DATABASE' 
  AND table = '$TABLE'
  AND name NOT IN ($EXCLUDED_QUOTED)
ORDER BY name
FORMAT TSVRaw
" | tr '\n' ' ')

echo "Columns found: $CH_COLUMNS"
echo ""

# Step 3: Compare column lists
echo -e "${BLUE}Step 3: Comparing column lists...${NC}"
echo ""

MYSQL_COL_ARRAY=($MYSQL_COLUMNS)
CH_COL_ARRAY=($CH_COLUMNS)

if [ "${MYSQL_COL_ARRAY[*]}" != "${CH_COL_ARRAY[*]}" ]; then
    echo -e "${YELLOW}WARNING: Column lists differ!${NC}"
    echo "MySQL columns: ${MYSQL_COL_ARRAY[*]}"
    echo "ClickHouse columns: ${CH_COL_ARRAY[*]}"
    echo ""
fi

# Use MySQL columns as the reference
COLUMNS=("${MYSQL_COL_ARRAY[@]}")

# Step 4: Compute individual column checksums
echo -e "${BLUE}Step 4: Computing individual column checksums...${NC}"
echo ""
echo "================================================================"
printf "%-25s | %-34s | %-34s | %-10s\n" "Column" "MySQL MD5" "ClickHouse MD5" "Match?"
echo "================================================================"

MISMATCH_COLUMNS=()
MATCH_COUNT=0
MISMATCH_COUNT=0

for column in "${COLUMNS[@]}"; do
    # MySQL: Compute checksum for this column
    # Handle special characters in column names with backticks
    MYSQL_CHECKSUM=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -s -e "
    SELECT MD5(GROUP_CONCAT(IFNULL(\`$column\`, '') ORDER BY customer_id SEPARATOR '|'))
    FROM $TABLE
    " 2>/dev/null || echo "ERROR")
    
    # ClickHouse: Compute checksum for this column
    CH_CHECKSUM=$(clickhouse-client --host="$CLICKHOUSE_HOST" --port="$CLICKHOUSE_PORT" --user="$CLICKHOUSE_USER" --password="$CLICKHOUSE_PASSWORD" --database="$CLICKHOUSE_DATABASE" --query="
    SELECT lower(hex(MD5(groupArray(ifNull(toString(\`$column\`), '')))))
    FROM (
        SELECT \`$column\`
        FROM $CLICKHOUSE_DATABASE.$TABLE FINAL
        WHERE is_deleted = 0 OR is_deleted IS NULL
        ORDER BY customer_id
    )
    FORMAT TSVRaw
    " 2>/dev/null || echo "ERROR")
    
    # Compare checksums
    if [ "$MYSQL_CHECKSUM" = "$CH_CHECKSUM" ]; then
        MATCH="✅ MATCH"
        MATCH_COUNT=$((MATCH_COUNT + 1))
        COLOR=$GREEN
    else
        MATCH="❌ MISMATCH"
        MISMATCH_COUNT=$((MISMATCH_COUNT + 1))
        MISMATCH_COLUMNS+=("$column")
        COLOR=$RED
    fi
    
    # Print result
    printf "${COLOR}%-25s${NC} | %-34s | %-34s | ${COLOR}%-10s${NC}\n" "$column" "$MYSQL_CHECKSUM" "$CH_CHECKSUM" "$MATCH"
done

echo "================================================================"
echo ""

# Step 5: Summary
echo -e "${BLUE}Step 5: Summary${NC}"
echo ""
echo "Total columns analyzed: ${#COLUMNS[@]}"
echo -e "${GREEN}Matching columns: $MATCH_COUNT${NC}"
echo -e "${RED}Mismatching columns: $MISMATCH_COUNT${NC}"
echo ""

if [ $MISMATCH_COUNT -gt 0 ]; then
    echo -e "${RED}================================================================${NC}"
    echo -e "${RED}MISMATCHING COLUMNS IDENTIFIED:${NC}"
    echo -e "${RED}================================================================${NC}"
    for col in "${MISMATCH_COLUMNS[@]}"; do
        echo -e "${RED}  • $col${NC}"
    done
    echo ""
    echo -e "${YELLOW}Next Steps:${NC}"
    echo "  1. Investigate each mismatching column in detail"
    echo "  2. Run detailed diagnostic: ./column_detail_diagnostic.sh <column_name>"
    echo "  3. Check data types, NULL handling, and sample values"
    echo ""
else
    echo -e "${GREEN}✅ All columns match! The issue may be in excluded columns or row ordering.${NC}"
fi

echo "================================================================"
echo "Diagnostic complete!"
echo "================================================================"
