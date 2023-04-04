
# debezium
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

# sink
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

