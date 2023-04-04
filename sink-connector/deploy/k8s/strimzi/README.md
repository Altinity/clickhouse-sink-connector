# Start Kind with local docker registry
`./local-kind-registry.sh`

# Create namespace
`kubectl create namespace altinity`

# Install Strimzi cluster
`kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n altinity`

# Build local Sink connector using the script
`./build-strimzi-sink.sh`

# Install kafka in cluster using Strimzi
`kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-persistent-single.yaml -n altinity`

# Tag and push the image to Kind container registry
`docker tag altinity/clickhouse-kafka-sink-connector:latest localhost:5001/altinity/clickhouse-kafka-sink-connector:latest` \
`docker push localhost:5001/altinity/clickhouse-kafka-sink-connector:latest`

# Install Clickhouse sink connector
`kubectl apply -f clickhouse-sink-cluster.yaml -n altinity`

# To Delete the connector
`kubectl delete kafkaconnect clickhouse-connect-cluster -n altinity`
# References
[1] https://strimzi.io/blog/2020/01/27/deploying-debezium-with-kafkaconnector-resource/
