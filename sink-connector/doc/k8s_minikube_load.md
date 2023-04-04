minikube start --addons=registry && minikube addons list

### cert-manager
docker image pull quay.io/jetstack/cert-manager-controller:v1.4.0
docker image pull quay.io/jetstack/cert-manager-cainjector:v1.4.0
docker image pull quay.io/jetstack/cert-manager-webhook:v1.4.0

minikube image load quay.io/jetstack/cert-manager-controller:v1.4.0
minikube image load quay.io/jetstack/cert-manager-cainjector:v1.4.0
minikube image load quay.io/jetstack/cert-manager-webhook:v1.4.0

### redpanda
docker image pull   vectorized/redpanda-operator:v21.11.15
docker image pull   vectorized/redpanda:v21.11.15
docker image pull   vectorized/configurator:v21.11.15
docker image pull   gcr.io/kubebuilder/kube-rbac-proxy:v0.8.0

minikube image load vectorized/redpanda-operator:v21.11.15
minikube image load vectorized/redpanda:v21.11.15
minikube image load vectorized/configurator:v21.11.15
minikube image load gcr.io/kubebuilder/kube-rbac-proxy:v0.8.0

### mysql-operator
docker   image pull mysql/mysql-operator:8.0.29-2.0.4
minikube image load mysql/mysql-operator:8.0.29-2.0.4

### mysql
docker   image pull mysql/mysql-server:8.0.29
docker   image pull mysql/mysql-router:8.0.29

minikube image load mysql/mysql-server:8.0.29
minikube image load mysql/mysql-router:8.0.29 

### clickhouse-operator
docker   image pull altinity/clickhouse-operator:0.18.4
docker   image pull altinity/metrics-exporter:0.18.4

minikube image load altinity/clickhouse-operator:0.18.4
minikube image load altinity/metrics-exporter:0.18.4

### clickhouse
docker   image pull clickhouse/clickhouse-server:22.3.5.5
minikube image load clickhouse/clickhouse-server:22.3.5.5

### schema-registry
docker   image pull apicurio/apicurio-registry-mem:2.0.0.Final
minikube image load apicurio/apicurio-registry-mem:2.0.0.Final 

### strimzi-operator
docker   image pull quay.io/strimzi/operator:0.28.0
docker   image pull quay.io/strimzi/kaniko-executor:0.28.0

minikube image load quay.io/strimzi/operator:0.28.0
minikube image load quay.io/strimzi/kaniko-executor:0.28.0 

### load images from local registry into minikube registry
kubectl -n kube-system port-forward service/registry 5000:80 > /dev/null 2>&1 &
KUBECTL_PORT_FORWARD_PID=$!
sleep 10

### clickhouse sink connector
docker image tag altinity/clickhouse-kafka-sink-connector:latest localhost:5000/altinity/clickhouse-kafka-sink-connector:latest
docker image push localhost:5000/altinity/clickhouse-kafka-sink-connector:latest

### debezium source connector
docker image tag altinity/debezium-mysql-source-connector:latest localhost:5000/altinity/debezium-mysql-source-connector:latest
docker image push localhost:5000/altinity/debezium-mysql-source-connector:latest

kill $KUBECTL_PORT_FORWARD_PID
