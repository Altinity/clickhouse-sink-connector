
## RedPanda

1. Create a Kubernetes cluster with minikube
2. Install cert-manager
3. Install the Redpanda operator
4. Install and connect to a Redpanda cluster
5. Start streaming
6. Clean up

### minikube
```bash
wget https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
install minikube-linux-amd64 ~/bin/minikube
rm minikube-linux-amd64 
minikube version
```

```bash
minikube start
minikube status
```

### k9s
```bash
VERSION="v0.25.18"
wget https://github.com/derailed/k9s/releases/download/$VERSION/k9s_Linux_x86_64.tar.gz
install k9s ~/bin/k9s
```

### helm
```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | HELM_INSTALL_DIR=~/bin USE_SUDO=false bash
helm version
```

### cert-manager
```bash
docker image pull quay.io/jetstack/cert-manager-controller:v1.4.0
docker image pull quay.io/jetstack/cert-manager-cainjector:v1.4.0
docker image pull quay.io/jetstack/cert-manager-webhook:v1.4.0

minikube image load quay.io/jetstack/cert-manager-controller:v1.4.0
minikube image load quay.io/jetstack/cert-manager-cainjector:v1.4.0
minikube image load quay.io/jetstack/cert-manager-webhook:v1.4.0
```

```bash
VERSION="v1.4.0"
NAMESPACE="cert-manager"
echo "Install cert-manager. Version: $VERSION Namespace: $NAMESPACE" && \
helm repo add jetstack https://charts.jetstack.io && \
helm repo update && \
helm install \
cert-manager jetstack/cert-manager \
--namespace $NAMESPACE \
--create-namespace \
--version $VERSION \
--set installCRDs=true
```

```bash
kubectl -n $NAMESPACE rollout status -w deployment/cert-manager
kubectl -n $NAMESPACE rollout status -w deployment/cert-manager-cainjector
kubectl -n $NAMESPACE rollout status -w deployment/cert-manager-webhook
kubectl -n $NAMESPACE get pod
```

```bash
kubectl get pod -n $NAMESPACE
NAME                                       READY   STATUS    RESTARTS   AGE
cert-manager-798f8bb594-n6fsm              1/1     Running   0          43s
cert-manager-cainjector-5bb9bfbb5c-bzvvq   1/1     Running   0          43s
cert-manager-webhook-69579b9ccd-ldhgb      1/1     Running   0          43s

```

### jq
Ubuntu
```bash
sudo apt-get update && sudo apt-get install jq
```

### redpanda-operator

```bash
docker image pull   vectorized/redpanda-operator:v21.11.15
docker image pull   gcr.io/kubebuilder/kube-rbac-proxy:v0.8.0

minikube image load vectorized/redpanda-operator:v21.11.15
minikube image load gcr.io/kubebuilder/kube-rbac-proxy:v0.8.0
```

```bash
#VERSION=$(curl -s https://api.github.com/repos/redpanda-data/redpanda/releases/latest | jq -r .tag_name)
VERSION="v21.11.15"
NAMESPACE=redpanda
echo "Install redpanda-operator. Version: $VERSION Namespace: $NAMESPACE" && \
helm repo add redpanda https://charts.vectorized.io/ && helm repo update && \
kubectl apply -k https://github.com/redpanda-data/redpanda/src/go/k8s/config/crd?ref=$VERSION && \
helm install \
  redpanda-operator \
  redpanda/redpanda-operator \
  --namespace $NAMESPACE \
  --create-namespace \
  --version $VERSION
```


```bash
kubectl -n $NAMESPACE rollout status -w deployment/redpanda-operator
kubectl -n $NAMESPACE get pod
```

### redpanda cluster

https://docs.redpanda.com/docs/quickstart/kubernetes-qs-minikube
use CERT-MANAGER v 1.4
```bash
NAMESPACE=redpanda
kubectl create namespace $NAMESPACE
kubectl -n $NAMESPACE create -f redpanda-internal.yaml
```

## mysql

### mysql-operator

```bash
docker   image pull mysql/mysql-operator:8.0.29-2.0.4
minikube image load mysql/mysql-operator:8.0.29-2.0.4
```

```bash
NAMESPACE="mysql"
VERSION="2.0.4"
echo "Install mysql-operator. Version: $VERSION Namespace: $NAMESPACE" && \
helm repo add mysql-operator https://mysql.github.io/mysql-operator/ && \
helm repo update && \
helm install \
  mysql-operator \
  mysql-operator/mysql-operator \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  --version $VERSION
```

```bash
docker   image pull mysql/mysql-server:8.0.29
docker   image pull mysql/mysql-router:8.0.29

minikube image load mysql/mysql-server:8.0.29
minikube image load mysql/mysql-router:8.0.29 
```

### mysql cluster
```bash
NAMESPACE="mysql"
kubectl create ns "${NAMESPACE}"
kubectl -n "${NAMESPACE}" apply -f mysql.yaml
```

