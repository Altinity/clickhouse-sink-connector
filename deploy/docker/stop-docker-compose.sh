#!/bin/bash

docker-compose down --remove-orphans

docker volume rm $(docker volume ls -q)