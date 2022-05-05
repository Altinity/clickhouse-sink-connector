
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

### jq
Ubuntu
```bash
sudo apt-get update && sudo apt-get install jq
```

### helm
```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | HELM_INSTALL_DIR=~/bin USE_SUDO=false bash
helm version
```

### cert-manager

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

### redpanda-operator

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
kubectl -n $NAMESPACE rollout status -w deployment/mysql-operator
kubectl -n $NAMESPACE get pod
```

### mysql cluster
```bash
NAMESPACE="mysql"
kubectl create ns "${NAMESPACE}"
kubectl -n "${NAMESPACE}" apply -f mysql.yaml
```

### clickhouse

```bash
NAMESPACE=clickhouse
INSTALL_SH="https://raw.githubusercontent.com/Altinity/clickhouse-operator/master/deploy/operator-web-installer/clickhouse-operator-install.sh"
curl -s ${INSTALL_SH} | OPERATOR_NAMESPACE="${NAMESPACE}" bash
```

## Local alternative installation
```bash
NAMESPACE=clickhouse
kubectl create namespace $NAMESPACE
kubectl -n ${NAMESPACE} apply -f <( \
  cat clickhouse-operator-install-template.yaml | \
    OPERATOR_NAMESPACE="clickhouse" \
    OPERATOR_IMAGE="altinity/clickhouse-operator:0.18.4" \
    METRICS_EXPORTER_IMAGE="altinity/metrics-exporter:0.18.4" \
    IMAGE_PULL_POLICY="IfNotPresent" \
    envsubst )
```

```bash
kubectl -n $NAMESPACE rollout status -w deployment/clickhouse-operator
kubectl -n $NAMESPACE get pod
```

```bash
NAMESPACE=clickhouse
kubectl -n $NAMESPACE apply -f clickhouse.yaml
```

### schema registry

```bash
NAMESPACE="registry"
kubectl create namespace ${NAMESPACE}
kubectl -n ${NAMESPACE} apply -f schema-registry.yaml
```

```bash
kubectl -n $NAMESPACE rollout status -w deployment/schema-registry
kubectl -n $NAMESPACE get pod
```

### Strimzi

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
kubectl -n $NAMESPACE rollout status -w deployment/strimzi-cluster-operator
kubectl -n $NAMESPACE get pod
```

```bash
helm delete strimzi-kafka-operator
```

#$ helm install --name my-release --set logLevel=DEBUG,fullReconciliationIntervalMs=240000 strimzi/strimzi-kafka-operator

#kafkaConnect.image.registry 	Override default Kafka Connect image registry 	nil
#kafkaConnect.image.repository 	Override default Kafka Connect image repository 	nil
#kafkaConnect.image.name 	Kafka Connect image name 	kafka
#kafkaConnect.image.tagPrefix 	Override default Kafka Connect image tag prefix 	nil

### debezium

```bash
kubectl -n mysql port-forward service/mysql 3306:3306
cat deploy/sql/mysql_schema_employees.sql | mysql --host=127.0.0.1 --port=3306 --user=root --password=root
cat deploy/sql/mysql_dump_employees.sql   | mysql --host=127.0.0.1 --port=3306 --user=root --password=root --database=test
echo "select count(*) from test.employees" | mysql --host=127.0.0.1 --port=3306 --user=root --password=root --database=test
```

```bash
mysql --host=127.0.0.1 --port=3306 --user=root --password=root --database=test
```

### create secret
```bash
NAMESPACE=debezium
kubectl create ns ${NAMESPACE}
kubectl -n ${NAMESPACE} create secret generic docker-access-secret \
  --from-file=.dockerconfigjson=${HOME}/.docker/config.json \
  --type=kubernetes.io/dockerconfigjson
```

```yaml
apiVersion: v1
kind: Secret
metadata:
  namespace: debezium
  name: docker-access-secret
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: cat ~/.docker/config.json | base64
```

```bash
NAMESPACE="debezium"
kubectl create namespace "${NAMESPACE}"
kubectl -n $NAMESPACE apply -f debezium-connect.yaml
sleep 10
kubectl -n $NAMESPACE rollout status -w deployment/debezium-connect
kubectl -n $NAMESPACE get pod

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
    envsubst )
```

```bash
rpk topic consume --offset=300000 --num=1 SERVER5432.test.employees
```

### sink

```bash
kubectl -n clickhouse port-forward service/clickhouse-clickhouse 9000:9000
cat deploy/sql/clickhouse_schema_employees.sql | clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password
echo "desc test.employees" | clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password
```

```bash
clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password --database=test
```

### create secret
```bash
NAMESPACE="sink"
kubectl create namespace "${NAMESPACE}"
kubectl -n ${NAMESPACE} create secret generic docker-access-secret \
  --from-file=.dockerconfigjson=${HOME}/.docker/config.json \
  --type=kubernetes.io/dockerconfigjson
```

```yaml
apiVersion: v1
kind: Secret
metadata:
  namespace: debezium
  name: docker-access-secret
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: cat ~/.docker/config.json | base64
```

```bash
BASE=$(pwd)
mvn clean compile package
rm -f ${BASE}/deploy/k8s/artefacts/*.tgz
(cd ${BASE}/deploy/libs; find . -name '*.jar' | xargs tar czvf ${BASE}/deploy/k8s/artefacts/libs.tgz)
(cd ${BASE}/target;      find . -name '*.jar' | xargs tar czvf ${BASE}/deploy/k8s/artefacts/sink.tgz)
```

```bash
NAMESPACE="sink"
kubectl create namespace "${NAMESPACE}"
kubectl -n $NAMESPACE apply -f sink-connect.yaml
sleep 5
echo -n "Building"
while kubectl -n $NAMESPACE get pod/sink-connect-build > /dev/null 2>&1; do
  echo -n "."
  sleep 1 
done
echo "done"
sleep 5
kubectl -n $NAMESPACE rollout status -w deployment/sink-connect
kubectl -n $NAMESPACE get pod
sleep 5
kubectl -n $NAMESPACE apply -f <( \
  cat sink-connector-avro.yaml | \
    CLICKHOUSE_HOST="clickhouse-clickhouse.clickhouse" \
    CLICKHOUSE_PORT=8123 \
    CLICKHOUSE_USER="clickhouse_operator" \
    CLICKHOUSE_PASSWORD="clickhouse_operator_password" \
    CLICKHOUSE_TABLE="employees" \
    CLICKHOUSE_DATABASE="test" \
    BUFFER_COUNT=10000 \
    TOPICS="SERVER5432.test.employees" \
    TOPICS_TABLE_MAP="SERVER5432.test.employees:employees" \
    REGISTRY_URL="http://schema-registry.registry:8080/apis/registry/v2" \
    envsubst )
```

```bash
kubectl -n registry port-forward service/schema-registry 8080:8080
firefox http://localhost:8080/ui/artifacts
```

