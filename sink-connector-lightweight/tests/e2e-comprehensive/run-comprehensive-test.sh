#!/bin/bash
set -e

################################################################################
# ClickHouse Sink Connector - Comprehensive E2E Test Orchestrator
#
# This script orchestrates a complete end-to-end test including:
# - Phase 1: Setup (MySQL + ClickHouse + test data)
# - Phase 2: Initial snapshot (mysqlsh dump + ClickHouse load)
# - Phase 3: Start CDC connector
# - Phase 4: Live DML operations
# - Phase 5: Validation (checksum + integrity checks)
# - Phase 6: Cleanup and reporting
################################################################################

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Test configuration
TEST_START_TIME=$(date +%s)
TEST_DATE=$(date '+%Y-%m-%d %H:%M:%S')
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$SCRIPT_DIR/logs" "$SCRIPT_DIR/reports"
REPORT_FILE="$SCRIPT_DIR/reports/test-report.txt"
LOG_FILE="$SCRIPT_DIR/logs/comprehensive-test.log"

# Phase status tracking
PHASE1_STATUS="PENDING"
PHASE2_STATUS="PENDING"
PHASE3_STATUS="PENDING"
PHASE4_STATUS="PENDING"
PHASE5_STATUS="PENDING"

################################################################################
# Helper Functions
################################################################################

log() {
    echo -e "${1}" | tee -a "$LOG_FILE"
}

log_phase() {
    echo "" | tee -a "$LOG_FILE"
    echo "========================================" | tee -a "$LOG_FILE"
    log "${CYAN}${1}${NC}"
    echo "========================================" | tee -a "$LOG_FILE"
    echo "" | tee -a "$LOG_FILE"
}

log_success() {
    log "${GREEN}✅ ${1}${NC}"
}

log_error() {
    log "${RED}❌ ${1}${NC}"
}

log_info() {
    log "${BLUE}ℹ️  ${1}${NC}"
}

log_warning() {
    log "${YELLOW}⚠️  ${1}${NC}"
}

################################################################################
# Phase Execution
################################################################################

execute_phase() {
    local phase_num=$1
    local phase_name=$2
    local phase_script=$3
    local status_var=$4
    
    log_phase "Phase ${phase_num}: ${phase_name}"
    
    if [ -f "$phase_script" ]; then
        if bash "$phase_script"; then
            eval "${status_var}=PASS"
            log_success "Phase ${phase_num} completed successfully"
            return 0
        else
            eval "${status_var}=FAIL"
            log_error "Phase ${phase_num} failed"
            return 1
        fi
    else
        eval "${status_var}=FAIL"
        log_error "Phase script not found: $phase_script"
        return 1
    fi
}

################################################################################
# Report Generation
################################################################################

generate_report() {
    local test_end_time=$(date +%s)
    local duration=$((test_end_time - TEST_START_TIME))
    local minutes=$((duration / 60))
    local seconds=$((duration % 60))
    
    cat > "$REPORT_FILE" << EOF
================================================================================
        ClickHouse Sink Connector - Comprehensive E2E Test Report
================================================================================

Test Date: ${TEST_DATE}
Duration: ${minutes}m ${seconds}s

--------------------------------------------------------------------------------
Phase Results:
--------------------------------------------------------------------------------

Phase 1: Setup                              ${PHASE1_STATUS}
Phase 2: Initial Snapshot (mysqlsh)         ${PHASE2_STATUS}
Phase 3: CDC Connector Startup              ${PHASE3_STATUS}
Phase 4: Live DML Operations                ${PHASE4_STATUS}
Phase 5: Data Validation & Checksum         ${PHASE5_STATUS}

--------------------------------------------------------------------------------
Test Summary:
--------------------------------------------------------------------------------

EOF

    # Determine overall status
    if [ "$PHASE1_STATUS" = "PASS" ] && \
       [ "$PHASE2_STATUS" = "PASS" ] && \
       [ "$PHASE3_STATUS" = "PASS" ] && \
       [ "$PHASE4_STATUS" = "PASS" ] && \
       [ "$PHASE5_STATUS" = "PASS" ]; then
        echo "OVERALL STATUS: ✅ PRODUCTION READY" >> "$REPORT_FILE"
        echo "" >> "$REPORT_FILE"
        echo "All phases completed successfully!" >> "$REPORT_FILE"
        echo "The ClickHouse Sink Connector has passed comprehensive validation." >> "$REPORT_FILE"
        return 0
    else
        echo "OVERALL STATUS: ❌ FAILED" >> "$REPORT_FILE"
        echo "" >> "$REPORT_FILE"
        echo "One or more phases failed. Review logs for details." >> "$REPORT_FILE"
        return 1
    fi
}

################################################################################
# Main Test Execution
################################################################################

main() {
    log_phase "ClickHouse Sink Connector - Comprehensive E2E Test"
    log_info "Test started at: ${TEST_DATE}"
    log_info "Logs: ${LOG_FILE}"
    log_info "Report: ${REPORT_FILE}"
    
    # Create necessary directories
    mkdir -p "$SCRIPT_DIR/dumps" "$SCRIPT_DIR/reports" "$SCRIPT_DIR/logs"
    
    # Execute test phases
    execute_phase 1 "Setup" "$SCRIPT_DIR/phase1-setup.sh" "PHASE1_STATUS" || {
        log_error "Phase 1 failed, aborting test"
        generate_report
        exit 1
    }
    
    execute_phase 2 "Initial Snapshot" "$SCRIPT_DIR/phase2-snapshot.sh" "PHASE2_STATUS" || {
        log_error "Phase 2 failed, aborting test"
        generate_report
        exit 1
    }
    
    execute_phase 3 "CDC Connector" "$SCRIPT_DIR/phase3-cdc.sh" "PHASE3_STATUS" || {
        log_error "Phase 3 failed, aborting test"
        generate_report
        exit 1
    }
    
    execute_phase 4 "Live DML Operations" "$SCRIPT_DIR/phase4-live-dml.sh" "PHASE4_STATUS" || {
        log_error "Phase 4 failed, aborting test"
        generate_report
        exit 1
    }
    
    execute_phase 5 "Validation" "$SCRIPT_DIR/phase5-validate.sh" "PHASE5_STATUS" || {
        log_error "Phase 5 failed, aborting test"
        generate_report
        exit 1
    }
    
    # Generate final report
    log_phase "Phase 6: Report Generation"
    if generate_report; then
        log_success "Test report generated: ${REPORT_FILE}"
        cat "$REPORT_FILE"
        log_success "ALL TESTS PASSED! ✅✅✅"
        exit 0
    else
        log_error "Test report generated with failures: ${REPORT_FILE}"
        cat "$REPORT_FILE"
        log_error "TESTS FAILED! ❌❌❌"
        exit 1
    fi
}

# Run main
main "$@"
