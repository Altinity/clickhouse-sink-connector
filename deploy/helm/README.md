### Install Strimzi CRD 

Install strimzi version
0.31.0
```
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi/strimzi-kafka-operator --version 0.31.0

```
Note: strimzi operator and the KafkaConnect pods need to be on the same namespace.

#### Update dependencies

```
helm dependency update
```

### Dry run charts
```
helm install sink altinity-sink-connector --dry-run
```
### Install Kafka

helm repo add bitnami https://charts.bitnami.com/bitnami \
helm install my-release bitnami/kafka


### Kafka instructions

```

Kafka can be accessed by consumers via port 9092 on the following DNS name from within your cluster:

    my-release-kafka.default.svc.cluster.local

Each Kafka broker can be accessed by producers via port 9092 on the following DNS name(s) from within your cluster:

    my-release-kafka-0.my-release-kafka-headless.default.svc.cluster.local:9092

```


## MySQL
```
https://charts.bitnami.com/bitnami

```
