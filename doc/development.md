### Build Sink Connector from sources.


Requirements
- Java JDK 17 (https://openjdk.java.net/projects/jdk/11/)
- Maven (mvn) (https://maven.apache.org/download.cgi)
- Docker and Docker-compose


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