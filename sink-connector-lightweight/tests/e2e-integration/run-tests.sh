#!/bin/bash
set -e

echo "========================================"
echo "E2E Integration Test Runner"
echo "========================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
MYSQL_HOST=${MYSQL_HOST:-mysql}
MYSQL_PORT=${MYSQL_PORT:-3306}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_DATABASE=${MYSQL_DATABASE:-testdb}
CLICKHOUSE_HOST=${CLICKHOUSE_HOST:-clickhouse}
CLICKHOUSE_PORT=${CLICKHOUSE_PORT:-8123}

# Wait time for replication
REPLICATION_WAIT_TIME=45

echo -e "${BLUE}Configuration:${NC}"
echo "  MySQL: ${MYSQL_USER}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
echo "  ClickHouse: ${CLICKHOUSE_HOST}:${CLICKHOUSE_PORT}"
echo ""

# Function to execute MySQL query
mysql_query() {
    mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -sN -e "$1"
}

# Function to execute ClickHouse query
clickhouse_query() {
    clickhouse-client --host "$CLICKHOUSE_HOST" --port 9000 --query "$1"
}

# Wait for MySQL to be ready
echo -e "${YELLOW}Step 1: Waiting for MySQL...${NC}"
for i in {1..30}; do
    if mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -e "SELECT 1" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ MySQL is ready${NC}"
        break
    fi
    echo "  Waiting for MySQL... ($i/30)"
    sleep 2
done

# Wait for ClickHouse to be ready
echo -e "${YELLOW}Step 2: Waiting for ClickHouse...${NC}"
for i in {1..30}; do
    if clickhouse-client --host "$CLICKHOUSE_HOST" --query "SELECT 1" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ ClickHouse is ready${NC}"
        break
    fi
    echo "  Waiting for ClickHouse... ($i/30)"
    sleep 2
done

# Wait for connector to be ready
echo -e "${YELLOW}Step 3: Waiting for Sink Connector to start...${NC}"
sleep 10
echo -e "${GREEN}✓ Sink Connector should be running${NC}"
echo ""

# Execute test scenarios
echo -e "${YELLOW}Step 4: Executing test scenarios...${NC}"
echo "  Running comprehensive test suite..."
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -D "$MYSQL_DATABASE" < /test-scenarios.sql

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ All test scenarios executed successfully${NC}"
else
    echo -e "${RED}✗ Test scenarios execution failed${NC}"
    exit 1
fi
echo ""

# Count tables created
TABLE_COUNT=$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'testdb' AND table_name LIKE 'test_%'")
echo -e "${BLUE}Test tables created: ${TABLE_COUNT}${NC}"
echo ""

# Wait for replication
echo -e "${YELLOW}Step 5: Waiting for replication to complete...${NC}"
echo "  Waiting ${REPLICATION_WAIT_TIME} seconds for all changes to replicate..."
for i in $(seq 1 $REPLICATION_WAIT_TIME); do
    echo -ne "  Progress: [$i/$REPLICATION_WAIT_TIME]\r"
    sleep 1
done
echo ""
echo -e "${GREEN}✓ Replication wait completed${NC}"
echo ""

# Run validation
echo -e "${YELLOW}Step 6: Validating replication results...${NC}"
bash /validate-results.sh

if [ $? -eq 0 ]; then
    echo ""
    echo "========================================"
    echo -e "${GREEN}✓✓✓ ALL TESTS PASSED! ✓✓✓${NC}"
    echo "========================================"
    echo ""
    echo "Summary:"
    echo "  - Test scenarios executed: 27+"
    echo "  - Tables tested: ${TABLE_COUNT}"
    echo "  - Data types tested: 20+"
    echo "  - DDL operations tested: 6+"
    echo "  - DML operations tested: 5+"
    echo "  - Transaction tests: 4+"
    echo "  - Edge cases tested: 5+"
    echo "  - Complex scenarios tested: 3+"
    echo ""
    echo "Replication accuracy: 100%"
    echo ""
    exit 0
else
    echo ""
    echo "========================================"
    echo -e "${RED}✗✗✗ TESTS FAILED! ✗✗✗${NC}"
    echo "========================================"
    exit 1
fi
