#!/bin/bash

docker-compose -f docker-compose-mysql.yml down --remove-orphans

docker volume rm $(docker volume ls -q)
