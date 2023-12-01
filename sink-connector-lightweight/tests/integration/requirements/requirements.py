# These requirements were auto generated
# from software requirements specification (SRS)
# document by TestFlows v1.9.230125.1024636.
# Do not edit by hand but re-generate instead
# using 'tfs requirements generate' command.
from testflows.core import Specification
from testflows.core import Requirement

Heading = Specification.Heading

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of single or multiple tables from [MySQL] database.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data deduplication when it receives the same data twice from [MySQL].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.2.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Select = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[ClickHouse] `SELECT ... FINAL` query SHALL always return exactly same data as [MySQL].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.2.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_OnlyOnceGuarantee = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with only-once guarantee.\n"
        "Block level de-duplication SHALL be used if it is going to replicated tables\n"
        "but the publisher SHALL publish only once.\n"
        "\n"
        "The following cases SHALL be supported:\n"
        "\n"
        "1. [MySQL] database crash\n"
        "2. [MySQL] database event stream provider crash\n"
        "3. [MySQL] restart\n"
        "3. [ClickHouse] server crash\n"
        "4. [Clickhouse] server restart\n"
        "5. [Kafka] server crash\n"
        "6. [Kafka] server restart\n"
        "7. [Debezium] server crash\n"
        "8. [Debezium] server restart\n"
        "9. [Altinity Sink Connector] server crash\n"
        "10. [Altinity Sink Connector] server restart\n"
        "11. [Schemaregistry] server crash\n"
        "12. [Schemaregistry] server restart\n"
        "13. [Zookeeper] read only mode\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.2.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Transactions = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support transactions for replicated [MySQL] tables.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLVersions = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        " \n"
        "[Altinity Sink Connector] SHALL support replication from the following [MySQL] versions:\n"
        "\n"
        "* [MySQL 8.0](https://dev.mysql.com/doc/refman/8.0/en/)\n"
        "\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to\n'
        '"ReplacingMergeTree" [ClickHouse] table engine.\n'
        "\n"
    ),
    link=None,
    level=3,
    num="4.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to\n'
        '"ReplacingMergeTree" [ClickHouse] table engine and virtual column names by default should be "_version" and "_sign".\n'
        "\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.5.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to\n'
        '"ReplicatedReplacingMergeTree" [ClickHouse] table engine.\n'
        "\n"
    ),
    link=None,
    level=3,
    num="4.5.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree_DifferentVersionColumnNames = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to\n'
        '"ReplicatedReplacingMergeTree" [ClickHouse] table engine with different version column names.\n'
        "\n"
    ),
    link=None,
    level=4,
    num="4.5.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_IntegerTypes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with\n"
        "'Integer' data types as they supported by [MySQL].\n"
        "\n"
        "Integer data types connection table:\n"
        "\n"
        "| MySQL              |           ClickHouse            | Min edge value | Max edge value |\n"
        "|:-------------------|:-------------------------------:|----------------|:--------------:|\n"
        "| Bigint             |              Int64              | 2^63           |     2^63-1     |\n"
        "| Bigint Unsigned    |             UInt64              | 0              |     2^64-1     |\n"
        "| Int                |              Int32              | -2147483648    |   2147483647   |\n"
        "| Int Unsigned       |             UInt32              | 0              |   4294967295   |\n"
        "| Mediumint          |              Int32              | -8388608       |    8388607     |\n"
        "| Mediumint Unsigned |             UInt32              | 0              |    16777215    |\n"
        "| Smallint           |              Int16              | -32768         |     32767      |\n"
        "| Smallint Unsigned  |             UInt16              | 0              |     65535      |\n"
        "| Tinyint            |              Int8               | -128           |      127       |\n"
        "| Tinyint Unsigned   |              UInt8              | 0              |      255       |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Decimal = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with\n"
        "'Decimal' data types as they supported by [MySQL].\n"
        "\n"
        "[ClickHouse]'s 'Decimal32(S)', 'Decimal64(S)', 'Decimal128(S)', 'Decimal256(S)' also can be\n"
        "manually used for [MySQL] 'Decimal'.\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL        |  ClickHouse  |\n"
        "|:-------------|:------------:|\n"
        "| Decimal(x,y) | Decimal(x,y) |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Double = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with\n"
        "'Double' data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL        |  ClickHouse  |\n"
        "|:-------------|:------------:|\n"
        "| Double       |   Float64    |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Data' and 'Time'\n"
        "data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL       |       ClickHouse        |\n"
        "|:------------|:-----------------------:|\n"
        "| Date        |         Date32          |\n"
        "| DateTime(6) | DateTime64(6) or String |\n"
        "| DATETIME    |       DateTime64        |\n"
        "| Time        |         String          |\n"
        "| Time(6)     |         String          |\n"
        "| Timestamp   |        DateTime         |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Binary = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] replication of tables that contain columns with 'Binary'\n"
        "data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL        |              ClickHouse              |\n"
        "|:-------------|:------------------------------------:|\n"
        "| Binary       |             String + hex             |\n"
        "| varbinary(*) |             String + hex             |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_String = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'String'\n"
        "data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL        |           ClickHouse            |\n"
        "|:-------------|:-------------------------------:|\n"
        "| Char         | String / LowCardinality(String) |\n"
        "| Text         |             String              |\n"
        "| varbinary(*) |          String + hex           |\n"
        "| varchar(*)   |             String              |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.6.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_BlobTypes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Blob' [MySQL]\n"
        "data types and correctly unhex() them.\n"
        "\n"
        "```sql\n"
        "SELECT unhex(blob_column) FROM our_table FORMAT CSV;\n"
        "```\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL        |           ClickHouse            |\n"
        "|:-------------|:-------------------------------:|\n"
        "| Blob         |          String + hex           |\n"
        "| Longblob     |          String + hex           |\n"
        "| Mediumblob   |          String + hex           |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.7.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Nullable = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with NULL [MySQL]\n"
        "data types if this expected `Nullable(DataType)` construction should be used.\n"
        "\n"
        "For example, [MySQL] `VARCHAR(*)` maps to [ClickHouse] `Nullable(String)` and MySQL\n"
        "`VARCHAR(*) NOT NULL` maps to [ClickHouse] `String`\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.8.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToEnum = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'\n"
        "data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL | ClickHouse |\n"
        "|:------|:----------:|\n"
        "| ENUM  |    ENUM    |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.9.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToString = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'\n"
        "data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL | ClickHouse |\n"
        "|:------|:----------:|\n"
        "| ENUM  |   String   |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.9.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_JSON = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'JSON'\n"
        "data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL | ClickHouse |\n"
        "|:------|:----------:|\n"
        "| JSON  |   String   |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.10.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Year = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Year'\n"
        "data types as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL | ClickHouse |\n"
        "|:------|:----------:|\n"
        "| Year  |   Int32    |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.11.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Bytes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'BIT(m)'\n"
        "data types where m: 2 - 64 as they supported by [MySQL].\n"
        "\n"
        "Data types connection table:\n"
        "\n"
        "| MySQL  | ClickHouse |\n"
        "|:-------|:----------:|\n"
        "| BIT(m) |   String   |\n"
        "\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.6.12.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support new data inserts replication from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.7.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts_PartitionLimits = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support correct data inserts replication from [MySQL] to [CLickHouse] when partition \n"
        "limits are hitting or avoid such situations.\n"
        "\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.7.2.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Updates = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data updates replication from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.7.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Deletes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data deletes replication from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.7.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector]SHALL support the following ways to replicate schema from [MySQL] to [CLickHouse]:\n"
        "* auto-create option\n"
        "* `clickhouse_loader` script\n"
        "* `chump` utility\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.8.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoCreate = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support auto table creation from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.8.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_MultipleAutoCreate = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support auto creation of multiple tables from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.8.2.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoDrop = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP TABLE` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.8.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER` queries or generate an exception if it does not.\n"
        "\n"
        "```sql\n"
        "alter_option: {\n"
        "    table_options\n"
        "  | ADD [COLUMN] col_name column_definition\n"
        "        [FIRST | AFTER col_name]\n"
        "  | ADD [COLUMN] (col_name column_definition,...)\n"
        "  | ADD {INDEX | KEY} [index_name]\n"
        "        [index_type] (key_part,...) [index_option] ...\n"
        "  | ADD {FULLTEXT | SPATIAL} [INDEX | KEY] [index_name]\n"
        "        (key_part,...) [index_option] ...\n"
        "  | ADD [CONSTRAINT [symbol]] PRIMARY KEY\n"
        "        [index_type] (key_part,...)\n"
        "        [index_option] ...\n"
        "  | ADD [CONSTRAINT [symbol]] UNIQUE [INDEX | KEY]\n"
        "        [index_name] [index_type] (key_part,...)\n"
        "        [index_option] ...\n"
        "  | ADD [CONSTRAINT [symbol]] FOREIGN KEY\n"
        "        [index_name] (col_name,...)\n"
        "        reference_definition\n"
        "  | ADD [CONSTRAINT [symbol]] CHECK (expr) [[NOT] ENFORCED]\n"
        "  | DROP {CHECK | CONSTRAINT} symbol\n"
        "  | ALTER {CHECK | CONSTRAINT} symbol [NOT] ENFORCED\n"
        "  | ALGORITHM [=] {DEFAULT | INSTANT | INPLACE | COPY}\n"
        "  | ALTER [COLUMN] col_name {\n"
        "        SET DEFAULT {literal | (expr)}\n"
        "      | SET {VISIBLE | INVISIBLE}\n"
        "      | DROP DEFAULT\n"
        "    }\n"
        "  | ALTER INDEX index_name {VISIBLE | INVISIBLE}\n"
        "  | CHANGE [COLUMN] old_col_name new_col_name column_definition\n"
        "        [FIRST | AFTER col_name]\n"
        "  | [DEFAULT] CHARACTER SET [=] charset_name [COLLATE [=] collation_name]\n"
        "  | CONVERT TO CHARACTER SET charset_name [COLLATE collation_name]\n"
        "  | {DISABLE | ENABLE} KEYS\n"
        "  | {DISCARD | IMPORT} TABLESPACE\n"
        "  | DROP [COLUMN] col_name\n"
        "  | DROP {INDEX | KEY} index_name\n"
        "  | DROP PRIMARY KEY\n"
        "  | DROP FOREIGN KEY fk_symbol\n"
        "  | FORCE\n"
        "  | LOCK [=] {DEFAULT | NONE | SHARED | EXCLUSIVE}\n"
        "  | MODIFY [COLUMN] col_name column_definition\n"
        "        [FIRST | AFTER col_name]\n"
        "  | ORDER BY col_name [, col_name] ...\n"
        "  | RENAME COLUMN old_col_name TO new_col_name\n"
        "  | RENAME {INDEX | KEY} old_index_name TO new_index_name\n"
        "  | RENAME [TO | AS] new_tbl_name\n"
        "  | {WITHOUT | WITH} VALIDATION\n"
        "}\n"
        "```\n"
        "\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.9.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddIndex = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD INDEX` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddKey = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD Key` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddFullText = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD FULLTEXT` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.6.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddSpecial = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD SPECIAL` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.7.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropCheck = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP CHECK` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.8.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropDefault = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP DEFAULT` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.9.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Check = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CHECK` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.10.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Constraint = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CONSTRAINT` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.11.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Index = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER INDEX` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.12.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_CharacterSet = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CHARACTER SET` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.13.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_ConvertToCharacterSet = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CONVERT TO CHARACTER SET` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.14.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Algorithm = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER ALGORITHM` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.15.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Force = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER FORCE` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.16.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Lock = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER LOCK` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.17.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Unlock = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER UNLOCK` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.18.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Validation = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER VALIDATION` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.19.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_NullNotNull = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN NULL/NOT NULL` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.1.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Default = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN DEFAULT` query from [MySQL] to [CLickHouse].\n"
        "\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.1.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_FirstAfter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN FIRST, AFTER` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.1.4",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `ADD COLUMN` query from [MySQL] to [CLickHouse].\n"
        "\n"
        "\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.1.5",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type` query from [MySQL] to [CLickHouse].\n"
        "\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_NullNotNull = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type NULL/NOT NULL` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.2.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Default = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type DEFAULT` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.2.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_FirstAfter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type FIRST, AFTER` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.2.4",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `MODIFY COLUMN` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.2.5",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_NullNotNullOldNew = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `CHANGE COLUMN old_name new_name datatype NULL/NOT NULL` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_FirstAfter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `CHANGE COLUMN FIRST, AFTER` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.3.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `CHANGE COLUMN` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.3.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP COLUMN` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `DROP COLUMN` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.4.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `RENAME COLUMN col1 to col2` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `RENAME COLUMN col1 to col2` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=5,
    num="4.9.20.5.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddConstraint = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD CONSTRAINT` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.21.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropConstraint = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP CONSTRAINT` query from [MySQL] to [CLickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.9.22.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_No = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries to tables\n"
        "with no `PRIMARY KEY`.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.10.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order\n"
        "as simple `PRIMARY KEY` does.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.10.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Composite = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order \n"
        "as composite `PRIMARY KEY` does.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.10.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleUpstreamServers = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] from multiple [MySQL] upstream servers.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.11.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDownstreamServers = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] when using multiple downstream [ClickHouse] servers.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.12.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ArchivalMode = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with archival mode that\n"
        "SHALL ignore deletes for some or all tables in [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.13.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BootstrappingMode = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with \n"
        "bootstrapping mode for the initial replication of very large tables\n"
        "that bypasses event stream by using [MySQL] dump files.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.14.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BinlogPosition = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support ability to start replication to [CLickHouse] \n"
        "from specific [MySQL] binlog position.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.15.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnMappingAndTransformationRules = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with support for\n"
        "defining column mapping and transformations rules.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.16.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnsInconsistency = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] replica table when it has fewer columns.\n"
        "[MySQL] replication to [CLickHouse] is not available in all other cases of columns inconsistency .\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.17.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Latency = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with latency as close as possible to real-time.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.18.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] more than 100,000 rows/sec.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="4.19.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance_LargeDailyDataVolumes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with large daily data volumes of at least 20-30TB per day.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.19.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Settings_Topic2TableMap = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `clickhouse.topic2table.map` setting for mapping [MySQL] tables to\n"
        "[ClickHouse] tables that have different name.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="4.20.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Prometheus = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support expose data transfer representation to [Prometheus] service.\n"
        "\n"
        "[SRS]: #srs\n"
        "[MySQL]: #mysql\n"
        "[Prometheus]: https://prometheus.io/\n"
        "[ClickHouse]: https://clickhouse.com/en/docs\n"
        "[Altinity Sink Connector]: https://github.com/Altinity/clickhouse-sink-connector\n"
        "[Git]: https://git-scm.com/\n"
        "[GitLab]: https://gitlab.com\n"
    ),
    link=None,
    level=3,
    num="4.21.1",
)

SRS030_MySQL_to_ClickHouse_Replication = Specification(
    name="SRS030 MySQL to ClickHouse Replication",
    description=None,
    author=None,
    date=None,
    status=None,
    approved_by=None,
    approved_date=None,
    approved_version=None,
    version=None,
    group=None,
    type=None,
    link=None,
    uid=None,
    parent=None,
    children=None,
    headings=(
        Heading(name="Introduction", level=1, num="1"),
        Heading(name="Terminology", level=1, num="2"),
        Heading(name="SRS", level=2, num="2.1"),
        Heading(name="MySQL", level=2, num="2.2"),
        Heading(name="Feature Diagram", level=1, num="3"),
        Heading(name="Requirements", level=1, num="4"),
        Heading(name="General", level=2, num="4.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication",
            level=3,
            num="4.1.1",
        ),
        Heading(name="Consistency", level=2, num="4.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency",
            level=3,
            num="4.2.1",
        ),
        Heading(name="Multiple MySQL Masters", level=3, num="4.2.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.MultipleMySQLMasters",
            level=4,
            num="4.2.2.1",
        ),
        Heading(name="Deduplication", level=3, num="4.2.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication",
            level=4,
            num="4.2.3.1",
        ),
        Heading(name="Selects", level=3, num="4.2.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select",
            level=4,
            num="4.2.4.1",
        ),
        Heading(name="Only Once Guarantee", level=3, num="4.2.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee",
            level=4,
            num="4.2.5.1",
        ),
        Heading(name="Transactions", level=2, num="4.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions",
            level=3,
            num="4.3.1",
        ),
        Heading(name="Supported Versions", level=2, num="4.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions",
            level=3,
            num="4.4.1",
        ),
        Heading(name="Supported Storage Engines", level=2, num="4.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree",
            level=3,
            num="4.5.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames",
            level=4,
            num="4.5.1.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree",
            level=3,
            num="4.5.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames",
            level=4,
            num="4.5.2.1",
        ),
        Heading(name="Data Types", level=2, num="4.6"),
        Heading(name="Integer Types", level=3, num="4.6.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes",
            level=4,
            num="4.6.1.1",
        ),
        Heading(name="Decimal", level=3, num="4.6.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal",
            level=4,
            num="4.6.2.1",
        ),
        Heading(name="Double", level=3, num="4.6.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double",
            level=4,
            num="4.6.3.1",
        ),
        Heading(name="DateTime", level=3, num="4.6.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime",
            level=4,
            num="4.6.4.1",
        ),
        Heading(name="Binary", level=3, num="4.6.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary",
            level=4,
            num="4.6.5.1",
        ),
        Heading(name="String", level=3, num="4.6.6"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String",
            level=4,
            num="4.6.6.1",
        ),
        Heading(name="Blob Types", level=3, num="4.6.7"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes",
            level=4,
            num="4.6.7.1",
        ),
        Heading(name="Nullable", level=3, num="4.6.8"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable",
            level=4,
            num="4.6.8.1",
        ),
        Heading(name="Enum", level=3, num="4.6.9"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum",
            level=4,
            num="4.6.9.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString",
            level=4,
            num="4.6.9.2",
        ),
        Heading(name="JSON", level=3, num="4.6.10"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON",
            level=4,
            num="4.6.10.1",
        ),
        Heading(name="Year", level=3, num="4.6.11"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year",
            level=4,
            num="4.6.11.1",
        ),
        Heading(name="Bytes", level=3, num="4.6.12"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes",
            level=4,
            num="4.6.12.1",
        ),
        Heading(name="Queries", level=2, num="4.7"),
        Heading(name="Test Feature Diagram", level=3, num="4.7.1"),
        Heading(name="Inserts", level=3, num="4.7.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts",
            level=4,
            num="4.7.2.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits",
            level=5,
            num="4.7.2.1.1",
        ),
        Heading(name="Updates", level=3, num="4.7.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates",
            level=4,
            num="4.7.3.1",
        ),
        Heading(name="Deletes", level=3, num="4.7.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes",
            level=4,
            num="4.7.4.1",
        ),
        Heading(name="Table Schema Creation", level=2, num="4.8"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation",
            level=3,
            num="4.8.1",
        ),
        Heading(name="Auto Create", level=3, num="4.8.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate",
            level=4,
            num="4.8.2.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate",
            level=4,
            num="4.8.2.2",
        ),
        Heading(name="Auto Drop", level=3, num="4.8.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop",
            level=4,
            num="4.8.3.1",
        ),
        Heading(name="Alter", level=2, num="4.9"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter",
            level=3,
            num="4.9.1",
        ),
        Heading(name="Test `ALTER` Feature Diagram", level=3, num="4.9.2"),
        Heading(name="Test multiple `ALTER` Feature Diagram", level=3, num="4.9.3"),
        Heading(name="Add Index", level=3, num="4.9.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex",
            level=4,
            num="4.9.4.1",
        ),
        Heading(name="Add Key", level=3, num="4.9.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey",
            level=4,
            num="4.9.5.1",
        ),
        Heading(name="Add FullText", level=3, num="4.9.6"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText",
            level=4,
            num="4.9.6.1",
        ),
        Heading(name="Add Special", level=3, num="4.9.7"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial",
            level=4,
            num="4.9.7.1",
        ),
        Heading(name="Drop Check", level=3, num="4.9.8"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck",
            level=4,
            num="4.9.8.1",
        ),
        Heading(name="Drop Default", level=3, num="4.9.9"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault",
            level=4,
            num="4.9.9.1",
        ),
        Heading(name="Check", level=3, num="4.9.10"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check",
            level=4,
            num="4.9.10.1",
        ),
        Heading(name="Constraint", level=3, num="4.9.11"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint",
            level=4,
            num="4.9.11.1",
        ),
        Heading(name="Index", level=3, num="4.9.12"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index",
            level=4,
            num="4.9.12.1",
        ),
        Heading(name="Character Set", level=3, num="4.9.13"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet",
            level=4,
            num="4.9.13.1",
        ),
        Heading(name="Convert To Character Set", level=3, num="4.9.14"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet",
            level=4,
            num="4.9.14.1",
        ),
        Heading(name="Algorithm", level=3, num="4.9.15"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm",
            level=4,
            num="4.9.15.1",
        ),
        Heading(name="Force", level=3, num="4.9.16"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force",
            level=4,
            num="4.9.16.1",
        ),
        Heading(name="Lock", level=3, num="4.9.17"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock",
            level=4,
            num="4.9.17.1",
        ),
        Heading(name="Unlock", level=3, num="4.9.18"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock",
            level=4,
            num="4.9.18.1",
        ),
        Heading(name="Validation", level=3, num="4.9.19"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation",
            level=4,
            num="4.9.19.1",
        ),
        Heading(name="Columns", level=3, num="4.9.20"),
        Heading(name="Add", level=4, num="4.9.20.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add",
            level=5,
            num="4.9.20.1.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull",
            level=5,
            num="4.9.20.1.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default",
            level=5,
            num="4.9.20.1.3",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter",
            level=5,
            num="4.9.20.1.4",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple",
            level=5,
            num="4.9.20.1.5",
        ),
        Heading(name="Modify", level=4, num="4.9.20.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify",
            level=5,
            num="4.9.20.2.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull",
            level=5,
            num="4.9.20.2.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default",
            level=5,
            num="4.9.20.2.3",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter",
            level=5,
            num="4.9.20.2.4",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple",
            level=5,
            num="4.9.20.2.5",
        ),
        Heading(name="Change", level=4, num="4.9.20.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew",
            level=5,
            num="4.9.20.3.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter",
            level=5,
            num="4.9.20.3.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple",
            level=5,
            num="4.9.20.3.3",
        ),
        Heading(name="Drop", level=4, num="4.9.20.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop",
            level=5,
            num="4.9.20.4.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple",
            level=5,
            num="4.9.20.4.2",
        ),
        Heading(name="Rename", level=4, num="4.9.20.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename",
            level=5,
            num="4.9.20.5.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple",
            level=5,
            num="4.9.20.5.2",
        ),
        Heading(name="Add Constraint", level=3, num="4.9.21"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint",
            level=4,
            num="4.9.21.1",
        ),
        Heading(name="Drop Constraint", level=3, num="4.9.22"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint",
            level=4,
            num="4.9.22.1",
        ),
        Heading(name="Primary Key", level=2, num="4.10"),
        Heading(name="No Primary Key", level=3, num="4.10.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No",
            level=4,
            num="4.10.1.1",
        ),
        Heading(name="Simple Primary Key", level=3, num="4.10.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple",
            level=4,
            num="4.10.2.1",
        ),
        Heading(name="Composite Primary Key", level=3, num="4.10.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite",
            level=4,
            num="4.10.3.1",
        ),
        Heading(name="Multiple Upstream Servers", level=2, num="4.11"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers",
            level=3,
            num="4.11.1",
        ),
        Heading(name="Multiple Downstream Servers", level=2, num="4.12"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers",
            level=3,
            num="4.12.1",
        ),
        Heading(name="Archival Mode", level=2, num="4.13"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode",
            level=3,
            num="4.13.1",
        ),
        Heading(name="Bootstrapping Mode", level=2, num="4.14"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode",
            level=3,
            num="4.14.1",
        ),
        Heading(name="Binlog Position", level=2, num="4.15"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition",
            level=3,
            num="4.15.1",
        ),
        Heading(name="Column Mapping And Transformation Rules", level=2, num="4.16"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules",
            level=3,
            num="4.16.1",
        ),
        Heading(name="Columns Inconsistency", level=2, num="4.17"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency",
            level=3,
            num="4.17.1",
        ),
        Heading(name="Latency", level=2, num="4.18"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency",
            level=3,
            num="4.18.1",
        ),
        Heading(name="Performance ", level=2, num="4.19"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance",
            level=3,
            num="4.19.1",
        ),
        Heading(name="Large Daily Data Volumes", level=3, num="4.19.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes",
            level=4,
            num="4.19.2.1",
        ),
        Heading(name="Settings", level=2, num="4.20"),
        Heading(name="clickhouse.topic2table.map", level=3, num="4.20.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap",
            level=4,
            num="4.20.1.1",
        ),
        Heading(name="Prometheus ", level=2, num="4.21"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus",
            level=3,
            num="4.21.1",
        ),
    ),
    requirements=(
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Select,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_OnlyOnceGuarantee,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Transactions,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLVersions,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree_DifferentVersionColumnNames,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_IntegerTypes,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Decimal,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Double,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Binary,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_String,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_BlobTypes,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Nullable,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToEnum,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToString,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_JSON,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Year,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Bytes,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts_PartitionLimits,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Updates,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Deletes,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoCreate,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_MultipleAutoCreate,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoDrop,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddIndex,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddKey,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddFullText,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddSpecial,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropCheck,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropDefault,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Check,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Constraint,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Index,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_CharacterSet,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_ConvertToCharacterSet,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Algorithm,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Force,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Lock,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Unlock,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Validation,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_NullNotNull,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Default,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_FirstAfter,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Multiple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_NullNotNull,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Default,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_FirstAfter,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Multiple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_NullNotNullOldNew,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_FirstAfter,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_Multiple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop_Multiple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename_Multiple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddConstraint,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropConstraint,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_No,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Composite,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleUpstreamServers,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDownstreamServers,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ArchivalMode,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BootstrappingMode,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BinlogPosition,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnMappingAndTransformationRules,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnsInconsistency,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Latency,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance_LargeDailyDataVolumes,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Settings_Topic2TableMap,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Prometheus,
    ),
    content="""
# SRS030 MySQL to ClickHouse Replication
# Software Requirements Specification

## Table of Contents

* 1 [Introduction](#introduction)
* 2 [Terminology](#terminology)
  * 2.1 [SRS](#srs)
  * 2.2 [MySQL](#mysql)
* 3 [Feature Diagram](#feature-diagram)
* 4 [Requirements](#requirements)
  * 4.1 [General](#general)
    * 4.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication](#rqsrs-030clickhousemysqltoclickhousereplication)
  * 4.2 [Consistency](#consistency)
    * 4.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency](#rqsrs-030clickhousemysqltoclickhousereplicationconsistency)
    * 4.2.2 [Multiple MySQL Masters](#multiple-mysql-masters)
      * 4.2.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.MultipleMySQLMasters](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencymultiplemysqlmasters)
    * 4.2.3 [Deduplication](#deduplication)
      * 4.2.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencydeduplication)
    * 4.2.4 [Selects](#selects)
      * 4.2.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencyselect)
    * 4.2.5 [Only Once Guarantee](#only-once-guarantee)
      * 4.2.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee](#rqsrs-030clickhousemysqltoclickhousereplicationonlyonceguarantee)
  * 4.3 [Transactions](#transactions)
    * 4.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions](#rqsrs-030clickhousemysqltoclickhousereplicationtransactions)
  * 4.4 [Supported Versions](#supported-versions)
    * 4.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlversions)
  * 4.5 [Supported Storage Engines](#supported-storage-engines)
    * 4.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplacingmergetree)
      * 4.5.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplacingmergetreevirtualcolumnnames)
    * 4.5.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplicatedreplacingmergetree)
      * 4.5.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplicatedreplacingmergetreedifferentversioncolumnnames)
  * 4.6 [Data Types](#data-types)
    * 4.6.1 [Integer Types](#integer-types)
      * 4.6.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesintegertypes)
    * 4.6.2 [Decimal](#decimal)
      * 4.6.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdecimal)
    * 4.6.3 [Double](#double)
      * 4.6.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdouble)
    * 4.6.4 [DateTime](#datetime)
      * 4.6.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdatetime)
    * 4.6.5 [Binary](#binary)
      * 4.6.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesbinary)
    * 4.6.6 [String](#string)
      * 4.6.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesstring)
    * 4.6.7 [Blob Types](#blob-types)
      * 4.6.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesblobtypes)
    * 4.6.8 [Nullable](#nullable)
      * 4.6.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesnullable)
    * 4.6.9 [Enum](#enum)
      * 4.6.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesenumtoenum)
      * 4.6.9.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesenumtostring)
    * 4.6.10 [JSON](#json)
      * 4.6.10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesjson)
    * 4.6.11 [Year](#year)
      * 4.6.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesyear)
    * 4.6.12 [Bytes](#bytes)
      * 4.6.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesbytes)
  * 4.7 [Queries](#queries)
    * 4.7.1 [Test Feature Diagram](#test-feature-diagram)
    * 4.7.2 [Inserts](#inserts)
      * 4.7.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesinserts)
        * 4.7.2.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesinsertspartitionlimits)
    * 4.7.3 [Updates](#updates)
      * 4.7.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesupdates)
    * 4.7.4 [Deletes](#deletes)
      * 4.7.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesdeletes)
  * 4.8 [Table Schema Creation](#table-schema-creation)
    * 4.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreation)
    * 4.8.2 [Auto Create](#auto-create)
      * 4.8.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationautocreate)
      * 4.8.2.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationmultipleautocreate)
    * 4.8.3 [Auto Drop](#auto-drop)
      * 4.8.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationautodrop)
  * 4.9 [Alter](#alter)
    * 4.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter](#rqsrs-030clickhousemysqltoclickhousereplicationalter)
    * 4.9.2 [Test `ALTER` Feature Diagram](#test-alter-feature-diagram)
    * 4.9.3 [Test multiple `ALTER` Feature Diagram](#test-multiple-alter-feature-diagram)
    * 4.9.4 [Add Index](#add-index)
      * 4.9.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddindex)
    * 4.9.5 [Add Key](#add-key)
      * 4.9.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddkey)
    * 4.9.6 [Add FullText](#add-fulltext)
      * 4.9.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddfulltext)
    * 4.9.7 [Add Special](#add-special)
      * 4.9.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddspecial)
    * 4.9.8 [Drop Check](#drop-check)
      * 4.9.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropcheck)
    * 4.9.9 [Drop Default](#drop-default)
      * 4.9.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropdefault)
    * 4.9.10 [Check](#check)
      * 4.9.10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check](#rqsrs-030clickhousemysqltoclickhousereplicationaltercheck)
    * 4.9.11 [Constraint](#constraint)
      * 4.9.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint](#rqsrs-030clickhousemysqltoclickhousereplicationalterconstraint)
    * 4.9.12 [Index](#index)
      * 4.9.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index](#rqsrs-030clickhousemysqltoclickhousereplicationalterindex)
    * 4.9.13 [Character Set](#character-set)
      * 4.9.13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet](#rqsrs-030clickhousemysqltoclickhousereplicationaltercharacterset)
    * 4.9.14 [Convert To Character Set](#convert-to-character-set)
      * 4.9.14.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet](#rqsrs-030clickhousemysqltoclickhousereplicationalterconverttocharacterset)
    * 4.9.15 [Algorithm](#algorithm)
      * 4.9.15.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm](#rqsrs-030clickhousemysqltoclickhousereplicationalteralgorithm)
    * 4.9.16 [Force](#force)
      * 4.9.16.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force](#rqsrs-030clickhousemysqltoclickhousereplicationalterforce)
    * 4.9.17 [Lock](#lock)
      * 4.9.17.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock](#rqsrs-030clickhousemysqltoclickhousereplicationalterlock)
    * 4.9.18 [Unlock](#unlock)
      * 4.9.18.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock](#rqsrs-030clickhousemysqltoclickhousereplicationalterunlock)
    * 4.9.19 [Validation](#validation)
      * 4.9.19.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation](#rqsrs-030clickhousemysqltoclickhousereplicationaltervalidation)
    * 4.9.20 [Columns](#columns)
      * 4.9.20.1 [Add](#add)
        * 4.9.20.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsadd)
        * 4.9.20.1.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddnullnotnull)
        * 4.9.20.1.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsadddefault)
        * 4.9.20.1.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddfirstafter)
        * 4.9.20.1.5 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddmultiple)
      * 4.9.20.2 [Modify](#modify)
        * 4.9.20.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodify)
        * 4.9.20.2.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifynullnotnull)
        * 4.9.20.2.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifydefault)
        * 4.9.20.2.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifyfirstafter)
        * 4.9.20.2.5 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifymultiple)
      * 4.9.20.3 [Change](#change)
        * 4.9.20.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangenullnotnulloldnew)
        * 4.9.20.3.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangefirstafter)
        * 4.9.20.3.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangemultiple)
      * 4.9.20.4 [Drop](#drop)
        * 4.9.20.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsdrop)
        * 4.9.20.4.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsdropmultiple)
      * 4.9.20.5 [Rename](#rename)
        * 4.9.20.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsrename)
        * 4.9.20.5.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsrenamemultiple)
    * 4.9.21 [Add Constraint](#add-constraint)
      * 4.9.21.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddconstraint)
    * 4.9.22 [Drop Constraint](#drop-constraint)
      * 4.9.22.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropconstraint)
  * 4.10 [Primary Key](#primary-key)
    * 4.10.1 [No Primary Key](#no-primary-key)
      * 4.10.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeyno)
    * 4.10.2 [Simple Primary Key](#simple-primary-key)
      * 4.10.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeysimple)
    * 4.10.3 [Composite Primary Key](#composite-primary-key)
      * 4.10.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeycomposite)
  * 4.11 [Multiple Upstream Servers](#multiple-upstream-servers)
    * 4.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipleupstreamservers)
  * 4.12 [Multiple Downstream Servers](#multiple-downstream-servers)
    * 4.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledownstreamservers)
  * 4.13 [Archival Mode](#archival-mode)
    * 4.13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode](#rqsrs-030clickhousemysqltoclickhousereplicationarchivalmode)
  * 4.14 [Bootstrapping Mode](#bootstrapping-mode)
    * 4.14.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode](#rqsrs-030clickhousemysqltoclickhousereplicationbootstrappingmode)
  * 4.15 [Binlog Position](#binlog-position)
    * 4.15.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition](#rqsrs-030clickhousemysqltoclickhousereplicationbinlogposition)
  * 4.16 [Column Mapping And Transformation Rules](#column-mapping-and-transformation-rules)
    * 4.16.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnmappingandtransformationrules)
  * 4.17 [Columns Inconsistency](#columns-inconsistency)
    * 4.17.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnsinconsistency)
  * 4.18 [Latency](#latency)
    * 4.18.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency](#rqsrs-030clickhousemysqltoclickhousereplicationlatency)
  * 4.19 [Performance ](#performance-)
    * 4.19.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance](#rqsrs-030clickhousemysqltoclickhousereplicationperformance)
    * 4.19.2 [Large Daily Data Volumes](#large-daily-data-volumes)
      * 4.19.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes](#rqsrs-030clickhousemysqltoclickhousereplicationperformancelargedailydatavolumes)
  * 4.20 [Settings](#settings)
    * 4.20.1 [clickhouse.topic2table.map](#clickhousetopic2tablemap)
      * 4.20.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap](#rqsrs-030clickhousemysqltoclickhousereplicationsettingstopic2tablemap)
  * 4.21 [Prometheus ](#prometheus-)
    * 4.21.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus](#rqsrs-030clickhousemysqltoclickhousereplicationprometheus)

## Introduction

This software requirements specification covers requirements related to [Altinity Sink Connector]'s
support for data replication from [MySQL] databases to [ClickHouse].

## Terminology

### SRS

Software Requirements Specification

### MySQL

[MySQL](https://www.mysql.com/) server.

## Feature Diagram

Test feature diagram.

```mermaid
graph LR;

  classDef yellow fill:#ffff33,stroke:#333,stroke-width:4px,color:black;
  classDef yellow2 fill:#ffff33,stroke:#333,stroke-width:4px,color:red;
  classDef green fill:#00ff33,stroke:#333,stroke-width:4px,color:black;
  classDef red fill:red,stroke:#333,stroke-width:4px,color:black;
  classDef blue fill:blue,stroke:#333,stroke-width:4px,color:white;
  
  subgraph O["MySQL to ClickHouse Replication"]
    id7>CH-keeper/zookeeper]:::yellow2 --Read only--> id6;
    id1[(MySQL)]:::yellow--Unstable Network-->id2["Debezium"]--> id3((Schema)) & id4((Kafka)) --> id5["Altinity"]--Unstable Network
    -->id6[(ClickHouse)]:::yellow;
    
    click id1 "https://www.mysql.com/" _blank
    click id6 "https://clickhouse.com/" _blank
    click id5 "https://github.com/Altinity/clickhouse-sink-connector" _blank

    

  
    subgraph A["User input MySQL"]
        1A["Create MySQL Table"]:::green
        2A["INSERT"]:::green
        3A["DELETE"]:::green
        4A["UPDATE"]:::green
        5A["Alter MySQL Table Schema"]:::green
        id1
        6A([sysbench]):::green
        7A["Primary Key Types (Composite or simple)"]:::green
        8A["Column Data Types"]:::green
        9A["MySQL Table Engine Types"]:::yellow
        10A["Replicated or not-replicated"]:::yellow
    end
    
    subgraph B["Source Connector (MySQL)"]
        1B["Unstable Network"]:::green
        2B["Restart"]:::green
        id2:::blue
        3B["Config Settings"]:::yellow
    end
    
    subgraph C["Schema Registry"]
        1C["Unstable Network"]:::green
        2C["Docker Settings"]:::yellow
        3C["Restart"]:::green
        id3:::blue
    end
    
    subgraph D["Kafka"]
        id4:::blue
        1D["Unstable Network"]:::green
        2D["Docker Settings"]:::yellow
        3D["Restart"]:::green
    end
    
    subgraph E["Sink Connector (ClickHouse)"]
        1E["Unstable Network"]:::green
        2E["Restart"]:::green
        id5:::blue
        3E["Config Settings"]:::green
    end
    
    subgraph F["Output"]
        1F["Check ClickHouse table data matches MySQL table"]:::green
        3F["ClickHouse Alter Table Schema"]:::yellow
        4F["ClickHouse Table Engine Types"]:::yellow
        2F["Restart"]:::green
        id6
        5F["Insert"]:::yellow
        6F["Update"]:::yellow
        7F["Delete"]:::yellow
        8F["No disk space on ClickHouse Server"]:::yellow
        9F["ClickHouse Table Parts"]:::red
        10F["ClickHouse Table Partitions"]:::red
    end
  end
