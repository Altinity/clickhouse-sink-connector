package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ClickHouseIcebergWriter writes a list of ClickHouseStruct records
 * to an Apache Iceberg table.
 *
 * <p>This class converts Kafka Connect Schema types to Iceberg schema
 * and writes data using Iceberg's DataWriter. It supports both
 * creating new tables and appending to existing ones.
 *
 * <p>Supports multiple catalog types:
 * <ul>
 *   <li>REST - Connect to Iceberg REST catalog (e.g., http://localhost:8181)</li>
 *   <li>HADOOP - Use filesystem-based catalog with warehouse path</li>
 *   <li>HIVE - Connect to Hive Metastore</li>
 *   <li>JDBC - Use JDBC-based catalog</li>
 * </ul>
 */
public class ClickHouseIcebergWriter implements Closeable {

    private static final Logger log = LogManager.getLogger(
            ClickHouseIcebergWriter.class);

    /**
     * Supported Iceberg catalog types.
     */
    public enum CatalogType {
        REST,
        HADOOP,
        HIVE,
        JDBC
    }

    /**
     * Hadoop configuration for Iceberg.
     */
    private final Configuration hadoopConf;

    /**
     * Iceberg catalog instance.
     */
    private Catalog catalog;

    /**
     * File format to use for data files.
     */
    private FileFormat fileFormat = FileFormat.PARQUET;

    /**
     * Constructor for REST catalog.
     *
     * <p>Use this constructor to connect to an Iceberg REST catalog service.
     *
     * @param catalogUri   the REST catalog URI (e.g., "http://localhost:8181")
     * @param warehouseLocation the warehouse location (can be S3, GCS, or local path)
     * @param catalogType  should be CatalogType.REST
     */
    public ClickHouseIcebergWriter(String catalogUri,
                                   String warehouseLocation,
                                   CatalogType catalogType) {
        this(catalogUri, warehouseLocation, catalogType, new HashMap<>());
    }

    /**
     * Constructor with additional catalog properties.
     *
     * @param catalogUri         the catalog URI or warehouse path
     * @param warehouseLocation  the warehouse location
     * @param catalogType        the type of catalog
     * @param additionalProps    additional catalog properties
     */
    public ClickHouseIcebergWriter(String catalogUri,
                                   String warehouseLocation,
                                   CatalogType catalogType,
                                   Map<String, String> additionalProps) {
        this.hadoopConf = new Configuration();
        initializeCatalog(catalogUri, warehouseLocation, catalogType,
                additionalProps);
    }

    /**
     * Constructor with Hadoop configuration.
     *
     * @param catalogUri         the catalog URI or warehouse path
     * @param warehouseLocation  the warehouse location
     * @param catalogType        the type of catalog
     * @param hadoopConf         the Hadoop configuration
     * @param additionalProps    additional catalog properties
     */
    public ClickHouseIcebergWriter(String catalogUri,
                                   String warehouseLocation,
                                   CatalogType catalogType,
                                   Configuration hadoopConf,
                                   Map<String, String> additionalProps) {
        this.hadoopConf = hadoopConf;
        initializeCatalog(catalogUri, warehouseLocation, catalogType,
                additionalProps);
    }

    /**
     * Initializes the Iceberg catalog based on type.
     *
     * @param catalogUri        the catalog URI
     * @param warehouseLocation the warehouse location
     * @param catalogType       the catalog type
     * @param additionalProps   additional properties
     */
    private void initializeCatalog(String catalogUri,
                                   String warehouseLocation,
                                   CatalogType catalogType,
                                   Map<String, String> additionalProps) {
        Map<String, String> catalogProperties = new HashMap<>(additionalProps);

        switch (catalogType) {
            case REST:
                catalogProperties.put(CatalogProperties.CATALOG_IMPL,
                        "org.apache.iceberg.rest.RESTCatalog");
                catalogProperties.put(CatalogProperties.URI, catalogUri);
                if (warehouseLocation != null && !warehouseLocation.isEmpty()) {
                    catalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION,
                            warehouseLocation);
                }
                break;
            case HADOOP:
                catalogProperties.put(CatalogProperties.CATALOG_IMPL,
                        "org.apache.iceberg.hadoop.HadoopCatalog");
                catalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION,
                        warehouseLocation != null ? warehouseLocation : catalogUri);
                break;
            case HIVE:
                catalogProperties.put(CatalogProperties.CATALOG_IMPL,
                        "org.apache.iceberg.hive.HiveCatalog");
                catalogProperties.put(CatalogProperties.URI, catalogUri);
                if (warehouseLocation != null && !warehouseLocation.isEmpty()) {
                    catalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION,
                            warehouseLocation);
                }
                break;
            case JDBC:
                catalogProperties.put(CatalogProperties.CATALOG_IMPL,
                        "org.apache.iceberg.jdbc.JdbcCatalog");
                catalogProperties.put(CatalogProperties.URI, catalogUri);
                if (warehouseLocation != null && !warehouseLocation.isEmpty()) {
                    catalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION,
                            warehouseLocation);
                }
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported catalog type: " + catalogType);
        }

        this.catalog = CatalogUtil.buildIcebergCatalog(
                "iceberg_catalog", catalogProperties, hadoopConf);
        log.info("Initialized Iceberg {} catalog with URI: {}",
                catalogType, catalogUri);
    }

    /**
     * Creates a writer for REST catalog.
     *
     * @param restUri           the REST catalog URI (e.g., "http://localhost:8181")
     * @param warehouseLocation the warehouse location (e.g., "s3://bucket/warehouse")
     * @return the configured writer
     */
    public static ClickHouseIcebergWriter forRestCatalog(String restUri,
                                                         String warehouseLocation) {
        return new ClickHouseIcebergWriter(restUri, warehouseLocation,
                CatalogType.REST);
    }

    /**
     * Creates a writer for REST catalog with bearer token.
     *
     * @param restUri           the REST catalog URI
     * @param warehouseLocation the warehouse location
     * @param bearerToken       the bearer token for authentication
     * @return the configured writer
     */
    public static ClickHouseIcebergWriter forRestCatalogWithToken(
            String restUri,
            String warehouseLocation,
            String bearerToken) {
        Map<String, String> props = new HashMap<>();
        props.put("token", bearerToken);
        return new ClickHouseIcebergWriter(restUri, warehouseLocation,
                CatalogType.REST, props);
    }

    /**
     * Creates a writer for REST catalog with OAuth2 credential.
     *
     * @param restUri           the REST catalog URI
     * @param warehouseLocation the warehouse location
     * @param credential        OAuth2 client credential
     * @return the configured writer
     */
    public static ClickHouseIcebergWriter forRestCatalogWithCredential(
            String restUri,
            String warehouseLocation,
            String credential) {
        Map<String, String> props = new HashMap<>();
        props.put("credential", credential);
        return new ClickHouseIcebergWriter(restUri, warehouseLocation,
                CatalogType.REST, props);
    }

    /**
     * Creates a writer for Hadoop filesystem catalog.
     *
     * @param warehouseLocation the warehouse path (e.g., "/path/to/warehouse"
     *                          or "s3://bucket/warehouse")
     * @return the configured writer
     */
    public static ClickHouseIcebergWriter forHadoopCatalog(
            String warehouseLocation) {
        return new ClickHouseIcebergWriter(warehouseLocation, warehouseLocation,
                CatalogType.HADOOP);
    }

    /**
     * Creates a writer for Hive Metastore catalog.
     *
     * @param metastoreUri      the Hive Metastore URI
     *                          (e.g., "thrift://localhost:9083")
     * @param warehouseLocation the warehouse location
     * @return the configured writer
     */
    public static ClickHouseIcebergWriter forHiveCatalog(String metastoreUri,
                                                         String warehouseLocation) {
        return new ClickHouseIcebergWriter(metastoreUri, warehouseLocation,
                CatalogType.HIVE);
    }

    /**
     * Creates a writer from ClickHouseSinkConnectorConfig.
     *
     * <p>This factory method reads all Iceberg configuration from the
     * connector config and creates an appropriately configured writer.
     *
     * @param config the connector configuration
     * @return the configured writer
     */
    public static ClickHouseIcebergWriter fromConfig(
            ClickHouseSinkConnectorConfig config) {

        String catalogTypeStr = config.getString(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_CATALOG_TYPE
                        .toString());
        String catalogUri = config.getString(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_CATALOG_URI
                        .toString());
        String warehouseLocation = config.getString(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_WAREHOUSE_LOCATION
                        .toString());

        // Build additional properties
        Map<String, String> props = new HashMap<>();

        // Add bearer token if provided (for REST catalog authentication)
        String token = config.getPassword(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_CATALOG_TOKEN
                        .toString()).value();
        if (token != null && !token.isEmpty()) {
            props.put("token", token);
        }

        // Add credential if provided (for OAuth2 client credentials flow)
        String credential = config.getPassword(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_CATALOG_CREDENTIAL
                        .toString()).value();
        if (credential != null && !credential.isEmpty()) {
            props.put("credential", credential);
        }

        // Add S3 configuration if provided
        String s3AccessKey = config.getString(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_S3_ACCESS_KEY
                        .toString());
        String s3SecretKey = config.getPassword(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_S3_SECRET_KEY
                        .toString()).value();
        String s3Region = config.getString(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_S3_REGION
                        .toString());
        String s3Endpoint = config.getString(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_S3_ENDPOINT
                        .toString());

        if (s3AccessKey != null && !s3AccessKey.isEmpty()) {
            props.put("s3.access-key-id", s3AccessKey);
        }
        if (s3SecretKey != null && !s3SecretKey.isEmpty()) {
            props.put("s3.secret-access-key", s3SecretKey);
        }
        if (s3Region != null && !s3Region.isEmpty()) {
            props.put("s3.region", s3Region);
            props.put("client.region", s3Region);
        }
        if (s3Endpoint != null && !s3Endpoint.isEmpty()) {
            props.put("s3.endpoint", s3Endpoint);
        }

        // Parse catalog type
        CatalogType catalogType;
        try {
            catalogType = CatalogType.valueOf(catalogTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid catalog type '{}', defaulting to REST",
                    catalogTypeStr);
            catalogType = CatalogType.REST;
        }

        return new ClickHouseIcebergWriter(catalogUri, warehouseLocation,
                catalogType, props);
    }

    /**
     * Checks if Iceberg writing is enabled in the configuration.
     *
     * @param config the connector configuration
     * @return true if Iceberg is enabled
     */
    public static boolean isEnabled(ClickHouseSinkConnectorConfig config) {
        return config.getBoolean(
                ClickHouseSinkConnectorConfigVariables.ENABLE_ICEBERG
                        .toString());
    }

    /**
     * Gets the Iceberg namespace from configuration.
     *
     * @param config the connector configuration
     * @return the namespace (database) name
     */
    public static String getNamespace(ClickHouseSinkConnectorConfig config) {
        return config.getString(
                ClickHouseSinkConnectorConfigVariables.ICEBERG_NAMESPACE
                        .toString());
    }

    /**
     * Writes a list of ClickHouseStruct records to an Iceberg table.
     *
     * <p>If the table doesn't exist, it will be created. Records are
     * appended to the table.
     *
     * @param records   the list of ClickHouseStruct records to write
     * @param namespace the Iceberg namespace (database)
     * @param tableName the table name
     * @return the number of records written
     * @throws IOException if an I/O error occurs
     */
    public int writeToIceberg(List<ClickHouseStruct> records,
                              String namespace,
                              String tableName) throws IOException {
        if (records == null || records.isEmpty()) {
            log.warn("No records to write to Iceberg");
            return 0;
        }

        // Build Iceberg schema from the first record
        Schema icebergSchema = buildIcebergSchema(records.get(0));
        if (icebergSchema == null) {
            log.error("Failed to build Iceberg schema from records");
            return 0;
        }

        TableIdentifier tableId = TableIdentifier.of(namespace, tableName);
        Table table = getOrCreateTable(tableId, icebergSchema);

        return writeRecords(table, records, icebergSchema);
    }

    /**
     * Gets an existing table or creates a new one.
     *
     * @param tableId the table identifier
     * @param schema  the Iceberg schema
     * @return the Iceberg table
     */
    private Table getOrCreateTable(TableIdentifier tableId, Schema schema) {
        if (catalog.tableExists(tableId)) {
            return catalog.loadTable(tableId);
        }

        // Create table with default partition spec (unpartitioned)
        Map<String, String> properties = new HashMap<>();
        properties.put(TableProperties.FORMAT_VERSION, "2");
        properties.put(TableProperties.DEFAULT_FILE_FORMAT,
                fileFormat.name().toLowerCase());

        return catalog.createTable(tableId, schema,
                PartitionSpec.unpartitioned(), properties);
    }

    /**
     * Writes records to an Iceberg table.
     *
     * @param table   the Iceberg table
     * @param records the records to write
     * @param schema  the Iceberg schema
     * @return the number of records written
     * @throws IOException if an I/O error occurs
     */
    private int writeRecords(Table table, List<ClickHouseStruct> records,
                             Schema schema) throws IOException {

        String dataFileLocation = table.location() + "/data/"
                + UUID.randomUUID() + ".parquet";
        OutputFile outputFile = table.io().newOutputFile(dataFileLocation);

        FileAppender<Record> appender = Parquet.write(outputFile)
                .schema(schema)
                .createWriterFunc(GenericParquetWriter::buildWriter)
                .build();

        int recordCount = 0;
        try {
            for (ClickHouseStruct record : records) {
                Record icebergRecord = convertToIcebergRecord(record, schema);
                if (icebergRecord != null) {
                    appender.add(icebergRecord);
                    recordCount++;
                }
            }
        } finally {
            appender.close();
        }

        // Build DataFile metadata
        DataFile dataFile = DataFiles.builder(PartitionSpec.unpartitioned())
                .withPath(dataFileLocation)
                .withFileSizeInBytes(appender.length())
                .withRecordCount(recordCount)
                .withFormat(FileFormat.PARQUET)
                .build();

        // Commit the data file to the table
        AppendFiles appendFiles = table.newAppend();
        appendFiles.appendFile(dataFile);
        appendFiles.commit();

        log.info("Written {} records to Iceberg table: {}",
                recordCount, table.name());
        return recordCount;
    }

    /**
     * Builds an Iceberg schema from a ClickHouseStruct record.
     *
     * @param record the ClickHouseStruct to derive schema from
     * @return the Iceberg schema, or null if unable to build
     */
    public Schema buildIcebergSchema(ClickHouseStruct record) {
        Struct dataStruct = record.getAfterStruct();
        if (dataStruct == null) {
            dataStruct = record.getBeforeStruct();
        }
        if (dataStruct == null) {
            log.error("Both afterStruct and beforeStruct are null");
            return null;
        }

        List<Types.NestedField> fields = new ArrayList<>();
        int fieldId = 1;

        // Add CDC metadata fields
        fields.add(Types.NestedField.optional(fieldId++, "_cdc_op",
                Types.StringType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_topic",
                Types.StringType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_partition",
                Types.IntegerType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_offset",
                Types.LongType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_timestamp",
                Types.LongType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_ts_ms",
                Types.LongType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_database",
                Types.StringType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_file",
                Types.StringType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_pos",
                Types.LongType.get()));
        fields.add(Types.NestedField.optional(fieldId++, "_gtid",
                Types.LongType.get()));

        // Add data fields from the struct
        List<Field> kafkaFields = dataStruct.schema().fields();
        for (Field field : kafkaFields) {
            Types.NestedField icebergField = convertField(fieldId++, field);
            if (icebergField != null) {
                fields.add(icebergField);
            }
        }

        return new Schema(fields);
    }

    /**
     * Converts a Kafka Connect field to an Iceberg field.
     *
     * @param fieldId the field ID
     * @param field   the Kafka Connect field
     * @return the Iceberg nested field
     */
    private Types.NestedField convertField(int fieldId, Field field) {
        String fieldName = sanitizeFieldName(field.name());
        org.apache.kafka.connect.data.Schema.Type type = field.schema().type();
        boolean isOptional = field.schema().isOptional();
        String schemaName = field.schema().name();

        Type icebergType = mapKafkaTypeToIceberg(type, schemaName);

        if (isOptional) {
            return Types.NestedField.optional(fieldId, fieldName, icebergType);
        } else {
            return Types.NestedField.required(fieldId, fieldName, icebergType);
        }
    }

    /**
     * Maps a Kafka Connect type to an Iceberg type.
     *
     * @param type       the Kafka Connect type
     * @param schemaName the logical schema name (for special types)
     * @return the Iceberg type
     */
    private Type mapKafkaTypeToIceberg(
            org.apache.kafka.connect.data.Schema.Type type,
            String schemaName) {

        // Handle logical types first
        if (schemaName != null) {
            switch (schemaName) {
                case "io.debezium.time.Date":
                case "org.apache.kafka.connect.data.Date":
                    return Types.DateType.get();
                case "io.debezium.time.Time":
                case "io.debezium.time.MicroTime":
                case "io.debezium.time.NanoTime":
                case "org.apache.kafka.connect.data.Time":
                    return Types.TimeType.get();
                case "io.debezium.time.Timestamp":
                case "io.debezium.time.MicroTimestamp":
                case "io.debezium.time.NanoTimestamp":
                case "io.debezium.time.ZonedTimestamp":
                case "org.apache.kafka.connect.data.Timestamp":
                    return Types.TimestampType.withZone();
                case "org.apache.kafka.connect.data.Decimal":
                    return Types.DecimalType.of(38, 18);
                default:
                    break;
            }
        }

        // Map basic types
        switch (type) {
            case INT8:
            case INT16:
            case INT32:
                return Types.IntegerType.get();
            case INT64:
                return Types.LongType.get();
            case FLOAT32:
                return Types.FloatType.get();
            case FLOAT64:
                return Types.DoubleType.get();
            case BOOLEAN:
                return Types.BooleanType.get();
            case STRING:
                return Types.StringType.get();
            case BYTES:
                return Types.BinaryType.get();
            case ARRAY:
                // For arrays, use string representation
                return Types.StringType.get();
            case MAP:
                // For maps, use string representation
                return Types.StringType.get();
            case STRUCT:
                // For nested structs, use string representation
                return Types.StringType.get();
            default:
                log.warn("Unknown type {}, defaulting to string", type);
                return Types.StringType.get();
        }
    }

    /**
     * Converts a ClickHouseStruct to an Iceberg Record.
     *
     * @param record the ClickHouseStruct to convert
     * @param schema the Iceberg schema
     * @return the Iceberg Record, or null if conversion fails
     */
    public Record convertToIcebergRecord(ClickHouseStruct record,
                                         Schema schema) {
        GenericRecord icebergRecord = GenericRecord.create(schema);

        // Set CDC metadata
        if (record.getCdcOperation() != null) {
            icebergRecord.setField("_cdc_op",
                    record.getCdcOperation().getOperation());
        }
        icebergRecord.setField("_topic", record.getTopic());
        icebergRecord.setField("_partition", record.getKafkaPartition());
        icebergRecord.setField("_offset", record.getKafkaOffset());
        icebergRecord.setField("_timestamp", record.getTimestamp());
        icebergRecord.setField("_ts_ms", record.getTs_ms());
        icebergRecord.setField("_database", record.getDatabase());
        icebergRecord.setField("_file", record.getFile());
        icebergRecord.setField("_pos", record.getPos());
        icebergRecord.setField("_gtid", record.getGtid());

        // Get data struct (prefer afterStruct, fall back to beforeStruct)
        Struct dataStruct = record.getAfterStruct();
        if (dataStruct == null) {
            dataStruct = record.getBeforeStruct();
        }
        if (dataStruct == null) {
            log.warn("Both afterStruct and beforeStruct are null for record");
            return icebergRecord;
        }

        // Convert data fields
        List<Field> fields = dataStruct.schema().fields();
        for (Field field : fields) {
            String fieldName = sanitizeFieldName(field.name());
            try {
                Object value = dataStruct.get(field);
                Object convertedValue = convertValue(value, field.schema());
                icebergRecord.setField(fieldName, convertedValue);
            } catch (Exception e) {
                log.debug("Error converting field {}: {}", fieldName,
                        e.getMessage());
            }
        }

        return icebergRecord;
    }

    /**
     * Converts a Kafka Connect value to an Iceberg-compatible value.
     *
     * @param value  the value to convert
     * @param schema the Kafka Connect schema
     * @return the converted value
     */
    private Object convertValue(Object value,
                                org.apache.kafka.connect.data.Schema schema) {
        if (value == null) {
            return null;
        }

        org.apache.kafka.connect.data.Schema.Type type = schema.type();
        String schemaName = schema.name();

        // Handle logical types
        if (schemaName != null) {
            switch (schemaName) {
                case "io.debezium.time.Date":
                case "org.apache.kafka.connect.data.Date":
                    // Days since epoch
                    if (value instanceof Integer) {
                        return LocalDate.ofEpochDay((Integer) value);
                    } else if (value instanceof java.util.Date) {
                        return ((java.util.Date) value).toInstant()
                                .atZone(ZoneOffset.UTC).toLocalDate();
                    }
                    break;
                case "io.debezium.time.Time":
                    // Milliseconds since midnight
                    if (value instanceof Integer) {
                        return LocalTime.ofNanoOfDay(
                                ((Integer) value) * 1_000_000L);
                    }
                    break;
                case "io.debezium.time.MicroTime":
                    // Microseconds since midnight
                    if (value instanceof Long) {
                        return LocalTime.ofNanoOfDay((Long) value * 1_000L);
                    }
                    break;
                case "io.debezium.time.Timestamp":
                    // Milliseconds since epoch
                    if (value instanceof Long) {
                        return Instant.ofEpochMilli((Long) value)
                                .atOffset(ZoneOffset.UTC);
                    }
                    break;
                case "io.debezium.time.MicroTimestamp":
                    // Microseconds since epoch
                    if (value instanceof Long) {
                        long micros = (Long) value;
                        return Instant.ofEpochSecond(
                                        micros / 1_000_000,
                                        (micros % 1_000_000) * 1_000)
                                .atOffset(ZoneOffset.UTC);
                    }
                    break;
                case "org.apache.kafka.connect.data.Decimal":
                    if (value instanceof BigDecimal) {
                        return value;
                    } else if (value instanceof byte[]) {
                        return new BigDecimal(new String((byte[]) value));
                    }
                    break;
                default:
                    break;
            }
        }

        // Handle basic types
        switch (type) {
            case INT8:
            case INT16:
            case INT32:
                return ((Number) value).intValue();
            case INT64:
                return ((Number) value).longValue();
            case FLOAT32:
                return ((Number) value).floatValue();
            case FLOAT64:
                return ((Number) value).doubleValue();
            case BOOLEAN:
                return value;
            case STRING:
                return value.toString();
            case BYTES:
                if (value instanceof byte[]) {
                    return ByteBuffer.wrap((byte[]) value);
                } else if (value instanceof ByteBuffer) {
                    return value;
                } else if (value instanceof BigDecimal) {
                    return ((BigDecimal) value);
                }
                return ByteBuffer.wrap(value.toString().getBytes());
            case ARRAY:
                // Convert array to string representation
                return value.toString();
            case MAP:
                // Convert map to string representation
                return value.toString();
            case STRUCT:
                // Convert nested struct to string representation
                if (value instanceof Struct) {
                    return structToString((Struct) value);
                }
                return value.toString();
            default:
                return value.toString();
        }
    }

    /**
     * Converts a Kafka Connect Struct to a string representation.
     *
     * @param struct the Struct to convert
     * @return the string representation
     */
    private String structToString(Struct struct) {
        Map<String, Object> map = new HashMap<>();
        for (Field field : struct.schema().fields()) {
            try {
                Object value = struct.get(field);
                if (value instanceof Struct) {
                    map.put(field.name(), structToString((Struct) value));
                } else {
                    map.put(field.name(), value);
                }
            } catch (Exception e) {
                log.debug("Error getting field value for {}: {}",
                        field.name(), e.getMessage());
            }
        }
        return map.toString();
    }

    /**
     * Sanitizes a field name to be a valid Iceberg field name.
     *
     * @param name the original field name
     * @return the sanitized field name
     */
    private String sanitizeFieldName(String name) {
        if (name == null || name.isEmpty()) {
            return "_unnamed";
        }
        // Replace invalid characters with underscores
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_");
        // Ensure it doesn't start with a digit
        if (Character.isDigit(sanitized.charAt(0))) {
            sanitized = "_" + sanitized;
        }
        return sanitized;
    }

    /**
     * Sets the file format for data files.
     *
     * @param fileFormat the file format (PARQUET, ORC, AVRO)
     */
    public void setFileFormat(FileFormat fileFormat) {
        this.fileFormat = fileFormat;
    }

    /**
     * Gets the current file format.
     *
     * @return the file format
     */
    public FileFormat getFileFormat() {
        return fileFormat;
    }

    /**
     * Gets the Iceberg catalog.
     *
     * @return the catalog
     */
    public Catalog getCatalog() {
        return catalog;
    }

    /**
     * Sets a custom catalog.
     *
     * @param catalog the catalog to use
     */
    public void setCatalog(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Gets the catalog name.
     *
     * @return the catalog name
     */
    public String getCatalogName() {
        return catalog != null ? catalog.name() : null;
    }

    /**
     * Creates a partitioned table with the given partition spec.
     *
     * @param namespace     the namespace (database)
     * @param tableName     the table name
     * @param schema        the Iceberg schema
     * @param partitionSpec the partition specification
     * @return the created table
     */
    public Table createPartitionedTable(String namespace,
                                        String tableName,
                                        Schema schema,
                                        PartitionSpec partitionSpec) {
        TableIdentifier tableId = TableIdentifier.of(namespace, tableName);

        Map<String, String> properties = new HashMap<>();
        properties.put(TableProperties.FORMAT_VERSION, "2");
        properties.put(TableProperties.DEFAULT_FILE_FORMAT,
                fileFormat.name().toLowerCase());

        return catalog.createTable(tableId, schema, partitionSpec, properties);
    }

    /**
     * Drops a table if it exists.
     *
     * @param namespace the namespace (database)
     * @param tableName the table name
     * @return true if the table was dropped, false if it didn't exist
     */
    public boolean dropTable(String namespace, String tableName) {
        TableIdentifier tableId = TableIdentifier.of(namespace, tableName);
        return catalog.dropTable(tableId);
    }

    /**
     * Checks if a table exists.
     *
     * @param namespace the namespace (database)
     * @param tableName the table name
     * @return true if the table exists
     */
    public boolean tableExists(String namespace, String tableName) {
        TableIdentifier tableId = TableIdentifier.of(namespace, tableName);
        return catalog.tableExists(tableId);
    }

    @Override
    public void close() throws IOException {
        if (catalog instanceof Closeable) {
            ((Closeable) catalog).close();
        }
    }
}

