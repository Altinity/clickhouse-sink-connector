Sink Connector Config
OpenJDK 11.0.14.1 

-Xms256M, -Xmx2G,

JMX metrics of sink connector are exposed through the port

The JMX_exporter docker image scrapes the JMX metrics from the sink connector
The metrics can be read through the following URL
http://localhost:9072/metrics

A Grafana dashboard is included to view JMX metrics.