```

## Requirements

### General

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication
version: 1.0

[Altinity Sink Connector] SHALL support replication of single or multiple tables from [MySQL] database.

### Consistency

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency

[Altinity Sink Connector] SHALL support consistent data replication from [MySQL] to [CLickHouse].

#### Multiple MySQL Masters

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.MultipleMySQLMasters

[Altinity Sink Connector] SHALL support consistent data replication from [MySQL] to [CLickHouse] when one or more MySQL
masters are going down.

#### Deduplication

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication
version: 1.0

[Altinity Sink Connector] SHALL support data deduplication when it receives the same data twice from [MySQL].

#### Selects

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select
version: 1.0

[ClickHouse] `SELECT ... FINAL` query SHALL always return exactly same data as [MySQL].

#### Only Once Guarantee

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with only-once guarantee.
Block level de-duplication SHALL be used if it is going to replicated tables
but the publisher SHALL publish only once.

The following cases SHALL be supported:

1. [MySQL] database crash
2. [MySQL] database event stream provider crash
3. [MySQL] restart
3. [ClickHouse] server crash
4. [Clickhouse] server restart
5. [Kafka] server crash
6. [Kafka] server restart
7. [Debezium] server crash
8. [Debezium] server restart
9. [Altinity Sink Connector] server crash
10. [Altinity Sink Connector] server restart
11. [Schemaregistry] server crash
12. [Schemaregistry] server restart
13. [Zookeeper] read only mode

### Transactions

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions
version: 1.0

[Altinity Sink Connector] SHALL support transactions for replicated [MySQL] tables.

### Supported Versions

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions
version: 1.0
 
[Altinity Sink Connector] SHALL support replication from the following [MySQL] versions:

* [MySQL 8.0](https://dev.mysql.com/doc/refman/8.0/en/)


### Supported Storage Engines

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplacingMergeTree" [ClickHouse] table engine.

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplacingMergeTree" [ClickHouse] table engine and virtual column names by default should be "_version" and "_sign".


#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplicatedReplacingMergeTree" [ClickHouse] table engine.

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplicatedReplacingMergeTree" [ClickHouse] table engine with different version column names.

### Data Types

#### Integer Types

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with
'Integer' data types as they supported by [MySQL].

Integer data types connection table:

| MySQL              |           ClickHouse            | Min edge value | Max edge value |
|:-------------------|:-------------------------------:|----------------|:--------------:|
| Bigint             |              Int64              | 2^63           |     2^63-1     |
| Bigint Unsigned    |             UInt64              | 0              |     2^64-1     |
| Int                |              Int32              | -2147483648    |   2147483647   |
| Int Unsigned       |             UInt32              | 0              |   4294967295   |
| Mediumint          |              Int32              | -8388608       |    8388607     |
| Mediumint Unsigned |             UInt32              | 0              |    16777215    |
| Smallint           |              Int16              | -32768         |     32767      |
| Smallint Unsigned  |             UInt16              | 0              |     65535      |
| Tinyint            |              Int8               | -128           |      127       |
| Tinyint Unsigned   |              UInt8              | 0              |      255       |

#### Decimal

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with
'Decimal' data types as they supported by [MySQL].

[ClickHouse]'s 'Decimal32(S)', 'Decimal64(S)', 'Decimal128(S)', 'Decimal256(S)' also can be
manually used for [MySQL] 'Decimal'.

Data types connection table:

| MySQL        |  ClickHouse  |
|:-------------|:------------:|
| Decimal(x,y) | Decimal(x,y) |

#### Double

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with
'Double' data types as they supported by [MySQL].

Data types connection table:

| MySQL        |  ClickHouse  |
|:-------------|:------------:|
| Double       |   Float64    |

#### DateTime

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Data' and 'Time'
data types as they supported by [MySQL].

Data types connection table:

| MySQL       |       ClickHouse        |
|:------------|:-----------------------:|
| Date        |         Date32          |
| DateTime(6) | DateTime64(6) or String |
| DATETIME    |       DateTime64        |
| Time        |         String          |
| Time(6)     |         String          |
| Timestamp   |        DateTime         |

#### Binary

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] replication of tables that contain columns with 'Binary'
data types as they supported by [MySQL].

Data types connection table:

| MySQL        |              ClickHouse              |
|:-------------|:------------------------------------:|
| Binary       |             String + hex             |
| varbinary(*) |             String + hex             |

#### String

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'String'
data types as they supported by [MySQL].

Data types connection table:

| MySQL        |           ClickHouse            |
|:-------------|:-------------------------------:|
| Char         | String / LowCardinality(String) |
| Text         |             String              |
| varbinary(*) |          String + hex           |
| varchar(*)   |             String              |

#### Blob Types

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Blob' [MySQL]
data types and correctly unhex() them.

```sql
SELECT unhex(blob_column) FROM our_table FORMAT CSV;
```

Data types connection table:

| MySQL        |           ClickHouse            |
|:-------------|:-------------------------------:|
| Blob         |          String + hex           |
| Longblob     |          String + hex           |
| Mediumblob   |          String + hex           |

#### Nullable

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with NULL [MySQL]
data types if this expected `Nullable(DataType)` construction should be used.

For example, [MySQL] `VARCHAR(*)` maps to [ClickHouse] `Nullable(String)` and MySQL
`VARCHAR(*) NOT NULL` maps to [ClickHouse] `String`

#### Enum

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| ENUM  |    ENUM    |

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| ENUM  |   String   |

#### JSON

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'JSON'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| JSON  |   String   |

#### Year

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Year'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| Year  |   Int32    |

#### Bytes

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'BIT(m)'
data types where m: 2 - 64 as they supported by [MySQL].

Data types connection table:

| MySQL  | ClickHouse |
|:-------|:----------:|
| BIT(m) |   String   |


### Queries

#### Test Feature Diagram

```mermaid
flowchart TB;

  classDef yellow fill:#ffff33,stroke:#333,stroke-width:4px,color:black;
  classDef yellow2 fill:#ffff33,stroke:#333,stroke-width:4px,color:red;
  classDef green fill:#00ff33,stroke:#333,stroke-width:4px,color:black;
  classDef red fill:red,stroke:#333,stroke-width:4px,color:black;
  classDef blue fill:blue,stroke:#333,stroke-width:4px,color:white;
  
  subgraph O["Queries Test Feature Diagram"]
  A-->D-->C-->B

  1A---2A---3A
  1D---2D
  1C---2C---3C
  1B---2B---3B---4B---5B---6B---7B
  
    subgraph A["User input MySQL"]

        1A["INSERT"]:::green
                2A["DELETE"]:::green
                        3A["UPDATE"]:::green

    end
    
    subgraph D["Engines"]
        1D["with table Engine"]:::yellow
        2D["without table Engine"]:::yellow
    end
    
    subgraph C["Different primary keys"]
        1C["simple primary key"]:::blue
        2C["composite primary key"]:::blue
        3C["no primary key"]:::blue
    end
    
    subgraph B["Different cases"]
        1B["one part one partition"]:::green
        2B["multiple parts one partition"]:::green
        3B["multiple partitions"]:::green
        4B["very large data set"]:::green
        5B["lots of small data sets"]:::green
        6B["table with large number of partitions"]:::green
        7B["table with large number of parts in partition"]:::green
    end
    

    

  end
