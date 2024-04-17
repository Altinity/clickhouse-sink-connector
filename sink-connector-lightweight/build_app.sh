#!/bin/bash

mvn clean install -DskipTests=true
./build_docker.sh