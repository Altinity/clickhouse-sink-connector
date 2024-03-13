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
    * 10.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.InnoDB](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesinnodb)
    * 10.2 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MyISAM](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesmyisam)
    * 10.3 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MEMORY](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesmemory)
    * 10.4 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CSV](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginescsv)
    * 10.5 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ARCHIVE](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesarchive)
    * 10.6 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.BLACKHOLE](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesblackhole)
    * 10.7 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.FEDERATED](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesfederated)
    * 10.8 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.EXAMPLE](#rqsrs-030clickhousemysqltoclickhousereplicationmysqlstorageenginesexample)
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
* 29 [Replication Interruption](#replication-interruption)
    * 29.1 [Retry Replication When ClickHouse Instance Is Not Active](#retry-replication-when-clickhouse-instance-is-not-active)
        * 29.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped](#rqsrs-030clickhousemysqltoclickhousereplicationinterruptionclickhouseinstancestopped)
* 30 [System Actions](#system-actions)
    * 30.1 [Handling Network Interruptions](#handling-network-interruptions)
        * 30.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Network](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsnetwork)
    * 30.2 [Handling Process Interruptions](#handling-process-interruptions)
        * 30.2.1 [Behaviour When Different Processes Were Killed](#behaviour-when-different-processes-were-killed)
            * 30.2.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Die](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsprocessdie)
        * 30.2.2 [Behaviour When Different Processes Were Restarted](#behaviour-when-different-processes-were-restarted)
            * 30.2.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Process.Restarted](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsprocessrestarted)
    * 30.3 [Behaviour When There Are Issues With Disk](#behaviour-when-there-are-issues-with-disk)
        * 30.3.1 [Disk Is out of Space](#disk-is-out-of-space)
            * 30.3.1.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.OutOfSpace](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsdiskoutofspace)
        * 30.3.2 [Disk Is Corrupted](#disk-is-corrupted)
            * 30.3.2.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.SystemActions.Disk.Corrupted](#rqsrs-030clickhousemysqltoclickhousereplicationsystemactionsdiskcorrupted)
* 31 [Prometheus](#prometheus)
    * 31.1 [RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Prometheus](#rqsrs-030clickhousemysqltoclickhousereplicationprometheus)

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
            - MySQL Cluster (NDB Cluster)
            - InnoDB Cluster
            - Replication Clusters
            - Galera Cluster
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

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.InnoDB
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'InnoDB' storage engine in MySQL:

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MyISAM
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'MyISAM' storage engine in MySQL:

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.MEMORY
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'MEMORY' storage engine in MySQL:

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.CSV
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'CSV' storage engine in MySQL:

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.ARCHIVE
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'ARCHIVE' storage engine in MySQL:

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.BLACKHOLE
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'BLACKHOLE' storage engine in MySQL:

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.FEDERATED
version: 1.0

[Altinity Sink Connector] SHALL support replication of tables that use the 'FEDERATED' storage engine in MySQL:

### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.MySQLStorageEngines.EXAMPLE
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

## Replication Interruption

### Retry Replication When ClickHouse Instance Is Not Active

#### RQ.SRS-030.ClickHouse.MySQLToClickHouseReplication.Interruption.ClickHouse.Instance.Stopped
version: 1.0

[Altinity Sink Connector] SHALL retry replication if the ClickHouse instance is stopped/killed during the active replication from source to destination tables. [Altinity Sink Connector] SHALL continue to retry to replicate data into a source table until the ClickHouse instance is not available again.

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

[SRS]: #srs
[MySQL]: #mysql
[Prometheus]: https://prometheus.io/
[ClickHouse]: https://clickhouse.com/en/docs
[Altinity Sink Connector]: https://github.com/Altinity/clickhouse-sink-connector
[Git]: https://git-scm.com/
[GitLab]: https://gitlab.com
