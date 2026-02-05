# Build and Test Guide

This guide covers building the ClickHouse Sink Connector JAR and running all test suites.

## Building the JAR

### Standard Build

```bash
# Build with Maven
mvn clean package

# Build skipping tests (faster)
mvn clean package -DskipTests

# Output JAR location
ls -lh sink-connector/target/*.jar
```

The built JAR will be located at:
- `sink-connector/target/clickhouse-sink-connector-<version>.jar`

### Build with Tests

```bash
# Run all tests
mvn clean test

# Run specific test class
mvn test -Dtest=ConcurrencyStressTest

# Run specific test method
mvn test -Dtest=ConcurrencyStressTest#testHighConcurrencyHashMapAccess

# Run with coverage report
mvn clean test jacoco:report
# View: sink-connector/target/site/jacoco/index.html
```

### Build Specific Modules

```bash
# Build only sink-connector module
cd sink-connector
mvn clean package

# Build only lightweight connector
cd sink-connector-lightweight
mvn clean package
```

## Docker Build

### Standard Debezium-based Image

```bash
# Build Docker image on Debezium base
cd sink-connector/docker
./build-sink-on-debezium-base.sh

# Verify image
docker images | grep clickhouse
```

### Lightweight Version

```bash
# Build lightweight embedded connector
cd sink-connector-lightweight
./build_docker.sh

# Or build for ARM architecture
./build_docker_arm.sh
```

### GraalVM Native Image (Advanced)

```bash
cd sink-connector/docker
./build-sink-on-graalvm.sh
```

## Running Tests

### Unit Tests

```bash
# Run all unit tests
mvn test

# Run specific test suites
mvn test -Dtest=ClickHouseSinkTaskTest
mvn test -Dtest=UtilsTest
```

### Concurrency Tests

```bash
# Run concurrency bug detection tests
mvn test -Dtest=ConcurrencyBugsTest

# Run stress tests (high load, many threads)
mvn test -Dtest=ConcurrencyStressTest

# Run with memory constraints to expose leaks
mvn test -Dtest=ConcurrencyStressTest#testMemoryPressureConcurrency -Xmx512m
```

### Data Type Tests

```bash
# Test MySQL data type conversions
mvn test -Dtest=MySQLDataTypeTest

# Test Postgres data type conversions
mvn test -Dtest=PostgresDataTypeTest
```

### DDL/Schema Evolution Tests

```bash
# Test ALTER TABLE operations
mvn test -Dtest=ClickHouseAlterTableTest

# Test schema cache management
mvn test -Dtest=DbWriterTest
```

### Transaction Tests

```bash
# Test transaction support
mvn test -Dtest=TransactionCoordinatorTest
mvn test -Dtest=TransactionManagerTest
```

### End-to-End Integration Tests

```bash
# Run full E2E test with Docker Compose
cd sink-connector-lightweight/tests/e2e-integration
docker-compose up --abort-on-container-exit

# View test results
docker-compose logs test-runner

# Clean up
docker-compose down -v
```

## Test Coverage

### Generate Coverage Report

```bash
# Run tests with coverage
mvn clean test jacoco:report

# View HTML report
# Open: sink-connector/target/site/jacoco/index.html
```

### Coverage Goals

- **Line Coverage**: > 80%
- **Branch Coverage**: > 70%
- **Critical Paths**: 100% (transaction handling, DDL operations)

## Performance Testing

### Throughput Benchmarks

```bash
# Run performance tests
mvn test -Dtest=PerformanceTest

# With specific configuration
mvn test -Dtest=PerformanceTest -Dthreads=10 -DbatchSize=10000
```

### Memory Profiling

```bash
# Run with heap dump on OOM
mvn test -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heap-dump.hprof

# Run with GC logging
mvn test -Xlog:gc*:file=/tmp/gc.log
```

## Debugging Tests

### Enable Debug Logging

```bash
# Run with debug logging
mvn test -Dtest=ConcurrencyStressTest -Dlog4j.configuration=file:log4j-debug.properties

# Or set log level via environment
export LOG_LEVEL=DEBUG
mvn test
```

### Remote Debugging

```bash
# Start tests with debug port open
mvn test -Dmaven.surefire.debug="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"

# Connect with IDE debugger to localhost:5005
```

## Continuous Integration

### GitHub Actions

The project includes CI workflows:
- `.github/workflows/build.yml` - Build and test on push
- `.github/workflows/release.yml` - Build release artifacts

### Local CI Simulation

```bash
# Run full CI pipeline locally
./scripts/ci-build.sh

# This will:
# 1. Clean workspace
# 2. Run unit tests
# 3. Run integration tests
# 4. Generate coverage report
# 5. Build JAR
# 6. Build Docker image
```

## Troubleshooting

### Common Issues

**Issue**: OutOfMemoryError during tests
```bash
# Solution: Increase heap size
export MAVEN_OPTS="-Xmx2g"
mvn test
```

**Issue**: Tests hang indefinitely
```bash
# Solution: Use timeout
mvn test -Dsurefire.timeout=300
```

**Issue**: Flaky concurrency tests
```bash
# Solution: Run multiple times to confirm
for i in {1..10}; do mvn test -Dtest=ConcurrencyStressTest || break; done
```

**Issue**: Docker compose fails to start
```bash
# Solution: Check ports and clean up
docker-compose down -v
lsof -i :3306,8123,9000
docker-compose up
```

## Quick Reference

| Task | Command |
|------|---------|
| Build JAR (skip tests) | `mvn clean package -DskipTests` |
| Run all tests | `mvn test` |
| Run specific test | `mvn test -Dtest=ClassName` |
| Coverage report | `mvn test jacoco:report` |
| Build Docker | `cd sink-connector/docker && ./build-sink-on-debezium-base.sh` |
| E2E test | `cd sink-connector-lightweight/tests/e2e-integration && docker-compose up` |
| Clean build | `mvn clean` |
| Install to local repo | `mvn install` |

## Release Build

### Create Release Artifacts

```bash
# Build release version (no SNAPSHOT)
mvn versions:set -DnewVersion=1.0.0
mvn clean package -DskipTests

# Build with all profiles
mvn clean package -P release

# Create distribution
mvn clean package assembly:single
```

### Verify Release

```bash
# Check JAR contents
jar tf sink-connector/target/clickhouse-sink-connector-1.0.0.jar

# Verify Main-Class manifest
unzip -p sink-connector/target/clickhouse-sink-connector-1.0.0.jar META-INF/MANIFEST.MF

# Test JAR execution
java -jar sink-connector/target/clickhouse-sink-connector-1.0.0.jar --help
```

## See Also

- [Configuration Reference](CONFIGURATION-REFERENCE.md)
- [Production Deployment Guide](PRODUCTION-DEPLOYMENT-GUIDE.md)
- [Development Guide](doc/development.md)
- [Transaction Support](sink-connector/doc/TRANSACTION-SUPPORT.md)
