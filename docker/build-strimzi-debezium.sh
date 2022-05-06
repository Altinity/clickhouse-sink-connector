#!/bin/bash

# Production docker image builder
set -e

# Source configuration
CUR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
SRC_ROOT="$(realpath "${CUR_DIR}/..")"

# Externally configurable build-dependent options
TAG="${TAG:-latest}"
DOCKER_IMAGE="altinity/debezium-mysql-source-connector:${TAG}"

# Externally configurable build-dependent options
DOCKERFILE_DIR="${SRC_ROOT}/docker"
DOCKERFILE="${DOCKERFILE_DIR}/Dockerfile-strimzi-debezium"

#echo "*********************"
#echo "* Download apicurio *"
#echo "*********************"
#VERSION="2.1.5.Final"
#REMOTE_FILE="https://repo1.maven.org/maven2/io/apicurio/apicurio-registry-distro-connect-converter/$VERSION/apicurio-registry-distro-connect-converter-$VERSION.tar.gz"
#FILE=/tmp/apicurio-registry-distro-connect-converter.tar.gz
#wget $REMOTE_FILE  -O $FILE
#EXTRACT_DIR=$SRC_ROOT/deploy/apicurio-registry-distro-connect-converter
#mkdir -p  $EXTRACT_DIR
#tar xvfz $FILE --directory $EXTRACT_DIR
#trap "echo 'Delete files' && rm -rf $FILE $EXTRACT_DIR" EXIT

echo "***************"
echo "* Build image *"
echo "***************"
DOCKER_CMD="docker build -t ${DOCKER_IMAGE} -f ${DOCKERFILE} ${SRC_ROOT}"


if ${DOCKER_CMD}; then
    echo "ALL DONE"
else
    echo "FAILED"
    exit 1
fi



