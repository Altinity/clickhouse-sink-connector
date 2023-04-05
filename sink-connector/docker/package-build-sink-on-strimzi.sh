#!/bin/bash

# Production docker image builder
set -e

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
SRC_ROOT="$(realpath "${CUR_DIR}/..")"

{ cd "${SRC_ROOT}"; mvn clean package; } && "${CUR_DIR}"/build-sink-on-strimzi.sh
