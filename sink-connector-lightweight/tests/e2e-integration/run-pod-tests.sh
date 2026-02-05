#!/bin/bash
set -e

echo "========================================" 
echo "E2E Tests using Podman Pod"
echo "========================================"
echo ""

# Clean up any existing resources
echo "Cleaning up existing resources..."
podman pod rm -f e2e-test-pod 2>/dev/null || true
podman volume rm -f e2e-mysql-data e2e-clickhouse-data 2>/dev/null || true

# Create volumes
echo "Creating volumes..."
podman volume create e2e-mysql-data
podman volume create e2e-clickhouse-data

# Create pod with port mappings (using alternative ports to avoid conflicts)
echo "Creating pod..."
podman pod create --name e2e-test-pod \
  -p 13306:3306 \
  -p 18123:8123 \
  -p 19000:9000

# Start MySQL
echo "Starting MySQL..."
podman run -d --pod e2e-test-pod --name e2e-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=testdb \
  -v $(pwd)/init-mysql.sql:/docker-entrypoint-initdb.d/01-init.sql:Z \
  -v $(pwd)/test-scenarios.sql:/test-scenarios.sql:Z \
  -v e2e-mysql-data:/var/lib/mysql \
  mysql:8.0 \
  --server-id=1 --log-bin=mysql-bin --binlog-format=ROW \
  --binlog-row-image=FULL --gtid-mode=ON --enforce-gtid-consistency=ON

# Start ClickHouse
echo "Starting ClickHouse..."
podman run -d --pod e2e-test-pod --name e2e-clickhouse \
  -e CLICKHOUSE_DB=testdb \
  -v $(pwd)/init-clickhouse.sql:/docker-entrypoint-initdb.d/init.sql:Z \
  -v e2e-clickhouse-data:/var/lib/clickhouse \
  clickhouse/clickhouse-server:latest

# Wait for MySQL
echo "Waiting for MySQL to be ready..."
for i in {1..60}; do
  if podman exec e2e-mysql mysqladmin ping -h localhost -uroot -proot > /dev/null 2>&1; then
    echo "✓ MySQL is ready"
    break
  fi
  echo "  Waiting for MySQL... ($i/60)"
  sleep 2
done

# Wait for ClickHouse  
echo "Waiting for ClickHouse to be ready..."
for i in {1..60}; do
  if podman exec e2e-clickhouse clickhouse-client --query "SELECT 1" > /dev/null 2>&1; then
    echo "✓ ClickHouse is ready"
    break
  fi
  echo "  Waiting for ClickHouse... ($i/60)"
  sleep 2
done

# Build and start connector
echo "Starting Sink Connector..."
cd ../../..
podman build -t e2e-sink-connector -f sink-connector-lightweight/Dockerfile .
cd sink-connector-lightweight/tests/e2e-integration

podman run -d --pod e2e-test-pod --name e2e-sink-connector \
  -v $(pwd)/config.yml:/config.yml:Z \
  e2e-sink-connector

# Wait for connector
echo "Waiting for connector to initialize..."
sleep 15

# Run tests
echo "Executing test scenarios..."
podman exec e2e-mysql mysql -uroot -proot testdb < test-scenarios.sql

if [ $? -ne 0 ]; then
  echo "✗ Test scenarios failed to execute"
  exit 1
fi

echo "✓ Test scenarios executed"

# Wait for replication
echo "Waiting for replication (45 seconds)..."
sleep 45

# Run validation
echo "Running validation..."
podman run --rm --pod e2e-test-pod \
  -v $(pwd)/validate-results.sh:/validate-results.sh:Z \
  -e MYSQL_HOST=localhost \
  -e CLICKHOUSE_HOST=localhost \
  mysql:8.0 bash /validate-results.sh

EXIT_CODE=$?

# Cleanup
echo ""
echo "Cleaning up..."
podman pod stop e2e-test-pod
podman pod rm e2e-test-pod
podman volume rm e2e-mysql-data e2e-clickhouse-data

if [ $EXIT_CODE -eq 0 ]; then
  echo ""
  echo "========================================"
  echo "✓✓✓ ALL E2E TESTS PASSED! ✓✓✓"
  echo "========================================"
else
  echo ""
  echo "========================================"
  echo "✗✗✗ E2E TESTS FAILED! ✗✗✗"
  echo "========================================"
fi

exit $EXIT_CODE
