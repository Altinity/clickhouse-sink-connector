ARG GRAFANA_VERSION
FROM grafana/grafana:${GRAFANA_VERSION}

COPY ./config/dashboard.yml /etc/grafana/provisioning/dashboards
COPY ./config/datasource.yml /etc/grafana/provisioning/datasources
#COPY ./config/debezium-dashboard.json /var/lib/grafana/dashboards/debezium-dashboard.json
COPY ./config/altinity_sink_connector.json /var/lib/grafana/dashboards/altinity_sink_connector.json
#COPY ./config/debezium-mysql-connector.json /var/lib/grafana/dashboards/dashboards/debezium-mysql-connector.json
