https://docs.redpanda.com/docs/quickstart/kubernetes-qs-minikube
use CERT-MANAGER v 1.4

kubectl create namespace redpanda
kubectl -n redpanda create -f redpanda-cluster.yaml