```

#### Inserts

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts
version: 1.0

[Altinity Sink Connector] SHALL support new data inserts replication from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits
version: 1.0

[Altinity Sink Connector] SHALL support correct data inserts replication from [MySQL] to [CLickHouse] when partition 
limits are hitting or avoid such situations.


#### Updates

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates
version: 1.0

[Altinity Sink Connector] SHALL support data updates replication from [MySQL] to [CLickHouse].

#### Deletes

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes
version: 1.0

[Altinity Sink Connector] SHALL support data deletes replication from [MySQL] to [CLickHouse].

### Table Schema Creation

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation
version: 1.0

[Altinity Sink Connector]SHALL support the following ways to replicate schema from [MySQL] to [CLickHouse]:
* auto-create option
* `clickhouse_loader` script
* `chump` utility

#### Auto Create

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate
version: 1.0

[Altinity Sink Connector] SHALL support auto table creation from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate
version: 1.0

[Altinity Sink Connector] SHALL support auto creation of multiple tables from [MySQL] to [CLickHouse].

#### Auto Drop

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop
version: 1.0

[Altinity Sink Connector] SHALL support `DROP TABLE` query from [MySQL] to [CLickHouse].

### Alter

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER` queries or generate an exception if it does not.

```sql
alter_option: {
    table_options
  | ADD [COLUMN] col_name column_definition
        [FIRST | AFTER col_name]
  | ADD [COLUMN] (col_name column_definition,...)
  | ADD {INDEX | KEY} [index_name]
        [index_type] (key_part,...) [index_option] ...
  | ADD {FULLTEXT | SPATIAL} [INDEX | KEY] [index_name]
        (key_part,...) [index_option] ...
  | ADD [CONSTRAINT [symbol]] PRIMARY KEY
        [index_type] (key_part,...)
        [index_option] ...
  | ADD [CONSTRAINT [symbol]] UNIQUE [INDEX | KEY]
        [index_name] [index_type] (key_part,...)
        [index_option] ...
  | ADD [CONSTRAINT [symbol]] FOREIGN KEY
        [index_name] (col_name,...)
        reference_definition
  | ADD [CONSTRAINT [symbol]] CHECK (expr) [[NOT] ENFORCED]
  | DROP {CHECK | CONSTRAINT} symbol
  | ALTER {CHECK | CONSTRAINT} symbol [NOT] ENFORCED
  | ALGORITHM [=] {DEFAULT | INSTANT | INPLACE | COPY}
  | ALTER [COLUMN] col_name {
        SET DEFAULT {literal | (expr)}
      | SET {VISIBLE | INVISIBLE}
      | DROP DEFAULT
    }
  | ALTER INDEX index_name {VISIBLE | INVISIBLE}
  | CHANGE [COLUMN] old_col_name new_col_name column_definition
        [FIRST | AFTER col_name]
  | [DEFAULT] CHARACTER SET [=] charset_name [COLLATE [=] collation_name]
  | CONVERT TO CHARACTER SET charset_name [COLLATE collation_name]
  | {DISABLE | ENABLE} KEYS
  | {DISCARD | IMPORT} TABLESPACE
  | DROP [COLUMN] col_name
  | DROP {INDEX | KEY} index_name
  | DROP PRIMARY KEY
  | DROP FOREIGN KEY fk_symbol
  | FORCE
  | LOCK [=] {DEFAULT | NONE | SHARED | EXCLUSIVE}
  | MODIFY [COLUMN] col_name column_definition
        [FIRST | AFTER col_name]
  | ORDER BY col_name [, col_name] ...
  | RENAME COLUMN old_col_name TO new_col_name
  | RENAME {INDEX | KEY} old_index_name TO new_index_name
  | RENAME [TO | AS] new_tbl_name
  | {WITHOUT | WITH} VALIDATION
}
```


