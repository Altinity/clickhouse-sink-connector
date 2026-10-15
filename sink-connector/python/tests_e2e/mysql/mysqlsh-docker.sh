#!/bin/sh
# MySQL Shell for the Python toolset MySQL e2e suite, taken from the MySQL server
# image the compose stack runs (it ships mysqlsh), so CI needs no package repository.
#
# The container shares the host network (the compose stack publishes MySQL there),
# runs as the calling user so the dump files it writes stay readable, sees
# PYTOOLS_E2E_MOUNT at the same path and starts in the caller's directory: the
# suite keeps every file mysqlsh reads or writes (option file, statement file,
# dump directories) under PYTOOLS_E2E_MOUNT. MYSQL_PWD and the RESYNC_* variables
# ch-mysql-resync sets for its mysqlsh child are passed through; stdin stays open
# for --passwords-from-stdin.
#
# PYTOOLS_E2E_MYSQL_IMAGE      image to run (default mysql:8.0)
# PYTOOLS_E2E_DOCKER_RUN_ARGS  extra `docker run` arguments (word-split)
set -eu
: "${PYTOOLS_E2E_MOUNT:?set PYTOOLS_E2E_MOUNT to the directory that holds the dump files}"
# shellcheck disable=SC2086
exec docker run --rm -i --network host \
    --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -e MYSQL_PWD -e RESYNC_SCHEMA -e RESYNC_DIR -e RESYNC_THREADS -e RESYNC_CONSISTENT -e RESYNC_TABLES \
    -v "$PYTOOLS_E2E_MOUNT:$PYTOOLS_E2E_MOUNT" -w "$PWD" \
    ${PYTOOLS_E2E_DOCKER_RUN_ARGS:-} \
    --entrypoint mysqlsh "${PYTOOLS_E2E_MYSQL_IMAGE:-mysql:8.0}" "$@"
