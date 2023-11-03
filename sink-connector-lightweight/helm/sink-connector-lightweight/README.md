To install Helm charts from this repository, add the repository to Helm:

The configuration is stored in templates/configmap.yaml.
#ToDo need to move the configuration to values.

For postgres replication, set this variable to true in `values.yaml`
```#### Postgres connector
postgres: true
```

```
cd sink-connector-lightweight/helm/sink-connector-lightweight
helm install sink-connector-lightweight . 
```