#### Test `ALTER` Feature Diagram

```mermaid
flowchart TB;

  classDef yellow fill:#ffff33,stroke:#333,stroke-width:4px,color:black;
  classDef yellow2 fill:#ffff33,stroke:#333,stroke-width:4px,color:red;
  classDef green fill:#00ff33,stroke:#333,stroke-width:4px,color:black;
  classDef red fill:red,stroke:#333,stroke-width:4px,color:black;
  classDef blue fill:blue,stroke:#333,stroke-width:4px,color:white;
  
  subgraph O["`ALTER` Test Feature Diagram"]
  D-->C-->B

  1D---2D
  1C---2C---3C
  1B---2B---3B---4B---5B---6B---7B
  8B---9B---10B---11B---12B---13B
  14B---15B---16B---17B---18B---19B
  
    
    subgraph D["Engines"]
        1D["with table Engine"]:::yellow
        2D["without table Engine"]:::yellow
    end
    
    subgraph C["Different primary keys"]
        1C["simple primary key"]:::blue
        2C["composite primary key"]:::blue
        3C["no primary key"]:::blue
    end
    
    subgraph B["Different `ALTER` cases"]
        1B["ADD COLUMN"]:::green
        2B["ADD COLUMN NULL/NOT NULL"]:::green
        3B["ADD COLUMN DEFAULT"]:::green
        4B["ADD COLUMN FIRST, AFTER"]:::green
        5B["MODIFY COLUMN data_type"]:::green
        6B["MODIFY COLUMN data_type NULL/NOT NULL"]:::green
        7B["MODIFY COLUMN data_type DEFAULT"]:::green
        8B["MODIFY COLUMN FIRST, AFTER"]:::green
        9B["CHANGE COLUMN old_name new_name datatype NULL/NOT NULL"]:::green
        10B["CHANGE COLUMN FIRST, AFTER"]:::green
        11B["RENAME COLUMN col1 to col2"]:::green
        12B["DROP COLUMN"]:::green
        13B["ALTER COLUMN col_name ADD DEFAULT"]:::red
        14B["ALTER COLUMN col_name ADD DROP DEFAULT"]:::red
        15B["ADD PRIMARY KEY"]:::red
        16B["ADD INDEX"]:::red
        17B["ADD CONSTRAINT (CHECK)"]:::yellow
        18B["ADD CONSTRAINT"]:::red
        19B["DROP CONSTRAINT"]:::red
        
    end
    

    

  end
```

