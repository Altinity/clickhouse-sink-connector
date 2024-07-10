# These requirements were auto generated
# from software requirements specification (SRS)
# document by TestFlows v2.0.240708.1162538.
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
    level=2,
    num="5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Configurations = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Configurations",
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
    level=2,
    num="6.1",
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
    level=3,
    num="7.3.1",
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
    level=3,
    num="7.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_OnlyOnceGuarantee = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with only-once guarantee.\n"
        "Block level de-duplication SHALL be used if it is going to replicated tables\n"
        "but the publisher SHALL publish only once.\n"
        "\n"
        "The following cases SHALL be supported:\n"
        "\n"
        "1. [MySQL] database crash\n"
        "2. [MySQL] database event stream provider crash\n"
        "3. [MySQL] restart\n"
        "3. [ClickHouse] server crash\n"
        "4. [ClickHouse] server restart\n"
        "5. [Altinity Sink Connector] server crash\n"
        "6. [Altinity Sink Connector] server restart\n"
        "\n"
    ),
    link=None,
    level=3,
    num="7.5.1",
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
    level=2,
    num="8.1",
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
    ),
    link=None,
    level=2,
    num="9.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_InnoDB = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.InnoDB",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'InnoDB' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_MyISAM = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MyISAM",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'MyISAM' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_MEMORY = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MEMORY",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'MEMORY' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_CSV = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CSV",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'CSV' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ARCHIVE = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ARCHIVE",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'ARCHIVE' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_BLACKHOLE = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.BLACKHOLE",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'BLACKHOLE' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.6.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_FEDERATED = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.FEDERATED",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'FEDERATED' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.7.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_EXAMPLE = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.EXAMPLE",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of tables that use the 'EXAMPLE' storage engine in MySQL:\n"
        "\n"
    ),
    link=None,
    level=3,
    num="10.8.1",
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
    level=2,
    num="11.1",
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
    level=2,
    num="11.2",
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
    level=3,
    num="11.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables with any datatypes that [MySQL] supports.\n"
        "\n"
        "| MySQL Datatypes    |\n"
        "|--------------------|\n"
        "| DECIMAL            |\n"
        "| DOUBLE             |\n"
        "| DATE               |\n"
        "| DATETIME(1-6)      |\n"
        "| TIME(1-6)          |\n"
        "| INT                |\n"
        "| INT UNSIGNED       |\n"
        "| BIGINT             |\n"
        "| BIGINT UNSIGNED    |\n"
        "| TINYINT            |\n"
        "| TINYINT UNSIGNED   |\n"
        "| SMALLINT           |\n"
        "| SMALLINT UNSIGNED  |\n"
        "| MEDIUMINT          |\n"
        "| MEDIUMINT UNSIGNED |\n"
        "| CHAR               |\n"
        "| TEXT               |\n"
        "| VARCHAR(1-32766)   |\n"
        "| BLOB               |\n"
        "| MEDIUMBLOB         |\n"
        "| LONGBLOB           |\n"
        "| BINARY             |\n"
        "| VARBINARY          |\n"
        "\n"
    ),
    link=None,
    level=2,
    num="12.1",
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
    level=3,
    num="12.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Decimal = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with\n"
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
    level=3,
    num="12.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Double = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with\n"
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
    level=3,
    num="12.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'Data' and 'Time'\n"
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
    level=3,
    num="12.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Binary = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] replication of tables that contain columns with 'Binary'\n"
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
    level=3,
    num="12.6.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_String = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'String'\n"
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
    level=3,
    num="12.7.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_BlobTypes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'Blob' [MySQL]\n"
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
    level=3,
    num="12.8.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Nullable = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with NULL [MySQL]\n"
        "data types if this expected `Nullable(DataType)` construction should be used.\n"
        "\n"
        "For example, [MySQL] `VARCHAR(*)` maps to [ClickHouse] `Nullable(String)` and MySQL\n"
        "`VARCHAR(*) NOT NULL` maps to [ClickHouse] `String`\n"
        "\n"
    ),
    link=None,
    level=3,
    num="12.9.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToEnum = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'ENUM'\n"
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
    level=3,
    num="12.10.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToString = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'ENUM'\n"
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
    level=3,
    num="12.10.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_JSON = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'JSON'\n"
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
    level=3,
    num="12.11.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Year = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'Year'\n"
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
    level=3,
    num="12.12.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Bytes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'BIT(m)'\n"
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
    level=3,
    num="12.13.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support new data inserts replication from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="13.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts_PartitionLimits = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support correct data inserts replication from [MySQL] to [ClickHouse] when partition \n"
        "limits are hitting or avoid such situations.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="13.1.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts_PartitionTypes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionTypes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support correct data inserts replication from [MySQL] to [ClickHouse] when the table in source table is partitioned with the following logic.\n"
        "\n"
        "| Partition Type |\n"
        "|----------------|\n"
        "| RANGE          |\n"
        "| LIST           |\n"
        "| COLUMNS        |\n"
        "| HASH           |\n"
        "| KEY            |\n"
        "| Subpartition   |\n"
        "\n"
        "\n"
    ),
    link=None,
    level=4,
    num="13.1.1.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Updates = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data updates replication from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="13.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Deletes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data deletes replication from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="13.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector]SHALL support the following ways to replicate schema from [MySQL] to [ClickHouse]:\n"
        "* auto-create option\n"
        "* `clickhouse_loader` script\n"
        "* `chump` utility\n"
        "\n"
    ),
    link=None,
    level=2,
    num="14.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoCreate = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support auto table creation from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="14.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_MultipleAutoCreate = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support auto creation of multiple tables from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="14.2.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation_AutoDrop = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP TABLE` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="14.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support the following `ALTER` queries.\n"
        "\n"
        "| MySQL                                                  | ClickHouse                                                      |\n"
        "|--------------------------------------------------------|-----------------------------------------------------------------|\n"
        "| ADD COLUMN                                             |                                                                 |\n"
        "| ADD COLUMN NULL/NOT NULL                               |                                                                 |\n"
        "| ADD COLUMN DEFAULT                                     |                                                                 |\n"
        "| ADD COLUMN FIRST, AFTER                                |                                                                 |\n"
        "| DROP COLUMN                                            |                                                                 |\n"
        "| MODIFY COLUMN data_type                                |                                                                 |\n"
        "| MODIFY COLUMN data_type NULL/NOT NULL                  |                                                                 |\n"
        "| MODIFY COLUMN data_type DEFAULT                        |                                                                 |\n"
        "| MODIFY COLUMN FIRST, AFTER                             |                                                                 |\n"
        "| MODIFY COLUMN old_name new_name datatype NULL/NOT NULL |                                                                 |\n"
        "| RENAME COLUMN col1 to col2                             |                                                                 |\n"
        "| CHANGE COLUMN FIRST, AFTER                             | MODIFY COLUMN                                                   |\n"
        "| ALTER COLUMN col_name ADD DEFAULT                      | Not supported by grammar                                        |\n"
        "| ALTER COLUMN col_name ADD DROP DEFAULT                 | Not supported by grammar                                        |\n"
        "| ADD PRIMARY KEY                                        | Cannot modify primary key in CH                                 |\n"
        "\n"
        "\n"
    ),
    link=None,
    level=2,
    num="15.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddIndex = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD INDEX` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddKey = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD Key` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddFullText = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD FULLTEXT` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddSpecial = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD SPECIAL` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropCheck = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP CHECK` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.6.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropDefault = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP DEFAULT` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.7.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Check = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CHECK` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.8.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Constraint = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CONSTRAINT` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.9.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Index = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER INDEX` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.10.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_CharacterSet = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CHARACTER SET` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.11.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_ConvertToCharacterSet = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER CONVERT TO CHARACTER SET` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.12.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Algorithm = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER ALGORITHM` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.13.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Force = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER FORCE` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.14.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Lock = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER LOCK` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.15.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Unlock = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER UNLOCK` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.16.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Validation = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ALTER VALIDATION` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.17.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_NullNotNull = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN NULL/NOT NULL` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.1.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Default = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN DEFAULT` query from [MySQL] to [ClickHouse].\n"
        "\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.1.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_FirstAfter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD COLUMN FIRST, AFTER` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.1.4",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `ADD COLUMN` query from [MySQL] to [ClickHouse].\n"
        "\n"
        "\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.1.5",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type` query from [MySQL] to [ClickHouse].\n"
        "\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_NullNotNull = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type NULL/NOT NULL` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.2.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Default = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type DEFAULT` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.2.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_FirstAfter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type FIRST, AFTER` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.2.4",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `MODIFY COLUMN` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.2.5",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_NullNotNullOldNew = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `CHANGE COLUMN old_name new_name datatype NULL/NOT NULL` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_FirstAfter = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `CHANGE COLUMN FIRST, AFTER` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.3.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `CHANGE COLUMN` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.3.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP COLUMN` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `DROP COLUMN` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.4.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `RENAME COLUMN col1 to col2` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename_Multiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support multiple `RENAME COLUMN col1 to col2` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=4,
    num="15.18.5.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_AddConstraint = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `ADD CONSTRAINT` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.19.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_DropConstraint = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support `DROP CONSTRAINT` query from [MySQL] to [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=3,
    num="15.20.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_No = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] query SHALL support [MySQL] data replication to [ClickHouse] on queries to tables\n"
        "with no `PRIMARY KEY`.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="16.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] query SHALL support [MySQL] data replication to [ClickHouse] on queries with the same order\n"
        "as simple `PRIMARY KEY` does.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="16.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Composite = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] query SHALL support [MySQL] data replication to [ClickHouse] on queries with the same order \n"
        "as composite `PRIMARY KEY` does.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="16.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleUpstreamServers = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] from multiple [MySQL] upstream servers.\n"
        "\n"
    ),
    link=None,
    level=2,
    num="17.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDownstreamServers = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] when using multiple downstream [ClickHouse] servers.\n"
        "\n"
    ),
    link=None,
    level=2,
    num="18.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ArchivalMode = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with archival mode that\n"
        "SHALL ignore deletes for some or all tables in [ClickHouse].\n"
        "\n"
    ),
    link=None,
    level=2,
    num="19.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BootstrappingMode = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with \n"
        "bootstrapping mode for the initial replication of very large tables\n"
        "that bypasses event stream by using [MySQL] dump files.\n"
        "\n"
    ),
    link=None,
    level=2,
    num="20.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BinlogPosition = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support ability to start replication to [ClickHouse] \n"
        "from specific [MySQL] binlog position.\n"
        "\n"
    ),
    link=None,
    level=2,
    num="21.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnMappingAndTransformationRules = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with support for\n"
        "defining column mapping and transformations rules.\n"
        "\n"
    ),
    link=None,
    level=2,
    num="22.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnsInconsistency = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] replica table when it has fewer columns.\n"
        "[MySQL] replication to [ClickHouse] is not available in all other cases of columns inconsistency .\n"
        "\n"
    ),
    link=None,
    level=2,
    num="23.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Latency = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with latency as close as possible to real-time.\n"
        "\n"
    ),
    link=None,
    level=2,
    num="24.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] more than 100,000 rows/sec.\n"
        "\n"
    ),
    link=None,
    level=2,
    num="25.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance_LargeDailyDataVolumes = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with large daily data volumes of at least 20-30TB per day.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="25.2.1",
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
    level=3,
    num="26.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableNames_Valid = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Valid",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of a table that was created on a source database with a name that follows the rules below.\n"
        "\n"
        "- Names starting with a number\n"
        "\n"
        "- Names containing spaces\n"
        "\n"
        "- Names using reserved keywords\n"
        "\n"
        "- Names with mixed alphanumeric characters and safe symbols\n"
        "\n"
    ),
    link=None,
    level=3,
    num="27.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableNames_Invalid = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Invalid",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL not support replication and SHALL output an error when trying to replicate a table with a name which [ClickHouse] does not support.\n"
        "\n"
        "\n"
    ),
    link=None,
    level=3,
    num="27.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Special = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Special",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from the source tables that have special column names.\n"
        "\n"
        "For example,\n"
        "\n"
        "If we create a source table that contains the column with the `is_deleted` name,\n"
        "\n"
        "```sql\n"
        "CREATE TABLE new_table(col1 VARCHAR(255), col2 INT, is_deleted INT)\n"
        "```\n"
        "\n"
        "The `ReplacingMergeTree` table created on ClickHouse side SHALL be updated and the `is_deleted` column should be renamed to  `_is_deleted` so there are no column name conflicts between ClickHouse and source table.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="28.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Backticks = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Backticks",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from the source tables that have backticks in column names.\n"
        "\n"
        "For example,\n"
        "\n"
        "If we create a source table that contains the column with the `is_deleted` name,\n"
        "\n"
        "```sql\n"
        "CREATE TABLE new_table(col1 VARCHAR(255), `col2` INT, `is_deleted` INT)\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=3,
    num="28.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Interruption_ClickHouse_Instance_Stopped = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL retry replication if the ClickHouse instance is stopped/killed during the active replication from source to destination tables. [Altinity Sink Connector] SHALL continue to retry to replicate data into a source table until the ClickHouse instance is not available again.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="29.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support using CLI commands to manipulate replication status in order to manage replication process more easily.\n"
        "To start using CLI commands, the user should run the `sink-connector-client` script from the command line.\n"
        "\n"
    ),
    link=None,
    level=3,
    num="30.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StartReplication = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StartReplication",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL start replication process when `start_replica` command is executed.\n"
        "\n"
        "```bash\n"
        "bash-4.4# ./sink-connector-client start_replica\n"
        "2024/04/05 10:57:14 Started Replication....\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="30.1.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StopReplication = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StopReplication",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altiniry Sink Connector] SHALL stop replication process when `stop_replica` command is executed.\n"
        "\n"
        "```bash\n"
        "bash-4.4# ./sink-connector-client stop_replica\n"
        "2024/04/05 10:57:21 ***** Stopping replication..... *****\n"
        "2024/04/05 10:57:22 \n"
        "2024/04/05 10:57:22 ***** Replication stopped successfully *****\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="30.1.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_ShowReplicationStatus = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ShowReplicationStatus",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL show replication status when `show_replica_status` command is executed.\n"
        "\n"
        "```bash\n"
        "bash-4.4# ./sink-connector-client show_replica_status\n"
        "[\n"
        "  {\n"
        '    "Seconds_Behind_Source": 18001\n'
        "  },\n"
        "  {\n"
        '    "Replica_Running": true\n'
        "  },\n"
        "  {\n"
        '    "Database": "test"\n'
        "  },\n"
        "  {\n"
        '    "record_insert_ts": 2024-04-05T06,\n'
        '    "offset_key": "[\\"company-1\\",{\\"server\\":\\"embeddedconnector\\"}]",\n'
        '    "record_insert_seq": 218,\n'
        '    "id": "457678de-4759-4bb6-8720-fbdd60627eb5",\n'
        '    "offset_val": "{\\"ts_sec\\":1712296812,\\"file\\":\\"mysql-bin.000003\\",\\"pos\\":197,\\"gtids\\":\\"978f1323-f33b-11ee-b609-0242ac120003:1-56\\"}"\n'
        "  }\n"
        "]\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="30.1.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_ChangeReplicationSource = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ChangeReplicationSource",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support updating binlog file/position and gtids by using `change_replication_source` command.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="30.1.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_GlobalOptions = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.GlobalOptions",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support using the following global options  for the `sink-connector-client` script.\n"
        "\n"
        "```shell\n"
        "GLOBAL OPTIONS:\n"
        "   --host value   Host server address of sink connector\n"
        "   --port value   Port of sink connector\n"
        "   --secure       If true, then use https, else http\n"
        "   --help, -h     show help\n"
        "   --version, -v  print the version\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=3,
    num="30.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Network = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Network",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL be able to recover replication after network related interruptions happen, so that the data on the destination table is not lost.\n"
        "\n"
        "\n"
        "| List of possible network related interruptions                |\n"
        "|---------------------------------------------------------------|\n"
        "| Internal network interruptions in source database cluster     |\n"
        "| Network interruptions from source database to sink connector  |\n"
        "| Network interruptions from sink connector to clickhouse       |\n"
        "| Internal network interruptions in clickhouse database cluster |\n"
        "\n"
        "\n"
    ),
    link=None,
    level=3,
    num="31.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Process_Die = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Die",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL output an error and keep the existing data when one of the following or all of the \n"
        "scenarios related to processes being killed happen:\n"
        "\n"
        "| Scenarios Related to Processes Being Killed           |\n"
        "|-------------------------------------------------------|\n"
        "| Internal processes die in source database cluster     |\n"
        "| Sink connector dies                                   |\n"
        "| Internal processes die in clickhouse database cluster |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="31.2.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Process_Restarted = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Restarted",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL continue replication without losing any data after one of the following or \n"
        "all of the scenarios related to processes being restarted happen:\n"
        "\n"
        "| Scenarios Related to Processes Being Restarted              |\n"
        "|-------------------------------------------------------------|\n"
        "| Restart of some or all nodes in source database cluster     |\n"
        "| Restart of sink connector                                   |\n"
        "| Restart of some or all nodes in clickhouse database cluster |\n"
        "\n"
    ),
    link=None,
    level=4,
    num="31.2.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Disk_OutOfSpace = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.OutOfSpace",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL output an error when:\n"
        "\n"
        "- Out of disk space on some node in source database cluster\n"
        "- Out of disk space where sink connector is running\n"
        "- Out of disk space on some node in clickhouse database cluster\n"
        "\n"
        "The error SHALL be shown so that the data on the source and destination tables is not lost due to the disk related issues.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="31.3.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Disk_Corrupted = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.Corrupted",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL output an error when:\n"
        "\n"
        "- Corruption on a disk used by some node in source database cluster\n"
        "- Corruption on a disk where sink connector is running\n"
        "- Corruption on a disk used by some node in clickhouse database cluster\n"
        "\n"
        "The error SHALL be shown so that the data on the source and destination tables is not lost due to the disk related issues.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="31.3.2.1",
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
    ),
    link=None,
    level=2,
    num="32.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support table replication from source database to the destination database and store the table as  `ReplicatedReplacingMergeTree` [ClickHouse] table engine.\n"
        "\n"
        "\n"
        "In order for [ALtinity Sink Connector] to replicate a source table as `ReplicatedReplacingMergeTree` in [ClickHouse] the configuration file should contain the following setting:\n"
        "\n"
        "```yaml\n"
        'auto.create.tables.replicated: "true"\n'
        "```\n"
    ),
    link=None,
    level=2,
    num="33.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_MultipleShardsAndReplicas = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.MultipleShardsAndReplicas",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a cluster with multiple shards and replicas.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.3.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_OneShardOneReplica = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.OneShardOneReplica",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a cluster with one shard and one replica.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.3.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_SecureClusterOneShardOneReplica = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterOneShardOneReplica",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a secure cluster with one shard and one replica.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.3.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_SecureClusterMultipleShardsAndReplicas = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterMultipleShardsAndReplicas",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a secure cluster with multiple shards and replicas.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.3.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_NodeRelatedEvents_Killed = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.Killed",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when some of the nodes where replicas are running are killed.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_NodeRelatedEvents_AllKilled = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.AllKilled",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when all the nodes where replicas are running are killed.\n"
        "\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.1.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_NodeRelatedEvents_ChangeLeader = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.ChangeLeader",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when the leader node is changed during the replication process.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.1.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_BehindLeader = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.BehindLeader",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when one or more replicas are behind the leader replica. \n"
        "Replication process from destination to source database SHALL not be interrupted in this case.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_DataInconsistency = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.DataInconsistency",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when there is data inconsistency between replicas.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.2.2",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_NewReplica = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.NewReplica",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when a new replica is added during the replication process.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.2.3",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_RemovedReplica = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.RemovedReplica",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when a replica is removed during the replication process.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.2.4",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ConnectionRelatedEvents_Interrupted = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ConnectionRelatedEvents.Interrupted",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when the connection between replicas is interrupted.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="33.4.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_Disk_OutOfSpace = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.OutOfSpace",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when one of the replicas in the source database cluster is out of disk space.\n"
        "\n"
    ),
    link=None,
    level=5,
    num="33.4.4.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_Disk_Corrupted = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.Corrupted",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication from source database to the destination database when one of the replicas in the source database cluster has a corrupted disk.\n"
        "\n"
        "\n"
    ),
    link=None,
    level=5,
    num="33.4.4.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of multiple databases from [MySQL] to [ClickHouse].\n"
        "\n"
        "The implementation works as follows,\n"
        "```mermaid\n"
        "graph LR\n"
        "    A[MySQL: customers] -->|Replicated| D[ClickHouse: customers]\n"
        "    B[MySQL: products] -->|Replicated| E[ClickHouse: products]\n"
        "    C[MySQL: departments] -->|Replicated| F[ClickHouse: departments]\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.2.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_SourceMultipleDestinationOne = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceMultipleDestinationOne",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of a database from source to destination when there are multiple databases on the source side and only one database on the destination side.\n"
        "\n"
        "```mermaid\n"
        "graph LR\n"
        "    A[MySQL: Database 1]\n"
        "    B[MySQL: Database 2] -->|Replicated| D[ClickHouse: Database 2]\n"
        "    C[MySQL: Database 3]\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.2.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_SourceOneDestinationMultiple = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationMultiple",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of a database from source to destination when there is only one database on the source side and multiple databases on the destination side.\n"
        "\n"
        "```mermaid\n"
        "graph LR\n"
        "    A[MySQL: Database 2] -->|Not Replicated| D[ClickHouse: Database 1]\n"
        "    A -->|Replicated| E[ClickHouse: Database 2]\n"
        "    A -->|Not Replicated| F[ClickHouse: Database 3]\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.2.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_SourceOneDestinationOne = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationOne",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of a database from source to destination when there is only one database on the source side and only one database on the destination side.\n"
        "\n"
        "```mermaid\n"
        "graph LR\n"
        "    A[MySQL: Database 1] -->|Replicated| D[ClickHouse: Database 1]\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.2.4.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_Tables_SameNameDifferentStructure = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameDifferentStructure",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of two tables with the same name and different structure on different databases on the source. The tables SHALL be replicated to the correct corresponding databases on the destination.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.3.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_Tables_SameNameSameStructure = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameSameStructure",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of two tables with the same name and the same structure on different databases on the source. The tables SHALL be replicated to the correct corresponding databases on the destination.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.3.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_Tables_DifferentNameSameStructure = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.DifferentNameSameStructure",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support replication of two tables with the different name and the same structure on different databases on the source. The tables SHALL be replicated to the correct corresponding databases on the destination.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.3.3.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_IncludeList = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.IncludeList",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support the usage of the `database.include.list` configuration value to specify a list of databases to replicate.\n"
        "\n"
        "for example,\n"
        "```yaml\n"
        "database.include.list: database1, database2, ... , databaseN\n"
        "```\n"
        "\n"
        "This configuration value SHALL ensure that only the databases specified in the list are replicated to the destination.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.4.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_ReplicateAll = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.ReplicateAll",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support the ability to monitor all databases from the source and replicate them to the destination without specifying the `database.include.list` configuration value.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.4.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_OverrideMap = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support the usage of the `clickhouse.database.override.map` configuration value to allow the user to replicate the data from the source database to the destination database with a different name.\n"
        "\n"
        "For example, when using the following value in configuration,\n"
        "\n"
        "```yaml\n"
        'clickhouse.database.override.map: "mysql1:ch1"\n'
        "```\n"
        "\n"
        "The source database `mysql1` SHALL be mapped to the destination database `ch1`, and the data from the source `mysql1` SHALL only be replicated to the destination database `ch1`.\n"
        "\n"
        "```mermaid\n"
        "flowchart TD\n"
        "    B[Read clickhouse.database.override.map] --> D[Identify Source Database mysql1]\n"
        "    D --> E[Map to Destination Database ch1]\n"
        "    E --> F[Replicate Data to ch1]\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=3,
    num="34.5.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_OverrideMap_MultipleValues = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap.MultipleValues",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support the usage of the `clickhouse.database.override.map` configuration value to map multiple source databases to different databases on the destination.\n"
        "\n"
        "For example, when using the following value in configuration,\n"
        "\n"
        "```yaml\n"
        'clickhouse.database.override.map: "mysql1:ch1, mysql2:ch2"\n'
        "```\n"
        "\n"
        "The source databases `mysql1` and `mysql2` SHALL be mapped to the destination databases `ch1` and `ch2`, and the data from these source databases SHALL only be replicated to the destination databases `ch1` and `ch2`.\n"
        "\n"
        "```mermaid\n"
        "flowchart TD\n"
        "    B[Read clickhouse.database.override.map]\n"
        "    B --> C[Parse Override Map]\n"
        "    C --> E[Map mysql1 to ch1]\n"
        "    C --> F[Map mysql2 to ch2]\n"
        "    E --> G[Replicate Data from mysql1 to ch1]\n"
        "    F --> H[Replicate Data from mysql2 to ch2]\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.5.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_TableOperations_SpecifyDatabaseName = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.SpecifyDatabaseName",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support specifying the database name in the table operations.\n"
        "\n"
        "For example,\n"
        "\n"
        "```sql\n"
        "CREATE TABLE {database}.{table_name}\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.6.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_TableOperations_NoSpecifyDatabaseName = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.NoSpecifyDatabaseName",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support table operations without specifying the database name.\n"
        "\n"
        "For example,\n"
        "\n"
        "```sql\n"
        "CREATE TABLE {table_name}\n"
        "```\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.6.2.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ErrorHandling_DatabaseNotExist = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ErrorHandling.DatabaseNotExist",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL output an error when the replicated database does not exist on the destination. The error SHALL be repeated until the database is created on the destination.\n"
        "\n"
    ),
    link=None,
    level=4,
    num="34.7.1.1",
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConcurrentActions = Requirement(
    name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConcurrentActions",
    version="1.0",
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL replicate concurrently performed actions on source.\n"
        "\n"
        "For example,\n"
        "if we perform multiple alter actions on multiple databases, the actions SHALL be replicated to the destination without issues.\n"
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
    level=4,
    num="34.8.1.1",
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
        Heading(name="Test Schema", level=1, num="3"),
        Heading(name="Configuration", level=1, num="4"),
        Heading(name="General", level=1, num="5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication",
            level=2,
            num="5.1",
        ),
        Heading(name="Configurations", level=1, num="6"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Configurations",
            level=2,
            num="6.1",
        ),
        Heading(name="Consistency", level=1, num="7"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency",
            level=2,
            num="7.1",
        ),
        Heading(name="Multiple MySQL Masters", level=2, num="7.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.MultipleMySQLMasters",
            level=3,
            num="7.2.1",
        ),
        Heading(name="Deduplication", level=2, num="7.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication",
            level=3,
            num="7.3.1",
        ),
        Heading(name="Selects", level=2, num="7.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select",
            level=3,
            num="7.4.1",
        ),
        Heading(name="Only Once Guarantee", level=2, num="7.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee",
            level=3,
            num="7.5.1",
        ),
        Heading(name="Transactions", level=1, num="8"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions",
            level=2,
            num="8.1",
        ),
        Heading(name="Supported Versions", level=1, num="9"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions",
            level=2,
            num="9.1",
        ),
        Heading(name="Supported Storage Engines", level=1, num="10"),
        Heading(name="InnoDB", level=2, num="10.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.InnoDB",
            level=3,
            num="10.1.1",
        ),
        Heading(name="MyISAM", level=2, num="10.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MyISAM",
            level=3,
            num="10.2.1",
        ),
        Heading(name="MEMORY", level=2, num="10.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MEMORY",
            level=3,
            num="10.3.1",
        ),
        Heading(name="CSV", level=2, num="10.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CSV",
            level=3,
            num="10.4.1",
        ),
        Heading(name="ARCHIVE", level=2, num="10.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ARCHIVE",
            level=3,
            num="10.5.1",
        ),
        Heading(name="BLACKHOLE", level=2, num="10.6"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.BLACKHOLE",
            level=3,
            num="10.6.1",
        ),
        Heading(name="FEDERATED", level=2, num="10.7"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.FEDERATED",
            level=3,
            num="10.7.1",
        ),
        Heading(name="EXAMPLE", level=2, num="10.8"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.EXAMPLE",
            level=3,
            num="10.8.1",
        ),
        Heading(name="Replication of InnoDB Storage Engine", level=1, num="11"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames",
            level=2,
            num="11.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree",
            level=2,
            num="11.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames",
            level=3,
            num="11.2.1",
        ),
        Heading(name="Data Types", level=1, num="12"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes",
            level=2,
            num="12.1",
        ),
        Heading(name="Integer Types", level=2, num="12.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes",
            level=3,
            num="12.2.1",
        ),
        Heading(name="Decimal", level=2, num="12.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal",
            level=3,
            num="12.3.1",
        ),
        Heading(name="Double", level=2, num="12.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double",
            level=3,
            num="12.4.1",
        ),
        Heading(name="DateTime", level=2, num="12.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime",
            level=3,
            num="12.5.1",
        ),
        Heading(name="Binary", level=2, num="12.6"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary",
            level=3,
            num="12.6.1",
        ),
        Heading(name="String", level=2, num="12.7"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String",
            level=3,
            num="12.7.1",
        ),
        Heading(name="Blob Types", level=2, num="12.8"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes",
            level=3,
            num="12.8.1",
        ),
        Heading(name="Nullable", level=2, num="12.9"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable",
            level=3,
            num="12.9.1",
        ),
        Heading(name="Enum", level=2, num="12.10"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum",
            level=3,
            num="12.10.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString",
            level=3,
            num="12.10.2",
        ),
        Heading(name="JSON", level=2, num="12.11"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON",
            level=3,
            num="12.11.1",
        ),
        Heading(name="Year", level=2, num="12.12"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year",
            level=3,
            num="12.12.1",
        ),
        Heading(name="Bytes", level=2, num="12.13"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes",
            level=3,
            num="12.13.1",
        ),
        Heading(name="Queries", level=1, num="13"),
        Heading(name="Inserts", level=2, num="13.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts",
            level=3,
            num="13.1.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits",
            level=4,
            num="13.1.1.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionTypes",
            level=4,
            num="13.1.1.2",
        ),
        Heading(name="Updates", level=2, num="13.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates",
            level=3,
            num="13.2.1",
        ),
        Heading(name="Deletes", level=2, num="13.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes",
            level=3,
            num="13.3.1",
        ),
        Heading(name="Table Schema Creation", level=1, num="14"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation",
            level=2,
            num="14.1",
        ),
        Heading(name="Auto Create", level=2, num="14.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate",
            level=3,
            num="14.2.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate",
            level=4,
            num="14.2.1.1",
        ),
        Heading(name="Auto Drop", level=2, num="14.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop",
            level=3,
            num="14.3.1",
        ),
        Heading(name="Alter", level=1, num="15"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter",
            level=2,
            num="15.1",
        ),
        Heading(name="Add Index", level=2, num="15.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex",
            level=3,
            num="15.2.1",
        ),
        Heading(name="Add Key", level=2, num="15.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey",
            level=3,
            num="15.3.1",
        ),
        Heading(name="Add FullText", level=2, num="15.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText",
            level=3,
            num="15.4.1",
        ),
        Heading(name="Add Special", level=2, num="15.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial",
            level=3,
            num="15.5.1",
        ),
        Heading(name="Drop Check", level=2, num="15.6"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck",
            level=3,
            num="15.6.1",
        ),
        Heading(name="Drop Default", level=2, num="15.7"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault",
            level=3,
            num="15.7.1",
        ),
        Heading(name="Check", level=2, num="15.8"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check",
            level=3,
            num="15.8.1",
        ),
        Heading(name="Constraint", level=2, num="15.9"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint",
            level=3,
            num="15.9.1",
        ),
        Heading(name="Index", level=2, num="15.10"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index",
            level=3,
            num="15.10.1",
        ),
        Heading(name="Character Set", level=2, num="15.11"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet",
            level=3,
            num="15.11.1",
        ),
        Heading(name="Convert To Character Set", level=2, num="15.12"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet",
            level=3,
            num="15.12.1",
        ),
        Heading(name="Algorithm", level=2, num="15.13"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm",
            level=3,
            num="15.13.1",
        ),
        Heading(name="Force", level=2, num="15.14"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force",
            level=3,
            num="15.14.1",
        ),
        Heading(name="Lock", level=2, num="15.15"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock",
            level=3,
            num="15.15.1",
        ),
        Heading(name="Unlock", level=2, num="15.16"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock",
            level=3,
            num="15.16.1",
        ),
        Heading(name="Validation", level=2, num="15.17"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation",
            level=3,
            num="15.17.1",
        ),
        Heading(name="Columns", level=2, num="15.18"),
        Heading(name="Add", level=3, num="15.18.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add",
            level=4,
            num="15.18.1.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull",
            level=4,
            num="15.18.1.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default",
            level=4,
            num="15.18.1.3",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter",
            level=4,
            num="15.18.1.4",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple",
            level=4,
            num="15.18.1.5",
        ),
        Heading(name="Modify", level=3, num="15.18.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify",
            level=4,
            num="15.18.2.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull",
            level=4,
            num="15.18.2.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default",
            level=4,
            num="15.18.2.3",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter",
            level=4,
            num="15.18.2.4",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple",
            level=4,
            num="15.18.2.5",
        ),
        Heading(name="Change", level=3, num="15.18.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew",
            level=4,
            num="15.18.3.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter",
            level=4,
            num="15.18.3.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple",
            level=4,
            num="15.18.3.3",
        ),
        Heading(name="Drop", level=3, num="15.18.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop",
            level=4,
            num="15.18.4.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple",
            level=4,
            num="15.18.4.2",
        ),
        Heading(name="Rename", level=3, num="15.18.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename",
            level=4,
            num="15.18.5.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple",
            level=4,
            num="15.18.5.2",
        ),
        Heading(name="Add Constraint", level=2, num="15.19"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint",
            level=3,
            num="15.19.1",
        ),
        Heading(name="Drop Constraint", level=2, num="15.20"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint",
            level=3,
            num="15.20.1",
        ),
        Heading(name="Primary Key", level=1, num="16"),
        Heading(name="No Primary Key", level=2, num="16.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No",
            level=3,
            num="16.1.1",
        ),
        Heading(name="Simple Primary Key", level=2, num="16.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple",
            level=3,
            num="16.2.1",
        ),
        Heading(name="Composite Primary Key", level=2, num="16.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite",
            level=3,
            num="16.3.1",
        ),
        Heading(name="Multiple Upstream Servers", level=1, num="17"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers",
            level=2,
            num="17.1",
        ),
        Heading(name="Multiple Downstream Servers", level=1, num="18"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers",
            level=2,
            num="18.1",
        ),
        Heading(name="Archival Mode", level=1, num="19"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode",
            level=2,
            num="19.1",
        ),
        Heading(name="Bootstrapping Mode", level=1, num="20"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode",
            level=2,
            num="20.1",
        ),
        Heading(name="Binlog Position", level=1, num="21"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition",
            level=2,
            num="21.1",
        ),
        Heading(name="Column Mapping And Transformation Rules", level=1, num="22"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules",
            level=2,
            num="22.1",
        ),
        Heading(name="Columns Inconsistency", level=1, num="23"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency",
            level=2,
            num="23.1",
        ),
        Heading(name="Latency", level=1, num="24"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency",
            level=2,
            num="24.1",
        ),
        Heading(name="Performance ", level=1, num="25"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance",
            level=2,
            num="25.1",
        ),
        Heading(name="Large Daily Data Volumes", level=2, num="25.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes",
            level=3,
            num="25.2.1",
        ),
        Heading(name="Settings", level=1, num="26"),
        Heading(name="clickhouse.topic2table.map", level=2, num="26.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap",
            level=3,
            num="26.1.1",
        ),
        Heading(name="Table Names", level=1, num="27"),
        Heading(name="Valid", level=2, num="27.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Valid",
            level=3,
            num="27.1.1",
        ),
        Heading(name="Invalid", level=2, num="27.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Invalid",
            level=3,
            num="27.2.1",
        ),
        Heading(name="Column Names", level=1, num="28"),
        Heading(name="Replicate Tables With Special Column Names", level=2, num="28.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Special",
            level=3,
            num="28.1.1",
        ),
        Heading(
            name="Replicate Tables With Backticks in Column Names", level=2, num="28.2"
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Backticks",
            level=3,
            num="28.2.1",
        ),
        Heading(name="Replication Interruption", level=1, num="29"),
        Heading(
            name="Retry Replication When ClickHouse Instance Is Not Active",
            level=2,
            num="29.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped",
            level=3,
            num="29.1.1",
        ),
        Heading(name="Sink Connector Actions From CLI", level=1, num="30"),
        Heading(name="Commands", level=2, num="30.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI",
            level=3,
            num="30.1.1",
        ),
        Heading(name="Start Replication", level=3, num="30.1.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StartReplication",
            level=4,
            num="30.1.2.1",
        ),
        Heading(name="Stop Replication", level=3, num="30.1.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StopReplication",
            level=4,
            num="30.1.3.1",
        ),
        Heading(name="Show Replication Status", level=3, num="30.1.4"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ShowReplicationStatus",
            level=4,
            num="30.1.4.1",
        ),
        Heading(name="Change Replication Source", level=3, num="30.1.5"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ChangeReplicationSource",
            level=4,
            num="30.1.5.1",
        ),
        Heading(name="Global Options", level=2, num="30.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.GlobalOptions",
            level=3,
            num="30.2.1",
        ),
        Heading(name="System Actions", level=1, num="31"),
        Heading(name="Handling Network Interruptions", level=2, num="31.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Network",
            level=3,
            num="31.1.1",
        ),
        Heading(name="Handling Process Interruptions", level=2, num="31.2"),
        Heading(
            name="Behaviour When Different Processes Were Killed", level=3, num="31.2.1"
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Die",
            level=4,
            num="31.2.1.1",
        ),
        Heading(
            name="Behaviour When Different Processes Were Restarted",
            level=3,
            num="31.2.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Restarted",
            level=4,
            num="31.2.2.1",
        ),
        Heading(name="Behaviour When There Are Issues With Disk", level=2, num="31.3"),
        Heading(name="Disk Is out of Space", level=3, num="31.3.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.OutOfSpace",
            level=4,
            num="31.3.1.1",
        ),
        Heading(name="Disk Is Corrupted", level=3, num="31.3.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.Corrupted",
            level=4,
            num="31.3.2.1",
        ),
        Heading(name="Prometheus", level=1, num="32"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus",
            level=2,
            num="32.1",
        ),
        Heading(name="ReplicatedReplacingMergeTree", level=1, num="33"),
        Heading(
            name="Test Schema For ReplicatedReplacingMergeTree", level=2, num="33.1"
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree",
            level=2,
            num="33.2",
        ),
        Heading(
            name="Types of Clusters That Can Be Used for ReplicatedReplacingMergeTree",
            level=2,
            num="33.3",
        ),
        Heading(name="Multiple Shards and Replicas", level=3, num="33.3.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.MultipleShardsAndReplicas",
            level=4,
            num="33.3.1.1",
        ),
        Heading(name="One Shard and One Replica", level=3, num="33.3.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.OneShardOneReplica",
            level=4,
            num="33.3.2.1",
        ),
        Heading(
            name="Secure Cluster with One Shard and One Replica", level=3, num="33.3.3"
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterOneShardOneReplica",
            level=4,
            num="33.3.3.1",
        ),
        Heading(
            name="Secure Cluster with Multiple Shards and Replicas",
            level=3,
            num="33.3.4",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterMultipleShardsAndReplicas",
            level=4,
            num="33.3.4.1",
        ),
        Heading(name="Possible Events", level=2, num="33.4"),
        Heading(name="Node Related Events", level=3, num="33.4.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.Killed",
            level=4,
            num="33.4.1.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.AllKilled",
            level=4,
            num="33.4.1.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.ChangeLeader",
            level=4,
            num="33.4.1.3",
        ),
        Heading(name="Replica Related Events", level=3, num="33.4.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.BehindLeader",
            level=4,
            num="33.4.2.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.DataInconsistency",
            level=4,
            num="33.4.2.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.NewReplica",
            level=4,
            num="33.4.2.3",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.RemovedReplica",
            level=4,
            num="33.4.2.4",
        ),
        Heading(name="Connection Related Events", level=3, num="33.4.3"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ConnectionRelatedEvents.Interrupted",
            level=4,
            num="33.4.3.1",
        ),
        Heading(name="Disk Related Events", level=3, num="33.4.4"),
        Heading(name="Out of Space", level=4, num="33.4.4.1"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.OutOfSpace",
            level=5,
            num="33.4.4.1.1",
        ),
        Heading(name="Corrupted Disk", level=4, num="33.4.4.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.Corrupted",
            level=5,
            num="33.4.4.2.1",
        ),
        Heading(name="Multiple Databases", level=1, num="34"),
        Heading(name="Test Schema - Multiple Databases ", level=2, num="34.1"),
        Heading(name="Databases on Source and Destination", level=2, num="34.2"),
        Heading(
            name="Multiple Databases on Source and Destination", level=3, num="34.2.1"
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases",
            level=4,
            num="34.2.1.1",
        ),
        Heading(
            name="Multiple Databases on Source and One Database on Destination",
            level=3,
            num="34.2.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceMultipleDestinationOne",
            level=4,
            num="34.2.2.1",
        ),
        Heading(
            name="One Database on Source and Multiple Databases on Destination",
            level=3,
            num="34.2.3",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationMultiple",
            level=4,
            num="34.2.3.1",
        ),
        Heading(
            name="One Database on Source and One Database on Destination",
            level=3,
            num="34.2.4",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationOne",
            level=4,
            num="34.2.4.1",
        ),
        Heading(
            name="Table Structure on Source and Destination Databases",
            level=2,
            num="34.3",
        ),
        Heading(
            name="Two Tables with the Same Name and Different Structure on Different Databases",
            level=3,
            num="34.3.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameDifferentStructure",
            level=4,
            num="34.3.1.1",
        ),
        Heading(
            name="Two Tables with the Same Name and the Same Structure on Different Databases",
            level=3,
            num="34.3.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameSameStructure",
            level=4,
            num="34.3.2.1",
        ),
        Heading(
            name="Two Tables with the Different Name and the Same Structure on Different Databases",
            level=3,
            num="34.3.3",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.DifferentNameSameStructure",
            level=4,
            num="34.3.3.1",
        ),
        Heading(name="Configuration Values", level=2, num="34.4"),
        Heading(
            name="Include Specific List of Databases To Replicate",
            level=3,
            num="34.4.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.IncludeList",
            level=4,
            num="34.4.1.1",
        ),
        Heading(name="Replicate All Databases", level=3, num="34.4.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.ReplicateAll",
            level=4,
            num="34.4.2.1",
        ),
        Heading(
            name="Overriding Source To Destination Database Name Mapping",
            level=2,
            num="34.5",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap",
            level=3,
            num="34.5.1",
        ),
        Heading(name="Multiple Database Names", level=3, num="34.5.2"),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap.MultipleValues",
            level=4,
            num="34.5.2.1",
        ),
        Heading(name="Table Operations", level=2, num="34.6"),
        Heading(
            name="Specify Database Name in Table Operations", level=3, num="34.6.1"
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.SpecifyDatabaseName",
            level=4,
            num="34.6.1.1",
        ),
        Heading(
            name="Table Operations Without Specifying Database Name",
            level=3,
            num="34.6.2",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.NoSpecifyDatabaseName",
            level=4,
            num="34.6.2.1",
        ),
        Heading(name="Error Handling", level=2, num="34.7"),
        Heading(
            name="When Replicated Database Does Not Exist on the Destination",
            level=3,
            num="34.7.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ErrorHandling.DatabaseNotExist",
            level=4,
            num="34.7.1.1",
        ),
        Heading(name="Concurrent Actions", level=2, num="34.8"),
        Heading(
            name="Perform Table Operations on Each Database Concurrently",
            level=3,
            num="34.8.1",
        ),
        Heading(
            name="RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConcurrentActions",
            level=4,
            num="34.8.1.1",
        ),
    ),
    requirements=(
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Configurations,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Select,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_OnlyOnceGuarantee,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Transactions,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLVersions,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_InnoDB,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_MyISAM,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_MEMORY,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_CSV,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ARCHIVE,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_BLACKHOLE,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_FEDERATED,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_EXAMPLE,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree_DifferentVersionColumnNames,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes,
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
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts_PartitionTypes,
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
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableNames_Valid,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableNames_Invalid,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Special,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Backticks,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Interruption_ClickHouse_Instance_Stopped,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StartReplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StopReplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_ShowReplicationStatus,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_ChangeReplicationSource,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_GlobalOptions,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Network,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Process_Die,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Process_Restarted,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Disk_OutOfSpace,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_SystemActions_Disk_Corrupted,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Prometheus,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_MultipleShardsAndReplicas,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_OneShardOneReplica,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_SecureClusterOneShardOneReplica,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_ClusterTypes_SecureClusterMultipleShardsAndReplicas,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_NodeRelatedEvents_Killed,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_NodeRelatedEvents_AllKilled,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_NodeRelatedEvents_ChangeLeader,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_BehindLeader,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_DataInconsistency,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_NewReplica,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ReplicaRelatedEvents_RemovedReplica,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_ConnectionRelatedEvents_Interrupted,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_Disk_OutOfSpace,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ReplicatedReplacingMergeTree_PossibleEvents_Disk_Corrupted,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_SourceMultipleDestinationOne,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_SourceOneDestinationMultiple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_SourceOneDestinationOne,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_Tables_SameNameDifferentStructure,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_Tables_SameNameSameStructure,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_Tables_DifferentNameSameStructure,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_IncludeList,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_ReplicateAll,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_OverrideMap,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_OverrideMap_MultipleValues,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_TableOperations_SpecifyDatabaseName,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_TableOperations_NoSpecifyDatabaseName,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ErrorHandling_DatabaseNotExist,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConcurrentActions,
    ),
    content=r"""
# SRS030 MySQL to ClickHouse Replication
# Software Requirements Specification

## Table of Contents

* 1 [Introduction](#introduction)
* 2 [Terminology](#terminology)
    * 2.1 [SRS](#srs)
    * 2.2 [MySQL](#mysql)
* 3 [Test Schema](#test-schema)
* 4 [Configuration](#configuration)
* 5 [General](#general)
    * 5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication](#rqsrs-030clickhousemysqltoclickhousereplication)
* 6 [Configurations](#configurations)
    * 6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Configurations](#rqsrs-030clickhousemysqltoclickhousereplicationconfigurations)
* 7 [Consistency](#consistency)
    * 7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency](#rqsrs-030clickhousemysqltoclickhousereplicationconsistency)
    * 7.2 [Multiple MySQL Masters](#multiple-mysql-masters)
        * 7.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.MultipleMySQLMasters](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencymultiplemysqlmasters)
    * 7.3 [Deduplication](#deduplication)
        * 7.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencydeduplication)
    * 7.4 [Selects](#selects)
        * 7.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencyselect)
    * 7.5 [Only Once Guarantee](#only-once-guarantee)
        * 7.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee](#rqsrs-030clickhousemysqltoclickhousereplicationonlyonceguarantee)
* 8 [Transactions](#transactions)
    * 8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions](#rqsrs-030clickhousemysqltoclickhousereplicationtransactions)
* 9 [Supported Versions](#supported-versions)
    * 9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlversions)
* 10 [Supported Storage Engines](#supported-storage-engines)
    * 10.1 [InnoDB](#innodb)
        * 10.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.InnoDB](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesinnodb)
    * 10.2 [MyISAM](#myisam)
        * 10.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MyISAM](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesmyisam)
    * 10.3 [MEMORY](#memory)
        * 10.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MEMORY](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesmemory)
    * 10.4 [CSV](#csv)
        * 10.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CSV](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginescsv)
    * 10.5 [ARCHIVE](#archive)
        * 10.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ARCHIVE](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesarchive)
    * 10.6 [BLACKHOLE](#blackhole)
        * 10.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.BLACKHOLE](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesblackhole)
    * 10.7 [FEDERATED](#federated)
        * 10.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.FEDERATED](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesfederated)
    * 10.8 [EXAMPLE](#example)
        * 10.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.EXAMPLE](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesexample)
* 11 [Replication of InnoDB Storage Engine](#replication-of-innodb-storage-engine)
    * 11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplacingmergetreevirtualcolumnnames)
    * 11.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplicatedreplacingmergetree)
        * 11.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplicatedreplacingmergetreedifferentversioncolumnnames)
* 12 [Data Types](#data-types)
    * 12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypes)
    * 12.2 [Integer Types](#integer-types)
        * 12.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesintegertypes)
    * 12.3 [Decimal](#decimal)
        * 12.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdecimal)
    * 12.4 [Double](#double)
        * 12.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdouble)
    * 12.5 [DateTime](#datetime)
        * 12.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdatetime)
    * 12.6 [Binary](#binary)
        * 12.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesbinary)
    * 12.7 [String](#string)
        * 12.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesstring)
    * 12.8 [Blob Types](#blob-types)
        * 12.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesblobtypes)
    * 12.9 [Nullable](#nullable)
        * 12.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesnullable)
    * 12.10 [Enum](#enum)
        * 12.10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesenumtoenum)
        * 12.10.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesenumtostring)
    * 12.11 [JSON](#json)
        * 12.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesjson)
    * 12.12 [Year](#year)
        * 12.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesyear)
    * 12.13 [Bytes](#bytes)
        * 12.13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesbytes)
* 13 [Queries](#queries)
    * 13.1 [Inserts](#inserts)
        * 13.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesinserts)
            * 13.1.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesinsertspartitionlimits)
            * 13.1.1.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionTypes](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesinsertspartitiontypes)
    * 13.2 [Updates](#updates)
        * 13.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesupdates)
    * 13.3 [Deletes](#deletes)
        * 13.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesdeletes)
* 14 [Table Schema Creation](#table-schema-creation)
    * 14.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreation)
    * 14.2 [Auto Create](#auto-create)
        * 14.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationautocreate)
            * 14.2.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationmultipleautocreate)
    * 14.3 [Auto Drop](#auto-drop)
        * 14.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationautodrop)
* 15 [Alter](#alter)
    * 15.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter](#rqsrs-030clickhousemysqltoclickhousereplicationalter)
    * 15.2 [Add Index](#add-index)
        * 15.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddindex)
    * 15.3 [Add Key](#add-key)
        * 15.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddkey)
    * 15.4 [Add FullText](#add-fulltext)
        * 15.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddfulltext)
    * 15.5 [Add Special](#add-special)
        * 15.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddspecial)
    * 15.6 [Drop Check](#drop-check)
        * 15.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropcheck)
    * 15.7 [Drop Default](#drop-default)
        * 15.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropdefault)
    * 15.8 [Check](#check)
        * 15.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check](#rqsrs-030clickhousemysqltoclickhousereplicationaltercheck)
    * 15.9 [Constraint](#constraint)
        * 15.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint](#rqsrs-030clickhousemysqltoclickhousereplicationalterconstraint)
    * 15.10 [Index](#index)
        * 15.10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index](#rqsrs-030clickhousemysqltoclickhousereplicationalterindex)
    * 15.11 [Character Set](#character-set)
        * 15.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet](#rqsrs-030clickhousemysqltoclickhousereplicationaltercharacterset)
    * 15.12 [Convert To Character Set](#convert-to-character-set)
        * 15.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet](#rqsrs-030clickhousemysqltoclickhousereplicationalterconverttocharacterset)
    * 15.13 [Algorithm](#algorithm)
        * 15.13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm](#rqsrs-030clickhousemysqltoclickhousereplicationalteralgorithm)
    * 15.14 [Force](#force)
        * 15.14.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force](#rqsrs-030clickhousemysqltoclickhousereplicationalterforce)
    * 15.15 [Lock](#lock)
        * 15.15.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock](#rqsrs-030clickhousemysqltoclickhousereplicationalterlock)
    * 15.16 [Unlock](#unlock)
        * 15.16.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock](#rqsrs-030clickhousemysqltoclickhousereplicationalterunlock)
    * 15.17 [Validation](#validation)
        * 15.17.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation](#rqsrs-030clickhousemysqltoclickhousereplicationaltervalidation)
    * 15.18 [Columns](#columns)
        * 15.18.1 [Add](#add)
            * 15.18.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsadd)
            * 15.18.1.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddnullnotnull)
            * 15.18.1.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsadddefault)
            * 15.18.1.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddfirstafter)
            * 15.18.1.5 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddmultiple)
        * 15.18.2 [Modify](#modify)
            * 15.18.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodify)
            * 15.18.2.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifynullnotnull)
            * 15.18.2.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifydefault)
            * 15.18.2.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifyfirstafter)
            * 15.18.2.5 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifymultiple)
        * 15.18.3 [Change](#change)
            * 15.18.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangenullnotnulloldnew)
            * 15.18.3.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangefirstafter)
            * 15.18.3.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangemultiple)
        * 15.18.4 [Drop](#drop)
            * 15.18.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsdrop)
            * 15.18.4.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsdropmultiple)
        * 15.18.5 [Rename](#rename)
            * 15.18.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsrename)
            * 15.18.5.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsrenamemultiple)
    * 15.19 [Add Constraint](#add-constraint)
        * 15.19.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddconstraint)
    * 15.20 [Drop Constraint](#drop-constraint)
        * 15.20.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropconstraint)
* 16 [Primary Key](#primary-key)
    * 16.1 [No Primary Key](#no-primary-key)
        * 16.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeyno)
    * 16.2 [Simple Primary Key](#simple-primary-key)
        * 16.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeysimple)
    * 16.3 [Composite Primary Key](#composite-primary-key)
        * 16.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeycomposite)
* 17 [Multiple Upstream Servers](#multiple-upstream-servers)
    * 17.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipleupstreamservers)
* 18 [Multiple Downstream Servers](#multiple-downstream-servers)
    * 18.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledownstreamservers)
* 19 [Archival Mode](#archival-mode)
    * 19.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode](#rqsrs-030clickhousemysqltoclickhousereplicationarchivalmode)
* 20 [Bootstrapping Mode](#bootstrapping-mode)
    * 20.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode](#rqsrs-030clickhousemysqltoclickhousereplicationbootstrappingmode)
* 21 [Binlog Position](#binlog-position)
    * 21.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition](#rqsrs-030clickhousemysqltoclickhousereplicationbinlogposition)
* 22 [Column Mapping And Transformation Rules](#column-mapping-and-transformation-rules)
    * 22.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnmappingandtransformationrules)
* 23 [Columns Inconsistency](#columns-inconsistency)
    * 23.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnsinconsistency)
* 24 [Latency](#latency)
    * 24.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency](#rqsrs-030clickhousemysqltoclickhousereplicationlatency)
* 25 [Performance ](#performance-)
    * 25.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance](#rqsrs-030clickhousemysqltoclickhousereplicationperformance)
    * 25.2 [Large Daily Data Volumes](#large-daily-data-volumes)
        * 25.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes](#rqsrs-030clickhousemysqltoclickhousereplicationperformancelargedailydatavolumes)
* 26 [Settings](#settings)
    * 26.1 [clickhouse.topic2table.map](#clickhousetopic2tablemap)
        * 26.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap](#rqsrs-030clickhousemysqltoclickhousereplicationsettingstopic2tablemap)
* 27 [Table Names](#table-names)
    * 27.1 [Valid](#valid)
        * 27.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Valid](#rqsrs-030clickhousemysqltoclickhousereplicationtablenamesvalid)
    * 27.2 [Invalid](#invalid)
        * 27.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Invalid](#rqsrs-030clickhousemysqltoclickhousereplicationtablenamesinvalid)
* 28 [Column Names](#column-names)
    * 28.1 [Replicate Tables With Special Column Names](#replicate-tables-with-special-column-names)
        * 28.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Special](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnnamesspecial)
    * 28.2 [Replicate Tables With Backticks in Column Names](#replicate-tables-with-backticks-in-column-names)
        * 28.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Backticks](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnnamesbackticks)
* 29 [Replication Interruption](#replication-interruption)
    * 29.1 [Retry Replication When ClickHouse Instance Is Not Active](#retry-replication-when-clickhouse-instance-is-not-active)
        * 29.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped](#rqsrs-030clickhousemysqltoclickhousereplicationinterruptionclickhouseinstancestopped)
* 30 [Sink Connector Actions From CLI](#sink-connector-actions-from-cli)
    * 30.1 [Commands](#commands)
        * 30.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI](#rqsrs-030clickhousemysqltoclickhousereplicationcli)
        * 30.1.2 [Start Replication](#start-replication)
            * 30.1.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StartReplication](#rqsrs-030clickhousemysqltoclickhousereplicationclistartreplication)
        * 30.1.3 [Stop Replication](#stop-replication)
            * 30.1.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StopReplication](#rqsrs-030clickhousemysqltoclickhousereplicationclistopreplication)
        * 30.1.4 [Show Replication Status](#show-replication-status)
            * 30.1.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ShowReplicationStatus](#rqsrs-030clickhousemysqltoclickhousereplicationclishowreplicationstatus)
        * 30.1.5 [Change Replication Source](#change-replication-source)
            * 30.1.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ChangeReplicationSource](#rqsrs-030clickhousemysqltoclickhousereplicationclichangereplicationsource)
    * 30.2 [Global Options](#global-options)
        * 30.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.GlobalOptions](#rqsrs-030clickhousemysqltoclickhousereplicationcliglobaloptions)
* 31 [System Actions](#system-actions)
    * 31.1 [Handling Network Interruptions](#handling-network-interruptions)
        * 31.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Network](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsnetwork)
    * 31.2 [Handling Process Interruptions](#handling-process-interruptions)
        * 31.2.1 [Behaviour When Different Processes Were Killed](#behaviour-when-different-processes-were-killed)
            * 31.2.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Die](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsprocessdie)
        * 31.2.2 [Behaviour When Different Processes Were Restarted](#behaviour-when-different-processes-were-restarted)
            * 31.2.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Restarted](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsprocessrestarted)
    * 31.3 [Behaviour When There Are Issues With Disk](#behaviour-when-there-are-issues-with-disk)
        * 31.3.1 [Disk Is out of Space](#disk-is-out-of-space)
            * 31.3.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.OutOfSpace](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsdiskoutofspace)
        * 31.3.2 [Disk Is Corrupted](#disk-is-corrupted)
            * 31.3.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.Corrupted](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsdiskcorrupted)
* 32 [Prometheus](#prometheus)
    * 32.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus](#rqsrs-030clickhousemysqltoclickhousereplicationprometheus)
* 33 [ReplicatedReplacingMergeTree](#replicatedreplacingmergetree)
    * 33.1 [Test Schema For ReplicatedReplacingMergeTree](#test-schema-for-replicatedreplacingmergetree)
    * 33.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetree)
    * 33.3 [Types of Clusters That Can Be Used for ReplicatedReplacingMergeTree](#types-of-clusters-that-can-be-used-for-replicatedreplacingmergetree)
        * 33.3.1 [Multiple Shards and Replicas](#multiple-shards-and-replicas)
            * 33.3.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.MultipleShardsAndReplicas](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreeclustertypesmultipleshardsandreplicas)
        * 33.3.2 [One Shard and One Replica](#one-shard-and-one-replica)
            * 33.3.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.OneShardOneReplica](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreeclustertypesoneshardonereplica)
        * 33.3.3 [Secure Cluster with One Shard and One Replica](#secure-cluster-with-one-shard-and-one-replica)
            * 33.3.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterOneShardOneReplica](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreeclustertypessecureclusteroneshardonereplica)
        * 33.3.4 [Secure Cluster with Multiple Shards and Replicas](#secure-cluster-with-multiple-shards-and-replicas)
            * 33.3.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterMultipleShardsAndReplicas](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreeclustertypessecureclustermultipleshardsandreplicas)
    * 33.4 [Possible Events](#possible-events)
        * 33.4.1 [Node Related Events](#node-related-events)
            * 33.4.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.Killed](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsnoderelatedeventskilled)
            * 33.4.1.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.AllKilled](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsnoderelatedeventsallkilled)
            * 33.4.1.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.ChangeLeader](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsnoderelatedeventschangeleader)
        * 33.4.2 [Replica Related Events](#replica-related-events)
            * 33.4.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.BehindLeader](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsreplicarelatedeventsbehindleader)
            * 33.4.2.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.DataInconsistency](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsreplicarelatedeventsdatainconsistency)
            * 33.4.2.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.NewReplica](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsreplicarelatedeventsnewreplica)
            * 33.4.2.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.RemovedReplica](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsreplicarelatedeventsremovedreplica)
        * 33.4.3 [Connection Related Events](#connection-related-events)
            * 33.4.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ConnectionRelatedEvents.Interrupted](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsconnectionrelatedeventsinterrupted)
        * 33.4.4 [Disk Related Events](#disk-related-events)
            * 33.4.4.1 [Out of Space](#out-of-space)
                * 33.4.4.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.OutOfSpace](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsdiskoutofspace)
            * 33.4.4.2 [Corrupted Disk](#corrupted-disk)
                * 33.4.4.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.Corrupted](#rqsrs-030clickhousemysqltoclickhousereplicationreplicatedreplacingmergetreepossibleeventsdiskcorrupted)
* 34 [Multiple Databases](#multiple-databases)
    * 34.1 [Test Schema - Multiple Databases ](#test-schema---multiple-databases-)
    * 34.2 [Databases on Source and Destination](#databases-on-source-and-destination)
        * 34.2.1 [Multiple Databases on Source and Destination](#multiple-databases-on-source-and-destination)
            * 34.2.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabases)
        * 34.2.2 [Multiple Databases on Source and One Database on Destination](#multiple-databases-on-source-and-one-database-on-destination)
            * 34.2.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceMultipleDestinationOne](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasessourcemultipledestinationone)
        * 34.2.3 [One Database on Source and Multiple Databases on Destination](#one-database-on-source-and-multiple-databases-on-destination)
            * 34.2.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationMultiple](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasessourceonedestinationmultiple)
        * 34.2.4 [One Database on Source and One Database on Destination](#one-database-on-source-and-one-database-on-destination)
            * 34.2.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationOne](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasessourceonedestinationone)
    * 34.3 [Table Structure on Source and Destination Databases](#table-structure-on-source-and-destination-databases)
        * 34.3.1 [Two Tables with the Same Name and Different Structure on Different Databases](#two-tables-with-the-same-name-and-different-structure-on-different-databases)
            * 34.3.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameDifferentStructure](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasestablessamenamedifferentstructure)
        * 34.3.2 [Two Tables with the Same Name and the Same Structure on Different Databases](#two-tables-with-the-same-name-and-the-same-structure-on-different-databases)
            * 34.3.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameSameStructure](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasestablessamenamesamestructure)
        * 34.3.3 [Two Tables with the Different Name and the Same Structure on Different Databases](#two-tables-with-the-different-name-and-the-same-structure-on-different-databases)
            * 34.3.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.DifferentNameSameStructure](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasestablesdifferentnamesamestructure)
    * 34.4 [Configuration Values](#configuration-values)
        * 34.4.1 [Include Specific List of Databases To Replicate](#include-specific-list-of-databases-to-replicate)
            * 34.4.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.IncludeList](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasesconfigvaluesincludelist)
        * 34.4.2 [Replicate All Databases](#replicate-all-databases)
            * 34.4.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.ReplicateAll](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasesconfigvaluesreplicateall)
    * 34.5 [Overriding Source To Destination Database Name Mapping](#overriding-source-to-destination-database-name-mapping)
        * 34.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasesconfigvaluesoverridemap)
        * 34.5.2 [Multiple Database Names](#multiple-database-names)
            * 34.5.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap.MultipleValues](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasesconfigvaluesoverridemapmultiplevalues)
    * 34.6 [Table Operations](#table-operations)
        * 34.6.1 [Specify Database Name in Table Operations](#specify-database-name-in-table-operations)
            * 34.6.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.SpecifyDatabaseName](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasestableoperationsspecifydatabasename)
        * 34.6.2 [Table Operations Without Specifying Database Name](#table-operations-without-specifying-database-name)
            * 34.6.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.NoSpecifyDatabaseName](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasestableoperationsnospecifydatabasename)
    * 34.7 [Error Handling](#error-handling)
        * 34.7.1 [When Replicated Database Does Not Exist on the Destination](#when-replicated-database-does-not-exist-on-the-destination)
            * 34.7.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ErrorHandling.DatabaseNotExist](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabaseserrorhandlingdatabasenotexist)
    * 34.8 [Concurrent Actions](#concurrent-actions)
        * 34.8.1 [Perform Table Operations on Each Database Concurrently](#perform-table-operations-on-each-database-concurrently)
            * 34.8.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConcurrentActions](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledatabasesconcurrentactions)

## Introduction

This software requirements specification covers requirements related to [Altinity Sink Connector]'s
support for data replication from [MySQL] databases to [ClickHouse].

## Terminology

### SRS

Software Requirements Specification

### MySQL

[MySQL](https://www.mysql.com/) server.

## Test Schema

```yaml
clickhouse-sink-connector:
    Services:
      - Source Database Cluster
      - Sink Connector
      - ClickHouse Database Cluster
    SourceTables:
      DatabaseType: [MySQL, PostgreSQL, MariaDB, MongoDB]
      MySQL:
          DatabaseVersion: 8.0
          DatabaseClusterConfiguration:
            - Standalone
            - Primary and replica
            - High available clusters
            - Multi-primary
            - Hosted
          EngineType: [InnoDB, MyISAM, MEMORY, CSV, ARCHIVE, BLACKHOLE, FEDERATED, EXAMPLE]
          Schema:
            TableName:
              Length: 64 characters
              Encodings:
                  - ASCII
                  - UTF-8
                  - otherEncodings: [armscii8, big5, binary, cp1250, cp1251, cp1256, cp1257, cp850, cp852, cp866, cp932, dec8, 
                                 eucjpms, euckr, gb18030, gb2312, gbk, geostd8, greek, hebrew, hp8, keybcs2, koi8r, koi8u, latin1, 
                                 latin2, latin5, latin7, macce, macroman, sjis, swe7, tis620, ucs2, ujis, utf16, utf16le, utf32, utf8mb3, utf8mb4]
          Partitioning: 
            - RANGE 
            - LIST 
            - COLUMNS 
            - HASH 
            - KEY 
            - Subpartitioning
          Columns:
              DefaultValues: [Numeric Types, Date and Time Types, String Types, ENUM Types, SET Types, BOOLEAN, Binary Types]
              Type: [Calculated Columns, Materialized Columns, Primary Key Columns, Foreign Key Columns, 
                     Index Columns, Unique Columns, Auto-Increment Columns, Timestamp/DateTime Columns, 
                     ENUM and SET Columns, Spatial Columns]
              Name:
                Length: 64 characters
                Encodings:
                  - ASCII
                  - UTF-8
                  - OtherEncodings: [armscii8, big5, binary, cp1250, cp1251, cp1256, cp1257, cp850, cp852, cp866, cp932, dec8, 
                                 eucjpms, euckr, gb18030, gb2312, gbk, geostd8, greek, hebrew, hp8, keybcs2, koi8r, koi8u, latin1, 
                                 latin2, latin5, latin7, macce, macroman, sjis, swe7, tis620, ucs2, ujis, utf16, utf16le, utf32, utf8mb3, utf8mb4]
              DataType:
                  - DECIMAL(2,1)
                  - DECIMAL(30, 10)
                  - DOUBLE
                  - DATE
                  - DATETIME(1-6)
                  - TIME(1-6)
                  - INT
                  - INT UNSIGNED
                  - BIGINT
                  - BIGINT UNSIGNED NOT NULL
                  - TINYINT
                  - TINYINT UNSIGNED
                  - SMALLINT
                  - SMALLINT UNSIGNED
                  - MEDIUMINT
                  - MEDIUMINT UNSIGNED
                  - CHAR
                  - TEXT
                  - VARCHAR(1-32766)
                  - BLOB
                  - MEDIUMBLOB
                  - LONGBLOB
                  - BINARY
                  - VARBINARY(4)
              DataValue:
                Numeric: [Min, Max, 0, -infinity, +infinity, nan, random value]
                Decimal: [Min value based on precision and scale, Max value based on precision and scale, 0, -0.0001, 
                          "0.0001", -Max value based on precision and scale, +Max value based on precision and scale, 
                          "NaN", A random value within precision and scale]
                String:
                  Bytes: [null bytes]
                  UTF-8: [C0 Controls and Basic Latin, Latin Extended-A, Spacing Modifiers, Diacritical Marks, 
                          Greek and Coptic, Cyrillic Basic, Currency Symbols, Mathematical Operators, Miscellaneous Symbols, Dingbats]
                  ASCII: [All ASCII characters]
                  otherEncodings: [armscii8, big5, binary, cp1250, cp1251, cp1256, cp1257, cp850, cp852, cp866, cp932, dec8, 
                                 eucjpms, euckr, gb18030, gb2312, gbk, geostd8, greek, hebrew, hp8, keybcs2, koi8r, koi8u, latin1, 
                                 latin2, latin5, latin7, macce, macroman, sjis, swe7, tis620, ucs2, ujis, utf16, utf16le, 
                                   utf32, utf8mb3, utf8mb4]
                  TableOperations:
                    - INSERT
                    - UPDATE
                    - DELETE
                    - SELECT
                    - ALTER:
                      - ADD COLUMN                                            
                      - ADD COLUMN NULL/NOT NULL                              
                      - ADD COLUMN DEFAULT                                    
                      - ADD COLUMN FIRST, AFTER                               
                      - DROP COLUMN                                           
                      - MODIFY COLUMN data_type                               
                      - MODIFY COLUMN data_type NULL/NOT NULL                 
                      - MODIFY COLUMN data_type DEFAULT                       
                      - MODIFY COLUMN FIRST, AFTER                            
                      - MODIFY COLUMN old_name new_name datatype NULL/NOT NULL
                      - RENAME COLUMN col1 to col2                            
                      - CHANGE COLUMN FIRST, AFTER                            
                      - ALTER COLUMN col_name ADD DEFAULT                     
                      - ALTER COLUMN col_name ADD DROP DEFAULT                
                      - ADD PRIMARY KEY                                       
      PostgreSQL:
          DatabaseVersion: null
          DatabaseClusterConfiguration: null
          EngineType: null
          Schema:
            TableName:
              Length: null
              Encodings: null
          Partitioning: null
          Columns:
              DefaultValues: null
              Type: null
              Name:
                Length: null
                Encodings: null
              DataType: null
              DataValue:
                Numeric: []
                Decimal: []
                String:
                  Bytes: []
                  UTF-8: []
                  ASCII: []
                  otherEncodings: null
                  TableOperations: null
      MariaDB:
          DatabaseVersion: null
          DatabaseClusterConfiguration: null
          EngineType: null
          Schema:
            TableName:
              Length: null
              Encodings: null
          Partitioning: null
          Columns:
              DefaultValues: null
              Type: null
              Name:
                Length: null
                Encodings: null
              DataType: null
              DataValue:
                Numeric: []
                Decimal: []
                String:
                  Bytes: []
                  UTF-8: []
                  ASCII: []
                  otherEncodings: null
                  TableOperations: null
      MongoDB:
          DatabaseVersion: null
          DatabaseClusterConfiguration: null
          EngineType: null
          Schema:
            TableName:
              Length: null
              Encodings: null
          Partitioning: null
          Columns:
              DefaultValues: null
              Type: null
              Name:
                Length: null
                Encodings: null
              DataType: null
              DataValue:
                Numeric: []
                Decimal: []
                String:
                  Bytes: []
                  UTF-8: []
                  ASCII: []
                  otherEncodings: null
                  TableOperations: null
      
    SinkConnector:
      Version: [latest]
      Configuration: The full list is inside configurations below
    
    DestinationTables:
      DatabaseType: [ClickHouse]
      DatabaseClusterConfiguration: 
        - One node
        - Sharded cluster secure
        - Cluster with Multiple Shards
        - Cluster with Replication (but a single shard)
        - Cluster with Multiple Shards and Replicas
        - Secure Cluster
      DatabaseVersion: [22.8, 23.3, 23.11, 23.12]
      EngineType: [ReplacingMergeTree, ReplicatedReplacingMergeTree]
      TableOperations:
       - INSERT
       - UPDATE
       - DELETE
       - SELECT
       - ALTER

    SystemActions:
      Network:
        - Internal network interruptions in source database cluster
        - Network interruptions from source database to sink connector
        - Network interruptions from sink connector to clickhouse
        - Internal network interruptions in clickhouse database cluster
      Process:
        Die:
          - Internal processes die in source database cluster
          - Sink connector dies
          - Internal processes die in clickhouse database cluster
        Restarted:
          - Restart of some or all nodes in source database cluster
          - Restart of sink connector
          - Restart of some or all nodes in clickhouse database cluster
      Disk:
        OutOfSpace:
          - Out of disk space on some node in source database cluster
          - Out of disk space where sink connector is running
          - Out of disk space on some node in clickhouse database cluster
        Corruptions:
          - Corruption on a disk used by some node in source database cluster
          - Corruption on a disk where sink connector is running
          - Corruption on a disk used by some node in clickhouse database cluster
```

## Configuration

| Name                                                     | Description                                                                                                                                                                                                                                                                                             |
|----------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `name`                                                   | Unique name for the connector.                                                                                                                                                                                                                                                                          |
| `database.hostname`                                      | IP address or hostname of the MySQL database server.                                                                                                                                                                                                                                                    |
| `database.port`                                          | Integer port number of the MySQL database server listening for client connections.                                                                                                                                                                                                                      |
| `database.user`                                          | Name of the MySQL database user to be used when connecting to the database.                                                                                                                                                                                                                             |
| `database.password`                                      | Password of the MySQL database user to be used when connecting to the database.                                                                                                                                                                                                                         |
| `database.server.name`                                   | The name of the MySQL database from which events are to be captured when not using snapshot mode.                                                                                                                                                                                                       |
| `database.include.list`                                  | An optional list of regular expressions that match database names to be monitored. any database name not included in the whitelist will be excluded from monitoring. By default all databases will be monitored.                                                                                        |
| `table.include.list`                                     | An optional list of regular expressions that match fully-qualified table identifiers for tables to be monitored.                                                                                                                                                                                        |
| `clickhouse.server.url`                                  | Clickhouse Server URL, Specify only the hostname.                                                                                                                                                                                                                                                       |
| `clickhouse.server.user`                                 | Clickhouse Server User                                                                                                                                                                                                                                                                                  |
| `clickhouse.server.password`                             | Clickhouse Server Password                                                                                                                                                                                                                                                                              |
| `clickhouse.server.port`                                 | Clickhouse Server Port                                                                                                                                                                                                                                                                                  |
| `clickhouse.server.database`                             | Clickhouse Server Database                                                                                                                                                                                                                                                                              |
| `database.allowPublicKeyRetrieval`                       |                                                                                                                                                                                                                                                                                                         |
| `snapshot.mode`                                          | Debezium can use different modes when it runs a snapshot. The snapshot mode is determined by the snapshot.mode configuration property. The default value of the property is initial. You can customize the way that the connector creates snapshots by changing the value of the snapshot.mode property |
| `offset.flush.interval.ms`                               | The number of milliseconds to wait before flushing recent offsets to Kafka. This ensures that offsets are committed within the specified time interval.                                                                                                                                                 |
| `connector.class`                                        | The Java class for the connector. This must be set to io.debezium.connector.mysql.MySqlConnector.                                                                                                                                                                                                       |
| `offset.storage`                                         | The Java class that implements the offset storage strategy. This must be set to io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore.                                                                                                                                                                 |
| `offset.storage.jdbc.offset.table.name`                  | The name of the database table where connector offsets are to be stored.                                                                                                                                                                                                                                |
| `offset.storage.jdbc.url`                                | The JDBC URL for the database where connector offsets are to be stored.                                                                                                                                                                                                                                 |
| `offset.storage.jdbc.user`                               | The name of the database user to be used when connecting to the database where connector offsets are to be stored.                                                                                                                                                                                      |
| `offset.storage.jdbc.password`                           | The password of the database user to be used when connecting to the database where connector offsets are to be stored.                                                                                                                                                                                  |
| `offset.storage.jdbc.offset.table.ddl`                   | The DDL statement used to create the database table where connector offsets are to be stored.(Advanced)                                                                                                                                                                                                 |
| `offset.storage.jdbc.offset.table.delete`                | The DML statement used to delete the database table where connector offsets are to be stored.(Advanced)                                                                                                                                                                                                 |
| `offset.storage.jdbc.offset.table.select`                |                                                                                                                                                                                                                                                                                                         |
| `schema.history.internal`                                | The Java class that implements the schema history strategy. This must be set to io.debezium.storage.jdbc.history.JdbcSchemaHistory.                                                                                                                                                                     |
| `schema.history.internal.jdbc.url`                       | The JDBC URL for the database where connector schema history is to be stored.                                                                                                                                                                                                                           |
| `schema.history.internal.jdbc.user`                      | The name of the database user to be used when connecting to the database where connector schema history is to be stored.                                                                                                                                                                                |
| `schema.history.internal.jdbc.password`                  | The password of the database user to be used when connecting to the database where connector schema history is to be stored.                                                                                                                                                                            |
| `schema.history.internal.jdbc.schema.history.table.ddl`  | The DDL statement used to create the database table where connector schema history is to be stored.(Advanced)                                                                                                                                                                                           |
| `schema.history.internal.jdbc.schema.history.table.name` | The name of the database table where connector schema history is to be stored.                                                                                                                                                                                                                          |
| `enable.snapshot.ddl`                                    | If set to true, the connector will parse the DDL statements from the initial load.                                                                                                                                                                                                                      |
| `persist.raw.bytes`                                      | If set to true, the connector will persist raw bytes as received in a String column.                                                                                                                                                                                                                    |
| `auto.create.tables`                                     | If set to true, the connector will create tables in the target based on the schema received in the incoming message.                                                                                                                                                                                    |
| `database.connectionTimeZone`                            | The timezone of the MySQL database server used to correctly shift the commit transaction timestamp.                                                                                                                                                                                                     |
| `clickhouse.datetime.timezone`                           | This timezone will override the default timezone of ClickHouse server. Timezone columns will be set to this timezone.                                                                                                                                                                                   |
| `skip_replica_start`                                     | If set to true, the connector will skip replication on startup. sink-connector-client start_replica will start replication.                                                                                                                                                                             |
| `binary.handling.mode`                                   | The mode for handling binary values. Possible values are bytes, base64, and decode. The default is bytes.                                                                                                                                                                                               |
| `ignore_delete`                                          | If set to true, the connector will ignore delete events. The default is false.                                                                                                                                                                                                                          |
| `disable.ddl`                                            | If set to true, the connector will ignore DDL events. The default is false.                                                                                                                                                                                                                             |
| `disable.drop.truncate`                                  | If set to true, the connector will ignore drop and truncate events. The default is false.                                                                                                                                                                                                               |
| `restart.event.loop`                                     | This will restart the CDC event loop if there are no messages received after timeout specified in `restart.event.loop.timeout.period.secs`.                                                                                                                                                             |
| `restart.event.loop.timeout.period.secs`                 | Defines the restart timeout period.                                                                                                                                                                                                                                                                     |
| `buffer.max.records`                                     | Max number of records for the flush buffer.                                                                                                                                                                                                                                                             |
| `clickhouse.jdbc.params`                                 | ClickHouse JDBC configuration parameters, as a list of key-value pairs separated by commas.                                                                                                                                                                                                             ||                                                          |                                                                                                                                                                                                                                                                                                         |


## General

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication
version: 1.0

[Altinity Sink Connector] SHALL support replication of single or multiple tables from [MySQL] database.

## Configurations

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Configurations
version: 1.0

[Altinity Sink Connector] SHALL support replication of single or multiple tables from [MySQL] database.

## Consistency

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency

[Altinity Sink Connector] SHALL support consistent data replication from [MySQL] to [ClickHouse].

### Multiple MySQL Masters

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.MultipleMySQLMasters

[Altinity Sink Connector] SHALL support consistent data replication from [MySQL] to [ClickHouse] when one or more MySQL
masters are going down.

### Deduplication

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication
version: 1.0

[Altinity Sink Connector] SHALL support data deduplication when it receives the same data twice from [MySQL].

### Selects

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select
version: 1.0

[ClickHouse] `SELECT ... FINAL` query SHALL always return exactly same data as [MySQL].

### Only Once Guarantee

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with only-once guarantee.
Block level de-duplication SHALL be used if it is going to replicated tables
but the publisher SHALL publish only once.

The following cases SHALL be supported:

1. [MySQL] database crash
2. [MySQL] database event stream provider crash
3. [MySQL] restart
3. [ClickHouse] server crash
4. [ClickHouse] server restart
5. [Altinity Sink Connector] server crash
6. [Altinity Sink Connector] server restart

## Transactions

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions
version: 1.0

[Altinity Sink Connector] SHALL support transactions for replicated [MySQL] tables.

## Supported Versions

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions
version: 1.0
 
[Altinity Sink Connector] SHALL support replication from the following [MySQL] versions:

* [MySQL 8.0](https://dev.mysql.com/doc/refman/8.0/en/)

## Supported Storage Engines

### InnoDB

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.InnoDB
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'InnoDB' storage engine in MySQL:

### MyISAM

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MyISAM
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'MyISAM' storage engine in MySQL:

### MEMORY

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MEMORY
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'MEMORY' storage engine in MySQL:

### CSV

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CSV
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'CSV' storage engine in MySQL:

### ARCHIVE

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ARCHIVE
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'ARCHIVE' storage engine in MySQL:

### BLACKHOLE

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.BLACKHOLE
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'BLACKHOLE' storage engine in MySQL:

### FEDERATED

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.FEDERATED
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'FEDERATED' storage engine in MySQL:

### EXAMPLE

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.EXAMPLE
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'EXAMPLE' storage engine in MySQL:

## Replication of InnoDB Storage Engine

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplacingMergeTree" [ClickHouse] table engine and virtual column names by default should be "_version" and "_sign".


### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplicatedReplacingMergeTree" [ClickHouse] table engine.

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplicatedReplacingMergeTree" [ClickHouse] table engine with different version column names.

## Data Types

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables with any datatypes that [MySQL] supports.

| MySQL Datatypes    |
|--------------------|
| DECIMAL            |
| DOUBLE             |
| DATE               |
| DATETIME(1-6)      |
| TIME(1-6)          |
| INT                |
| INT UNSIGNED       |
| BIGINT             |
| BIGINT UNSIGNED    |
| TINYINT            |
| TINYINT UNSIGNED   |
| SMALLINT           |
| SMALLINT UNSIGNED  |
| MEDIUMINT          |
| MEDIUMINT UNSIGNED |
| CHAR               |
| TEXT               |
| VARCHAR(1-32766)   |
| BLOB               |
| MEDIUMBLOB         |
| LONGBLOB           |
| BINARY             |
| VARBINARY          |

### Integer Types

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes
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

### Decimal

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with
'Decimal' data types as they supported by [MySQL].

[ClickHouse]'s 'Decimal32(S)', 'Decimal64(S)', 'Decimal128(S)', 'Decimal256(S)' also can be
manually used for [MySQL] 'Decimal'.

Data types connection table:

| MySQL        |  ClickHouse  |
|:-------------|:------------:|
| Decimal(x,y) | Decimal(x,y) |

### Double

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with
'Double' data types as they supported by [MySQL].

Data types connection table:

| MySQL        |  ClickHouse  |
|:-------------|:------------:|
| Double       |   Float64    |

### DateTime

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'Data' and 'Time'
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

### Binary

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] replication of tables that contain columns with 'Binary'
data types as they supported by [MySQL].

Data types connection table:

| MySQL        |              ClickHouse              |
|:-------------|:------------------------------------:|
| Binary       |             String + hex             |
| varbinary(*) |             String + hex             |

### String

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'String'
data types as they supported by [MySQL].

Data types connection table:

| MySQL        |           ClickHouse            |
|:-------------|:-------------------------------:|
| Char         | String / LowCardinality(String) |
| Text         |             String              |
| varbinary(*) |          String + hex           |
| varchar(*)   |             String              |

### Blob Types

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'Blob' [MySQL]
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

### Nullable

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with NULL [MySQL]
data types if this expected `Nullable(DataType)` construction should be used.

For example, [MySQL] `VARCHAR(*)` maps to [ClickHouse] `Nullable(String)` and MySQL
`VARCHAR(*) NOT NULL` maps to [ClickHouse] `String`

### Enum

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'ENUM'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| ENUM  |    ENUM    |

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'ENUM'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| ENUM  |   String   |

### JSON

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'JSON'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| JSON  |   String   |

### Year

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'Year'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| Year  |   Int32    |

### Bytes

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with 'BIT(m)'
data types where m: 2 - 64 as they supported by [MySQL].

Data types connection table:

| MySQL  | ClickHouse |
|:-------|:----------:|
| BIT(m) |   String   |


## Queries

### Inserts

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts
version: 1.0

[Altinity Sink Connector] SHALL support new data inserts replication from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits
version: 1.0

[Altinity Sink Connector] SHALL support correct data inserts replication from [MySQL] to [ClickHouse] when partition 
limits are hitting or avoid such situations.

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionTypes
version: 1.0

[Altinity Sink Connector] SHALL support correct data inserts replication from [MySQL] to [ClickHouse] when the table in source table is partitioned with the following logic.

| Partition Type |
|----------------|
| RANGE          |
| LIST           |
| COLUMNS        |
| HASH           |
| KEY            |
| Subpartition   |


### Updates

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates
version: 1.0

[Altinity Sink Connector] SHALL support data updates replication from [MySQL] to [ClickHouse].

### Deletes

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes
version: 1.0

[Altinity Sink Connector] SHALL support data deletes replication from [MySQL] to [ClickHouse].

## Table Schema Creation

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation
version: 1.0

[Altinity Sink Connector]SHALL support the following ways to replicate schema from [MySQL] to [ClickHouse]:
* auto-create option
* `clickhouse_loader` script
* `chump` utility

### Auto Create

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate
version: 1.0

[Altinity Sink Connector] SHALL support auto table creation from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate
version: 1.0

[Altinity Sink Connector] SHALL support auto creation of multiple tables from [MySQL] to [ClickHouse].

### Auto Drop

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop
version: 1.0

[Altinity Sink Connector] SHALL support `DROP TABLE` query from [MySQL] to [ClickHouse].

## Alter

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter
version: 1.0

[Altinity Sink Connector] SHALL support the following `ALTER` queries.

| MySQL                                                  | ClickHouse                                                      |
|--------------------------------------------------------|-----------------------------------------------------------------|
| ADD COLUMN                                             |                                                                 |
| ADD COLUMN NULL/NOT NULL                               |                                                                 |
| ADD COLUMN DEFAULT                                     |                                                                 |
| ADD COLUMN FIRST, AFTER                                |                                                                 |
| DROP COLUMN                                            |                                                                 |
| MODIFY COLUMN data_type                                |                                                                 |
| MODIFY COLUMN data_type NULL/NOT NULL                  |                                                                 |
| MODIFY COLUMN data_type DEFAULT                        |                                                                 |
| MODIFY COLUMN FIRST, AFTER                             |                                                                 |
| MODIFY COLUMN old_name new_name datatype NULL/NOT NULL |                                                                 |
| RENAME COLUMN col1 to col2                             |                                                                 |
| CHANGE COLUMN FIRST, AFTER                             | MODIFY COLUMN                                                   |
| ALTER COLUMN col_name ADD DEFAULT                      | Not supported by grammar                                        |
| ALTER COLUMN col_name ADD DROP DEFAULT                 | Not supported by grammar                                        |
| ADD PRIMARY KEY                                        | Cannot modify primary key in CH                                 |


### Add Index

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex
version: 1.0

[Altinity Sink Connector] SHALL support `ADD INDEX` query from [MySQL] to [ClickHouse].

### Add Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey
version: 1.0

[Altinity Sink Connector] SHALL support `ADD Key` query from [MySQL] to [ClickHouse].

### Add FullText

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText
version: 1.0

[Altinity Sink Connector] SHALL support `ADD FULLTEXT` query from [MySQL] to [ClickHouse].

### Add Special

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial
version: 1.0

[Altinity Sink Connector] SHALL support `ADD SPECIAL` query from [MySQL] to [ClickHouse].

### Drop Check

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck
version: 1.0

[Altinity Sink Connector] SHALL support `DROP CHECK` query from [MySQL] to [ClickHouse].

### Drop Default

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault
version: 1.0

[Altinity Sink Connector] SHALL support `DROP DEFAULT` query from [MySQL] to [ClickHouse].

### Check

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CHECK` query from [MySQL] to [ClickHouse].

### Constraint

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CONSTRAINT` query from [MySQL] to [ClickHouse].

### Index

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER INDEX` query from [MySQL] to [ClickHouse].

### Character Set

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CHARACTER SET` query from [MySQL] to [ClickHouse].

### Convert To Character Set

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CONVERT TO CHARACTER SET` query from [MySQL] to [ClickHouse].

### Algorithm

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER ALGORITHM` query from [MySQL] to [ClickHouse].

### Force

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER FORCE` query from [MySQL] to [ClickHouse].

### Lock

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER LOCK` query from [MySQL] to [ClickHouse].

### Unlock

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER UNLOCK` query from [MySQL] to [ClickHouse].

### Validation

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER VALIDATION` query from [MySQL] to [ClickHouse].

### Columns

#### Add

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN NULL/NOT NULL` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN DEFAULT` query from [MySQL] to [ClickHouse].


##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN FIRST, AFTER` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `ADD COLUMN` query from [MySQL] to [ClickHouse].



#### Modify

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type` query from [MySQL] to [ClickHouse].


##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type NULL/NOT NULL` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type DEFAULT` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type FIRST, AFTER` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `MODIFY COLUMN` query from [MySQL] to [ClickHouse].

#### Change

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew
version: 1.0

[Altinity Sink Connector] SHALL support `CHANGE COLUMN old_name new_name datatype NULL/NOT NULL` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `CHANGE COLUMN FIRST, AFTER` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `CHANGE COLUMN` query from [MySQL] to [ClickHouse].

#### Drop

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop
version: 1.0

[Altinity Sink Connector] SHALL support `DROP COLUMN` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `DROP COLUMN` query from [MySQL] to [ClickHouse].

#### Rename

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename
version: 1.0

[Altinity Sink Connector] SHALL support `RENAME COLUMN col1 to col2` query from [MySQL] to [ClickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `RENAME COLUMN col1 to col2` query from [MySQL] to [ClickHouse].

### Add Constraint

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint
version: 1.0

[Altinity Sink Connector] SHALL support `ADD CONSTRAINT` query from [MySQL] to [ClickHouse].

### Drop Constraint

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint
version: 1.0

[Altinity Sink Connector] SHALL support `DROP CONSTRAINT` query from [MySQL] to [ClickHouse].

## Primary Key

### No Primary Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [ClickHouse] on queries to tables
with no `PRIMARY KEY`.

### Simple Primary Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [ClickHouse] on queries with the same order
as simple `PRIMARY KEY` does.

### Composite Primary Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [ClickHouse] on queries with the same order 
as composite `PRIMARY KEY` does.

## Multiple Upstream Servers

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] from multiple [MySQL] upstream servers.

## Multiple Downstream Servers

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] when using multiple downstream [ClickHouse] servers.

## Archival Mode

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with archival mode that
SHALL ignore deletes for some or all tables in [ClickHouse].

## Bootstrapping Mode

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with 
bootstrapping mode for the initial replication of very large tables
that bypasses event stream by using [MySQL] dump files.

## Binlog Position

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition
version: 1.0

[Altinity Sink Connector] SHALL support ability to start replication to [ClickHouse] 
from specific [MySQL] binlog position.

## Column Mapping And Transformation Rules

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with support for
defining column mapping and transformations rules.

## Columns Inconsistency

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] replica table when it has fewer columns.
[MySQL] replication to [ClickHouse] is not available in all other cases of columns inconsistency .

## Latency

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with latency as close as possible to real-time.

## Performance 

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] more than 100,000 rows/sec.

### Large Daily Data Volumes

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [ClickHouse] with large daily data volumes of at least 20-30TB per day.

## Settings

### clickhouse.topic2table.map

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap
version: 1.0

[Altinity Sink Connector] SHALL support `clickhouse.topic2table.map` setting for mapping [MySQL] tables to
[ClickHouse] tables that have different name.

## Table Names

### Valid

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Valid
version: 1.0

[Altinity Sink Connector] SHALL support replication of a table that was created on a source database with a name that follows the rules below.

- Names starting with a number

- Names containing spaces

- Names using reserved keywords

- Names with mixed alphanumeric characters and safe symbols

### Invalid

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Invalid
version: 1.0

[Altinity Sink Connector] SHALL not support replication and SHALL output an error when trying to replicate a table with a name which [ClickHouse] does not support.


## Column Names

### Replicate Tables With Special Column Names

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Special
version: 1.0

[Altinity Sink Connector] SHALL support replication from the source tables that have special column names.

For example,

If we create a source table that contains the column with the `is_deleted` name,

```sql
CREATE TABLE new_table(col1 VARCHAR(255), col2 INT, is_deleted INT)
```

The `ReplacingMergeTree` table created on ClickHouse side SHALL be updated and the `is_deleted` column should be renamed to  `_is_deleted` so there are no column name conflicts between ClickHouse and source table.

### Replicate Tables With Backticks in Column Names

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Backticks
version: 1.0

[Altinity Sink Connector] SHALL support replication from the source tables that have backticks in column names.

For example,

If we create a source table that contains the column with the `is_deleted` name,

```sql
CREATE TABLE new_table(col1 VARCHAR(255), `col2` INT, `is_deleted` INT)
```

## Replication Interruption

### Retry Replication When ClickHouse Instance Is Not Active

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped
version: 1.0

[Altinity Sink Connector] SHALL retry replication if the ClickHouse instance is stopped/killed during the active replication from source to destination tables. [Altinity Sink Connector] SHALL continue to retry to replicate data into a source table until the ClickHouse instance is not available again.

## Sink Connector Actions From CLI

### Commands

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI
version: 1.0

[Altinity Sink Connector] SHALL support using CLI commands to manipulate replication status in order to manage replication process more easily.
To start using CLI commands, the user should run the `sink-connector-client` script from the command line.

#### Start Replication

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StartReplication
version: 1.0

[Altinity Sink Connector] SHALL start replication process when `start_replica` command is executed.

```bash
bash-4.4# ./sink-connector-client start_replica
2024/04/05 10:57:14 Started Replication....
```

#### Stop Replication

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.StopReplication
version: 1.0

[Altiniry Sink Connector] SHALL stop replication process when `stop_replica` command is executed.

```bash
bash-4.4# ./sink-connector-client stop_replica
2024/04/05 10:57:21 ***** Stopping replication..... *****
2024/04/05 10:57:22 
2024/04/05 10:57:22 ***** Replication stopped successfully *****
```

#### Show Replication Status

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ShowReplicationStatus
version: 1.0

[Altinity Sink Connector] SHALL show replication status when `show_replica_status` command is executed.

```bash
bash-4.4# ./sink-connector-client show_replica_status
[
  {
    "Seconds_Behind_Source": 18001
  },
  {
    "Replica_Running": true
  },
  {
    "Database": "test"
  },
  {
    "record_insert_ts": 2024-04-05T06,
    "offset_key": "[\"company-1\",{\"server\":\"embeddedconnector\"}]",
    "record_insert_seq": 218,
    "id": "457678de-4759-4bb6-8720-fbdd60627eb5",
    "offset_val": "{\"ts_sec\":1712296812,\"file\":\"mysql-bin.000003\",\"pos\":197,\"gtids\":\"978f1323-f33b-11ee-b609-0242ac120003:1-56\"}"
  }
]
```

#### Change Replication Source

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.ChangeReplicationSource
version: 1.0

[Altinity Sink Connector] SHALL support updating binlog file/position and gtids by using `change_replication_source` command.

### Global Options

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.CLI.GlobalOptions
version: 1.0

[Altinity Sink Connector] SHALL support using the following global options  for the `sink-connector-client` script.

```shell
GLOBAL OPTIONS:
   --host value   Host server address of sink connector
   --port value   Port of sink connector
   --secure       If true, then use https, else http
   --help, -h     show help
   --version, -v  print the version
```

## System Actions

### Handling Network Interruptions

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Network
version: 1.0

[Altinity Sink Connector] SHALL be able to recover replication after network related interruptions happen, so that the data on the destination table is not lost.


| List of possible network related interruptions                |
|---------------------------------------------------------------|
| Internal network interruptions in source database cluster     |
| Network interruptions from source database to sink connector  |
| Network interruptions from sink connector to clickhouse       |
| Internal network interruptions in clickhouse database cluster |


### Handling Process Interruptions

#### Behaviour When Different Processes Were Killed

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Die
version: 1.0

[Altinity Sink Connector] SHALL output an error and keep the existing data when one of the following or all of the 
scenarios related to processes being killed happen:

| Scenarios Related to Processes Being Killed           |
|-------------------------------------------------------|
| Internal processes die in source database cluster     |
| Sink connector dies                                   |
| Internal processes die in clickhouse database cluster |

#### Behaviour When Different Processes Were Restarted

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Restarted
version: 1.0

[Altinity Sink Connector] SHALL continue replication without losing any data after one of the following or 
all of the scenarios related to processes being restarted happen:

| Scenarios Related to Processes Being Restarted              |
|-------------------------------------------------------------|
| Restart of some or all nodes in source database cluster     |
| Restart of sink connector                                   |
| Restart of some or all nodes in clickhouse database cluster |

### Behaviour When There Are Issues With Disk

#### Disk Is out of Space

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.OutOfSpace
version: 1.0

[Altinity Sink Connector] SHALL output an error when:

- Out of disk space on some node in source database cluster
- Out of disk space where sink connector is running
- Out of disk space on some node in clickhouse database cluster

The error SHALL be shown so that the data on the source and destination tables is not lost due to the disk related issues.

#### Disk Is Corrupted

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.Corrupted
version: 1.0

[Altinity Sink Connector] SHALL output an error when:

- Corruption on a disk used by some node in source database cluster
- Corruption on a disk where sink connector is running
- Corruption on a disk used by some node in clickhouse database cluster

The error SHALL be shown so that the data on the source and destination tables is not lost due to the disk related issues.

## Prometheus

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus
version: 1.0

[Altinity Sink Connector] SHALL support expose data transfer representation to [Prometheus] service.

## ReplicatedReplacingMergeTree

### Test Schema For ReplicatedReplacingMergeTree

```yaml
ReplicatedReplacingMergeTree:
  Clusters:
    - Cluster with multiple shards and replicas
    - Cluster with one shard and one replica
    - Secure cluster with one shard and one replica
    - Secure cluster with multiple shards and replicas
  Possible Events:
    Node Related Events:  
      - Some of the nodes where replicas are running are killed
      - All of the nodes where replicas are running are killed
      - Change of the leader node during the replication process
    Replica Related Events:  
      - One or more replicas are behind the leader replica
      - Data inconsistency between replicas
      - New replica added durin replication process
      - Replica removed during replication process
    Connection Related Event:
      - Connection between replicas is interrupted
  Disk:
    OutOfSpace:
      - Out of disk space on the disk used by one of replicas in source database cluster
    Corruptions:
      - Corruption on a disk used by one of replicas in source database cluster
```

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree
version: 1.0

[Altinity Sink Connector] SHALL support table replication from source database to the destination database and store the table as  `ReplicatedReplacingMergeTree` [ClickHouse] table engine.


In order for [ALtinity Sink Connector] to replicate a source table as `ReplicatedReplacingMergeTree` in [ClickHouse] the configuration file should contain the following setting:

```yaml
auto.create.tables.replicated: "true"
```
### Types of Clusters That Can Be Used for ReplicatedReplacingMergeTree

#### Multiple Shards and Replicas

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.MultipleShardsAndReplicas
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a cluster with multiple shards and replicas.

#### One Shard and One Replica

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.OneShardOneReplica
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a cluster with one shard and one replica.

#### Secure Cluster with One Shard and One Replica

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterOneShardOneReplica
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a secure cluster with one shard and one replica.

#### Secure Cluster with Multiple Shards and Replicas

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.ClusterTypes.SecureClusterMultipleShardsAndReplicas
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database that is stored on a secure cluster with multiple shards and replicas.

### Possible Events

#### Node Related Events

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.Killed
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when some of the nodes where replicas are running are killed.

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.AllKilled
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when all the nodes where replicas are running are killed.


##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.NodeRelatedEvents.ChangeLeader
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when the leader node is changed during the replication process.

#### Replica Related Events

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.BehindLeader
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when one or more replicas are behind the leader replica. 
Replication process from destination to source database SHALL not be interrupted in this case.

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.DataInconsistency
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when there is data inconsistency between replicas.

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.NewReplica
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when a new replica is added during the replication process.

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ReplicaRelatedEvents.RemovedReplica
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when a replica is removed during the replication process.

#### Connection Related Events

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.ConnectionRelatedEvents.Interrupted
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when the connection between replicas is interrupted.

#### Disk Related Events

##### Out of Space

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.OutOfSpace
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when one of the replicas in the source database cluster is out of disk space.

##### Corrupted Disk

###### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ReplicatedReplacingMergeTree.PossibleEvents.Disk.Corrupted
version: 1.0

[Altinity Sink Connector] SHALL support replication from source database to the destination database when one of the replicas in the source database cluster has a corrupted disk.


## Multiple Databases

### Test Schema - Multiple Databases 

```yaml
Multiple Databases:
  Source:
      structure:
          - One database on source and one database on destination
          - Multiple databases on source and multiple database on destination
          - Multiple databases on source and one database on destination
          - One database on source and multiple databases on destination
      tables:
        - Two tables with the same name and different structure on different databases
        - Two tables with the sam name and the same structure on the different databases
        - Two tables with the different name and the same structure on the different databases
        - Two tables with the different name and the different structure on the different databases
      actions:
          - Perform table operations on each database sequentially
          - Perform table operations on all databases simultaneously
          - Create database on source and map it to the database with different name on destination
          - Remove database
      configValues: 
        - Don't specify database.include.list
        - database.include.list: database1, database2, ... , databaseN
        - clickhouse.database.override.map: "test:test2, products:products2"
      TableOperations:
        - types:
            - With database name
            - Without database name
        - operations:
            - CREATE
            - INSERT
            - UPDATE
            - DELETE
            - SELECT
            - ALTER:
              - ADD COLUMN                                            
              - ADD COLUMN NULL/NOT NULL                              
              - ADD COLUMN DEFAULT                                    
              - ADD COLUMN FIRST, AFTER                               
              - DROP COLUMN                                           
              - MODIFY COLUMN data_type                               
              - MODIFY COLUMN data_type NULL/NOT NULL                 
              - MODIFY COLUMN data_type DEFAULT                       
              - MODIFY COLUMN FIRST, AFTER                            
              - MODIFY COLUMN old_name new_name datatype NULL/NOT NULL
              - RENAME COLUMN col1 to col2                            
              - CHANGE COLUMN FIRST, AFTER                            
              - ALTER COLUMN col_name ADD DEFAULT                     
              - ALTER COLUMN col_name ADD DROP DEFAULT                
              - ADD PRIMARY KEY    
  Destination:
        Engines: [ReplicatedReplacingMergeTree, ReplacingMergeTree]
        actions:
          - Remove database
          - Remove database and create it again
          - One of the databases is out if sync with source database 
```

### Databases on Source and Destination

#### Multiple Databases on Source and Destination

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases
version: 1.0

[Altinity Sink Connector] SHALL support replication of multiple databases from [MySQL] to [ClickHouse].

The implementation works as follows,
```mermaid
graph LR
    A[MySQL: customers] -->|Replicated| D[ClickHouse: customers]
    B[MySQL: products] -->|Replicated| E[ClickHouse: products]
    C[MySQL: departments] -->|Replicated| F[ClickHouse: departments]
```

#### Multiple Databases on Source and One Database on Destination

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceMultipleDestinationOne
version: 1.0

[Altinity Sink Connector] SHALL support replication of a database from source to destination when there are multiple databases on the source side and only one database on the destination side.

```mermaid
graph LR
    A[MySQL: Database 1]
    B[MySQL: Database 2] -->|Replicated| D[ClickHouse: Database 2]
    C[MySQL: Database 3]
```

#### One Database on Source and Multiple Databases on Destination

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationMultiple
version: 1.0

[Altinity Sink Connector] SHALL support replication of a database from source to destination when there is only one database on the source side and multiple databases on the destination side.

```mermaid
graph LR
    A[MySQL: Database 2] -->|Not Replicated| D[ClickHouse: Database 1]
    A -->|Replicated| E[ClickHouse: Database 2]
    A -->|Not Replicated| F[ClickHouse: Database 3]
```

#### One Database on Source and One Database on Destination

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.SourceOneDestinationOne
version: 1.0

[Altinity Sink Connector] SHALL support replication of a database from source to destination when there is only one database on the source side and only one database on the destination side.

```mermaid
graph LR
    A[MySQL: Database 1] -->|Replicated| D[ClickHouse: Database 1]
```

### Table Structure on Source and Destination Databases

#### Two Tables with the Same Name and Different Structure on Different Databases

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameDifferentStructure
version: 1.0

[Altinity Sink Connector] SHALL support replication of two tables with the same name and different structure on different databases on the source. The tables SHALL be replicated to the correct corresponding databases on the destination.

#### Two Tables with the Same Name and the Same Structure on Different Databases

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.SameNameSameStructure
version: 1.0

[Altinity Sink Connector] SHALL support replication of two tables with the same name and the same structure on different databases on the source. The tables SHALL be replicated to the correct corresponding databases on the destination.

#### Two Tables with the Different Name and the Same Structure on Different Databases

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.Tables.DifferentNameSameStructure
version: 1.0

[Altinity Sink Connector] SHALL support replication of two tables with the different name and the same structure on different databases on the source. The tables SHALL be replicated to the correct corresponding databases on the destination.

### Configuration Values

#### Include Specific List of Databases To Replicate

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.IncludeList
version: 1.0

[Altinity Sink Connector] SHALL support the usage of the `database.include.list` configuration value to specify a list of databases to replicate.

for example,
```yaml
database.include.list: database1, database2, ... , databaseN
```

This configuration value SHALL ensure that only the databases specified in the list are replicated to the destination.

#### Replicate All Databases

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.ReplicateAll
version: 1.0

[Altinity Sink Connector] SHALL support the ability to monitor all databases from the source and replicate them to the destination without specifying the `database.include.list` configuration value.

### Overriding Source To Destination Database Name Mapping

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap
version: 1.0

[Altinity Sink Connector] SHALL support the usage of the `clickhouse.database.override.map` configuration value to allow the user to replicate the data from the source database to the destination database with a different name.

For example, when using the following value in configuration,

```yaml
clickhouse.database.override.map: "mysql1:ch1"
```

The source database `mysql1` SHALL be mapped to the destination database `ch1`, and the data from the source `mysql1` SHALL only be replicated to the destination database `ch1`.

```mermaid
flowchart TD
    B[Read clickhouse.database.override.map] --> D[Identify Source Database mysql1]
    D --> E[Map to Destination Database ch1]
    E --> F[Replicate Data to ch1]
```

#### Multiple Database Names

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConfigValues.OverrideMap.MultipleValues
version: 1.0

[Altinity Sink Connector] SHALL support the usage of the `clickhouse.database.override.map` configuration value to map multiple source databases to different databases on the destination.

For example, when using the following value in configuration,

```yaml
clickhouse.database.override.map: "mysql1:ch1, mysql2:ch2"
```

The source databases `mysql1` and `mysql2` SHALL be mapped to the destination databases `ch1` and `ch2`, and the data from these source databases SHALL only be replicated to the destination databases `ch1` and `ch2`.

```mermaid
flowchart TD
    B[Read clickhouse.database.override.map]
    B --> C[Parse Override Map]
    C --> E[Map mysql1 to ch1]
    C --> F[Map mysql2 to ch2]
    E --> G[Replicate Data from mysql1 to ch1]
    F --> H[Replicate Data from mysql2 to ch2]
```

### Table Operations

#### Specify Database Name in Table Operations

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.SpecifyDatabaseName
version: 1.0

[Altinity Sink Connector] SHALL support specifying the database name in the table operations.

For example,

```sql
CREATE TABLE {database}.{table_name}
```

#### Table Operations Without Specifying Database Name

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.TableOperations.NoSpecifyDatabaseName
version: 1.0

[Altinity Sink Connector] SHALL support table operations without specifying the database name.

For example,

```sql
CREATE TABLE {table_name}
```

### Error Handling

#### When Replicated Database Does Not Exist on the Destination

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ErrorHandling.DatabaseNotExist
version: 1.0

[Altinity Sink Connector] SHALL output an error when the replicated database does not exist on the destination. The error SHALL be repeated until the database is created on the destination.

### Concurrent Actions

#### Perform Table Operations on Each Database Concurrently

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDatabases.ConcurrentActions
version: 1.0

[Altinity Sink Connector] SHALL replicate concurrently performed actions on source.

For example,
if we perform multiple alter actions on multiple databases, the actions SHALL be replicated to the destination without issues.

[SRS]: #srs
[MySQL]: #mysql
[Prometheus]: https://prometheus.io/
[ClickHouse]: https://clickhouse.com/en/docs
[Altinity Sink Connector]: https://github.com/Altinity/clickhouse-sink-connector
[Git]: https://git-scm.com/
[GitLab]: https://gitlab.com
""",
)
