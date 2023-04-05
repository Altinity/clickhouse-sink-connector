#!/bin/bash

helm repo add bitnami https://charts.bitnami.com/bitnami

helm install my-release bitnami/kafka

helm repo add redpanda-console 'https://packages.vectorized.io/public/console/helm/charts/'
helm repo update
helm install redpanda-console/console -f kafka-console-values.yaml