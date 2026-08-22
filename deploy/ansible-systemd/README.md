# Systemd Deployment for ClickHouse Sink Connector (Lightweight)

An Ansible-based deployment method that runs the lightweight (embedded
Debezium) sink connector as a **systemd user service** — no Docker, no
Kubernetes, no Kafka.

We have been running production connector instances this way
(MySQL → ClickHouse and PostgreSQL → ClickHouse) and are contributing
the templates and the config-building method upstream so others can
reuse them.

## Why systemd user services?

- **No container runtime required** — a JVM and a JAR are the only
  runtime dependencies.
- **Native supervision** — `Restart=always` + `StartLimitBurst` give
  automatic crash recovery without an orchestrator.
- **Journald integration** — `journalctl --user -u sink-connector-<name>`
  for stdout/stderr, while Log4j2 owns file logging and rotation.
- **Least privilege** — runs as an unprivileged user
  (`loginctl enable-linger <user>`), with `NoNewPrivileges`,
  `ProtectSystem=strict`, `ProtectHome=read-only` hardening.
- **Multiple connectors per host** — each deployment gets its own
  directory, config, service unit, and (optionally) its own suffixed
  offset/schema-history tables in ClickHouse.

## Layout on the target host

```
~/sink-connector/<deployment_name>/
  config/
    config.yml        # rendered from templates/config.yml.j2
    log4j2.xml        # rendered from templates/systemd_log4j2.xml.j2
  logs/
    sink-connector.log         # app log (Log4j2, 100MB rolling, 7d)
    sink-connector-error.log   # WARN+ (50MB rolling, 3d)
    sink-connector-metrics.log # metrics (100MB rolling, 7d)
  clickhouse-debezium-embedded-latest.jar   # symlink -> versioned JAR

~/.config/systemd/user/
  sink-connector-<deployment_name>.service
```

The service file always references the
`clickhouse-debezium-embedded-latest.jar` symlink, so upgrading the
connector is: drop the new JAR, repoint the symlink, restart the
service. No service-file edit needed.

## Config building method

All connector configuration is declared as structured inventory data
(one list entry per connector instance) and rendered through a single
Jinja2 template (`templates/config.yml.j2`). This gives you:

- one source of truth per environment (Ansible inventory / group_vars)
- version-aware rendering (property names changed in 2.7.1 — the
  template picks the right ones based on the declared connector version)
- safe defaults for the dangerous knobs
  (`snapshot.mode: schema_only`,
  `schema.history.internal.skip.unparseable.ddl: true`)

### Example inventory

```yaml
sink_connector_deployments:
  - name: orders-sink-prod
    deployment_mode: systemd
    systemd:
      sink_connector_version: "2.8.0"
      # Directory that contains the connector JAR
      local_sink_connector_bin_dir: /opt/sink-connector/bin
      local_jar_filename: clickhouse-debezium-embedded.jar
      # Java (defaults to /usr/bin/java if omitted)
      local_java_bin_dir: /usr/lib/jvm/java-17/bin
      sink_connector_java_opts: "-Xmx8G -Xms8G"
      timezone: "UTC"
      jmx_port: 9127
      java_debug_enabled: false

    mysql:
      host: mysql-source.example.com
      port: 3306
      user: sink_connector_user
      password: "{{ vault_mysql_password }}"     # use Ansible Vault
      server_id: 200
      server_name: orders-prod
      database: "orders,billing"
      source_mysql_timezone: "UTC"
      # table_exclude_list: "orders.tmp_.*"

    clickhouse:
      host: clickhouse-target.example.com
      port: 8123
      user: sink_connector_user
      password: "{{ vault_clickhouse_password }}"
      timezone: "UTC"
      auto_create_tables: "true"

    offset_clickhouse:
      jdbc_url: "jdbc:clickhouse://clickhouse-target.example.com:8123/altinity_sink_connector"
      user: sink_connector_user
      password: "{{ vault_clickhouse_password }}"
      table_name: altinity_sink_connector.replica_source_info

    schema_history_clickhouse:
      jdbc_url: "jdbc:clickhouse://clickhouse-target.example.com:8123/altinity_sink_connector"
      user: sink_connector_user
      password: "{{ vault_clickhouse_password }}"
      table_name: altinity_sink_connector.replicate_schema_history

    sink_connector:
      cli_port: 7000
      metrics_port: 8083
      snapshot_mode: schema_only
      skip_unparseable_ddl: true
      thread_pool_size: 10
      max_batch_size: 10000
```

When running more than one connector on the same ClickHouse host, give
each deployment its own suffixed tables
(`replica_source_info_<name>`, `replicate_schema_history_<name>`) and
unique `cli_port` / `metrics_port` / `jmx_port` values.

### Running

```bash
ansible-playbook deploy.yml -i inventory/ --diff
# deploy configs without (re)starting the services:
ansible-playbook deploy.yml -i inventory/ --diff -e start_service=false
```

Where `deploy.yml` simply applies this role for the target host group.

## Operational notes learned in production

1. **Never set `snapshot.mode: initial` on an existing target** — it
   drops and recreates all replicated tables. `schema_only` is the safe
   default and is what this template defaults to.
2. **Keep `skip.unparseable.ddl: true`** — otherwise vendor-specific
   DDL (e.g. MySQL 8 functional indexes) crashes the connector.
3. **Log4j2 must be the sole owner of log rotation.** We originally ran
   logrotate alongside Log4j2 rolling files and got ghost files and
   triple logging. The unit file sends stdout/stderr to the journal and
   Log4j2 handles files — do not add logrotate on top.
4. **`Restart=always` interacts with disaster recovery** — if you need
   to stop a connector for manual intervention, `systemctl --user stop`
   it first; otherwise systemd restarts it within 30 seconds.
5. **Large heaps take time to stop** — with multi-GB heaps a stop can
   take minutes; size `TimeoutStopSec` accordingly if you tune it.
6. **Each connector needs a unique `database.server.id`** — duplicates
   cause binlog conflicts on the source.

## Files

| File | Purpose |
|------|---------|
| `defaults/main.yml` | Role defaults (symlink name, safety defaults) |
| `tasks/main.yml` | Deployment flow: dirs → configs → unit → enable/start → verify |
| `templates/config.yml.j2` | Connector `config.yml` builder (MySQL source) |
| `templates/systemd_sink_connector.service.j2` | systemd user unit |
| `templates/systemd_log4j2.xml.j2` | Log4j2 rolling-file logging config |