#### Test multiple `ALTER` Feature Diagram

```mermaid
flowchart TB;

  classDef yellow fill:#ffff33,stroke:#333,stroke-width:4px,color:black;
  classDef yellow2 fill:#ffff33,stroke:#333,stroke-width:4px,color:red;
  classDef green fill:#00ff33,stroke:#333,stroke-width:4px,color:black;
  classDef red fill:red,stroke:#333,stroke-width:4px,color:black;
  classDef blue fill:blue,stroke:#333,stroke-width:4px,color:white;
  
  subgraph O["multiple `ALTER` Test Feature Diagram"]
  D-->C-->E-->B
  C-->F

  1D---2D
  1C---2C---3C
  1E---2E---3E---4E
  5B---6B---7B
  8B---9B---10B---11B---12B---13B
  14B---15B---16B---17B---18B---19B
  1F---2F---3F---4F
  
  
  
    
    subgraph D["Engines"]
        1D["with table Engine"]:::yellow
        2D["without table Engine"]:::yellow
    end
    
    subgraph C["Different primary keys"]
        1C["simple primary key"]:::blue
        2C["composite primary key"]:::blue
        3C["no primary key"]:::blue
    end
    
    subgraph E["Table parts"]
        1E["Keys"]:::blue
        2E["Columns"]:::blue
        3E["Values"]:::blue
        4E["Indexes"]:::blue
    end
    
    subgraph B["Different `ALTER` cases"]
        5B["MODIFY COLUMN data_type"]:::green
        6B["MODIFY COLUMN data_type NULL/NOT NULL"]:::green
        7B["MODIFY COLUMN data_type DEFAULT"]:::green
        8B["MODIFY COLUMN FIRST, AFTER"]:::green
        9B["CHANGE COLUMN old_name new_name datatype NULL/NOT NULL"]:::green
        10B["CHANGE COLUMN FIRST, AFTER"]:::green
        11B["RENAME COLUMN col1 to col2"]:::green
        12B["DROP COLUMN"]:::green
        13B["ALTER COLUMN col_name ADD DEFAULT"]:::red
        14B["ALTER COLUMN col_name ADD DROP DEFAULT"]:::red
        15B["ADD PRIMARY KEY"]:::red
        16B["ADD INDEX"]:::red
        17B["ADD CONSTRAINT (CHECK)"]:::yellow
        18B["ADD CONSTRAINT"]:::red
        19B["DROP CONSTRAINT"]:::red
        
    end
    
    subgraph F["ADD COLUMN"]
        1F["ADD COLUMN"]:::green
        2F["ADD COLUMN NULL/NOT NULL"]:::green
        3F["ADD COLUMN DEFAULT"]:::green
        4F["ADD COLUMN FIRST, AFTER"]:::green
        
    end
    

    

  end
```

