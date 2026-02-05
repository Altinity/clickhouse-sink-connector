#!/bin/bash
# Setup script for comprehensive E2E test

set -e

echo "========================================="
echo "Comprehensive E2E Test - Setup"
echo "========================================="
echo ""

# Make all scripts executable
echo "Making scripts executable..."
chmod +x run-comprehensive-test.sh
chmod +x phase1-setup.sh
chmod +x phase2-snapshot.sh
chmod +x phase3-cdc.sh
chmod +x phase4-live-dml.sh
chmod +x phase5-validate.sh

echo "✅ Scripts are now executable"
echo ""

# Verify Docker is available
echo "Verifying Docker/Podman..."
if command -v docker &> /dev/null; then
    echo "✅ Docker found: $(docker --version)"
    DOCKER_CMD="docker"
elif command -v podman &> /dev/null; then
    echo "✅ Podman found: $(podman --version)"
    DOCKER_CMD="podman"
else
    echo "❌ Neither Docker nor Podman found. Please install one of them."
    exit 1
fi
echo ""

# Verify docker-compose is available
echo "Verifying docker-compose..."
if command -v docker-compose &> /dev/null; then
    echo "✅ docker-compose found: $(docker-compose --version)"
    COMPOSE_CMD="docker-compose"
elif command -v podman-compose &> /dev/null; then
    echo "✅ podman-compose found: $(podman-compose --version)"
    COMPOSE_CMD="podman-compose"
else
    echo "❌ Neither docker-compose nor podman-compose found. Please install one of them."
    exit 1
fi
echo ""

# Check if we're in the right directory
if [ ! -f "docker-compose-comprehensive.yml" ]; then
    echo "❌ Error: docker-compose-comprehensive.yml not found"
    echo "   Please run this script from: sink-connector-lightweight/tests/e2e-comprehensive/"
    exit 1
fi

echo "✅ All prerequisites met!"
echo ""
echo "========================================="
echo "Ready to run comprehensive E2E test"
echo "========================================="
echo ""
echo "To start the test:"
echo ""
echo "  1. Start containers:"
echo "     ${COMPOSE_CMD} -f docker-compose-comprehensive.yml up -d"
echo ""
echo "  2. Run test:"
echo "     ${DOCKER_CMD} exec -it e2e-comp-tools bash /scripts/run-comprehensive-test.sh"
echo ""
echo "  3. View results:"
echo "     ${DOCKER_CMD} exec e2e-comp-tools cat /reports/test-report.txt"
echo ""
echo "  4. Cleanup:"
echo "     ${COMPOSE_CMD} -f docker-compose-comprehensive.yml down -v"
echo ""
