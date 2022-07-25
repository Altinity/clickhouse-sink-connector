ARG GRAFANA_VERSION
FROM grafana/grafana:${GRAFANA_VERSION}

COPY ./config/dashboard.yml /etc/grafana/provisioning/dashboards
COPY ./config/datasource.yml /etc/grafana/provisioning/datasources
COPY ./config/debezium-dashboard.json /var/lib/grafana/dashboards/debezium-dashboard.json