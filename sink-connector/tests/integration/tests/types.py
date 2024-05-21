from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def check_datatype_replication(
    self,
    mysql_type,
    ch_type,
    values,
    ch_values,
    clickhouse_table,
    nullable=False,
    hex_type=False,
):
    """Check replication of a given MySQL data type."""
    table_name = f"types_{getuid()}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    mysql_columns = f"MyData {mysql_type}{' NOT NULL' if not nullable else ''}"
    clickhouse_columns = (
        f"{f'MyData Nullable({ch_type})' if nullable else f'MyData {ch_type}'}"
    )

    init_sink_connector(
        auto_create_tables=clickhouse_table[0], topics=f"SERVER5432.test.{table_name}"
    )

    with Given(f"I create MySql to CH replicated table", description=table_name):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
            clickhouse_columns=clickhouse_columns,
            clickhouse_table=clickhouse_table,
        )

    with When(f"I insert data in MySql table {table_name}"):
        for i, value in enumerate(values, 1):
            mysql.query(f"INSERT INTO {table_name} VALUES ({i}, {value})")
            with Then(f"I make check that ClickHouse table has same dataset"):
                retry(clickhouse.query, timeout=50, delay=1)(
                    f"SELECT id,{'unhex(MyData)' if hex_type else 'MyData'} FROM test.{table_name} FINAL FORMAT CSV",
                    message=f"{ch_values[i - 1]}",
                )


@TestOutline(Feature)
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
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table=clickhouse_table,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values  nullable",
    [
        ("DATE", "Date32", ["'2012-12-12'"], ['"2012-12-12"'], False),
        ("TIME", "String", ["'17:51:04.777'"], ['"17:51:05.000000"'], False),
        ("TIME(6)", "String", ["'17:51:04.777'"], ['"17:51:04.777000"'], False),
        ("DATE", "Date32", ["NULL"], ["\\N"], True),
        ("TIME", "String", ["NULL"], ["\\N"], True),
        ("TIME(6)", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_DateTime("1.0")
)
def date_time(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'DATE' and 'TIME' data type."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table=clickhouse_table,
            )


@TestOutline(Feature)
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
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table=clickhouse_table,
            )


@TestOutline(Feature)
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
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table=clickhouse_table,
            )


@TestOutline(Feature)
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
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table=clickhouse_table,
                hex_type=True,
            )


@TestOutline(Feature)
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
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table=clickhouse_table,
                hex_type=True,
            )


@TestOutline(Feature)
@Examples(
    "mysql_type ch_type values ch_values nullable",
    [
        ("ENUM('hello','world')", "String", ["'hello'"], ['"hello"'], False),
        ("ENUM('hello','world')", "String", ["NULL"], ["\\N"], True),
    ],
)
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_EnumToString("1.0")
)
def enum(self, mysql_type, ch_type, values, ch_values, nullable):
    """Check replication of MySQl 'ENUM' data types."""
    for clickhouse_table in available_clickhouse_tables:
        with Example({clickhouse_table}, flags=TE):
            check_datatype_replication(
                mysql_type=mysql_type,
                ch_type=ch_type,
                values=values,
                ch_values=ch_values,
                nullable=nullable,
                clickhouse_table=clickhouse_table,
            )


@TestModule
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_DataTypes_Nullable("1.0")
)
@Name("types")
def module(self):
    """Verify correct replication of all supported MySQL data types."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    with Pool(1) as executor:
        try:
            for feature in loads(current_module(), Feature):
                Feature(test=feature, parallel=True, executor=executor)()
        finally:
            join()
