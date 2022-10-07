import time

from testflows.core import *
from mysql_to_clickhouse_replication.requirements import *
from mysql_to_clickhouse_replication.tests.steps import *
from helpers.common import *


@TestOutline
def check_datatype_replication(
    self,
    mysql_type,
    ch_type,
    values,
    ch_values,
    nullable=False,
    hex_type=False,
    auto_create_tables=False,
):
    """Check replication of a given MySQL data type."""
    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=auto_create_tables, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE IF NOT EXISTS {table_name} "
            f"(id INT AUTO_INCREMENT,"
            f"MyData {mysql_type}{' NOT NULL' if not nullable else ''},"
            f" PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )

    if not auto_create_tables:
        with And(f"I create ClickHouse replica test.{table_name}"):
            create_clickhouse_table(
                name=table_name,
                statement=f"CREATE TABLE IF NOT EXISTS test.{table_name} "
                f"(id Int32,{f'MyData Nullable({ch_type})' if nullable else f'MyData {ch_type}'}, sign "
                f"Int8, ver UInt64) "
                f"ENGINE = ReplacingMergeTree(ver) "
                f"PRIMARY KEY id ORDER BY id SETTINGS "
                f"index_granularity = 8192;",
            )

    with When(f"I insert data in MySql table {table_name}"):
        for i, value in enumerate(values, 1):
            mysql.query(f"INSERT INTO {table_name} VALUES ({i}, {value})")
            with Then(f"I make check that ClickHouse table has same dataset"):
                retry(clickhouse.query, timeout=50, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FINAL FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("DECIMAL(2,1)", "DECIMAL(2,1)", ["2/3"], ["0.7"], False),
        ("DECIMAL(30, 10)", "DECIMAL(30, 10)", ["1.232323233"], ["1.232323233"], False),
        ("DECIMAL(9, 5)", "DECIMAL32(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(18, 5)", "DECIMAL64(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(38, 5)", "DECIMAL128(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(65, 5)", "DECIMAL256(5)", ["1.23"], ["1.23"], False),
        ("DECIMAL(2,1)", "DECIMAL(2,1)", ["NULL"], ["\\N"], True),
        ("DECIMAL(30, 10)", "DECIMAL(30, 10)", ["NULL"], ["\\N"], True),
        ("DECIMAL(9, 5)", "DECIMAL32(5)", ["NULL"], ["\\N"], True),
        ("DECIMAL(18, 5)", "DECIMAL64(5)", ["NULL"], ["\\N"], True),
        ("DECIMAL(38, 5)", "DECIMAL128(5)", ["NULL"], ["\\N"], True),
        ("DECIMAL(65, 5)", "DECIMAL256(5)", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Decimal("1.0")
)
def decimal(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'DECIMAL' data types."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
    )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("DOUBLE", "Float64", ["999.00009"], ["999.00009"], False),
        ("DOUBLE", "Float64", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Double("1.0")
)
@Requirements()
def double(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'DOUBLE' data type."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
    )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values  nullable",
    [
        ("DATE", "Date", ["'2012-12-12'"], ['"2012-12-12"'], False),
        (
            "DATETIME(6)",
            "String",
            ["'2018-09-08 17:51:04.777'"],
            ['"2018-09-08 17:51:04.777000"'],
            False,
        ),
        ("TIME", "String", ["'17:51:04.777'"], ['"17:51:05"'], False),
        ("TIME(6)", "String", ["'17:51:04.777'"], ['"17:51:04.777000"'], False),
        ("DATE", "Date", ["NULL"], ["\\N"], True),
        ("DATETIME(6)", "String", ["NULL"], ["\\N"], True),
        ("TIME", "String", ["NULL"], ["\\N"], True),
        ("TIME(6)", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime("1.0")
)
def date_time(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'DATE' and 'TIME' data type."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
    )


@TestOutline(Scenario)
# @Repeat(3)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        (
            "INT",
            "Int32",
            ["-2147483648", "0", "2147483647"],
            ["-2147483648", "0", "2147483647"],
            False,
        ),
        ("INT UNSIGNED", "UInt32", ["0", "4294967295"], ["0", "4294967295"], False),
        (
            "BIGINT",
            "Int64",
            ["-9223372036854775808", "0", "9223372036854775807"],
            ["-9223372036854775808", "0", "9223372036854775807"],
            False,
        ),
        (
            "BIGINT UNSIGNED",
            "UInt64",
            ["0", "18446744073709551615"],
            ["0", "18446744073709551615"],
            False,
        ),
        ("TINYINT", "Int8", ["-128", "127"], ["-128", "127"], False),
        ("TINYINT UNSIGNED", "UInt8", ["0", "255"], ["0", "255"], False),
        (
            "SMALLINT",
            "Int16",
            ["-32768", "0", "32767"],
            ["-32768", "0", "32767"],
            False,
        ),
        ("SMALLINT UNSIGNED", "UInt16", ["0", "65535"], ["0", "65535"], False),
        (
            "MEDIUMINT",
            "Int32",
            ["-8388608", "0", "8388607"],
            ["-8388608", "0", "8388607"],
            False,
        ),
        ("MEDIUMINT UNSIGNED", "UInt32", ["0", "16777215"], ["0", "16777215"], False),
        ("INT", "Int32", ["NULL"], ["\\N"], True),
        ("INT UNSIGNED", "UInt32", ["NULL"], ["\\N"], True),
        ("BIGINT", "Int64", ["NULL"], ["\\N"], True),
        ("BIGINT UNSIGNED", "UInt64", ["NULL"], ["\\N"], True),
        ("TINYINT", "Int8", ["NULL"], ["\\N"], True),
        ("TINYINT UNSIGNED", "UInt8", ["NULL"], ["\\N"], True),
        ("SMALLINT", "Int16", ["NULL"], ["\\N"], True),
        ("SMALLINT UNSIGNED", "UInt16", ["NULL"], ["\\N"], True),
        ("MEDIUMINT", "Int32", ["NULL"], ["\\N"], True),
        ("MEDIUMINT UNSIGNED", "UInt32", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_IntegerTypes("1.0")
)
def integer_types(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'INT' data types."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
    )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("CHAR", "LowCardinality(String)", ["'x'"], ['"x"'], False),
        ("TEXT", "String", ["'some_text'"], ['"some_text"'], False),
        ("VARCHAR(4)", "String", ["'IVAN'"], ['"IVAN"'], False),
        ("CHAR", "String", ["NULL"], ["\\N"], True),
        ("TEXT", "String", ["NULL"], ["\\N"], True),
        ("VARCHAR(4)", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_String("1.0")
)
def string(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'STRING' data types."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
    )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("BLOB", "String", ["'some_blob'"], ['"some_blob"'], False),
        ("MEDIUMBLOB", "String", ["'x_Mediumblob'"], ['"x_Mediumblob"'], False),
        (
            "LONGBLOB",
            "String",
            ["'some_Longblobblobblob'"],
            ['"some_Longblobblobblob"'],
            False,
        ),
        ("BLOB", "String", ["NULL"], ["\\N"], True),
        ("MEDIUMBLOB", "String", ["NULL"], ["\\N"], True),
        ("LONGBLOB", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_BlobTypes("1.0")
)
def blob(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'BLOB' data types."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
        hex_type=True,
    )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("BINARY", "String", ["'a'"], ['"a"'], False),
        ("VARBINARY(4)", "String", ["'IVAN'"], ['"IVAN"'], False),
        ("BINARY", "String", ["NULL"], ["\\N"], True),
        ("VARBINARY(4)", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Binary("1.0")
)
def binary(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'BINARY' data types."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
        hex_type=True,
    )


@TestOutline(Scenario)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("ENUM('hello','world')", "String", ["'hello'"], ['"hello"'], False),
        ("ENUM('hello','world')", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToString("1.0"))
def enum(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'ENUM' data types."""
    check_datatype_replication(
        mysql_type=mysql_type,
        ch_type=ch_type,
        values=values,
        ch_values=ch_values,
        nullable=nullable,
    )


@TestFeature
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Nullable("1.0")
)
@Name("data types")
def feature(self):
    """Verify correct replication of all supported MySQL data types."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
