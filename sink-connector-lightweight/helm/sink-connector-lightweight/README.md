# Sink Connector Lightweight Helm Instructions
To install Helm charts from this repository, add the repository to Helm: [TODO]

## Configuring
There are two main ways to configure the sink-connector-lightweight helm chart:

1. By specifying a configmap name (`configmapName: "myconfigmap"`) in the same namespace as the deployment is deployed to that has the configuration stored under the `configmap.yaml` path and setting `config-yaml: false`. Or:
1. Overriding the default configuration located under the `config-yaml` values file key, and [optionally] specifying a custom name for the configmap using the `configmapName` key.

> [!NOTE]
> Specific configuration for postgres can be found in the `values.yaml` file under the `#### Postgres Configuration` header.

## Installing

```
cd sink-connector-lightweight/helm/sink-connector-lightweight
helm install sink-connector-lightweight . 
```