#### Add Index

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex
version: 1.0

[Altinity Sink Connector] SHALL support `ADD INDEX` query from [MySQL] to [CLickHouse].

#### Add Key

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey
version: 1.0

[Altinity Sink Connector] SHALL support `ADD Key` query from [MySQL] to [CLickHouse].

#### Add FullText

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText
version: 1.0

[Altinity Sink Connector] SHALL support `ADD FULLTEXT` query from [MySQL] to [CLickHouse].

#### Add Special

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial
version: 1.0

[Altinity Sink Connector] SHALL support `ADD SPECIAL` query from [MySQL] to [CLickHouse].

#### Drop Check

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck
version: 1.0

[Altinity Sink Connector] SHALL support `DROP CHECK` query from [MySQL] to [CLickHouse].

#### Drop Default

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault
version: 1.0

[Altinity Sink Connector] SHALL support `DROP DEFAULT` query from [MySQL] to [CLickHouse].

#### Check

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CHECK` query from [MySQL] to [CLickHouse].

#### Constraint

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CONSTRAINT` query from [MySQL] to [CLickHouse].

#### Index

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER INDEX` query from [MySQL] to [CLickHouse].

#### Character Set

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CHARACTER SET` query from [MySQL] to [CLickHouse].

#### Convert To Character Set

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CONVERT TO CHARACTER SET` query from [MySQL] to [CLickHouse].

#### Algorithm

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER ALGORITHM` query from [MySQL] to [CLickHouse].

