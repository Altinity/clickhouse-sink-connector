# These requirements were auto generated
# from software requirements specification (SRS)
# document by TestFlows v1.9.220712.1163352.
# Do not edit by hand but re-generate instead
# using 'tfs requirements generate' command.
from testflows.core import Specification
from testflows.core import Requirement

Heading = Specification.Heading

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of single or multiple tables from [MySQL] database.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.1.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support data deduplication when it receives the same data twice from [MySQL].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.2.2.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Select = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[ClickHouse] `SELECT ... FINAL` query SHALL always return exactly same data as [MySQL].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.2.3.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_OnlyOnceGuarantee = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with only-once guarantee.\n'
        'Block level de-duplication SHALL be used if it is going to replicated tables\n'
        'but the publisher SHALL publish only once.\n'
        '\n'
        'The following cases SHALL be supported:\n'
        '\n'
        '1. [MySQL] database crash\n'
        '2. [MySQL] database event stream provider crash\n'
        '3. [MySQL] restart\n'
        '3. [ClickHouse] server crash\n'
        '4. [Clickhouse] server restart\n'
        '5. [Kafka] server crash\n'
        '6. [Kafka] server restart\n'
        '7. [Debezium] server crash\n'
        '8. [Debezium] server restart\n'
        '9. [Altinity Sink Connector] server crash\n'
        '10. [Altinity Sink Connector] server restart\n'
        '11. [Schemaregistry] server crash\n'
        '12. [Schemaregistry] server restart\n'
        '13. [Zookeeper] read only mode\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.2.4.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Transactions = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support transactions for replicated [MySQL] tables.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.3.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLVersions = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        ' \n'
        '[Altinity Sink Connector] SHALL support replication from the following [MySQL] versions:\n'
        '\n'
        '* [MySQL 8.0](https://dev.mysql.com/doc/refman/8.0/en/)\n'
        '\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.4.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_ = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree ',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to\n'
        '"ReplacingMergeTree" [ClickHouse] table engine.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.5.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to\n'
        '"ReplacingMergeTree" [ClickHouse] table engine and virtual column names should be "_version" and "_sign".\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.5.1.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_CollapsingMergeTree_ = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CollapsingMergeTree ',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to\n'
        '"CollapsingMergeTree" [ClickHouse] table engine.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.5.2'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_IntegerTypes = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support data replication to [ClickHouse] of tables that contain columns with\n'
        "'Integer' data types as they supported by [MySQL].\n"
        '\n'
        'Integer data types connection table:\n'
        '\n'
        '| MySQL              |           ClickHouse            | Min edge value | Max edge value |\n'
        '|:-------------------|:-------------------------------:|----------------|:--------------:|\n'
        '| Bigint             |              Int64              | 2^63           |     2^63-1     |\n'
        '| Bigint Unsigned    |             UInt64              | 0              |     2^64-1     |\n'
        '| Int                |              Int32              | -2147483648    |   2147483647   |\n'
        '| Int Unsigned       |             UInt32              | 0              |   4294967295   |\n'
        '| Mediumint          |              Int32              | -8388608       |    8388607     |\n'
        '| Mediumint Unsigned |             UInt32              | 0              |    16777215    |\n'
        '| Smallint           |              Int16              | -32768         |     32767      |\n'
        '| Smallint Unsigned  |             UInt16              | 0              |     65535      |\n'
        '| Tinyint            |              Int8               | -128           |      127       |\n'
        '| Tinyint Unsigned   |              UInt8              | 0              |      255       |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.1.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Decimal = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with\n'
        "'Decimal' data types as they supported by [MySQL].\n"
        '\n'
        "[ClickHouse]'s 'Decimal32(S)', 'Decimal64(S)', 'Decimal128(S)', 'Decimal256(S)' also can be\n"
        "manually used for [MySQL] 'Decimal'.\n"
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL        |  ClickHouse  |\n'
        '|:-------------|:------------:|\n'
        '| Decimal(x,y) | Decimal(x,y) |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.2.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Double = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with\n'
        "'Double' data types as they supported by [MySQL].\n"
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL        |  ClickHouse  |\n'
        '|:-------------|:------------:|\n'
        '| Double       |   Float64    |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.3.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Data' and 'Time'\n"
        'data types as they supported by [MySQL].\n'
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL       |       ClickHouse        |\n'
        '|:------------|:-----------------------:|\n'
        '| Date        |     Date or String      |\n'
        '| DateTime(6) | DateTime64(6) or String |\n'
        '| DATETIME    |        DateTime         |\n'
        '| Time        |         String          |\n'
        '| Time(6)     |         String          |\n'
        '| Timestamp   |        DateTime         |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.4.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Binary = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] replication of tables that contain columns with 'Binary'\n"
        'data types as they supported by [MySQL].\n'
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL        |              ClickHouse              |\n'
        '|:-------------|:------------------------------------:|\n'
        '| Binary       |             String + hex             |\n'
        '| varbinary(*) |             String + hex             |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.5.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_String = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'String'\n"
        'data types as they supported by [MySQL].\n'
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL        |           ClickHouse            |\n'
        '|:-------------|:-------------------------------:|\n'
        '| Char         | String / LowCardinality(String) |\n'
        '| Text         |             String              |\n'
        '| varbinary(*) |          String + hex           |\n'
        '| varchar(*)   |             String              |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.6.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_BlobTypes = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Blob' [MySQL]\n"
        'data types and correctly unhex() them.\n'
        '\n'
        '```sql\n'
        'SELECT unhex(blob_column) FROM our_table FORMAT CSV;\n'
        '```\n'
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL        |           ClickHouse            |\n'
        '|:-------------|:-------------------------------:|\n'
        '| Blob         |          String + hex           |\n'
        '| Longblob     |          String + hex           |\n'
        '| Mediumblob   |          String + hex           |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.7.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Nullable = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with NULL [MySQL]\n'
        'data types if this expected `Nullable(DataType)` construction should be used.\n'
        '\n'
        'For example, [MySQL] `VARCHAR(*)` maps to [ClickHouse] `Nullable(String)` and MySQL\n'
        '`VARCHAR(*) NOT NULL` maps to [ClickHouse] `String`\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.8.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToEnum = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'\n"
        'data types as they supported by [MySQL].\n'
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL | ClickHouse |\n'
        '|:------|:----------:|\n'
        '| ENUM  |    ENUM    |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.9.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToString = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'\n"
        'data types as they supported by [MySQL].\n'
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL | ClickHouse |\n'
        '|:------|:----------:|\n'
        '| ENUM  |   String   |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.9.2'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_JSON = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        "[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'JSON'\n"
        'data types as they supported by [MySQL].\n'
        '\n'
        'Data types connection table:\n'
        '\n'
        '| MySQL | ClickHouse |\n'
        '|:------|:----------:|\n'
        '| JSON  |    JSON    |\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.6.10.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Inserts = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Inserts',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support new data inserts replication from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.1.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Updates = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Updates',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support data updates replication from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.2.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Deletes = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Deletes',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support data deletes replication from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.3.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_AutoCreateTable = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoCreateTable',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support auto table creation from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.4.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_AutoDropTable = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoDropTable',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support `DROP TABLE` query from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.5.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ModifyColumn = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ModifyColumn',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support `MODIFY COLUMN` query from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.6.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_AddColumn = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AddColumn',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support `ADD COLUMN` query from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.7.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_RemoveColumn = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.RemoveColumn',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support `REMOVE COLUMN` query from [MySQL] to [CLickHouse].\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.7.8.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_NoPrimaryKey = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.NoPrimaryKey',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries to tables\n'
        'with no `PRIMARY KEY`.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.8.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order\n'
        'as simple `PRIMARY KEY` does.\n'
        '\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.8.2'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Composite = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order \n'
        'as composite `PRIMARY KEY` does.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.8.3'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleUpstreamServers = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] from multiple [MySQL] upstream servers.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.9.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDownstreamServers = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] when using multiple downstream [ClickHouse] servers.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.10.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ArchivalMode = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with archival mode that\n'
        'SHALL ignore deletes for some or all tables in [ClickHouse].\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.11.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BootstrappingMode = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with \n'
        'bootstrapping mode for the initial replication of very large tables\n'
        'that bypasses event stream by using [MySQL] dump files.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.12.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BinlogPosition = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support ability to start replication to [CLickHouse] \n'
        'from specific [MySQL] binlog position.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.13.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnMappingAndTransformationRules = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with support for\n'
        'defining column mapping and transformations rules.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.14.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Latency = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with latency as close as possible to real-time.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.15.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] more than 100,000 rows/sec.\n'
        '\n'
    ),
    link=None,
    level=3,
    num='4.16.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance_LargeDailyDataVolumes = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with large daily data volumes of at least 20-30TB per day.\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.16.2.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Settings_Topic2TableMap = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support `clickhouse.topic2table.map` setting for mapping [MySQL] tables to\n'
        '[ClickHouse] tables that have different name.\n'
        '\n'
    ),
    link=None,
    level=4,
    num='4.17.1.1'
)

RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Prometheus = Requirement(
    name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus',
    version='1.0',
    priority=None,
    group=None,
    type=None,
    uid=None,
    description=(
        '[Altinity Sink Connector] SHALL support expose data transfer representation to [Prometheus] service.\n'
        '\n'
        '[SRS]: #srs\n'
        '[MySQL]: #mysql\n'
        '[Prometheus]: https://prometheus.io/\n'
        '[ClickHouse]: https://clickhouse.com/en/docs\n'
        '[Altinity Sink Connector]: https://github.com/Altinity/clickhouse-sink-connector\n'
        '[Git]: https://git-scm.com/\n'
        '[GitLab]: https://gitlab.com\n'
    ),
    link=None,
    level=3,
    num='4.18.1'
)

SRS030_MySQL_to_ClickHouse_Replication = Specification(
    name='SRS030 MySQL to ClickHouse Replication',
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
        Heading(name='Introduction', level=1, num='1'),
        Heading(name='Terminology', level=1, num='2'),
        Heading(name='SRS', level=2, num='2.1'),
        Heading(name='MySQL', level=2, num='2.2'),
        Heading(name='Feature Diagram', level=1, num='3'),
        Heading(name='Requirements', level=1, num='4'),
        Heading(name='General', level=2, num='4.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication', level=3, num='4.1.1'),
        Heading(name='Consistency', level=2, num='4.2'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency', level=3, num='4.2.1'),
        Heading(name='Deduplication', level=3, num='4.2.2'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication', level=4, num='4.2.2.1'),
        Heading(name='Selects', level=3, num='4.2.3'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select', level=4, num='4.2.3.1'),
        Heading(name='Only Once Guarantee', level=3, num='4.2.4'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee', level=4, num='4.2.4.1'),
        Heading(name='Transactions', level=2, num='4.3'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions', level=3, num='4.3.1'),
        Heading(name='Supported Versions', level=2, num='4.4'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions', level=3, num='4.4.1'),
        Heading(name='Supported Storage Engines', level=2, num='4.5'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree ', level=3, num='4.5.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames', level=4, num='4.5.1.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CollapsingMergeTree ', level=3, num='4.5.2'),
        Heading(name='Data Types', level=2, num='4.6'),
        Heading(name='Integer Types', level=3, num='4.6.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes', level=4, num='4.6.1.1'),
        Heading(name='Decimal', level=3, num='4.6.2'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal', level=4, num='4.6.2.1'),
        Heading(name='Double', level=3, num='4.6.3'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double', level=4, num='4.6.3.1'),
        Heading(name='DateTime', level=3, num='4.6.4'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime', level=4, num='4.6.4.1'),
        Heading(name='Binary', level=3, num='4.6.5'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary', level=4, num='4.6.5.1'),
        Heading(name='String', level=3, num='4.6.6'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String', level=4, num='4.6.6.1'),
        Heading(name='Blob Types', level=3, num='4.6.7'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes', level=4, num='4.6.7.1'),
        Heading(name='Nullable', level=3, num='4.6.8'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable', level=4, num='4.6.8.1'),
        Heading(name='Enum', level=3, num='4.6.9'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum', level=4, num='4.6.9.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString', level=4, num='4.6.9.2'),
        Heading(name='JSON', level=3, num='4.6.10'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON', level=4, num='4.6.10.1'),
        Heading(name='Queries', level=2, num='4.7'),
        Heading(name='Inserts', level=3, num='4.7.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Inserts', level=4, num='4.7.1.1'),
        Heading(name='Updates', level=3, num='4.7.2'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Updates', level=4, num='4.7.2.1'),
        Heading(name='Deletes', level=3, num='4.7.3'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Deletes', level=4, num='4.7.3.1'),
        Heading(name='Auto Create Table', level=3, num='4.7.4'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoCreateTable', level=4, num='4.7.4.1'),
        Heading(name='Auto Drop Table', level=3, num='4.7.5'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoDropTable', level=4, num='4.7.5.1'),
        Heading(name='Modify Column', level=3, num='4.7.6'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ModifyColumn', level=4, num='4.7.6.1'),
        Heading(name='Add Column', level=3, num='4.7.7'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AddColumn', level=4, num='4.7.7.1'),
        Heading(name='Remove Column', level=3, num='4.7.8'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.RemoveColumn', level=4, num='4.7.8.1'),
        Heading(name='Primary Key', level=2, num='4.8'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.NoPrimaryKey', level=3, num='4.8.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple', level=3, num='4.8.2'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite', level=3, num='4.8.3'),
        Heading(name='Multiple Upstream Servers', level=2, num='4.9'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers', level=3, num='4.9.1'),
        Heading(name='Multiple Downstream Servers', level=2, num='4.10'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers', level=3, num='4.10.1'),
        Heading(name='Archival Mode', level=2, num='4.11'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode', level=3, num='4.11.1'),
        Heading(name='Bootstrapping Mode', level=2, num='4.12'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode', level=3, num='4.12.1'),
        Heading(name='Binlog Position', level=2, num='4.13'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition', level=3, num='4.13.1'),
        Heading(name='Column Mapping And Transformation Rules', level=2, num='4.14'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules', level=3, num='4.14.1'),
        Heading(name='Latency', level=2, num='4.15'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency', level=3, num='4.15.1'),
        Heading(name='Performance ', level=2, num='4.16'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance', level=3, num='4.16.1'),
        Heading(name='Large Daily Data Volumes', level=3, num='4.16.2'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes', level=4, num='4.16.2.1'),
        Heading(name='Settings', level=2, num='4.17'),
        Heading(name='clickhouse.topic2table.map', level=3, num='4.17.1'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap', level=4, num='4.17.1.1'),
        Heading(name='Prometheus ', level=2, num='4.18'),
        Heading(name='RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus', level=3, num='4.18.1'),
        ),
    requirements=(
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Deduplication,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Select,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_OnlyOnceGuarantee,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Transactions,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLVersions,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree_VirtualColumnNames,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_CollapsingMergeTree_,
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
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Inserts,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Updates,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Deletes,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_AutoCreateTable,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_AutoDropTable,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ModifyColumn,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_AddColumn,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_RemoveColumn,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_NoPrimaryKey,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Composite,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleUpstreamServers,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDownstreamServers,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ArchivalMode,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BootstrappingMode,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_BinlogPosition,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnMappingAndTransformationRules,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Latency,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Performance_LargeDailyDataVolumes,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Settings_Topic2TableMap,
        RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Prometheus,
        ),
    content='''
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
    * 4.2.2 [Deduplication](#deduplication)
      * 4.2.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Deduplication](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencydeduplication)
    * 4.2.3 [Selects](#selects)
      * 4.2.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.Select](#rqsrs-030clickhousemysqltoclickhousereplicationconsistencyselect)
    * 4.2.4 [Only Once Guarantee](#only-once-guarantee)
      * 4.2.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.OnlyOnceGuarantee](#rqsrs-030clickhousemysqltoclickhousereplicationonlyonceguarantee)
  * 4.3 [Transactions](#transactions)
    * 4.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Transactions](#rqsrs-030clickhousemysqltoclickhousereplicationtransactions)
  * 4.4 [Supported Versions](#supported-versions)
    * 4.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLVersions](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlversions)
  * 4.5 [Supported Storage Engines](#supported-storage-engines)
    * 4.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree ](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplacingmergetree-)
      * 4.5.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplacingmergetreevirtualcolumnnames)
    * 4.5.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CollapsingMergeTree ](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginescollapsingmergetree-)
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
  * 4.7 [Queries](#queries)
    * 4.7.1 [Inserts](#inserts)
      * 4.7.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Inserts](#rqsrs-030clickhousemysqltoclickhousereplicationinserts)
    * 4.7.2 [Updates](#updates)
      * 4.7.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Updates](#rqsrs-030clickhousemysqltoclickhousereplicationupdates)
    * 4.7.3 [Deletes](#deletes)
      * 4.7.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Deletes](#rqsrs-030clickhousemysqltoclickhousereplicationdeletes)
    * 4.7.4 [Auto Create Table](#auto-create-table)
      * 4.7.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoCreateTable](#rqsrs-030clickhousemysqltoclickhousereplicationautocreatetable)
    * 4.7.5 [Auto Drop Table](#auto-drop-table)
      * 4.7.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoDropTable](#rqsrs-030clickhousemysqltoclickhousereplicationautodroptable)
    * 4.7.6 [Modify Column](#modify-column)
      * 4.7.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ModifyColumn](#rqsrs-030clickhousemysqltoclickhousereplicationmodifycolumn)
    * 4.7.7 [Add Column](#add-column)
      * 4.7.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AddColumn](#rqsrs-030clickhousemysqltoclickhousereplicationaddcolumn)
    * 4.7.8 [Remove Column](#remove-column)
      * 4.7.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.RemoveColumn](#rqsrs-030clickhousemysqltoclickhousereplicationremovecolumn)
  * 4.8 [Primary Key](#primary-key)
    * 4.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.NoPrimaryKey](#rqsrs-030clickhousemysqltoclickhousereplicationnoprimarykey)
    * 4.8.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeysimple)
    * 4.8.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeycomposite)
  * 4.9 [Multiple Upstream Servers](#multiple-upstream-servers)
    * 4.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipleupstreamservers)
  * 4.10 [Multiple Downstream Servers](#multiple-downstream-servers)
    * 4.10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledownstreamservers)
  * 4.11 [Archival Mode](#archival-mode)
    * 4.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode](#rqsrs-030clickhousemysqltoclickhousereplicationarchivalmode)
  * 4.12 [Bootstrapping Mode](#bootstrapping-mode)
    * 4.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode](#rqsrs-030clickhousemysqltoclickhousereplicationbootstrappingmode)
  * 4.13 [Binlog Position](#binlog-position)
    * 4.13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition](#rqsrs-030clickhousemysqltoclickhousereplicationbinlogposition)
  * 4.14 [Column Mapping And Transformation Rules](#column-mapping-and-transformation-rules)
    * 4.14.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnmappingandtransformationrules)
  * 4.15 [Latency](#latency)
    * 4.15.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency](#rqsrs-030clickhousemysqltoclickhousereplicationlatency)
  * 4.16 [Performance ](#performance-)
    * 4.16.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance](#rqsrs-030clickhousemysqltoclickhousereplicationperformance)
    * 4.16.2 [Large Daily Data Volumes](#large-daily-data-volumes)
      * 4.16.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes](#rqsrs-030clickhousemysqltoclickhousereplicationperformancelargedailydatavolumes)
  * 4.17 [Settings](#settings)
    * 4.17.1 [clickhouse.topic2table.map](#clickhousetopic2tablemap)
      * 4.17.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap](#rqsrs-030clickhousemysqltoclickhousereplicationsettingstopic2tablemap)
  * 4.18 [Prometheus ](#prometheus-)
    * 4.18.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus](#rqsrs-030clickhousemysqltoclickhousereplicationprometheus)

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
"ReplacingMergeTree" [ClickHouse] table engine and virtual column names should be "_version" and "_sign".

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CollapsingMergeTree 
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"CollapsingMergeTree" [ClickHouse] table engine.

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
| Date        |     Date or String      |
| DateTime(6) | DateTime64(6) or String |
| DATETIME    |        DateTime         |
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
| JSON  |    JSON    |

### Queries

#### Inserts

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Inserts
version: 1.0

[Altinity Sink Connector] SHALL support new data inserts replication from [MySQL] to [CLickHouse].

#### Updates

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Updates
version: 1.0

[Altinity Sink Connector] SHALL support data updates replication from [MySQL] to [CLickHouse].

#### Deletes

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Deletes
version: 1.0

[Altinity Sink Connector] SHALL support data deletes replication from [MySQL] to [CLickHouse].

#### Auto Create Table

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoCreateTable
version: 1.0

[Altinity Sink Connector] SHALL support auto table creation from [MySQL] to [CLickHouse].

#### Auto Drop Table

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AutoDropTable
version: 1.0

[Altinity Sink Connector] SHALL support `DROP TABLE` query from [MySQL] to [CLickHouse].

#### Modify Column

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ModifyColumn
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN` query from [MySQL] to [CLickHouse].

#### Add Column

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.AddColumn
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN` query from [MySQL] to [CLickHouse].

#### Remove Column

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.RemoveColumn
version: 1.0

[Altinity Sink Connector] SHALL support `REMOVE COLUMN` query from [MySQL] to [CLickHouse].

### Primary Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.NoPrimaryKey
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries to tables
with no `PRIMARY KEY`.

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order
as simple `PRIMARY KEY` does.


#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite
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
'''
)
