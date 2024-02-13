[![License](http://img.shields.io/:license-apache%202.0-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Sink Connector(Kafka version) tests](https://github.com/Altinity/clickhouse-sink-connector/actions/workflows/sink-connector-kafka-tests.yml/badge.svg)](https://github.com/Altinity/clickhouse-sink-connector/actions/workflows/sink-connector-kafka-tests.yml)
[![Sink Connector(Light-weight) Tests](https://github.com/Altinity/clickhouse-sink-connector/actions/workflows/sink-connector-lightweight-tests.yml/badge.svg)](https://github.com/Altinity/clickhouse-sink-connector/actions/workflows/sink-connector-lightweight-tests.yml)
<a href="https://join.slack.com/t/altinitydbworkspace/shared_invite/zt-w6mpotc1-fTz9oYp0VM719DNye9UvrQ">
  <img src="https://img.shields.io/static/v1?logo=slack&logoColor=959DA5&label=Slack&labelColor=333a41&message=join%20conversation&color=3AC358" alt="AltinityDB Slack" />
</a>
<img alt="Docker Pulls" src="https://img.shields.io/docker/pulls/altinityinfra/clickhouse-sink-connector">
### Latest Releases
| Component            | Docker Tag                                                                              |
|----------------------|-----------------------------------------------------------------------------------------|
| Kafka Sink Connector | altinity/clickhouse-sink-connector:latest                                               |
| Lightweight          | altinityinfra/clickhouse-sink-connector:408-97b1d3d83ef93c1b76a2b1c4d9c544dc67fbbec3-lt |
|                      |                                                                                         |

# Altinity Sink Connector for ClickHouse

The Altinity Sink Connector moves data automatically from 
transactional database tables in MySQL and PostgreSQL to ClickHouse
for analysis. 

## Features

* Initial data dump and load 
* Change data capture of new transactions using Debezium
* Automatic loading into ClickHouse
* Sources: Support for MySQL, PostgreSQL (other databases experimental)
* Target: Support for ClickHouse ReplacingMergeTree
* Able to recover/restart from failures on source or target
* Handle upstream schema changes automatically
* Checksum-based table comparisons
* Scalable to 1000s of tables
* Multiple deployment models
  * Lightweight: single process that transfers from source to target (prod)
  * Kafka: separate source and target processes using Kafka as transport (experimental)
* Distribution as [Docker](https://hub.docker.com/layers/altinityinfra/clickhouse-sink-connector/408-97b1d3d83ef93c1b76a2b1c4d9c544dc67fbbec3-lt/images/sha256-d134bc05e50df7f63025e776ab6e3216c6622cd159eb0f2d459ea2ce8975f396?context=explore)
 container

## Getting Started

See [QuickStart Guide(Lightweight)](doc/quickstart.md).

## Blog Articles

First two are good tutorials on MySQL and PostgreSQL respectively. 

- [Altinity Sink connector for ClickHouse](https://altinity.com/blog/fast-mysql-to-clickhouse-replication-announcing-the-altinity-sink-connector-for-clickhouse)
- [Replicating PostgreSQL to ClickHouse](https://altinity.com/blog/replicating-data-from-postgresql-to-clickhouse-with-the-altinity-sink-connector)
- [ClickHouse as an analytic extension for MySQL](https://altinity.com/blog/using-clickhouse-as-an-analytic-extension-for-mysql?utm_campaign=Brand&utm_content=224583767&utm_medium=social&utm_source=linkedin&hss_channel=lcp-10955938)

## Reference Documentation

### General 

* [Architecture Overview](doc/architecture.md)
* [Lightweight Sink Connect CLI](doc/sink_connector_cli.md)
* [Mutable Data Handling](doc/mutable_data.md)

### Installation

* [Sink Connector Setup(Kafka)](doc/setup.md)
* [Sink Connector Configuration(Kafka & Lightweight)](doc/configuration.md)
* [Debezium Setup](doc/debezium_setup.md)

### Operations

* [Monitoring](doc/Monitoring.md)
* [Load Testing with Sysbench](doc/Performance.md)

### Development

* [Testing](doc/TESTING.md)

## Roadmap 

See [2024 Roadmap](https://github.com/Altinity/clickhouse-sink-connector/issues/401).

## Help

File an issue or contact us on the Altinity public Slack workspace. Use 
the link on the Slack badge at the top of this page. 

## Contributing

Contributions to the project are welcome in any form. 

* Submit issues documenting feature requests and bugs
* Submit PRs to make changes
* Talk about the project, write blog articles, or give presentations

We recommend that you file an issue before implementing feature additions 
or major fixes. We are happy to provide guidance and encouragement!

## Commercial Support

Altinity is the primary maintainer of the Sink Connector. It is used
together with Altinity.Cloud as well as self-managed ClickHouse
installations.  Altinity.Cloud and is also used in self-managed
installations. Altinity offers a range of software and services related
to ClickHouse and analytic applications built on ClickHouse. 

- [Official website](https://altinity.com/) - Get a high level overview of Altinity and our offerings.
- [Altinity.Cloud](https://altinity.com/cloud-database/) - Run ClickHouse in our cloud or yours.
- [Altinity Support](https://altinity.com/support/) - Get Enterprise-class support for ClickHouse and Sink Connector.
- [Slack](https://altinitydbworkspace.slack.com/join/shared_invite/zt-1togw9b4g-N0ZOXQyEyPCBh_7IEHUjdw#/shared-invite/email) - Talk directly with ClickHouse users and Altinity devs.
- [Contact us](https://hubs.la/Q020sH3Z0) - Contact Altinity with your questions or issues.
- [Free consultation](https://hubs.la/Q020sHkv0) - Get a free consultation with a ClickHouse expert today.