#### Force

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER FORCE` query from [MySQL] to [CLickHouse].

#### Lock

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER LOCK` query from [MySQL] to [CLickHouse].

#### Unlock

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER UNLOCK` query from [MySQL] to [CLickHouse].

#### Validation

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER VALIDATION` query from [MySQL] to [CLickHouse].

#### Columns

##### Add

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN NULL/NOT NULL` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN DEFAULT` query from [MySQL] to [CLickHouse].


###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN FIRST, AFTER` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `ADD COLUMN` query from [MySQL] to [CLickHouse].



##### Modify

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type` query from [MySQL] to [CLickHouse].


###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type NULL/NOT NULL` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type DEFAULT` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type FIRST, AFTER` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `MODIFY COLUMN` query from [MySQL] to [CLickHouse].

##### Change

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew
version: 1.0

[Altinity Sink Connector] SHALL support `CHANGE COLUMN old_name new_name datatype NULL/NOT NULL` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `CHANGE COLUMN FIRST, AFTER` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `CHANGE COLUMN` query from [MySQL] to [CLickHouse].

##### Drop

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop
version: 1.0

[Altinity Sink Connector] SHALL support `DROP COLUMN` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `DROP COLUMN` query from [MySQL] to [CLickHouse].

##### Rename

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename
version: 1.0

