### Build Sink Connector from sources.


Requirements
- Java JDK 17 (https://openjdk.java.net/projects/jdk/11/)
- Maven (mvn) (https://maven.apache.org/download.cgi)
- Docker and Docker-compose

Install JDK(For Mac)
```
brew install openjdk@17
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.11/libexec/openjdk.jdk/Contents/Home/
mvn -v
# verify it's actual openjdk 17 used and continue with steps
```

1. Clone the ClickHouse Sink connector repository:
```bash
git clone git@github.com:Altinity/clickhouse-sink-connector.git
```

2. Build the ClickHouse Sink connector Library:
This builds the requirement for sink connector lightweight`<sink-connector-library-version>0.0.8</sink-connector-library-version>`

```bash
cd sink-connector
mvn install -DskipTests=true
```

3. Build the ClickHouse Lightweight connector:
```bash
cd ../sink-connector-lightweight
mvn install -DskipTests=true
```

The JAR file will be created in the  `target` directory.

### Local Environment setup(IntelliJ)

Run the following script to start MYSQL and Clickhouse docker containers.

```
clickhouse-sink-connector/sink-connector-lightweight/docker$ ./startMySQLCHLocalSinkDev.sh 
```

Setup run configuration and set the **main class** to

```
com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication
```
**Arguments:**
```
sink-connector-lightweight/docker/config_local.yml -Dlog4j.debug=true  -Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG
```

![image](https://github.com/user-attachments/assets/00b0e9ff-f622-42d1-943d-568dd8706145)
