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
    * 10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplacingmergetree)
        * 10.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplacingmergetreevirtualcolumnnames)
    * 10.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplicatedreplacingmergetree)
        * 10.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplicatedReplacingMergeTree.DifferentVersionColumnNames](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesreplicatedreplacingmergetreedifferentversioncolumnnames)
* 11 [Data Types](#data-types)
    * 11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypes)
    * 11.2 [Integer Types](#integer-types)
        * 11.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.IntegerTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesintegertypes)
    * 11.3 [Decimal](#decimal)
        * 11.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Decimal](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdecimal)
    * 11.4 [Double](#double)
        * 11.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Double](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdouble)
    * 11.5 [DateTime](#datetime)
        * 11.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesdatetime)
    * 11.6 [Binary](#binary)
        * 11.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesbinary)
    * 11.7 [String](#string)
        * 11.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesstring)
    * 11.8 [Blob Types](#blob-types)
        * 11.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesblobtypes)
    * 11.9 [Nullable](#nullable)
        * 11.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesnullable)
    * 11.10 [Enum](#enum)
        * 11.10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesenumtoenum)
        * 11.10.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesenumtostring)
    * 11.11 [JSON](#json)
        * 11.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesjson)
    * 11.12 [Year](#year)
        * 11.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesyear)
    * 11.13 [Bytes](#bytes)
        * 11.13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes](#rqsrs-030clickhousemysqltoclickhousereplicationdatatypesbytes)
* 12 [Queries](#queries)
    * 12.1 [Inserts](#inserts)
        * 12.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesinserts)
            * 12.1.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesinsertspartitionlimits)
    * 12.2 [Updates](#updates)
        * 12.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesupdates)
    * 12.3 [Deletes](#deletes)
        * 12.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes](#rqsrs-030clickhousemysqltoclickhousereplicationqueriesdeletes)
* 13 [Table Schema Creation](#table-schema-creation)
    * 13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreation)
    * 13.2 [Auto Create](#auto-create)
        * 13.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationautocreate)
            * 13.2.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationmultipleautocreate)
    * 13.3 [Auto Drop](#auto-drop)
        * 13.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop](#rqsrs-030clickhousemysqltoclickhousereplicationtableschemacreationautodrop)
* 14 [Alter](#alter)
    * 14.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter](#rqsrs-030clickhousemysqltoclickhousereplicationalter)
    * 14.2 [Add Index](#add-index)
        * 14.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddIndex](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddindex)
    * 14.3 [Add Key](#add-key)
        * 14.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddkey)
    * 14.4 [Add FullText](#add-fulltext)
        * 14.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddfulltext)
    * 14.5 [Add Special](#add-special)
        * 14.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddspecial)
    * 14.6 [Drop Check](#drop-check)
        * 14.6.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropcheck)
    * 14.7 [Drop Default](#drop-default)
        * 14.7.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropdefault)
    * 14.8 [Check](#check)
        * 14.8.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check](#rqsrs-030clickhousemysqltoclickhousereplicationaltercheck)
    * 14.9 [Constraint](#constraint)
        * 14.9.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint](#rqsrs-030clickhousemysqltoclickhousereplicationalterconstraint)
    * 14.10 [Index](#index)
        * 14.10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index](#rqsrs-030clickhousemysqltoclickhousereplicationalterindex)
    * 14.11 [Character Set](#character-set)
        * 14.11.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet](#rqsrs-030clickhousemysqltoclickhousereplicationaltercharacterset)
    * 14.12 [Convert To Character Set](#convert-to-character-set)
        * 14.12.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet](#rqsrs-030clickhousemysqltoclickhousereplicationalterconverttocharacterset)
    * 14.13 [Algorithm](#algorithm)
        * 14.13.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm](#rqsrs-030clickhousemysqltoclickhousereplicationalteralgorithm)
    * 14.14 [Force](#force)
        * 14.14.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force](#rqsrs-030clickhousemysqltoclickhousereplicationalterforce)
    * 14.15 [Lock](#lock)
        * 14.15.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock](#rqsrs-030clickhousemysqltoclickhousereplicationalterlock)
    * 14.16 [Unlock](#unlock)
        * 14.16.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock](#rqsrs-030clickhousemysqltoclickhousereplicationalterunlock)
    * 14.17 [Validation](#validation)
        * 14.17.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation](#rqsrs-030clickhousemysqltoclickhousereplicationaltervalidation)
    * 14.18 [Columns](#columns)
        * 14.18.1 [Add](#add)
            * 14.18.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsadd)
            * 14.18.1.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddnullnotnull)
            * 14.18.1.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsadddefault)
            * 14.18.1.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddfirstafter)
            * 14.18.1.5 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsaddmultiple)
        * 14.18.2 [Modify](#modify)
            * 14.18.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodify)
            * 14.18.2.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifynullnotnull)
            * 14.18.2.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifydefault)
            * 14.18.2.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifyfirstafter)
            * 14.18.2.5 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsmodifymultiple)
        * 14.18.3 [Change](#change)
            * 14.18.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangenullnotnulloldnew)
            * 14.18.3.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangefirstafter)
            * 14.18.3.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnschangemultiple)
        * 14.18.4 [Drop](#drop)
            * 14.18.4.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsdrop)
            * 14.18.4.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsdropmultiple)
        * 14.18.5 [Rename](#rename)
            * 14.18.5.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsrename)
            * 14.18.5.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple](#rqsrs-030clickhousemysqltoclickhousereplicationaltercolumnsrenamemultiple)
    * 14.19 [Add Constraint](#add-constraint)
        * 14.19.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint](#rqsrs-030clickhousemysqltoclickhousereplicationalteraddconstraint)
    * 14.20 [Drop Constraint](#drop-constraint)
        * 14.20.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint](#rqsrs-030clickhousemysqltoclickhousereplicationalterdropconstraint)
* 15 [Primary Key](#primary-key)
    * 15.1 [No Primary Key](#no-primary-key)
        * 15.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeyno)
    * 15.2 [Simple Primary Key](#simple-primary-key)
        * 15.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeysimple)
    * 15.3 [Composite Primary Key](#composite-primary-key)
        * 15.3.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite](#rqsrs-030clickhousemysqltoclickhousereplicationprimarykeycomposite)
* 16 [Multiple Upstream Servers](#multiple-upstream-servers)
    * 16.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipleupstreamservers)
* 17 [Multiple Downstream Servers](#multiple-downstream-servers)
    * 17.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers](#rqsrs-030clickhousemysqltoclickhousereplicationmultipledownstreamservers)
* 18 [Archival Mode](#archival-mode)
    * 18.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode](#rqsrs-030clickhousemysqltoclickhousereplicationarchivalmode)
* 19 [Bootstrapping Mode](#bootstrapping-mode)
    * 19.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode](#rqsrs-030clickhousemysqltoclickhousereplicationbootstrappingmode)
* 20 [Binlog Position](#binlog-position)
    * 20.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition](#rqsrs-030clickhousemysqltoclickhousereplicationbinlogposition)
* 21 [Column Mapping And Transformation Rules](#column-mapping-and-transformation-rules)
    * 21.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnmappingandtransformationrules)
* 22 [Columns Inconsistency](#columns-inconsistency)
    * 22.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnsinconsistency)
* 23 [Latency](#latency)
    * 23.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency](#rqsrs-030clickhousemysqltoclickhousereplicationlatency)
* 24 [Performance ](#performance-)
    * 24.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance](#rqsrs-030clickhousemysqltoclickhousereplicationperformance)
    * 24.2 [Large Daily Data Volumes](#large-daily-data-volumes)
        * 24.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes](#rqsrs-030clickhousemysqltoclickhousereplicationperformancelargedailydatavolumes)
* 25 [Settings](#settings)
    * 25.1 [clickhouse.topic2table.map](#clickhousetopic2tablemap)
        * 25.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Settings.Topic2TableMap](#rqsrs-030clickhousemysqltoclickhousereplicationsettingstopic2tablemap)
* 26 [Table Names](#table-names)
    * 26.1 [Valid](#valid)
        * 26.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Valid](#rqsrs-030clickhousemysqltoclickhousereplicationtablenamesvalid)
    * 26.2 [Invalid](#invalid)
        * 26.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableNames.Invalid](#rqsrs-030clickhousemysqltoclickhousereplicationtablenamesinvalid)
* 27 [Column Names](#column-names)
    * 27.1 [Replicate Tables With Special Column Names](#replicate-tables-with-special-column-names)
        * 27.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnNames.Special](#rqsrs-030clickhousemysqltoclickhousereplicationcolumnnamesspecial)
* 28 [Replication Interruption](#replication-interruption)
    * 28.1 [Retry Replication When ClickHouse Instance Is Not Active](#retry-replication-when-clickhouse-instance-is-not-active)
        * 28.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped](#rqsrs-030clickhousemysqltoclickhousereplicationinterruptionclickhouseinstancestopped)
* 29 [Prometheus ](#prometheus-)
    * 29.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus](#rqsrs-030clickhousemysqltoclickhousereplicationprometheus)

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
Services:
  - Source Database Cluster
  - Sink Connector
  - ClickHouse Database Cluster

SourceTables:
  DatabaseType: [MySQL, PostgreSQL, MariaDB, MongoDB]
  DatabaseVersions:
    MySQL: [8.0]
    PostgreSQL: null
    MariaDB: null
    MongoDB: null
  DatabaseClusterConfiguration:
    MySQL: null
    PostgreSQL: null
    MariaDB: null
    MongoDB: null
  EngineType:
    MySQL: [InnoDB]
    PostgreSQL: null
    MariaDB: null
    MongoDB: null
  Schema:
    TableName:
      length: 
        MySQL: 64 characters
        PostgreSQL: null
        MariaDB: null
        MongoDB: null
      encodings:
          ASCII: null
          UTF-8: null
          otherEncodings: [armscii8, big5, binary, cp1250, cp1251, cp1256, cp1257, cp850, cp852, cp866, cp932, dec8, 
                         eucjpms, euckr, gb18030, gb2312, gbk, geostd8, greek, hebrew, hp8, keybcs2, koi8r, koi8u, latin1, 
                         latin2, latin5, latin7, macce, macroman, sjis, swe7, tis620, ucs2, ujis, utf16, utf16le, utf32, utf8mb3, utf8mb4]
    Partitioning:
      MySQL: [RANGE, LIST, COLUMNS, HASH, KEY, Subpartitioning]
    Columns:
      DefaultValues: [Numeric Types, Date and Time Types, String Types, ENUM Types, SET Types, BOOLEAN, Binary Types]
      Type:
        MySQL: [Calculated columns, Materialized columns, Primary Key Columns, Foreign Key Columns, Index Columns, Unique Columns, Auto-Increment Columns, Timestamp/DateTime Columns, ENUM and SET Columns, Spatial Columns]
      Name:
        length:
          mysql: null
          PostgreSQL: null
          MariaDB: null
          MongoDB: null
        ASCII: null
        UTF-8: null
        otherEncodings: [armscii8, big5, binary, cp1250, cp1251, cp1256, cp1257, cp850, cp852, cp866, cp932, dec8, 
                         eucjpms, euckr, gb18030, gb2312, gbk, geostd8, greek, hebrew, hp8, keybcs2, koi8r, koi8u, latin1, 
                         latin2, latin5, latin7, macce, macroman, sjis, swe7, tis620, ucs2, ujis, utf16, utf16le, utf32, utf8mb3, utf8mb4]
      DataType:
        - DECIMAL(2,1) NOT NULL
        - DECIMAL(30, 10) NOT NULL
        - DOUBLE NOT NULL
        - DATE NOT NULL
        - DATETIME(6) NOT NULL
        - TIME NOT NULL
        - TIME(6) NOT NULL
        - INT NOT NULL
        - INT NOT NULL
        - INT UNSIGNED NOT NULL
        - INT UNSIGNED NOT NULL
        - BIGINT NOT NULL
        - BIGINT NOT NULL
        - BIGINT UNSIGNED NOT NULL
        - BIGINT UNSIGNED NOT NULL
        - TINYINT NOT NULL
        - TINYINT NOT NULL
        - TINYINT UNSIGNED NOT NULL
        - TINYINT UNSIGNED NOT NULL
        - SMALLINT NOT NULL
        - SMALLINT NOT NULL
        - SMALLINT UNSIGNED NOT NULL
        - SMALLINT UNSIGNED NOT NULL
        - MEDIUMINT NOT NULL
        - MEDIUMINT NOT NULL
        - MEDIUMINT UNSIGNED NOT NULL
        - MEDIUMINT UNSIGNED NOT NULL
        - CHAR NOT NULL
        - TEXT NOT NULL
        - VARCHAR(4) NOT NULL
        - BLOB NOT NULL
        - MEDIUMBLOB NOT NULL
        - LONGBLOB NOT NULL
        - BINARY NOT NULL
        - VARBINARY(4) NOT NULL
      DataValue:
        Numeric: [Min, Max, 0, -infinity, +infinity, nan, random value]
        Decimal: [Min value based on precision and scale, Max value based on precision and scale, 0, -0.0001, "0.0001", -Max value based on precision and scale, +Max value based on precision and scale, "NaN", A random value within precision and scale]
        String:
          bytes: [null bytes, ...]
          UTF-8: []
          ASCII: []

TableOperations:
  MySQL:
    - INSERT
    - UPDATE
    - DELETE
    - SELECT
    - ALTERs:
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
  MariaDB: null
  PostgreSQL: null
  MongoDB: null

SinkConnector:
  Version: [latest]
  Configuration: The full list is inside configurations below

DestinationTables:
  DatabaseType: [ClickHouse]
  DatabaseClusterConfiguration: null
  DatabaseVersion: [22.8, 23.3, 23,11]
  EngineType: [ReplacingMergeTree, ReplicatedReplacingMergeTree]
  Schema: null
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

[Altinity Sink Connector] SHALL support consistent data replication from [MySQL] to [CLickHouse].

### Multiple MySQL Masters

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Consistency.MultipleMySQLMasters

[Altinity Sink Connector] SHALL support consistent data replication from [MySQL] to [CLickHouse] when one or more MySQL
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

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with only-once guarantee.
Block level de-duplication SHALL be used if it is going to replicated tables
but the publisher SHALL publish only once.

The following cases SHALL be supported:

1. [MySQL] database crash
2. [MySQL] database event stream provider crash
3. [MySQL] restart
3. [ClickHouse] server crash
4. [Clickhouse] server restart
7. [Debezium] server crash
8. [Debezium] server restart
9. [Altinity Sink Connector] server crash
10. [Altinity Sink Connector] server restart
11. [Schemaregistry] server crash
12. [Schemaregistry] server restart
13. [Zookeeper] read only mode

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

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use "InnoDB" [MySQL] storage engine to
"ReplacingMergeTree" [ClickHouse] table engine.

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ReplacingMergeTree.VirtualColumnNames
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

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with
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

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with
'Double' data types as they supported by [MySQL].

Data types connection table:

| MySQL        |  ClickHouse  |
|:-------------|:------------:|
| Double       |   Float64    |

### DateTime

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.DateTime
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

### Binary

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Binary
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] replication of tables that contain columns with 'Binary'
data types as they supported by [MySQL].

Data types connection table:

| MySQL        |              ClickHouse              |
|:-------------|:------------------------------------:|
| Binary       |             String + hex             |
| varbinary(*) |             String + hex             |

### String

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.String
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

### Blob Types

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.BlobTypes
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

### Nullable

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Nullable
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with NULL [MySQL]
data types if this expected `Nullable(DataType)` construction should be used.

For example, [MySQL] `VARCHAR(*)` maps to [ClickHouse] `Nullable(String)` and MySQL
`VARCHAR(*) NOT NULL` maps to [ClickHouse] `String`

### Enum

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToEnum
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| ENUM  |    ENUM    |

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.EnumToString
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'ENUM'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| ENUM  |   String   |

### JSON

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.JSON
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'JSON'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| JSON  |   String   |

### Year

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Year
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'Year'
data types as they supported by [MySQL].

Data types connection table:

| MySQL | ClickHouse |
|:------|:----------:|
| Year  |   Int32    |

### Bytes

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.DataTypes.Bytes
version: 1.0

[Altinity Sink Connector] SHALL support data replication to [CLickHouse] of tables that contain columns with 'BIT(m)'
data types where m: 2 - 64 as they supported by [MySQL].

Data types connection table:

| MySQL  | ClickHouse |
|:-------|:----------:|
| BIT(m) |   String   |


## Queries

### Inserts

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts
version: 1.0

[Altinity Sink Connector] SHALL support new data inserts replication from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Inserts.PartitionLimits
version: 1.0

[Altinity Sink Connector] SHALL support correct data inserts replication from [MySQL] to [CLickHouse] when partition 
limits are hitting or avoid such situations.


### Updates

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Updates
version: 1.0

[Altinity Sink Connector] SHALL support data updates replication from [MySQL] to [CLickHouse].

### Deletes

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Queries.Deletes
version: 1.0

[Altinity Sink Connector] SHALL support data deletes replication from [MySQL] to [CLickHouse].

## Table Schema Creation

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation
version: 1.0

[Altinity Sink Connector]SHALL support the following ways to replicate schema from [MySQL] to [CLickHouse]:
* auto-create option
* `clickhouse_loader` script
* `chump` utility

### Auto Create

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoCreate
version: 1.0

[Altinity Sink Connector] SHALL support auto table creation from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.MultipleAutoCreate
version: 1.0

[Altinity Sink Connector] SHALL support auto creation of multiple tables from [MySQL] to [CLickHouse].

### Auto Drop

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.TableSchemaCreation.AutoDrop
version: 1.0

[Altinity Sink Connector] SHALL support `DROP TABLE` query from [MySQL] to [CLickHouse].

## Alter

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter
version: 1.0

[Altinity Sink Connector] SHALL support all `ALTER` queries.

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

[Altinity Sink Connector] SHALL support `ADD INDEX` query from [MySQL] to [CLickHouse].

### Add Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddKey
version: 1.0

[Altinity Sink Connector] SHALL support `ADD Key` query from [MySQL] to [CLickHouse].

### Add FullText

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddFullText
version: 1.0

[Altinity Sink Connector] SHALL support `ADD FULLTEXT` query from [MySQL] to [CLickHouse].

### Add Special

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddSpecial
version: 1.0

[Altinity Sink Connector] SHALL support `ADD SPECIAL` query from [MySQL] to [CLickHouse].

### Drop Check

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropCheck
version: 1.0

[Altinity Sink Connector] SHALL support `DROP CHECK` query from [MySQL] to [CLickHouse].

### Drop Default

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropDefault
version: 1.0

[Altinity Sink Connector] SHALL support `DROP DEFAULT` query from [MySQL] to [CLickHouse].

### Check

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Check
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CHECK` query from [MySQL] to [CLickHouse].

### Constraint

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Constraint
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CONSTRAINT` query from [MySQL] to [CLickHouse].

### Index

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Index
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER INDEX` query from [MySQL] to [CLickHouse].

### Character Set

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.CharacterSet
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CHARACTER SET` query from [MySQL] to [CLickHouse].

### Convert To Character Set

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.ConvertToCharacterSet
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER CONVERT TO CHARACTER SET` query from [MySQL] to [CLickHouse].

### Algorithm

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Algorithm
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER ALGORITHM` query from [MySQL] to [CLickHouse].

### Force

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Force
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER FORCE` query from [MySQL] to [CLickHouse].

### Lock

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Lock
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER LOCK` query from [MySQL] to [CLickHouse].

### Unlock

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Unlock
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER UNLOCK` query from [MySQL] to [CLickHouse].

### Validation

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Validation
version: 1.0

[Altinity Sink Connector] SHALL support `ALTER VALIDATION` query from [MySQL] to [CLickHouse].

### Columns

#### Add

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.NullNotNull
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN NULL/NOT NULL` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Default
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN DEFAULT` query from [MySQL] to [CLickHouse].


##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `ADD COLUMN FIRST, AFTER` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Add.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `ADD COLUMN` query from [MySQL] to [CLickHouse].



#### Modify

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type` query from [MySQL] to [CLickHouse].


##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.NullNotNull
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type NULL/NOT NULL` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Default
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type DEFAULT` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `MODIFY COLUMN data_type FIRST, AFTER` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Modify.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `MODIFY COLUMN` query from [MySQL] to [CLickHouse].

#### Change

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.NullNotNullOldNew
version: 1.0

[Altinity Sink Connector] SHALL support `CHANGE COLUMN old_name new_name datatype NULL/NOT NULL` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.FirstAfter
version: 1.0

[Altinity Sink Connector] SHALL support `CHANGE COLUMN FIRST, AFTER` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Change.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `CHANGE COLUMN` query from [MySQL] to [CLickHouse].

#### Drop

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop
version: 1.0

[Altinity Sink Connector] SHALL support `DROP COLUMN` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Drop.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `DROP COLUMN` query from [MySQL] to [CLickHouse].

#### Rename

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename
version: 1.0

[Altinity Sink Connector] SHALL support `RENAME COLUMN col1 to col2` query from [MySQL] to [CLickHouse].

##### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.Columns.Rename.Multiple
version: 1.0

[Altinity Sink Connector] SHALL support multiple `RENAME COLUMN col1 to col2` query from [MySQL] to [CLickHouse].

### Add Constraint

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.AddConstraint
version: 1.0

[Altinity Sink Connector] SHALL support `ADD CONSTRAINT` query from [MySQL] to [CLickHouse].

### Drop Constraint

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Alter.DropConstraint
version: 1.0

[Altinity Sink Connector] SHALL support `DROP CONSTRAINT` query from [MySQL] to [CLickHouse].

## Primary Key

### No Primary Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.No
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries to tables
with no `PRIMARY KEY`.

### Simple Primary Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Simple
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order
as simple `PRIMARY KEY` does.

### Composite Primary Key

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.PrimaryKey.Composite
version: 1.0

[Altinity Sink Connector] query SHALL support [MySQL] data replication to [CLickHouse] on queries with the same order 
as composite `PRIMARY KEY` does.

## Multiple Upstream Servers

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleUpstreamServers
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] from multiple [MySQL] upstream servers.

## Multiple Downstream Servers

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MultipleDownstreamServers
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] when using multiple downstream [ClickHouse] servers.

## Archival Mode

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ArchivalMode
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with archival mode that
SHALL ignore deletes for some or all tables in [ClickHouse].

## Bootstrapping Mode

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BootstrappingMode
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with 
bootstrapping mode for the initial replication of very large tables
that bypasses event stream by using [MySQL] dump files.

## Binlog Position

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.BinlogPosition
version: 1.0

[Altinity Sink Connector] SHALL support ability to start replication to [CLickHouse] 
from specific [MySQL] binlog position.

## Column Mapping And Transformation Rules

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnMappingAndTransformationRules
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with support for
defining column mapping and transformations rules.

## Columns Inconsistency

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.ColumnsInconsistency
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] replica table when it has fewer columns.
[MySQL] replication to [CLickHouse] is not available in all other cases of columns inconsistency .

## Latency

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Latency
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with latency as close as possible to real-time.

## Performance 

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] more than 100,000 rows/sec.

### Large Daily Data Volumes

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Performance.LargeDailyDataVolumes
version: 1.0

[Altinity Sink Connector] SHALL support [MySQL] replication to [CLickHouse] with large daily data volumes of at least 20-30TB per day.

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

## Replication Interruption

### Retry Replication When ClickHouse Instance Is Not Active

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped
version: 1.0

[Altinity Sink Connector] SHALL retry replication if the ClickHouse instance is stopped/killed during the active replication from source to destination tables. [Altinity Sink Connector] SHALL continue to retry to replicate data into a source table until the ClickHouse instance is not available again.

## Prometheus 

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus
version: 1.0

[Altinity Sink Connector] SHALL support expose data transfer representation to [Prometheus] service.

[SRS]: #srs
[MySQL]: #mysql
[Prometheus]: https://prometheus.io/
[ClickHouse]: https://clickhouse.com/en/docs
[Altinity Sink Connector]: https://github.com/Altinity/clickhouse-sink-connector
[Git]: https://git-scm.com/
[GitLab]: https://gitlab.com
