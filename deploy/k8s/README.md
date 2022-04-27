
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

```bash
VERSION="v0.25.18"
wget https://github.com/derailed/k9s/releases/download/$VERSION/k9s_Linux_x86_64.tar.gz
install k9s ~/bin/minikube
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
helm repo add redpanda https://charts.vectorized.io/ && helm repo update
VERSION=$(curl -s https://api.github.com/repos/redpanda-data/redpanda/releases/latest | jq -r .tag_name)
NAMESPACE=redpanda-system
echo "Going to install redpanda-operator version $VERSION into namespace $NAMESPACE"
echo "Install CRDs"
kubectl apply -k https://github.com/redpanda-data/redpanda/src/go/k8s/config/crd?ref=$VERSION
echo "Install operator"
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