[Altinity Sink Connector] SHALL support `RENAME COLUMN col1 to col2` query from [MySQL] to [CLickHouse].

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `RENAME COLUMN col1 to col2` query from [MySQL] to [CLickHouse].

#### Add Constraint

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint
version: 1.0

[Altinity Sink Connector] SHALL support `ADD CONSTRAINT` query from [MySQL] to [CLickHouse].

#### Drop Constraint

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint
version: 1.0

[Altinity Sink Connector] SHALL support `DROP CONSTRAINT` query from [MySQL] to [CLickHouse].

### Primary Key

#### No Primary Key

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries to tables
with no `PRIMARY KEY`.

#### Simple Primary Key

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order
as simple `PRIMARY KEY` does.

#### Composite Primary Key

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order 
as composite `PRIMARY KEY` does.

### Multiple Upstream Servers

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] from multiple [MySQL] upstream servers.

### Multiple Downstream Servers

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] when using multiple downstream [ClickHouse] servers.

### Archival Mode

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with archival mode that
SHALL ignore deletes for some or all tables in [ClickHouse].

### Bootstrapping Mode

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with 
bootstrapping mode for the initial replication of very large tables
that bypasses event stream by using [MySQL] dump files.

### Binlog Position

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition
version: 1.0

[Altinity Sink Connector] SHALL support ability to start replication to [CLickHouse] 
from specific [MySQL] binlog position.

### Column Mapping And Transformation Rules

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with support for
defining column mapping and transformations rules.

### Columns Inconsistency

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] replica table when it has fewer columns.
[MySQL] replication to [CLickHouse] is not available in all other cases of columns inconsistency .

### Latency

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with latency as close as possible to real-time.

### Performance 

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] more than 100,000 rows/sec.

#### Large Daily Data Volumes

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with large daily data volumes of at least 20-30TB per day.

### Settings

#### clickhouse.topic2table.map

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap
version: 1.0

[Altinity Sink Connector] SHALL support `clickhouse.topic2table.map` setting for mapping [MySQL] tables to
[ClickHouse] tables that have different name.

### Prometheus 

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus
version: 1.0

[Altinity Sink Connector] SHALL support expose data transfer representation to [Prometheus] service.

[SRS]: #srs
[MySQL]: #mysql
[Prometheus]: https://prometheus.io/
[ClickHouse]: https://clickhouse.com/en/docs
[Altinity Sink Connector]: https://github.com/Altinity/clickhouse-sink-connector
[Git]: https://git-scm.com/
[GitLab]: https://gitlab.com
""",
)
