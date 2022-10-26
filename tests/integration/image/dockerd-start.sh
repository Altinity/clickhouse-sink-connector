#!/bin/bash
set -e

dockerd --storage-driver vfs --data-root /scratch/docker --host=unix:///var/run/docker.sock &>/var/log/somefile &

set +e
reties=0
while true; do
    docker info &>/dev/null && break
    reties=$[$reties+1]
    if [[ $reties -ge 100 ]]; then # 10 sec max
        echo "Can't start docker daemon, timeout exceeded." >&2
        exit 1;
    fi
    sleep 0.1
done
set -e