```bash
minikube image load altinity/clickhouse-kafka-sink-connector:latest
minikube image build -t my_image .
```

```bash
kubectl -n $NAMESPACE rollout status -w deployment/mysql-operator
kubectl -n $NAMESPACE get pod
```

### Strimzi

```bash
docker   image pull quay.io/strimzi/operator:0.28.0
minikube image load quay.io/strimzi/operator:0.28.0 
```

```bash
VERSION="0.28.0"
NAMESPACE="strimzi"
echo "Install strimzi-operator. Version: $VERSION Namespace: $NAMESPACE" && \
helm repo add strimzi https://strimzi.io/charts/ && \
helm install \
    strimzi-kafka-operator \
    strimzi/strimzi-kafka-operator \
    --namespace $NAMESPACE \
    --create-namespace \
    --version $VERSION \
    --set watchAnyNamespace=true
```
```bash
helm delete strimzi-kafka-operator
```

#$ helm install --name my-release --set logLevel=DEBUG,fullReconciliationIntervalMs=240000 strimzi/strimzi-kafka-operator

#kafkaConnect.image.registry 	Override default Kafka Connect image registry 	nil
#kafkaConnect.image.repository 	Override default Kafka Connect image repository 	nil
#kafkaConnect.image.name 	Kafka Connect image name 	kafka
#kafkaConnect.image.tagPrefix 	Override default Kafka Connect image tag prefix 	nil

### clickhouse

```bash
docker   image pull altinity/clickhouse-operator:0.18.4
minikube image load altinity/clickhouse-operator:0.18.4
```

```bash
NAMESPACE=clickhouse
INSTALL_SH="https://raw.githubusercontent.com/Altinity/clickhouse-operator/master/deploy/operator-web-installer/clickhouse-operator-install.sh"
curl -s $INSTALL_SH | OPERATOR_NAMESPACE="${NAMESPACE}" bash
```

```bash
kubectl -n ${NAMESPACE} apply -f <( \
  cat clickhouse-operator-install-template.yaml | \
    OPERATOR_NAMESPACE="clickhouse" \
    OPERATOR_IMAGE="altinity/clickhouse-operator:0.18.4" \
    METRICS_EXPORTER_IMAGE="altinity/metrics-exporter:0.18.4" \
    IMAGE_PULL_POLICY="IfNotPresent" \
    envsubst \
)
```

```bash
docker   image pull altinity/clickhouse-operator:0.18.4
docker   image pull altinity/metrics-exporter:0.18.4

minikube image load altinity/clickhouse-operator:0.18.4
minikube image load altinity/metrics-exporter:0.18.4
```

```bash
docker   image pull clickhouse/clickhouse-server:22.3.5.5
minikube image load clickhouse/clickhouse-server:22.3.5.5
```

```bash
NAMESPACE=clickhouse
kubectl -n $NAMESPACE apply -f clickhouse.yaml
```

### schema registry

```bash
docker   image pull apicurio/apicurio-registry-mem:2.0.0.Final
minikube image load apicurio/apicurio-registry-mem:2.0.0.Final 
```

```bash
NAMESPACE="registry"
kubectl create namespace ${NAMESPACE}
kubectl -n ${NAMESPACE} apply -f schema-registry.yaml
```

### debezium

```bash
docker   image pull sunsingerus/debezium-mysql-source-connector:latest
minikube image load sunsingerus/debezium-mysql-source-connector:latest
```

```bash
NAMESPACE="debezium"
kubectl create namespace "${NAMESPACE}"
kubectl -n $NAMESPACE apply -f debezium-connect.yaml
kubectl -n $NAMESPACE apply -f <( \
  cat debezium-connector-avro.yaml | \
    MYSQL_HOST="mysql.mysql" \
    MYSQL_PORT="3306" \
    MYSQL_USER="root" \
    MYSQL_PASSWORD="root" \
    MYSQL_DBS="test" \
    MYSQL_TABLES="employees" \
    KAFKA_BOOTSTRAP_SERVERS="redpanda.redpanda:9092" \
    KAFKA_TOPIC="schema-changes.test_db" \
    DATABASE_SERVER_ID="5432" \
    DATABASE_SERVER_NAME="SERVER5432" \
    REGISTRY_URL="http://schema-registry.registry:8080/apis/registry/v2" \
    envsubst \ 
)
```

### sink

```bash
NAMESPACE="sink"
kubectl -n $NAMESPACE apply -f sink-connect.yaml
kubectl -n $NAMESPACE apply -f <( \
cat sink-connector.yaml | \
  CLICKHOUSE_HOST="clickhouse" \
  CLICKHOUSE_PORT=8123 \
  CLICKHOUSE_USER="root" \
  CLICKHOUSE_PASSWORD="root" \
  CLICKHOUSE_TABLE="employees" \
  CLICKHOUSE_DATABASE="test" \
  BUFFER_COUNT=10000 \
  TOPICS="SERVER5432.test.employees_predated, SERVER5432.test.products" \
  envsubst \ 
)
```
