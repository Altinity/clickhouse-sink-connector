kubectl delete namespace sink
kubectl create namespace sink

kubectl apply -f sink-connect.yaml -n sink
kubectl apply -f sink-connector-avro.yaml -n sink

kubectl apply -f sink-pod-avro.yaml -n sink
