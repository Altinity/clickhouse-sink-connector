## Start local Docker registry
```bash
docker run -d -p 5000:5000 --restart=always --name registry registry:2
```

## RedPanda

1. Create a Kubernetes cluster with minikube
2. Install cert-manager
3. Install the Redpanda operator
4. Install and connect to a Redpanda cluster
5. Start streaming
6. Clean up

```bash
SRC_ROOT=.
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

Wait to start
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

Wait to start
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
kubectl -n $NAMESPACE create -f "${SRC_ROOT}/deploy/k8s/redpanda-internal.yaml"
```
To deploy 3-node cluster
```bash
kubectl -n $NAMESPACE create -f redpanda-external.yaml
```

Wait to start
```bash
kubectl -n $NAMESPACE wait pod/redpanda-0 --for condition=Ready=True
kubectl -n $NAMESPACE get statefulset
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

Wait to start
```bash
kubectl -n $NAMESPACE rollout status -w deployment/mysql-operator
kubectl -n $NAMESPACE get pod
```

### mysql cluster
```bash
NAMESPACE="mysql"
kubectl create ns "${NAMESPACE}"
kubectl -n "${NAMESPACE}" apply -f "${SRC_ROOT}/deploy/k8s/mysql.yaml"
```

Wait to start
```bash
kubectl -n $NAMESPACE rollout status -w statefulset/mysql
kubectl -n $NAMESPACE get statefulset
```

Fill MySQL with data

Port forward to make MySQL accessible 
```bash
kubectl -n mysql port-forward service/mysql 3306:3306 > /dev/null 2>&1 &
KUBECTL_PORT_FORWARD_PID=$!
sleep 10
```
Load
```bash
cat "${SRC_ROOT}/deploy/sql/mysql_schema_employees.sql" | mysql --host=127.0.0.1 --port=3306 --user=root --password=root
cat "${SRC_ROOT}/deploy/sql/mysql_dump_employees.sql"   | mysql --host=127.0.0.1 --port=3306 --user=root --password=root --database=test
echo "select count(*) from test.employees" | mysql --host=127.0.0.1 --port=3306 --user=root --password=root --database=test
kill $KUBECTL_PORT_FORWARD_PID
```

```bash
mysql --host=127.0.0.1 --port=3306 --user=root --password=root --database=test
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
  cat "${SRC_ROOT}/deploy/k8s/clickhouse-operator-install-template.yaml" | \
    OPERATOR_NAMESPACE="clickhouse" \
    OPERATOR_IMAGE="altinity/clickhouse-operator:0.18.4" \
    METRICS_EXPORTER_IMAGE="altinity/metrics-exporter:0.18.4" \
    IMAGE_PULL_POLICY="IfNotPresent" \
    envsubst )
```

Wait to start
```bash
kubectl -n $NAMESPACE rollout status -w deployment/clickhouse-operator
kubectl -n $NAMESPACE get pod
```

### clickhouse-cluster

```bash
NAMESPACE=clickhouse
kubectl create namespace $NAMESPACE
kubectl -n $NAMESPACE apply -f "${SRC_ROOT}/deploy/k8s/clickhouse.yaml"
```

Wait to start
```bash
kubectl -n $NAMESPACE rollout status -w statefulset/chi-clickhouse-cluster-0-0
kubectl -n $NAMESPACE get statefulset
```

### schema registry(Confluent)

```bash
NAMESPACE="registry"
kubectl create namespace ${NAMESPACE}
kubectl -n ${NAMESPACE} apply -f "${SRC_ROOT}/deploy/k8s/schema-registry.yaml"
```

Wait to start
```bash
kubectl -n $NAMESPACE rollout status -w deployment/schema-registry
kubectl -n $NAMESPACE get pod
```

Ensure schema registry is empty
Port forward to make schema registry accessible
```bash
kubectl -n registry port-forward service/schema-registry 8080:8080 > /dev/null 2>&1 &
KUBECTL_PORT_FORWARD_PID=$!
sleep 10
```
```bash
firefox http://localhost:8080/ui/artifacts
sleep 10
kill $KUBECTL_PORT_FORWARD_PID
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

Wait to start
```bash
kubectl -n $NAMESPACE rollout status -w deployment/strimzi-cluster-operator
kubectl -n $NAMESPACE get pod
```

### debezium

```bash
NAMESPACE="debezium"
kubectl create namespace "${NAMESPACE}"
kubectl -n $NAMESPACE apply -f "${SRC_ROOT}/deploy/k8s/debezium-connect.yaml"
sleep 10
kubectl -n $NAMESPACE rollout status -w deployment/debezium-connect
kubectl -n $NAMESPACE get pod
sleep 10
kubectl -n $NAMESPACE apply -f <( \
  cat "${SRC_ROOT}/deploy/k8s/debezium-connector-avro.yaml" | \
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

Ensure schema registry is **NOT empty**

Port forward to make schema registry accessible
```bash
kubectl -n registry port-forward service/schema-registry 8080:8080 > /dev/null 2>&1 &
KUBECTL_PORT_FORWARD_PID=$!
sleep 10
```
```bash
firefox http://localhost:8080/ui/artifacts
sleep 10
kill $KUBECTL_PORT_FORWARD_PID
```

Ensure Kafka records

```bash
kubectl -n redpanda exec redpanda-0 -c redpanda -- rpk topic consume --offset=300000 --num=1 SERVER5432.test.employees

```
```bash
rpk topic consume --offset=300000 --num=1 SERVER5432.test.employees
```

### sink

### create schema
Port forward to make ClickHouse accessible
```bash
kubectl -n clickhouse port-forward service/clickhouse-clickhouse 9000:9000 > /dev/null 2>&1 &
KUBECTL_PORT_FORWARD_PID=$!
sleep 10
```
```bash
cat "${SRC_ROOT}/deploy/sql/clickhouse_schema_employees.sql" | clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password
echo "desc employees" | clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password --database=test
kill $KUBECTL_PORT_FORWARD_PID
```

```bash
clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password --database=test
```

### sink-connector
```bash
NAMESPACE="sink"
kubectl create namespace "${NAMESPACE}"
kubectl -n $NAMESPACE apply -f "${SRC_ROOT}/deploy/k8s/sink-connect.yaml"
sleep 10
#echo -n "Building"
#while kubectl -n $NAMESPACE get pod/sink-connect-build > /dev/null 2>&1; do
#  echo -n "."
#  sleep 1 
#done
#echo "done"
#sleep 5
kubectl -n $NAMESPACE rollout status -w deployment/sink-connect
kubectl -n $NAMESPACE get pod
sleep 10
kubectl -n $NAMESPACE apply -f <( \
  cat "${SRC_ROOT}/deploy/k8s/sink-connector-avro.yaml" | \
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

Check data
Port forward to make ClickHouse accessible
```bash
kubectl -n clickhouse port-forward service/clickhouse-clickhouse 9000:9000 > /dev/null 2>&1 &
KUBECTL_PORT_FORWARD_PID=$!
sleep 10
```
Check for data
```bash
echo "desc employees" | clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password --database=test
echo "select count() from employees" | clickhouse-client --host=127.0.0.1 --port=9000 --multiline --multiquery --user=clickhouse_operator --password=clickhouse_operator_password --database=test
```
```bash
kill $KUBECTL_PORT_FORWARD_PID
```
