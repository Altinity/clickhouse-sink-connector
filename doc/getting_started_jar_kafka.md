- The following are the steps to install the Sink Connector Kafka JAR 


**Download or Build the Sink Connector Kafka JAR** \
   The Sink Connector Kafka JAR is available
    in the releases artifacts or can be built from the source code.
     ```cd sink-connector
        mvn clean package
    ```
    The JAR file is available in the target directory.

**Identify or Create a Plugins Directory** \
   Kafka Connect loads connectors from the plugin.path defined in its configuration.
   If you haven't configured a plugins directory, create one. For example:
   ```
   /usr/local/kafka/connectors/
   ```
**Place the JAR File in the Plugins Directory** \
   Copy the connector JAR file and its dependencies into a subdirectory inside the plugin path:
   ```
   /usr/local/kafka/connectors/my-sink-connector/
   ```
**Configure Kafka Connect to Use the Plugins Directory** \\
   Edit your Kafka Connect worker properties file (connect-distributed.properties or connect-standalone.properties):
   properties
   ```
   plugin.path=/usr/local/kafka/connectors/
   ```
**Restart Kafka Connect**
   Restart Kafka Connect to load the new connector:\

   *For distributed mode:*

   ```
   bin/connect-distributed.sh config/connect-distributed.properties
   ```
   
   *For standalone mode:*
   ```
   bin/connect-standalone.sh config/connect-standalone.properties <connector-config-file>
   ```
**Verify Connector Installation** 

   Use the Kafka Connect REST API to verify that the connector is installed:
   ```
   curl -sS localhost:8083/connector-plugins | jq .
   ```
   This command should list your newly installed connector.

**Configure and Start the Sink Connector**
  
Follow the steps below to configure and start the Sink Connector:
  
  [Kafka Quick Start Guide](quickstart_kafka.md) 

**Monitor the Connector**
   Use the REST API to monitor the status of your connector:
   ```
   curl -sS localhost:8083/connectors/my-sink-connector/status | jq .
   ```