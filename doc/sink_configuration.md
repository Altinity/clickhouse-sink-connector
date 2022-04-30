All the Sink Connector configuration variables are documented here.

`topics`: Kafka topics delimited by comma.\
`clickhouse.topic2table.map`: Map of Kafka topics to table names, <topic_name1>:<table_name1>,<topic_name2>:<table_name2>
This variable will override the default mapping of topics to table names.

`store.kafka.metadata`: If set to true, kafka metadata columns will be added to Clickhouse.


This is a sample configuration that's used in creating the connector using the Kafka connect REST API.


    `"topics": "${TOPICS}",
    "clickhouse.topic2table.map": "${TOPICS_TABLE_MAP}",
    "clickhouse.server.url": "${CLICKHOUSE_HOST}",
    "clickhouse.server.user": "${CLICKHOUSE_USER}",
    "clickhouse.server.pass": "${CLICKHOUSE_PASSWORD}",
    "clickhouse.server.database": "${CLICKHOUSE_DATABASE}",
    "clickhouse.server.port": ${CLICKHOUSE_PORT},
    "clickhouse.table.name": "${CLICKHOUSE_TABLE}",
    "key.converter": "io.apicurio.registry.utils.converter.AvroConverter",
    "value.converter": "io.apicurio.registry.utils.converter.AvroConverter",

    "key.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
    "key.converter.apicurio.registry.auto-register": "true",
    "key.converter.apicurio.registry.find-latest": "true",

    "value.converter.apicurio.registry.url": "http://schemaregistry:8080/apis/registry/v2",
    "value.converter.apicurio.registry.auto-register": "true",
    "value.converter.apicurio.registry.find-latest": "true",
    "store.kafka.metadata": true,
    "topic.creation.default.partitions": 3,

    "store.raw.data": true,
    "store.raw.data.column": "raw_data"`